/*
 * Convert Android SDK — sdk/worker tests (PR #39 Cluster 1 AC-1.3)
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
import com.convert.sdk.core.model.VisitorEvent
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * PR #39 Cluster 1 tests for [EventFlushWorker]'s drain-based flush.
 *
 * Covers:
 * - **AC-1.3** worker POST failure → drained events re-persisted before Result.retry()
 * - Successful flush → events already removed by drain (no separate clear needed)
 */
@RunWith(RobolectricTestRunner::class)
internal class EventFlushWorkerDrainTest {

    private lateinit var context: Context
    private lateinit var server: MockWebServer
    private lateinit var cacheDir: File
    private lateinit var queueFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        server = MockWebServer()
        server.start()
        cacheDir = File(context.filesDir, "convert-sdk")
        queueFile = File(cacheDir, "events.json")
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
        }
    }

    @After
    fun tearDown() {
        server.shutdown()
        if (cacheDir.exists()) {
            cacheDir.listFiles()?.forEach { it.delete() }
            cacheDir.delete()
        }
    }

    private fun seedQueue(vararg visitorIds: String) = runBlocking {
        FileEventQueue(context, Logger.NoOp).persist(
            visitorIds.map { id ->
                VisitorEvent(
                    visitorId = id,
                    event = BucketingEvent(experienceId = "e-1", variationId = "var-1"),
                )
            },
        )
    }

    private fun queueSize(): Int = runBlocking {
        FileEventQueue(context, Logger.NoOp).size()
    }

    private fun inputData(): Data = Data.Builder()
        .putString(EventFlushWorker.KEY_SDK_KEY, "sk-drain-test")
        .putString(EventFlushWorker.KEY_PROJECT_ID, "proj-drain")
        .putString(EventFlushWorker.KEY_ACCOUNT_ID, "acc-drain")
        .putString(
            EventFlushWorker.KEY_TRACK_ENDPOINT,
            server.url("/").toString().trimEnd('/'),
        )
        .build()

    private fun buildWorker(): EventFlushWorker =
        TestListenableWorkerBuilder<EventFlushWorker>(context)
            .setInputData(inputData())
            .build()

    // ---------------------------------------------------------------
    // AC-1.3 — delivery failure → re-persist before Result.retry()
    // ---------------------------------------------------------------

    @Test
    fun `worker re-persists drained events on non-2xx response before returning retry`() {
        seedQueue("v-1", "v-2")
        assertEquals("pre-condition: 2 events in queue", 2, queueSize())

        server.enqueue(MockResponse().setResponseCode(500))

        val result = runBlocking { buildWorker().doWork() }

        assertEquals("worker must return retry on non-2xx", ListenableWorker.Result.retry(), result)
        assertEquals(
            "drained events must be re-persisted before retry (AC-1.3)",
            2,
            queueSize(),
        )
    }

    @Test
    fun `worker re-persists drained events on IOException before returning retry`() {
        seedQueue("v-1")
        assertEquals("pre-condition: 1 event in queue", 1, queueSize())

        // Shut down server to force an IOException.
        server.shutdown()

        val result = runBlocking { buildWorker().doWork() }

        assertEquals("worker must return retry on IOException", ListenableWorker.Result.retry(), result)
        assertEquals(
            "drained events must be re-persisted on IOException (AC-1.3)",
            1,
            queueSize(),
        )
    }

    // ---------------------------------------------------------------
    // Successful flush — drain atomically removes events; no separate clear
    // ---------------------------------------------------------------

    @Test
    fun `worker drain-based success - events removed without separate clear`() {
        seedQueue("v-1", "v-2", "v-3")
        assertEquals("pre-condition: 3 events in queue", 3, queueSize())

        server.enqueue(MockResponse().setResponseCode(200))

        val result = runBlocking { buildWorker().doWork() }

        assertEquals("worker must succeed on 2xx", ListenableWorker.Result.success(), result)
        assertEquals(
            "events must be gone after successful drain-based flush",
            0,
            queueSize(),
        )
    }

    // ---------------------------------------------------------------
    // Empty queue path — fast-return success
    // ---------------------------------------------------------------

    @Test
    fun `worker returns success immediately on empty queue - no HTTP call`() {
        // Queue is empty — no seedQueue call.
        val result = runBlocking { buildWorker().doWork() }

        assertEquals("empty queue must return success", ListenableWorker.Result.success(), result)
        // No request should have been dispatched.
        val recorded = server.takeRequest(200, java.util.concurrent.TimeUnit.MILLISECONDS)
        assertEquals("no HTTP call should fire on empty queue", null, recorded)
    }
}
