/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.generated.ConfigAudience
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigExperienceSettings
import com.convert.sdk.core.model.generated.ConfigExperienceSettingsMatchingOptions
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.model.generated.GenericListMatchingOptions
import com.convert.sdk.core.model.generated.RuleObjectAudience
import com.convert.sdk.core.rules.rawRuleSerializersModule
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

/**
 * End-to-end tests for AND-2 (qs-03 mutual-exclusion rule,
 * `bucketed_into_experience_key`) at the [ConvertContext] audience
 * gate. GREEN — the production seam is wired.
 *
 * ### Scope
 *
 * AND-1 gave [RuleManager][com.convert.sdk.core.rules.RuleManager]
 * an optional [BucketedExperienceResolver][com.convert.sdk.core.rules.BucketedExperienceResolver]
 * seam. AND-2 wires it at the REAL production surface —
 * [ConvertContext.runExperience]'s `passesAudienceGate` builds a
 * read-only, KEY-keyed [BucketedExperienceResolver] from the
 * already-loaded config and the visitor's stored bucketing map, and
 * passes it as the 3rd argument to `sdk.ruleManager.evaluate(audience.rules,
 * context.currentAttributes(), bucketedResolver)` (`ConvertContext.kt:1456`).
 * The location gate (`passesLocationGate`, `ConvertContext.kt:1508`) is
 * unchanged — it stays on the 2-arg, resolver-free overload and falls
 * closed by construction (`RuleManager.kt:380-382`), the same fail-closed
 * shape every other unresolvable rule element in the location rule-walk
 * gets.
 *
 * ### What these tests verify
 *
 * End-to-end coverage of AC2 (exclusion: a visitor who runs exp-a is
 * excluded from an audience carrying `NOT bucketed_into_experience_key(exp-a)`;
 * a visitor who never ran exp-a buckets into exp-b/c/d normally), AC3
 * (cross-restart persistence via warm `SharedPreferences`), AC4 (empty
 * visitor attributes throughout), and AC6 (a separate generic audience
 * combined with the exclusion audience via `matching_options.audiences:
 * ALL`/`ANY` — the cross-audience axis, JS #416 parity; NOT a rule-tree
 * AND/OR_WHEN combination within one audience, which is covered instead
 * at the [com.convert.sdk.core.rules.RuleManager] unit level by
 * `BucketedIntoExperienceKeyRuleTest`).
 *
 * AC5 (read-only) is target-scoped (exp-a): evaluating the exclusion
 * rule must not bucket, write, or track the TARGET (exp-a) itself. The
 * outer experience (exp-b) bucketing normally for an un-excluded
 * visitor is expected (AC2) and out of scope for these assertions —
 * they do not claim anything about exp-b.
 *
 * ### Android storage-shape reality divergence (id vs. KEY)
 *
 * See `com.convert.sdk.core.rules.BucketedExperienceResolver`'s KDoc and
 * `ai-driven-product-dev/work/2026-07-15-android-sdk-mutual-exclusion/decision-log.md`
 * for the full grounding — not re-derived here. The qs-03 spec's 8-row
 * fixture and normative algorithm key the stored bucketing map by
 * experience **id** (`{"100111":"100901"}`, the JS SDK shape); Android's
 * [com.convert.sdk.core.model.StoreData.bucketing] is keyed by experience
 * **KEY** (`StoreData.kt:19,28`). Every fixture below uses the qs-03
 * fixture's `matched` outcomes unchanged, re-keyed to KEY.
 *
 * ### Determinism (no hash pre-computation needed)
 *
 * Every experience below has exactly ONE active variation at
 * `traffic_allocation = 100.0`. [com.convert.sdk.core.bucketing.BucketingLayoutResolver.buildPackedBuckets]
 * gives that single variation a cumulative boundary spanning the entire
 * `0..10000` traffic space, so any visitor hashes into it — the same
 * "single 100% variation" pattern `ConvertContextPreviewZeroTraceTest`
 * already uses to avoid pre-computing MurmurHash3 boundaries per
 * visitor id.
 */
@RunWith(RobolectricTestRunner::class)
internal class ConvertContextMutualExclusionTest {

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
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    // --- fixtures -----------------------------------------------------------

    private fun decodeAudienceRules(payload: String): RuleObjectAudience =
        ruleJson.decodeFromString(payload)

    /**
     * One experience, one variation, 100% allocation — deterministic bucketing
     * (see class KDoc). [matchingOptions] drives `settings.matching_options.audiences`
     * (AC6's cross-audience ALL/ANY combination axis) — `null` (the default)
     * omits `settings` entirely, matching every non-AC6 fixture below.
     */
    private fun oneVariationExperience(
        id: String,
        key: String,
        variationId: String,
        audienceIds: List<String>? = null,
        matchingOptions: GenericListMatchingOptions? = null,
    ): ConfigExperience = ConfigExperience(
        id = id,
        key = key,
        audiences = audienceIds,
        settings = matchingOptions?.let {
            ConfigExperienceSettings(matchingOptions = ConfigExperienceSettingsMatchingOptions(audiences = it))
        },
        variations = listOf(
            ExperienceVariationConfig(
                id = variationId,
                key = "var",
                trafficAllocation = BigDecimal.valueOf(HUNDRED_PERCENT),
            ),
        ),
    )

    /** Exclusion-only audience: matches iff the visitor is NOT bucketed into [targetKey]. */
    private fun exclusionAudience(id: String, targetKey: String): ConfigAudience = ConfigAudience(
        id = id,
        key = id,
        rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"bucketed_into_experience_key","matching":{"match_type":"equals","negated":true},"value":"$targetKey"}
            ]}]}]}
            """.trimIndent(),
        ),
    )

    /**
     * AC6 cross-audience axis: a dedicated single-leaf generic audience
     * (`plan == premium`) held SEPARATE from the exclusion audience —
     * mirrors JS #416's `makeGenericAudience()`. AC6's ALL/ANY combination
     * is driven by `matching_options.audiences` combining THIS audience
     * with [exclusionAudience], not by a rule-tree AND/OR_WHEN inside one
     * audience (that in-audience combination is already covered by
     * `BucketedIntoExperienceKeyRuleTest`'s "combination under ALL/ANY"
     * cases at the [com.convert.sdk.core.rules.RuleManager] unit level).
     */
    private fun genericAudience(id: String): ConfigAudience = ConfigAudience(
        id = id,
        key = id,
        rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_text_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        ),
    )

    private fun buildSdk(config: ConfigResponseData, context: Context = appContext): ConvertSDK {
        val sdk = ConvertSDK.builder(context).data(config).build()
        awaitCondition(timeoutMs = 2_000L) { sdk.dataManager.hasData() }
        return sdk
    }

    // --- AC2: end-to-end exclusion -------------------------------------------

    @Test
    fun `AC2 visitor bucketed into A is excluded from B, a visitor who never ran A buckets normally`() {
        val config = ConfigResponseData(
            experiences = listOf(
                oneVariationExperience(EXP_A_ID, EXP_A_KEY, VAR_A_ID),
                oneVariationExperience(EXP_B_ID, EXP_B_KEY, VAR_B_ID, audienceIds = listOf(EXCLUDE_A_AUDIENCE)),
            ),
            audiences = listOf(exclusionAudience(EXCLUDE_A_AUDIENCE, EXP_A_KEY)),
        )
        val sdk = buildSdk(config)

        val excludedVisitor = sdk.createContext("visitor_ran_a")
        assertEquals(VAR_A_ID, excludedVisitor.runExperience(EXP_A_KEY)?.id)
        assertNull(
            "visitor already bucketed into exp-a must be excluded from exp-b",
            excludedVisitor.runExperience(EXP_B_KEY),
        )

        val controlVisitor = sdk.createContext("visitor_never_ran_a")
        assertEquals(
            "a visitor who never ran exp-a must bucket into exp-b normally",
            VAR_B_ID,
            controlVisitor.runExperience(EXP_B_KEY)?.id,
        )
    }

    // --- AC3: cross-restart persistence (row 8, end-to-end) ------------------

    @Test
    fun `AC3 persisted bucketing decision survives SDK re-initialization and still excludes from B`() {
        val config = ConfigResponseData(
            experiences = listOf(
                oneVariationExperience(EXP_A_ID, EXP_A_KEY, VAR_A_ID),
                oneVariationExperience(EXP_B_ID, EXP_B_KEY, VAR_B_ID, audienceIds = listOf(EXCLUDE_A_AUDIENCE)),
            ),
            audiences = listOf(exclusionAudience(EXCLUDE_A_AUDIENCE, EXP_A_KEY)),
        )

        // First SDK instance persists A's decision into warm SharedPreferences.
        val sdk1 = buildSdk(config)
        val ctx1 = sdk1.createContext("visitor_relaunch")
        assertEquals(VAR_A_ID, ctx1.runExperience(EXP_A_KEY)?.id)
        assertEquals(
            "sanity: the persisted map is KEY-keyed (M1), not id-keyed",
            VAR_A_ID,
            sdk1.dataManager.getStoreData("visitor_relaunch").bucketing?.get(EXP_A_KEY),
        )

        // "App relaunch" — a FRESH ConvertSDK over the SAME appContext / SAME
        // SharedPreferences file (no clear() call between sdk1 and sdk2).
        val sdk2 = buildSdk(config)

        val relaunchedCtx = sdk2.createContext("visitor_relaunch")
        assertNull(
            "the re-hydrated persisted map must still exclude the visitor from B after relaunch",
            relaunchedCtx.runExperience(EXP_B_KEY),
        )

        // Control on the SAME fresh SDK instance: a visitor with NO persisted
        // A decision buckets into B normally — proves the exclusion above is
        // driven by the re-hydrated map, not a blanket gate failure.
        val controlCtx = sdk2.createContext("visitor_never_ran_a_relaunch")
        assertEquals(
            "control visitor with no persisted exp-a decision must bucket into B normally",
            VAR_B_ID,
            controlCtx.runExperience(EXP_B_KEY)?.id,
        )
    }

    // --- AC4: zero new inputs — AC2 holds with empty visitor attributes ------

    @Test
    fun `AC4 exclusion holds with empty visitor attributes throughout`() {
        val config = ConfigResponseData(
            experiences = listOf(
                oneVariationExperience(EXP_A_ID, EXP_A_KEY, VAR_A_ID),
                oneVariationExperience(EXP_B_ID, EXP_B_KEY, VAR_B_ID, audienceIds = listOf(EXCLUDE_A_AUDIENCE)),
            ),
            audiences = listOf(exclusionAudience(EXCLUDE_A_AUDIENCE, EXP_A_KEY)),
        )
        val sdk = buildSdk(config)

        // No setAttributes call anywhere below — empty attribute map throughout.
        val excludedVisitor = sdk.createContext("visitor_empty_attrs")
        assertEquals(VAR_A_ID, excludedVisitor.runExperience(EXP_A_KEY)?.id)
        assertNull(excludedVisitor.runExperience(EXP_B_KEY))

        val controlVisitor = sdk.createContext("visitor_empty_attrs_control")
        assertEquals(VAR_B_ID, controlVisitor.runExperience(EXP_B_KEY)?.id)
    }

    // --- AC6: combination semantics at the ConvertContext gate ---------------

    @Test
    fun `AC6 ALL — generic audience AND exclusion audience must both match`() {
        val config = ConfigResponseData(
            experiences = listOf(
                oneVariationExperience(EXP_A_ID, EXP_A_KEY, VAR_A_ID),
                oneVariationExperience(
                    EXP_C_ID,
                    EXP_C_KEY,
                    VAR_C_ID,
                    audienceIds = listOf(GENERIC_AUDIENCE_ID, EXCLUDE_A_AUDIENCE),
                    matchingOptions = GenericListMatchingOptions.ALL,
                ),
            ),
            audiences = listOf(
                genericAudience(GENERIC_AUDIENCE_ID),
                exclusionAudience(EXCLUDE_A_AUDIENCE, EXP_A_KEY),
            ),
        )
        val sdk = buildSdk(config)

        // Generic audience matches AND visitor is NOT bucketed into A -> ALL passes -> bucketed.
        val eligible = sdk.createContext("visitor_all_eligible")
        eligible.setAttributes(mapOf("plan" to "premium"))
        assertEquals(
            "generic audience matches and visitor is NOT bucketed into exp-a: ALL must pass",
            VAR_C_ID,
            eligible.runExperience(EXP_C_KEY)?.id,
        )

        // Generic audience matches but visitor IS bucketed into A -> ALL fails -> excluded.
        val excluded = sdk.createContext("visitor_all_excluded")
        assertEquals(VAR_A_ID, excluded.runExperience(EXP_A_KEY)?.id)
        excluded.setAttributes(mapOf("plan" to "premium"))
        assertNull(
            "generic audience matches but visitor IS bucketed into exp-a: ALL must fail",
            excluded.runExperience(EXP_C_KEY),
        )
    }

    @Test
    fun `AC6 ANY — either the generic audience or the exclusion audience satisfies`() {
        val config = ConfigResponseData(
            experiences = listOf(
                oneVariationExperience(EXP_A_ID, EXP_A_KEY, VAR_A_ID),
                oneVariationExperience(
                    EXP_D_ID,
                    EXP_D_KEY,
                    VAR_D_ID,
                    audienceIds = listOf(GENERIC_AUDIENCE_ID, EXCLUDE_A_AUDIENCE),
                    matchingOptions = GenericListMatchingOptions.ANY,
                ),
            ),
            audiences = listOf(
                genericAudience(GENERIC_AUDIENCE_ID),
                exclusionAudience(EXCLUDE_A_AUDIENCE, EXP_A_KEY),
            ),
        )
        val sdk = buildSdk(config)

        // Generic audience fails (plan unset) but visitor is NOT bucketed into A ->
        // the exclusion audience alone satisfies ANY -> bucketed.
        val viaExclusionAudience = sdk.createContext("visitor_any_via_exclusion")
        assertEquals(
            "generic audience fails but visitor is NOT bucketed into exp-a: ANY must pass via " +
                "the exclusion audience",
            VAR_D_ID,
            viaExclusionAudience.runExperience(EXP_D_KEY)?.id,
        )

        // Generic audience fails AND visitor IS bucketed into A -> both audiences fail -> excluded.
        val bothFail = sdk.createContext("visitor_any_both_fail")
        assertEquals(VAR_A_ID, bothFail.runExperience(EXP_A_KEY)?.id)
        assertNull(
            "generic audience fails and visitor IS bucketed into exp-a: ANY must fail",
            bothFail.runExperience(EXP_D_KEY),
        )
    }

    // --- AC5: read-only — zero writes, zero tracking, no target bucketing ----

    @Test
    fun `AC5 evaluating the exclusion rule never writes, tracks, or buckets the target`() {
        val putCalls = mutableListOf<Pair<String, String>>()
        val recordingContext = RecordingContext(appContext, putCalls)
        val config = ConfigResponseData(
            experiences = listOf(
                oneVariationExperience(EXP_A_ID, EXP_A_KEY, VAR_A_ID),
                oneVariationExperience(EXP_B_ID, EXP_B_KEY, VAR_B_ID, audienceIds = listOf(EXCLUDE_A_AUDIENCE)),
            ),
            audiences = listOf(exclusionAudience(EXCLUDE_A_AUDIENCE, EXP_A_KEY)),
        )
        val sdk = buildSdk(config, context = recordingContext)
        val recordingApi = ConvertContextRunExperienceTest.RecordingApiManager()
        sdk.attachTestApiManager(recordingApi)
        var bucketingFired = false
        sdk.on(SystemEvents.BUCKETING) { data -> if (data["experienceKey"] == EXP_A_KEY) bucketingFired = true }

        val visitorId = "visitor_read_only"
        val ctx = sdk.createContext(visitorId)

        // Evaluate the exclusion rule WITHOUT ever running exp-a first.
        ctx.runExperience(EXP_B_KEY)

        awaitCondition { recordingApi.enqueueBucketingCalls.any { it.experienceId == EXP_B_ID } }

        assertTrue(
            "evaluating the exclusion rule must never bucket the target experience (exp-a)",
            sdk.dataManager.getStoreData(visitorId).bucketing?.containsKey(EXP_A_KEY) != true,
        )
        assertTrue(
            "evaluating the exclusion rule must never enqueue a tracking event for exp-a",
            recordingApi.enqueueBucketingCalls.none { it.experienceId == EXP_A_ID },
        )
        assertTrue(
            "evaluating the exclusion rule must never fire SystemEvents.BUCKETING for exp-a",
            !bucketingFired,
        )
        assertTrue(
            "evaluating the exclusion rule must never persist a bucketing entry for the " +
                "target (exp-a) in any SharedPreferences write (the getStoreData lazy READ is " +
                "allowed; exp-b's OWN normal bucketing write, since this visitor is correctly " +
                "NOT excluded, is expected and out of scope for this assertion)",
            putCalls.none { it.second.contains(EXP_A_KEY) },
        )
    }

    // --- AC5 storage spy ------------------------------------------------------

    /**
     * [ContextWrapper] that intercepts [getSharedPreferences] to return a
     * write-recording [SharedPreferences] — the AC5 storage spy.
     *
     * Overrides [getApplicationContext] to return `this`: [ConvertSDK.builder]
     * normalizes its argument via `context.applicationContext`
     * (`ConvertSDK.kt:1037`), and the default [ContextWrapper.getApplicationContext]
     * delegates to the wrapped base's OWN applicationContext — which, for a
     * real Robolectric [android.app.Application], returns itself and would
     * silently discard this wrapper before `Builder.build()` ever calls
     * [getSharedPreferences].
     */
    private class RecordingContext(
        base: Context,
        private val putCalls: MutableList<Pair<String, String>>,
    ) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this

        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            RecordingSharedPreferences(super.getSharedPreferences(name, mode), putCalls)
    }

    /** Delegates every read to [real]; [edit] returns a [RecordingEditor]. */
    private class RecordingSharedPreferences(
        private val real: SharedPreferences,
        private val putCalls: MutableList<Pair<String, String>>,
    ) : SharedPreferences by real {
        override fun edit(): SharedPreferences.Editor = RecordingEditor(real.edit(), putCalls)
    }

    /** Records every [putString] call before delegating to [real]. */
    private class RecordingEditor(
        private val real: SharedPreferences.Editor,
        private val putCalls: MutableList<Pair<String, String>>,
    ) : SharedPreferences.Editor by real {
        override fun putString(key: String, value: String?): SharedPreferences.Editor {
            if (value != null) putCalls += key to value
            real.putString(key, value)
            return this
        }

        override fun apply() = real.apply()

        override fun commit(): Boolean = real.commit()
    }

    /**
     * Polls the supplied condition for up to [timeoutMs] at 10ms intervals.
     * Mirrors the identical helper in [ConvertContextAudienceTest].
     */
    private fun awaitCondition(timeoutMs: Long = 1_000L, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && !check()) {
            Thread.sleep(10)
        }
        assertTrue("Timed out waiting for condition", check())
    }

    private companion object {
        private const val PREFS_NAME = "com.convert.sdk.visitor"
        private const val HUNDRED_PERCENT = 100.0

        private const val EXP_A_ID = "100111"
        private const val EXP_A_KEY = "exp-a"
        private const val VAR_A_ID = "100901"

        private const val EXP_B_ID = "100222"
        private const val EXP_B_KEY = "exp-b"
        private const val VAR_B_ID = "100902"

        private const val EXP_C_ID = "100333"
        private const val EXP_C_KEY = "exp-c"
        private const val VAR_C_ID = "100903"

        private const val EXP_D_ID = "100444"
        private const val EXP_D_KEY = "exp-d"
        private const val VAR_D_ID = "100904"

        private const val EXCLUDE_A_AUDIENCE = "aud-exclude-a"
        private const val GENERIC_AUDIENCE_ID = "aud-generic-plan-premium"
    }
}
