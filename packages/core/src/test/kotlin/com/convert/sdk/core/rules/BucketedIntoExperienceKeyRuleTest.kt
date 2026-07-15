/*
 * Convert Android SDK — core/rules tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.rules

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.generated.RuleObjectAudience
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * RED-phase tests for AND-1 (qs-03 mutual-exclusion rule,
 * `bucketed_into_experience_key`).
 *
 * ### Android storage-shape reality divergence (see decision-log)
 *
 * The qs-03 spec's normative resolution algorithm and its 8-row fixture are
 * written against **id-keyed** stored bucketing (`{"100111":"100901"}` —
 * the JS SDK reference shape). Android's [com.convert.sdk.core.model.StoreData.bucketing]
 * is documented and implemented as **experience-KEY-keyed**
 * (`StoreData.kt:19,28`; read site `ConvertContext.kt:369`). This divergence
 * is intentional and is logged in full at
 * `ai-driven-product-dev/work/2026-07-15-android-sdk-mutual-exclusion/decision-log.md`
 * — it is NOT re-derived here. Every row below re-keys the spec's stored-map
 * literals to experience KEY; every expected `matched` value is UNCHANGED
 * from the spec table.
 *
 * ### The seam under test (does not exist yet — GREEN phase creates it)
 *
 * [RuleManager.evaluate] gains an optional third parameter,
 * `resolver: BucketedExperienceResolver?`, threaded through the OR/AND/OR_WHEN
 * walk so the new `bucketed_into_experience_key` leaf is resolved in place
 * (required for AC6 nested ALL/ANY). [BucketedExperienceResolver.isBucketed]
 * returns three logical states collapsed into a nullable `Boolean`:
 *
 *  - `null` — the target experience KEY is unknown (not in the served
 *    config) → the branch computes `bucketedRaw = false` AND logs a WARN
 *    naming the key (AC8, rows 6/7).
 *  - `false` — the target experience is known but the visitor's bucketing
 *    map has no entry for it → `bucketedRaw = false`, no WARN (rows 1/5).
 *  - `true` — the target experience is known and bucketed → `bucketedRaw = true`,
 *    no WARN (rows 3/4/8).
 *
 * The new branch computes `matched = if (negated) !bucketedRaw else bucketedRaw`
 * itself (M2) — this is NOT the generic `Comparisons.apply` negation path,
 * and it must be dispatched BEFORE the generic `match_type` dispatch so a
 * rule element carrying `rule_type: "bucketed_into_experience_key"` never
 * falls into the generic comparator (which has no concept of the resolver).
 * A `resolver == null` call site (location/segment trees that don't thread
 * visitor bucketing state) falls closed to plain `false` WITHOUT applying
 * negation — identical fail-closed shape to every other unresolvable rule
 * element in this file today.
 */
internal class BucketedIntoExperienceKeyRuleTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = rawRuleSerializersModule
    }

    private fun decodeAudienceRules(payload: String): RuleObjectAudience =
        json.decodeFromString(payload)

    private fun managerWith(logger: Logger = Logger.NoOp): RuleManager =
        RuleManager(config = ConvertConfig(), logger = logger)

    /** Fake resolver returning a fixed [result] regardless of the queried key. */
    private fun fakeResolver(result: Boolean?): BucketedExperienceResolver =
        BucketedExperienceResolver { _ -> result }

    /** Resolver that fails the test if it is ever invoked — proves a code path never reaches it. */
    private val throwIfCalledResolver = BucketedExperienceResolver { key ->
        throw AssertionError("resolver must not have been called for key=\"$key\" in this scenario")
    }

    private fun bucketedRule(targetKey: String, negated: Boolean): String = """
        {"OR":[{"AND":[{"OR_WHEN":[
          {"rule_type":"bucketed_into_experience_key","matching":{"match_type":"equals","negated":$negated},"value":"$targetKey"}
        ]}]}]}
    """.trimIndent()

    private class CapturingLogger : Logger {
        val warnings: MutableList<String> = mutableListOf()
        override fun error(message: String, throwable: Throwable?, tag: String?) = Unit
        override fun warn(message: String, throwable: Throwable?, tag: String?) {
            warnings += message
        }
        override fun info(message: String, tag: String?) = Unit
        override fun debug(message: String, tag: String?) = Unit
    }

    /** One row of the qs-03 8-row fixture, re-keyed per M1/M2 (see class KDoc). */
    internal data class FixtureRow(
        val rowNumber: Int,
        val bucketedRaw: Boolean?,
        val negated: Boolean,
        val expectedMatched: Boolean,
        val expectWarn: Boolean,
        val notes: String,
    )

    // --- AC1: the 8-row fixture ------------------------------------------

    @ParameterizedTest(name = "row {0}")
    @MethodSource("fixtureRows")
    fun `bucketed_into_experience_key matches per 8-row fixture`(row: FixtureRow) {
        val logger = CapturingLogger()
        val rules = decodeAudienceRules(bucketedRule(targetKey = "exp-a", negated = row.negated))
        val resolver = fakeResolver(row.bucketedRaw)

        val result = managerWith(logger).evaluate(rules, emptyMap(), resolver)

        assertEquals(row.expectedMatched, result, "row ${row.rowNumber}: ${row.notes}")
        if (row.expectWarn) {
            assertTrue(
                logger.warnings.any { it.contains("exp-a") },
                "row ${row.rowNumber} must WARN naming the unresolved key \"exp-a\"; got ${logger.warnings}",
            )
        } else {
            assertTrue(
                logger.warnings.isEmpty(),
                "row ${row.rowNumber} must NOT warn (known target); got ${logger.warnings}",
            )
        }
    }

    // --- AC8: unknown-target warning, isolated assertion on rows 6/7 -----

    @Test
    fun `unknown target key logs WARN naming the key — AC8 row 6`() {
        val logger = CapturingLogger()
        val rules = decodeAudienceRules(bucketedRule(targetKey = "exp-zz", negated = false))
        val result = managerWith(logger).evaluate(rules, emptyMap(), fakeResolver(null))
        assertFalse(result)
        assertTrue(logger.warnings.any { it.contains("exp-zz") }, "expected WARN naming exp-zz; got ${logger.warnings}")
    }

    @Test
    fun `unknown target key with negation still warns and dissolves exclusion — AC8 row 7`() {
        val logger = CapturingLogger()
        val rules = decodeAudienceRules(bucketedRule(targetKey = "exp-zz", negated = true))
        val result = managerWith(logger).evaluate(rules, emptyMap(), fakeResolver(null))
        assertTrue(result)
        assertTrue(logger.warnings.any { it.contains("exp-zz") }, "expected WARN naming exp-zz; got ${logger.warnings}")
    }

    // --- AC6: nested combination with a generic leaf, ALL (AND) semantics -

    @Test
    fun `combination under ALL — generic and bucketed leaves both must pass`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[
              {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}]},
              {"OR_WHEN":[{"rule_type":"bucketed_into_experience_key","matching":{"match_type":"equals","negated":false},"value":"exp-a"}]}
            ]}]}
            """.trimIndent(),
        )
        val attrs = mapOf("plan" to JsonPrimitive("premium"))

        // Both pass → ALL passes.
        assertTrue(managerWith().evaluate(rules, attrs, fakeResolver(true)))

        // Generic passes, bucketed leaf fails → ALL fails.
        assertFalse(managerWith().evaluate(rules, attrs, fakeResolver(false)))
    }

    @Test
    fun `combination under ALL — generic leaf fails first, short-circuits before bucketed leaf`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[
              {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}]},
              {"OR_WHEN":[{"rule_type":"bucketed_into_experience_key","matching":{"match_type":"equals","negated":false},"value":"exp-a"}]}
            ]}]}
            """.trimIndent(),
        )
        // "plan" attribute fails the first block — AND.all short-circuits and
        // must never reach the bucketed leaf, so a throw-if-called resolver
        // proves the second OR_WHEN block was never evaluated.
        val result = managerWith().evaluate(
            rules,
            mapOf("plan" to JsonPrimitive("free")),
            throwIfCalledResolver,
        )
        assertFalse(result)
    }

    // --- AC6: nested combination with a generic leaf, ANY (OR_WHEN) semantics

    @Test
    fun `combination under ANY — generic leaf matches first, short-circuits before bucketed leaf`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"},
              {"rule_type":"bucketed_into_experience_key","matching":{"match_type":"equals","negated":false},"value":"exp-a"}
            ]}]}]}
            """.trimIndent(),
        )
        // "plan" matches — OR_WHEN.any short-circuits on the first true and
        // must never reach the bucketed leaf.
        val result = managerWith().evaluate(
            rules,
            mapOf("plan" to JsonPrimitive("premium")),
            throwIfCalledResolver,
        )
        assertTrue(result)
    }

    @Test
    fun `combination under ANY — generic leaf fails, bucketed leaf evaluated and matches`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"},
              {"rule_type":"bucketed_into_experience_key","matching":{"match_type":"equals","negated":false},"value":"exp-a"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(
            rules,
            mapOf("plan" to JsonPrimitive("free")),
            fakeResolver(true),
        )
        assertTrue(result)
    }

    @Test
    fun `combination under ANY — both leaves fail returns false`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"},
              {"rule_type":"bucketed_into_experience_key","matching":{"match_type":"equals","negated":false},"value":"exp-a"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(
            rules,
            mapOf("plan" to JsonPrimitive("free")),
            fakeResolver(false),
        )
        assertFalse(result)
    }

    // --- AC7: generic-rule regression lock — resolver never touched -------

    @Test
    fun `purely generic rule tree never touches the resolver — AC7`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[
              {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}]},
              {"OR_WHEN":[{"rule_type":"generic_numeric_key_value","matching":{"match_type":"less","negated":false},"key":"age","value":30}]}
            ]}]}
            """.trimIndent(),
        )
        val attrs = mapOf("plan" to JsonPrimitive("premium"), "age" to JsonPrimitive(25))

        // Baseline: identical result with no resolver at all (today's 2-arg call shape).
        val baseline = managerWith().evaluate(rules, attrs)

        // Injecting a throw-if-called resolver must not change the result and
        // must not throw — proving the generic match_type path never
        // dispatches to the resolver (M2: new branch is gated strictly on
        // rule_type, never reached for generic elements).
        val withResolver = managerWith().evaluate(rules, attrs, throwIfCalledResolver)

        assertTrue(baseline)
        assertEquals(baseline, withResolver)
    }

    // --- Absent resolver falls closed, negation NOT applied ---------------

    @Test
    fun `absent resolver falls closed to false regardless of negation (not negated)`() {
        val rules = decodeAudienceRules(bucketedRule(targetKey = "exp-a", negated = false))
        // No third argument — matches every other call site that hasn't
        // threaded visitor bucketing state (e.g. location/segment trees).
        val result = managerWith().evaluate(rules, emptyMap())
        assertFalse(result)
    }

    @Test
    fun `absent resolver falls closed to false even when negated true (negation NOT applied)`() {
        val rules = decodeAudienceRules(bucketedRule(targetKey = "exp-a", negated = true))
        // If negation were (incorrectly) applied on the fail-closed fall-
        // through, this would be true. The fail-closed contract (mirroring
        // every other unresolvable-rule path in RuleManager today) demands
        // false regardless of `negated`.
        val result = managerWith().evaluate(rules, emptyMap())
        assertFalse(result)
    }

    companion object {
        @JvmStatic
        fun fixtureRows(): Stream<FixtureRow> = Stream.of(
            FixtureRow(
                rowNumber = 1,
                bucketedRaw = false,
                negated = false,
                expectedMatched = false,
                expectWarn = false,
                notes = "known target, not bucketed, no warn",
            ),
            FixtureRow(
                rowNumber = 2,
                bucketedRaw = false,
                negated = true,
                expectedMatched = true,
                expectWarn = false,
                notes = "known target, not bucketed, negation applied",
            ),
            FixtureRow(
                rowNumber = 3,
                bucketedRaw = true,
                negated = false,
                expectedMatched = true,
                expectWarn = false,
                notes = "known target, bucketed, no negation",
            ),
            FixtureRow(
                rowNumber = 4,
                bucketedRaw = true,
                negated = true,
                expectedMatched = false,
                expectWarn = false,
                notes = "known target, bucketed, negated -> excluded",
            ),
            FixtureRow(
                rowNumber = 5,
                bucketedRaw = false,
                negated = true,
                expectedMatched = true,
                expectWarn = false,
                notes = "known target, bucketed into a DIFFERENT experience only, no warn",
            ),
            FixtureRow(
                rowNumber = 6,
                bucketedRaw = null,
                negated = false,
                expectedMatched = false,
                expectWarn = true,
                notes = "unknown target key -> warn",
            ),
            FixtureRow(
                rowNumber = 7,
                bucketedRaw = null,
                negated = true,
                expectedMatched = true,
                expectWarn = true,
                notes = "unknown target key, negated -> exclusion dissolves, still warns",
            ),
            FixtureRow(
                rowNumber = 8,
                bucketedRaw = true,
                negated = true,
                expectedMatched = false,
                expectWarn = false,
                // Cross-restart persistence (warm SharedPreferences on a fresh
                // SDK instance) is exercised end-to-end in AND-2; at the
                // RuleManager-unit level the resolver simply reports a known,
                // bucketed target — identical inputs to row 4.
                notes = "cross-restart (AND-2 end-to-end); unit-level equivalent to row 4",
            ),
        )
    }
}
