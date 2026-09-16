/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.internal.sharedSerializersModule
import com.convert.sdk.core.model.FeatureStatus
import com.convert.sdk.core.model.generated.ConfigResponseData
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Collections

/**
 * CAP-1 (SPEC-per-call-bucketing-attributes) behavioural RED tests for the
 * `enableTracking` parameter on [ConvertContext.runFeature] /
 * [ConvertContext.runFeatures]. Asserted at the [ConvertContext] boundary,
 * never against [FeatureManager] directly — that class already threads
 * `enableTracking`, which is exactly why the public-surface gap survived.
 *
 * Every negative fire assertion below carries a positive control in the
 * SAME test: [SystemEvents] dispatch is scope-scheduled, so an
 * unconditional "stayed empty" read is vacuous unless a fire that MUST
 * happen is first proven to land.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextRunFeatureTrackingControlTest {

    private lateinit var appContext: Context

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = sharedSerializersModule
    }

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    // --- fixtures ----------------------------------------------------------

    /**
     * Two experiences, each exposing only its own feature, both variations
     * at 100% traffic allocation — every visitor is bucketed regardless of
     * hash, so the fixture needs no visitor-specific hash bookkeeping.
     */
    private fun twoExperienceConfigJson(): String = """
    {
      "experiences": [
        {
          "id": "exp-1",
          "key": "welcome-a",
          "variations": [
            {
              "id": "var-a1",
              "key": "control",
              "traffic_allocation": 100.0,
              "changes": [
                {"id": 1, "type": "fullStackFeature", "data": {"feature_id": 100, "variables_data": {}}}
              ]
            }
          ]
        },
        {
          "id": "exp-2",
          "key": "welcome-b",
          "variations": [
            {
              "id": "var-b1",
              "key": "control",
              "traffic_allocation": 100.0,
              "changes": [
                {"id": 2, "type": "fullStackFeature", "data": {"feature_id": 200, "variables_data": {}}}
              ]
            }
          ]
        }
      ],
      "features": [
        {"id": "100", "key": "feature-a", "name": "Feature A", "variables": []},
        {"id": "200", "key": "feature-b", "name": "Feature B", "variables": []}
      ]
    }
    """.trimIndent()

    /**
     * One experience carrying TWO features on its sole variation — the
     * sticky-revisit fixture: `evaluateAll`'s second declared feature
     * revisits this SAME experience via the sticky path.
     */
    private fun dualFeatureConfigJson(): String = """
    {
      "experiences": [
        {
          "id": "exp-dual",
          "key": "dual-flags",
          "variations": [
            {
              "id": "var-dual",
              "key": "control",
              "traffic_allocation": 100.0,
              "changes": [
                {"id": 1, "type": "fullStackFeature", "data": {"feature_id": 100, "variables_data": {}}},
                {"id": 2, "type": "fullStackFeature", "data": {"feature_id": 200, "variables_data": {}}}
              ]
            }
          ]
        }
      ],
      "features": [
        {"id": "100", "key": "feature-a", "name": "Feature A", "variables": []},
        {"id": "200", "key": "feature-b", "name": "Feature B", "variables": []}
      ]
    }
    """.trimIndent()

    // --- helpers -------------------------------------------------------

    private fun buildSdk(configJson: String): ConvertSDK {
        val config: ConfigResponseData = json.decodeFromString(configJson)
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    /** Synchronized per Finding #2 — [ConvertContextRunExperienceTest.RecordingEventCallback]'s
     * plain `MutableList` loses entries under concurrent `add` once a test asserts across
     * two-or-more fires. */
    private fun recordingSink(): MutableList<Map<String, Any?>> =
        Collections.synchronizedList(mutableListOf())

    private fun awaitCondition(timeoutMs: Long = 1000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for condition", check())
    }

    /**
     * One arm's fully-wired fixture: a fresh [ConvertSDK] for [configJson],
     * its own [RecordingApiManager]/[RecordingEventCallback] pair, and a
     * context for [visitorId] — never shared across arms per CD-3.
     */
    private data class Arm(
        val sdk: ConvertSDK,
        val api: ConvertContextRunExperienceTest.RecordingApiManager,
        val sink: MutableList<Map<String, Any?>>,
        val ctx: ConvertContext,
    )

    private fun newArm(configJson: String, visitorId: String): Arm {
        val sdk = buildSdk(configJson)
        val api = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(api)
        val sink = recordingSink()
        sdk.on(SystemEvents.BUCKETING, ConvertContextRunExperienceTest.RecordingEventCallback(sink))
        return Arm(sdk, api, sink, sdk.createContext(visitorId))
    }

    // --- fixture sanity: the exposes-only-its-own-feature invariant --------

    @Test
    fun `twoExperienceConfigJson exposes each feature to only its own experience`() {
        val config: ConfigResponseData = json.decodeFromString(twoExperienceConfigJson())
        val changesA = config.experiences?.first { it.key == "welcome-a" }
            ?.variations?.single()?.changes.orEmpty()
        val changesB = config.experiences?.first { it.key == "welcome-b" }
            ?.variations?.single()?.changes.orEmpty()

        assertEquals(1, changesA.size)
        assertEquals(1, changesB.size)
    }

    // --- CAP-1: runFeature(key, enableTracking = false) --------------------

    @Test
    fun `runFeature untracked suppresses enqueue and fire, still persists sticky, matches tracked result`() {
        // Positive control — a tracked call on its own sdk/visitor MUST enqueue and fire.
        val tracked = newArm(twoExperienceConfigJson(), "visitor_feature_a_tracked")

        val trackedFeature = tracked.ctx.runFeature("feature-a", enableTracking = true)
        awaitCondition { tracked.sink.isNotEmpty() }
        assertEquals(1, tracked.api.enqueueBucketingCalls.size)
        assertEquals(1, tracked.sink.size)

        // Untracked arm — a distinct sdk + visitor from the control above, so
        // neither the recorded counters nor the sticky state are shared.
        val untracked = newArm(twoExperienceConfigJson(), "visitor_feature_a_untracked")

        val untrackedFeature = untracked.ctx.runFeature("feature-a", enableTracking = false)
        Thread.sleep(200)

        assertTrue(
            "Expected no enqueueBucketingEvent calls, got ${untracked.api.enqueueBucketingCalls}",
            untracked.api.enqueueBucketingCalls.isEmpty(),
        )
        assertTrue(
            "Expected no SystemEvents.BUCKETING fires, got ${untracked.sink}",
            untracked.sink.isEmpty(),
        )
        assertEquals(
            "var-a1",
            untracked.sdk.dataManager.getStoreData("visitor_feature_a_untracked")
                .bucketing?.get("welcome-a"),
        )
        assertNotNull(untrackedFeature)
        assertEquals(FeatureStatus.ENABLED, untrackedFeature?.status)
        assertEquals(trackedFeature, untrackedFeature)
    }

    // --- CAP-1: runFeatures(enableTracking = false) -------------------------

    @Test
    fun `runFeatures untracked suppresses enqueue and fire for both, still persists sticky, matches tracked list`() {
        val tracked = newArm(twoExperienceConfigJson(), "visitor_features_tracked")

        val trackedFeatures = tracked.ctx.runFeatures(enableTracking = true)
        awaitCondition { tracked.sink.size >= 2 }
        assertEquals(2, tracked.api.enqueueBucketingCalls.size)
        assertEquals(2, tracked.sink.size)

        val untracked = newArm(twoExperienceConfigJson(), "visitor_features_untracked")

        val untrackedFeatures = untracked.ctx.runFeatures(enableTracking = false)
        Thread.sleep(200)

        assertTrue(
            "Expected no enqueueBucketingEvent calls, got ${untracked.api.enqueueBucketingCalls}",
            untracked.api.enqueueBucketingCalls.isEmpty(),
        )
        assertTrue(
            "Expected no SystemEvents.BUCKETING fires, got ${untracked.sink}",
            untracked.sink.isEmpty(),
        )
        val storedBucketing = untracked.sdk.dataManager
            .getStoreData("visitor_features_untracked").bucketing
        assertEquals("var-a1", storedBucketing?.get("welcome-a"))
        assertEquals("var-b1", storedBucketing?.get("welcome-b"))
        assertEquals(2, untrackedFeatures.size)
        assertEquals(trackedFeatures, untrackedFeatures)
    }

    // --- CAP-1: the default arities stay byte-identical to today -----------

    @Test
    fun `runFeature and runFeatures without the parameter still enqueue and fire exactly as today`() {
        val single = newArm(twoExperienceConfigJson(), "visitor_default_arity")

        val singleFeature = single.ctx.runFeature("feature-a")
        awaitCondition { single.sink.isNotEmpty() }
        assertEquals(1, single.api.enqueueBucketingCalls.size)
        assertEquals(1, single.sink.size)
        assertNotNull(singleFeature)

        val all = newArm(twoExperienceConfigJson(), "visitor_default_arity_all")

        val allFeatures = all.ctx.runFeatures()
        awaitCondition { all.sink.size >= 2 }
        assertEquals(2, all.api.enqueueBucketingCalls.size)
        assertEquals(2, all.sink.size)
        assertEquals(2, allFeatures.size)
    }

    // --- CAP-1: sticky-revisit within one experience carrying two features -

    @Test
    fun `runFeatures on one experience carrying two features enqueues once but fires twice`() {
        val arm = newArm(dualFeatureConfigJson(), "visitor_dual_feature")

        val results = arm.ctx.runFeatures()
        awaitCondition { arm.sink.size >= 2 }

        assertEquals(2, results.size)
        assertEquals(
            "Expected exactly one enqueue — the second declared feature revisits the " +
                "SAME experience via the sticky path",
            1,
            arm.api.enqueueBucketingCalls.size,
        )
        assertEquals(2, arm.sink.size)
    }
}
