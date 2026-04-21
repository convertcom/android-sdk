/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import kotlin.system.measureTimeMillis

/**
 * Robolectric-backed tests for Story 3.2 AC-6 / AC-7 / AC-8 / AC-9 / AC-10 /
 * AC-11: `ConvertContext.runExperience` full-body behaviour including
 * sticky bucketing persistence, event fire, tracking gate, and
 * the <10ms performance bound (AC-8).
 *
 * ### Why Robolectric
 *
 * `runExperience` reads sticky state via [com.convert.sdk.core.data.DataManager.getStoreData],
 * which itself reads from SharedPreferences on cache miss. The easiest
 * way to test this without mocking every DataStore call is the same
 * pattern the Story 3.1 Context tests use: a real
 * [ApplicationProvider.getApplicationContext] Robolectric-backed
 * SharedPreferences file.
 *
 * ### Test config shape
 *
 * `testConfig()` builds a [ConfigResponseData] with one experience (`exp-1`
 * keyed `welcome`) carrying two 50/50 variations. Because the JS-SDK-derived
 * hash value for `("exp-1", <visitorId>)` is deterministic, the expected
 * variation for each fixed visitorId is known up front (we use
 * `visitor_abc` which hashes to 833 → below the 5000 boundary → `var-a`).
 *
 * ### Tracking gate
 *
 * `RecordingApiManager` is a hand-rolled spy on
 * [com.convert.sdk.core.api.ApiManager.enqueueBucketingEvent] — we can't
 * use MockK here because the :sdk module's Robolectric+JVM23 combo needs
 * external-process attach that ApiManager tests already avoid. The spy
 * logs every call; assertions check call count / args against the gate
 * (enableTracking flag).
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextRunExperienceTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        // Clear every SharedPreferences file the SDK touches so each test
        // starts from a pristine state (visitor_id auto-gen + per-visitor
        // StoreData).
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    // --- test fixtures ----------------------------------------------------

    /**
     * Builds a two-variation 50/50 test config with a single experience
     * (`exp-1` keyed `welcome`). Override [pctA] / [pctB] per-test for
     * traffic-allocation-miss scenarios. The rest of the defaults
     * ensure the visitor hash vectors used in the assertions remain
     * stable across tests.
     */
    private fun testConfig(
        pctA: Double = 50.0,
        pctB: Double = 50.0,
    ): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-1",
                key = "welcome",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-a",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(pctA),
                    ),
                    ExperienceVariationConfig(
                        id = "var-b",
                        key = "treatment",
                        trafficAllocation = BigDecimal.valueOf(pctB),
                    ),
                ),
            ),
        ),
    )

    /**
     * Builds the SDK in direct-data mode and waits for the data seed
     * coroutine to finish before returning. The Builder's
     * `launchInitialDataSeed` posts `setData` onto the SDK scope
     * (`Dispatchers.Default`) so the returned SDK reports `hasData ==
     * false` for a brief window after `build()` returns; all of the
     * tests below need the config in place before they call into
     * `runExperience`.
     */
    private fun buildSdk(config: ConfigResponseData): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-6 step 1: config-not-ready gate -------------------------------

    @Test
    fun `runExperience returns null before config ready`() {
        // Build with no data() → hasData is false until Story 2.2's fetch
        // completes (which won't, because we didn't set an sdkKey either).
        val sdk = ConvertSDK.builder(appContext).build()
        val ctx = sdk.createContext("v")

        val result = ctx.runExperience("welcome")

        assertNull(result)
    }

    // --- AC-9: non-existent / empty / missing-list keys → null + no crash -

    @Test
    fun `runExperience returns null for unknown key`() {
        val sdk = buildSdk(testConfig())
        val ctx = sdk.createContext("v")

        val result = ctx.runExperience("does-not-exist")

        assertNull(result)
    }

    @Test
    fun `runExperience returns null for empty key`() {
        val sdk = buildSdk(testConfig())
        val ctx = sdk.createContext("v")

        val result = ctx.runExperience("")

        assertNull(result)
    }

    @Test
    fun `runExperience returns null when experiences list is null`() {
        val sdk = buildSdk(ConfigResponseData(experiences = null))
        val ctx = sdk.createContext("v")

        val result = ctx.runExperience("welcome")

        assertNull(result)
    }

    @Test
    fun `runExperience returns null when experiences list is empty`() {
        val sdk = buildSdk(ConfigResponseData(experiences = emptyList()))
        val ctx = sdk.createContext("v")

        val result = ctx.runExperience("welcome")

        assertNull(result)
    }

    // --- AC-6 end-to-end: bucketing produces a variation -------------------

    @Test
    fun `runExperience buckets and returns variation for valid key`() {
        val sdk = buildSdk(testConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runExperience("welcome")

        // visitor_abc + exp-1 hashes to value 833 → <5000 → var-a (control).
        assertNotNull(result)
        assertEquals("var-a", result?.id)
        assertEquals("control", result?.key)
    }

    @Test
    fun `runExperience returns null when traffic allocation excludes visitor`() {
        // value 833 with 4%+4% wheel → boundaries 400 + 800 → 833 > 800 → null.
        val sdk = buildSdk(testConfig(pctA = 4.0, pctB = 4.0))
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runExperience("welcome")

        assertNull(result)
    }

    // --- AC-7 sticky: same variation returned on subsequent calls ---------

    @Test
    fun `runExperience returns same variation on second call (sticky)`() {
        val sdk = buildSdk(testConfig())
        val ctx = sdk.createContext("visitor_abc")

        val first = ctx.runExperience("welcome")
        val second = ctx.runExperience("welcome")

        // Compare on identity-bearing fields; the fresh-bucket path sets
        // `bucketingAllocation` to the hash-pipeline value while the
        // sticky path leaves it null (matches JS SDK BucketedVariation
        // semantics), so whole-object equality intentionally differs.
        assertEquals(first?.id, second?.id)
        assertEquals(first?.key, second?.key)
        assertEquals("var-a", second?.id)
    }

    @Test
    fun `runExperience sticky persists across SDK instances (app restart)`() {
        // First "process" bucket and persist.
        val sdkOne = buildSdk(testConfig())
        val ctxOne = sdkOne.createContext("visitor_restart")
        val firstVariation = ctxOne.runExperience("welcome")
        assertNotNull(firstVariation)

        // Second "process" — same appContext, same SharedPreferences,
        // different ConvertSDK instance. Sticky read should restore the
        // same variation without calling the hash pipeline.
        val sdkTwo = buildSdk(testConfig())
        val ctxTwo = sdkTwo.createContext("visitor_restart")
        val secondVariation = ctxTwo.runExperience("welcome")

        assertEquals(firstVariation?.id, secondVariation?.id)
    }

    // --- AC-10: enableTracking gate ---------------------------------------

    @Test
    fun `runExperience with enableTracking true enqueues bucketing event`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        ctx.runExperience("welcome", enableTracking = true)

        assertEquals(1, recordingApi.enqueueBucketingCalls.size)
        val call = recordingApi.enqueueBucketingCalls.first()
        assertEquals("visitor_abc", call.visitorId)
        assertEquals("exp-1", call.experienceId)
        assertEquals("var-a", call.variationId)
    }

    @Test
    fun `runExperience with enableTracking false does not enqueue bucketing event`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        ctx.runExperience("welcome", enableTracking = false)

        assertTrue(
            "Expected no enqueueBucketingEvent calls, got ${recordingApi.enqueueBucketingCalls}",
            recordingApi.enqueueBucketingCalls.isEmpty(),
        )
    }

    @Test
    fun `runExperience with enableTracking false still persists sticky decision`() {
        val sdk = buildSdk(testConfig())
        val ctx = sdk.createContext("visitor_abc")

        val first = ctx.runExperience("welcome", enableTracking = false)
        val second = ctx.runExperience("welcome", enableTracking = true)

        // Sticky returns the same variation on the second call; the
        // second call's enableTracking flag does NOT trigger another
        // enqueue because the sticky path short-circuits before the
        // bucketing step.
        //
        // Compare on variation id / key / experience id rather than full
        // equality — the JS SDK surface has `bucketingAllocation` populated
        // on the fresh-bucket path and `null` on the sticky path, so the
        // two Variation objects are intentionally unequal at the whole-object
        // level while pointing at the same selected variation.
        assertNotNull(first)
        assertNotNull(second)
        assertEquals(first?.id, second?.id)
        assertEquals(first?.key, second?.key)
        assertEquals(first?.experienceId, second?.experienceId)
    }

    @Test
    fun `runExperience sticky path does NOT enqueue bucketing event`() {
        val sdk = buildSdk(testConfig())
        val recordingApi = RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        ctx.runExperience("welcome") // first call — enqueues
        ctx.runExperience("welcome") // sticky — no enqueue

        assertEquals(
            "Sticky path should not re-enqueue the bucketing event",
            1,
            recordingApi.enqueueBucketingCalls.size,
        )
    }

    // --- AC-10: internal SystemEvents.BUCKETING still fires in all cases --

    @Test
    fun `runExperience fires SystemEvents BUCKETING on new bucketing`() {
        val sdk = buildSdk(testConfig())
        val received = mutableListOf<Map<String, Any?>>()
        val callback = RecordingEventCallback(received)
        sdk.on(SystemEvents.BUCKETING, callback)
        val ctx = sdk.createContext("visitor_abc")

        ctx.runExperience("welcome")

        // Event dispatch is scope-scheduled; wait briefly for the async fire.
        awaitCondition { received.isNotEmpty() }

        assertEquals(1, received.size)
        val payload = received.first()
        assertEquals("welcome", payload["experienceKey"])
        assertEquals("control", payload["variationKey"])
        assertEquals("visitor_abc", payload["visitorId"])
    }

    @Test
    fun `runExperience fires SystemEvents BUCKETING when enableTracking is false`() {
        // Internal bus is NOT gated by the per-call tracking flag — only
        // the outbound network enqueue is.
        val sdk = buildSdk(testConfig())
        val received = mutableListOf<Map<String, Any?>>()
        val callback = RecordingEventCallback(received)
        sdk.on(SystemEvents.BUCKETING, callback)
        val ctx = sdk.createContext("visitor_abc")

        ctx.runExperience("welcome", enableTracking = false)

        awaitCondition { received.isNotEmpty() }
        assertEquals(1, received.size)
    }

    // --- AC-8: performance (<10ms in test, loose bound for CI variability) --

    @Test
    fun `runExperience completes in under 10ms after warmup`() {
        val sdk = buildSdk(testConfig())
        val ctx = sdk.createContext("visitor_perf_1")

        // Warmup — let JIT kick in on the hash pipeline.
        ctx.runExperience("welcome")

        // Different visitor so we exercise the NON-sticky hash path again.
        val coldCtx = sdk.createContext("visitor_perf_2")
        val elapsed = measureTimeMillis {
            coldCtx.runExperience("welcome")
        }

        assertTrue(
            "runExperience took ${elapsed}ms — expected <10ms on the cold-hash path (NFR2 bound)",
            elapsed < 10L,
        )
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Polls the supplied condition for up to ~1s at 10ms intervals. Used to
     * wait for the EventManager's scope-scheduled BUCKETING callbacks.
     */
    private fun awaitCondition(timeoutMs: Long = 1000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for condition", check())
    }

    /**
     * Simple [EventCallback] helper that appends every dispatched payload
     * to the supplied [sink]. Replaces an inline anonymous-object literal
     * that tripped detekt's `Wrapping` rule on the `sdk.on(event, object : EventCallback {...})`
     * call site.
     */
    internal class RecordingEventCallback(
        private val sink: MutableList<Map<String, Any?>>,
    ) : EventCallback {
        override fun onEvent(data: Map<String, Any?>) {
            sink.add(data)
        }
    }

    /** Recorded call to [com.convert.sdk.core.api.ApiManager.enqueueBucketingEvent]. */
    internal data class EnqueueBucketingCall(
        val visitorId: String,
        val experienceId: String,
        val variationId: String,
    )

    /**
     * Hand-rolled fake ApiManager subclass that records every
     * `enqueueBucketingEvent` call. Lives in the test file (no shared
     * module) so it stays internal to these tests.
     *
     * Constructed with minimal dependencies — the base class fields
     * `httpClient` / `logger` / `config` / `json` are unused by the
     * stub we're spying on.
     */
    internal class RecordingApiManager :
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
            json = kotlinx.serialization.json.Json {
                ignoreUnknownKeys = true
                explicitNulls = false
            },
        ) {

        val enqueueBucketingCalls: MutableList<EnqueueBucketingCall> = mutableListOf()

        override fun enqueueBucketingEvent(
            visitorId: String,
            experienceId: String,
            variationId: String,
        ) {
            enqueueBucketingCalls.add(
                EnqueueBucketingCall(visitorId, experienceId, variationId),
            )
        }
    }
}
