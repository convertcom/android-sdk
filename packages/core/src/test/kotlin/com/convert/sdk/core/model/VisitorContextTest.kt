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

internal class VisitorContextTest {

    @Test
    fun `visitor context round-trips with all fields`() {
        val original = VisitorContext(
            visitorId = "visitor_xyz",
            attributes = mapOf("age" to JsonPrimitive(34)),
            locationProperties = mapOf("path" to JsonPrimitive("/checkout")),
            defaultSegments = mapOf("country" to "DE"),
            customSegments = mapOf("loyalty" to JsonPrimitive("gold")),
        )

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<VisitorContext>(json)

        assertEquals(original, restored)
    }

    @Test
    fun `visitor context round-trips with only required fields`() {
        val original = VisitorContext(visitorId = "v_min")

        val json = testJson.encodeToString(original)
        val restored = testJson.decodeFromString<VisitorContext>(json)

        assertEquals(original, restored)
    }
}
