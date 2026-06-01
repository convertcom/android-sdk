/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.model.generated.ClicksElementGoalSettings
import com.convert.sdk.core.model.generated.ConfigGoal
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.RuleObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CountDownLatch

/**
 * Robolectric-backed tests for Story 4.3:
 * Goal Deduplication & Multiple Transactions.
 *
 * Builds on Story 4.2's `ConvertContext.trackConversion` by adding an
 * atomic check-and-set guard via Story 4.3 SDK-1's
 * [com.convert.sdk.core.data.DataManager.markGoalTracked] and a per-call
 * `conversionSetting: Map<String, Any?>? = null` parameter carrying the
 * `forceMultipleTransactions` flag.
 *
 * ### Corrected single-call shape (F-007 / F-015 / F-008 remediation)
 *
 * The dedup semantics use a SINGLE `enqueueConversionEvent` call per tracked
 * conversion, carrying `goalData` verbatim. There is no separate `"tr"` event
 * type (`types.gen.ts:2742-2757` defines only `"bucketing"` and
 * `"conversion"`); revenue lives inside `goalData` on the same ConversionEvent.
 *
 *  - `goalTriggered && !forceMultipleTransactions` → DEBUG log, early return —
 *    no enqueue, no CONVERSION fire.
 *  - `!goalTriggered || forceMultipleTransactions` → single
 *    `enqueueConversionEvent` call with goalData (null when omitted), FIRE
 *    CONVERSION.
 *
 * ### Test structure
 *
 * Shares the fixtures and helpers of [ConvertContextTrackConversionTest]
 * (Story 4.2) — goal id `g-42`, key `purchase`, RecordingConversionApiManager,
 * `awaitCondition` polling. Kept in a separate file so the dedup-specific
 * assertions stay organised and the 4.2 regressions remain untouched.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextDedupTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        ShadowLog.clear()
    }

    // --- fixtures ---------------------------------------------------------

    private fun testConfig(
        goalId: String = "g-42",
        goalKey: String = "purchase",
    ): ConfigResponseData = ConfigResponseData(
        goals = listOf(TestGoal(id = goalId, key = goalKey, name = "Purchase goal")),
    )

    private data class TestGoal(
        override val id: String? = null,
        override val name: String? = null,
        override val key: String? = null,
        override val type: String? = null,
        override val rules: RuleObject? = null,
        override val settings: ClicksElementGoalSettings? = null,
    ) : ConfigGoal

    private fun buildSdk(
        config: ConfigResponseData,
        logLevel: LogLevel = LogLevel.DEBUG,
    ): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext)
            .data(config)
            .logLevel(logLevel)
            .build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-1: second trackConversion for same goal does not enqueue ------

    @Test
    fun `second trackConversion for same goal does not enqueue`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val received = mutableListOf<Map<String, Any?>>()
        sdk.on(SystemEvents.CONVERSION, RecordingEventCallback(received))
        val ctx = sdk.createContext("visitor_abc")

        // First call — enqueue once + fire CONVERSION once.
        ctx.trackConversion("purchase")
        awaitCondition { recordingApi.enqueueConversionCalls.size == 1 }
        awaitCondition { received.size == 1 }

        // Second call — dedup skips. No new enqueue, no new CONVERSION fire.
        ctx.trackConversion("purchase")
        Thread.sleep(SETTLE_MS)

        assertEquals(
            "second call must not add an enqueue; got ${recordingApi.enqueueConversionCalls}",
            1,
            recordingApi.enqueueConversionCalls.size,
        )
        // JS parity: context.ts line 417 `if (triggred)` is false when
        // convert() early-returns, so CONVERSION does NOT fire on the
        // skipped path.
        assertEquals(
            "CONVERSION fires once (first call) — dedup-skipped call does not fire",
            1,
            received.size,
        )
    }

    // --- AC-2: dedup survives a cold restart (SharedPreferences persistence)

    @Test
    fun `trackConversion after restart still dedups`() {
        // First SDK instance — track once.
        val sdk1 = buildSdk(testConfig())
        val api1 = RecordingConversionApiManager()
        sdk1.attachTestApiManager(api1)
        val ctx1 = sdk1.createContext("visitor_abc")
        ctx1.trackConversion("purchase")
        awaitCondition { api1.enqueueConversionCalls.size == 1 }

        // Simulate app restart — build a fresh SDK against the SAME
        // SharedPreferences (Robolectric's shared prefs are process-wide
        // within a single JVM test). Story 3.1's SharedPrefsDataStore
        // should reload the persisted goals map for visitor_abc.
        val sdk2 = buildSdk(testConfig())
        val api2 = RecordingConversionApiManager()
        sdk2.attachTestApiManager(api2)
        val ctx2 = sdk2.createContext("visitor_abc")

        ctx2.trackConversion("purchase")
        Thread.sleep(SETTLE_MS)

        assertTrue(
            "restart must reload dedup state — no new enqueue; got ${api2.enqueueConversionCalls}",
            api2.enqueueConversionCalls.isEmpty(),
        )
    }

    // --- AC-3: forceMultipleTransactions bypasses dedup for conversion ------

    @Test
    fun `forceMultipleTransactions goal allows repeat tracking`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        val goalData = listOf(
            GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(19.99)),
            GoalData(key = GoalDataKey.TRANSACTION_ID, value = JsonPrimitive("TX-1")),
        )
        val forceSetting = mapOf("forceMultipleTransactions" to true)

        // First call with force+goalData — single ConversionEvent with goalData.
        // F-007/F-015 remediation: no separate "bare" + "transaction" enqueues;
        // revenue lives in goalData on the single ConversionEvent.
        ctx.trackConversion("purchase", goalData = goalData, conversionSetting = forceSetting)
        awaitCondition { recordingApi.enqueueConversionCalls.size == 1 }

        // Second call with force+goalData — forceMultipleTransactions=true
        // bypasses dedup entirely; another single ConversionEvent with goalData2.
        val goalData2 = listOf(
            GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(29.99)),
            GoalData(key = GoalDataKey.TRANSACTION_ID, value = JsonPrimitive("TX-2")),
        )
        ctx.trackConversion("purchase", goalData = goalData2, conversionSetting = forceSetting)
        awaitCondition { recordingApi.enqueueConversionCalls.size == 2 }

        assertEquals(2, recordingApi.enqueueConversionCalls.size)
        val firstCall = recordingApi.enqueueConversionCalls[0]
        val secondCall = recordingApi.enqueueConversionCalls[1]
        assertNotNull("first call carries goalData", firstCall.goalData)
        assertNotNull("second call (force repeat) carries goalData2", secondCall.goalData)
        assertEquals(
            "TX-2",
            secondCall.goalData?.firstOrNull { it.key == GoalDataKey.TRANSACTION_ID }
                ?.value?.toString()?.trim('"'),
        )
    }

    // --- AC-3: force=true FIRST call sends single ConversionEvent with goalData

    @Test
    fun `forceMultipleTransactions first call sends single conversion with goalData`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_fresh")

        ctx.trackConversion(
            "purchase",
            goalData = listOf(GoalData(GoalDataKey.AMOUNT, JsonPrimitive(10.0))),
            conversionSetting = mapOf("forceMultipleTransactions" to true),
        )
        awaitCondition { recordingApi.enqueueConversionCalls.size == 1 }

        // Fresh visitor, force=true → single ConversionEvent carrying goalData
        // (F-007/F-015: no separate bare + transaction calls; revenue in goalData).
        val call = recordingApi.enqueueConversionCalls[0]
        assertNotNull("first call carries goalData", call.goalData)
        assertEquals(1, recordingApi.enqueueConversionCalls.size)
    }

    // --- AC-4: dedup is per-visitor ---------------------------------------

    @Test
    fun `dedup is per-visitor — two contexts track independently`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)

        val ctxA = sdk.createContext("visitor-A")
        val ctxB = sdk.createContext("visitor-B")

        ctxA.trackConversion("purchase")
        ctxB.trackConversion("purchase")
        awaitCondition { recordingApi.enqueueConversionCalls.size == 2 }

        // Both visitors enqueue — dedup lives in each visitor's StoreData.
        assertEquals(2, recordingApi.enqueueConversionCalls.size)
        val visitors = recordingApi.enqueueConversionCalls.map { it.visitorId }.toSet()
        assertEquals(setOf("visitor-A", "visitor-B"), visitors)

        // A second call from either should now dedup.
        ctxA.trackConversion("purchase")
        Thread.sleep(SETTLE_MS)
        assertEquals(
            "visitor-A second call must dedup; got ${recordingApi.enqueueConversionCalls}",
            2,
            recordingApi.enqueueConversionCalls.size,
        )
    }

    // --- AC-6: concurrent trackConversion calls enqueue exactly one event -

    @Test
    fun `concurrent trackConversion calls enqueue exactly one event`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_race")

        val threadCount = 10
        val startGate = CountDownLatch(1)

        runBlocking {
            val jobs = (0 until threadCount).map {
                launch(Dispatchers.Default) {
                    withContext(Dispatchers.Default) {
                        startGate.await()
                        ctx.trackConversion("purchase")
                    }
                }
            }
            startGate.countDown()
            jobs.forEach { it.join() }
        }

        // Wait for all scope.launch dispatches to complete. The 10 racing
        // calls go through markGoalTracked (atomic); exactly one returns
        // true and enqueues. The other nine log DEBUG and return.
        Thread.sleep(LONGER_SETTLE_MS)

        assertEquals(
            "exactly one bare enqueue expected under concurrent calls; got " +
                recordingApi.enqueueConversionCalls,
            1,
            recordingApi.enqueueConversionCalls.size,
        )
    }

    // --- AC-7: DEBUG log emitted when dedup skips -------------------------

    @Test
    fun `DEBUG log emitted when dedup skips`() {
        val sdk = buildSdk(testConfig(), logLevel = LogLevel.DEBUG)
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_debug")

        ctx.trackConversion("purchase")
        awaitCondition { recordingApi.enqueueConversionCalls.size == 1 }

        ShadowLog.clear() // discard first-call logs, focus on the skip path
        ctx.trackConversion("purchase")
        Thread.sleep(SETTLE_MS)

        val debugLogs = ShadowLog.getLogs().filter { it.type == Log.DEBUG }
        // The DEBUG line must mention the goalKey AND "already tracked"
        // so operators can trace which goal was deduplicated for which
        // visitor without parsing internal state.
        assertTrue(
            "DEBUG log missing dedup message; got ${debugLogs.map { it.msg }}",
            debugLogs.any {
                it.msg.contains("already tracked", ignoreCase = true) &&
                    it.msg.contains("purchase") &&
                    it.msg.contains("visitor_debug")
            },
        )
    }

    // --- AC-5 edge case: dedup with goalData suppresses the ConversionEvent --

    @Test
    fun `regular goal second call with goalData dedups the conversion enqueue`() {
        // Without forceMultipleTransactions, AC-5 rationale: "if the
        // conversion is skipped, so is the revenue". The single ConversionEvent
        // (carrying goalData) must be suppressed on the second call.
        // F-007/F-015: no separate bare/transaction; one ConversionEvent total.
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_notx")

        val goalData = listOf(GoalData(GoalDataKey.AMOUNT, JsonPrimitive(50.0)))

        ctx.trackConversion("purchase", goalData = goalData)
        awaitCondition { recordingApi.enqueueConversionCalls.size == 1 }

        ctx.trackConversion("purchase", goalData = goalData)
        Thread.sleep(SETTLE_MS)

        // Second call is suppressed by dedup — still only 1 enqueue total.
        assertEquals(
            "second call must suppress the conversion enqueue; got " +
                recordingApi.enqueueConversionCalls,
            1,
            recordingApi.enqueueConversionCalls.size,
        )
    }

    // --- helpers ---------------------------------------------------------

    private fun awaitCondition(timeoutMs: Long = 1_000L, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(POLL_MS)
        }
        assertTrue("Timed out waiting for condition", check())
    }

    internal class RecordingEventCallback(
        private val sink: MutableList<Map<String, Any?>>,
    ) : EventCallback {
        override fun onEvent(data: Map<String, Any?>) {
            sink.add(data)
        }
    }

    internal data class EnqueueConversionCall(
        val visitorId: String,
        val goalId: String,
        val goalData: List<GoalData>?,
        val segments: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    )

    internal class RecordingConversionApiManager :
        com.convert.sdk.core.api.ApiManager(
            httpClient = object : com.convert.sdk.core.port.HttpClient {
                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                ): com.convert.sdk.core.port.HttpClient.HttpResponse =
                    com.convert.sdk.core.port.HttpClient.HttpResponse(0, "", emptyMap())

                override suspend fun post(
                    url: String,
                    body: String,
                    headers: Map<String, String>,
                ): com.convert.sdk.core.port.HttpClient.HttpResponse =
                    com.convert.sdk.core.port.HttpClient.HttpResponse(0, "", emptyMap())
            },
            logger = com.convert.sdk.core.port.Logger.NoOp,
            config = com.convert.sdk.core.config.ConvertConfig(),
            json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        ) {

        val enqueueConversionCalls: MutableList<EnqueueConversionCall> =
            java.util.Collections.synchronizedList(mutableListOf())

        override fun enqueueConversionEvent(
            visitorId: String,
            goalId: String,
            goalData: List<GoalData>?,
            segments: Map<String, kotlinx.serialization.json.JsonElement>,
        ) {
            enqueueConversionCalls.add(
                EnqueueConversionCall(visitorId, goalId, goalData, segments),
            )
        }
    }

    private companion object {
        /** Polling interval for awaitCondition. */
        private const val POLL_MS: Long = 10L

        /**
         * Short settle window for "nothing should happen" negative
         * assertions — gives any stray coroutine launched by the SDK
         * scope a chance to hit the recording fake so we don't pass the
         * negative assertion prematurely.
         */
        private const val SETTLE_MS: Long = 150L

        /**
         * Longer settle for the concurrent-race test — 10 coroutines each
         * launch their own `sdk.scope.launch` inside trackConversion, so
         * the settle window must cover the tail of those dispatches.
         */
        private const val LONGER_SETTLE_MS: Long = 500L
    }
}
