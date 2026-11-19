/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.coroutines.CancellationException

/**
 * Console session helper: run one authenticated request with a transparent
 * access-token refresh. When the call comes back 401 the refresh token is
 * rotated once and the call retried a single time with the new access token;
 * a failed refresh surfaces as [SessionExpiredException] so callers can
 * clear the session and ask the user to sign in again.
 */
object ConsoleSession {

    class SessionExpiredException(cause: Throwable) : Exception(cause)

    suspend fun <T> withRefresh(
        accessToken: String,
        getRefreshToken: () -> String?,
        onTokensUpdated: (accessToken: String, refreshToken: String) -> Unit,
        block: suspend (accessToken: String) -> T,
    ): T {
        val token = accessToken.trim().takeIf { it.isNotEmpty() }
            ?: throw ConsoleApi.ConsoleHttpException(401, "Not signed in")
        return try {
            block(token)
        } catch (e: ConsoleApi.ConsoleHttpException) {
            if (e.status != 401) throw e
            val refreshToken = getRefreshToken()?.takeIf { it.isNotBlank() }
            if (refreshToken == null) throw SessionExpiredException(e)
            val rotated = try {
                ConsoleApi.refreshTokens(refreshToken)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                throw SessionExpiredException(e)
            }
            onTokensUpdated(rotated.access_token, rotated.refresh_token)
            block(rotated.access_token)
        }
    }
}
