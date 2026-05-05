/*
 * Convert Android SDK — core/api batching tests (Story 5.1)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ApiConfig
import com.convert.sdk.core.config.ApiEndpoint
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.EventsConfig
import com.convert.sdk.core.config.NetworkConfig
import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.generated.ConfigProject
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 5.1 AC-10 tests for [ApiManager]'s event-batching & flush path.
 *
 * ### Why we parse POST bodies instead of binding to SendTrackingEventsRequestData
 *
 * The OpenAPI-generated `ConversionEventGoalDataInnerValue` is an empty
 * class (the spec models `value` as `number | string | Array<string>`,
 * which the Kotlin generator can't express as a proper union). Rather
 * than introduce a broken binding that loses goalData values, the
 * ApiManager serialises the outbound payload using JsonObject builders
 * directly. Tests therefore assert on the JSON structure via
 * [Json.parseToJsonElement] rather than decoding into a data class.
 *
 * ### Why TestScope
 *
 * AC-2 requires a timer loop that fires every `releaseInterval` ms.
 * Real wall-clock `delay` would make tests flaky; `TestScope` gives us
 * virtual time. `advanceTimeBy` drives the timer forward deterministically.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ApiManagerBatchingTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    // ------------------------------------------------------------------
    // AC-1 / AC-2 — enqueue and flush triggers
    // ------------------------------------------------------------------

    @Test
    fun `enqueue appends to in-memory queue`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(http, CapturingLogger(), configWithProject(), json)

        api.enqueueBucketingEvent(
            visitorId = "v-1",
            experienceId = "e-1",
            variationId = "var-a",
        )

        // No auto-flush (batch size not reached; no timer in this config).
        assertEquals(0, http.calls.size)
        // Queue depth is observable via flush(): after an explicit flush
        // we expect exactly one POST with one visitor carrying one event.
        api.flushForTest()
        assertEquals(1, http.calls.size)
    }

    @Test
    fun `queue flushes at batchSize threshold`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        // Large releaseInterval keeps the timer dormant so we can assert
        // on the size-triggered flush in isolation.
        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(batchSize = 3, releaseInterval = 1_000_000L),
            json = json,
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.enqueueBucketingEvent("v-1", "e-2", "var-b")
        runCurrent()
        assertEquals(0, http.calls.size, "should not flush below threshold")

        api.enqueueBucketingEvent("v-1", "e-3", "var-c")
        // Flush is launched on the scope; drain scheduled work without
        // advancing virtual time (timer is dormant at 1Ms ms anyway).
        runCurrent()

        assertEquals(1, http.calls.size)
        val body = Json.parseToJsonElement(http.calls.single().body!!).jsonObject
        val events = body["visitors"]!!.jsonArray.single().jsonObject["events"]!!.jsonArray
        assertEquals(3, events.size)
        api.cancelTimerForTest()
    }

    @Test
    fun `timer flushes queue after releaseInterval`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(releaseInterval = 5_000L),
            json = json,
            scope = this,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        )
        // Timer loop is launched in init via the scope parameter.
        api.enqueueBucketingEvent("v-1", "e-1", "var-a")

        advanceTimeBy(4_999L)
        assertEquals(0, http.calls.size, "timer should not fire before releaseInterval")

        advanceTimeBy(2L) // crosses the 5000ms boundary
        runCurrent()
        assertEquals(1, http.calls.size)
        api.cancelTimerForTest()
    }

    // ------------------------------------------------------------------
    // AC-6 / AC-7 — success clears queue; failure re-adds
    // ------------------------------------------------------------------

    @Test
    fun `successful POST clears queue`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(http, CapturingLogger(), configWithProject(), json)

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()
        assertEquals(1, http.calls.size)

        // Second flush is a no-op (queue is empty).
        api.flushForTest()
        assertEquals(1, http.calls.size, "second flush must not POST")
    }

    @Test
    fun `failed POST returns events to queue`() = runTest {
        val http = FakeHttpClient(statusCode = 500, body = "boom")
        val api = ApiManager(http, CapturingLogger(), configWithProject(), json)

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()
        assertEquals(1, http.calls.size)

        // Queue still holds the event. Flip the client to 200 and flush
        // again — the retry sends the same event.
        http.statusCode = 200
        api.flushForTest()
        assertEquals(2, http.calls.size, "retry must re-POST the kept event")
        val retryBody = Json.parseToJsonElement(http.calls.last().body!!).jsonObject
        val events = retryBody["visitors"]!!.jsonArray.single().jsonObject["events"]!!.jsonArray
        assertEquals(1, events.size, "retry payload should carry the original event")
    }

    @Test
    fun `exception in post re-adds events to queue`() = runTest {
        val http = ThrowingHttpClient(IllegalStateException("network down"))
        val api = ApiManager(http, CapturingLogger(), configWithProject(), json)

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        // flush must not re-throw — it must catch and re-add.
        api.flushForTest()
        assertEquals(1, http.attempts)

        // Second flush still re-attempts because the event is still queued.
        api.flushForTest()
        assertEquals(2, http.attempts)
    }

    // ------------------------------------------------------------------
    // AC-4 — payload shape with multiple visitors
    // ------------------------------------------------------------------

    @Test
    fun `payload matches expected schema with multiple visitors`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(http, CapturingLogger(), configWithProject(), json)

        api.enqueueBucketingEvent(
            visitorId = "v-1",
            experienceId = "e-1",
            variationId = "var-a",
            segments = mapOf("country" to JsonPrimitive("US")),
        )
        api.enqueueBucketingEvent(
            visitorId = "v-1",
            experienceId = "e-2",
            variationId = "var-b",
            segments = mapOf("country" to JsonPrimitive("US")),
        )
        api.enqueueBucketingEvent(
            visitorId = "v-2",
            experienceId = "e-3",
            variationId = "var-c",
            segments = mapOf("country" to JsonPrimitive("CA")),
        )

        api.flushForTest()

        assertEquals(1, http.calls.size)
        val body = Json.parseToJsonElement(http.calls.single().body!!).jsonObject
        assertEquals("acc-1", body["accountId"]!!.jsonPrimitive.content)
        assertEquals("proj-7", body["projectId"]!!.jsonPrimitive.content)

        val visitors = body["visitors"]!!.jsonArray
        assertEquals(2, visitors.size, "one entry per unique visitorId")

        val v1 = visitors.first { it.jsonObject["visitorId"]!!.jsonPrimitive.content == "v-1" }.jsonObject
        val v2 = visitors.first { it.jsonObject["visitorId"]!!.jsonPrimitive.content == "v-2" }.jsonObject
        assertEquals(2, v1["events"]!!.jsonArray.size, "v-1 groups both events")
        assertEquals(1, v2["events"]!!.jsonArray.size)
        assertEquals("US", v1["segments"]!!.jsonObject["country"]!!.jsonPrimitive.content)
        assertEquals("CA", v2["segments"]!!.jsonObject["country"]!!.jsonPrimitive.content)

        val firstEvent = v1["events"]!!.jsonArray.first().jsonObject
        assertEquals("bucketing", firstEvent["eventType"]!!.jsonPrimitive.content)
        val firstData = firstEvent["data"]!!.jsonObject
        assertEquals("e-1", firstData["experienceId"]!!.jsonPrimitive.content)
        assertEquals("var-a", firstData["variationId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `URL targets track endpoint with sdkKey and project-id substitution`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(
            http,
            CapturingLogger(),
            configWithProject(
                trackEndpoint = "https://[project_id].metrics.example.com/v1/",
                sdkKey = "sk-xyz",
            ),
            json,
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()

        val url = http.calls.single().url
        assertEquals("https://proj-7.metrics.example.com/v1/track/sk-xyz", url)
    }

    // ------------------------------------------------------------------
    // AC-6 — API_QUEUE_RELEASED event
    // ------------------------------------------------------------------

    @Test
    fun `API_QUEUE_RELEASED fires on successful flush`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        // EventManager dispatches subscribers on its own default scope.
        // Pin it to the test scheduler so fires propagate synchronously.
        val eventManager = EventManager(scope = this)
        val fires = mutableListOf<Map<String, Any?>>()
        eventManager.on(SystemEvents.API_QUEUE_RELEASED) { fires += it }

        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(),
            json = json,
            eventManager = eventManager,
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.enqueueBucketingEvent("v-2", "e-1", "var-a")
        api.flushForTest()

        advanceUntilIdle()
        assertEquals(1, fires.size)
        val payload = fires.single()
        // AC-6: payload must match JS SDK api-manager.ts:232-237: { reason, result, visitors }
        assertEquals("release", payload["reason"])
        assertEquals(200, payload["result"])
        @Suppress("UNCHECKED_CAST")
        val visitors = payload["visitors"] as List<Map<String, Any?>>
        assertEquals(2, visitors.size) // two visitors: v-1 and v-2

        // AC-6 (F-012 / F-089): per-event payloads must be structured (so Story 7.2
        // can resolve QUEUED → DELIVERED by reading eventType + data) — NOT stringified.
        val v1Entry = visitors.first { it["visitorId"] == "v-1" }

        @Suppress("UNCHECKED_CAST")
        val v1Events = v1Entry["events"] as List<JsonObject>
        assertEquals(1, v1Events.size)
        assertEquals("bucketing", v1Events.single()["eventType"]!!.jsonPrimitive.content)
        val v1Data = v1Events.single()["data"]!!.jsonObject
        assertEquals("e-1", v1Data["experienceId"]!!.jsonPrimitive.content)
        assertEquals("var-a", v1Data["variationId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `API_QUEUE_RELEASED does not fire on failed flush`() = runTest {
        val http = FakeHttpClient(statusCode = 500, body = "boom")
        val eventManager = EventManager(scope = this)
        val fires = mutableListOf<Map<String, Any?>>()
        eventManager.on(SystemEvents.API_QUEUE_RELEASED) { fires += it }

        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(),
            json = json,
            eventManager = eventManager,
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()
        advanceUntilIdle()

        assertTrue(fires.isEmpty(), "no fire on non-2xx — Story 5.2 may retry")
    }

    // ------------------------------------------------------------------
    // AC-9 — concurrency
    // ------------------------------------------------------------------

    @Test
    fun `concurrent enqueues do not lose events`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(batchSize = 10_000), // disable size-triggered flush
            json = json,
            // No scope → no timer loop; test drives flush explicitly.
        )

        val total = 200
        val jobs = (0 until total).map { i ->
            async(UnconfinedTestDispatcher(testScheduler)) {
                api.enqueueBucketingEvent(
                    visitorId = "v-${i % 5}",
                    experienceId = "e-$i",
                    variationId = "var-x",
                )
            }
        }
        jobs.awaitAll()

        api.flushForTest()
        assertEquals(1, http.calls.size)
        val body = Json.parseToJsonElement(http.calls.single().body!!).jsonObject
        val total2 = body["visitors"]!!.jsonArray.sumOf { it.jsonObject["events"]!!.jsonArray.size }
        assertEquals(total, total2, "every enqueue must appear in the flushed batch")
    }

    // ------------------------------------------------------------------
    // AC-8 — tracking disabled suppresses enqueue (not just flush)
    // ------------------------------------------------------------------

    @Test
    fun `enqueue is a no-op when tracking is disabled`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(http, CapturingLogger(), configWithProject(tracking = false), json)
        assertFalse(api.isTrackingEnabled())

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.enqueueConversionEvent("v-1", "g-1", goalData = null)
        api.flushForTest()

        assertEquals(0, http.calls.size, "disabled tracking must not POST")
    }

    @Test
    fun `setTrackingEnabled re-enables enqueue mid-lifecycle`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(http, CapturingLogger(), configWithProject(tracking = false), json)

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()
        assertEquals(0, http.calls.size)

        api.setTrackingEnabled(true)
        api.enqueueBucketingEvent("v-1", "e-2", "var-b")
        api.flushForTest()
        assertEquals(1, http.calls.size)
    }

    // ------------------------------------------------------------------
    // AC-3 — URL / sdkKey / projectId guards
    // ------------------------------------------------------------------

    @Test
    fun `flush is skipped when projectId is null`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        // config.data is null → projectId unresolvable.
        val api = ApiManager(http, logger, ConvertConfig(sdkKey = "sk-1"), json)

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()

        assertEquals(0, http.calls.size)
        assertTrue(logger.warnMessages().any { it.contains("projectId") }, logger.warnMessages().toString())
    }

    @Test
    fun `flush is skipped when sdkKey is null`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val logger = CapturingLogger()
        val api = ApiManager(http, logger, configWithProject(sdkKey = null), json)

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()

        assertEquals(0, http.calls.size)
        assertTrue(logger.warnMessages().any { it.contains("sdkKey") }, logger.warnMessages().toString())
    }

    // ------------------------------------------------------------------
    // AC-5 — wire shape per event type
    // ------------------------------------------------------------------

    @Test
    fun `BUCKETING event wire shape uses eventType=bucketing`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(http, CapturingLogger(), configWithProject(), json)

        api.enqueueBucketingEvent("v-1", "e-42", "var-q")
        api.flushForTest()

        val body = Json.parseToJsonElement(http.calls.single().body!!).jsonObject
        val event = body["visitors"]!!.jsonArray.single().jsonObject["events"]!!.jsonArray.single().jsonObject
        assertEquals("bucketing", event["eventType"]!!.jsonPrimitive.content)
        val data = event["data"]!!.jsonObject
        assertEquals("e-42", data["experienceId"]!!.jsonPrimitive.content)
        assertEquals("var-q", data["variationId"]!!.jsonPrimitive.content)
        // No goalId on bucketing events — the wire should omit it.
        assertNull(data["goalId"])
    }

    @Test
    fun `CONVERSION event with goalData uses eventType=conversion`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(http, CapturingLogger(), configWithProject(), json)

        val goalData = listOf(
            GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(29.99)),
            GoalData(key = GoalDataKey.TRANSACTION_ID, value = JsonPrimitive("TX-42")),
        )
        api.enqueueConversionEvent(
            visitorId = "v-1",
            goalId = "g-42",
            goalData = goalData,
        )
        api.flushForTest()

        val body = Json.parseToJsonElement(http.calls.single().body!!).jsonObject
        val event = body["visitors"]!!.jsonArray.single().jsonObject["events"]!!.jsonArray.single().jsonObject
        assertEquals("conversion", event["eventType"]!!.jsonPrimitive.content)
        val data = event["data"]!!.jsonObject
        assertEquals("g-42", data["goalId"]!!.jsonPrimitive.content)
        val gd = data["goalData"]!!.jsonArray
        assertEquals(2, gd.size)
        val amountEntry = gd.first().jsonObject
        assertEquals("amount", amountEntry["key"]!!.jsonPrimitive.content)
        assertEquals(29.99, amountEntry["value"]!!.jsonPrimitive.content.toDouble())
        val txEntry = gd.last().jsonObject
        assertEquals("transactionId", txEntry["key"]!!.jsonPrimitive.content)
        assertEquals("TX-42", txEntry["value"]!!.jsonPrimitive.content)
    }

    @Test
    fun `CONVERSION event without goalData omits goalData field`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(http, CapturingLogger(), configWithProject(), json)

        api.enqueueConversionEvent("v-1", "g-1", goalData = null)
        api.flushForTest()

        val body = Json.parseToJsonElement(http.calls.single().body!!).jsonObject
        val data = body["visitors"]!!.jsonArray.single().jsonObject["events"]!!
            .jsonArray.single().jsonObject["data"]!!.jsonObject
        assertEquals("g-1", data["goalId"]!!.jsonPrimitive.content)
        assertNull(data["goalData"], "bare conversion must omit goalData entirely")
    }

    // ------------------------------------------------------------------
    // AC-4 — enrichData / source wiring
    // ------------------------------------------------------------------

    @Test
    fun `payload includes enrichData and source from config`() = runTest {
        val http = FakeHttpClient(statusCode = 200, body = "{}")
        val api = ApiManager(
            http,
            CapturingLogger(),
            configWithProject(source = "android-sdk"),
            json,
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()

        val body = Json.parseToJsonElement(http.calls.single().body!!).jsonObject
        // When config.data is non-null → SDK has enrichment context →
        // enrichData = false (client pushes fully-formed payloads).
        assertEquals(false, body["enrichData"]!!.jsonPrimitive.content.toBooleanStrict())
        assertEquals("android-sdk", body["source"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------------
    // Test helpers
    // ------------------------------------------------------------------

    @Suppress("LongParameterList")
    private fun configWithProject(
        sdkKey: String? = "sk-1",
        batchSize: Int? = null,
        releaseInterval: Long? = null,
        tracking: Boolean? = null,
        source: String? = null,
        trackEndpoint: String? = null,
        accountId: String = "acc-1",
        projectId: String = "proj-7",
    ): ConvertConfig {
        val api = if (trackEndpoint != null) {
            ApiConfig(endpoint = ApiEndpoint(config = null, track = trackEndpoint))
        } else {
            null
        }
        val events = if (batchSize != null || releaseInterval != null) {
            EventsConfig(batchSize = batchSize, releaseInterval = releaseInterval)
        } else {
            null
        }
        val network = if (tracking != null || source != null) {
            NetworkConfig(tracking = tracking, cacheLevel = null, source = source)
        } else {
            null
        }
        return ConvertConfig(
            sdkKey = sdkKey,
            api = api,
            events = events,
            network = network,
            data = ConfigResponseData(
                accountId = accountId,
                project = ConfigProject(id = projectId),
            ),
        )
    }

    private class FakeHttpClient(
        var statusCode: Int,
        private val body: String,
    ) : HttpClient {
        data class RecordedCall(
            val method: String,
            val url: String,
            val headers: Map<String, String>,
            val body: String?,
        )

        val calls: MutableList<RecordedCall> = mutableListOf()

        override suspend fun get(url: String, headers: Map<String, String>): HttpClient.HttpResponse {
            calls += RecordedCall("GET", url, headers, null)
            return HttpClient.HttpResponse(statusCode, body, emptyMap())
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            calls += RecordedCall("POST", url, headers, body)
            return HttpClient.HttpResponse(statusCode, this.body, emptyMap())
        }
    }

    private class ThrowingHttpClient(private val error: Throwable) : HttpClient {
        var attempts: Int = 0
        override suspend fun get(url: String, headers: Map<String, String>): HttpClient.HttpResponse {
            attempts++
            throw error
        }
        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            attempts++
            throw error
        }
    }

    private class CapturingLogger : Logger {
        private val entries: MutableList<Triple<String, String, String?>> = mutableListOf()
        override fun error(message: String, throwable: Throwable?, tag: String?) {
            entries += Triple("ERROR", message, tag)
        }
        override fun warn(message: String, throwable: Throwable?, tag: String?) {
            entries += Triple("WARN", message, tag)
        }
        override fun info(message: String, tag: String?) {
            entries += Triple("INFO", message, tag)
        }
        override fun debug(message: String, tag: String?) {
            entries += Triple("DEBUG", message, tag)
        }
        fun warnMessages(): List<String> = entries.filter { it.first == "WARN" }.map { it.second }
    }
}
