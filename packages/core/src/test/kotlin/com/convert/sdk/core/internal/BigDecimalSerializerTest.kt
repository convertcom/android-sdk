/*
 * Convert Android SDK — core/internal tests
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.internal

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * Story 2.2 AC-12 (F-172) — direct-Json reproducer tests for
 * [BigDecimalSerializer] and [bigDecimalSerializersModule].
 *
 * The OpenAPI-generated config types annotate every arbitrary-precision
 * numeric field as `@Contextual val x: java.math.BigDecimal?` (e.g.
 * [com.convert.sdk.core.model.generated.ConfigProjectSettings.minOrderValue]
 * and `maxOrderValue`). For this to round-trip through
 * `kotlinx.serialization.json.Json`, the Json instance MUST register a
 * contextual serializer for [BigDecimal] on its `serializersModule`.
 * Without that registration, `encodeToString` throws
 * `kotlinx.serialization.SerializationException: Serializer for class
 * 'BigDecimal' is not found` — the F-172 production failure mode.
 *
 * These tests exercise the serializer with a tiny single-field carrier
 * (decoupled from any generated type), so the smallest possible
 * reproducer of the underlying regression class lives in core and runs
 * on JVM without Robolectric.
 */
internal class BigDecimalSerializerTest {

    @Serializable
    private data class BigDecimalCarrier(
        @Contextual val value: BigDecimal,
    )

    private val jsonWithModule: Json = Json {
        serializersModule = bigDecimalSerializersModule
    }

    private val jsonWithoutModule: Json = Json { }

    @Test
    fun `Json with bigDecimalSerializersModule encodes a Contextual BigDecimal field without throwing`() {
        val carrier = BigDecimalCarrier(value = BigDecimal("12.345"))

        val encoded = jsonWithModule.encodeToString(BigDecimalCarrier.serializer(), carrier)

        // Encoded form should contain the unquoted numeric literal —
        // BigDecimalSerializer writes via JsonPrimitive(BigDecimal) which
        // emits a JSON number, NOT a JSON string. The JSON shape is
        // `{"value":12.345}`.
        assertTrue(
            encoded.contains("\"value\":12.345"),
            "encoded JSON should carry the BigDecimal as a numeric literal; " +
                "got: $encoded",
        )
    }

    @Test
    fun `Json with bigDecimalSerializersModule round-trips a Contextual BigDecimal under compareTo`() {
        val expected = BigDecimal("0.0001234567890123456789")
        val carrier = BigDecimalCarrier(value = expected)

        val encoded = jsonWithModule.encodeToString(BigDecimalCarrier.serializer(), carrier)
        val decoded = jsonWithModule.decodeFromString(BigDecimalCarrier.serializer(), encoded)

        // compareTo (rather than equals) — BigDecimal.equals discriminates
        // on scale; compareTo compares numeric value only.
        assertEquals(
            0,
            decoded.value.compareTo(expected),
            "round-tripped BigDecimal must be numerically equal " +
                "(compareTo == 0); expected=$expected, got=${decoded.value}, " +
                "encoded=$encoded",
        )
    }

    @Test
    fun `Json without bigDecimalSerializersModule throws SerializationException on encode (F-172 reproducer)`() {
        val carrier = BigDecimalCarrier(value = BigDecimal("12.345"))

        // Locks down the F-172 contract: encoding a @Contextual BigDecimal
        // without a registered serializer must throw, NOT silently succeed.
        // The test asserts only the exception TYPE — the precise message
        // text is owned by kotlinx-serialization and may evolve.
        val thrown = assertThrows(SerializationException::class.java) {
            jsonWithoutModule.encodeToString(BigDecimalCarrier.serializer(), carrier)
        }
        assertTrue(
            thrown.message?.contains("BigDecimal", ignoreCase = true) == true,
            "expected SerializationException naming BigDecimal; got: ${thrown.message}",
        )
    }
}
