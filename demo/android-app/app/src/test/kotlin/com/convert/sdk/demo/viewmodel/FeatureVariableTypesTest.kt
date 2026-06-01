/*
 * Convert Android SDK Demo App — Feature variable type-detection tests (Story 7.4)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Story 7.4 AC-2 / AC-4 / Task 2 — [JsonElement.typeLabel] returns the
 * JS SDK canonical lowercase labels (`"string"`, `"integer"`, `"float"`,
 * `"boolean"`, `"json"`) plus `"unknown"` for `JsonNull` / failed parses.
 * Per F-030 remediation: labels mirror the JS SDK's `VARIABLE_TYPES`
 * enum verbatim so demo output reads with the same vocabulary as
 * backend config and JS SDK docs.
 *
 * The detector is strict-first: it reads `isString` before `booleanOrNull`
 * because `booleanOrNull` returns non-null for the string literals `"true"`
 * and `"false"` (kotlinx-serialization parses them permissively). A
 * string primitive like `"true"` must render as `[string]`, not
 * `[boolean]`.
 */
class FeatureVariableTypesTest {

    @Test
    fun `string primitive returns string label`() {
        assertEquals("string", JsonPrimitive("blue").typeLabel())
    }

    @Test
    fun `stringy true returns string label not boolean`() {
        // isString takes precedence — the value was authored as a string.
        assertEquals("string", JsonPrimitive("true").typeLabel())
    }

    @Test
    fun `boolean primitive returns boolean label`() {
        assertEquals("boolean", JsonPrimitive(true).typeLabel())
        assertEquals("boolean", JsonPrimitive(false).typeLabel())
    }

    @Test
    fun `integer primitive returns integer label`() {
        assertEquals("integer", JsonPrimitive(3).typeLabel())
        assertEquals("integer", JsonPrimitive(-7).typeLabel())
    }

    @Test
    fun `long primitive still returns integer label (users do not care)`() {
        // Gotcha 1: longOrNull resolves both Int and Long — one label covers both.
        assertEquals("integer", JsonPrimitive(9_999_999_999L).typeLabel())
    }

    @Test
    fun `double primitive returns float label`() {
        assertEquals("float", JsonPrimitive(0.15).typeLabel())
    }

    @Test
    fun `whole-number double primitive returns float label not integer (F-110)`() {
        // F-110 boundary: a JSON number authored as 1.0 (a Double with no
        // fractional part) MUST render as "float" — not "integer".
        // kotlinx-serialization stores the content "1.0", which makes
        // longOrNull return null (because of the decimal); doubleOrNull
        // therefore wins. Pin this so any future ordering change that
        // accidentally regresses it (e.g. swapping to a coercion that
        // strips trailing zeros) is caught immediately.
        assertEquals("float", JsonPrimitive(1.0).typeLabel())
        assertEquals("float", JsonPrimitive(2.0).typeLabel())
        assertEquals("float", JsonPrimitive(-3.0).typeLabel())
    }

    @Test
    fun `json null returns unknown`() {
        assertEquals("unknown", JsonNull.typeLabel())
    }

    @Test
    fun `json object returns json (a known type, not unknown)`() {
        // F-030: JSON object/array are valid known types per the JS SDK's
        // VARIABLE_TYPES enum — they must NOT fall into the "unknown" bucket.
        assertEquals("json", JsonObject(emptyMap()).typeLabel())
    }

    @Test
    fun `json array returns json (a known type, not unknown)`() {
        assertEquals("json", JsonArray(emptyList()).typeLabel())
    }

    // ----- TypedVariable formatting contract ---------------------------

    @Test
    fun `TypedVariable from string JsonPrimitive wraps value in quotes`() {
        val tv = TypedVariable.fromJson(name = "buttonColor", element = JsonPrimitive("blue"))
        assertEquals("buttonColor", tv.name)
        assertEquals("\"blue\"", tv.value)
        assertEquals("string", tv.typeLabel)
    }

    @Test
    fun `TypedVariable from integer JsonPrimitive renders unquoted`() {
        val tv = TypedVariable.fromJson(name = "maxRetries", element = JsonPrimitive(3))
        assertEquals("3", tv.value)
        assertEquals("integer", tv.typeLabel)
    }

    @Test
    fun `TypedVariable from boolean JsonPrimitive renders unquoted`() {
        val tv = TypedVariable.fromJson(name = "showBanner", element = JsonPrimitive(true))
        assertEquals("true", tv.value)
        assertEquals("boolean", tv.typeLabel)
    }

    @Test
    fun `TypedVariable from double JsonPrimitive renders unquoted`() {
        val tv = TypedVariable.fromJson(name = "discountFactor", element = JsonPrimitive(0.15))
        assertEquals("0.15", tv.value)
        assertEquals("float", tv.typeLabel)
    }

    @Test
    fun `TypedVariable from JsonObject renders value as raw content with json type`() {
        val tv = TypedVariable.fromJson(name = "config", element = JsonObject(emptyMap()))
        // Fallback: use the element's toString so the user sees SOMETHING — never crash.
        assertEquals("json", tv.typeLabel)
    }
}
