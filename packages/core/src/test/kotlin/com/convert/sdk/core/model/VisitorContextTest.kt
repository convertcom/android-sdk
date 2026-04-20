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

internal class VisitorContextTest {

    @Test
    fun `visitor context serializes and deserializes symmetrically`() {
        val original = VisitorContext(
            visitorId = "visitor-42",
            attributes = mapOf("plan" to JsonPrimitive("pro")),
            locationProperties = mapOf("path" to JsonPrimitive("/home")),
            defaultSegments = mapOf("browser" to "chrome"),
            customSegments = mapOf("role" to JsonPrimitive("admin")),
        )
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<VisitorContext>(encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `visitor context round-trips when optional fields are null`() {
        val original = VisitorContext(visitorId = "visitor-42")
        val encoded = testJson.encodeToString(original)
        val decoded = testJson.decodeFromString<VisitorContext>(encoded)
        assertEquals(original, decoded)
    }
}
