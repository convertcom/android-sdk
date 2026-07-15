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
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
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
 * RED-phase tests for AND-2 (qs-03 mutual-exclusion rule,
 * `bucketed_into_experience_key`) — end-to-end at the
 * [ConvertContext] audience gate.
 *
 * ### Scope
 *
 * AND-1 (already GREEN) gave [RuleManager][com.convert.sdk.core.rules.RuleManager]
 * an optional [BucketedExperienceResolver][com.convert.sdk.core.rules.BucketedExperienceResolver]
 * seam. This file exercises the seam through the REAL production
 * surface — [ConvertContext.runExperience] — which today still calls
 * `sdk.ruleManager.evaluate(audience.rules, context.currentAttributes())`
 * (the 2-arg overload, no resolver) at `ConvertContext.kt:1440`
 * (`passesAudienceGate`). Wiring a real KEY-keyed resolver there is
 * AND-2's GREEN-phase production change — deliberately NOT made in this
 * commit.
 *
 * ### Why these tests currently FAIL (and must)
 *
 * With no resolver threaded, [RuleManager]'s
 * `evaluateBucketedIntoExperienceKey` short-circuits to plain `false`
 * — WITHOUT applying `negated` — for every `bucketed_into_experience_key`
 * leaf (`RuleManager.kt:380-382`). Every audience below carries that
 * leaf negated (`NOT bucketed into exp-a`), so **today** the leaf is
 * always `false` and the audience it lives in never matches, REGARDLESS
 * of whether the visitor actually ran exp-a. Concretely:
 *  - a visitor who never ran exp-a is (wrongly) excluded from exp-b/c/d
 *    today — the assertions expecting a normal bucket (`VAR_B_ID` /
 *    `VAR_C_ID` / `VAR_D_ID`) fail against an actual `null`.
 *  - a visitor who DID run exp-a is (right, but for the wrong reason)
 *    excluded today too — those assertions do not RED on their own, but
 *    the test method as a whole still fails via the sibling assertion
 *    above.
 *
 * AC5 (read-only) is the one exception: because the audience gate
 * already fails closed for everyone today, its "zero writes / zero
 * tracking / no target bucketing" assertions hold trivially both before
 * and after AND-2's GREEN wiring — it is a regression lock, not a RED
 * signal.
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

    /** One experience, one variation, 100% allocation — deterministic bucketing (see class KDoc). */
    private fun oneVariationExperience(
        id: String,
        key: String,
        variationId: String,
        audienceIds: List<String>? = null,
    ): ConfigExperience = ConfigExperience(
        id = id,
        key = key,
        audiences = audienceIds,
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

    /** AC6 ALL semantics: two AND blocks — `plan == premium` AND `NOT bucketed into [targetKey]`. */
    private fun exclusionAllAudience(id: String, targetKey: String): ConfigAudience = ConfigAudience(
        id = id,
        key = id,
        rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[
              {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}]},
              {"OR_WHEN":[{"rule_type":"bucketed_into_experience_key","matching":{"match_type":"equals","negated":true},"value":"$targetKey"}]}
            ]}]}
            """.trimIndent(),
        ),
    )

    /**
     * AC6 ANY semantics: one OR_WHEN block with BOTH leaves — either
     * `plan == premium` OR `NOT bucketed into [targetKey]`.
     */
    private fun exclusionAnyAudience(id: String, targetKey: String): ConfigAudience = ConfigAudience(
        id = id,
        key = id,
        rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"},
              {"rule_type":"bucketed_into_experience_key","matching":{"match_type":"equals","negated":true},"value":"$targetKey"}
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
    fun `AC6 ALL — generic rule AND NOT-bucketed-into-A must both pass`() {
        val config = ConfigResponseData(
            experiences = listOf(
                oneVariationExperience(EXP_A_ID, EXP_A_KEY, VAR_A_ID),
                oneVariationExperience(EXP_C_ID, EXP_C_KEY, VAR_C_ID, audienceIds = listOf(EXCLUDE_A_ALL_AUDIENCE)),
            ),
            audiences = listOf(exclusionAllAudience(EXCLUDE_A_ALL_AUDIENCE, EXP_A_KEY)),
        )
        val sdk = buildSdk(config)

        // Generic passes AND visitor is NOT bucketed into A -> ALL passes -> bucketed.
        val eligible = sdk.createContext("visitor_all_eligible")
        eligible.setAttributes(mapOf("plan" to "premium"))
        assertEquals(
            "generic rule passes and visitor is NOT bucketed into exp-a: ALL must pass",
            VAR_C_ID,
            eligible.runExperience(EXP_C_KEY)?.id,
        )

        // Generic passes but visitor IS bucketed into A -> ALL fails -> excluded.
        val excluded = sdk.createContext("visitor_all_excluded")
        assertEquals(VAR_A_ID, excluded.runExperience(EXP_A_KEY)?.id)
        excluded.setAttributes(mapOf("plan" to "premium"))
        assertNull(
            "generic rule passes but visitor IS bucketed into exp-a: ALL must fail",
            excluded.runExperience(EXP_C_KEY),
        )
    }

    @Test
    fun `AC6 ANY — either the generic rule or NOT-bucketed-into-A satisfies`() {
        val config = ConfigResponseData(
            experiences = listOf(
                oneVariationExperience(EXP_A_ID, EXP_A_KEY, VAR_A_ID),
                oneVariationExperience(EXP_D_ID, EXP_D_KEY, VAR_D_ID, audienceIds = listOf(EXCLUDE_A_ANY_AUDIENCE)),
            ),
            audiences = listOf(exclusionAnyAudience(EXCLUDE_A_ANY_AUDIENCE, EXP_A_KEY)),
        )
        val sdk = buildSdk(config)

        // Generic fails (plan unset) but visitor is NOT bucketed into A ->
        // the bucketed leaf alone satisfies ANY -> bucketed.
        val viaBucketedLeaf = sdk.createContext("visitor_any_via_bucketed")
        assertEquals(
            "generic rule fails but visitor is NOT bucketed into exp-a: ANY must pass via the bucketed leaf",
            VAR_D_ID,
            viaBucketedLeaf.runExperience(EXP_D_KEY)?.id,
        )

        // Generic fails AND visitor IS bucketed into A -> both leaves fail -> excluded.
        val bothFail = sdk.createContext("visitor_any_both_fail")
        assertEquals(VAR_A_ID, bothFail.runExperience(EXP_A_KEY)?.id)
        assertNull(
            "generic rule fails and visitor IS bucketed into exp-a: ANY must fail",
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

        Thread.sleep(EVENT_SETTLE_MS)

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
        private const val EVENT_SETTLE_MS = 300L
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
        private const val EXCLUDE_A_ALL_AUDIENCE = "aud-exclude-a-all"
        private const val EXCLUDE_A_ANY_AUDIENCE = "aud-exclude-a-any"
    }
}
