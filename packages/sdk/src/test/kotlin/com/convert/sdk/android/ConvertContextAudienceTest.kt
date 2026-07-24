/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.config.RulesConfig
import com.convert.sdk.core.model.generated.ConfigAudience
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigExperienceSettings
import com.convert.sdk.core.model.generated.ConfigExperienceSettingsMatchingOptions
import com.convert.sdk.core.model.generated.ConfigLocation
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.model.generated.GenericListMatchingOptions
import com.convert.sdk.core.model.generated.RuleObject
import com.convert.sdk.core.model.generated.RuleObjectAudience
import com.convert.sdk.core.rules.rawRuleSerializersModule
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * Robolectric-backed tests for Story 3.4 AC-5 / AC-6:
 * `ConvertContext.runExperience` consulting audience and location rules
 * before bucketing.
 *
 * ### Test strategy
 *
 * Each test constructs a [ConfigResponseData] with one experience and
 * the appropriate audience / location tie-in, then calls
 * [ConvertContext.setAttributes] / [ConvertContext.setLocationProperties]
 * to drive the rule evaluation path. The bucketing engine and sticky
 * logic are unchanged from Story 3.2 — the tests here only verify the
 * audience/location gate, not the post-gate code path.
 *
 * ### Rule JSON payloads
 *
 * The tests decode rule trees via [ruleJson] (which registers the
 * [rawRuleSerializersModule] exactly like production) rather than
 * constructing typed `RuleObjectAudience` instances. This exercises the
 * same raw-leaf path used in production config fetches.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextAudienceTest {

    private lateinit var appContext: Context

    /** JSON codec with the polymorphic rule-element serializers — matches production wiring. */
    private val ruleJson: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = rawRuleSerializersModule
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

    // --- fixtures ---------------------------------------------------------

    private fun decodeAudienceRules(payload: String): RuleObjectAudience =
        ruleJson.decodeFromString(payload)

    private fun decodeLocationRules(payload: String): RuleObject =
        ruleJson.decodeFromString(payload)

    /** Audience matching `plan == premium`. */
    private val planPremiumAudience = ConfigAudience(
        id = "aud-premium",
        key = "premium_users",
        rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
                {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        ),
    )

    /**
     * Second audience matching `tier == vip` — keyed on a DIFFERENT
     * attribute than [planPremiumAudience] so both can match the SAME
     * visitor simultaneously (needed for the ALL-combination positive-path
     * tests below; two audiences both keyed on `plan` can never both
     * match one visitor's single `plan` value).
     */
    private val tierVipAudience = ConfigAudience(
        id = "aud-vip",
        rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
                {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"tier","value":"vip"}
            ]}]}]}
            """.trimIndent(),
        ),
    )

    /** Location matching `country == us`. */
    private val usLocation = ConfigLocation(
        id = "loc-us",
        key = "us_visitors",
        rules = decodeLocationRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
                {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"country","value":"us"}
            ]}]}]}
            """.trimIndent(),
        ),
    )

    /**
     * Builds a one-experience config. By default, the experience has no
     * audiences or locations — callers override via [audiences] / [locations]
     * to opt into the gated paths. [audienceMatchingOptions] drives
     * `settings.matching_options.audiences` (Finding A / JS parity
     * `data-manager.ts:419-428`) — `null` (the default) omits `settings`
     * entirely, matching every pre-existing fixture below.
     */
    private fun testConfig(
        audienceIds: List<String>? = null,
        locationIds: List<String>? = null,
        allAudiences: List<ConfigAudience>? = null,
        allLocations: List<ConfigLocation>? = null,
        audienceMatchingOptions: GenericListMatchingOptions? = null,
    ): ConfigResponseData = ConfigResponseData(
        experiences = listOf(
            ConfigExperience(
                id = "exp-1",
                key = "welcome",
                audiences = audienceIds,
                locations = locationIds,
                settings = audienceMatchingOptions?.let {
                    ConfigExperienceSettings(
                        matchingOptions = ConfigExperienceSettingsMatchingOptions(audiences = it),
                    )
                },
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
        audiences = allAudiences,
        locations = allLocations,
    )

    private fun buildSdk(config: ConfigResponseData): ConvertSDK {
        val sdk = ConvertSDK.builder(appContext).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC-5: Audience rules ---------------------------------------------

    @Test
    fun `runExperience returns null when audience rules fail`() {
        val config = testConfig(
            audienceIds = listOf("aud-premium"),
            allAudiences = listOf(planPremiumAudience),
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_fail_aud")
        ctx.setAttributes(mapOf("plan" to "free"))

        val result = ctx.runExperience("welcome")

        assertNull(result)
    }

    @Test
    fun `runExperience proceeds when audience rules pass`() {
        val config = testConfig(
            audienceIds = listOf("aud-premium"),
            allAudiences = listOf(planPremiumAudience),
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_pass_aud")
        ctx.setAttributes(mapOf("plan" to "premium"))

        val result = ctx.runExperience("welcome")

        assertNotNull(result)
        assertEquals("welcome", result?.experienceKey)
    }

    @Test
    fun `runExperience with no audiences on experience skips audience gate`() {
        val config = testConfig() // no audienceIds
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_no_aud")

        val result = ctx.runExperience("welcome")

        assertNotNull(result)
    }

    @Test
    fun `runExperience with OR-of-audiences passes when any audience matches`() {
        val goldAudience = ConfigAudience(
            id = "aud-gold",
            rules = decodeAudienceRules(
                """
                {"OR":[{"AND":[{"OR_WHEN":[
                    {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"gold"}
                ]}]}]}
                """.trimIndent(),
            ),
        )
        val config = testConfig(
            audienceIds = listOf("aud-premium", "aud-gold"),
            allAudiences = listOf(planPremiumAudience, goldAudience),
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_gold")
        ctx.setAttributes(mapOf("plan" to "gold"))

        val result = ctx.runExperience("welcome")

        // Gold visitor passes via second audience even though first fails
        assertNotNull(result)
    }

    // --- Finding A (PR #53 review): `matching_options.audiences` ALL/ANY --

    @Test
    fun `runExperience with two audiences under ALL passes when both match`() {
        val config = testConfig(
            audienceIds = listOf("aud-premium", "aud-vip"),
            allAudiences = listOf(planPremiumAudience, tierVipAudience),
            audienceMatchingOptions = GenericListMatchingOptions.ALL,
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_all_both_match")
        ctx.setAttributes(mapOf("plan" to "premium", "tier" to "vip"))

        val result = ctx.runExperience("welcome")

        assertNotNull("both audiences match under ALL: must pass", result)
    }

    @Test
    fun `runExperience with two audiences under ALL fails when only one matches (Finding A regression guard)`() {
        val config = testConfig(
            audienceIds = listOf("aud-premium", "aud-vip"),
            allAudiences = listOf(planPremiumAudience, tierVipAudience),
            audienceMatchingOptions = GenericListMatchingOptions.ALL,
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_all_one_match")
        ctx.setAttributes(mapOf("plan" to "premium")) // "tier" unset -> aud-vip fails

        val result = ctx.runExperience("welcome")

        // Pre-fix `audienceIds.any {}` incorrectly passed here (fail-open) since one
        // of the two audiences matched — ALL must require BOTH.
        assertNull(
            "only one of two audiences matching must NOT pass under ALL",
            result,
        )
    }

    @Test
    fun `runExperience with two audiences under ANY passes when either matches`() {
        val config = testConfig(
            audienceIds = listOf("aud-premium", "aud-vip"),
            allAudiences = listOf(planPremiumAudience, tierVipAudience),
            audienceMatchingOptions = GenericListMatchingOptions.ANY,
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_any_one_match")
        ctx.setAttributes(mapOf("plan" to "premium")) // "tier" unset, but ANY only needs one

        val result = ctx.runExperience("welcome")

        assertNotNull("one of two audiences matching is enough under ANY", result)
    }

    @Test
    fun `runExperience with a single audience is unaffected by ALL vs ANY`() {
        val configAll = testConfig(
            audienceIds = listOf("aud-premium"),
            allAudiences = listOf(planPremiumAudience),
            audienceMatchingOptions = GenericListMatchingOptions.ALL,
        )
        val sdkAll = buildSdk(configAll)
        val ctxAll = sdkAll.createContext("visitor_single_all")
        ctxAll.setAttributes(mapOf("plan" to "premium"))
        assertNotNull("single matching audience must pass under ALL", ctxAll.runExperience("welcome"))

        val configAny = testConfig(
            audienceIds = listOf("aud-premium"),
            allAudiences = listOf(planPremiumAudience),
            audienceMatchingOptions = GenericListMatchingOptions.ANY,
        )
        val sdkAny = buildSdk(configAny)
        val ctxAny = sdkAny.createContext("visitor_single_any")
        ctxAny.setAttributes(mapOf("plan" to "premium"))
        assertNotNull("single matching audience must pass under ANY", ctxAny.runExperience("welcome"))
    }

    @Test
    fun `runExperience passes unrestricted when every audience id is dangling (JS parity)`() {
        // Experience references only an audience id absent from the config's
        // audiences list. JS SDK (data-manager.ts getItemsByIds) drops
        // unresolved ids BEFORE combining, so an empty resolved set means
        // "no restrictions" — same as an empty `experience.audiences` list.
        val config = testConfig(
            audienceIds = listOf("aud-ghost"),
            allAudiences = listOf(planPremiumAudience),
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_ghost_aud")
        ctx.setAttributes(mapOf("plan" to "premium"))

        val result = ctx.runExperience("welcome")

        assertNotNull("all-dangling audience ids must not zero the gate", result)
    }

    @Test
    fun `runExperience under ALL passes when the matching audience is present but a co-listed id is dangling`() {
        // FIX A regression guard: one present+matching audience plus one
        // dangling id must still serve under `ALL` — the dangling id is
        // dropped before the ALL/ANY combination, not counted as a hard
        // failure that zeroes the gate.
        val config = testConfig(
            audienceIds = listOf("aud-premium", "aud-ghost"),
            allAudiences = listOf(planPremiumAudience),
            audienceMatchingOptions = GenericListMatchingOptions.ALL,
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_all_with_dangling")
        ctx.setAttributes(mapOf("plan" to "premium"))

        val result = ctx.runExperience("welcome")

        assertNotNull("dangling id dropped, remaining resolved audience matches under ALL", result)
    }

    @Test
    fun `runExperience under ALL still fails when the only present audience does not match`() {
        // Confirms the FIX A rewrite didn't fail-open the ordinary case:
        // a real, resolved audience that fails its rules still fails the
        // gate under ALL (no dangling id involved here).
        val config = testConfig(
            audienceIds = listOf("aud-premium"),
            allAudiences = listOf(planPremiumAudience),
            audienceMatchingOptions = GenericListMatchingOptions.ALL,
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_all_present_no_match")
        ctx.setAttributes(mapOf("plan" to "free"))

        val result = ctx.runExperience("welcome")

        assertNull("resolved audience present but non-matching must still fail under ALL", result)
    }

    // --- AC-6: Location rules ---------------------------------------------

    @Test
    fun `runExperience returns null when location rules set but no location properties`() {
        val config = testConfig(
            locationIds = listOf("loc-us"),
            allLocations = listOf(usLocation),
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_no_loc_props")
        // Intentionally no setLocationProperties call

        val result = ctx.runExperience("welcome")

        assertNull(result)
    }

    @Test
    fun `runExperience returns null when location rules set but no location match`() {
        val config = testConfig(
            locationIds = listOf("loc-us"),
            allLocations = listOf(usLocation),
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_wrong_country")
        ctx.setLocationProperties(mapOf("country" to "de"))

        val result = ctx.runExperience("welcome")

        assertNull(result)
    }

    @Test
    fun `runExperience proceeds when location rule matches`() {
        val config = testConfig(
            locationIds = listOf("loc-us"),
            allLocations = listOf(usLocation),
        )
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_us")
        ctx.setLocationProperties(mapOf("country" to "us"))

        val result = ctx.runExperience("welcome")

        assertNotNull(result)
    }

    @Test
    fun `runExperience with no locations on experience skips location gate`() {
        val config = testConfig() // no locationIds
        val sdk = buildSdk(config)
        val ctx = sdk.createContext("visitor_no_loc")

        val result = ctx.runExperience("welcome")

        assertNotNull(result)
    }

    // --- Combined audience + location gates -------------------------------

    @Test
    fun `runExperience requires both audience and location to pass`() {
        val config = testConfig(
            audienceIds = listOf("aud-premium"),
            locationIds = listOf("loc-us"),
            allAudiences = listOf(planPremiumAudience),
            allLocations = listOf(usLocation),
        )
        val sdk = buildSdk(config)

        // Fail audience, pass location → null
        val ctxFailAud = sdk.createContext("visitor_fail_aud")
        ctxFailAud.setAttributes(mapOf("plan" to "free"))
        ctxFailAud.setLocationProperties(mapOf("country" to "us"))
        assertNull(ctxFailAud.runExperience("welcome"))

        // Pass audience, fail location → null
        val ctxFailLoc = sdk.createContext("visitor_fail_loc")
        ctxFailLoc.setAttributes(mapOf("plan" to "premium"))
        ctxFailLoc.setLocationProperties(mapOf("country" to "de"))
        assertNull(ctxFailLoc.runExperience("welcome"))

        // Pass both → variation
        val ctxPass = sdk.createContext("visitor_pass_both")
        ctxPass.setAttributes(mapOf("plan" to "premium"))
        ctxPass.setLocationProperties(mapOf("country" to "us"))
        assertNotNull(ctxPass.runExperience("welcome"))
    }

    // --- Rules with keysCaseSensitive config knob ------------------------

    @Test
    fun `runExperience rules keys_case_sensitive false coalesces to true (JS SDK quirk - F-108)`() {
        // F-108: JS SDK rule-manager.ts:57-58 does
        // `config?.rules?.keys_case_sensitive || DEFAULT_KEYS_CASE_SENSITIVE`
        // which coerces any falsy value (including explicit `false`) back
        // to `true`. The Android RuleManager mirrors this quirk verbatim
        // for cross-SDK parity. Therefore passing `rulesKeysCaseSensitive(false)`
        // through the public builder has NO effect — the lookup remains
        // case-sensitive, "PLAN" (attr) ≠ "plan" (rule key), audience
        // fails, runExperience returns null.
        //
        // The case-insensitive code path itself is exercised by
        // RuleManagerTest's `case insensitive: equals with
        // keys_case_sensitive injected false (bypassing coalesce)` test
        // via the internal `keysCaseSensitiveOverride` seam.
        val config = testConfig(
            audienceIds = listOf("aud-premium"),
            allAudiences = listOf(planPremiumAudience),
        )
        val sdk = ConvertSDK.builder(appContext)
            .data(config)
            .rulesKeysCaseSensitive(false)
            .build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        val ctx = sdk.createContext("visitor_ci")
        ctx.setAttributes(mapOf("PLAN" to "premium"))

        val result = ctx.runExperience("welcome")

        // Quirk: false → true → case-sensitive → no audience match → null
        assertNull(result)
    }

    @Suppress("unused")
    private val unusedRulesImport: RulesConfig? = null

    /**
     * Polls the supplied condition for up to [timeoutMs] at 10ms
     * intervals. Mirrors the identical helper in
     * [ConvertContextRunExperienceTest] — the SDK's Story 2.4 scope
     * wiring means `builder(...).data(config).build()` reports
     * `hasData == false` for a brief window after `build()` returns, so
     * every test waits for the seed coroutine to finish before calling
     * into `runExperience`.
     */
    private fun awaitCondition(timeoutMs: Long = 1_000L, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for condition", check())
    }
}
