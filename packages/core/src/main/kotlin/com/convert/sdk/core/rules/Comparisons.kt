/*
 * Convert Android SDK — core/rules
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Operator dispatcher for rule-element comparisons.
 *
 * Mirrors the JS SDK's `@convertcom/js-sdk-utils/Comparisons` static
 * class method-for-method. The operator names listed below are the
 * wire-level `match_type` strings the config JSON carries — keeping them
 * identical to the JS SDK is a cross-SDK parity guarantee.
 *
 * ### Supported operators (JS SDK method names)
 *
 *  - `equals` / `equalsNumber` / `matches` — case-insensitive string
 *    equality. When `value` is a [JsonArray], returns whether
 *    `testAgainst` appears in the array. When `value` is a [JsonObject],
 *    returns whether `testAgainst.toString()` appears among the keys.
 *  - `less` / `lessEqual` — numeric comparisons. Both sides coerced via
 *    [JsonPrimitive.doubleOrNull]; coercion failure returns `false`.
 *    Matches JS SDK `isNumeric(x) ? toNumber(x) : x` + `typeof` equality
 *    check — if the types after coercion differ (e.g. a numeric string on
 *    one side and a non-numeric string on the other), the comparison
 *    returns `false`.
 *  - `contains` — case-insensitive substring match. An all-whitespace
 *    `testAgainst` returns `true` unconditionally (matches JS SDK's
 *    `replace(/^([\s]*)|([\s]*)$/g, '').length === 0` branch).
 *  - `startsWith` / `endsWith` — case-insensitive prefix / suffix match.
 *  - `regexMatches` — **always case-insensitive** regex scan (JS SDK
 *    pins `new RegExp(testAgainst, 'i')` regardless of the rule's
 *    case-sensitivity setting). The [RuleManager] does NOT flip this
 *    flag when `keys_case_sensitive = false` — that setting only
 *    affects key lookup, never the regex compile. Uses
 *    [Regex.containsMatchIn] so partial matches satisfy the rule.
 *  - `isIn` — pipe-separated value versus pipe-separated test string OR
 *    a [JsonArray] of candidates. Both sides lowercased for comparison.
 *  - `exists` — `true` when the attribute value is present and non-empty.
 *    Matches JS SDK `Comparisons.exists`: `value !== undefined && value !==
 *    null && value !== ''`. Neither this operator nor `doesNotExist`
 *    requires a `value` field on the rule element; [RuleManager] dispatches
 *    them even when the attribute key is absent (passing [JsonNull]) —
 *    exactly as JS `_processRuleItem` calls the comparator with `undefined`.
 *  - `doesNotExist` — logical inverse of `exists` (`not_exists` alias in
 *    JS SDK).
 *
 * ### Negation (`matching.negated: boolean`)
 *
 * Each operator consults the `negation` flag at the very end: when
 * `true`, the boolean result flips. The JS SDK puts this behind a
 * private `_returnNegationCheck` helper; we inline it in [applyNegation]
 * for the same reason.
 *
 * ### JsonNull handling
 *
 * All operators except `exists`/`doesNotExist` return `false` when the
 * `value` side is [JsonNull]. `exists` and `doesNotExist` must still be
 * dispatched when `value` is [JsonNull] (the attribute key was absent or
 * the lookup returned null) — they short-circuit the early [JsonNull]
 * guard and proceed to their own implementations.
 *
 * ### Unknown operators
 *
 * [apply] returns `null` when the `matchType` argument is not a recognised
 * operator. The caller (typically [RuleManager]) is expected to log a
 * WARN with the unknown operator name and treat the comparison as
 * `false` (per AC-7). Separating the "unknown" signal from a `false`
 * match lets the caller distinguish a failed comparison from an
 * unsupported operator in aggregated logs.
 */
internal object Comparisons {

    /**
     * Known `match_type` strings. Exposed as a read-only [Set] so
     * [isKnown] can dispatch in constant time and the [RuleManager]
     * can enumerate supported operators in its trace logs.
     */
    private val KNOWN: Set<String> = setOf(
        "equals",
        "equalsNumber",
        "matches",
        "less",
        "lessEqual",
        "contains",
        "isIn",
        "startsWith",
        "endsWith",
        "regexMatches",
        "exists",
        "doesNotExist",
    )

    /**
     * Returns `true` when [matchType] is a JS-SDK-recognised operator
     * and [apply] will dispatch it. Used by [RuleManager] to produce a
     * "unknown match_type" WARN with a stable phrasing before returning
     * `false` on behalf of the caller.
     *
     * @param matchType the `match_type` string from the rule element.
     * @return `true` when the operator is dispatchable by [apply].
     */
    fun isKnown(matchType: String): Boolean = matchType in KNOWN

    /**
     * Dispatches to the appropriate comparison function, returning
     * `true` / `false` on a successful comparison or `null` when
     * [matchType] is not a recognised operator.
     *
     * @param matchType the `match_type` string from the rule element.
     * @param value the attribute value (from the visitor's attributes
     *   or location properties).
     * @param testAgainst the rule value (from `rule.value`).
     * @param negation the `matching.negated` flag (flips the result).
     * @return the comparison result, or `null` when [matchType] is
     *   unknown. Callers treat `null` as "false + log WARN".
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun apply(
        matchType: String,
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
    ): Boolean? {
        // `exists` and `doesNotExist` must be dispatched even when value is
        // JsonNull (attribute key absent / lookup returned null) — this is
        // what makes `doesNotExist` return true for a missing key, mirroring
        // JS SDK `_processRuleItem` calling the comparator with `undefined`.
        if (matchType == "exists") return exists(value, negation)
        if (matchType == "doesNotExist") return doesNotExist(value, negation)

        // JsonNull on the value side: nothing to compare against. JS SDK
        // returns false here for all remaining operators.
        if (value is JsonNull) {
            return applyNegation(result = false, negation = negation)
        }
        return when (matchType) {
            "equals", "equalsNumber", "matches" -> equals(value, testAgainst, negation)
            "less" -> less(value, testAgainst, negation)
            "lessEqual" -> lessEqual(value, testAgainst, negation)
            "contains" -> contains(value, testAgainst, negation)
            "isIn" -> isIn(value, testAgainst, negation)
            "startsWith" -> startsWith(value, testAgainst, negation)
            "endsWith" -> endsWith(value, testAgainst, negation)
            "regexMatches" -> regexMatches(value, testAgainst, negation)
            else -> null
        }
    }

    /**
     * Mirrors `Comparisons.equals` in the JS SDK:
     *
     *  - [JsonArray]: returns whether [testAgainst] is an element.
     *  - [JsonObject]: returns whether `testAgainst.toString()` is a key.
     *  - Otherwise: string-lowercase both sides and `==` compare.
     */
    private fun equals(
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
    ): Boolean {
        val result = when (value) {
            is JsonArray -> value.contains(testAgainst) ||
                value.any { it.asComparableString() == testAgainst.asComparableString() }
            is JsonObject -> value.keys.any { it.lowercase() == testAgainst.asComparableString() }
            else -> value.asComparableString() == testAgainst.asComparableString()
        }
        return applyNegation(result, negation)
    }

    /**
     * Mirrors `Comparisons.less`:
     *
     *  - Both sides coerced via `isNumeric(...) ? toNumber(...) : x`.
     *  - If the coerced types differ (one number / one string), return
     *    `false` before the `<` compare. JS SDK checks `typeof`; we use
     *    [Double] coercion + null-short-circuit for the same outcome.
     */
    private fun less(
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
    ): Boolean = numericCompare(value, testAgainst, negation) { lhs, rhs -> lhs < rhs }

    /** [less] with `<=` instead of `<`. */
    private fun lessEqual(
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
    ): Boolean = numericCompare(value, testAgainst, negation) { lhs, rhs -> lhs <= rhs }

    /**
     * Shared numeric-operator body. Coerces both sides to [Double];
     * coercion failure (either side non-numeric) short-circuits to
     * `false`, then [applyNegation] flips the result if requested.
     */
    private inline fun numericCompare(
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
        op: (Double, Double) -> Boolean,
    ): Boolean {
        val lhs = value.asDoubleOrNull()
        val rhs = testAgainst.asDoubleOrNull()
        val result = if (lhs == null || rhs == null) false else op(lhs, rhs)
        return applyNegation(result, negation)
    }

    /**
     * Mirrors `Comparisons.contains`:
     *
     *  - Lowercase both sides.
     *  - Trimmed all-whitespace [testAgainst] → `true` (JS SDK special
     *    case — avoids accidental empty-needle matches).
     *  - Otherwise: [String.contains] (lowercased).
     */
    private fun contains(
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
    ): Boolean {
        val lhs = value.asComparableString()
        val rhs = testAgainst.asComparableString()
        val result = if (rhs.trim().isEmpty()) true else lhs.contains(rhs)
        return applyNegation(result, negation)
    }

    /** Mirrors `Comparisons.startsWith` (lowercase + [String.startsWith]). */
    private fun startsWith(
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
    ): Boolean {
        val lhs = value.asComparableString()
        val rhs = testAgainst.asComparableString()
        return applyNegation(lhs.startsWith(rhs), negation)
    }

    /** Mirrors `Comparisons.endsWith` (lowercase + [String.endsWith]). */
    private fun endsWith(
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
    ): Boolean {
        val lhs = value.asComparableString()
        val rhs = testAgainst.asComparableString()
        return applyNegation(lhs.endsWith(rhs), negation)
    }

    /**
     * Mirrors `Comparisons.regexMatches` — always case-insensitive. The
     * pattern is compiled fresh each call; [RuleManager] does NOT cache
     * compiled patterns because the rule set is small (typically < 10
     * rules per experience) and the JIT handles the repeated compiles
     * well below the <5ms bucketing budget.
     */
    private fun regexMatches(
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
    ): Boolean {
        val lhs = value.asComparableString()
        val pattern = testAgainst.asRawString()
        val result = try {
            Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(lhs)
        } catch (_: IllegalArgumentException) {
            // Malformed regex — treat as no match. JS SDK throws and the
            // RuleManager catches; we short-circuit here so the caller's
            // code path stays clean.
            false
        }
        return applyNegation(result, negation)
    }

    /**
     * Mirrors `Comparisons.isIn`:
     *
     *  - Value side: split by `|` into tokens.
     *  - TestAgainst: if [JsonArray], use elements; if string, split by
     *    `|`; otherwise the empty list.
     *  - Both sides lowercased.
     *  - Returns `true` if ANY value token matches ANY testAgainst token.
     *
     * ### INTENTIONAL PARITY EXCEPTION vs JS SDK (`comparisons.ts:89-115`)
     *
     * The Android SDK lowercases **both** the value side and the
     * `testAgainst` side, making `isIn` fully case-insensitive. The JS SDK
     * (`comparisons.ts:106-108`) only lowercases the `testAgainst` (rule)
     * side; the value side retains its original casing, so `"US"` vs
     * `"us|ca"` would **not** match in JS but **does** match here.
     *
     * This is a documented, intentional divergence (Direction B) chosen for
     * consistency with Android's other string operators (all of which
     * lowercase both sides). The JS value-side case-sensitivity is likely an
     * unintended quirk; it is a candidate for a separate JS-side fix ticket.
     * Do **not** remove this lowercasing to "fix" parity — doing so would
     * break AC-4.4 and the Android-specific test coverage.
     */
    private fun isIn(
        value: JsonElement,
        testAgainst: JsonElement,
        negation: Boolean,
    ): Boolean {
        val lhsTokens = value.asRawString().split('|').map { it.lowercase() }
        val rhsTokens: List<String> = when (testAgainst) {
            is JsonArray -> testAgainst.map { it.asComparableString() }
            else -> testAgainst.asRawString().split('|').map { it.lowercase() }
        }
        val result = lhsTokens.any { it in rhsTokens }
        return applyNegation(result, negation)
    }

    /**
     * `exists` operator — mirrors JS SDK `Comparisons.exists`
     * (`comparisons.ts:154-161`):
     *
     *   `value !== undefined && value !== null && value !== ''`
     *
     * [JsonNull] is the Android equivalent of JS `undefined`/`null`.
     * An empty string (`""`) also counts as non-existent, matching JS
     * semantics. This operator is dispatched even when the attribute key is
     * absent from the visitor attributes (the caller passes [JsonNull]) —
     * which is what makes `exists=false` correct for a missing key without
     * a separate short-circuit in [RuleManager].
     *
     * Does **not** require a `value` field on the rule element; [apply]
     * ignores the [testAgainst] parameter for this operator.
     */
    private fun exists(value: JsonElement, negation: Boolean): Boolean {
        val valueExists = value !is JsonNull && value.asRawString() != ""
        return applyNegation(valueExists, negation)
    }

    /**
     * `doesNotExist` operator — logical inverse of [exists]. Mirrors JS SDK
     * `Comparisons.not_exists` / `doesNotExist` alias
     * (`comparisons.ts:163-172`):
     *
     *   `value === undefined || value === null || value === ''`
     *
     * Dispatched even when the attribute key is absent ([JsonNull] value),
     * so `doesNotExist=true` for a missing key is the natural result.
     *
     * Does **not** require a `value` field on the rule element.
     */
    private fun doesNotExist(value: JsonElement, negation: Boolean): Boolean {
        val valueNotExists = value is JsonNull || value.asRawString() == ""
        return applyNegation(valueNotExists, negation)
    }

    /**
     * Applies the `matching.negated` flag. Single-point implementation
     * so every operator's negation semantics stay identical and aligned
     * with JS SDK `_returnNegationCheck`.
     */
    private fun applyNegation(result: Boolean, negation: Boolean): Boolean =
        if (negation) !result else result

    /**
     * String-compares-ready rendering: strips JSON quotes and lowercases.
     * [JsonNull] becomes the string `"null"` (matches JS SDK `String(null)
     * === "null"`), but [apply] short-circuits [JsonNull] before dispatch
     * so this branch is defensive only.
     */
    private fun JsonElement.asComparableString(): String = asRawString().lowercase()

    /**
     * Unquoted string rendering. [JsonPrimitive.content] strips JSON
     * quotes whether the primitive is a string or a number, so we can
     * return it directly. [JsonNull] becomes `"null"` (matches JS SDK
     * `String(null) === "null"`), though [apply] short-circuits
     * [JsonNull] on the `value` side before dispatch so this branch
     * only triggers when the `testAgainst` side is null — which is
     * itself degenerate input. Non-primitive, non-null elements
     * render via [JsonElement.toString] as a last-ditch for
     * array-operator comparisons.
     */
    private fun JsonElement.asRawString(): String = when (this) {
        is JsonNull -> "null"
        is JsonPrimitive -> content
        else -> toString()
    }

    /**
     * Coerces a [JsonElement] to [Double] for numeric operators.
     * Delegates to [JsonPrimitive.doubleOrNull] which understands both
     * JSON numeric literals and JSON strings containing a number (JS SDK
     * `isNumeric(x) ? toNumber(x) : x` semantics).
     */
    private fun JsonElement.asDoubleOrNull(): Double? = when (this) {
        is JsonPrimitive -> doubleOrNull
        else -> null
    }
}
