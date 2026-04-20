/*
 * Convert Android SDK — core tests
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class VariationTest {

    @Test
    fun `variation serializes and deserializes symmetrically with all fields populated`() {
        val original = Variation(
            id = "var-1",
            key = "variation-a",
            name = "Variation A",
            experienceId = "exp-1",
            experienceKey = "home-hero",
            experienceName = "Home Hero",
            bucketingAllocation = 5000.0,
            changes = listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("text"))
                    put("value", JsonPrimitive("Hello"))
                },
            ),
        )
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<Variation>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `variation serializes experience fields as snake_case`() {
        val original = Variation(
            id = "var-1",
            key = "variation-a",
            experienceId = "exp-1",
            experienceKey = "home-hero",
            experienceName = "Home Hero",
            bucketingAllocation = 2500.0,
        )
        val encoded = testJson.encodeToString(original)
        assertTrue(encoded.contains("\"experience_id\":\"exp-1\""), "got: $encoded")
        assertTrue(encoded.contains("\"experience_key\":\"home-hero\""), "got: $encoded")
        assertTrue(encoded.contains("\"experience_name\":\"Home Hero\""), "got: $encoded")
        assertTrue(encoded.contains("\"bucketing_allocation\":2500.0"), "got: $encoded")
    }

    @Test
    fun `variation round-trips with only required fields`() {
        val original = Variation(id = "var-2", key = "variation-b")
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<Variation>(encoded)
        assertEquals(original, decoded)
    }
}
