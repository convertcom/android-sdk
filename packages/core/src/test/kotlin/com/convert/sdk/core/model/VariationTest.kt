/*
 * Convert Android SDK — core/model tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
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
    fun `variation serializes and deserializes symmetrically with all fields`() {
        val original = Variation(
            id = "var_123",
            key = "treatment",
            name = "Treatment variant",
            experienceId = "exp_456",
            experienceKey = "checkout_copy",
            experienceName = "Checkout copy test",
            bucketingAllocation = 0.5,
            changes = listOf(
                buildJsonObject {
                    put("type", JsonPrimitive("dom"))
                    put("selector", JsonPrimitive(".btn"))
                },
            ),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<Variation>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `variation with only required fields round-trips`() {
        val original = Variation(id = "var_0", key = "control")

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<Variation>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `variation with all fields null round-trips`() {
        // Guards against regressions where Variation.id / Variation.key are
        // marked non-nullable — the JS SDK's ExperienceVariationConfig
        // declares them optional, so a partial backend response must
        // deserialise without throwing.
        val original = Variation()

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<Variation>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `variation serializes experience_id using snake_case`() {
        val v = Variation(id = "v", key = "k", experienceId = "exp_1")

        val json = testJson.encodeToString(v)

        assertTrue(
            json.contains("\"experience_id\":\"exp_1\""),
            "Expected snake_case experience_id in JSON, got: $json",
        )
    }
}
