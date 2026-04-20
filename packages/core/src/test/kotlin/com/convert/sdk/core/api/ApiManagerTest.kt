/*
 * Convert Android SDK — core/api tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ApiConfig
import com.convert.sdk.core.config.ApiEndpoint
import com.convert.sdk.core.config.ConfigDefaults
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.NetworkConfig
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 2.2 AC-10 tests for [ApiManager.fetchConfig].
 *
 * Uses [FakeHttpClient] (in-memory recorder) rather than MockWebServer because
 * the core module has no OkHttp dependency. Uses [CapturingLogger] to grep
 * captured log messages for secrets (AC-9 / AC-10 non-leakage test).
 *
 * ### Jupiter assertion order
 *
 * JUnit Jupiter assertions accept `(condition, message)` — the message comes
 * second. (JUnit 4 used the opposite convention.) All assertions in this file
 * follow the Jupiter convention to match [org.junit.jupiter.api.Assertions].
 */
internal class ApiManagerTest {

    // Reusable Json with the same config used by the real ApiManager.
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Test
    fun `fetchConfig builds url with sdkKey path and cacheLevel query`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(
            sdkKey = "sk-abc",
            cacheLevel = "low",
        )
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        // URL must be endpoint + "/" + sdkKey; "low" cache level appends the bypass query.
        assertEquals(1, http.calls.size)
        val calledUrl = http.calls.first().url
        assertTrue(
            calledUrl.startsWith(ConfigDefaults.DEFAULT_CONFIG_ENDPOINT),
            "url should start with the config endpoint: $calledUrl",
        )
        assertTrue(
            calledUrl.contains("/sk-abc"),
            "url should include /sk-abc: $calledUrl",
        )
        assertTrue(
            calledUrl.contains("_conv_low_cache=1"),
            "url should contain _conv_low_cache=1 for low cacheLevel: $calledUrl",
        )
    }

    @Test
    fun `fetchConfig sets Authorization header when sdkKeySecret provided`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(
            sdkKey = "sk-1",
            sdkKeySecret = "super-secret-token",
        )
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val headers = http.calls.single().headers
        // Value is raw, not base64-encoded, and the key is "Authorization".
        assertEquals("super-secret-token", headers["Authorization"])
    }

    @Test
    fun `fetchConfig does not set Authorization header when sdkKeySecret is null`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-1", sdkKeySecret = null)
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val headers = http.calls.single().headers
        assertFalse(
            headers.containsKey("Authorization"),
            "Authorization must not be present",
        )
    }

    @Test
    fun `fetchConfig rejects non-https urls with warn`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(
            sdkKey = "sk-1",
            configEndpoint = "http://insecure.example.com/api/v1/",
        )
        val api = ApiManager(http, logger, config, json)

        val result = api.fetchConfig()

        assertNull(result)
        // Must NOT have called the http client.
        assertEquals(0, http.calls.size)
        // Must have logged a WARN mentioning HTTPS.
        val warnMessages = logger.warnMessages()
        assertTrue(
            warnMessages.any { it.contains("https", ignoreCase = true) },
            "expected a WARN mentioning https, got: $warnMessages",
        )
    }

    @Test
    fun `fetchConfig returns null on non-2xx and logs warn`() = runTest {
        val http = FakeHttpClient(statusCode = 500, body = "internal server error body")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-1")
        val api = ApiManager(http, logger, config, json)

        val result = api.fetchConfig()

        assertNull(result)
        val warnMessages = logger.warnMessages()
        assertTrue(
            warnMessages.any { it.contains("500") },
            "expected a WARN containing status 500, got: $warnMessages",
        )
    }

    @Test
    fun `fetchConfig does not log sdkKeySecret value`() = runTest {
        val http = FakeHttpClient(statusCode = 500, body = "whatever")
        val logger = CapturingLogger()
        val config = convertConfig(
            sdkKey = "sk-1",
            sdkKeySecret = "LEAK-CANARY-TOKEN-9999",
        )
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        // Grep every captured line for the canary.
        val allMessages = logger.allMessages()
        assertFalse(
            allMessages.any { it.contains("LEAK-CANARY-TOKEN-9999") },
            "secret must never appear in any log message; captured: $allMessages",
        )
    }

    @Test
    fun `fetchConfig parses valid 200 response to ConfigResponseData`() = runTest {
        // Minimal but valid ConfigResponseData JSON — account_id populated.
        val body = """{"account_id":"12345"}"""
        val http = FakeHttpClient(statusCode = 200, body = body)
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-1")
        val api = ApiManager(http, logger, config, json)

        val result = api.fetchConfig()

        assertNotNull(result)
        assertEquals("12345", result?.accountId)
    }

    @Test
    fun `fetchConfig returns null and logs error on parse failure`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "not valid json {{{")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-1")
        val api = ApiManager(http, logger, config, json)

        val result = api.fetchConfig()

        assertNull(result)
        val errorMessages = logger.errorMessages()
        assertTrue(
            errorMessages.any {
                it.contains("failed to parse config response", ignoreCase = true)
            },
            "expected an ERROR about parse failure, got: $errorMessages",
        )
    }

    @Test
    fun `fetchConfig returns null on network failure (statusCode 0)`() = runTest {
        // Matches OkHttpClientAdapter's convention: statusCode 0 = transport
        // layer failure (DNS / connect refused / timeout).
        val http = FakeHttpClient(statusCode = 0, body = "")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-1")
        val api = ApiManager(http, logger, config, json)

        val result = api.fetchConfig()

        assertNull(result)
        val warnMessages = logger.warnMessages()
        assertTrue(
            warnMessages.isNotEmpty(),
            "expected a WARN on network failure, got: $warnMessages",
        )
    }

    @Test
    fun `fetchConfig does not append cacheLevel query when cacheLevel is default`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-1", cacheLevel = "default")
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val url = http.calls.single().url
        assertFalse(
            url.contains("_conv_low_cache"),
            "default cacheLevel must NOT include the bypass query: $url",
        )
    }

    @Test
    fun `fetchConfig uses configured endpoint override when provided`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(
            sdkKey = "sk-1",
            configEndpoint = "https://staging-cdn.example.com/api/v1/",
        )
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val url = http.calls.single().url
        assertTrue(
            url.startsWith("https://staging-cdn.example.com/api/v1/"),
            "url should start with configured endpoint: $url",
        )
        assertTrue(url.contains("/sk-1"), "url should include /sk-1: $url")
    }

    @Test
    fun `fetchConfig returns null and logs warn when sdkKey is null`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = null)
        val api = ApiManager(http, logger, config, json)

        val result = api.fetchConfig()

        assertNull(result)
        // No network call — we can't build the URL without an sdkKey.
        assertEquals(0, http.calls.size)
    }

    // --- Test helpers -------------------------------------------------------

    /**
     * Builds a [ConvertConfig] with the common fields tests need, defaulting
     * everything else.
     */
    private fun convertConfig(
        sdkKey: String? = null,
        sdkKeySecret: String? = null,
        configEndpoint: String? = null,
        cacheLevel: String? = null,
    ): ConvertConfig {
        val api = if (configEndpoint != null) {
            ApiConfig(endpoint = ApiEndpoint(config = configEndpoint, track = null))
        } else {
            null
        }
        val network = if (cacheLevel != null) NetworkConfig(cacheLevel = cacheLevel) else null
        return ConvertConfig(
            sdkKey = sdkKey,
            sdkKeySecret = sdkKeySecret,
            api = api,
            network = network,
        )
    }

    /**
     * In-memory recording [HttpClient]. Captures every call (url + headers +
     * body) so the test can inspect what the ApiManager actually sent.
     * Returns a canned [HttpClient.HttpResponse] constructed from the
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
            return HttpClient.HttpResponse(
                statusCode = statusCode,
                body = body,
                headers = emptyMap(),
            )
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            calls += RecordedCall("POST", url, headers, body)
            return HttpClient.HttpResponse(
                statusCode = statusCode,
                body = this.body,
                headers = emptyMap(),
            )
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

        fun errorMessages(): List<String> =
            entries.filter { it.level == "ERROR" }.map { it.message }

        fun warnMessages(): List<String> =
            entries.filter { it.level == "WARN" }.map { it.message }

        fun allMessages(): List<String> = entries.map { it.message }
    }
}
