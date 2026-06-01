/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.model.generated.ClicksElementGoalSettings
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigGoal
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.model.generated.RuleObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * Robolectric-backed tests for Story 4.4 — visitor segmentation.
 *
 * AC-7 enumerates the five required unit tests:
 *  - `setDefaultSegments stores and returns context`
 *  - `setCustomSegments stores JsonElement values`
 *  - `segments are replaced not merged across calls`
 *  - `segments are included in bucketing event payload` — asserts via
 *    `RecordingApiManager.enqueueBucketingCalls[].segments`
 *  - `segments persist across restart`
 *
 * We also add tests for:
 *  - AC-3 custom-wins-on-collision merge semantics
 *  - AC-3 conversion event carries segments
 *  - AC-6 empty map clears segments to emptyMap (not null) in StoreData
 *
 * ### Robolectric vs pure JVM
 *
 * The setter-returns-this and replace-semantics assertions could run on
 * pure JVM (no Android APIs), but the persistence tests (AC-4, AC-6) and
 * payload tests (AC-3) need a real DataManager backed by
 * SharedPreferences. Keeping all tests in the same class under
 * [RobolectricTestRunner] keeps the fixture consistent.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextSegmentsTest {

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
     * Minimal config with one experience (`welcome` → 100% `var-a`) and one
     * goal (`purchase` → id `g-42`). Used by payload tests that need a
     * successful bucketing + conversion path end-to-end.
     */
    private fun testConfig(): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-1",
                key = "welcome",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-a",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(DEFAULT_PCT),
                    ),
                ),
            ),
        ),
        goals = listOf(TestGoal(id = "g-42", key = "purchase", name = "Purchase")),
    )

    /**
     * Minimal [ConfigGoal] — mirrors the pattern in
     * [ConvertContextTrackConversionTest.TestGoal].
     */
    private data class TestGoal(
        override val id: String? = null,
        override val name: String? = null,
        override val key: String? = null,
        override val type: String? = null,
        override val rules: RuleObject? = null,
        override val settings: ClicksElementGoalSettings? = null,
    ) : ConfigGoal

    private fun buildSdk(config: ConfigResponseData = testConfig()): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-1 / AC-5 — setDefaultSegments stores and returns context -------

    @Test
    fun `setDefaultSegments stores and returns context`() {
        val sdk = buildSdk()
        val ctx = sdk.createContext("visitor_abc")

        val returned = ctx.setDefaultSegments(
            mapOf("country" to "US", "device" to "mobile"),
        )

        // AC-1: fluent return of the same context instance.
        assertSame("setDefaultSegments must return the same context", ctx, returned)

        // Snapshot shows the stored value. Default segments are held as
        // Map<String, String> in-memory; debugSnapshot exposes the raw map.
        @Suppress("UNCHECKED_CAST")
        val stored = ctx.debugSnapshot()["defaultSegments"] as? Map<String, String>
        assertEquals(mapOf("country" to "US", "device" to "mobile"), stored)
    }

    // --- AC-2 — setCustomSegments stores JsonElement values ----------------

    @Test
    fun `setCustomSegments stores JsonElement values`() {
        val sdk = buildSdk()
        val ctx = sdk.createContext("visitor_abc")

        val customs: Map<String, JsonElement> = mapOf(
            "plan_tier" to JsonPrimitive("gold"),
            "ltv" to JsonPrimitive(1234.56),
            "beta_tester" to JsonPrimitive(true),
            "tags" to buildJsonArray {
                add(JsonPrimitive("a"))
                add(JsonPrimitive("b"))
            },
            "profile" to buildJsonObject { put("age", 30) },
        )

        val returned = ctx.setCustomSegments(customs)

        assertSame(ctx, returned)

        // Internal coerced accessor returns JsonElement values verbatim
        // (the setter stored them into the Any? backing field but the
        // Gotcha 7 passthrough leaves JsonElement unchanged).
        val coerced = ctx.currentCustomSegments()
        assertEquals(customs["plan_tier"], coerced["plan_tier"])
        assertEquals(customs["ltv"], coerced["ltv"])
        assertEquals(customs["beta_tester"], coerced["beta_tester"])
        assertEquals(customs["tags"], coerced["tags"])
        assertEquals(customs["profile"], coerced["profile"])
    }

    // --- AC-5 — segments are replaced not merged across calls --------------

    @Test
    fun `segments are replaced not merged across calls`() {
        val sdk = buildSdk()
        val ctx = sdk.createContext("visitor_abc")

        ctx.setDefaultSegments(mapOf("country" to "US", "device" to "mobile"))
        ctx.setDefaultSegments(mapOf("country" to "CA"))

        @Suppress("UNCHECKED_CAST")
        val defaults = ctx.debugSnapshot()["defaultSegments"] as? Map<String, String>
        // "device" is gone — replace, not merge.
        assertEquals(mapOf("country" to "CA"), defaults)

        ctx.setCustomSegments(mapOf("a" to JsonPrimitive(1), "b" to JsonPrimitive(2)))
        ctx.setCustomSegments(mapOf("b" to JsonPrimitive(99)))

        val customs = ctx.currentCustomSegments()
        assertEquals(setOf("b"), customs.keys)
        assertEquals(JsonPrimitive(99), customs["b"])
    }

    // --- AC-3 — segments are included in bucketing event payload -----------
    //
    // F-111 remediation note: AC-7 also requires a serialized-JSON
    // assertion — build a `TrackingEvent` batch via
    // `TrackingPayloadBuilder.build(events)`, parse with
    // `Json.parseToJsonElement`, and assert that `visitors[0].segments`
    // carries the expected key-value pairs. That builder is a Story 5.3
    // deliverable that does not exist at the time of this story; the JSON
    // schema assertion lives in Story 5.6's `TrackingPayloadTest`
    // (AC-2 — top-level schema). The mock-inspection assertions below
    // verify the in-memory contract (segments reach the ApiManager call
    // site with the merged shape); structural JSON verification is owned
    // by 5.6 and must not be re-litigated here once 5.3/5.6 land.

    @Test
    fun `segments are included in bucketing event payload`() {
        val sdk = buildSdk()
        val recordingApi = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")
            .setDefaultSegments(mapOf("country" to "US"))
            .setCustomSegments(mapOf("plan" to JsonPrimitive("gold")))

        ctx.runExperience("welcome")

        assertEquals(1, recordingApi.enqueueBucketingCalls.size)
        val call = recordingApi.enqueueBucketingCalls.single()
        // Default segments coerced to JsonPrimitive string values.
        assertEquals(JsonPrimitive("US"), call.segments["country"])
        assertEquals(JsonPrimitive("gold"), call.segments["plan"])
        assertEquals(2, call.segments.size)
    }

    // --- AC-3 — segments are included in conversion event payload ----------

    @Test
    fun `segments are included in conversion event payload`() {
        val sdk = buildSdk()
        val recordingApi = ConvertContextTrackConversionTest.RecordingConversionApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")
            .setDefaultSegments(mapOf("country" to "US"))
            .setCustomSegments(mapOf("plan" to JsonPrimitive("gold")))

        ctx.trackConversion("purchase")

        awaitCondition { recordingApi.enqueueConversionCalls.isNotEmpty() }
        val call = recordingApi.enqueueConversionCalls.first()
        assertEquals(JsonPrimitive("US"), call.segments["country"])
        assertEquals(JsonPrimitive("gold"), call.segments["plan"])
    }

    // --- AC-3 — custom segments override defaults on key collision ---------

    @Test
    fun `custom segments override defaults on key collision`() {
        val sdk = buildSdk()
        val recordingApi = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        val ctx = sdk.createContext("visitor_abc")
            .setDefaultSegments(mapOf("region" to "us-east"))
            .setCustomSegments(mapOf("region" to JsonPrimitive("eu-west")))

        ctx.runExperience("welcome")

        val call = recordingApi.enqueueBucketingCalls.single()
        // Custom wins per Dev Notes Gotcha 1.
        assertEquals(JsonPrimitive("eu-west"), call.segments["region"])
        assertEquals(1, call.segments.size)
    }

    // --- AC-4 — segments persist across restart ----------------------------

    @Test
    fun `segments persist across restart`() {
        val sdk1 = buildSdk()
        val ctx = sdk1.createContext("visitor_abc")
            .setDefaultSegments(mapOf("country" to "US"))
            .setCustomSegments(mapOf("plan" to JsonPrimitive("gold")))

        // Force the merged segments to be persisted by running any setter;
        // both setDefaultSegments and setCustomSegments must write to
        // StoreData synchronously (AC-4).
        val persisted1 = sdk1.dataManager.getStoreData("visitor_abc").segments
        assertNotNull("segments must be persisted after setters", persisted1)
        assertEquals(JsonPrimitive("US"), persisted1?.get("country"))
        assertEquals(JsonPrimitive("gold"), persisted1?.get("plan"))

        // Simulate restart: build a new SDK instance pointing at the same
        // SharedPreferences file. The new DataManager reads the persisted
        // StoreData on first getStoreData() call.
        val sdk2 = ConvertSDK.builder(appContext).data(testConfig()).build()
        awaitCondition(timeoutMs = 2_000L) { sdk2.dataManager.hasData() }

        val persisted2 = sdk2.dataManager.getStoreData("visitor_abc").segments
        assertEquals(JsonPrimitive("US"), persisted2?.get("country"))
        assertEquals(JsonPrimitive("gold"), persisted2?.get("plan"))
    }

    // --- AC-6 — empty map clears segments to emptyMap (not null) -----------

    @Test
    fun `empty segments map clears stored segments`() {
        val sdk = buildSdk()
        val ctx = sdk.createContext("visitor_abc")
            .setDefaultSegments(mapOf("country" to "US"))

        // Snapshot 1: segments populated.
        val populated = sdk.dataManager.getStoreData("visitor_abc").segments
        assertEquals(JsonPrimitive("US"), populated?.get("country"))

        // Now clear.
        ctx.setDefaultSegments(emptyMap())

        val cleared = sdk.dataManager.getStoreData("visitor_abc").segments
        // AC-6 — empty map, NOT null.
        assertNotNull("cleared segments must be an empty map, not null", cleared)
        assertTrue("cleared segments must be empty", cleared!!.isEmpty())
    }

    // --- helpers ----------------------------------------------------------

    private fun awaitCondition(timeoutMs: Long = 1000L, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(POLL_MS)
        }
        assertTrue("Timed out waiting for condition", check())
    }

    private companion object {
        private const val POLL_MS: Long = 10L
        private const val DEFAULT_PCT: Double = 100.0
    }
}
