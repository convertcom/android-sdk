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
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * The test instantiates a `ConvertSDK` via the test-visible secondary
 * constructor that accepts a caller-supplied [CoroutineScope] (a
 * [TestScope]) and pre-built [ApiManager] / [FileConfigCache]. This lets
 * the test:
 *  - Drive virtual time with `advanceTimeBy` to verify polling cadence.
 *  - Swap in a [RecordingHttpClient] to control what each
 *    `apiManager.fetchConfig()` returns per iteration.
 *  - Engage the refresh-state machine directly via the SDK's internal
 *    `startRefreshLoop` / `stopRefreshLoop` helpers, bypassing
 *    `ProcessLifecycleOwner` entirely (the production path registers a
 *    real `SdkLifecycleObserver` that calls those same helpers on
 *    ON_START / ON_STOP — covered by `SdkLifecycleObserverTest`).
 *
 * Using the refresh-state API directly means these tests do NOT rely on
 * Looper / main-thread bookkeeping and do NOT install a real observer
 * with `ProcessLifecycleOwner`; they validate the refresh contract
 * without coupling to Android lifecycle internals.
 *
 * Robolectric is still required because `FileConfigCache` reads
 * `context.filesDir` and the SDK's logger wires an `AndroidLogger`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
internal class ConvertSDKRefreshTest {

    private lateinit var appContext: Context
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `refresh polls at configured interval`() = runTest {
        val http = RecordingHttpClient(responder = { AlwaysSucceed.response() })
        val logger = Logger.NoOp
        val config = ConvertConfig(sdkKey = "sk-1", dataRefreshInterval = 1_000L)
        val eventManager = EventManager(logger = logger)
        val dataManager = DataManager(eventManager, config.environment).also {
            // Pre-seed so the refresh loop passes its hasData() gate.
            it.setData(ConfigResponseData())
        }
        val apiManager = ApiManager(http, logger, config, json)
        val cache = FileConfigCache(appContext, logger)
        val testScope = TestScope(StandardTestDispatcher(testScheduler))

        val sdk = ConvertSDK(
            config = config,
            appContext = appContext,
            logger = logger,
            eventManager = eventManager,
            initialDataManager = dataManager,
            apiManager = apiManager,
            fileConfigCache = cache,
            scope = testScope,
        )

        sdk.startRefreshLoopForTest()

        // No network calls yet — the loop delays first.
        advanceTimeBy(500L)
        assertEquals(0, http.calls.get())

        // One full interval — exactly one fetch should have run.
        advanceTimeBy(500L)
        advanceUntilIdle()
        assertEquals(1, http.calls.get())

        // Another interval — another fetch.
        advanceTimeBy(1_000L)
        advanceUntilIdle()
        assertEquals(2, http.calls.get())

        sdk.stopRefreshLoopForTest()
    }

    @Test
    fun `stopRefreshLoop cancels in-flight refresh`() = runTest {
        val http = RecordingHttpClient(responder = { AlwaysSucceed.response() })
        val config = ConvertConfig(sdkKey = "sk-1", dataRefreshInterval = 1_000L)
        val eventManager = EventManager(logger = Logger.NoOp)
        val dataManager = DataManager(eventManager, config.environment)
            .also { it.setData(ConfigResponseData()) }
        val apiManager = ApiManager(http, Logger.NoOp, config, json)
        val cache = FileConfigCache(appContext, Logger.NoOp)
        val testScope = TestScope(StandardTestDispatcher(testScheduler))

        val sdk = ConvertSDK(
            config = config,
            appContext = appContext,
            logger = Logger.NoOp,
            eventManager = eventManager,
            initialDataManager = dataManager,
            apiManager = apiManager,
            fileConfigCache = cache,
            scope = testScope,
        )
        sdk.startRefreshLoopForTest()
        advanceTimeBy(1_000L)
        advanceUntilIdle()
        assertEquals("one fetch expected before stop", 1, http.calls.get())

        sdk.stopRefreshLoopForTest()

        // Let many more intervals elapse — no further fetches should run.
        advanceTimeBy(5_000L)
        advanceUntilIdle()
        assertEquals("stopRefreshLoop must halt future polls", 1, http.calls.get())
    }

    @Test
    fun `startRefreshLoop without prior config does NOT fetch until READY`() = runTest {
        val http = RecordingHttpClient(responder = { AlwaysSucceed.response() })
        val config = ConvertConfig(sdkKey = "sk-1", dataRefreshInterval = 500L)
        val eventManager = EventManager(logger = Logger.NoOp)
        val dataManager = DataManager(eventManager, config.environment) // not seeded
        val apiManager = ApiManager(http, Logger.NoOp, config, json)
        val cache = FileConfigCache(appContext, Logger.NoOp)
        val testScope = TestScope(StandardTestDispatcher(testScheduler))

        val sdk = ConvertSDK(
            config = config,
            appContext = appContext,
            logger = Logger.NoOp,
            eventManager = eventManager,
            initialDataManager = dataManager,
            apiManager = apiManager,
            fileConfigCache = cache,
            scope = testScope,
        )

        // onStart-style trigger BEFORE any data is seeded.
        sdk.startRefreshLoopForTest()
        advanceTimeBy(2_000L)
        advanceUntilIdle()

        // Gate was closed (hasData == false), so no fetch should have fired.
        assertEquals(0, http.calls.get())

        // Now seed data — fires READY, which is the gate the refresh loop
        // is waiting on. The refresh loop should now activate.
        dataManager.setData(ConfigResponseData())
        advanceUntilIdle()
        advanceTimeBy(500L)
        advanceUntilIdle()
        assertEquals("refresh starts after READY", 1, http.calls.get())

        sdk.stopRefreshLoopForTest()
    }

    @Test
    fun `CONFIG_UPDATED event fires on successful refresh`() = runTest {
        val http = RecordingHttpClient(responder = { AlwaysSucceed.response() })
        val config = ConvertConfig(sdkKey = "sk-1", dataRefreshInterval = 500L)
        val eventManager = EventManager(logger = Logger.NoOp)
        val dataManager = DataManager(eventManager, config.environment)
            .also { it.setData(ConfigResponseData()) }
        val apiManager = ApiManager(http, Logger.NoOp, config, json)
        val cache = FileConfigCache(appContext, Logger.NoOp)
        val testScope = TestScope(StandardTestDispatcher(testScheduler))

        val configUpdatedCount = AtomicInteger(0)
        eventManager.on(SystemEvents.CONFIG_UPDATED) { _ ->
            configUpdatedCount.incrementAndGet()
        }

        val sdk = ConvertSDK(
            config = config,
            appContext = appContext,
            logger = Logger.NoOp,
            eventManager = eventManager,
            initialDataManager = dataManager,
            apiManager = apiManager,
            fileConfigCache = cache,
            scope = testScope,
        )
        sdk.startRefreshLoopForTest()

        advanceTimeBy(500L)
        advanceUntilIdle()
        assertEquals(1, configUpdatedCount.get())

        advanceTimeBy(500L)
        advanceUntilIdle()
        assertEquals(2, configUpdatedCount.get())

        sdk.stopRefreshLoopForTest()
    }

    @Test
    fun `refresh failure does NOT exit the loop (AC-6)`() = runTest {
        // Alternate success / failure on each fetch call so we can see the
        // loop continue through a failure.
        val counter = AtomicInteger(0)
        val http = RecordingHttpClient(responder = {
            if (counter.getAndIncrement() % 2 == 1) AlwaysSucceed.response()
            else HttpClient.HttpResponse(statusCode = 500, body = "boom", headers = emptyMap())
        })
        val config = ConvertConfig(sdkKey = "sk-1", dataRefreshInterval = 500L)
        val eventManager = EventManager(logger = Logger.NoOp)
        val dataManager = DataManager(eventManager, config.environment)
            .also { it.setData(ConfigResponseData()) }
        val apiManager = ApiManager(http, Logger.NoOp, config, json)
        val cache = FileConfigCache(appContext, Logger.NoOp)
        val testScope = TestScope(StandardTestDispatcher(testScheduler))

        val configUpdatedCount = AtomicInteger(0)
        eventManager.on(SystemEvents.CONFIG_UPDATED) { _ ->
            configUpdatedCount.incrementAndGet()
        }

        val sdk = ConvertSDK(
            config = config,
            appContext = appContext,
            logger = Logger.NoOp,
            eventManager = eventManager,
            initialDataManager = dataManager,
            apiManager = apiManager,
            fileConfigCache = cache,
            scope = testScope,
        )
        sdk.startRefreshLoopForTest()

        // Four intervals: fetch calls 0..3 → 500 / 200 / 500 / 200 → two successes.
        advanceTimeBy(2_000L)
        advanceUntilIdle()

        assertEquals("loop should have attempted 4 fetches", 4, http.calls.get())
        assertEquals(
            "CONFIG_UPDATED should fire only on the two successes",
            2,
            configUpdatedCount.get(),
        )

        sdk.stopRefreshLoopForTest()
    }

    @Test
    fun `startRefreshLoop is idempotent — no double-start (AC-9)`() = runTest {
        val http = RecordingHttpClient(responder = { AlwaysSucceed.response() })
        val config = ConvertConfig(sdkKey = "sk-1", dataRefreshInterval = 1_000L)
        val eventManager = EventManager(logger = Logger.NoOp)
        val dataManager = DataManager(eventManager, config.environment)
            .also { it.setData(ConfigResponseData()) }
        val apiManager = ApiManager(http, Logger.NoOp, config, json)
        val cache = FileConfigCache(appContext, Logger.NoOp)
        val testScope = TestScope(StandardTestDispatcher(testScheduler))

        val sdk = ConvertSDK(
            config = config,
            appContext = appContext,
            logger = Logger.NoOp,
            eventManager = eventManager,
            initialDataManager = dataManager,
            apiManager = apiManager,
            fileConfigCache = cache,
            scope = testScope,
        )

        // Call startRefreshLoop three times in a row — only one loop should run.
        sdk.startRefreshLoopForTest()
        sdk.startRefreshLoopForTest()
        sdk.startRefreshLoopForTest()

        advanceTimeBy(1_000L)
        advanceUntilIdle()

        // If we'd double-started, we'd see > 1 fetch per interval.
        assertEquals(1, http.calls.get())

        sdk.stopRefreshLoopForTest()
    }

    // --- Test helpers -------------------------------------------------------

    /**
     * Canned 200 response producing a valid empty ConfigResponseData JSON.
     */
    private object AlwaysSucceed {
        fun response(): HttpClient.HttpResponse =
            HttpClient.HttpResponse(statusCode = 200, body = "{}", headers = emptyMap())
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
