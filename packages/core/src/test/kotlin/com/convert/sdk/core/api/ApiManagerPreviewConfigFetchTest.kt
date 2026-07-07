/*
 * Convert Android SDK — core/api tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.io.IOException
import java.util.stream.Stream

/**
 * qs-02 AND-4 (contract §2 "Resolution" — the `?exp=` preview-config fetch
 * + 60s in-memory memo) RED-phase tests for [ApiManager.fetchConfig]'s
 * `experienceId` overload.
 *
 * Covers AC8 (memoization: two resolutions for the same `experienceId`
 * within 60s → exactly one HTTP request, and eviction of expired entries)
 * plus the AC3 token-hygiene redaction extended to the `?exp=` URL — the
 * `debug_token` on that URL must never appear in clear in a WARN log.
 *
 * The disk-cache non-interaction half of AC8 ("never written to the
 * on-disk config cache") is architecturally guaranteed here rather than
 * RED/GREEN-testable in this module: [ApiManager] (`:packages:core`) has
 * no reference to `FileConfigCache` (`:packages:sdk`, requires
 * `android.content.Context`) — see
 * `packages/sdk/src/test/kotlin/com/convert/sdk/android/adapter/ApiManagerPreviewConfigFetchDiskCacheTest.kt`
 * for the Robolectric-backed regression lock that exercises this with a
 * real `FileConfigCache` instance.
 *
 * ### RED-phase stub
 *
 * Before the GREEN-phase implementation, [ApiManager] has no `experienceId`
 * overload of [fetchConfig] and no `clock` constructor parameter — every
 * test in this file fails to compile, which is the RED signal per the
 * qs-02 AND-4 brief.
 */
internal class ApiManagerPreviewConfigFetchTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // ------------------------------------------------------------------
    // AC8 (transport half) + AC3 extension — exp=<id> URL shape + redaction
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "debugToken={0}")
    @MethodSource("debugTokenCases")
    fun `fetchConfig(experienceId) builds exact exp url with forced low-cache and optional debug_token`(
        debugToken: String?,
    ) = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-abc", debugToken = debugToken)
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig(experienceId = "555")

        assertEquals(1, http.calls.size)
        val expectedUrl = "https://cdn-4.convertexperiments.com/api/v1/config/sk-abc" +
            "?environment=staging&exp=555" +
            (debugToken?.let { "&debug_token=$it" } ?: "") +
            "&_conv_low_cache=1"
        assertEquals(expectedUrl, http.calls.first().url)
    }

    @Test
    fun `fetchConfig(experienceId) redacts debug_token in fetch-error WARN logs`() = runTest {
        val http = ThrowingHttpClient(IOException("boom"))
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-abc", debugToken = "dbg-canary-exp-9999")
        val api = ApiManager(http, logger, config, json)

        val result = api.fetchConfig(experienceId = "555")

        assertNull(result)
        val warnMessages = logger.warnMessages()
        assertFalse(
            warnMessages.any { it.contains("dbg-canary-exp-9999") },
            "raw debug token must never appear in a WARN log: $warnMessages",
        )
        assertTrue(
            warnMessages.any { it.contains("debug_token=[REDACTED]") },
            "expected a WARN with a redacted debug_token marker, got: $warnMessages",
        )
    }

    // ------------------------------------------------------------------
    // AC8 (memoization half) — 60s in-memory memo, per experienceId
    // ------------------------------------------------------------------

    @Test
    fun `two resolutions for the same experienceId within 60s make exactly one HTTP request`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = """{"accountId":"acc-1"}""")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-abc")
        val fakeClock = FakeClock(startMillis = 1_000L)
        val api = ApiManager(httpClient = http, logger = logger, config = config, json = json, clock = fakeClock)

        val first = api.fetchConfig(experienceId = "555")
        fakeClock.advanceBy(59_000L) // still inside the 60s TTL window
        val second = api.fetchConfig(experienceId = "555")

        assertEquals(1, http.calls.size, "memo hit must not issue a second HTTP request")
        assertNotNull(first)
        assertSame(first, second, "memoized resolution should return the same cached instance")
    }

    @Test
    fun `two resolutions for different experienceIds each make their own HTTP request`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-abc")
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig(experienceId = "555")
        api.fetchConfig(experienceId = "777")

        assertEquals(2, http.calls.size)
        assertTrue(http.calls[0].url.contains("exp=555"))
        assertTrue(http.calls[1].url.contains("exp=777"))
    }

    @Test
    fun `an entry past the 60s TTL triggers a fresh fetch after being memoized within the window`() = runTest {
        // Deliberately brackets the TTL-expiry call with an in-window memo
        // hit so a naive "always fetch, no memo at all" implementation
        // cannot accidentally satisfy this assertion — it would produce 3
        // calls (one per resolution) instead of the expected 2.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-abc")
        val fakeClock = FakeClock(startMillis = 1_000L)
        val api = ApiManager(httpClient = http, logger = logger, config = config, json = json, clock = fakeClock)

        api.fetchConfig(experienceId = "555") // call #1 — miss, fetches
        fakeClock.advanceBy(30_000L)
        api.fetchConfig(experienceId = "555") // call #2 — still inside the 60s TTL, memo hit
        fakeClock.advanceBy(30_000L) // elapsed since call #1 == 60_000ms — entry has aged out
        api.fetchConfig(experienceId = "555") // call #3 — expired, fresh fetch

        assertEquals(
            2,
            http.calls.size,
            "expected exactly 2 HTTP requests: the initial fetch + one fresh fetch after TTL " +
                "expiry — the middle in-window call must be a memo hit",
        )
    }

    // --- Test helpers -------------------------------------------------------

    private fun convertConfig(
        sdkKey: String? = null,
        debugToken: String? = null,
    ): ConvertConfig = ConvertConfig(sdkKey = sdkKey, debugToken = debugToken)

    private companion object {
        @JvmStatic
        fun debugTokenCases(): Stream<Arguments> = Stream.of(
            Arguments.of(null),
            Arguments.of("dbg-canary-2"),
        )
    }

    /**
     * Deterministic, manually-advanced clock — avoids a real 60-second
     * sleep in [an entry past the 60s TTL triggers a fresh fetch] and the
     * memo-hit test.
     */
    private class FakeClock(startMillis: Long) : () -> Long {
        private var current: Long = startMillis
        override fun invoke(): Long = current
        fun advanceBy(deltaMillis: Long) {
            current += deltaMillis
        }
    }

    /**
     * In-memory recording [HttpClient]. Captures every call (method + url +
     * headers) so the test can inspect what [ApiManager] actually sent.
     */
    private class FakeHttpClient(
        private val statusCode: Int,
        private val body: String,
    ) : HttpClient {

        data class RecordedCall(val method: String, val url: String, val headers: Map<String, String>)

        val calls: MutableList<RecordedCall> = mutableListOf()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            calls += RecordedCall("GET", url, headers)
            return HttpClient.HttpResponse(statusCode = statusCode, body = body, headers = emptyMap())
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            calls += RecordedCall("POST", url, headers)
            return HttpClient.HttpResponse(statusCode = statusCode, body = this.body, headers = emptyMap())
        }
    }

    /** HTTP client that always throws — simulates a true transport failure. */
    private class ThrowingHttpClient(private val error: Throwable) : HttpClient {
        override suspend fun get(url: String, headers: Map<String, String>): HttpClient.HttpResponse {
            throw error
        }
        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            throw error
        }
    }

    /**
     * Capturing [Logger] — every method appends to a level-tagged list so
     * tests can grep-assert specific messages and secret non-leakage.
     */
    private class CapturingLogger : Logger {
        data class Entry(val level: String, val message: String, val tag: String?)

        private val entries: MutableList<Entry> = mutableListOf()

        override fun error(message: String, throwable: Throwable?, tag: String?) {
            entries += Entry("ERROR", message, tag)
        }
        override fun warn(message: String, throwable: Throwable?, tag: String?) {
            entries += Entry("WARN", message, tag)
        }
        override fun info(message: String, tag: String?) {
            entries += Entry("INFO", message, tag)
        }
        override fun debug(message: String, tag: String?) {
            entries += Entry("DEBUG", message, tag)
        }

        fun warnMessages(): List<String> = entries.filter { it.level == "WARN" }.map { it.message }
    }
}
