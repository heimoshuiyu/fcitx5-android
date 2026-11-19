/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.voice

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceHttpCancellationTest {

    @Test(timeout = 10_000)
    fun cancellingCoroutineCancelsHttpCall() = runTest {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse.Builder()
                    .headersDelay(30, TimeUnit.SECONDS)
                    .body("{}")
                    .build(),
            )

            val call = OkHttpClient().newCall(
                Request.Builder()
                    .url(server.url("/voice/transcribe"))
                    .build(),
            )
            val job = launch {
                call.executeAsync().use { response ->
                    response.body.string()
                }
            }

            yield()
            val recordedRequest = withContext(Dispatchers.IO) {
                server.takeRequest(5, TimeUnit.SECONDS)
            }
            assertNotNull(recordedRequest)

            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertTrue(call.isCanceled())
        }
    }

    @Test
    fun cancellationIsNotConvertedToFailure() = runTest {
        val expected = CancellationException("cancel transcription")

        try {
            runTranscriptionCatching<Unit> { throw expected }
            fail("CancellationException should be rethrown")
        } catch (actual: CancellationException) {
            assertSame(expected, actual)
        }
    }
}
