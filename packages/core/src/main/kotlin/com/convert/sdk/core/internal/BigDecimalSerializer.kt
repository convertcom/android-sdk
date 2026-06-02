/*
 * Convert Android SDK — core/internal
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.convert.sdk.core.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonUnquotedLiteral
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import java.math.BigDecimal

/**
 * [KSerializer] for [java.math.BigDecimal]. Reads a JSON number primitive
 * (or numeric string for loose parity with JavaScript's `Number(x)`
 * coercion) into a [BigDecimal] via `BigDecimal(String)`; writes a
 * [BigDecimal] back out as the raw unquoted numeric literal via
 * [JsonPrimitive]'s number constructor.
 *
 * Registered contextually because the OpenAPI-generated model types
 * annotate numeric fields with arbitrary precision as
 * `@Contextual val x: java.math.BigDecimal?` — for example
 * [com.convert.sdk.core.model.generated.ConfigProjectSettings.minOrderValue]
 * and `maxOrderValue`. Without a registered contextual serializer for
 * [BigDecimal], encoding any config payload carrying one of these
 * fields throws `kotlinx.serialization.SerializationException:
 * Serializer for class 'BigDecimal' is not found` (Story 2.2 AC-12,
 * F-172 — the runtime evidence is captured in the F-172 finding-history
 * block in `_bmad-output/implementation-artifacts/.../2-2-config-fetching-and-local-caching.md`).
 *
 * `BigDecimal(String)` accepts scientific notation, plain decimals, and
 * integers. A non-numeric string throws `NumberFormatException`, which
 * kotlinx-serialization surfaces as a decode failure (correct — the
 * config wire is malformed).
 *
 * @see bigDecimalSerializersModule
 */
internal object BigDecimalSerializer : KSerializer<BigDecimal> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error(
                "BigDecimalSerializer only supports JSON decoding; " +
                    "got ${decoder::class}",
            )
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: error("BigDecimal expected JSON primitive, got $element")
        return BigDecimal(primitive.content)
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error(
                "BigDecimalSerializer only supports JSON encoding; " +
                    "got ${encoder::class}",
            )
        // Use JsonUnquotedLiteral with toPlainString so that arbitrary-
        // precision BigDecimal values round-trip without floating-point
        // loss. Going via JsonPrimitive(Number) path passes through a
        // Double in some kotlinx-serialization paths and silently
        // truncates to ~17 significant digits; toPlainString writes the
        // decimal representation verbatim and JsonUnquotedLiteral emits
        // it as a JSON number (no quotes), preserving server-side scale
        // and precision.
        jsonEncoder.encodeJsonElement(JsonUnquotedLiteral(value.toPlainString()))
    }
}

/**
 * [SerializersModule] registering [BigDecimalSerializer] as the contextual
 * serializer for [java.math.BigDecimal].
 *
 * Installed on the SDK's shared [kotlinx.serialization.json.Json]
 * instance (the `sharedJson` block in
 * [com.convert.sdk.android.ConvertSDK.Builder.build]) so every encode
 * and decode of generated config types — most notably the
 * [com.convert.sdk.android.adapter.FileConfigCache] write path that
 * re-serialises [com.convert.sdk.core.model.generated.ConfigResponseData]
 * to disk — sees the BigDecimal contextual serializer and does not
 * throw the F-172 SerializationException.
 *
 * Story 2.2 AC-12 makes the injection mandatory: every component that
 * encodes a generated type with `@Contextual BigDecimal` fields MUST
 * receive a [kotlinx.serialization.json.Json] whose `serializersModule`
 * includes this module (or an aggregate that subsumes it).
 */
public val bigDecimalSerializersModule: SerializersModule = SerializersModule {
    contextual(BigDecimal::class, BigDecimalSerializer)
}
