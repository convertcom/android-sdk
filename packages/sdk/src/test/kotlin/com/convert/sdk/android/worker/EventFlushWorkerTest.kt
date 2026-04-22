/*
 * Convert Android SDK — sdk/worker tests (Story 5.3)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.worker

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.convert.sdk.android.adapter.FileEventQueue
import com.convert.sdk.core.model.BucketingEvent
import com.convert.sdk.core.model.ConversionEvent
import com.convert.sdk.core.model.VisitorEvent
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Story 5.3 AC-10 tests for [EventFlushWorker].
 *
 * The worker runs in a WorkManager-provided context which may be a fresh
 * process after app kill. Its contract:
 *
 *  1. If [FileEventQueue] is empty → `Result.success()` without making an
 *     HTTP call.
 *  2. Read the persisted queue, build the payload via
 *     [com.convert.sdk.core.api.TrackingPayloadBuilder], POST to the track
 *     endpoint. On 2xx, clear the queue and return `Result.success()`.
 *  3. On non-2xx or any IOException (network down, DNS failure, etc.)
 *     return `Result.retry()` — WorkManager's exponential backoff handles
 *     the re-schedule. Queue is NOT cleared on retry so the next attempt
 *     picks up the same events.
 *
 * Tests use `TestListenableWorkerBuilder` (from `androidx.work:work-testing`)
 * to drive `doWork()` directly without spinning up a WorkManager
 * scheduler; [MockWebServer] provides the real HTTP endpoint.
 */
@RunWith(RobolectricTestRunner::class)
internal class EventFlushWorkerTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var queueFile: File
    private lateinit var tmpFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()

        cacheDir = File(context.filesDir, "convert-sdk")
        queueFile = File(cacheDir, "events.json")
        tmpFile = File(cacheDir, "events.json.tmp")
        queueFile.delete()
        tmpFile.delete()
    }

    @After
    fun tearDown() {
        server.shutdown()
        queueFile.delete()
        tmpFile.delete()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    private fun makeEvent(visitorId: String, eventType: String): VisitorEvent =
        VisitorEvent(
            visitorId = visitorId,
            segments = null,
            event = if (eventType == "bucketing") {
                BucketingEvent(experienceId = "exp-1", variationId = "var-1")
            } else {
                ConversionEvent(goalId = "goal-1")
            },
        )

    private fun inputData(): Data = Data.Builder()
        .putString(EventFlushWorker.KEY_SDK_KEY, "sk-test")
        .putString(EventFlushWorker.KEY_PROJECT_ID, "proj-1")
        .putString(EventFlushWorker.KEY_ACCOUNT_ID, "acc-1")
        // Story 5.3 contract: ConvertSDK passes the template URL with
        // `[project_id]` already substituted but NO `/track/` segment —
        // the worker itself appends `/track/{sdkKey}` in buildTrackUrl.
        // The MockWebServer base URL is the natural equivalent of the
        // substituted template here.
        .putString(
            EventFlushWorker.KEY_TRACK_ENDPOINT,
            server.url("/").toString().trimEnd('/'),
        )
        .build()

    @Test
    fun `worker reads persisted events and POSTs successfully`() {
        // Seed the queue on disk.
        runBlocking {
            FileEventQueue(context, Logger.NoOp).persist(
                listOf(
                    makeEvent("v-1", "bucketing"),
                    makeEvent("v-2", "conversion"),
                ),
            )
        }
        server.enqueue(MockResponse().setResponseCode(200))

        val worker: EventFlushWorker =
            TestListenableWorkerBuilder<EventFlushWorker>(context)
                .setInputData(inputData())
                .build()

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.success(), result)

        val recorded: RecordedRequest? = server.takeRequest(2, TimeUnit.SECONDS)
        assertNotNull("worker must POST to the track endpoint", recorded)
        // The body is the TrackingPayloadBuilder output — assert the sdkKey
        // did NOT leak into it (payload carries accountId / projectId only).
        val body = recorded!!.body.readUtf8()
        assertTrue("body must contain visitorId", body.contains("\"visitorId\""))
        assertTrue("body must contain accountId", body.contains("\"accountId\":\"acc-1\""))
        assertTrue("body must contain projectId", body.contains("\"projectId\":\"proj-1\""))
        // URL path: exactly /track/{sdkKey} (no duplicated `/track/`
        // segment — regression guard against an earlier bug where the
        // enqueue side was emitting `.../track/` and the worker double-
        // appended).
        assertEquals(
            "track URL path must be exactly /track/sk-test",
            "/track/sk-test",
            recorded.path,
        )

        // Queue cleared on success
        runBlocking {
            assertEquals(
                "queue must be cleared after successful flush",
                0,
                FileEventQueue(context, Logger.NoOp).size(),
            )
        }
    }

    @Test
    fun `worker returns Result retry on HTTP failure`() {
        runBlocking {
            FileEventQueue(context, Logger.NoOp).persist(listOf(makeEvent("v-1", "bucketing")))
        }
        server.enqueue(MockResponse().setResponseCode(500))

        val worker: EventFlushWorker =
            TestListenableWorkerBuilder<EventFlushWorker>(context)
                .setInputData(inputData())
                .build()

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
        // Queue NOT cleared on retry
        runBlocking {
            assertEquals(
                "queue must NOT be cleared when worker returns retry",
                1,
                FileEventQueue(context, Logger.NoOp).size(),
            )
        }
    }

    @Test
    fun `worker returns Result success when queue is empty`() {
        // No seeding — queue is empty.
        val worker: EventFlushWorker =
            TestListenableWorkerBuilder<EventFlushWorker>(context)
                .setInputData(inputData())
                .build()

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.success(), result)
        // No HTTP call should have fired.
        val recorded: RecordedRequest? = server.takeRequest(200, TimeUnit.MILLISECONDS)
        assertNull("no HTTP request should fire when queue is empty", recorded)
    }

    @Test
    fun `worker returns Result retry on IOException`() {
        runBlocking {
            FileEventQueue(context, Logger.NoOp).persist(listOf(makeEvent("v-1", "bucketing")))
        }
        // Shutdown the server to force a connection failure.
        server.shutdown()

        val worker: EventFlushWorker =
            TestListenableWorkerBuilder<EventFlushWorker>(context)
                .setInputData(inputData())
                .build()

        val result = runBlocking { worker.doWork() }

        assertEquals(ListenableWorker.Result.retry(), result)
        // Queue NOT cleared
        runBlocking {
            assertEquals(1, FileEventQueue(context, Logger.NoOp).size())
        }
    }

    @Test
    fun `worker returns Result success when required input data is missing`() {
        // Seed a non-empty queue but omit sdkKey input — the worker has
        // nothing to POST to, so it logs and returns success to avoid an
        // infinite retry loop on mis-configured input.
        runBlocking {
            FileEventQueue(context, Logger.NoOp).persist(listOf(makeEvent("v-1", "bucketing")))
        }
        val worker: EventFlushWorker =
            TestListenableWorkerBuilder<EventFlushWorker>(context)
                // No input data — all keys null.
                .build()

        val result = runBlocking { worker.doWork() }

        assertEquals(
            "missing input data must not cause an infinite retry loop",
            ListenableWorker.Result.success(),
            result,
        )
    }
}
