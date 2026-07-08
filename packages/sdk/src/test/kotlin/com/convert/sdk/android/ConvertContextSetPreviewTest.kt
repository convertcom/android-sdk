/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.api.ApiManager
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.internal.sharedSerializersModule
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceStatuses
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.math.BigDecimal

/**
 * Robolectric-backed tests for qs-02 / AND-5 —
 * `ConvertContext.setPreview` wiring, per-context isolation, and
 * inert-on-bad-input.
 *
 * Covers (per the AND-5 brief):
 *  - AC4: a draft experience delivered ONLY via the AND-4 `?exp=` fetch
 *    returns the requested variation from the preview context.
 *  - Precedence (contract §3): preview forcing beats a pre-existing
 *    sticky decision for the target experience.
 *  - Inert-on-bad-input (contract §2): an unknown experience id (even
 *    after the fetch) and an unknown variation id both leave the context
 *    behaving fully normally, with a WARN logged.
 *  - "Other experiences still evaluate normally" on the preview context.
 *  - AC7: a concurrent non-preview context buckets normally.
 *
 * ### Known hash fact reused from [ConvertContextRunExperienceTest]
 *
 * `visitor_abc` + `exp-1` hashes to value 833 → below the 5000 boundary
 * of a 50/50 wheel → `var-a` (`control`). Reused here as an independent
 * ground truth for "this context bucketed normally, not via preview
 * forcing" — no preview-specific hash math is needed.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextSetPreviewTest {

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
        ShadowLog.clear()
    }

    // --- test fixtures ------------------------------------------------

    /**
     * `exp-1` keyed `welcome`, two 50/50 variations — identical shape to
     * [ConvertContextRunExperienceTest]'s `testConfig()` so the
     * `visitor_abc` → `var-a` hash fact holds here too.
     */
    private fun mainConfig(): ConfigResponseData = ConfigResponseData(
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
        ),
    )

    /**
     * Same as [mainConfig] plus a second experience (`exp-2` keyed
     * `promo`) with a SINGLE variation at 100% allocation — deterministic
     * regardless of visitor hash, used to prove a non-previewed
     * experience on the SAME preview context still decides normally.
     */
    private fun mainConfigWithSecondExperience(): ConfigResponseData = ConfigResponseData(
        experiences = mainConfig().experiences.orEmpty() + ConfigExperience(
            id = "exp-2",
            key = "promo",
            variations = listOf(
                ExperienceVariationConfig(
                    id = "var-p",
                    key = "promo-v",
                    trafficAllocation = BigDecimal.valueOf(100.0),
                ),
            ),
        ),
    )

    /** A draft experience that exists ONLY in the `?exp=` fetch response. */
    private fun draftPreviewConfig(): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-draft-9",
                key = "draft-promo",
                status = ExperienceStatuses.DRAFT,
                variations = listOf(
                    ExperienceVariationConfig(id = "var-x", key = "x"),
                    ExperienceVariationConfig(id = "var-y", key = "y"),
                ),
            ),
        ),
    )

    /** An `?exp=` fetch response that does not contain the requested experience. */
    private fun emptyFetchConfig(): ConfigResponseData = ConfigResponseData(experiences = emptyList())

    private fun buildSdk(config: ConfigResponseData): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    /**
     * Builds a real [ApiManager] whose `?exp=` fetch always returns
     * [fetchResult] — round-tripped through [previewJson] so the exact
     * on-wire shape (including `@Contextual` BigDecimal fields) never has
     * to be hand-written.
     */
    private fun fakeApiManagerReturning(fetchResult: ConfigResponseData): ApiManager {
        val body = previewJson.encodeToString(ConfigResponseData.serializer(), fetchResult)
        val http = object : HttpClient {
            override suspend fun get(
                url: String,
                headers: Map<String, String>,
            ): HttpClient.HttpResponse = HttpClient.HttpResponse(
                statusCode = 200,
                body = body,
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
        return ApiManager(
            httpClient = http,
            logger = Logger.NoOp,
            config = ConvertConfig(sdkKey = "sk-preview-test"),
            json = previewJson,
        )
    }

    // --- AC4: forced decision via the AND-4 ?exp= fetch ----------------

    @Test
    fun `setPreview forces the requested variation for a draft experience delivered only via exp fetch`() {
        val sdk = buildSdk(mainConfig())
        sdk.attachTestApiManager(fakeApiManagerReturning(draftPreviewConfig()))
        val ctx = sdk.createContext("visitor_preview_1")

        ctx.setPreview(experienceId = "exp-draft-9", variationId = "var-y")
        awaitCondition { ctx.runExperience("draft-promo") != null }

        val result = ctx.runExperience("draft-promo")

        assertNotNull(result)
        assertEquals("var-y", result?.id)
        assertEquals("y", result?.key)
        assertEquals("exp-draft-9", result?.experienceId)
    }

    // --- Precedence: preview beats a pre-existing sticky decision ------

    @Test
    fun `setPreview beats a pre-existing sticky decision for the target experience`() {
        val sdk = buildSdk(mainConfig())
        val ctx = sdk.createContext("visitor_abc")
        val sticky = ctx.runExperience("welcome")
        assertEquals("var-a", sticky?.id)

        ctx.setPreview(experienceId = "exp-1", variationId = "var-b")
        val forced = ctx.runExperience("welcome")

        assertEquals("var-b", forced?.id)
        assertEquals("treatment", forced?.key)
    }

    // --- Inert on bad input ---------------------------------------------

    @Test
    fun `setPreview is inert when the experience id is unknown even after the exp fetch`() {
        val sdk = buildSdk(mainConfig())
        sdk.attachTestApiManager(fakeApiManagerReturning(emptyFetchConfig()))
        val ctx = sdk.createContext("visitor_abc")

        ctx.setPreview(experienceId = "exp-does-not-exist", variationId = "var-z")
        awaitCondition {
            ShadowLog.getLogs().any { it.type == Log.WARN && it.msg.contains("exp-does-not-exist") }
        }

        val result = ctx.runExperience("welcome")

        assertEquals("var-a", result?.id)
    }

    @Test
    fun `setPreview is inert when the variation id is unknown within the previewed experience`() {
        val sdk = buildSdk(mainConfig())
        val ctx = sdk.createContext("visitor_abc")

        ctx.setPreview(experienceId = "exp-1", variationId = "var-does-not-exist")
        val result = ctx.runExperience("welcome")

        assertEquals("var-a", result?.id)
        val warnLogs = ShadowLog.getLogs().filter { it.type == Log.WARN }
        assertTrue(
            "expected a WARN about the unknown preview variation, got ${warnLogs.map { it.msg }}",
            warnLogs.any { it.msg.contains("var-does-not-exist") },
        )
    }

    // --- Other experiences on the preview context still decide normally -

    @Test
    fun `other experiences on the preview context still evaluate normally`() {
        val sdk = buildSdk(mainConfigWithSecondExperience())
        val ctx = sdk.createContext("visitor_abc")

        ctx.setPreview(experienceId = "exp-1", variationId = "var-b")
        val forced = ctx.runExperience("welcome")
        val untouched = ctx.runExperience("promo")

        assertEquals("var-b", forced?.id)
        assertEquals("var-p", untouched?.id)
    }

    // --- AC7: isolation ---------------------------------------------------

    @Test
    fun `a concurrent non-preview context buckets normally`() {
        val sdk = buildSdk(mainConfig())
        val previewCtx = sdk.createContext("visitor_preview_iso")
        previewCtx.setPreview(experienceId = "exp-1", variationId = "var-b")
        val normalCtx = sdk.createContext("visitor_abc")

        val previewResult = previewCtx.runExperience("welcome")
        val normalResult = normalCtx.runExperience("welcome")

        assertEquals("var-b", previewResult?.id)
        assertEquals("var-a", normalResult?.id)
    }

    // --- helpers ------------------------------------------------------

    private fun awaitCondition(timeoutMs: Long = 1000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for condition", check())
    }
}
