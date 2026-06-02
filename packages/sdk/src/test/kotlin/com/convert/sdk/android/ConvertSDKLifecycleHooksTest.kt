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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
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
        // AC-6.2 WorkManager isolation: cancel all work and close the internal
        // WorkManager database between tests. Without closeWorkDatabase(), the
        // SQLite-backed queue leaks enqueued work across tests in the same JVM
        // run — the "exactly one job" assertion would accumulate jobs from prior
        // test cases and fail non-deterministically. The @Before re-initialises
        // the singleton via initializeTestWorkManager, which calls
        // WorkManagerImpl.setDelegate() to replace the singleton — but that alone
        // does not flush the on-disk state that the previous instance wrote.
        // closeWorkDatabase() releases the connection so the next init starts
        // with a clean database.
        runCatching {
            WorkManager.getInstance(context).cancelAllWork()
        }
        runCatching {
            WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }

    // ---------------------------------------------------------------
    // AC-2 — onStop flushes then enqueues the worker
    // ---------------------------------------------------------------

    @Test
    fun `onStop flushes and enqueues worker`() {
        // AC-6.2: drive the SDK coroutine via an injected TestScope so the test
        // is deterministic — no fixed Thread.sleep / wall-clock waits.
        val testScope = TestScope()
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
            scope = testScope,
        )

        // Drive the lifecycle hook — launches a coroutine on sdk.scope (= testScope).
        sdk.onProcessStopForTest()

        // Drain all coroutines launched on testScope: flushNow, snapshotQueue,
        // persist, and enqueueFlushWorker all run to completion synchronously
        // before advanceUntilIdle() returns.
        testScope.advanceUntilIdle()

        // Drain main-looper runnables posted by WorkManager's enqueueUniqueWork
        // (Robolectric runs main-looper in paused mode; a single idle() call is
        // sufficient — no sleep loop needed).
        shadowOf(Looper.getMainLooper()).idle()

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
    // AC-3 — onStart restores persisted events (PR #39 H1 fix)
    // onProcessStart now uses drain() + reenqueuePersisted() (atomic
    // read-and-remove with visitorId-aware dedup) instead of
    // read() + enqueueAll() + clear().
    // ---------------------------------------------------------------

    @Test
    fun `onStart restores persisted events via drain and reenqueuePersisted`() {
        // AC-6.2: drive the SDK coroutine via an injected TestScope so the test
        // is deterministic — no fixed Thread.sleep / wall-clock waits.
        val testScope = TestScope()
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
            scope = testScope,
        )

        // Drive the lifecycle hook — launches a coroutine on sdk.scope (= testScope).
        sdk.onProcessStartForTest()

        // Drain all coroutines launched on testScope: drain() and reenqueuePersisted()
        // both run to completion synchronously before advanceUntilIdle() returns.
        testScope.advanceUntilIdle()

        assertTrue(
            "reenqueuePersisted must be called on onStart (not enqueueAll)",
            recordingApi.reenqueuePersistedCalled.get(),
        )
        assertEquals(
            "persisted events must be passed to reenqueuePersisted",
            persisted.size,
            recordingApi.reenqueuePersistedReceived.size,
        )
        assertEquals("v-restored", recordingApi.reenqueuePersistedReceived.first().visitorId)
        assertTrue(
            "drain() must have been called (atomically removes the persisted queue)",
            recordingQueue.drained.get(),
        )
    }

    @Test
    fun `onStart is a no-op when persisted queue is empty`() {
        // AC-6.2: drive the SDK coroutine via an injected TestScope so the test
        // is deterministic — no fixed Thread.sleep / wall-clock waits.
        val testScope = TestScope()
        val recordingQueue = RecordingEventQueue(initial = emptyList())
        val recordingApi = RecordingApiManager()
        val sdk = buildSdk(
            apiManager = recordingApi,
            eventQueue = recordingQueue,
            scope = testScope,
        )

        // Drive the lifecycle hook — launches a coroutine on sdk.scope (= testScope).
        sdk.onProcessStartForTest()

        // Drain all coroutines launched on testScope: drain() runs, observes an
        // empty list, and returns without calling reenqueuePersisted.
        testScope.advanceUntilIdle()

        assertFalse(
            "reenqueuePersisted must NOT be called when there are no persisted events",
            recordingApi.reenqueuePersistedCalled.get(),
        )
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private fun buildSdk(
        apiManager: ApiManager,
        eventQueue: EventQueue,
        scope: CoroutineScope? = null,
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
            scope = scope,
        )
    }

    /**
     * [EventQueue] recorder that starts with a seeded list (simulates
     * events left on disk from a previous session) and records every
     * mutation. Implements [drain] as an atomic read-and-remove per TD-1.
     */
    private class RecordingEventQueue(initial: List<VisitorEvent> = emptyList()) : EventQueue {
        val persisted: MutableList<VisitorEvent> = initial.toMutableList()
        val cleared: AtomicBoolean = AtomicBoolean(false)
        val drained: AtomicBoolean = AtomicBoolean(false)
        override suspend fun persist(events: List<VisitorEvent>) {
            persisted.addAll(events)
        }
        override suspend fun read(): List<VisitorEvent> = persisted.toList()
        override suspend fun clear() {
            cleared.set(true)
            persisted.clear()
        }
        override suspend fun size(): Int = persisted.size
        override suspend fun drain(): List<VisitorEvent> {
            drained.set(true)
            val snap = persisted.toList()
            persisted.clear()
            return snap
        }
    }

    /**
     * [ApiManager] subclass that records flush / reenqueuePersisted /
     * snapshotQueue invocations for the test assertions. Uses a no-op
     * HTTP client; we override `flushNow`, `snapshotQueue`, and
     * `reenqueuePersisted` directly to keep tests deterministic.
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
        val reenqueuePersistedCalled: AtomicBoolean = AtomicBoolean(false)
        val snapshotQueueCalled: AtomicInteger = AtomicInteger(0)
        val enqueueAllReceived: MutableList<VisitorEvent> = mutableListOf()
        val reenqueuePersistedReceived: MutableList<VisitorEvent> = mutableListOf()

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

        override fun reenqueuePersisted(events: List<VisitorEvent>) {
            reenqueuePersistedCalled.set(true)
            reenqueuePersistedReceived.addAll(events)
        }
    }
}
