/*
 * Convert Android SDK — core/model tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

internal class GoalDataTest {

    @Test
    fun `goal data round-trips with numeric value`() {
        val original = GoalData(
            key = GoalDataKey.AMOUNT,
            value = JsonPrimitive(42.5),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<GoalData>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `goal data round-trips with string value`() {
        val original = GoalData(
            key = GoalDataKey.TRANSACTION_ID,
            value = JsonPrimitive("tx_abcdef"),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<GoalData>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `goal data serializes PRODUCTS_COUNT as camelCase productsCount`() {
        val gd = GoalData(
            key = GoalDataKey.PRODUCTS_COUNT,
            value = JsonPrimitive(2),
        )

        val json = testJson.encodeToString(gd)

        // Locks in the JS-SDK-compatible camelCase mapping for goal data keys
        // (NOT snake_case — see GoalDataKey KDoc and Story 1.2 Gotcha 2).
        assertTrue(
            json.contains("\"key\":\"productsCount\""),
            "Expected camelCase 'productsCount' in JSON, got: $json",
        )
    }

    @Test
    fun `goal data serializes custom dimensions as camelCase`() {
        val gd = GoalData(
            key = GoalDataKey.CUSTOM_DIMENSION_3,
            value = JsonPrimitive("segment-a"),
        )

        val json = testJson.encodeToString(gd)

        assertTrue(
            json.contains("\"key\":\"customDimension3\""),
            "Expected camelCase 'customDimension3' in JSON, got: $json",
        )
    }
}
