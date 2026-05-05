/*
 * Convert Android SDK — core/api retry tests (Story 5.2)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ApiConfig
import com.convert.sdk.core.config.ApiEndpoint
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.EventsConfig
import com.convert.sdk.core.model.BucketingEvent
import com.convert.sdk.core.model.VisitorEvent
import com.convert.sdk.core.model.generated.ConfigProject
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.EventQueue
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Story 5.2 AC-7 tests for [ApiManager]'s retry + persist + dedup path.
 *
 * Uses `TestScope` + `StandardTestDispatcher` for virtual time so
 * `delay(10_000)` completes instantly via `advanceTimeBy`. The
 * [StandardTestDispatcher] is passed as ApiManager's `ioDispatcher` so
 * the `withContext(ioDispatcher)` wrapping the HTTP POST also participates
 * in virtual time — without that, the POST runs on a real thread and the
 * test scheduler loses track of it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class ApiManagerRetryTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    // ---------------------------------------------------------------
    // AC-2 — HTTP 500 triggers exponential backoff (10s, 20s, 40s)
    // ---------------------------------------------------------------

    @Test
    fun `HTTP 500 triggers exponential retries at 10s 20s 40s`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val http = SequencedHttpClient(
            // 1 initial POST + 3 retries, all 500 → after the 3rd retry,
            // the snapshot is persisted instead of a 5th POST.
            responses = listOf(500, 500, 500, 500),
        )
        val queue = InMemoryEventQueue()
        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(),
            json = json,
            eventQueue = queue,
            scope = this,
            ioDispatcher = dispatcher,
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()
        runCurrent() // initial POST resolves
        assertEquals(1, http.calls.size, "initial POST")

        advanceTimeBy(9_999) // not yet retried
        runCurrent()
        assertEquals(1, http.calls.size, "before 10s tick")

        advanceTimeBy(2) // ~10s elapsed
        runCurrent()
        assertEquals(2, http.calls.size, "first retry at 10s")

        advanceTimeBy(20_000) // ~30s elapsed
        runCurrent()
        assertEquals(3, http.calls.size, "second retry at 20s after first")

        advanceTimeBy(40_000) // ~70s elapsed
        runCurrent()
        assertEquals(4, http.calls.size, "third retry at 40s after second")

        // Cancel the timer BEFORE further advancement — a forever-ticking
        // timer loop would make the test hang or double-persist.
        api.cancelTimerForTest()
        runCurrent() // drain the persist launch
        assertEquals(1, queue.persisted.size, "snapshot must persist after max retries")
    }

    // ---------------------------------------------------------------
    // AC-3 — IOException (UnknownHostException / ConnectException):
    //         skip retries, persist immediately
    // ---------------------------------------------------------------

    @Test
    fun `IOException skips retries and persists events`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val http = ThrowingHttpClient(UnknownHostException("no network"))
        val queue = InMemoryEventQueue()
        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(),
            json = json,
            eventQueue = queue,
            scope = this,
            ioDispatcher = dispatcher,
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()
        runCurrent()

        assertEquals(1, http.attempts, "IOException path must not retry")
        assertEquals(1, queue.persisted.size, "IOException must persist snapshot")
        // Cancel the timer before further advancement so no stray ticks
        // rerun the flush and double-persist.
        api.cancelTimerForTest()
    }

    @Test
    fun `ConnectException treated as offline and persists without retry`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val http = ThrowingHttpClient(ConnectException("refused"))
        val queue = InMemoryEventQueue()
        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(),
            json = json,
            eventQueue = queue,
            scope = this,
            ioDispatcher = dispatcher,
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()
        runCurrent()
        assertEquals(1, http.attempts)
        assertEquals(1, queue.persisted.size)
        api.cancelTimerForTest()
    }

    // ---------------------------------------------------------------
    // AC-3 — SocketTimeoutException: treated as transient server-side
    //         failure → apply exponential backoff per AC-2 (not persist-
    //         immediately like UnknownHostException/ConnectException)
    // ---------------------------------------------------------------

    @Test
    fun `SocketTimeoutException applies backoff retries then persists`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        // 1 initial POST + 3 retries, all timeout → persist after 3rd retry
        val http = ThrowingHttpClient(SocketTimeoutException("timeout"))
        val queue = InMemoryEventQueue()
        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(),
            json = json,
            eventQueue = queue,
            scope = this,
            ioDispatcher = dispatcher,
        )

        api.enqueueBucketingEvent("v-1", "e-1", "var-a")
        api.flushForTest()
        runCurrent()
        // Initial attempt fires immediately
        assertEquals(1, http.attempts, "initial attempt")

        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(2, http.attempts, "first retry at 10s")

        advanceTimeBy(20_001)
        runCurrent()
        assertEquals(3, http.attempts, "second retry at 20s")

        advanceTimeBy(40_001)
        runCurrent()
        assertEquals(4, http.attempts, "third retry at 40s")

        // Cancel the timer BEFORE advanceUntilIdle so the infinite loop
        // does not appear as an uncompleted coroutine to the test runner.
        api.cancelTimerForTest()
        advanceUntilIdle() // drain the persist launch
        assertEquals(1, queue.persisted.size, "must persist after 3 retries exhausted")
    }

    // ---------------------------------------------------------------
    // AC-6 — persisted events re-enqueued on network restore deduplicate
    //         using Set<TrackingEvent> content equality (data classes).
    //         Per patched spec F-002/F-014 option c.
    // ---------------------------------------------------------------

    @Test
    fun `persisted events re-enqueued on network restore do not duplicate`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val http = SequencedHttpClient(responses = listOf(200))
        val api = ApiManager(
            httpClient = http,
            logger = CapturingLogger(),
            config = configWithProject(),
            json = json,
            scope = this,
            ioDispatcher = dispatcher,
        )

        // Enqueue a live event.
        val liveTrackingEvent = BucketingEvent(experienceId = "e-1", variationId = "var-a")
        api.enqueueBucketingEvent("v-1", "e-1", "var-a")

        // Re-enqueue same event (duplicate) and a fresh distinct event.
        val duplicate = VisitorEvent(visitorId = "v-1", event = liveTrackingEvent)
        val fresh = VisitorEvent(
            visitorId = "v-1",
            event = BucketingEvent(experienceId = "e-2", variationId = "var-b"),
        )
        api.reenqueuePersisted(listOf(duplicate, fresh))

        // After dedup: live event + fresh event = 2 entries; duplicate dropped.
        val snapshot = api.snapshotQueueForTest()
        assertEquals(2, snapshot.size, "duplicate must be dropped; fresh must be accepted")

        api.cancelTimerForTest()
    }

    // ---------------------------------------------------------------
    // Test helpers
    // ---------------------------------------------------------------

    private fun configWithProject(
        sdkKey: String? = "sk-1",
        accountId: String = "acc-1",
        projectId: String = "proj-7",
    ): ConvertConfig = ConvertConfig(
        sdkKey = sdkKey,
        api = ApiConfig(endpoint = ApiEndpoint(config = null, track = "https://track.test/[project_id]")),
        // Keep the timer dormant: big releaseInterval.
        events = EventsConfig(batchSize = 10_000, releaseInterval = 1_000_000L),
        data = ConfigResponseData(
            accountId = accountId,
            project = ConfigProject(id = projectId),
        ),
    )

    /**
     * HTTP client that returns pre-sequenced status codes. Exhausting the
     * sequence falls back to the last response — keeps tests resilient
     * against an extra `runCurrent()`.
     */
    private class SequencedHttpClient(
        private val responses: List<Int>,
    ) : HttpClient {
        data class RecordedCall(val url: String, val body: String?)
        val calls: MutableList<RecordedCall> = mutableListOf()
        override suspend fun get(url: String, headers: Map<String, String>) =
            HttpClient.HttpResponse(200, "{}", emptyMap())
        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            calls += RecordedCall(url, body)
            val idx = (calls.size - 1).coerceAtMost(responses.size - 1)
            return HttpClient.HttpResponse(responses[idx], "{}", emptyMap())
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

    private class InMemoryEventQueue : EventQueue {
        val persisted: MutableList<VisitorEvent> = mutableListOf()
        override suspend fun persist(events: List<VisitorEvent>) {
            persisted.addAll(events)
        }
        override suspend fun read(): List<VisitorEvent> = persisted.toList()
        override suspend fun clear() {
            persisted.clear()
        }
        override suspend fun size(): Int = persisted.size
    }

    private class CapturingLogger : Logger {
        override fun error(message: String, throwable: Throwable?, tag: String?) = Unit
        override fun warn(message: String, throwable: Throwable?, tag: String?) = Unit
        override fun info(message: String, tag: String?) = Unit
        override fun debug(message: String, tag: String?) = Unit
    }
}
