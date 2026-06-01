/*
 * Convert Android SDK — ConvertSDK lifecycle-hook tests (Story 5.3 AC-10)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.convert.sdk.android.worker.EventFlushWorker
import com.convert.sdk.core.api.ApiManager
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
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Story 5.3 AC-10 tests for [ConvertSDK]'s extended lifecycle wiring.
 *
 * The observer class [com.convert.sdk.android.lifecycle.SdkLifecycleObserver]
 * is unchanged (SdkLifecycleObserverTest from Story 2.3 stays valid); what
 * Story 5.3 changes is the lambdas ConvertSDK installs. Specifically:
 *
 *  - onStop now additionally (a) flushes the ApiManager, (b) persists the
 *    snapshot to FileEventQueue, (c) enqueues an [EventFlushWorker] via
 *    WorkManager.
 *  - onStart now additionally reads FileEventQueue into the ApiManager
 *    (mirroring the NetworkObserver restore path) — AC-3.
 *
 * These tests exercise the internal hooks [ConvertSDK.onProcessStop] and
 * [ConvertSDK.onProcessStart] directly rather than driving
 * ProcessLifecycleOwner — Robolectric can initialise a TestWorkManager
 * but can't easily move the process lifecycle between STARTED and
 * STOPPED from a unit test.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertSDKLifecycleHooksTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Install the synchronous test scheduler so WorkManager.enqueueUniqueWork
        // resolves immediately rather than kicking off an OS-scheduled worker.
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.VERBOSE).build(),
        )
    }

    @After
    fun tearDown() {
        // WorkManager holds a singleton per process — cancelling uniqueWork by
        // name keeps the singleton alive (required because tests share the
        // Robolectric application) but makes sure no stale enqueue leaks
        // across tests in the same JVM run.
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(EventFlushWorker.UNIQUE_WORK_NAME)
        }
    }

    // ---------------------------------------------------------------
    // AC-2 — onStop flushes then enqueues the worker
    // ---------------------------------------------------------------

    @Test
    fun `onStop flushes and enqueues worker`() = runBlocking {
        val events = listOf(
            VisitorEvent(
                visitorId = "v-1",
                segments = null,
                event = BucketingEvent(experienceId = "exp-1", variationId = "var-1"),
            ),
        )
        val recordingQueue = RecordingEventQueue()
        val recordingApi = RecordingApiManager(snapshotReturns = events)
        val sdk = buildSdk(
            apiManager = recordingApi,
            eventQueue = recordingQueue,
        )

        // Drive the lifecycle hook we care about.
        sdk.onProcessStopForTest()
        // onProcessStop launches a coroutine on sdk.scope; wait for each
        // observable milestone to tick through before asserting. The
        // Thread.sleeps are tiny — the entire chain runs within tens of
        // milliseconds on a Default dispatcher.
        val deadline = System.currentTimeMillis() + 5_000
        while (!recordingApi.flushCalled.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        while (recordingApi.snapshotQueueCalled.get() < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        while (recordingQueue.persisted.size < 1 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        // Drain main-looper runnables so WorkManager's enqueueUniqueWork
        // observable sees the scheduled work. Robolectric runs main-looper
        // work in paused mode; without this, getWorkInfos returns an empty
        // list even though enqueueUniqueWork was called.
        shadowOf(Looper.getMainLooper()).idle()
        // Small final idle loop — WorkManager sync is async and may need
        // a couple of looper ticks before getWorkInfosForUniqueWork
        // reflects the enqueue.
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }

        assertTrue("flushNow must be called on onStop", recordingApi.flushCalled.get())
        assertEquals(
            "snapshot must be persisted verbatim to the event queue",
            1,
            recordingQueue.persisted.size,
        )

        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(EventFlushWorker.UNIQUE_WORK_NAME)
            .get()
        assertEquals("exactly one EventFlushWorker must be enqueued", 1, workInfos.size)
        assertNotNull(workInfos[0])
    }

    // ---------------------------------------------------------------
    // AC-3 — onStart restores persisted events
    // ---------------------------------------------------------------

    @Test
    fun `onStart restores persisted events`() = runBlocking {
        val persisted = listOf(
            VisitorEvent(
                visitorId = "v-restored",
                segments = null,
                event = BucketingEvent(experienceId = "exp-1", variationId = "var-1"),
            ),
        )
        val recordingQueue = RecordingEventQueue(initial = persisted)
        val recordingApi = RecordingApiManager()
        val sdk = buildSdk(
            apiManager = recordingApi,
            eventQueue = recordingQueue,
        )

        sdk.onProcessStartForTest()
        // Wait for the coroutine.
        while (!recordingApi.enqueueAllCalled.get()) { Thread.sleep(5) }

        assertEquals(
            "persisted events must be re-enqueued onto the ApiManager",
            persisted.size,
            recordingApi.enqueueAllReceived.size,
        )
        assertEquals("v-restored", recordingApi.enqueueAllReceived.first().visitorId)
        assertTrue(
            "persisted queue must be cleared after restoration",
            recordingQueue.cleared.get(),
        )
    }

    @Test
    fun `onStart is a no-op when persisted queue is empty`() = runBlocking {
        val recordingQueue = RecordingEventQueue(initial = emptyList())
        val recordingApi = RecordingApiManager()
        val sdk = buildSdk(
            apiManager = recordingApi,
            eventQueue = recordingQueue,
        )

        sdk.onProcessStartForTest()
        // Give the launched coroutine a chance to observe the empty read.
        Thread.sleep(50)

        assertFalse(
            "enqueueAll must NOT be called when there are no persisted events",
            recordingApi.enqueueAllCalled.get(),
        )
        assertFalse(
            "clear must NOT be called when there was nothing to read",
            recordingQueue.cleared.get(),
        )
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun buildSdk(
        apiManager: ApiManager,
        eventQueue: EventQueue,
    ): ConvertSDK {
        val config = ConvertConfig(
            sdkKey = "sk-test",
            api = ApiConfig(endpoint = ApiEndpoint(track = "https://[project_id].track.test/v1/")),
            events = EventsConfig(batchSize = 10_000, releaseInterval = 1_000_000L),
            data = ConfigResponseData(
                accountId = "acc-1",
                project = ConfigProject(id = "proj-1"),
            ),
        )
        return ConvertSDK(
            config = config,
            appContext = context,
            logger = Logger.NoOp,
            apiManager = apiManager,
            fileEventQueue = eventQueue,
        )
    }

    /**
     * [EventQueue] recorder that starts with a seeded list (simulates
     * events left on disk from a previous session) and records every
     * mutation.
     */
    private class RecordingEventQueue(initial: List<VisitorEvent> = emptyList()) : EventQueue {
        val persisted: MutableList<VisitorEvent> = initial.toMutableList()
        val cleared: AtomicBoolean = AtomicBoolean(false)
        override suspend fun persist(events: List<VisitorEvent>) {
            persisted.addAll(events)
        }
        override suspend fun read(): List<VisitorEvent> = persisted.toList()
        override suspend fun clear() {
            cleared.set(true)
            persisted.clear()
        }
        override suspend fun size(): Int = persisted.size
    }

    /**
     * [ApiManager] subclass that records flush / enqueueAll / snapshotQueue
     * invocations for the test assertions. Uses a no-op HTTP client so
     * the superclass's `flush()` would be a no-op on its own; we override
     * `flushNow` and `snapshotQueue` directly to keep the test fully
     * deterministic.
     */
    private class RecordingApiManager(
        private val snapshotReturns: List<VisitorEvent> = emptyList(),
    ) : ApiManager(
        httpClient = object : HttpClient {
            override suspend fun get(url: String, headers: Map<String, String>) =
                HttpClient.HttpResponse(200, "{}", emptyMap())
            override suspend fun post(url: String, body: String, headers: Map<String, String>) =
                HttpClient.HttpResponse(200, "{}", emptyMap())
        },
        logger = Logger.NoOp,
        config = ConvertConfig(
            sdkKey = "sk-test",
            events = EventsConfig(batchSize = 10_000, releaseInterval = 1_000_000L),
            data = ConfigResponseData(
                accountId = "acc-1",
                project = ConfigProject(id = "proj-1"),
            ),
        ),
        json = Json { ignoreUnknownKeys = true },
    ) {
        val flushCalled: AtomicBoolean = AtomicBoolean(false)
        val enqueueAllCalled: AtomicBoolean = AtomicBoolean(false)
        val snapshotQueueCalled: AtomicInteger = AtomicInteger(0)
        val enqueueAllReceived: MutableList<VisitorEvent> = mutableListOf()

        override suspend fun flushNow() {
            flushCalled.set(true)
        }

        override fun snapshotQueue(): List<VisitorEvent> {
            snapshotQueueCalled.incrementAndGet()
            return snapshotReturns
        }

        override fun enqueueAll(events: List<VisitorEvent>) {
            enqueueAllCalled.set(true)
            enqueueAllReceived.addAll(events)
        }
    }
}
