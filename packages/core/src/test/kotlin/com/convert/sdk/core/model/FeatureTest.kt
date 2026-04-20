/*
 * Convert Android SDK — core tests
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
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
    fun `feature serializes and deserializes symmetrically`() {
        val original = Feature(
            id = "feat-1",
            key = "dark-mode",
            name = "Dark Mode",
            status = FeatureStatus.ENABLED,
            variables = mapOf(
                "theme" to JsonPrimitive("midnight"),
                "contrast" to JsonPrimitive(4),
            ),
            experienceId = "exp-7",
            experienceKey = "dark-mode-test",
            experienceName = "Dark Mode Test",
        )
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<Feature>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `feature enabled convenience property reflects status`() {
        val enabled = Feature(status = FeatureStatus.ENABLED, id = "a")
        val disabled = Feature(status = FeatureStatus.DISABLED, id = "b")
        assertTrue(enabled.enabled)
        assertFalse(disabled.enabled)
    }

    @Test
    fun `feature status serializes as lowercase enabled and disabled`() {
        val enabled = testJson.encodeToString(Feature(status = FeatureStatus.ENABLED, id = "a"))
        val disabled = testJson.encodeToString(Feature(status = FeatureStatus.DISABLED, id = "b"))
        assertTrue(enabled.contains("\"status\":\"enabled\""), "got: $enabled")
        assertTrue(disabled.contains("\"status\":\"disabled\""), "got: $disabled")
    }
}
