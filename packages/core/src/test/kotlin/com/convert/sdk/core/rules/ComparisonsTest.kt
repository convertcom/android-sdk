/*
 * Convert Android SDK — core/rules tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream

/**
 * Tests for [Comparisons] — Story 3.4 AC-2, AC-3, AC-4, AC-8, AC-9.
 *
 * ### JS SDK parity
 *
 * Every operator name mirrored from the JS SDK's
 * `@convertcom/js-sdk-utils/Comparisons` static class. Each test below
 * double-checks both the positive-match and the `negated=true` inversion
 * path, plus the "coercion fails → returns false" branch where applicable.
 */
internal class ComparisonsTest {

    // --- equals / matches / equalsNumber (aliases) ------------------------

    @Test
    fun `equals returns true for matching primitives (case-insensitive)`() {
        val result = Comparisons.apply(
            matchType = "equals",
            value = JsonPrimitive("Premium"),
            testAgainst = JsonPrimitive("premium"),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `equals returns false for mismatched primitives`() {
        val result = Comparisons.apply(
            matchType = "equals",
            value = JsonPrimitive("free"),
            testAgainst = JsonPrimitive("premium"),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `equals with negation inverts the result`() {
        val result = Comparisons.apply(
            matchType = "equals",
            value = JsonPrimitive("free"),
            testAgainst = JsonPrimitive("premium"),
            negation = true,
        )
        assertEquals(true, result)
    }

    @Test
    fun `matches is alias of equals`() {
        val result = Comparisons.apply(
            matchType = "matches",
            value = JsonPrimitive("gold"),
            testAgainst = JsonPrimitive("gold"),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `equalsNumber is alias of equals`() {
        val result = Comparisons.apply(
            matchType = "equalsNumber",
            value = JsonPrimitive(42),
            testAgainst = JsonPrimitive(42),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `equals on JsonArray returns true when testAgainst is an element`() {
        val result = Comparisons.apply(
            matchType = "equals",
            value = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"), JsonPrimitive("c"))),
            testAgainst = JsonPrimitive("b"),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `equals on JsonArray returns false when testAgainst is not an element`() {
        val result = Comparisons.apply(
            matchType = "equals",
            value = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            testAgainst = JsonPrimitive("z"),
            negation = false,
        )
        assertEquals(false, result)
    }

    // --- less / lessEqual -------------------------------------------------

    @Test
    fun `less returns true when value less than testAgainst`() {
        val result = Comparisons.apply(
            matchType = "less",
            value = JsonPrimitive(5),
            testAgainst = JsonPrimitive(10),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `less returns false when value greater than testAgainst`() {
        val result = Comparisons.apply(
            matchType = "less",
            value = JsonPrimitive(15),
            testAgainst = JsonPrimitive(10),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `less returns false when values equal`() {
        val result = Comparisons.apply(
            matchType = "less",
            value = JsonPrimitive(10),
            testAgainst = JsonPrimitive(10),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `less returns false on non-numeric coercion failure`() {
        val result = Comparisons.apply(
            matchType = "less",
            value = JsonPrimitive("not-a-number"),
            testAgainst = JsonPrimitive(10),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `lessEqual returns true for equal values`() {
        val result = Comparisons.apply(
            matchType = "lessEqual",
            value = JsonPrimitive(10),
            testAgainst = JsonPrimitive(10),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `lessEqual returns false when value greater`() {
        val result = Comparisons.apply(
            matchType = "lessEqual",
            value = JsonPrimitive(11),
            testAgainst = JsonPrimitive(10),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `less handles numeric strings per JS SDK isNumeric coercion`() {
        val result = Comparisons.apply(
            matchType = "less",
            value = JsonPrimitive("5"),
            testAgainst = JsonPrimitive("10"),
            negation = false,
        )
        assertEquals(true, result)
    }

    // --- contains ---------------------------------------------------------

    @Test
    fun `contains returns true for substring match (case-insensitive)`() {
        val result = Comparisons.apply(
            matchType = "contains",
            value = JsonPrimitive("Hello World"),
            testAgainst = JsonPrimitive("world"),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `contains returns false when substring absent`() {
        val result = Comparisons.apply(
            matchType = "contains",
            value = JsonPrimitive("Hello"),
            testAgainst = JsonPrimitive("xyz"),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `contains returns true for empty testAgainst per JS SDK convention`() {
        // JS SDK: if testAgainst is all whitespace → true.
        val result = Comparisons.apply(
            matchType = "contains",
            value = JsonPrimitive("anything"),
            testAgainst = JsonPrimitive(" "),
            negation = false,
        )
        assertEquals(true, result)
    }

    // --- startsWith / endsWith --------------------------------------------

    @Test
    fun `startsWith returns true when value begins with testAgainst`() {
        val result = Comparisons.apply(
            matchType = "startsWith",
            value = JsonPrimitive("Hello World"),
            testAgainst = JsonPrimitive("hello"),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `startsWith returns false when value does not begin with testAgainst`() {
        val result = Comparisons.apply(
            matchType = "startsWith",
            value = JsonPrimitive("Hello"),
            testAgainst = JsonPrimitive("world"),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `endsWith returns true when value ends with testAgainst`() {
        val result = Comparisons.apply(
            matchType = "endsWith",
            value = JsonPrimitive("Hello World"),
            testAgainst = JsonPrimitive("WORLD"),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `endsWith returns false when value does not end with testAgainst`() {
        val result = Comparisons.apply(
            matchType = "endsWith",
            value = JsonPrimitive("Hello World"),
            testAgainst = JsonPrimitive("hello"),
            negation = false,
        )
        assertEquals(false, result)
    }

    // --- regexMatches ----------------------------------------------------

    @Test
    fun `regexMatches returns true on regex hit`() {
        val result = Comparisons.apply(
            matchType = "regexMatches",
            value = JsonPrimitive("foo-123-bar"),
            testAgainst = JsonPrimitive("\\d+"),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `regexMatches is always case-insensitive (JS SDK parity)`() {
        val result = Comparisons.apply(
            matchType = "regexMatches",
            value = JsonPrimitive("HELLO"),
            testAgainst = JsonPrimitive("hello"),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `regexMatches returns false on no match`() {
        val result = Comparisons.apply(
            matchType = "regexMatches",
            value = JsonPrimitive("abc"),
            testAgainst = JsonPrimitive("^\\d+$"),
            negation = false,
        )
        assertEquals(false, result)
    }

    // --- isIn -------------------------------------------------------------

    @Test
    fun `isIn splits value by pipe and checks against testAgainst array`() {
        val result = Comparisons.apply(
            matchType = "isIn",
            value = JsonPrimitive("apple|banana"),
            testAgainst = JsonArray(
                listOf(JsonPrimitive("banana"), JsonPrimitive("cherry")),
            ),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `isIn returns false when no overlap`() {
        val result = Comparisons.apply(
            matchType = "isIn",
            value = JsonPrimitive("x|y"),
            testAgainst = JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `isIn is case-insensitive per JS SDK`() {
        val result = Comparisons.apply(
            matchType = "isIn",
            value = JsonPrimitive("APPLE"),
            testAgainst = JsonArray(listOf(JsonPrimitive("apple"))),
            negation = false,
        )
        assertEquals(true, result)
    }

    @Test
    fun `isIn accepts pipe-separated string testAgainst`() {
        // JS SDK: if testAgainst is string, split by '|'.
        val result = Comparisons.apply(
            matchType = "isIn",
            value = JsonPrimitive("apple"),
            testAgainst = JsonPrimitive("apple|banana|cherry"),
            negation = false,
        )
        assertEquals(true, result)
    }

    // --- JsonNull handling ------------------------------------------------

    @Test
    fun `equals on JsonNull value returns false`() {
        val result = Comparisons.apply(
            matchType = "equals",
            value = JsonNull,
            testAgainst = JsonPrimitive("anything"),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `contains on JsonNull value returns false`() {
        val result = Comparisons.apply(
            matchType = "contains",
            value = JsonNull,
            testAgainst = JsonPrimitive("anything"),
            negation = false,
        )
        assertEquals(false, result)
    }

    @Test
    fun `less on JsonNull value returns false`() {
        val result = Comparisons.apply(
            matchType = "less",
            value = JsonNull,
            testAgainst = JsonPrimitive(10),
            negation = false,
        )
        assertEquals(false, result)
    }

    // --- Unknown match type dispatches to null ----------------------------

    @Test
    fun `apply returns null for unknown match type`() {
        val result = Comparisons.apply(
            matchType = "not_a_real_operator",
            value = JsonPrimitive("a"),
            testAgainst = JsonPrimitive("a"),
            negation = false,
        )
        assertNull(result)
    }

    // --- Known-operator sentinel ------------------------------------------

    @Test
    fun `isKnown returns true for all supported match types`() {
        val supported = listOf(
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
        for (op in supported) {
            assertTrue(Comparisons.isKnown(op), "Expected $op to be known")
        }
    }

    @Test
    fun `isKnown returns false for unknown match types`() {
        assertFalse(Comparisons.isKnown("not_equals"))
        assertFalse(Comparisons.isKnown("greater_than"))
        assertFalse(Comparisons.isKnown(""))
    }

    // --- Sanity: aliased operators produce identical results --------------

    @Test
    fun `equals equalsNumber and matches produce identical results`() {
        val left = JsonPrimitive("a")
        val right = JsonPrimitive("A")
        val r1 = Comparisons.apply("equals", left, right, false)
        val r2 = Comparisons.apply("equalsNumber", left, right, false)
        val r3 = Comparisons.apply("matches", left, right, false)
        assertNotNull(r1)
        assertEquals(r1, r2)
        assertEquals(r1, r3)
    }

    // --- AC-4.2 / AC-4.3: exists / doesNotExist (parametrized) -----------
    //
    // Covers the full present/empty/absent matrix for both operators and
    // their negated variants — table-driven to avoid CPD duplication.
    //
    // Input matrix:
    //   (matchType, value, negation) → expectedResult
    //
    // AC-4.2 (present / empty-string value):
    //   exists(non-empty)      → true
    //   doesNotExist(non-empty)→ false
    //   exists(empty "")       → false  (empty string counts as non-existent)
    //   doesNotExist(empty "") → true
    //
    // AC-4.3 (absent key → JsonNull — operator dispatched, NOT short-circuited):
    //   exists(JsonNull)       → false
    //   doesNotExist(JsonNull) → true
    //
    // Negation variants: each result inverts with negation=true.

    @ParameterizedTest(name = "{0}(value={1}, negation={2}) → {3}")
    @MethodSource("existsMatrix")
    fun `exists and doesNotExist — present empty and absent matrix`(
        matchType: String,
        value: JsonElement,
        negation: Boolean,
        expected: Boolean,
    ) {
        val result = Comparisons.apply(
            matchType = matchType,
            value = value,
            testAgainst = JsonNull, // no rule value field required
            negation = negation,
        )
        assertEquals(expected, result, "$matchType(value=$value, negation=$negation)")
    }

    // --- AC-4.4: isIn case-insensitive — documented parity exception ------
    //
    // Android lowercases BOTH sides (Direction B — intentional parity
    // exception vs JS SDK). "US" vs rule "us|ca" must match.

    @ParameterizedTest(name = "isIn: value={0} in {1} → {2}")
    @MethodSource("isInCaseMatrix")
    fun `isIn — fully case-insensitive (intentional parity exception vs JS SDK)`(
        rawValue: String,
        rawRule: String,
        expected: Boolean,
    ) {
        // Both sides lowercased on Android (Direction B). JS only lowercases
        // the rule side — see Comparisons.isIn KDoc for the full explanation.
        val result = Comparisons.apply(
            matchType = "isIn",
            value = JsonPrimitive(rawValue),
            testAgainst = JsonPrimitive(rawRule),
            negation = false,
        )
        assertEquals(expected, result, "isIn($rawValue, $rawRule)")
    }

    companion object {
        @JvmStatic
        fun existsMatrix(): Stream<Arguments> = Stream.of(
            // AC-4.2: present non-empty value
            Arguments.of("exists", JsonPrimitive("hello"), false, true),
            Arguments.of("exists", JsonPrimitive("hello"), true, false), // negated
            Arguments.of("doesNotExist", JsonPrimitive("hello"), false, false),
            Arguments.of("doesNotExist", JsonPrimitive("hello"), true, true), // negated
            // AC-4.2 edge: present empty string → counts as non-existent
            Arguments.of("exists", JsonPrimitive(""), false, false),
            Arguments.of("exists", JsonPrimitive(""), true, true), // negated
            Arguments.of("doesNotExist", JsonPrimitive(""), false, true),
            Arguments.of("doesNotExist", JsonPrimitive(""), true, false), // negated
            // AC-4.3: absent key → JsonNull — operator STILL dispatched (not short-circuited)
            Arguments.of("exists", JsonNull, false, false),
            Arguments.of("exists", JsonNull, true, true), // negated
            Arguments.of("doesNotExist", JsonNull, false, true),
            Arguments.of("doesNotExist", JsonNull, true, false), // negated
        )

        @JvmStatic
        fun isInCaseMatrix(): Stream<Arguments> = Stream.of(
            // AC-4.4: value-side uppercase, rule-side lowercase → matches (Android Direction B)
            Arguments.of("US", "us|ca", true),
            Arguments.of("CA", "us|ca", true),
            Arguments.of("DE", "us|ca", false),
            // Both sides mixed-case — still matches because both lowercased
            Arguments.of("United States", "united states|canada", true),
            // JS SDK would fail this (doesn't lowercase value side); Android passes
            Arguments.of("APPLE", "apple|banana", true),
        )
    }
}
