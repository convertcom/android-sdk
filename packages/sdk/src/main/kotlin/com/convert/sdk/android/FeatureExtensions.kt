/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
@file:JvmName("FeatureExtensions")

package com.convert.sdk.android

import com.convert.sdk.core.model.Feature
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Typed-variable accessor extension functions for [Feature] — Story 4.1
 * AC-4.
 *
 * These extensions are syntactic sugar for Kotlin consumers — Java
 * consumers can either call them via the `FeatureExtensions` static
 * facade (thanks to the `@file:JvmName`) or read the [Feature.variables]
 * map directly and cast the [kotlinx.serialization.json.JsonPrimitive]
 * values themselves.
 *
 * ### Coercion semantics (strict-then-loose)
 *
 * Each accessor first tries the strict primitive path — e.g. `getInt`
 * calls `intOrNull` on a [JsonPrimitive] whose `isString == false`. When
 * that returns `null`, the accessor falls back to `content?.toXxxOrNull()`
 * so string-typed values carrying numbers or booleans still resolve.
 * This matches the JS SDK's `castType` utility
 * (`javascript-sdk/packages/utils/src/data.ts`) which uses `Number(value)`
 * / `Boolean(value)`-style coercion.
 *
 * ### Null propagation
 *
 *  - `Feature.variables == null` (DISABLED feature) → every accessor returns `null`.
 *  - Key absent from the map → `null`.
 *  - Value is not a [JsonPrimitive] (nested object / array / JsonNull) → `null`.
 *  - Value's `content` cannot be coerced to the target type → `null`.
 *
 * None of these paths throw — consumers are expected to chain `?.` and
 * default values (`feature.getString("color") ?: "default"`).
 */

/**
 * Returns the string content of the variable named [key], or `null` when
 * the variable is missing, the feature is disabled, or the value is not
 * a [JsonPrimitive] string-or-number primitive.
 *
 * Accepts string-typed primitives (`"blue"`) and returns their content
 * unmodified. Returns `null` for JsonNull, arrays, nested objects, and
 * absent keys.
 */
public fun Feature.getString(key: String): String? {
    val primitive = variables?.get(key) as? JsonPrimitive ?: return null
    // contentOrNull returns null ONLY for JsonNull — string and numeric
    // primitives both resolve to their string content, which matches
    // JS SDK's String(value) coercion.
    return primitive.contentOrNull
}

/**
 * Returns the integer value of the variable named [key], or `null` when
 * coercion fails.
 *
 * Tries strict [intOrNull] first (non-string primitive), then falls back
 * to `content?.toIntOrNull()` so numeric strings (`"7"`) resolve. Floats
 * (`0.5`) return `null` — callers wanting truncation semantics should
 * call [getDouble] and truncate themselves.
 */
public fun Feature.getInt(key: String): Int? {
    val primitive = variables?.get(key) as? JsonPrimitive ?: return null
    return primitive.intOrNull ?: primitive.contentOrNull?.toIntOrNull()
}

/**
 * Returns the double value of the variable named [key], or `null` when
 * coercion fails.
 *
 * Tries strict [doubleOrNull] first, then falls back to
 * `content?.toDoubleOrNull()` so numeric strings (`"1.25"`) resolve.
 */
public fun Feature.getDouble(key: String): Double? {
    val primitive = variables?.get(key) as? JsonPrimitive ?: return null
    return primitive.doubleOrNull ?: primitive.contentOrNull?.toDoubleOrNull()
}

/**
 * Returns the boolean value of the variable named [key], or `null` when
 * coercion fails.
 *
 * Tries strict [booleanOrNull] first, then case-insensitive string
 * matching against `"true"` / `"false"` (the only two recognised
 * spellings — `"yes"` / `"1"` / `"on"` do NOT resolve, matching the JS
 * SDK's strict `Boolean(value)` behaviour against boolean-typed
 * variables).
 */
@Suppress("ReturnCount")
public fun Feature.getBoolean(key: String): Boolean? {
    // Three exits: missing/wrong shape (null), strict primitive
    // (booleanOrNull wins), string fallback (lowercase match). A
    // single-expression form obscures the strict-then-loose policy.
    val primitive = variables?.get(key) as? JsonPrimitive ?: return null
    primitive.booleanOrNull?.let { return it }
    return when (primitive.contentOrNull?.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}
