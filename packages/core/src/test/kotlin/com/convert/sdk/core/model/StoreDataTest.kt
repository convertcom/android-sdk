/*
 * Convert Android SDK — core tests
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class StoreDataTest {

    @Test
    fun `store data serializes and deserializes symmetrically`() {
        val original = StoreData(
            bucketing = mapOf("home-hero" to "var-a"),
            locations = listOf("home", "checkout"),
            segments = mapOf("browser" to JsonPrimitive("chrome")),
            goals = mapOf("goal-1" to true, "goal-2" to false),
        )
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<StoreData>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `store data round-trips when all fields are null`() {
        val original = StoreData()
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<StoreData>(encoded)
        assertEquals(original, decoded)
    }
}
