/*
 * Convert Android SDK — sdk tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.FeatureStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure-JVM tests for the typed-variable extension helpers declared in
 * [FeatureExtensions]. Coverage per readiness Q5 (strict-then-loose
 * coercion):
 *  - Primitive-typed values extracted via `intOrNull` / `doubleOrNull` /
 *    `booleanOrNull` / `contentOrNull` first.
 *  - String-carried numerics / booleans fall back to `content?.toXxxOrNull()`.
 *  - Missing key, null variables map (disabled Feature), and non-primitive
 *    shapes (arrays / objects / JsonNull) all resolve to `null` without
 *    throwing.
 */
internal class FeatureExtensionsTest {

    private fun featureOf(vararg pairs: Pair<String, JsonElement>): Feature =
        Feature(
            id = "f-1",
            key = "k",
            name = "Name",
            status = FeatureStatus.ENABLED,
            variables = mapOf(*pairs),
        )

    private fun disabledFeature(): Feature = Feature(
        id = "f-1",
        key = "k",
        name = "Name",
        status = FeatureStatus.DISABLED,
        variables = null,
    )

    // --- getString --------------------------------------------------------

    @Test
    fun `getString returns content for JsonPrimitive string`() {
        val feature = featureOf("color" to JsonPrimitive("blue"))
        assertEquals("blue", feature.getString("color"))
    }

    @Test
    fun `getString returns null for missing key`() {
        val feature = featureOf("color" to JsonPrimitive("blue"))
        assertNull(feature.getString("missing"))
    }

    @Test
    fun `getString returns null when variables is null (disabled feature)`() {
        assertNull(disabledFeature().getString("color"))
    }

    @Test
    fun `getString returns null for non-primitive value`() {
        val feature = featureOf("nested" to JsonArray(listOf(JsonPrimitive(1))))
        assertNull(feature.getString("nested"))
    }

    @Test
    fun `getString returns null for JsonNull`() {
        val feature = featureOf("absent" to JsonNull)
        assertNull(feature.getString("absent"))
    }

    // --- getInt -----------------------------------------------------------

    @Test
    fun `getInt returns Int for JsonPrimitive number`() {
        val feature = featureOf("count" to JsonPrimitive(42))
        assertEquals(42, feature.getInt("count"))
    }

    @Test
    fun `getInt returns Int for numeric string (loose coercion)`() {
        val feature = featureOf("count" to JsonPrimitive("7"))
        assertEquals(7, feature.getInt("count"))
    }

    @Test
    fun `getInt returns null for non-numeric value`() {
        val feature = featureOf("count" to JsonPrimitive("abc"))
        assertNull(feature.getInt("count"))
    }

    @Test
    fun `getInt returns null when variables null`() {
        assertNull(disabledFeature().getInt("count"))
    }

    // --- getDouble --------------------------------------------------------

    @Test
    fun `getDouble returns Double for JsonPrimitive float`() {
        val feature = featureOf("ratio" to JsonPrimitive(0.5))
        assertEquals(0.5, feature.getDouble("ratio") ?: Double.NaN, 0.0001)
    }

    @Test
    fun `getDouble returns Double for numeric string`() {
        val feature = featureOf("ratio" to JsonPrimitive("1.25"))
        assertEquals(1.25, feature.getDouble("ratio") ?: Double.NaN, 0.0001)
    }

    @Test
    fun `getDouble returns null for non-numeric value`() {
        val feature = featureOf("ratio" to JsonPrimitive("nope"))
        assertNull(feature.getDouble("ratio"))
    }

    // --- getBoolean -------------------------------------------------------

    @Test
    fun `getBoolean returns Boolean for JsonPrimitive boolean`() {
        val feature = featureOf("flag" to JsonPrimitive(true))
        assertEquals(true, feature.getBoolean("flag"))
    }

    @Test
    fun `getBoolean accepts lowercase true string`() {
        val feature = featureOf("flag" to JsonPrimitive("true"))
        assertEquals(true, feature.getBoolean("flag"))
    }

    @Test
    fun `getBoolean accepts uppercase true string`() {
        val feature = featureOf("flag" to JsonPrimitive("TRUE"))
        assertEquals(true, feature.getBoolean("flag"))
    }

    @Test
    fun `getBoolean accepts mixed case false string`() {
        val feature = featureOf("flag" to JsonPrimitive("False"))
        assertEquals(false, feature.getBoolean("flag"))
    }

    @Test
    fun `getBoolean returns null for non-boolean value`() {
        val feature = featureOf("flag" to JsonPrimitive("yes"))
        assertNull(feature.getBoolean("flag"))
    }

    @Test
    fun `getBoolean returns null when variables null`() {
        assertNull(disabledFeature().getBoolean("flag"))
    }
}
