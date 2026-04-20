/*
 * Convert Android SDK — core/model tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

internal class StoreDataTest {

    @Test
    fun `store data round-trips with all sections populated`() {
        val original = StoreData(
            bucketing = mapOf("exp_a" to "var_1", "exp_b" to "var_2"),
            locations = listOf("home", "checkout"),
            segments = mapOf(
                "country" to JsonPrimitive("DE"),
                "premium" to JsonPrimitive(true),
            ),
            goals = mapOf("goal_x" to true, "goal_y" to false),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<StoreData>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `store data round-trips when all sections are null`() {
        val original = StoreData()

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<StoreData>(json)

        assertEquals(original, restored)
    }
}
