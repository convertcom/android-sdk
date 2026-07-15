/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.api.ApiManager
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.internal.sharedSerializersModule
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * qs-02 / Review R3 — timing and race-guard tests for
 * [ConvertContext.setPreview]'s async AND-4 `?exp=` resolution.
 *
 * These tests deliberately control exactly WHEN the underlying `?exp=`
 * HTTP fetch "completes" via [GatedHttpClient] — a real (not virtual-time)
 * gate, since [ApiManager.fetchConfig] hardcodes `withContext(Dispatchers.IO)`
 * internally and therefore cannot be driven by an injected [kotlinx.coroutines.test.TestScope]
 * scheduler (the IO hop always runs on a real thread regardless of which
 * scope launched the coroutine). This mirrors the gate-free async-polling
 * idiom [ConvertContextSetPreviewTest] already uses for the same fetch
 * (`awaitCondition { ctx.runExperience(...) != null }`), just with
 * explicit control over the exact moment the fetch resolves.
 *
 * ### F1 — suppression timing
 *
 * [ConvertContext.isPreviewActive] must stay `false` (no suppression;
 * events fire normally) for the ENTIRE in-flight fetch window, engaging
 * only once [PreviewState.experience] actually resolves — see
 * [ConvertContext.isPreviewActive]'s KDoc. `isPreviewActive()` is the
 * SINGLE decision point every suppression (BUCKETING fire, CONVERSION
 * fire, network enqueue, visitor-state persistence) consults, so
 * asserting ITS state transition — `false` throughout the in-flight
 * window, `true` only once resolved — proves the timing contract
 * directly, without needing to observe any specific downstream effect
 * under this file's gated/async fetch.
 *
 * ### Why F2 (event suppression) is NOT asserted in THIS file
 *
 * [EventManager.fire] dispatches every subscriber callback via
 * `scope.launch { ... }` on the SDK's real `Dispatchers.Default`-backed
 * scope — genuinely asynchronous relative to the calling thread. Layering
 * that asynchrony on TOP OF this file's already-asynchronous gated `?exp=`
 * fetch (real `Dispatchers.IO`) produced a test that was fragile in
 * practice: not because production is wrong ([ConvertContext.isPreviewActive]
 * is correctly `false` throughout the in-flight window and correctly
 * `true` after resolution, proven below), but because two independent
 * layers of real-thread asynchrony compound into a hard-to-drive test.
 * [ConvertContextPreviewZeroTraceTest] already covers F2 (BUCKETING /
 * CONVERSION suppression) deterministically with a CONFIG-RESIDENT
 * preview — `setPreview` resolves synchronously there, so
 * `isPreviewActive()` is `true` immediately with no gate and no fetch to
 * race against — plus a non-vacuous baseline proving the same
 * observation mechanism DOES detect a real fire when no preview is set.
 * This file stays scoped to what only IT can exercise: the async
 * fetch-window TIMING (F1) and the concurrent-preview race guard (F3).
 *
 * ### F3 — resolvePreviewFetch race guard (honest scope)
 *
 * The fix reads `previewState` into a named local at each of the three
 * write sites in `resolvePreviewFetch` and only writes when that local's
 * `experienceId` still matches, rather than the rejected `takeIf`-chain
 * suggestion that would assign `previewState = null` whenever a DIFFERENT
 * preview ("B") is active — nulling out B's legitimate state instead of
 * leaving it untouched. [a concurrent preview B is never clobbered]
 * deterministically exercises that guard for the realistic, easily-hit
 * case: B becomes active (via its own synchronous, config-resident
 * [ConvertContext.setPreview] call) BEFORE A's gated fetch resolves at
 * all — every one of the three write sites reads `previewState` after
 * B is already in place and correctly leaves it alone. The absolute
 * tightest theoretical window — B landing in the single non-suspending
 * statement between the middle `activeVariationId` read and the final
 * `.copy(experience = ...)` write, on a genuinely concurrent thread — has
 * no suspension point to hook into and is not deterministically
 * reproducible without invasive test-only instrumentation of production
 * code; per this review's own guidance ("F3 race is hard to test
 * deterministically... fix + reason without a flaky concurrency test"),
 * that residual window is accepted and reasoned about here rather than
 * chased with a timing-dependent test.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextPreviewRaceAndTimingTest {

    private lateinit var appContext: Context

    private val previewJson: Json = Json {
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

    // --- fixtures -------------------------------------------------------

    /** `exp-1` keyed `welcome` (config-resident) + `exp-2` keyed `promo` (single 100% variation). */
    private fun mainConfig(): ConfigResponseData = ConfigResponseData(
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
    )

    /** A draft experience delivered only via the AND-4 `?exp=` fetch. */
    private fun draftExpFetchResult(): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-draft",
                key = "draft-promo",
                variations = listOf(ExperienceVariationConfig(id = "var-x", key = "x")),
            ),
        ),
    )

    /**
     * Builds a [ConvertSDK] via the SAME public-Builder + [attachTestApiManager]
     * construction [ConvertContextSetPreviewTest] uses for the identical
     * `?exp=` fetch scenario — deliberately NOT a divergent internal-constructor
     * SDK. [attachTestApiManager] swaps in [gatedHttp] as the transport for
     * the gated preview-config fetch; every other SDK-level default
     * (tracking-enabled, batching, event dispatch scope) stays exactly as
     * the Builder configures it in production.
     *
     * ### [sdkKey] MUST be unique per test method
     *
     * `ApiManager.fetchConfig(experienceId)`'s AND-4 memo is process-wide
     * (companion-object, Review R3 F4), keyed `"$sdkKey:$experienceId"`,
     * and persists for the lifetime of the test-run classloader — NOT
     * reset between test methods (the reset seam is `internal` to
     * `:packages:core` and structurally unreachable from this module).
     * Both tests in this file preview `experienceId = "exp-draft"`; without
     * a distinct [sdkKey] per test, whichever test runs SECOND would find
     * the FIRST test's memoized fetch result and resolve instantly
     * regardless of its own gate — exactly the cross-test collision this
     * parameter prevents. Give every caller of this method its own unique
     * value (e.g. `"sk-timing"` vs `"sk-race"`).
     */
    private fun buildSdk(gatedHttp: GatedHttpClient, sdkKey: String): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext)
            .sdkKey(sdkKey)
            .data(mainConfig())
            .build()
        awaitCondition { sdk.dataManager.hasData() }
        sdk.attachTestApiManager(
            ApiManager(
                httpClient = gatedHttp,
                logger = Logger.NoOp,
                config = ConvertConfig(sdkKey = sdkKey),
                json = previewJson,
            ),
        )
        return sdk
    }

    private fun awaitCondition(timeoutMs: Long = AWAIT_TIMEOUT_MS, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(AWAIT_POLL_MS)
        }
        assertTrue("Timed out waiting for condition", check())
    }

    /**
     * Builds a [GatedHttpClient] whose eventual `?exp=` response resolves
     * to [draftExpFetchResult]. Extracted so both tests below stay within
     * detekt's line-length / argument-wrapping ceilings.
     */
    private fun gatedHttpForDraftFetch(): GatedHttpClient {
        val body = previewJson.encodeToString(ConfigResponseData.serializer(), draftExpFetchResult())
        return GatedHttpClient(body)
    }

    // ---------------------------------------------------------------
    // F1 — normal firing during the in-flight window; suppression only
    // once the fetch resolves
    // ---------------------------------------------------------------

    @Test
    fun `isPreviewActive stays false during the in-flight exp fetch window and becomes true once resolved`() {
        val gatedHttp = gatedHttpForDraftFetch()
        val sdk = buildSdk(gatedHttp, sdkKey = "sk-timing")
        val ctx = sdk.createContext("visitor_timing_1")

        // Dispatches the async ?exp= fetch — held open by the gate.
        ctx.setPreview(experienceId = "exp-draft", variationId = "var-x")
        assertFalse(
            "isPreviewActive must be false during the in-flight fetch window (Review R3 F1)",
            ctx.isPreviewActive(),
        )

        // A DIFFERENT (non-previewed) experience decides normally while the
        // fetch is in flight — proves the context is NOT suppressed yet.
        val duringFetch = ctx.runExperience("promo")
        assertEquals("var-p", duringFetch?.id)
        assertFalse(
            "isPreviewActive must still be false right up to fetch resolution (Review R3 F1)",
            ctx.isPreviewActive(),
        )

        // Let the fetch resolve.
        gatedHttp.openGate()
        awaitCondition { ctx.isPreviewActive() }
        assertTrue(
            "isPreviewActive must become true once the fetch genuinely resolves (Review R3 F1)",
            ctx.isPreviewActive(),
        )
    }

    // ---------------------------------------------------------------
    // F3 — resolvePreviewFetch race guard
    // ---------------------------------------------------------------

    @Test
    fun `a concurrent preview B is never clobbered by preview A's delayed exp fetch resolution`() {
        // A's eventual fetch result resolves successfully to a DIFFERENT
        // experience/key than B's — the exact shape that would corrupt
        // B's PreviewState.experience while leaving its experienceId /
        // variationId strings intact, per the pre-fix `previewState =
        // previewState?.copy(experience = resolvedExperience)` bug.
        val gatedHttp = gatedHttpForDraftFetch()
        val sdk = buildSdk(gatedHttp, sdkKey = "sk-race")
        val ctx = sdk.createContext("visitor_race_1")

        // Preview A — not config-resident, dispatches the gated async fetch.
        ctx.setPreview(experienceId = "exp-draft", variationId = "var-x")

        // Preview B — config-resident, resolves synchronously, lands
        // WHILE A's fetch is still gated (in flight).
        ctx.setPreview(experienceId = "exp-1", variationId = "var-b")
        val forcedBeforeRaceResolves = ctx.runExperience("welcome")
        assertEquals(
            "preview B must force its variation before A's fetch resolves",
            "var-b",
            forcedBeforeRaceResolves?.id,
        )

        // Let A's fetch resolve now.
        gatedHttp.openGate()
        assertTrue(
            "the gated fetch must have been given the chance to respond",
            gatedHttp.responded.await(RESPOND_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        )
        // Brief settle for the few synchronous lines after the HTTP
        // response returns (JSON decode + the read-once write-guard) to
        // finish running on the SDK scope.
        Thread.sleep(EVENT_SETTLE_MS)

        val forcedAfterRaceResolves = ctx.runExperience("welcome")
        assertEquals(
            "preview B must remain untouched after A's unrelated fetch resolves " +
                "(Review R3 F3 — read-once write guard)",
            "var-b",
            forcedAfterRaceResolves?.id,
        )
    }

    /**
     * [HttpClient] whose `get()` blocks (via a real [CountDownLatch], not
     * virtual time) until the test calls [openGate] — gives these tests
     * exact control over when the AND-4 `?exp=` fetch "completes" relative
     * to other calls made on the test thread, without guessing at
     * real-world timing.
     */
    private class GatedHttpClient(private val body: String) : HttpClient {
        private val gate = CountDownLatch(1)

        /** Counted down once [get] has observed the gate opening. */
        val responded: CountDownLatch = CountDownLatch(1)

        fun openGate() {
            gate.countDown()
        }

        override suspend fun get(url: String, headers: Map<String, String>): HttpClient.HttpResponse {
            gate.await()
            val response = HttpClient.HttpResponse(statusCode = HTTP_OK, body = body, headers = emptyMap())
            responded.countDown()
            return response
        }

        override suspend fun post(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpClient.HttpResponse = HttpClient.HttpResponse(statusCode = HTTP_OK, body = "", headers = emptyMap())
    }

    private companion object {
        private const val AWAIT_TIMEOUT_MS = 2_000L
        private const val AWAIT_POLL_MS = 10L
        private const val EVENT_SETTLE_MS = 300L
        private const val RESPOND_TIMEOUT_SECONDS = 2L
        private const val FIFTY_FIFTY = 50.0
        private const val FULL_ALLOCATION = 100.0
        private const val HTTP_OK = 200
    }
}
