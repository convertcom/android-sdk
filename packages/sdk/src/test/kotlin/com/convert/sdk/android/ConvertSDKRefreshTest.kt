/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.android.adapter.FileConfigCache
import com.convert.sdk.core.api.ApiManager
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.data.DataManager
import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.internal.sharedSerializersModule
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger

/**
 * Story 2.3 AC-10 tests for the `ConvertSDK` refresh loop.
 *
 * ### Strategy
 *
 * Two interacting realities made virtual-time testing impractical here:
 *
 *  1. [ApiManager.fetchConfig] switches to `Dispatchers.IO` via
 *     `withContext(...)`. Virtual-time dispatchers from
 *     `kotlinx-coroutines-test` do not intercept `Dispatchers.IO`, so the
 *     fetch coroutine briefly escapes to a real IO thread and the test
 *     scheduler returns from `advanceTimeBy` before the IO continuation
 *     has resumed.
 *  2. [ProcessLifecycleOwner] observer registration posts to the main
 *     looper, which Robolectric does not auto-advance — the pattern gets
 *     in the way of both paths.
 *
 * The pragmatic choice: use real wall-clock time with a tight refresh
 * interval (50 ms) and `runBlocking { delay(...) }` to wait. The tests
 * still finish in under a second each, and — importantly — they drive
 * the exact production code path rather than a virtual-time simulation
 * of it. Each test constructs the SDK with the test-visible constructor
 * parameters and engages `startRefreshLoopForTest` / `stopRefreshLoopForTest`
 * directly; [ProcessLifecycleOwner] registration never runs in these
 * tests (it's covered by `SdkLifecycleObserverTest`).
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertSDKRefreshTest {

    private lateinit var appContext: Context
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = sharedSerializersModule
    }

    /** Real refresh interval used by every test — small enough to keep
     * tests fast, large enough to let the assertion-sleep observe distinct
     * iterations without racing. */
    private val refreshIntervalMs: Long = 50L

    /** Time to wait to observe N iterations: N * interval + one fudge tick. */
    private fun waitForIterations(n: Int): Long = n * refreshIntervalMs + 30L

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    /**
     * Builds a self-contained test harness with a fresh [CoroutineScope]
     * (real `Dispatchers.Default` + SupervisorJob so the refresh coroutine
     * doesn't crash the test JVM if it throws) plus a recording HTTP
     * client that produces responses via [responder].
     */
    private data class Harness(
        val sdk: ConvertSDK,
        val http: RecordingHttpClient,
        val eventManager: EventManager,
        val dataManager: DataManager,
        val scope: CoroutineScope,
    )

    private fun buildHarness(
        preSeedData: Boolean = true,
        responder: () -> HttpClient.HttpResponse = { HttpResponses.ok() },
    ): Harness {
        val http = RecordingHttpClient(responder)
        val logger = Logger.NoOp
        val config = ConvertConfig(sdkKey = "sk-1", dataRefreshInterval = refreshIntervalMs)
        val eventManager = EventManager(logger = logger)
        val dataManager = DataManager(eventManager, config.environment).also {
            if (preSeedData) it.setData(ConfigResponseData())
        }
        val apiManager = ApiManager(http, logger, config, json)
        val cache = FileConfigCache(appContext, logger, json)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val sdk = ConvertSDK(
            config = config,
            appContext = appContext,
            logger = logger,
            eventManager = eventManager,
            initialDataManager = dataManager,
            apiManager = apiManager,
            fileConfigCache = cache,
            scope = scope,
        )
        return Harness(sdk, http, eventManager, dataManager, scope)
    }

    @Test
    fun `refresh polls at configured interval`() = runBlocking {
        val h = buildHarness(preSeedData = true)

        h.sdk.startRefreshLoopForTest()
        try {
            // Wait slightly beyond one interval — expect exactly one fetch.
            delay(waitForIterations(1))
            val afterOne = h.http.calls.get()
            assertTrue(
                "expected at least one fetch after ~$refreshIntervalMs ms; got $afterOne",
                afterOne >= 1,
            )

            // Wait one more interval — expect at least two fetches total.
            delay(refreshIntervalMs)
            val afterTwo = h.http.calls.get()
            assertTrue(
                "expected at least two fetches after ~${2 * refreshIntervalMs} ms; got $afterTwo",
                afterTwo >= 2,
            )
        } finally {
            h.sdk.stopRefreshLoopForTest()
            h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `stopRefreshLoop cancels in-flight refresh`() = runBlocking {
        val h = buildHarness(preSeedData = true)

        h.sdk.startRefreshLoopForTest()
        try {
            delay(waitForIterations(1))
            val callsBeforeStop = h.http.calls.get()
            assertTrue(
                "at least one fetch should have happened before stop; got $callsBeforeStop",
                callsBeforeStop >= 1,
            )

            h.sdk.stopRefreshLoopForTest()

            // Wait many intervals — no further fetches should happen.
            delay(waitForIterations(5))
            val callsAfterStop = h.http.calls.get()
            assertEquals(
                "stopRefreshLoop must halt further polling",
                callsBeforeStop,
                callsAfterStop,
            )
        } finally {
            h.sdk.stopRefreshLoopForTest()
            h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `startRefreshLoop without prior config does NOT fetch`() = runBlocking {
        // AC-4: the refresh loop is gated on DataManager.hasData(). Starting
        // the loop before data is seeded must NOT produce any fetch calls.
        val h = buildHarness(preSeedData = false)

        h.sdk.startRefreshLoopForTest()
        try {
            // Wait for several would-be intervals. Without data, no fetch.
            delay(waitForIterations(5))
            assertEquals(
                "no fetch should happen until dataManager.hasData() is true",
                0,
                h.http.calls.get(),
            )

            // Seed data — the gate opens. Refresh-loop coroutine was never
            // launched; a new call to startRefreshLoop starts it now.
            h.dataManager.setData(ConfigResponseData())
            h.sdk.startRefreshLoopForTest()

            delay(waitForIterations(1))
            assertTrue(
                "refresh should start once hasData is true and startRefreshLoop is re-called",
                h.http.calls.get() >= 1,
            )
        } finally {
            h.sdk.stopRefreshLoopForTest()
            h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `CONFIG_UPDATED event fires on successful refresh`() = runBlocking {
        val h = buildHarness(preSeedData = true)
        val configUpdatedCount = AtomicInteger(0)
        h.eventManager.on(SystemEvents.CONFIG_UPDATED) { _ ->
            configUpdatedCount.incrementAndGet()
        }

        h.sdk.startRefreshLoopForTest()
        try {
            delay(waitForIterations(2))
            assertTrue(
                "CONFIG_UPDATED should have fired on each successful refresh",
                configUpdatedCount.get() >= 2,
            )
        } finally {
            h.sdk.stopRefreshLoopForTest()
            h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `refresh failure does NOT exit the loop (AC-6)`() = runBlocking {
        // Alternate 500 failure / 200 success to prove the loop continues
        // through a failure instead of exiting.
        val counter = AtomicInteger(0)
        val h = buildHarness(preSeedData = true) {
            if (counter.getAndIncrement() % 2 == 1) {
                HttpResponses.ok()
            } else {
                HttpResponses.serverError()
            }
        }
        val configUpdatedCount = AtomicInteger(0)
        h.eventManager.on(SystemEvents.CONFIG_UPDATED) { _ ->
            configUpdatedCount.incrementAndGet()
        }

        h.sdk.startRefreshLoopForTest()
        try {
            // Wait for several iterations — should see alternating
            // successes and failures; CONFIG_UPDATED only fires on the
            // successes.
            delay(waitForIterations(6))
            val totalCalls = h.http.calls.get()
            assertTrue(
                "loop should have attempted at least 4 fetches through failures; got $totalCalls",
                totalCalls >= 4,
            )
            assertTrue(
                "CONFIG_UPDATED should only fire on successful refreshes; got ${configUpdatedCount.get()}",
                configUpdatedCount.get() >= 1 &&
                    configUpdatedCount.get() < totalCalls,
            )
        } finally {
            h.sdk.stopRefreshLoopForTest()
            h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `startRefreshLoop is idempotent — no double-start (AC-9)`() = runBlocking {
        val h = buildHarness(preSeedData = true)

        // Three rapid start calls — all but the first should be no-ops.
        h.sdk.startRefreshLoopForTest()
        h.sdk.startRefreshLoopForTest()
        h.sdk.startRefreshLoopForTest()

        try {
            delay(waitForIterations(1))
            // If we'd double-started, we'd see > 1 fetch per interval.
            // Give a small tolerance window: at this exact moment the loop
            // may have just kicked off a second iteration naturally. Assert
            // the total is within one interval's worth.
            val calls = h.http.calls.get()
            assertTrue(
                "no double-start: expected ~1 fetch per interval; got $calls after ${waitForIterations(1)} ms",
                calls in 1..2,
            )
        } finally {
            h.sdk.stopRefreshLoopForTest()
            h.scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    // --- Test helpers -------------------------------------------------------

    /**
     * Canned responses for the [RecordingHttpClient].
     */
    private object HttpResponses {
        fun ok(): HttpClient.HttpResponse =
            HttpClient.HttpResponse(statusCode = 200, body = "{}", headers = emptyMap())

        fun serverError(): HttpClient.HttpResponse =
            HttpClient.HttpResponse(statusCode = 500, body = "boom", headers = emptyMap())
    }

    /**
     * Records every GET/POST call; each call's response is produced by a
     * caller-supplied [responder] so tests can simulate per-iteration
     * behaviour (alternating success/failure, etc.).
     */
    private class RecordingHttpClient(
        private val responder: () -> HttpClient.HttpResponse,
    ) : HttpClient {
        val calls: AtomicInteger = AtomicInteger(0)
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            calls.incrementAndGet()
            return responder()
        }
        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse {
            calls.incrementAndGet()
            return responder()
        }
    }
}
