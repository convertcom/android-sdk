/*
 * Convert Android SDK Demo App — Feature variable type detection
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
 * ### JS SDK canonical type vocabulary
 *
 * Labels match the JS SDK's `VARIABLE_TYPES` enum verbatim
 * (`@convertcom/js-sdk-enums/variable-types.ts`): `"boolean"`, `"float"`,
 * `"json"`, `"integer"`, `"string"` — lowercase, no Kotlin-idiomatic
 * casing. Developers reading the demo alongside JS SDK docs see the
 * same vocabulary in both. JSON object/array values surface as
 * `"json"` (a valid known type — never `"unknown"`); only `JsonNull`
 * and number-primitives that fail every parse attempt fall back to
 * `"unknown"`. Per F-030 remediation in story 7.4.
 *
 * ### Strict-first ordering
 *
 * The dispatch ladder ORDER MATTERS:
 *
 *  1. `JsonObject` / `JsonArray` → `"json"` (a JSON-typed variable is a
 *     known type, not unknown).
 *  2. `isString` — a string-typed primitive ALWAYS wins, even if its
 *     content happens to be the literal `"true"` / `"123"` that the
 *     boolean/numeric coercers would otherwise match. This preserves
 *     author intent (kotlinx-serialization stores the `isString` flag
 *     on every primitive).
 *  3. `booleanOrNull` — a true boolean-typed primitive.
 *  4. `longOrNull` — any integer-typed primitive (Gotcha 1: `Long` also
 *     resolves through `longOrNull`; the user-facing label `"integer"`
 *     covers both since users don't care about the 32/64-bit distinction
 *     for feature-variable display).
 *  5. `doubleOrNull` — any floating-point primitive (Gotcha 2:
 *     kotlinx-serialization deserialises Kotlin `Float` as `Double`;
 *     one label `"float"` covers both, matching the JS SDK's `'float'`
 *     canonical name).
 *  6. Everything else (`JsonNull`, or a primitive that failed every
 *     parse attempt) → `"unknown"`.
 */
public fun JsonElement.typeLabel(): String = when {
    this is JsonObject -> "json"
    this is JsonArray -> "json"
    this !is JsonPrimitive -> "unknown"
    isString -> "string"
    booleanOrNull != null -> "boolean"
    longOrNull != null -> "integer"
    doubleOrNull != null -> "float"
    else -> "unknown"
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
 * @property typeLabel one of the JS SDK canonical lowercase type names —
 *   `"string"`, `"integer"`, `"float"`, `"boolean"`, `"json"`,
 *   `"unknown"`. The rendering layer wraps this in `[...]` and applies
 *   `labelMedium` + `outline` color (AC-4).
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
         *  - Non-primitive shapes (`JsonObject` / `JsonArray`) →
         *    `element.toString()` so the user at least sees something;
         *    [typeLabel] is `"json"`. `JsonNull` falls into the same
         *    branch with [typeLabel] `"unknown"`.
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
