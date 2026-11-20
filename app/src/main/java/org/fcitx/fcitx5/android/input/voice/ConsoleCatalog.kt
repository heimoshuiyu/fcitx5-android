/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Catalog of Console-hosted models usable for voice transcription, one group
 * per channel (connection) the workspace can use: the managed OpenCode
 * connection, a Go subscription's, and the workspace's own (BYOK)
 * connections. The per-channel model catalogs decide what is usable and carry
 * the audio/chat capabilities and the channel's own base URL; the config
 * entry of the same channel only refines a model with headers, body overlays
 * and variants — a regular member's config contains overlays, not the full
 * model list, so catalog models absent from the config are served with the
 * default variant.
 *
 * Each [Kind] builds its own entry list from the same channel catalogs: the
 * voice flow drives the protocols it implements and needs audio input, the
 * hotword agent drives Chat Completions on the fixed managed endpoint for
 * every channel and needs tools. The first successful fetch per kind is
 * cached in memory; callers reuse it and only refetch after [reset]
 * (sign-in/sign-out) or process restart.
 */
object ConsoleCatalog {

    /** Which request surface a catalog serves. */
    enum class Kind {
        /** Voice transcription: only models with audio input and a drivable protocol. */
        Voice,

        /** The hotword agent: tool-capable models on the fixed managed chat endpoint. */
        Agent,
    }

    /** A hosted model offered for selection, on one channel of the workspace. */
    data class ModelEntry(
        /**
         * The persisted selection key: `providerKey/modelId`. Unique across
         * channels, so a selection can never route to another channel.
         */
        val id: String,
        /** The channel (connection) the model is served on. */
        val providerKey: String,
        /** The channel's label for group headers and the selection summary. */
        val channelName: String,
        /** The bare model id the wire request names. */
        val modelId: String,
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
        /** The bare model id the wire request names on the channel's base URL. */
        val model: String,
        val protocol: String,
        val baseUrl: String,
        val headers: Map<String, String>,
        val bodyOverlay: JsonObject,
    )

    @Volatile
    private var cachedVoice: List<ModelEntry>? = null

    @Volatile
    private var cachedAgent: List<ModelEntry>? = null

    fun reset() {
        cachedVoice = null
        cachedAgent = null
    }

    /**
     * Return the voice catalog, fetching config and the per-channel model
     * catalogs on first use. Throws [ConsoleApi.ConsoleHttpException] on
     * network errors.
     */
    suspend fun ensure(accessToken: String, orgId: String?): List<ModelEntry> =
        ensure(accessToken, orgId, Kind.Voice)

    /**
     * Return the catalog of one [Kind], fetching config and the per-channel
     * model catalogs on first use. Each kind caches separately so one
     * surface's filter never decides what the other offers.
     */
    suspend fun ensure(accessToken: String, orgId: String?, kind: Kind): List<ModelEntry> {
        when (kind) {
            Kind.Voice -> cachedVoice?.let { return it }
            Kind.Agent -> cachedAgent?.let { return it }
        }
        val entries = buildEntries(
            ConsoleApi.fetchConfig(accessToken, orgId),
            ConsoleApi.fetchChannelModels(accessToken, orgId),
            kind,
        )
        when (kind) {
            Kind.Voice -> cachedVoice = entries
            Kind.Agent -> cachedAgent = entries
        }
        return entries
    }

    /**
     * Build the selectable entries from the per-channel model catalogs, each
     * joined with its own channel's config entry where that channel has
     * something to say about a model. Overlays never cross channels: a
     * channel's entry only refines its own models, and a model disabled on
     * one channel stays selectable on another. Channel models disabled on
     * their own channel are already absent from the catalog the server
     * publishes; a config entry marking one disabled still excludes it.
     *
     * Each entry carries the protocol the app can drive and the base URL for
     * it: the channel's published route for the model and protocol wins,
     * else the channel provider's config base URL, else the protocol's
     * Console default. Agent entries instead always carry the fixed managed
     * Chat Completions endpoint the agent speaks for every channel.
     */
    fun buildEntries(
        config: ConsoleApi.ConfigDocument,
        channels: Map<String, ConsoleApi.ChannelModels>,
    ): List<ModelEntry> = buildEntries(config, channels, Kind.Voice)

    fun buildEntries(
        config: ConsoleApi.ConfigDocument,
        channels: Map<String, ConsoleApi.ChannelModels>,
        kind: Kind,
    ): List<ModelEntry> {
        // Two channels may publish the same display name (for example the
        // same BYOK provider connected twice); the provider key disambiguates
        // both the group headers and the selection summary.
        val nameCounts = channels.values
            .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
            .groupingBy { it }
            .eachCount()
        fun channelLabel(providerKey: String, channel: ConsoleApi.ChannelModels): String {
            val name = channel.name?.takeIf { it.isNotBlank() }
            return when {
                name == null -> providerKey
                (nameCounts[name] ?: 0) > 1 -> "$name ($providerKey)"
                else -> name
            }
        }
        // The managed OpenCode connection first, then the other channels by
        // display name and key for a stable order.
        val orderedChannels = channels.entries.sortedWith(
            compareBy(
                { it.key != ConsoleApi.MANAGED_PROVIDER_KEY },
                { (it.value.name ?: it.key).lowercase() },
                { it.key },
            )
        )
        return orderedChannels.flatMap { (providerKey, channel) ->
            // Only this channel's config entry refines its models. Joining
            // every provider's models into one map would let one channel's
            // disabled flag or overlays hide or redirect another's model.
            // The channel's own provider-level headers and body apply to
            // every model it serves; model and variant overlays do not.
            val provider = config.providers[providerKey]
            val configModels = HashMap<String, ConsoleApi.ConsoleModel>()
            provider?.models?.forEach { (key, model) ->
                val id = model.modelID?.takeIf { it.isNotBlank() } ?: key
                // Disabled entries stay in the map so the filter below can
                // exclude them.
                configModels[id] = model
            }
            channel.models.entries.asSequence()
                .sortedBy { it.key }
                // Chat models publish `type: "chat"` in models.dev v2; other
                // kinds (realtime, image, video, …) serve neither surface.
                .filter { (_, model) -> model.type == null || model.type == "chat" }
                .filter { (_, model) -> model.status != "deprecated" }
                .filter { (modelId, model) -> configModels[modelId]?.disabled != true }
                .mapNotNull { (modelId, model) ->
                    val configModel = configModels[modelId]
                    // The agent speaks Chat Completions on the fixed managed
                    // endpoint for every channel, so a model's published
                    // protocol never filters or addresses it; only an explicit
                    // `tools.supported: false` excludes one, mirroring the
                    // gateway's own capability check. The voice flow instead
                    // drives only the protocols it implements, and only audio
                    // models.
                    val protocol: String
                    val baseUrl: String
                    if (kind == Kind.Agent) {
                        if (!model.supportsTools()) return@mapNotNull null
                        protocol = ConsoleApi.CHAT_COMPLETIONS_PROTOCOL
                        baseUrl = ConsoleApi.DEFAULT_CHAT_BASE
                    } else {
                        protocol = model.protocolFor() ?: return@mapNotNull null
                        if (!model.supportsAudioInput()) return@mapNotNull null
                        // The channel's published route for the model and protocol
                        // wins; a channel-level base URL is its OpenAI-compatible
                        // one, so it only backs a chat-completions model up and
                        // never overrides a generate-content model's address.
                        baseUrl = model.baseUrlFor(protocol)?.takeIf { it.isNotBlank() }
                            ?: (if (protocol == ConsoleApi.CHAT_COMPLETIONS_PROTOCOL)
                                provider?.baseURL?.takeIf { it.isNotBlank() } else null)
                            ?: when (protocol) {
                                ConsoleApi.CHAT_COMPLETIONS_PROTOCOL -> ConsoleApi.DEFAULT_CHAT_BASE
                                else -> ConsoleApi.DEFAULT_GOOGLE_BASE
                            }
                    }
                    // The model's published effort levels become variants, in
                    // published order and merged with the workspace's saved
                    // ones: a saved variant of the same id overrides the
                    // built-in body, and saved-only ids follow after. The
                    // implicit default stays the picker's blank value; no
                    // "default" or invented level is ever added.
                    val effortLevels = model.effortLevels()
                    val configVariants = configModel?.variants.orEmpty()
                    val variants = buildList {
                        effortLevels.forEach { level ->
                            add(configVariants.find { it.id == level } ?: reasoningVariant(protocol, level))
                        }
                        configVariants.forEach { variant ->
                            if (variant.id !in effortLevels) add(variant)
                        }
                    }
                    ModelEntry(
                        id = "$providerKey/$modelId",
                        providerKey = providerKey,
                        channelName = channelLabel(providerKey, channel),
                        modelId = modelId,
                        displayName = model.name ?: configModel?.name ?: modelId,
                        protocol = protocol,
                        variants = variants.map { it.id },
                        baseUrl = baseUrl,
                        providerHeaders = provider?.headers,
                        providerBody = provider?.body,
                        modelHeaders = configModel?.headers,
                        modelBody = configModel?.body,
                        variantsById = variants.associateBy { it.id },
                    )
                }
                .toList()
        }
    }

    /**
     * Find the entry a persisted selection names. New selections store the
     * composite id `providerKey/modelId`; a bare model id left by an older
     * version resolves to the managed channel's model of that id.
     */
    fun findById(entries: List<ModelEntry>, value: String): ModelEntry? =
        entries.find { it.id == value }
            ?: entries.find { it.providerKey == ConsoleApi.MANAGED_PROVIDER_KEY && it.modelId == value }

    /**
     * The wire `model` value for the agent's fixed managed Chat Completions
     * endpoint: the managed connection serves its models under their bare
     * ids, while Go and BYOK connections are named by the provider key the
     * catalog gives the channel, before the model id's first `/` — which is
     * exactly the composite [ModelEntry.id].
     */
    fun agentWireModel(entry: ModelEntry): String =
        if (entry.providerKey == ConsoleApi.MANAGED_PROVIDER_KEY) entry.modelId else entry.id

    /**
     * The body overlay a published effort level sends on the entry's wire
     * protocol: Chat Completions takes a top-level `reasoning_effort`, and
     * the Gemini request's overlay merge places a top-level `thinkingConfig`
     * under `generationConfig` on the wire. Nothing here turns reasoning off
     * or names a level the model did not publish.
     */
    private fun reasoningVariant(protocol: String, level: String): ConsoleApi.ConsoleVariant =
        ConsoleApi.ConsoleVariant(
            id = level,
            body = when (protocol) {
                ConsoleApi.GENERATE_CONTENT_PROTOCOL -> buildJsonObject {
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", level)
                        put("includeThoughts", true)
                    })
                }
                else -> buildJsonObject { put("reasoning_effort", level) }
            },
        )

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
            // The wire request names the bare model id on the entry's
            // channel's base URL; the composite id is only the selection key.
            model = entry.modelId,
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
