/*
 * Convert Android SDK — core/model tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class FeatureTest {

    @Test
    fun `feature round-trips with all fields populated`() {
        val original = Feature(
            id = "feat_1",
            key = "new_checkout",
            name = "New checkout",
            status = FeatureStatus.ENABLED,
            variables = mapOf(
                "color" to JsonPrimitive("red"),
                "count" to JsonPrimitive(3),
            ),
            experienceId = "exp_7",
            experienceKey = "checkout_exp",
            experienceName = "Checkout experiment",
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<Feature>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `feature enabled computed property is true when status is ENABLED`() {
        val f = Feature(key = "k", status = FeatureStatus.ENABLED)

        assertTrue(f.enabled)
    }

    @Test
    fun `feature enabled computed property is false when status is DISABLED`() {
        val f = Feature(key = "k", status = FeatureStatus.DISABLED)

        assertFalse(f.enabled)
    }

    @Test
    fun `feature serializes status as lowercase string`() {
        val f = Feature(key = "k", status = FeatureStatus.ENABLED)

        val json = testJson.encodeToString(f)

        assertTrue(json.contains("\"status\":\"enabled\""), "Got: $json")
    }
}
