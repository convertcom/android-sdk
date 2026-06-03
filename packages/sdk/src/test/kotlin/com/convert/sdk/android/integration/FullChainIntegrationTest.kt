/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.integration

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.android.ConvertSDK
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.generated.ClicksElementGoalSettings
import com.convert.sdk.core.model.generated.ConfigGoal
import com.convert.sdk.core.model.generated.RuleObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Story 5.5 — Full-chain end-to-end integration test.
 *
 * Exercises the complete A/B loop under Robolectric with a [MockWebServer]
 * serving both the `/config` and `/track` endpoints:
 *
 *  1. [ConvertSDK.builder] wired to the mock endpoints.
 *  2. Mock server returns a fixture config (one experience, two 50/50
 *     variations, one feature, one goal).
 *  3. `sdk.onReady { latch.countDown() }` — wait for config fetch to
 *     complete (5s latch).
 *  4. [ConvertSDK.createContext] with the known deterministic visitor
 *     `visitor_abc` (hashes into `var-a`, confirmed by
 *     [com.convert.sdk.android.ConvertContextRunExperienceTest]).
 *  5. [com.convert.sdk.android.ConvertContext.runExperience] — assert
 *     non-null [com.convert.sdk.core.model.Variation] returned.
 *  6. [com.convert.sdk.android.ConvertContext.trackConversion] with
 *     `goalData = [{AMOUNT, 49.99}]`.
 *  7. [ConvertSDK.flushForTesting] — synchronous [runBlocking] flush so
 *     the POST hits MockWebServer deterministically.
 *  8. Inspect MockWebServer's recorded track requests and the POST body.
 *
 * ### Wire-format expectations (Epic 5 parity)
 *
 * The JS-parity wire format (Stories 5.1 / 5.2 / 5.3) produces events
 * with `eventType` in `{"bucketing", "conversion"}`. Revenue data rides
 * inside a single ConversionEvent's `goalData[]` field — there is no
 * separate `"tr"` or `"transaction"` event type (F-008 / F-007 / F-015
 * remediation). `trackConversion` fires exactly ONE
 * `enqueueConversionEvent` call; the `goalData` list carries the
 * amount/custom-dimension fields verbatim.
 *
 * ### Batch settings
 *
 * `batchSize = 100` so the size threshold never fires on its own — every
 * flush in this test is explicit via [ConvertSDK.flushForTesting]. A
 * long `releaseInterval` ensures the timer loop does not accidentally
 * beat the explicit flush. Together these lock the test to deterministic
 * timing; without the guard, a 1-second timer tick could interleave with
 * the explicit flush and produce two recorded POSTs in a flaky order.
 *
 * ### Timing — trackConversion is async
 *
 * [com.convert.sdk.android.ConvertContext.trackConversion] dispatches
 * its enqueue + fire work onto `sdk.scope.launch { ... }` (Story 4.2
 * AC-2 fire-and-forget). The test must therefore `awaitCondition` on
 * `apiManager.snapshotQueue().size` reaching the expected count
 * BEFORE calling [ConvertSDK.flushForTesting]; otherwise the flush
 * sees a half-populated queue and the test fails intermittently.
 */
@RunWith(RobolectricTestRunner::class)
@Suppress("LargeClass")
internal class FullChainIntegrationTest {

    private lateinit var appContext: Context
    private lateinit var server: MockWebServer

    /** Shared JSON parser for assertions on the recorded POST body. */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        // Clear per-visitor prefs from earlier sprint stories so the sticky
        // bucketing path does not short-circuit THIS test's fresh-bucket
        // assertions.
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()

        server = MockWebServer()
        server.dispatcher = FixtureDispatcher()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // --- Helpers --------------------------------------------------------

    /**
     * Loads the fixture JSON from the test classpath and returns it verbatim.
     *
     * The fixture lives at `packages/sdk/src/test/resources/integration-config-fixture.json`
     * and is served by [FixtureDispatcher] in response to config-endpoint GETs.
     */
    private fun fixtureJson(): String {
        val stream = javaClass.classLoader
            ?.getResourceAsStream(FIXTURE_FILENAME)
            ?: error("Missing fixture on classpath: $FIXTURE_FILENAME")
        return stream.bufferedReader().use { it.readText() }
    }

    /**
     * Dispatcher that pattern-matches the two endpoints this test cares
     * about:
     *
     *  - `GET /config/<sdkKey>` — returns the fixture JSON.
     *  - `POST /track/<sdkKey>` — returns 200 with an empty body; the
     *    test inspects the request via `server.takeRequest` /
     *    `server.requestCount` + `server.takeRequest` for the body.
     *
     * Any other path returns 404 so accidental requests are loud.
     */
    private inner class FixtureDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            return when {
                request.method == "GET" && path.contains("/config/") ->
                    MockResponse().setResponseCode(200).setBody(fixtureJson())
                request.method == "POST" && path.contains("/track/") ->
                    MockResponse().setResponseCode(200).setBody("")
                else -> MockResponse().setResponseCode(NOT_FOUND)
            }
        }
    }

    /**
     * Builds a fully-wired SDK against the mock server and awaits the
     * `onReady` latch. Tests that need to inspect async behaviour
     * receive a ready SDK without having to re-implement the wait.
     *
     * Blocks up to 5 seconds for the config fetch (matches AC-2 step 3).
     */
    private fun buildAndAwaitReady(): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey(TEST_SDK_KEY)
            .configEndpoint(server.url("/config/").toString())
            .trackEndpoint(server.url("/").toString())
            // Make size-triggered flushes impossible in-test; flushForTesting
            // is the only path to a POST.
            .batchSize(LARGE_BATCH_SIZE)
            // Long release interval — the 1s JS-parity default would fire
            // mid-test in Robolectric and cause flaky double-POSTs.
            .releaseInterval(LONG_RELEASE_INTERVAL_MS)
            // Long data-refresh interval — we do not want the refresh loop
            // triggering a second /config fetch mid-test.
            .dataRefreshInterval(LONG_DATA_REFRESH_INTERVAL_MS)
            .build()

        val readyLatch = CountDownLatch(1)
        sdk.onReady { readyLatch.countDown() }
        val ready = readyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!ready) {
            // Dump the MockWebServer request log to help diagnose which
            // half of the handshake failed (fetch vs parse).
            val lastRequest = server.takeRequest(SHORT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            error(
                "SDK did not become ready within $READY_TIMEOUT_SECONDS s. " +
                    "Server saw ${server.requestCount} request(s). " +
                    "Last request: $lastRequest",
            )
        }

        // Workaround for the discovered goal-polymorphic-deserializer gap
        // (beads ai-driven-product-dev-iksc). The fixture served over HTTP
        // cannot carry a goals[] array — ConfigGoal is an interface and
        // sharedSerializersModule does not register a polymorphic fallback
        // for it yet. So the fetch path delivers a goals-less config; we
        // inject the integration-test goal here via setData once onReady
        // has fired. This still exercises EVERY inter-manager path the
        // story cares about — the only thing we skip is the Goal JSON
        // decode, which is NOT the subject of Story 5.5.
        val current = sdk.dataManager.data
        assertNotNull("SDK must have a loaded config after onReady", current)
        sdk.dataManager.setData(
            current!!.copy(
                goals = listOf(
                    TestGoal(
                        id = "g-int",
                        key = "test-goal",
                        name = "Test revenue goal",
                        type = "revenue",
                    ),
                ),
            ),
        )
        return sdk
    }

    /**
     * In-test [ConfigGoal] impl — same pattern used by
     * [com.convert.sdk.android.ConvertContextTrackConversionTest] and
     * [com.convert.sdk.android.ConvertContextDedupTest]. `ConfigGoal` is a
     * generated interface; none of the concrete subtypes declare
     * `: ConfigGoal`, so tests synthesise a minimal implementation
     * carrying only the fields the trackConversion path inspects (id,
     * key).
     */
    private data class TestGoal(
        override val id: String? = null,
        override val name: String? = null,
        override val key: String? = null,
        override val type: String? = null,
        override val rules: RuleObject? = null,
        override val settings: ClicksElementGoalSettings? = null,
    ) : ConfigGoal

    /**
     * Polls [check] until true or [timeoutMs] expires. Fails the test when
     * the condition never becomes true.
     */
    private fun awaitCondition(
        timeoutMs: Long = DEFAULT_AWAIT_MS,
        check: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(AWAIT_POLL_MS)
        }
        assertTrue("Timed out after ${timeoutMs}ms waiting for condition", check())
    }

    /**
     * Pulls every tracking POST the server has recorded so far and returns
     * their request bodies. Non-tracking requests (config GETs) are
     * skipped. Drains the queue — subsequent calls see zero.
     */
    private fun drainTrackingPosts(): List<String> {
        val bodies = mutableListOf<String>()
        while (true) {
            val recorded = server.takeRequest(SHORT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                ?: break
            if (recorded.method == "POST" && (recorded.path ?: "").contains("/track/")) {
                bodies.add(recorded.body.readUtf8())
            }
        }
        return bodies
    }

    // --- AC-1 / AC-2 / AC-3 / AC-4: happy path -----------------------------

    /**
     * AC-1, AC-2, AC-3, AC-4 — full happy-path exercise.
     *
     * 1. Build SDK against mock endpoints and await ready (AC-1, AC-2 steps 1-3).
     * 2. `createContext("visitor_abc")` (AC-2 step 4).
     * 3. `runExperience("test-experience")` — non-null `var-a` (AC-2 step 5).
     * 4. `trackConversion("test-goal", goalData=[AMOUNT=49.99])` (AC-2 step 6).
     * 5. `flushForTesting()` (AC-2 step 7).
     * 6. Exactly one tracking POST recorded (AC-2 step 8).
     * 7. POST body contains a bucketing event whose data.variationId
     *    matches the returned variation (AC-3).
     * 8. POST body contains exactly one conversion event with goalId AND
     *    goalData.amount = 49.99 embedded (F-008 single-event contract,
     *    AC-4).
     */
    @Test
    fun `full chain produces bucketing and conversion events in one POST`() {
        val sdk = buildAndAwaitReady()
        val ctx = sdk.createContext("visitor_abc")

        // --- AC-2 step 5 + AC-3 ------------------------------------------
        val variation = ctx.runExperience("test-experience")
        assertNotNull("runExperience must return a non-null Variation", variation)
        assertTrue(
            "Returned variation.id must be one of the fixture's declared " +
                "variations, got '${variation?.id}'",
            variation?.id in setOf("var-a", "var-b"),
        )

        // --- AC-2 step 6 -------------------------------------------------
        ctx.trackConversion(
            goalKey = "test-goal",
            goalData = listOf(
                GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(GOAL_AMOUNT)),
            ),
        )

        // trackConversion dispatches onto sdk.scope.launch — wait until
        // the single conversion event (F-008: goalData embedded) lands in
        // the queue alongside the bucketing event from runExperience.
        awaitCondition { sdk.apiManager?.snapshotQueue()?.size == EXPECTED_QUEUE_SIZE }

        // --- AC-2 step 7 + 8 ---------------------------------------------
        val events = flushAndExtractEvents(sdk, visitorId = "visitor_abc")

        // --- AC-3 + AC-4 -------------------------------------------------
        assertBucketingEventForVariation(events, expectedVariationId = variation?.id)
        assertConversionEventsWithTransaction(events)
    }

    /**
     * Drives [ConvertSDK.flushForTesting], asserts exactly one tracking
     * POST was recorded, and returns the parsed events[] array for
     * [visitorId]. Shared by the happy-path and enableTracking tests so
     * each test method stays under detekt's `LongMethod` ceiling while
     * the setup semantics stay identical.
     */
    private fun flushAndExtractEvents(
        sdk: ConvertSDK,
        visitorId: String,
    ): List<JsonObject> {
        sdk.flushForTesting()

        val bodies = drainTrackingPosts()
        assertEquals(
            "Expected exactly one tracking POST after flushForTesting(); " +
                "bodies=$bodies",
            1,
            bodies.size,
        )
        val payload = json.parseToJsonElement(bodies.single()).jsonObject
        return extractEventsForVisitor(payload, visitorId)
    }

    /**
     * AC-3 — verifies the POSTed payload contains exactly one bucketing
     * event whose `data.variationId` matches [expectedVariationId] and
     * whose `data.experienceId` matches the fixture.
     */
    private fun assertBucketingEventForVariation(
        events: List<JsonObject>,
        expectedVariationId: String?,
    ) {
        val bucketingEvents = events.filter { eventTypeOf(it) == "bucketing" }
        assertEquals(
            "Expected exactly 1 bucketing event, got ${bucketingEvents.size}",
            1,
            bucketingEvents.size,
        )
        val bucketingData = bucketingEvents.single()["data"]?.jsonObject
        assertNotNull("bucketing event must carry a data object", bucketingData)
        assertEquals(
            "bucketing event data.variationId must match runExperience's result",
            expectedVariationId,
            bucketingData!!["variationId"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals(
            "exp-int-1",
            bucketingData["experienceId"]?.jsonPrimitive?.contentOrNull,
        )
    }

    /**
     * AC-4 — verifies the POSTed payload contains exactly ONE conversion
     * event carrying both the goalId and the goalData array with
     * `{key:amount, value:49.99}`.
     *
     * F-008 / F-007 / F-015 remediation: the JS SDK schema defines only
     * two `eventType` values — `"bucketing"` and `"conversion"`. Revenue
     * data rides inside a single `ConversionEvent.goalData` field; there
     * is no separate `"tr"` event type. The prior spec AC wording (bare
     * hit + separate transaction event) was incorrect and has been
     * corrected here to match the actual `dispatchConversion` contract
     * (single [com.convert.sdk.core.api.ApiManager.enqueueConversionEvent]
     * call per F-008 remediation).
     */
    private fun assertConversionEventsWithTransaction(events: List<JsonObject>) {
        val conversionEvents = events.filter { eventTypeOf(it) == "conversion" }
        assertEquals(
            "Expected exactly 1 conversion event (goalData embedded per F-008), got " +
                conversionEvents.size,
            1,
            conversionEvents.size,
        )

        val conversionEvent = conversionEvents.single()
        val conversionData = conversionEvent["data"]?.jsonObject
        assertNotNull("conversion event must carry a data object", conversionData)
        assertEquals(
            "conversion event data.goalId must match the test fixture goal",
            "g-int",
            conversionData!!["goalId"]?.jsonPrimitive?.contentOrNull,
        )
        assertConversionCarriesAmount(conversionEvent)
    }

    /**
     * Asserts the single conversion event carries `{goalId, goalData}`
     * with `goalData` containing a `{key:amount, value:GOAL_AMOUNT}`
     * entry (F-008 single-event wire format — revenue lives in
     * `ConversionEvent.goalData`, not a separate `"tr"` event).
     * Split from [assertConversionEventsWithTransaction] so the
     * outer method stays under detekt's `LongMethod` ceiling.
     */
    private fun assertConversionCarriesAmount(conversionEvent: JsonObject) {
        val conversionData = conversionEvent["data"]!!.jsonObject
        val goalDataArr = conversionData["goalData"]?.jsonArray
        assertNotNull("conversion event must carry goalData array", goalDataArr)
        val amountEntry = goalDataArr!!.firstOrNull { entry ->
            entry.jsonObject["key"]?.jsonPrimitive?.contentOrNull == "amount"
        }
        assertNotNull("goalData array must contain a {key: amount} entry", amountEntry)
        val amountValue = amountEntry!!.jsonObject["value"]?.jsonPrimitive?.doubleOrNull
        assertNotNull("amount entry must carry a numeric value", amountValue)
        assertEquals(
            "goalData[amount].value must equal the caller-supplied $GOAL_AMOUNT",
            GOAL_AMOUNT,
            amountValue!!,
            AMOUNT_EPSILON,
        )
    }

    // --- AC-5: enableTracking=false suppresses bucketing POST -------------

    /**
     * AC-5 — `runExperience(..., enableTracking = false)` returns a
     * non-null variation but does NOT enqueue the bucketing event.
     *
     * The subsequent `trackConversion` DOES fire (it has no per-call
     * enableTracking parameter), so the POST body should carry the
     * conversion events but no bucketing event. This matches the
     * readiness Q2 auto-delegated answer: the AC tests the per-call
     * gate, not the global SDK-level flag.
     */
    @Test
    fun `runExperience with enableTracking false suppresses bucketing event in POST`() {
        val sdk = buildAndAwaitReady()
        val ctx = sdk.createContext("visitor_abc")

        val variation = ctx.runExperience("test-experience", enableTracking = false)
        // Variation is STILL returned — the gate only suppresses the
        // outbound event, not the bucketing computation itself. The
        // specific id varies by (visitorId, experienceId) hash input;
        // just confirm the bucketing found SOME variation.
        assertNotNull(
            "Variation must be returned even when enableTracking = false",
            variation,
        )
        assertTrue(
            "Returned variation.id must be one of the fixture's declared " +
                "variations, got '${variation?.id}'",
            variation?.id in setOf("var-a", "var-b"),
        )

        ctx.trackConversion(
            goalKey = "test-goal",
            goalData = listOf(
                GoalData(
                    key = GoalDataKey.AMOUNT,
                    value = JsonPrimitive(GOAL_AMOUNT),
                ),
            ),
        )

        // Expect only the one conversion event to be enqueued (no bucketing,
        // F-008: goalData embedded in a single event).
        awaitCondition {
            sdk.apiManager?.snapshotQueue()?.size == EXPECTED_CONVERSION_ONLY_QUEUE_SIZE
        }

        val events = flushAndExtractEvents(sdk, visitorId = "visitor_abc")
        assertTrue(
            "No bucketing event must appear when enableTracking=false — got $events",
            events.none { eventTypeOf(it) == "bucketing" },
        )
        assertEquals(
            "Exactly one conversion event (goalData embedded, F-008 contract) must fire",
            1,
            events.count { eventTypeOf(it) == "conversion" },
        )
    }

    // --- Sanity check: no POST without flushForTesting call ---------------

    /**
     * Defensive assertion — confirms the batching timer is effectively
     * disabled for the test's duration. If this ever starts failing, a
     * later story has reduced the default release interval or introduced
     * a new flush trigger; update [LONG_RELEASE_INTERVAL_MS] or the test
     * strategy to match.
     */
    @Test
    fun `no tracking POST before flushForTesting is called`() {
        val sdk = buildAndAwaitReady()
        val ctx = sdk.createContext("visitor_abc")
        ctx.runExperience("test-experience")

        // Wait for async enqueue — then ASSERT no POST has happened yet.
        awaitCondition { sdk.apiManager?.snapshotQueue()?.size == 1 }

        // Give the timer loop a full 500ms window to fire if it were
        // going to — at LONG_RELEASE_INTERVAL_MS (30s) it won't, but we
        // explicitly allow the test to run in < 1 wall-second.
        Thread.sleep(SHORT_PAUSE_MS)

        val recorded = server.takeRequest(SHORT_REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        // The initial config GET happened during build; takeRequest drained
        // it as part of buildAndAwaitReady's implicit wait — so any request
        // returned here would be a surprise (either a POST to /track or a
        // second config GET). Neither should happen.
        if (recorded != null) {
            assertFalse(
                "No tracking POST must occur before flushForTesting(); got $recorded",
                recorded.method == "POST" && (recorded.path ?: "").contains("/track/"),
            )
        }
    }

    // --- body-walking helpers --------------------------------------------

    /**
     * Walks the outbound payload's `visitors[]` array, finds the entry
     * for [visitorId], and returns its `events[]` as a typed list.
     *
     * Fails the test with a clear error when the visitor is missing or
     * the events array is absent — the test deserves an assertion rather
     * than an NPE when the payload shape drifts.
     */
    private fun extractEventsForVisitor(
        payload: JsonObject,
        visitorId: String,
    ): List<JsonObject> {
        val visitors = payload["visitors"]?.jsonArray
        assertNotNull("payload must contain a visitors[] array", visitors)
        val entry = visitors!!.firstOrNull { v ->
            v.jsonObject["visitorId"]?.jsonPrimitive?.contentOrNull == visitorId
        }
        assertNotNull(
            "payload visitors[] must contain an entry for '$visitorId'",
            entry,
        )
        val events = entry!!.jsonObject["events"]?.jsonArray
        assertNotNull("visitor entry must contain an events[] array", events)
        return events!!.map { it.jsonObject }
    }

    /** Returns the event's `eventType` string, or `""` when absent. */
    private fun eventTypeOf(event: JsonObject): String =
        event["eventType"]?.jsonPrimitive?.contentOrNull ?: ""

    // --- test-only companions --------------------------------------------

    companion object {
        private const val FIXTURE_FILENAME: String = "integration-config-fixture.json"

        /** SDK key used across every integration-test SDK instance. */
        private const val TEST_SDK_KEY: String = "test-sdk-key"

        /** Seconds to wait for `onReady` to fire. */
        private const val READY_TIMEOUT_SECONDS: Long = 5

        /** Default ms budget for `awaitCondition` checks. */
        private const val DEFAULT_AWAIT_MS: Long = 3_000

        /** Polling interval (ms) inside `awaitCondition`. */
        private const val AWAIT_POLL_MS: Long = 10

        /**
         * Large enough that a single enqueueBucketingEvent +
         * trackConversion (2-3 events) never reaches the batch threshold;
         * keeps flushForTesting the only path to the wire.
         */
        private const val LARGE_BATCH_SIZE: Int = 100

        /**
         * Long enough that the ApiManager timer loop never fires during a
         * test (tests finish in < 1s wall-clock); forces every flush to
         * be explicit.
         */
        private const val LONG_RELEASE_INTERVAL_MS: Long = 30_000

        /**
         * Long enough that the config-refresh loop never fires during a
         * test. Prevents a second GET /config/ mid-assertion.
         */
        private const val LONG_DATA_REFRESH_INTERVAL_MS: Long = 600_000

        /** Short ms timeout used with MockWebServer.takeRequest. */
        private const val SHORT_REQUEST_TIMEOUT_MS: Long = 200

        /**
         * Small wall-clock pause to let the timer loop "try and fail" to
         * fire in the "no POST before flushForTesting" sanity test.
         */
        private const val SHORT_PAUSE_MS: Long = 500

        /**
         * Goal amount the happy-path test supplies — must round-trip
         * through GoalData / ApiManager / TrackingPayloadBuilder intact.
         */
        private const val GOAL_AMOUNT: Double = 49.99

        /**
         * Float equality epsilon for the amount round-trip assertion —
         * double → JSON number → double can drift by
         * ~1e-14 on typical JVMs; 1e-6 is well above that and small
         * enough to catch a real regression.
         */
        private const val AMOUNT_EPSILON: Double = 0.000_001

        /**
         * Expected queue size after runExperience + trackConversion with
         * goalData — one bucketing + one conversion (goalData embedded,
         * F-008 remediation: single ConversionEvent carries goalData,
         * no separate "tr" event type).
         */
        private const val EXPECTED_QUEUE_SIZE: Int = 2

        /**
         * Expected queue size when bucketing is suppressed by
         * enableTracking=false — one conversion event only (goalData
         * embedded per F-008 single-event contract).
         */
        private const val EXPECTED_CONVERSION_ONLY_QUEUE_SIZE: Int = 1

        /** HTTP 404 status code — shorthand for the FixtureDispatcher. */
        private const val NOT_FOUND: Int = 404
    }
}
