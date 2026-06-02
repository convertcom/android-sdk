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
    fun `fetchConfig builds exact url with config prefix sdkKey and environment plus cacheLevel query`() = runTest {
        // Story 2.2 AC-10 (F-006/F-037 option a) — full URL parity.
        // The audit's class 4.4 rule forbids substring / contains /
        // startsWith / endsWith on URL assertions; this test asserts
        // the canonical full URL via assertEquals.
        //
        // Expected URL composition:
        //   - base    = ConfigDefaults.DEFAULT_CONFIG_ENDPOINT trimmed of "/"
        //   - segment = "/config/"
        //   - sdkKey  = "sk-abc"
        //   - query   = "?environment=staging&_conv_low_cache=1"
        // The corrected story (F-006 option a) inserts `&` between
        // `environment=...` and `_conv_low_cache=1` (intentional
        // divergence from JS SDK which omits the separator).
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(
            sdkKey = "sk-abc",
            // environment defaults to "staging" via ConvertConfig.
            network = TestNetworkConfig(cacheLevel = "low"),
        )
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        assertEquals(1, http.calls.size)
        val expectedUrl =
            "https://cdn-4.convertexperiments.com/api/v1/config/sk-abc" +
                "?environment=staging&_conv_low_cache=1"
        assertEquals(expectedUrl, http.calls.first().url)
    }

    @Test
    fun `fetchConfig builds exact url without low-cache when cacheLevel default`() = runTest {
        // Story 2.2 AC-1 / AC-10 — environment param always present (default
        // "staging" from ConvertConfig); _conv_low_cache=1 omitted when
        // cacheLevel is anything other than "low". Full-URL parity.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-1") // no cacheLevel → null
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val expectedUrl =
            "https://cdn-4.convertexperiments.com/api/v1/config/sk-1?environment=staging"
        assertEquals(expectedUrl, http.calls.single().url)
    }

    @Test
    fun `fetchConfig builds exact url with custom environment override`() = runTest {
        // Story 2.2 AC-1 — environment value flows directly into the
        // query string. Full-URL parity (no substring assertions).
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-1", environment = "prod")
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val expectedUrl =
            "https://cdn-4.convertexperiments.com/api/v1/config/sk-1?environment=prod"
        assertEquals(expectedUrl, http.calls.single().url)
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
            network = TestNetworkConfig(configEndpoint = "http://insecure.example.com/api/v1/"),
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
        // Story 2.2 AC-1 / AC-10 — explicit "default" cacheLevel produces
        // the same URL as the omitted-cacheLevel case: only the
        // environment param is included. Full-URL parity.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(sdkKey = "sk-1", network = TestNetworkConfig(cacheLevel = "default"))
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val expectedUrl =
            "https://cdn-4.convertexperiments.com/api/v1/config/sk-1?environment=staging"
        assertEquals(expectedUrl, http.calls.single().url)
    }

    @Test
    fun `fetchConfig uses configured endpoint override when provided`() = runTest {
        // Story 2.2 AC-1 / AC-10 — configured endpoint replaces the default
        // CDN host but the literal `/config/` path segment and the query
        // string are unchanged. Full-URL parity.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val config = convertConfig(
            sdkKey = "sk-1",
            network = TestNetworkConfig(configEndpoint = "https://staging-cdn.example.com/api/v1/"),
        )
        val api = ApiManager(http, logger, config, json)

        api.fetchConfig()

        val expectedUrl =
            "https://staging-cdn.example.com/api/v1/config/sk-1?environment=staging"
        assertEquals(expectedUrl, http.calls.single().url)
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

    // --- Story 2.3 AC-5 — tracking toggle pre-wiring ------------------------

    @Test
    fun `isTrackingEnabled defaults to DEFAULT_TRACKING_ENABLED when network is null`() {
        // When NetworkConfig is absent, the flag initialises to the compile-
        // time default so that downstream event-enqueue paths (Story 5.4) see
        // a non-null, deterministic value.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(http, CapturingLogger(), convertConfig(sdkKey = "sk-1"), json)

        assertEquals(ConfigDefaults.DEFAULT_TRACKING_ENABLED, api.isTrackingEnabled())
    }

    @Test
    fun `isTrackingEnabled echoes NetworkConfig tracking when provided`() {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(
            http,
            CapturingLogger(),
            convertConfig(sdkKey = "sk-1", tracking = false),
            json,
        )

        assertFalse(api.isTrackingEnabled(), "tracking=false from config should propagate")
    }

    @Test
    fun `setTrackingEnabled flips the flag atomically and survives a get`() {
        // The architecture chose AtomicBoolean for this flag; AC-5 asks us to
        // verify that flipping the value via setTrackingEnabled is observed
        // by a subsequent isTrackingEnabled call — exercise both transitions.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(
            http,
            CapturingLogger(),
            convertConfig(sdkKey = "sk-1", tracking = true),
            json,
        )

        assertTrue(api.isTrackingEnabled())

        api.setTrackingEnabled(false)
        assertFalse(api.isTrackingEnabled())

        api.setTrackingEnabled(true)
        assertTrue(api.isTrackingEnabled())
    }

    @Test
    fun `setTrackingEnabled does not disable fetchConfig (bucketing path)`() = runTest {
        // AC-5 guarantee: flipping tracking off pre-wires the Story 5.4 enqueue
        // bypass but does NOT gate the config fetch itself (bucketing depends
        // on fetched config). Story 2.3 only pre-wires state; this test locks
        // that invariant in place so future refactors can't silently break it.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(
            http,
            CapturingLogger(),
            convertConfig(sdkKey = "sk-1", tracking = false),
            json,
        )

        val result = api.fetchConfig()

        // Even with tracking disabled, the config fetch still runs and parses.
        assertNotNull(result, "fetchConfig must still run when tracking is disabled")
        assertEquals(1, http.calls.size)
    }

    // --- Story 4.2 SDK-1 — enqueueConversionEvent stub ----------------------

    @Test
    fun `enqueueConversionEvent stub is a no-op and logs DEBUG with bare goalId`() {
        // Story 4.2 placeholder for the conversion-event enqueue that
        // Story 5.1 will implement. Contract for THIS story: public open
        // method exists, signature matches (visitorId, goalId, goalData?),
        // body never throws, a DEBUG trace is emitted so operators can
        // see the stub was reached.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val api = ApiManager(http, logger, convertConfig(sdkKey = "sk-1"), json)

        // goalData == null is the "bare conversion" case — matches the JS SDK
        // `sendConversion()` path (data-manager.ts:1050-1066) which builds
        // `{goalId}` with no transaction payload.
        api.enqueueConversionEvent(
            visitorId = "v-1",
            goalId = "g-42",
            goalData = null,
        )

        // No HTTP calls — the stub is a pure no-op; Story 5.1 wires the queue.
        assertEquals(0, http.calls.size)
        // DEBUG trace includes all three arg values so operators following
        // a support ticket can trace the call without needing a verbose
        // trace at INFO/WARN levels.
        val debugs = logger.allMessages().filter { it.contains("enqueueConversionEvent") }
        assertEquals(1, debugs.size, "expected one DEBUG trace; got $debugs")
        assertTrue(debugs.first().contains("visitorId=v-1"), debugs.first())
        assertTrue(debugs.first().contains("goalId=g-42"), debugs.first())
    }

    @Test
    fun `enqueueConversionEvent stub is a no-op and logs DEBUG with goalData list`() {
        // Story 4.2 `sendTransaction()`-flavoured call: goalData carries
        // amount / productsCount / transactionId / customDimensionN.
        // Still a pure no-op at Story 4.2; the stub distinguishes the two
        // call variants only by logging the goalData size.
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val api = ApiManager(http, logger, convertConfig(sdkKey = "sk-1"), json)

        val goalData = listOf(
            com.convert.sdk.core.model.GoalData(
                key = com.convert.sdk.core.model.GoalDataKey.AMOUNT,
                value = kotlinx.serialization.json.JsonPrimitive(29.99),
            ),
            com.convert.sdk.core.model.GoalData(
                key = com.convert.sdk.core.model.GoalDataKey.TRANSACTION_ID,
                value = kotlinx.serialization.json.JsonPrimitive("TX-42"),
            ),
        )

        api.enqueueConversionEvent(
            visitorId = "v-1",
            goalId = "g-42",
            goalData = goalData,
        )

        assertEquals(0, http.calls.size)
        val debugs = logger.allMessages().filter { it.contains("enqueueConversionEvent") }
        assertEquals(1, debugs.size)
        assertTrue(debugs.first().contains("goalDataSize=2"), debugs.first())
    }

    // --- Test helpers -------------------------------------------------------

    /**
     * Builds a [ConvertConfig] with the common fields tests need, defaulting
     * everything else. `environment` defaults to ConvertConfig's own default
     * (`"staging"`) — pass `environment = "..."` to override.
     */
    private data class TestNetworkConfig(
        val configEndpoint: String? = null,
        val cacheLevel: String? = null,
    )

    private fun convertConfig(
        sdkKey: String? = null,
        sdkKeySecret: String? = null,
        environment: String = "staging",
        tracking: Boolean? = null,
        network: TestNetworkConfig? = null,
    ): ConvertConfig {
        val api = if (network?.configEndpoint != null) {
            ApiConfig(endpoint = ApiEndpoint(config = network.configEndpoint, track = null))
        } else {
            null
        }
        val networkConfig = if (network?.cacheLevel != null || tracking != null) {
            NetworkConfig(cacheLevel = network?.cacheLevel, tracking = tracking)
        } else {
            null
        }
        return ConvertConfig(
            sdkKey = sdkKey,
            sdkKeySecret = sdkKeySecret,
            api = api,
            network = networkConfig,
            environment = environment,
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
