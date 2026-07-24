/*
 * Convert Android SDK — core/api tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.NetworkConfig
import com.convert.sdk.core.model.generated.ConfigProject
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * qs-02 AND-1 (contract §1 — `debugToken`) RED-phase tests for
 * [ApiManager.fetchConfig] and [ApiManager.flush]'s URL/logging surface.
 *
 * Covers AC1 (transport: `debug_token=<value>` + forced
 * `_conv_low_cache=1` on every config-fetch URL, regardless of
 * `cacheLevel`) and AC3 (token hygiene: never sent to the track endpoint,
 * never logged in clear).
 *
 * The RED-phase stub ([ConvertConfig.debugToken] is a plain pass-through
 * field, per `Builder.debugToken()` / `ConvertConfig`) does not yet wire
 * the value into [ApiManager.buildConfigQuery] or redact it before
 * logging — see the GREEN-phase TODOs in `ApiManager.kt` and
 * `ConvertConfig.kt`. These tests therefore fail on assertions today.
 */
internal class ApiManagerDebugTokenTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    // ------------------------------------------------------------------
    // AC1 — transport: debug_token + forced _conv_low_cache=1
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "cacheLevel={0}")
    @MethodSource("cacheLevelCases")
    fun `fetchConfig with debugToken set forces debug_token and _conv_low_cache=1 regardless of cacheLevel`(
        cacheLevel: String?,
    ) = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(
            sdkKey = "sk-abc",
            debugToken = "dbg-canary-1",
            cacheLevel = cacheLevel,
        )
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        assertEquals(1, http.calls.size)
        val expectedUrl = "https://cdn-4.convertexperiments.com/api/v1/config/sk-abc" +
            "?environment=staging&debug_token=dbg-canary-1&_conv_low_cache=1"
        assertEquals(expectedUrl, http.calls.first().url)
    }

    @Test
    fun `fetchConfig percent-encodes a debugToken containing reserved query characters`() = runTest {
        // Parity guard: iOS/Ruby/PHP/Python already URL-encode dynamic
        // config-fetch query values. A debug token containing '+', '/', '='
        // must be percent-encoded rather than concatenated raw, or it
        // corrupts the query string / is misparsed by the backend.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-abc", debugToken = "dbg+tok/en=x", cacheLevel = null)
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val expectedUrl = "https://cdn-4.convertexperiments.com/api/v1/config/sk-abc" +
            "?environment=staging&debug_token=dbg%2Btok%2Fen%3Dx&_conv_low_cache=1"
        assertEquals(expectedUrl, http.calls.single().url)
    }

    @Test
    fun `fetchConfig without debugToken never adds debug_token param`() = runTest {
        // Regression lock: absence of debugToken must reproduce today's
        // exact URL shape (no debug_token, cacheLevel governs low-cache).
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-abc", debugToken = null, cacheLevel = null)
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val expectedUrl = "https://cdn-4.convertexperiments.com/api/v1/config/sk-abc?environment=staging"
        assertEquals(expectedUrl, http.calls.single().url)
    }

    // ------------------------------------------------------------------
    // AC3 — token hygiene
    // ------------------------------------------------------------------

    @Test
    fun `flush track request URL never contains debug_token even when debugToken is set`() = runTest {
        // Structurally safe today (buildTrackUrl only uses sdkKey +
        // projectId, no query params) — this is a regression lock, not a
        // RED assertion. Still mandated by the AND-1 brief so a future
        // refactor of buildTrackUrl can't silently start leaking the
        // token onto the track endpoint.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(
            sdkKey = "sk-abc",
            debugToken = "dbg-canary-track",
            data = ConfigResponseData(accountId = "acc-1", project = ConfigProject(id = "proj-9")),
        )
        val api = ApiManager(http, logger, config, json)

        api.enqueueBucketingEvent(visitorId = "v-1", experienceId = "e-1", variationId = "var-a")
        api.flushForTest()

        assertEquals(1, http.calls.size)
        val trackUrl = http.calls.single { it.method == "POST" }.url
        assertFalse(
            trackUrl.contains("debug_token"),
            "track request URL must never carry debug_token: $trackUrl",
        )
        assertEquals(
            "https://proj-9.metrics.convertexperiments.com/v1/track/sk-abc",
            trackUrl,
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fetchErrorCases")
    fun `fetchConfig error logs redact debug_token instead of leaking the raw value`(
        caseName: String,
        http: HttpClient,
    ) = runTest {
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-abc", debugToken = "dbg-canary-log-9999")
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val warnMessages = logger.warnMessages()
        assertFalse(
            warnMessages.any { it.contains("dbg-canary-log-9999") },
            "[$caseName] raw debug token must never appear in a WARN log: $warnMessages",
        )
        assertTrue(
            warnMessages.any { it.contains("debug_token=[REDACTED]") },
            "[$caseName] expected a WARN with a redacted debug_token marker, got: $warnMessages",
        )
    }

    @Test
    fun `redactDebugToken leaves a lookalike param name intact, redacting only the real debug_token`() {
        // Review R2 Finding 2 — the unanchored regex `debug_token=[^&]*`
        // also matches the tail of a DIFFERENT param name that merely
        // ENDS in `debug_token` (e.g. `not_debug_token=`), corrupting
        // that param's own value even though it carries no secret. The
        // fix anchors the match to a `?`/`&` immediately preceding
        // `debug_token=`.
        //
        // FIX B (URL-encoding every dynamic config-fetch query value)
        // closes the only config-driven vector that could ever construct
        // this raw collision through `fetchConfig` — a literal `=` inside
        // `environment`/`exp`/`debugToken` is now always percent-encoded
        // to `%3D` before it reaches the URL, so this regression guard
        // exercises the private `redactDebugToken` regex directly via
        // reflection (same pattern as NetworkObserverTest) instead of
        // relying on an `environment` override that encoding now sanitizes.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-abc", debugToken = "SECRET")
        val api = ApiManager(http, logger, config, json)

        val redactMethod = ApiManager::class.java.getDeclaredMethod("redactDebugToken", String::class.java)
        redactMethod.isAccessible = true
        val redacted = redactMethod.invoke(
            api,
            "https://cdn-4.convertexperiments.com/api/v1/config/sk-abc?not_debug_token=xyz&debug_token=SECRET",
        ) as String

        assertTrue(
            redacted.contains("not_debug_token=xyz"),
            "lookalike param 'not_debug_token=xyz' must be left fully intact, got: $redacted",
        )
        assertTrue(
            redacted.contains("debug_token=[REDACTED]"),
            "the real debug_token value must still be redacted, got: $redacted",
        )
        assertFalse(
            redacted.contains("SECRET"),
            "raw debug token must never appear after redaction: $redacted",
        )
    }

    // --- Test helpers -------------------------------------------------------

    private fun convertConfig(
        sdkKey: String? = null,
        debugToken: String? = null,
        cacheLevel: String? = null,
        data: ConfigResponseData? = null,
        environment: String = "staging",
    ): ConvertConfig = ConvertConfig(
        sdkKey = sdkKey,
        debugToken = debugToken,
        network = NetworkConfig(cacheLevel = cacheLevel),
        data = data,
        environment = environment,
    )

    private companion object {
        @JvmStatic
        fun cacheLevelCases(): Stream<Arguments> = Stream.of(
            Arguments.of(null),
            Arguments.of("default"),
            Arguments.of("low"),
        )

        @JvmStatic
        fun fetchErrorCases(): Stream<Arguments> = Stream.of(
            Arguments.of(
                "network error (thrown exception)",
                ThrowingHttpClient(java.io.IOException("boom")),
            ),
            Arguments.of(
                "network error (statusCode 0)",
                FakeHttpClient(statusCode = 0, body = ""),
            ),
        )
    }

    /**
     * In-memory recording [HttpClient]. Captures every call (method + url +
     * headers + body) so the test can inspect what the ApiManager actually
     * sent. Returns a canned [HttpClient.HttpResponse] constructed from the
     * `statusCode` / `body` passed in at test setup time.
     */
    private class FakeHttpClient(
        private val statusCode: Int,
        private val body: String,
    ) : HttpClient {

        data class RecordedCall(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: String?,
        )

        val calls: MutableList<RecordedCall> = mutableListOf()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            calls += RecordedCall("GET", url, headers, null)
            return HttpClient.HttpResponse(statusCode = statusCode, body = body, headers = emptyMap())
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            calls += RecordedCall("POST", url, headers, body)
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
