/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConsoleApiTest {

    @Test
    fun authRequestsEncodeWithExplicitSerializers() {
        // Regression: a mixed-value map infers `Any` values, which have no
        // serializer and fail before the request is ever sent.
        assertEquals(
            """{"client_id":"fcitx5-android","supports_org_scope":true}""",
            ConsoleApi.json.encodeToString(
                ConsoleApi.DeviceCodeRequest(ConsoleApi.CLIENT_ID, supports_org_scope = true)
            ),
        )
        assertEquals(
            """{"grant_type":"urn:ietf:params:oauth:grant-type:device_code",""" +
                """"device_code":"dc","client_id":"fcitx5-android"}""",
            ConsoleApi.json.encodeToString(
                ConsoleApi.DeviceTokenRequest(
                    grant_type = "urn:ietf:params:oauth:grant-type:device_code",
                    device_code = "dc",
                    client_id = ConsoleApi.CLIENT_ID,
                )
            ),
        )
        assertEquals(
            """{"grant_type":"refresh_token","refresh_token":"rt",""" +
                """"client_id":"fcitx5-android"}""",
            ConsoleApi.json.encodeToString(
                ConsoleApi.RefreshTokenRequest(
                    grant_type = "refresh_token",
                    refresh_token = "rt",
                    client_id = ConsoleApi.CLIENT_ID,
                )
            ),
        )
    }

    @Test
    fun verificationUrlResolvesAgainstConsoleOriginOnly() {
        // The device-code response carries a root-relative path.
        assertEquals(
            "https://opencode.ai/console/device?user_code=ABCD",
            ConsoleApi.resolveVerificationUrl("/console/device?user_code=ABCD"),
        )
        // An absolute URL on the Console origin passes as-is.
        assertEquals(
            "https://opencode.ai/console/auth/device",
            ConsoleApi.resolveVerificationUrl("https://opencode.ai/console/auth/device"),
        )
        // Anything that resolves elsewhere is refused.
        assertNull(ConsoleApi.resolveVerificationUrl("https://evil.example.com/console/device"))
        assertNull(ConsoleApi.resolveVerificationUrl("//evil.example.com/device"))
        assertNull(ConsoleApi.resolveVerificationUrl("http://opencode.ai/console/device"))
        assertNull(ConsoleApi.resolveVerificationUrl("https://user:pass@opencode.ai/console/device"))
        assertNull(ConsoleApi.resolveVerificationUrl(null))
    }
}
