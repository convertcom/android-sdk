/*
 * Convert Android SDK Demo App — Feature variable type detection
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Story 7.4 Task 2 / AC-2 / AC-4 — resolves the user-facing type label
 * for a [JsonElement] returned in a [com.convert.sdk.core.model.Feature]'s
 * `variables` map.
 *
 * The label string is the raw type name (no brackets) so callers can
 * compose it into whatever surface the UI needs (the [FeaturesScreen]
 * wraps it in `[...]` for display; tests can assert on the raw label
 * without fragile substring matching).
 *
 * ### Strict-first ordering
 *
 * The dispatch ladder ORDERS MATTER:
 *
 *  1. `isString` — a string-typed primitive ALWAYS wins, even if its
 *     content happens to be the literal `"true"` / `"123"` that the
 *     boolean/numeric coercers would otherwise match. This preserves
 *     author intent (kotlinx-serialization stores the `isString` flag
 *     on every primitive).
 *  2. `booleanOrNull` — a true boolean-typed primitive.
 *  3. `longOrNull` — any integer-typed primitive (Gotcha 1: `Long` also
 *     resolves through `longOrNull`; the user-facing label `Int` covers
 *     both since users don't care about the 32/64-bit distinction for
 *     feature-variable display).
 *  4. `doubleOrNull` — any floating-point primitive (Gotcha 2:
 *     kotlinx-serialization deserialises `Float` as `Double`; one label
 *     covers both).
 *  5. Everything else (JsonNull, JsonObject, JsonArray, or a number
 *     primitive that failed every parse attempt) → `"Unknown"`.
 */
public fun JsonElement.typeLabel(): String = when {
    this !is JsonPrimitive -> "Unknown"
    isString -> "String"
    booleanOrNull != null -> "Boolean"
    longOrNull != null -> "Int"
    doubleOrNull != null -> "Double"
    else -> "Unknown"
}

/**
 * Story 7.4 — a single feature-variable row ready for [FeaturesScreen]
 * rendering.
 *
 * @property name the variable's key in the Feature's `variables` map.
 * @property value the display string. Strings are double-quoted
 *   (`"blue"`) to preserve type visibility at a glance; numbers and
 *   booleans render unquoted (`3`, `true`). Mirrors the concrete AC-2
 *   examples exactly.
 * @property typeLabel one of `"String"`, `"Int"`, `"Double"`,
 *   `"Boolean"`, `"Unknown"`. The rendering layer wraps this in
 *   `[...]` and applies `labelMedium` + `outline` color (AC-4).
 */
public data class TypedVariable(
    val name: String,
    val value: String,
    val typeLabel: String,
) {
    public companion object {

        /**
         * Builds a [TypedVariable] from a `(name, JsonElement)` pair.
         *
         * Value formatting follows AC-2 exactly:
         *  - String primitives → `"content"` (double-quoted, content preserved).
         *  - Non-string primitives → raw `content` (numeric precision and
         *    boolean spelling preserved — `3` not `3.0`, `true` not `True`).
         *  - Non-primitive shapes → `element.toString()` fallback so the
         *    user at least sees something; [typeLabel] is `"Unknown"`.
         */
        public fun fromJson(name: String, element: JsonElement): TypedVariable {
            val label = element.typeLabel()
            val value: String = when {
                element !is JsonPrimitive -> element.toString()
                element.isString -> "\"${element.content}\""
                else -> element.content
            }
            return TypedVariable(name = name, value = value, typeLabel = label)
        }
    }
}
