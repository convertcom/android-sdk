/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.generated.ClicksElementGoalSettings
import com.convert.sdk.core.model.generated.ConfigGoal
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.RuleObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Robolectric-backed tests for Story 4.2 AC-6:
 * `ConvertContext.trackConversion(goalKey, goalData?)` full-body behaviour.
 *
 * ### JS SDK parity — event shape overrides story wording
 *
 * The story text refers to a `hitGoal` tracking event and a separate `tr`
 * event. The JS SDK (`javascript-sdk/packages/data/src/data-manager.ts`
 * `sendConversion` + `sendTransaction`) and the OpenAPI-generated
 * [com.convert.sdk.core.model.generated.ConversionEvent] type both model
 * these as TWO independent conversion events carrying `{goalId}` and
 * `{goalId, goalData}` respectively, **both** with `eventType = "conversion"`.
 * The per-story readiness-assessment auto-delegated Q1 resolved this in
 * favour of parity; these tests encode the parity interpretation via the
 * Story 4.2 SDK-1 [com.convert.sdk.core.api.ApiManager.enqueueConversionEvent]
 * stub's `(visitorId, goalId, goalData?)` signature — two separate calls
 * when `goalData` is non-empty, one call otherwise. Story 5.1 folds the
 * two calls into a single `enqueue(VisitorTrackingEvents)` once the
 * outbound queue lands.
 *
 * ### Why Robolectric
 *
 * `trackConversion` dispatches its enqueue + fire work onto `sdk.scope`
 * (Story 2.1 coroutine scope), which in turn uses `Dispatchers.Default` —
 * Robolectric's `androidx.test.core.app.ApplicationProvider` gives us the
 * matching Android `Context` the SDK builder expects without forcing a
 * MainDispatcher swap. This is the same pattern the sibling
 * [ConvertContextRunExperienceTest] uses.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextTrackConversionTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    // --- test fixtures ----------------------------------------------------

    /**
     * Builds a [ConfigResponseData] with one goal (`purchase` → id `g-42`).
     *
     * The generated [ConfigGoal] is an `interface` and none of its generated
     * concrete subtypes (`NoSettingsGoal`, `ClicksElementGoal`, `DomInteractionGoal`, …)
     * declare `: ConfigGoal`, so we satisfy the `List<ConfigGoal>` field by
     * providing a tiny in-test implementation that captures the handful of
     * fields `trackConversion` inspects (id + key). This mirrors the pattern
     * used for `ExperienceChangeServing` in Story 4.1.
     */
    private fun testConfig(goalId: String = "g-42", goalKey: String = "purchase"): ConfigResponseData =
        ConfigResponseData(
            goals = listOf(TestGoal(id = goalId, key = goalKey, name = "Purchase goal")),
        )

    /**
     * Minimal in-test [ConfigGoal] implementation. Only [id] and [key] are
     * consulted by `trackConversion` — the remaining fields are populated
     * to whatever satisfies the interface.
     */
    private data class TestGoal(
        override val id: String? = null,
        override val name: String? = null,
        override val key: String? = null,
        override val type: String? = null,
        override val rules: RuleObject? = null,
        override val settings: ClicksElementGoalSettings? = null,
    ) : ConfigGoal

    private fun buildSdk(config: ConfigResponseData): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-1: bare trackConversion enqueues one "hitGoal" event ----------

    @Test
    fun `trackConversion enqueues hitGoal event`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        ctx.trackConversion("purchase")

        awaitCondition { recordingApi.enqueueConversionCalls.isNotEmpty() }
        // Bare goal-hit path → exactly one call with goalData == null.
        assertEquals(1, recordingApi.enqueueConversionCalls.size)
        val call = recordingApi.enqueueConversionCalls.single()
        assertEquals("visitor_abc", call.visitorId)
        assertEquals("g-42", call.goalId)
        assertNull("bare call should have null goalData", call.goalData)
    }

    // --- AC-1: with-goalData trackConversion also enqueues transaction event

    @Test
    fun `trackConversion with amount enqueues tr event alongside hitGoal`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        val goalData = listOf(
            GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(29.99)),
            GoalData(
                key = GoalDataKey.PRODUCTS_COUNT,
                value = JsonPrimitive(2),
            ),
            GoalData(
                key = GoalDataKey.TRANSACTION_ID,
                value = JsonPrimitive("TX-101"),
            ),
        )

        ctx.trackConversion("purchase", goalData = goalData)

        awaitCondition { recordingApi.enqueueConversionCalls.size >= 2 }
        // Parity with JS SDK data-manager.ts:1044-1048 —
        // sendConversion() + sendTransaction() both run when goalData is present.
        assertEquals(
            "expected one bare hit + one transaction call, got ${recordingApi.enqueueConversionCalls}",
            2,
            recordingApi.enqueueConversionCalls.size,
        )
        val bare = recordingApi.enqueueConversionCalls[0]
        val transaction = recordingApi.enqueueConversionCalls[1]
        // Call order matters: bare conversion first (sendConversion), then
        // transaction (sendTransaction) — matches JS SDK ordering so downstream
        // enrichment (Story 5.1 bucketingData merge) sees the goal-hit first.
        assertNull("first call is bare goal hit", bare.goalData)
        assertNotNull("second call carries the transaction payload", transaction.goalData)
        assertEquals(3, transaction.goalData?.size)
        assertEquals("g-42", bare.goalId)
        assertEquals("g-42", transaction.goalId)
    }

    // --- AC-1: unknown goal → WARN, no enqueue, no fire ------------------

    @Test
    fun `trackConversion with unknown goal key logs warn and does not enqueue`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val received = mutableListOf<Map<String, Any?>>()
        sdk.on(SystemEvents.CONVERSION, RecordingEventCallback(received))
        val ctx = sdk.createContext("visitor_abc")

        ctx.trackConversion("does-not-exist")

        // Unknown-goal branch returns BEFORE the scope.launch dispatch, so
        // waiting on a never-fired event would time out the assertion loop;
        // a short settle window is enough to rule out any late dispatch.
        Thread.sleep(SETTLE_MS)
        assertTrue(
            "no enqueue expected for unknown goal, got ${recordingApi.enqueueConversionCalls}",
            recordingApi.enqueueConversionCalls.isEmpty(),
        )
        assertTrue(
            "no CONVERSION fire expected for unknown goal, got $received",
            received.isEmpty(),
        )
    }

    // --- AC-4: null sdk reference / null config → returns silently -------

    @Test
    fun `trackConversion with null config does not crash`() {
        // Build with no data() AND no sdkKey → hasData returns false and
        // no config fetch kicks in. trackConversion must no-op silently.
        val sdk = ConvertSDK.builder(appContext).build()
        val recordingApi = RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        // Test passes if no exception is thrown AND no enqueue happened.
        ctx.trackConversion("purchase")
        ctx.trackConversion(
            "purchase",
            goalData = listOf(GoalData(GoalDataKey.AMOUNT, JsonPrimitive(10.0))),
        )

        Thread.sleep(SETTLE_MS)
        assertTrue(
            "no enqueue expected when config is not ready",
            recordingApi.enqueueConversionCalls.isEmpty(),
        )
    }

    // --- AC-3: goalData keys preserve camelCase JSON mapping -------------

    @Test
    fun `goalData serializes keys as camelCase (productsCount not products_count)`() {
        // Story AC-3 locks the payload-parity contract (JS SDK camelCase
        // enum values). Asserted here via a JSON-round-trip rather than
        // enum name comparison — the @SerialName mapping is the piece
        // tracking back-ends and JS SDK parity care about.
        val json = Json { ignoreUnknownKeys = true }
        val gd = listOf(
            GoalData(GoalDataKey.AMOUNT, JsonPrimitive(29.99)),
            GoalData(GoalDataKey.PRODUCTS_COUNT, JsonPrimitive(2)),
            GoalData(GoalDataKey.TRANSACTION_ID, JsonPrimitive("TX-1")),
            GoalData(GoalDataKey.CUSTOM_DIMENSION_1, JsonPrimitive("gold")),
            GoalData(GoalDataKey.CUSTOM_DIMENSION_5, JsonPrimitive(true)),
        )

        val serialized = json.encodeToString(
            kotlinx.serialization.builtins.ListSerializer(GoalData.serializer()),
            gd,
        )

        // Every key must appear in camelCase — NEVER snake_case.
        assertTrue(
            "expected 'amount' key in $serialized",
            serialized.contains("\"amount\""),
        )
        assertTrue(
            "expected 'productsCount' key in $serialized",
            serialized.contains("\"productsCount\""),
        )
        assertTrue(
            "productsCount must not be snake_cased in $serialized",
            !serialized.contains("products_count"),
        )
        assertTrue(
            "expected 'transactionId' key in $serialized",
            serialized.contains("\"transactionId\""),
        )
        assertTrue(
            "transactionId must not be snake_cased in $serialized",
            !serialized.contains("transaction_id"),
        )
        assertTrue(
            "expected 'customDimension1' key in $serialized",
            serialized.contains("\"customDimension1\""),
        )
        assertTrue(
            "expected 'customDimension5' key in $serialized",
            serialized.contains("\"customDimension5\""),
        )
        // Round-trip — the values survive unchanged.
        val decoded = json.decodeFromString(
            kotlinx.serialization.builtins.ListSerializer(GoalData.serializer()),
            serialized,
        )
        assertEquals(5, decoded.size)
        assertEquals(
            29.99,
            decoded[0].value?.jsonPrimitive?.content?.toDouble(),
        )
    }

    // --- AC-1: CONVERSION internal event fires on successful enqueue -----

    @Test
    fun `trackConversion fires SystemEvents CONVERSION with visitorId and goalKey`() {
        val sdk = buildSdk(testConfig())
        val received = mutableListOf<Map<String, Any?>>()
        sdk.on(SystemEvents.CONVERSION, RecordingEventCallback(received))
        val ctx = sdk.createContext("visitor_abc")

        ctx.trackConversion("purchase")

        awaitCondition { received.isNotEmpty() }
        assertEquals(1, received.size)
        val payload = received.single()
        // JS SDK parity: context.ts:418-426 fires {visitorId, goalKey}.
        assertEquals("visitor_abc", payload["visitorId"])
        assertEquals("purchase", payload["goalKey"])
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Polls the supplied condition at 10ms intervals up to [timeoutMs]. Used
     * to wait for the scope-scheduled `sdk.scope.launch` dispatches that
     * trackConversion relies on per AC-2 (fire-and-forget semantics).
     */
    private fun awaitCondition(timeoutMs: Long = 1000L, check: () -> Boolean) {
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

    /** Recorded call to [com.convert.sdk.core.api.ApiManager.enqueueConversionEvent]. */
    internal data class EnqueueConversionCall(
        val visitorId: String,
        val goalId: String,
        val goalData: List<GoalData>?,
    )

    /**
     * Fake ApiManager subclass capturing every `enqueueConversionEvent`
     * call. Constructed with minimal dependencies per the pattern in
     * [ConvertContextRunExperienceTest.RecordingApiManager] — the base
     * class fields are unused by the stub we spy on.
     */
    internal class RecordingConversionApiManager :
        com.convert.sdk.core.api.ApiManager(
            httpClient = object : com.convert.sdk.core.port.HttpClient {
                override suspend fun get(
                    url: String,
                    headers: Map<String, String>,
                ): com.convert.sdk.core.port.HttpClient.HttpResponse =
                    com.convert.sdk.core.port.HttpClient.HttpResponse(
                        statusCode = 0,
                        body = "",
                        headers = emptyMap(),
                    )

                override suspend fun post(
                    url: String,
                    body: String,
                    headers: Map<String, String>,
                ): com.convert.sdk.core.port.HttpClient.HttpResponse =
                    com.convert.sdk.core.port.HttpClient.HttpResponse(
                        statusCode = 0,
                        body = "",
                        headers = emptyMap(),
                    )
            },
            logger = com.convert.sdk.core.port.Logger.NoOp,
            config = com.convert.sdk.core.config.ConvertConfig(),
            json = Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        ) {

        val enqueueConversionCalls: MutableList<EnqueueConversionCall> = mutableListOf()

        override fun enqueueConversionEvent(
            visitorId: String,
            goalId: String,
            goalData: List<GoalData>?,
        ) {
            enqueueConversionCalls.add(
                EnqueueConversionCall(visitorId, goalId, goalData),
            )
        }
    }

    private companion object {
        /** Small settle window for "nothing should happen" negative assertions. */
        private const val SETTLE_MS: Long = 150L

        /** awaitCondition polling interval. */
        private const val POLL_MS: Long = 10L
    }
}
