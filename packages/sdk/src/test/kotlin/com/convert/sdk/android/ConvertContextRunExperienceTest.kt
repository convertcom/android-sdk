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
import org.junit.Assert.assertFalse
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

    private fun testConfig(
        experienceId: String = "exp-1",
        experienceKey: String = "welcome",
        variationIdA: String = "var-a",
        variationKeyA: String = "control",
        variationIdB: String = "var-b",
        variationKeyB: String = "treatment",
        // 50/50 split — default. Override per-test for traffic-miss scenarios.
        pctA: Double = 50.0,
        pctB: Double = 50.0,
    ): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = experienceId,
                key = experienceKey,
                variations = listOf(
                    ExperienceVariationConfig(
                        id = variationIdA,
                        key = variationKeyA,
                        trafficAllocation = BigDecimal.valueOf(pctA),
                    ),
                    ExperienceVariationConfig(
                        id = variationIdB,
                        key = variationKeyB,
                        trafficAllocation = BigDecimal.valueOf(pctB),
                    ),
                ),
            ),
        ),
    )

    private fun buildSdk(config: ConfigResponseData): ConvertSDK =
        ConvertSDK.builder(appContext).data(config).build()

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

        assertEquals(first, second)
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
        assertNotNull(first)
        assertEquals(first, second)
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
        sdk.on(SystemEvents.BUCKETING, object : EventCallback {
            override fun onEvent(data: Map<String, Any?>) {
                received.add(data)
            }
        })
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
        sdk.on(SystemEvents.BUCKETING, object : EventCallback {
            override fun onEvent(data: Map<String, Any?>) {
                received.add(data)
            }
        })
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
