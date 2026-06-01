/*
 * Convert Android SDK — core/rules tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.rules

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.RulesConfig
import com.convert.sdk.core.model.generated.RuleObject
import com.convert.sdk.core.model.generated.RuleObjectAudience
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Tests for [RuleManager] — Story 3.4 AC-1, AC-3, AC-4, AC-5 (indirectly),
 * AC-7, AC-8, AC-9.
 *
 * ### Rule-tree deserialization
 *
 * The helper [decodeAudienceRules] reuses the production JSON codec
 * (with the [rawRuleSerializersModule] registered) so every test walks
 * the same path the SDK uses at runtime — including the raw-leaf capture
 * that bypasses the generated `RuleElementAudience` interface.
 */
internal class RuleManagerTest {

    private val logger: Logger = Logger.NoOp

    /** JSON codec with the raw-rule polymorphic defaults wired in — same config the SDK ships with. */
    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        serializersModule = rawRuleSerializersModule
    }

    private fun managerWith(
        keysCaseSensitive: Boolean? = null,
        negation: String? = null,
    ): RuleManager {
        val rules = if (keysCaseSensitive != null || negation != null) {
            RulesConfig(keysCaseSensitive = keysCaseSensitive, negation = negation)
        } else {
            null
        }
        return RuleManager(config = ConvertConfig(rules = rules), logger = logger)
    }

    private fun decodeAudienceRules(payload: String): RuleObjectAudience =
        json.decodeFromString(payload)

    private fun decodeLocationRules(payload: String): RuleObject =
        json.decodeFromString(payload)

    private fun attrs(vararg pairs: Pair<String, JsonElement>): Map<String, JsonElement> =
        mapOf(*pairs)

    // --- AC-1: Empty / null rules return false (rule not valid) — F-024 ---

    @Test
    fun `evaluate returns false for null audience rules (rule not valid)`() {
        // F-024: JS SDK rule-manager.ts:116-153 treats absent/empty OR as
        // INVALID — log WARN + return false. Empty rule set is NOT "no
        // constraints"; cross-SDK parity demands false here.
        val result = managerWith().evaluate(null as RuleObjectAudience?, emptyMap())
        assertFalse(result)
    }

    @Test
    fun `evaluate returns false for empty OR block (rule not valid)`() {
        val rules = decodeAudienceRules("""{"OR": []}""")
        assertFalse(managerWith().evaluate(rules, emptyMap()))
    }

    @Test
    fun `evaluate returns false when rules OR field is null (rule not valid)`() {
        val rules = decodeAudienceRules("""{}""")
        assertFalse(managerWith().evaluate(rules, emptyMap()))
    }

    // --- AC-1: Single OR/AND/OR_WHEN with equals ------------------------

    @Test
    fun `evaluate returns true when single equals rule matches`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
                {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("plan" to JsonPrimitive("premium")))
        assertTrue(result)
    }

    @Test
    fun `evaluate returns false when single equals rule does not match`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
                {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("plan" to JsonPrimitive("free")))
        assertFalse(result)
    }

    // --- AC-1: OR-level — any AND passing returns true -------------------

    @Test
    fun `OR level first AND fails second AND passes returns true`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[
              {"AND":[{"OR_WHEN":[
                {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"gold"}
              ]}]},
              {"AND":[{"OR_WHEN":[
                {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}
              ]}]}
            ]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("plan" to JsonPrimitive("premium")))
        assertTrue(result)
    }

    // --- AC-1: AND-level — every OR_WHEN must pass -----------------------

    @Test
    fun `AND level all OR_WHEN pass returns true`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[
              {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}]},
              {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"country","value":"us"}]}
            ]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(
            rules,
            attrs("plan" to JsonPrimitive("premium"), "country" to JsonPrimitive("us")),
        )
        assertTrue(result)
    }

    @Test
    fun `AND level one OR_WHEN fails returns false`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[
              {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}]},
              {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"country","value":"us"}]}
            ]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(
            rules,
            attrs("plan" to JsonPrimitive("premium"), "country" to JsonPrimitive("de")),
        )
        assertFalse(result)
    }

    // --- AC-1: OR_WHEN-level — any rule passing returns true -------------

    @Test
    fun `OR_WHEN level first rule fails second rule passes returns true`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"gold"},
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("plan" to JsonPrimitive("premium")))
        assertTrue(result)
    }

    // --- AC-3 / F-108: keys_case_sensitive — JS-SDK falsy-coalesce quirk ---

    @Test
    fun `case insensitive — keys_case_sensitive false coalesces to true`() {
        // F-108 (test 1 of 2): mirrors JS SDK rule-manager.ts:57-58
        // `config?.rules?.keys_case_sensitive || DEFAULT_KEYS_CASE_SENSITIVE`
        // — any falsy value, including explicit `false`, coalesces back to
        // `true`. So passing `false` via config has NO effect; the lookup
        // remains case-sensitive and the mixed-case attribute is not found.
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"Plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        )
        val mgr = managerWith(keysCaseSensitive = false)
        val result = mgr.evaluate(rules, attrs("PLAN" to JsonPrimitive("premium")))
        // Quirk: false → true → case-sensitive lookup → "PLAN" != "Plan" → false
        assertFalse(result)
    }

    @Test
    fun `case insensitive — equals with keys_case_sensitive injected false bypassing coalesce`() {
        // F-108 (test 2 of 2): the JS-SDK quirk makes the case-insensitive
        // code path unreachable through normal config construction. To
        // verify the underlying logic still works (and to keep cross-SDK
        // parity in the unlikely event the JS SDK fixes its quirk), the
        // RuleManager exposes an `internal` `keysCaseSensitiveOverride`
        // seam that bypasses the coalesce. Production code never touches
        // it; tests use it to drive the case-insensitive branch directly.
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"Plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        )
        val mgr = managerWith()
        mgr.keysCaseSensitiveOverride = false
        val result = mgr.evaluate(rules, attrs("PLAN" to JsonPrimitive("PREMIUM")))
        // Override bypasses coalesce → case-insensitive lookup → "PLAN" matches "Plan" → true
        assertTrue(result)
    }

    @Test
    fun `keys case sensitive default does not match mixed case key`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"Plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        )
        // Default is case-sensitive — "PLAN" attribute is not found when rule key is "Plan"
        val result = managerWith().evaluate(rules, attrs("PLAN" to JsonPrimitive("premium")))
        assertFalse(result)
    }

    // --- AC-4: negation (matching.negated = true) -----------------------

    @Test
    fun `negation inverts equals result`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":true},"key":"plan","value":"free"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("plan" to JsonPrimitive("premium")))
        assertTrue(result)
    }

    // --- AC-7: Unknown operator → WARN + false --------------------------

    @Test
    fun `unknown match_type returns false`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"gibberish","negated":false},"key":"plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("plan" to JsonPrimitive("premium")))
        assertFalse(result)
    }

    // --- AC-7: Missing attribute → WARN + false -------------------------

    @Test
    fun `missing attribute key returns false`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}
            ]}]}]}
            """.trimIndent(),
        )
        // No `plan` attribute on the map
        val result = managerWith().evaluate(rules, emptyMap())
        assertFalse(result)
    }

    // --- AC-8: Numeric operators ----------------------------------------

    @Test
    fun `numeric less operator matches`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_numeric_key_value","matching":{"match_type":"less","negated":false},"key":"age","value":30}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("age" to JsonPrimitive(25)))
        assertTrue(result)
    }

    @Test
    fun `numeric less operator fails when value above`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_numeric_key_value","matching":{"match_type":"less","negated":false},"key":"age","value":30}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("age" to JsonPrimitive(35)))
        assertFalse(result)
    }

    // --- AC-9: isIn operator --------------------------------------------

    @Test
    fun `isIn with pipe-separated value matches array element`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"isIn","negated":false},"key":"plan","value":"silver|gold|platinum"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("plan" to JsonPrimitive("gold")))
        assertTrue(result)
    }

    // --- regexMatches (story Dev Notes) ---------------------------------

    @Test
    fun `regexMatches operator matches pattern`() {
        val rules = decodeAudienceRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"regexMatches","negated":false},"key":"email","value":"@example\\.com"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("email" to JsonPrimitive("alice@EXAMPLE.com")))
        assertTrue(result)
    }

    // --- AC-5 / AC-6: Location overload behaves identically --------------

    @Test
    fun `location rules overload evaluates against location properties`() {
        val rules = decodeLocationRules(
            """
            {"OR":[{"AND":[{"OR_WHEN":[
              {"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"country","value":"us"}
            ]}]}]}
            """.trimIndent(),
        )
        val result = managerWith().evaluate(rules, attrs("country" to JsonPrimitive("us")))
        assertTrue(result)
    }

    @Test
    fun `location rules overload returns false for null rules (rule not valid)`() {
        // F-024: same invalid-rule-set semantics as the audience overload.
        val result = managerWith().evaluate(null as RuleObject?, emptyMap())
        assertFalse(result)
    }

    // --- Complex combined structure -------------------------------------

    @Test
    fun `complex OR-AND-OR_WHEN combination evaluates correctly`() {
        // (plan == premium AND country == us) OR (plan == enterprise)
        val rules = decodeAudienceRules(
            """
            {"OR":[
              {"AND":[
                {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"premium"}]},
                {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"country","value":"us"}]}
              ]},
              {"AND":[
                {"OR_WHEN":[{"rule_type":"generic_key_value","matching":{"match_type":"equals","negated":false},"key":"plan","value":"enterprise"}]}
              ]}
            ]}
            """.trimIndent(),
        )
        val mgr = managerWith()
        // Enterprise visitor from anywhere → passes via second AND branch
        assertTrue(mgr.evaluate(rules, attrs("plan" to JsonPrimitive("enterprise"), "country" to JsonPrimitive("de"))))
        // Premium visitor in US → passes via first AND branch
        assertTrue(mgr.evaluate(rules, attrs("plan" to JsonPrimitive("premium"), "country" to JsonPrimitive("us"))))
        // Premium visitor outside US → fails both branches
        assertEquals(
            false,
            mgr.evaluate(rules, attrs("plan" to JsonPrimitive("premium"), "country" to JsonPrimitive("de"))),
        )
    }
}
