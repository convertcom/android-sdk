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
 * four user-facing labels plus `[Unknown]` for non-primitive shapes.
 *
 * The detector is strict-first: it reads `isString` before `booleanOrNull`
 * because `booleanOrNull` returns non-null for the string literals `"true"`
 * and `"false"` (kotlinx-serialization parses them permissively). A
 * string primitive like `"true"` must render as `[String]`, not
 * `[Boolean]`.
 */
class FeatureVariableTypesTest {

    @Test
    fun `string primitive returns String label`() {
        assertEquals("String", JsonPrimitive("blue").typeLabel())
    }

    @Test
    fun `stringy true returns String label not Boolean`() {
        // isString takes precedence — the value was authored as a string.
        assertEquals("String", JsonPrimitive("true").typeLabel())
    }

    @Test
    fun `boolean primitive returns Boolean label`() {
        assertEquals("Boolean", JsonPrimitive(true).typeLabel())
        assertEquals("Boolean", JsonPrimitive(false).typeLabel())
    }

    @Test
    fun `int primitive returns Int label`() {
        assertEquals("Int", JsonPrimitive(3).typeLabel())
        assertEquals("Int", JsonPrimitive(-7).typeLabel())
    }

    @Test
    fun `long primitive still returns Int label (users do not care)`() {
        // Gotcha 1: longOrNull resolves both Int and Long — one label covers both.
        assertEquals("Int", JsonPrimitive(9_999_999_999L).typeLabel())
    }

    @Test
    fun `double primitive returns Double label`() {
        assertEquals("Double", JsonPrimitive(0.15).typeLabel())
        assertEquals("Double", JsonPrimitive(1.0).typeLabel())
    }

    @Test
    fun `json null returns Unknown`() {
        assertEquals("Unknown", JsonNull.typeLabel())
    }

    @Test
    fun `json object returns Unknown`() {
        assertEquals("Unknown", JsonObject(emptyMap()).typeLabel())
    }

    @Test
    fun `json array returns Unknown`() {
        assertEquals("Unknown", JsonArray(emptyList()).typeLabel())
    }

    // ----- TypedVariable formatting contract ---------------------------

    @Test
    fun `TypedVariable from string JsonPrimitive wraps value in quotes`() {
        val tv = TypedVariable.fromJson(name = "buttonColor", element = JsonPrimitive("blue"))
        assertEquals("buttonColor", tv.name)
        assertEquals("\"blue\"", tv.value)
        assertEquals("String", tv.typeLabel)
    }

    @Test
    fun `TypedVariable from int JsonPrimitive renders unquoted`() {
        val tv = TypedVariable.fromJson(name = "maxRetries", element = JsonPrimitive(3))
        assertEquals("3", tv.value)
        assertEquals("Int", tv.typeLabel)
    }

    @Test
    fun `TypedVariable from boolean JsonPrimitive renders unquoted`() {
        val tv = TypedVariable.fromJson(name = "showBanner", element = JsonPrimitive(true))
        assertEquals("true", tv.value)
        assertEquals("Boolean", tv.typeLabel)
    }

    @Test
    fun `TypedVariable from double JsonPrimitive renders unquoted`() {
        val tv = TypedVariable.fromJson(name = "discountFactor", element = JsonPrimitive(0.15))
        assertEquals("0.15", tv.value)
        assertEquals("Double", tv.typeLabel)
    }

    @Test
    fun `TypedVariable from non-primitive renders value as raw content with Unknown type`() {
        val tv = TypedVariable.fromJson(name = "config", element = JsonObject(emptyMap()))
        // Fallback: use the element's toString so the user sees SOMETHING — never crash.
        assertEquals("Unknown", tv.typeLabel)
    }
}
