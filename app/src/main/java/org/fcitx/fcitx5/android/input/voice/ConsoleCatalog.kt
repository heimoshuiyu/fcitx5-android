/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Catalog of Console-hosted models usable for voice transcription. The
 * authenticated inference model list decides what is usable and carries the
 * audio/chat capabilities; the managed provider's config entry only refines
 * a model with its base URL, headers, body overlays and variants — a
 * regular member's config contains overlays, not the full model list, so
 * catalog models absent from the config are served on the default base URL
 * with the default variant.
 *
 * The first successful fetch is cached in memory; transcription reuses it
 * and only refetches after [reset] (sign-in/sign-out) or process restart.
 */
object ConsoleCatalog {

    /** A hosted model offered for selection. */
    data class ModelEntry(
        /** Request id (`provider/model`), also the persisted selection key. */
        val id: String,
        val displayName: String,
        /** The wire protocol this app drives for the model. */
        val protocol: String,
        /** Variant ids in config order; the implicit default variant is not listed. */
        val variants: List<String>,
        val baseUrl: String,
        val providerHeaders: Map<String, String>?,
        val providerBody: JsonObject?,
        val modelHeaders: Map<String, String>?,
        val modelBody: JsonObject?,
        val variantsById: Map<String, ConsoleApi.ConsoleVariant>,
    )

    /** A model plus a chosen variant, ready to be merged into a request. */
    data class Resolved(
        val model: String,
        val protocol: String,
        val baseUrl: String,
        val headers: Map<String, String>,
        val bodyOverlay: JsonObject,
    )

    @Volatile
    private var cached: List<ModelEntry>? = null

    fun reset() {
        cached = null
    }

    /**
     * Return the catalog, fetching config and the inference model list on
     * first use. Throws [ConsoleApi.ConsoleHttpException] on network errors.
     */
    suspend fun ensure(accessToken: String, orgId: String?): List<ModelEntry> {
        cached?.let { return it }
        val entries = buildEntries(
            ConsoleApi.fetchConfig(accessToken, orgId),
            ConsoleApi.fetchInferenceModels(accessToken, orgId),
        )
        cached = entries
        return entries
    }

    /**
     * Build the selectable entries from the inference model list, joined with
     * the managed provider's config entry where it has something to say about
     * a model. The list serves the managed OpenCode connection, so the
     * workspace's other (BYOK) providers never affect it — not even a model
     * they mark disabled. The managed entry is an overlay source, not a
     * gate: an audio-capable model it never mentions is still offered; only
     * an entry marking the model disabled excludes it.
     *
     * Each entry carries the protocol the app can drive and the base URL for
     * it: a config overlay wins, else the model's models.dev route for that
     * protocol, else the protocol's Console default.
     */
    fun buildEntries(
        config: ConsoleApi.ConfigDocument,
        models: List<ConsoleApi.InferenceModel>,
    ): List<ModelEntry> {
        // `/inference/v1/models` serves the managed OpenCode connection, so
        // only that provider's entries refine these models. Flattening every
        // provider's models into one map would let a BYOK provider's disabled
        // flag or overlays hide or redirect a managed model.
        val managed = config.providers[ConsoleApi.MANAGED_PROVIDER_KEY]
        val configModels = HashMap<String, Pair<ConsoleApi.ConsoleProvider, ConsoleApi.ConsoleModel>>()
        managed?.models?.forEach { (key, model) ->
            val id = model.modelID?.takeIf { it.isNotBlank() } ?: key
            // Disabled entries stay in the map so the filter below can
            // exclude them.
            configModels[id] = managed to model
        }
        return models.asSequence()
            // Chat models publish `type: "chat"` in models.dev v2; other kinds
            // (realtime, image, video, …) are not usable for transcription.
            .filter { it.type == null || it.type == "chat" }
            .filter { it.status != "deprecated" }
            .filter { model -> configModels[model.id]?.second?.disabled != true }
            .mapNotNull { model ->
                val protocol = model.protocolFor() ?: return@mapNotNull null
                if (!model.supportsAudioInput()) return@mapNotNull null
                val configPair = configModels[model.id]
                val provider = configPair?.first
                val configModel = configPair?.second
                ModelEntry(
                    id = model.id,
                    displayName = model.name ?: configModel?.name ?: model.id,
                    protocol = protocol,
                    variants = configModel?.variants.orEmpty().map { it.id },
                    baseUrl = provider?.baseURL?.takeIf { it.isNotBlank() }
                        ?: model.baseUrlFor(protocol)?.takeIf { it.isNotBlank() }
                        ?: when (protocol) {
                            ConsoleApi.CHAT_COMPLETIONS_PROTOCOL -> ConsoleApi.DEFAULT_CHAT_BASE
                            else -> ConsoleApi.DEFAULT_GOOGLE_BASE
                        },
                    providerHeaders = provider?.headers,
                    providerBody = provider?.body,
                    modelHeaders = configModel?.headers,
                    modelBody = configModel?.body,
                    variantsById = configModel?.variants.orEmpty().associateBy { it.id },
                )
            }
            .sortedBy { it.id }
            .toList()
    }

    /**
     * Merge config overlays for a variant selection. [variantId] empty means
     * the model default; an id missing from the entry throws, so a stale
     * selection surfaces instead of silently changing behavior.
     */
    fun resolve(entry: ModelEntry, variantId: String): Resolved {
        val variant = when {
            variantId.isBlank() -> null
            else -> entry.variantsById[variantId]
                ?: throw TranscriptionException(
                    "Variant \"$variantId\" of ${entry.id} is not available. Open voice settings to choose another one."
                )
        }
        return Resolved(
            model = entry.id,
            protocol = entry.protocol,
            baseUrl = entry.baseUrl.trimEnd('/'),
            headers = mergeConsoleHeaders(
                entry.providerHeaders,
                entry.modelHeaders,
                variant?.headers,
            ),
            bodyOverlay = mergeConsoleBody(entry.providerBody, entry.modelBody, variant?.body),
        )
    }

    /**
     * Recursive JSON object merge, later overlays win per key — the same rule
     * the reference client's overlay merge uses: when both sides are objects
     * the values merge recursively, otherwise the overlay value replaces the
     * base value.
     */
    fun mergeConsoleBody(vararg overlays: JsonObject?): JsonObject {
        return overlays.filterNotNull().fold(JsonObject(emptyMap())) { acc, next ->
            mergeConsoleBodyRecords(acc, next)
        }
    }

    private fun mergeConsoleBodyRecords(base: JsonObject, overlay: JsonObject): JsonObject {
        val merged = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        (base.keys + overlay.keys).forEach { key ->
            val left = base[key]
            val right = overlay[key]
            when {
                right == null -> merged[key] = left!!
                left is JsonObject && right is JsonObject ->
                    merged[key] = mergeConsoleBodyRecords(left, right)
                else -> merged[key] = right
            }
        }
        return buildJsonObject { merged.forEach { (key, value) -> put(key, value) }
        }
    }

    /**
     * Case-insensitive header merge, later overlays win and the winning
     * entry's original key casing is kept — mirrors the reference client's
     * header merge.
     */
    fun mergeConsoleHeaders(vararg maps: Map<String, String>?): Map<String, String> {
        val merged = LinkedHashMap<String, Pair<String, String>>()
        maps.forEach { map ->
            map?.forEach { (key, value) -> merged[key.lowercase()] = key to value }
        }
        val result = LinkedHashMap<String, String>()
        merged.values.forEach { (originalKey, value) -> result[originalKey] = value }
        return result
    }
}
