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

internal class GoalDataTest {

    @Test
    fun `goal data serializes and deserializes symmetrically with a numeric value`() {
        val original = GoalData(
            key = GoalDataKey.AMOUNT,
            value = JsonPrimitive(42.5),
        )
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<GoalData>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `goal data keys serialize as camelCase not snake_case`() {
        val original = GoalData(
            key = GoalDataKey.PRODUCTS_COUNT,
            value = JsonPrimitive(2),
        )
        val encoded = testJson.encodeToString(original)
        assertTrue(
            encoded.contains("\"key\":\"productsCount\""),
            "expected camelCase productsCount; got: $encoded",
        )
        assertFalse(
            encoded.contains("products_count"),
            "encoding must not contain snake_case; got: $encoded",
        )
    }

    @Test
    fun `goal data round-trips every enum value using the camelCase JSON form`() {
        val expected = mapOf(
            GoalDataKey.AMOUNT to "amount",
            GoalDataKey.PRODUCTS_COUNT to "productsCount",
            GoalDataKey.TRANSACTION_ID to "transactionId",
            GoalDataKey.CUSTOM_DIMENSION_1 to "customDimension1",
            GoalDataKey.CUSTOM_DIMENSION_2 to "customDimension2",
            GoalDataKey.CUSTOM_DIMENSION_3 to "customDimension3",
            GoalDataKey.CUSTOM_DIMENSION_4 to "customDimension4",
            GoalDataKey.CUSTOM_DIMENSION_5 to "customDimension5",
        )
        expected.forEach { (key, jsonName) ->
            val encoded = testJson.encodeToString(GoalData(key = key, value = JsonPrimitive("x")))
            assertTrue(
                encoded.contains("\"key\":\"$jsonName\""),
                "expected $jsonName for $key; got: $encoded",
            )
            val decoded = testJson.decodeFromString<GoalData>(encoded)
            assertEquals(key, decoded.key)
        }
    }
}
