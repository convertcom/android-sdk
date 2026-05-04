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

internal class VisitorEventTest {

    @Test
    fun `visitor event round-trips with segments and bucketing event`() {
        val original = VisitorEvent(
            visitorId = "visitor_abc",
            segments = mapOf(
                "country" to JsonPrimitive("US"),
                "plan" to JsonPrimitive("pro"),
            ),
            event = BucketingEvent(
                experienceId = "e1",
                variationId = "var_a",
            ),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<VisitorEvent>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `visitor event round-trips when segments is null`() {
        val original = VisitorEvent(
            visitorId = "v_1",
            event = ConversionEvent(
                goalId = "g1",
            ),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<VisitorEvent>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `visitor event round-trips conversion event with goalData`() {
        val original = VisitorEvent(
            visitorId = "v_2",
            event = ConversionEvent(
                goalId = "g2",
                goalData = listOf(
                    GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(49.99)),
                    GoalData(key = GoalDataKey.TRANSACTION_ID, value = JsonPrimitive("txn_abc")),
                ),
            ),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<VisitorEvent>(json)

        assertEquals(original, restored)
    }
}
