/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * Robolectric-backed tests for Story 3.3 AC-1 .. AC-7:
 * `ConvertContext.runExperiences(enableTracking)` thin-wrapper behaviour
 * over the existing Story 3.2 `runExperience` full-body.
 *
 * ### Why Robolectric
 *
 * `runExperiences` delegates to `runExperience`, which itself reads the
 * visitor's sticky state through
 * [com.convert.sdk.core.data.DataManager.getStoreData]. That in turn
 * reads `SharedPreferences` on cache miss, so we use a real
 * Robolectric-backed Android context — the same pattern the sibling
 * [ConvertContextRunExperienceTest] uses.
 *
 * ### Fixture shape
 *
 * `twoExperienceConfig()` builds a [ConfigResponseData] with TWO
 * experiences (`exp-1` keyed `welcome` and `exp-2` keyed `farewell`),
 * each carrying two 50/50 variations. For `visitor_abc` the bucketing
 * algorithm resolves `welcome` → `var-a` (control) deterministically;
 * the matching variation on `farewell` is whichever side of the wheel
 * the separate `exp-2` hash lands on (we do not assert on the id, only
 * that a non-null variation was returned with `experienceKey` set).
 *
 * `excludedSecondExperienceConfig()` builds a config where the second
 * experience has two zero-traffic variations, so `runExperience` returns
 * null for it and `runExperiences` drops it from the list — this
 * exercises the "skip and continue" contract from AC-7 test 5.
 *
 * ### Recording helpers
 *
 * [RecordingApiManager] is a hand-rolled fake that records every
 * `enqueueBucketingEvent` call; we use it to prove the
 * `enableTracking = false` gate and the sticky short-circuit both
 * suppress the outbound enqueue.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextRunExperiencesTest {

    private lateinit var appContext: Context

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        // Pristine SharedPreferences — prevents leakage of sticky decisions
        // between tests.
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    // --- fixtures ---------------------------------------------------------

    /**
     * Two concurrent experiences, each with a 50/50 wheel — the common
     * case `runExperiences` is designed for.
     */
    private fun twoExperienceConfig(): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-1",
                key = "welcome",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-a",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(50.0),
                    ),
                    ExperienceVariationConfig(
                        id = "var-b",
                        key = "treatment",
                        trafficAllocation = BigDecimal.valueOf(50.0),
                    ),
                ),
            ),
            ConfigExperience(
                id = "exp-2",
                key = "farewell",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-c",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(50.0),
                    ),
                    ExperienceVariationConfig(
                        id = "var-d",
                        key = "treatment",
                        trafficAllocation = BigDecimal.valueOf(50.0),
                    ),
                ),
            ),
        ),
    )

    /**
     * Two experiences where the second has zero-traffic variations.
     * `buildBuckets` drops zero-allocation variations, so
     * `runExperience("doomed")` returns `null` and `runExperiences` skips
     * it via [mapNotNull]. Exercises AC-7 test 5 (continues with others).
     */
    private fun excludedSecondExperienceConfig(): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-1",
                key = "welcome",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-a",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(50.0),
                    ),
                    ExperienceVariationConfig(
                        id = "var-b",
                        key = "treatment",
                        trafficAllocation = BigDecimal.valueOf(50.0),
                    ),
                ),
            ),
            ConfigExperience(
                id = "exp-2",
                key = "doomed",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-x",
                        key = "nope",
                        trafficAllocation = BigDecimal.ZERO,
                    ),
                    ExperienceVariationConfig(
                        id = "var-y",
                        key = "also-nope",
                        trafficAllocation = BigDecimal.ZERO,
                    ),
                ),
            ),
        ),
    )

    /**
     * Builds the SDK in direct-data mode and waits for the data seed
     * coroutine to finish before returning. Same pattern as
     * [ConvertContextRunExperienceTest.buildSdk].
     */
    private fun buildSdk(config: ConfigResponseData): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-1 step 1: config-not-ready gate -------------------------------

    @Test
    fun `runExperiences returns empty before config ready`() {
        // No data() and no sdkKey → hasData is permanently false in this test.
        val sdk = ConvertSDK.builder(appContext).build()
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runExperiences()

        assertTrue(
            "Expected empty list when config is not ready, got $result",
            result.isEmpty(),
        )
    }

    // --- AC-1 / AC-5: batch evaluation across all experiences -------------

    @Test
    fun `runExperiences returns variations for all eligible experiences`() {
        val sdk = buildSdk(twoExperienceConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runExperiences()

        assertEquals(
            "Expected one variation per eligible experience (welcome + farewell)",
            2,
            result.size,
        )
        // Ordering is config-declaration order (Gotcha 2). `welcome` is
        // declared first, so it must be index 0.
        assertEquals("welcome", result[0].experienceKey)
        assertEquals("farewell", result[1].experienceKey)
        // Both entries point at real variations.
        assertNotNull(result[0].id)
        assertNotNull(result[1].id)
    }

    // --- AC-3: sticky decisions honored on subsequent calls ---------------

    @Test
    fun `runExperiences respects sticky decisions on second call`() {
        val sdk = buildSdk(twoExperienceConfig())
        val recordingApi = RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        val first = ctx.runExperiences()
        val second = ctx.runExperiences()

        assertEquals(2, first.size)
        assertEquals(2, second.size)
        // Same variation ids (sticky) — compare by experienceKey → id pairs.
        assertEquals(
            first.associate { it.experienceKey to it.id },
            second.associate { it.experienceKey to it.id },
        )
        // First call: one enqueue per experience (2 experiences → 2 events).
        // Second call: all-sticky, no additional enqueues.
        assertEquals(
            "Expected 2 total bucketing events (first call only); sticky calls " +
                "must not re-enqueue. Got: ${recordingApi.enqueueBucketingCalls}",
            2,
            recordingApi.enqueueBucketingCalls.size,
        )
    }

    // --- AC-2 / AC-4: per-call enableTracking gate ------------------------

    @Test
    fun `runExperiences with enableTracking false enqueues zero events`() {
        val sdk = buildSdk(twoExperienceConfig())
        val recordingApi = RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runExperiences(enableTracking = false)

        assertEquals(
            "enableTracking=false must suppress network enqueues; recorded: " +
                "${recordingApi.enqueueBucketingCalls}",
            0,
            recordingApi.enqueueBucketingCalls.size,
        )
        // The returned list is still populated — gate is only for outbound
        // tracking, not for returning the bucketed variations.
        assertFalse(
            "runExperiences(false) should still return bucketed variations",
            result.isEmpty(),
        )
        assertEquals(2, result.size)
    }

    // --- AC-7 test 5: continue past a per-experience failure --------------

    @Test
    fun `runExperiences skips experiences that cannot bucket and continues with others`() {
        val sdk = buildSdk(excludedSecondExperienceConfig())
        val ctx = sdk.createContext("visitor_abc")

        val result = ctx.runExperiences()

        // `doomed` has all-zero allocations → runExperience returns null →
        // mapNotNull drops it. `welcome` still buckets for visitor_abc.
        assertEquals(
            "Expected 1 variation (welcome only); doomed should be skipped. " +
                "Got: ${result.map { it.experienceKey }}",
            1,
            result.size,
        )
        assertEquals("welcome", result[0].experienceKey)
    }

    // --- helpers ----------------------------------------------------------

    /**
     * Polls the supplied condition for up to [timeoutMs] at 10 ms
     * intervals. Used to wait for the SDK's direct-data seed coroutine
     * to complete before the test calls into `runExperiences`.
     */
    private fun awaitCondition(timeoutMs: Long = 1_000L, check: () -> Boolean) {
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
        val segments: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
    )

    /**
     * Hand-rolled fake ApiManager subclass that records every
     * `enqueueBucketingEvent` call. A copy of the sibling test's spy —
     * duplicated here so the two test files are independent. The base
     * class fields (`httpClient` / `logger` / `config` / `json`) are
     * unused by the stub we're spying on.
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
            segments: Map<String, kotlinx.serialization.json.JsonElement>,
        ) {
            enqueueBucketingCalls.add(
                EnqueueBucketingCall(visitorId, experienceId, variationId, segments),
            )
        }
    }
}
