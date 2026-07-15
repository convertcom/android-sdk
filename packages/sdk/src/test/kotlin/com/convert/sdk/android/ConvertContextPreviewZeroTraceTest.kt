/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import android.os.Looper
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.convert.sdk.android.worker.EventFlushWorker
import com.convert.sdk.core.api.ApiManager
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.generated.ClicksElementGoalSettings
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigGoal
import com.convert.sdk.core.model.generated.ConfigProject
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.model.generated.RuleObject
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * qs-02 / AND-6 (contract §2 "Zero-trace", AC6, AC7) — durable-pipeline
 * zero-trace tests for [ConvertContext.setPreview].
 *
 * ### What "zero trace" means here (contract §2)
 *
 * While a preview is active on a context, ALL tracking is suppressed at
 * the enqueue source (nothing reaches the in-memory [com.convert.sdk.core.api.ApiManager]
 * queue, the durable [com.convert.sdk.android.adapter.FileEventQueue], or a
 * network request) and ALL visitor-state persistence writes are suppressed
 * (no `SharedPreferences` sticky-bucketing / goal-tracked / segments
 * writes). This holds for EVERY experience/goal evaluated on the preview
 * context — not just the previewed one (contract §2 "other experiences
 * still evaluate and decide normally for coherent rendering, but nothing
 * is tracked or persisted from the preview").
 *
 * ### WorkManager-clause interpretation (spec-silent decision, recorded here)
 *
 * AC6 reads "zero WorkManager enqueues". Story 5.3's [ConvertSDK.onProcessStop]
 * unconditionally flushes and enqueues an [EventFlushWorker] on ANY
 * backgrounding transition — that scheduling is preview-independent
 * lifecycle behaviour, out of AND-6's scope to change (shared, shipped
 * code; changing it would violate the additive-only mandate). The literal
 * "zero WorkManager enqueues" reading would therefore fail even with a
 * perfectly zero-trace preview implementation. This suite instead asserts
 * the INTENT: after driving [ConvertSDK.onProcessStopForTest] with a
 * preview active, the durable pipeline carries zero preview trace because
 * the flush worker — scheduled or not — has nothing to drain: the
 * in-memory [com.convert.sdk.core.api.ApiManager] snapshot is empty, the
 * [com.convert.sdk.android.adapter.FileEventQueue] is empty, and zero
 * requests ever reach the track endpoint. [EventFlushWorkerDrainTest]
 * already regression-locks "empty queue → no HTTP call, no matter how the
 * worker is invoked" — this suite does not re-derive that; it proves the
 * PRECONDITION (nothing preview-sourced ever enters either queue) holds
 * across a full context lifecycle including the background transition.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextPreviewZeroTraceTest {

    private lateinit var appContext: Context
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        appContext = ApplicationProvider.getApplicationContext()
        appContext
            .getSharedPreferences("com.convert.sdk.visitor", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        WorkManagerTestInitHelper.initializeTestWorkManager(
            appContext,
            Configuration.Builder().setMinimumLoggingLevel(android.util.Log.VERBOSE).build(),
        )
        server = MockWebServer()
        server.dispatcher = TrackOnlyDispatcher()
        server.start()
        ShadowLog.clear()
    }

    @After
    fun tearDown() {
        server.shutdown()
        // AC-6.2 WorkManager isolation (same rationale as
        // ConvertSDKLifecycleHooksTest): without closing the database, the
        // SQLite-backed queue leaks enqueued work across tests in the same
        // JVM run.
        runCatching { WorkManager.getInstance(appContext).cancelAllWork() }
        runCatching { WorkManagerTestInitHelper.closeWorkDatabase() }
    }

    /** Any POST containing `/track/` succeeds; everything else 404s loudly. */
    private inner class TrackOnlyDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path ?: ""
            return if (request.method == "POST" && path.contains("/track/")) {
                MockResponse().setResponseCode(HTTP_OK).setBody("")
            } else {
                MockResponse().setResponseCode(HTTP_NOT_FOUND)
            }
        }
    }

    // --- fixtures ---------------------------------------------------------

    /**
     * `exp-1` keyed `welcome` (two 50/50 variations, previewed as
     * `var-b`) + `exp-2` keyed `promo` (single 100% variation,
     * deterministic) — `promo` proves "a DIFFERENT experience on the same
     * preview context still decides normally" while exercising the
     * AND-6-gated [ConvertContext] persist/enqueue path. `zt-goal` backs
     * the conversion-attempt step.
     */
    private fun zeroTraceConfig(): ConfigResponseData = ConfigResponseData(
        accountId = "acc-zt",
        project = ConfigProject(id = "proj-zt"),
        experiences = listOf(
            ConfigExperience(
                id = "exp-1",
                key = "welcome",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-a",
                        key = "control",
                        trafficAllocation = BigDecimal.valueOf(FIFTY_FIFTY),
                    ),
                    ExperienceVariationConfig(
                        id = "var-b",
                        key = "treatment",
                        trafficAllocation = BigDecimal.valueOf(FIFTY_FIFTY),
                    ),
                ),
            ),
            ConfigExperience(
                id = "exp-2",
                key = "promo",
                variations = listOf(
                    ExperienceVariationConfig(
                        id = "var-p",
                        key = "promo-v",
                        trafficAllocation = BigDecimal.valueOf(FULL_ALLOCATION),
                    ),
                ),
            ),
        ),
        goals = listOf(ZeroTraceGoal(id = "g-zt", key = "zt-goal")),
    )

    /**
     * In-test [ConfigGoal] impl — same minimal-field pattern used by
     * [com.convert.sdk.android.integration.FullChainIntegrationTest].
     */
    private data class ZeroTraceGoal(
        override val id: String? = null,
        override val name: String? = null,
        override val key: String? = null,
        override val type: String? = null,
        override val rules: RuleObject? = null,
        override val settings: ClicksElementGoalSettings? = null,
    ) : ConfigGoal

    private fun buildSdk(sdkKey: String): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey(sdkKey)
            .data(zeroTraceConfig())
            .trackEndpoint(server.url("/").toString())
            .batchSize(LARGE_BATCH_SIZE)
            .releaseInterval(LONG_RELEASE_INTERVAL_MS)
            .dataRefreshInterval(LONG_DATA_REFRESH_INTERVAL_MS)
            .build()
        awaitCondition { sdk.dataManager.hasData() }
        return sdk
    }

    private fun awaitCondition(timeoutMs: Long = AWAIT_TIMEOUT_MS, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(AWAIT_POLL_MS)
        }
        assertTrue("Timed out waiting for condition", check())
    }

    // ---------------------------------------------------------------
    // Review R3 (F2) — non-vacuous baseline: the SAME observation
    // mechanism used by the "must NOT fire" assertions below DOES detect
    // a real fire when no preview is set at all. Without this, a broken
    // subscription (e.g. a typo'd event name) could make every
    // suppression assertion in this file pass for the wrong reason.
    // ---------------------------------------------------------------

    @Test
    fun `BUCKETING and CONVERSION fire normally when no preview is set (non-vacuous baseline)`() {
        val sdk = buildSdk("sk-baseline-fires")
        val ctx = sdk.createContext("visitor_baseline_fires")

        val bucketingLatch = CountDownLatch(1)
        sdk.on(SystemEvents.BUCKETING) { bucketingLatch.countDown() }
        val decision = ctx.runExperience("promo")
        assertEquals("var-p", decision?.id)
        assertTrue(
            "BUCKETING must fire normally with no preview set — proves the observation " +
                "mechanism used by the suppression assertions in this file is capable of " +
                "detecting a real fire",
            bucketingLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )

        val conversionLatch = CountDownLatch(1)
        sdk.on(SystemEvents.CONVERSION) { conversionLatch.countDown() }
        ctx.trackConversion(goalKey = "zt-goal")
        assertTrue(
            "CONVERSION must fire normally with no preview set (non-vacuous baseline)",
            conversionLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
    }

    // ---------------------------------------------------------------
    // AC6 — zero trace across bucketing, conversion, and background flush
    // ---------------------------------------------------------------

    @Test
    fun `preview lifecycle leaves zero trace across bucketing, conversion, and background flush`() {
        val sdk = buildSdk("sk-zero-trace")
        val ctx = sdk.createContext("visitor_preview_zt")
        ctx.setPreview(experienceId = "exp-1", variationId = "var-b")

        // Review R3 (F2, JS parity) — Android is now the only Convert SDK
        // to suppress the in-process SystemEvents bus during preview,
        // matching the JS SDK's uniform `if (!this._preview) { fire }`
        // gate (context.ts) and the Python SDK's `self._preview is None`
        // check. Subscribed BEFORE any run/track call below so both
        // negative assertions (no BUCKETING, no CONVERSION) cover the
        // whole lifecycle.
        var bucketingFired = false
        sdk.on(SystemEvents.BUCKETING) { bucketingFired = true }
        var conversionFired = false
        sdk.on(SystemEvents.CONVERSION) { conversionFired = true }

        // Previewed experience decides as forced — bypasses allocateAndRecord
        // entirely (resolvePreviewOverride short-circuits runExperience), so
        // this leg is inherently zero-trace by construction (and never fires
        // SystemEvents.BUCKETING at all — resolvePreviewOverride returns
        // directly without touching the event bus).
        val forced = ctx.runExperience("welcome")
        assertEquals("var-b", forced?.id)

        // A DIFFERENT experience on the SAME preview context (contract §2
        // "other experiences still evaluate and decide normally") — this is
        // the leg that actually exercises the AND-6 / Review R3 gates inside
        // allocateAndRecord (updateBucketing / enqueueBucketingEvent / the
        // SystemEvents.BUCKETING fire).
        val other = ctx.runExperience("promo")
        assertEquals("var-p", other?.id)

        // A conversion attempt on the preview context. Review R3 (F2) makes
        // dispatchConversion's ENTIRE step — network enqueue AND the
        // internal CONVERSION fire — a no-op while preview is active,
        // mirroring the JS SDK's trackConversion returning before its
        // CONVERSION fire (context.ts:514-521).
        ctx.trackConversion(
            goalKey = "zt-goal",
            goalData = listOf(GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(GOAL_AMOUNT))),
        )

        // Neither event has a positive completion signal to await now that
        // both are no-ops by design — settle over a bounded grace window
        // generous enough for the fire-and-forget coroutines to have run
        // if they were (incorrectly) going to fire, then assert absence.
        Thread.sleep(EVENT_SETTLE_MS)
        assertTrue(
            "BUCKETING must NOT fire internally for ANY experience while preview is active " +
                "(Review R3 F2, JS/Python parity)",
            !bucketingFired,
        )
        assertTrue(
            "CONVERSION must NOT fire internally while preview is active (Review R3 F2, JS/Python parity)",
            !conversionFired,
        )

        // Background transition — see the class doc for why this suite
        // asserts "drains an empty queue" rather than "zero WorkManager
        // enqueues": Story 5.3's onProcessStop schedules the flush worker
        // unconditionally on any backgrounding, preview or not.
        sdk.onProcessStopForTest()
        awaitCondition {
            WorkManager.getInstance(appContext)
                .getWorkInfosForUniqueWork(EventFlushWorker.UNIQUE_WORK_NAME)
                .get()
                .isNotEmpty()
        }
        shadowOf(Looper.getMainLooper()).idle()

        // --- Zero-trace assertions --------------------------------------
        assertEquals(
            "ApiManager in-memory queue must be empty — nothing from the " +
                "preview context ever entered it",
            0,
            sdk.apiManager!!.snapshotQueue().size,
        )
        assertEquals(
            "FileEventQueue must be empty — the WorkManager-backed durable " +
                "path carries zero preview trace because it has nothing to drain",
            0,
            runBlocking { sdk.fileEventQueue!!.size() },
        )
        assertEquals(
            "zero requests must ever reach the mock track endpoint",
            0,
            server.requestCount,
        )
        val store = sdk.dataManager.getStoreData("visitor_preview_zt")
        assertTrue(
            "no sticky-bucketing write for either the previewed or the " +
                "coherently-rendered experience while preview is active",
            store.bucketing.isNullOrEmpty(),
        )
        assertTrue(
            "no persisted goal-tracked write for the preview context",
            store.goals.isNullOrEmpty(),
        )
    }

    // ---------------------------------------------------------------
    // AC7 — isolation: a concurrent non-preview context tracks/persists normally
    // ---------------------------------------------------------------

    @Test
    fun `a concurrent non-preview context tracks and persists normally`() {
        val sdk = buildSdk("sk-isolation")
        val previewCtx = sdk.createContext("visitor_preview_iso")
        previewCtx.setPreview(experienceId = "exp-1", variationId = "var-b")
        previewCtx.runExperience("welcome")

        val normalCtx = sdk.createContext("visitor_normal_iso")
        val normalResult = normalCtx.runExperience("promo")
        assertEquals("var-p", normalResult?.id)

        // The shared ApiManager queue must carry EXACTLY the non-preview
        // context's event — proving the preview context contributed zero.
        awaitCondition { sdk.apiManager!!.snapshotQueue().size == 1 }
        assertEquals(
            "the single queued event must belong to the non-preview visitor",
            "visitor_normal_iso",
            sdk.apiManager!!.snapshotQueue().single().visitorId,
        )

        sdk.flushForTesting()
        assertEquals(
            "the non-preview context's bucketing event must reach the track endpoint",
            1,
            server.requestCount,
        )

        val normalStore = sdk.dataManager.getStoreData("visitor_normal_iso")
        assertEquals(
            "the non-preview context's sticky decision must persist normally",
            "var-p",
            normalStore.bucketing?.get("promo"),
        )
        val previewStore = sdk.dataManager.getStoreData("visitor_preview_iso")
        assertTrue(
            "the preview context must still carry zero sticky-bucketing writes",
            previewStore.bucketing.isNullOrEmpty(),
        )
    }

    // ---------------------------------------------------------------
    // Review R3 (F2 sweep) — a STICKY recall on the preview context must
    // also suppress BUCKETING, not just a fresh allocateAndRecord decision
    // ---------------------------------------------------------------
    //
    // resolveSticky is a SEPARATE fire site from allocateAndRecord's. The
    // JS SDK has no equivalent branch split — its single
    // `if (!this._preview) { fire }` gate in context.ts covers whatever
    // selectVariation returns, sticky or fresh. This test locks in that
    // resolveSticky's fire is gated the same way once a preview becomes
    // active on the context, for a DIFFERENT (non-previewed) experience
    // that the visitor was already stickily bucketed into.

    @Test
    fun `a sticky recall on the preview context does not fire BUCKETING`() {
        val sdk = buildSdk("sk-sticky-suppress")
        val ctx = sdk.createContext("visitor_sticky_zt")

        // First call (no preview yet) buckets "promo" fresh and persists
        // the sticky decision — deterministic, single 100% variation.
        val firstDecision = ctx.runExperience("promo")
        assertEquals("var-p", firstDecision?.id)

        var bucketingFired = false
        sdk.on(SystemEvents.BUCKETING) { bucketingFired = true }

        ctx.setPreview(experienceId = "exp-1", variationId = "var-b")
        // Recall "promo" — now hits resolveSticky, not allocateAndRecord.
        val stickyRecall = ctx.runExperience("promo")
        assertEquals(
            "sticky recall must still resolve the same variation while preview is active",
            "var-p",
            stickyRecall?.id,
        )

        Thread.sleep(EVENT_SETTLE_MS)
        assertTrue(
            "BUCKETING must NOT fire for a sticky recall while preview is active on the context " +
                "(Review R3 F2 sweep)",
            !bucketingFired,
        )
    }

    // ---------------------------------------------------------------
    // Review R2 (Finding 1 sweep) — inert-on-bad-input must RESUME
    // tracking/persistence, not just decide normally
    // ---------------------------------------------------------------
    //
    // ConvertContextSetPreviewTest's existing inert tests only assert the
    // DECISION (runExperience returns the normally-bucketed variation).
    // That is necessary but not sufficient: contract §2 "Inert on bad
    // input" requires the context to behave FULLY normally, which
    // includes tracking enqueue and sticky-bucketing persistence — both
    // of which stay gated by ConvertContext.isPreviewActive() regardless
    // of what runExperience returns. These two table-driven tests (a
    // shared assertion helper, one @Test per bad-input case) close that
    // coverage gap for both bad-input paths: an unknown experience id
    // (resolved async via the AND-4 ?exp= fetch) and an unknown variation
    // id (resolved synchronously against a config-resident experience).

    @Test
    fun `unknown experience id after the exp fetch resumes tracking and persistence`() {
        val sdk = buildSdk("sk-bad-exp-zt")
        sdk.attachTestApiManager(fakeApiManagerReturningEmptyExpFetch())
        val ctx = sdk.createContext("visitor_bad_exp_zt")

        ctx.setPreview(experienceId = "exp-does-not-exist", variationId = "var-z")
        awaitCondition {
            ShadowLog.getLogs().any { it.type == Log.WARN && it.msg.contains("exp-does-not-exist") }
        }

        assertBadPreviewInputResumesTrackingAndPersistence(
            sdk = sdk,
            ctx = ctx,
            visitorId = "visitor_bad_exp_zt",
            experienceKey = "welcome",
            goalKey = "zt-goal",
        )
    }

    @Test
    fun `unknown variation id resumes tracking and persistence`() {
        val sdk = buildSdk("sk-bad-var-zt")
        val ctx = sdk.createContext("visitor_bad_var_zt")

        ctx.setPreview(experienceId = "exp-1", variationId = "var-does-not-exist")

        assertBadPreviewInputResumesTrackingAndPersistence(
            sdk = sdk,
            ctx = ctx,
            visitorId = "visitor_bad_var_zt",
            experienceKey = "welcome",
            goalKey = "zt-goal",
        )
    }

    /**
     * Shared assertion for both bad-input scenarios above: a normal
     * bucketing call followed by a conversion must enqueue a tracking
     * event AND persist the sticky decision — proving
     * [ConvertContext.isPreviewActive] went back to `false` rather than
     * staying stuck on `true` forever (the Finding 1 defect).
     */
    private fun assertBadPreviewInputResumesTrackingAndPersistence(
        sdk: ConvertSDK,
        ctx: ConvertContext,
        visitorId: String,
        experienceKey: String,
        goalKey: String,
    ) {
        // Review R3 (F1) — bad preview input clears previewState, so
        // isPreviewActive() is false again and BUCKETING must fire
        // normally for this decision, exactly as it would with no
        // preview ever having been set.
        val bucketingLatch = CountDownLatch(1)
        sdk.on(SystemEvents.BUCKETING) { bucketingLatch.countDown() }
        val decision = ctx.runExperience(experienceKey)
        assertNotNull("bad preview input must not block normal bucketing", decision)
        assertTrue(
            "BUCKETING must fire normally once bad preview input resumes normal behaviour",
            bucketingLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )

        val conversionLatch = CountDownLatch(1)
        sdk.on(SystemEvents.CONVERSION) { conversionLatch.countDown() }
        ctx.trackConversion(goalKey = goalKey)
        assertTrue(
            "CONVERSION must fire for a normally-dispatched conversion",
            conversionLatch.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )

        assertTrue(
            "bad preview input must NOT permanently suppress the tracking enqueue — the " +
                "queue must be non-empty once the context resumes normal behaviour",
            sdk.apiManager!!.snapshotQueue().isNotEmpty(),
        )
        val store = sdk.dataManager.getStoreData(visitorId)
        assertTrue(
            "bad preview input must NOT permanently suppress sticky-bucketing persistence",
            store.bucketing?.containsKey(experienceKey) == true,
        )
    }

    /**
     * Real [ApiManager] whose `?exp=` (and any other GET) fetch always
     * resolves to an empty config (`{}` decodes to every field `null`) —
     * used to exercise the "experience genuinely unknown after the fetch"
     * bad-input path deterministically.
     */
    private fun fakeApiManagerReturningEmptyExpFetch(): ApiManager {
        val http = object : HttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
            ): HttpClient.HttpResponse = HttpClient.HttpResponse(
                statusCode = 200,
                body = "{}",
                headers = emptyMap(),
            )

            override suspend fun post(
                url: String,
                body: String,
                headers: Map<String, String>,
            ): HttpClient.HttpResponse = HttpClient.HttpResponse(
                statusCode = 200,
                body = "",
                headers = emptyMap(),
            )
        }
        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }
        return ApiManager(
            httpClient = http,
            logger = Logger.NoOp,
            config = ConvertConfig(sdkKey = "sk-bad-exp-fetch"),
            json = json,
        )
    }

    private companion object {
        private const val LARGE_BATCH_SIZE = 100
        private const val LONG_RELEASE_INTERVAL_MS = 30_000L
        private const val LONG_DATA_REFRESH_INTERVAL_MS = 600_000L
        private const val AWAIT_TIMEOUT_MS = 2_000L
        private const val AWAIT_POLL_MS = 10L
        private const val LATCH_TIMEOUT_SECONDS = 2L

        /**
         * Review R3 (F2) — grace window for the "must NOT fire" assertions
         * in the main lifecycle test. Both BUCKETING and CONVERSION are
         * no-ops by design during preview, so there is no positive
         * completion signal to await; this bounded settle gives the
         * fire-and-forget dispatch coroutines ample time to have run if
         * they were (incorrectly) going to fire.
         */
        private const val EVENT_SETTLE_MS = 300L
        private const val FIFTY_FIFTY = 50.0
        private const val FULL_ALLOCATION = 100.0
        private const val GOAL_AMOUNT = 9.99
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
    }
}
