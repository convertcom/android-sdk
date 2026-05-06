/*
 * Convert Android SDK — core/internal
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.internal

import com.convert.sdk.core.model.generated.generatedPolymorphicSerializersModule
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.plus
import java.math.BigDecimal

/**
 * [KSerializer] that reads any JSON value as a [JsonElement] and writes
 * [JsonElement] values straight back to JSON.
 *
 * Registered as the contextual serializer for [Any] in
 * [sharedSerializersModule]. This is how the SDK handles the
 * OpenAPI-generated `@Contextual val variablesData: Any?` field on
 * `ExperienceChangeFullStackFeatureBaseAllOfData` — instead of letting
 * kotlinx-serialization fail at decode time ("no contextual serializer
 * registered for Any"), the field arrives at runtime as a [JsonElement]
 * (typically a [kotlinx.serialization.json.JsonObject] carrying the
 * feature-variable key/value pairs). Story 4.1's FeatureManager then
 * casts to [kotlinx.serialization.json.JsonObject] and exposes the map.
 *
 * ### Why not just re-generate with `Map<String, JsonElement>`?
 *
 * The spec-side schema for `variablesData` is polymorphic (variables
 * can be strings, numbers, booleans, or nested JSON) so the generator
 * emits `Any?` with `@Contextual`. Registering this serializer is the
 * least-invasive way to bridge the typing gap — the alternative is to
 * post-process the generated files (fragile) or patch the spec
 * (out-of-scope).
 *
 * ### Symmetric encode
 *
 * The encoder path is needed because `FileConfigCache` re-serializes
 * the loaded `ConfigResponseData` to disk. Writing a `JsonElement` back
 * out delegates to the standard `JsonElement` serializer — no loss.
 */
public object AnyAsJsonElementSerializer : KSerializer<Any> {

    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor

    override fun deserialize(decoder: Decoder): Any {
        // Requires a JsonDecoder — the SDK only ever reads config via
        // kotlinx.serialization.json.Json, so this invariant is safe.
        val jsonDecoder = decoder as? JsonDecoder
            ?: error(
                "AnyAsJsonElementSerializer only supports JSON decoding; got ${decoder::class}",
            )
        return jsonDecoder.decodeJsonElement()
    }

    override fun serialize(encoder: Encoder, value: Any) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error(
                "AnyAsJsonElementSerializer only supports JSON encoding; got ${encoder::class}",
            )
        val element = value as? JsonElement ?: JsonNull
        jsonEncoder.encodeJsonElement(element)
    }
}

/**
 * F-165 (2026-05-05) replaced the previous `RawExperienceChangeServing`
 * catch-all wrapper + `RawExperienceChangeServingSerializer` polymorphic
 * `defaultDeserializer` registration. The wrapper existed because the
 * generator emitted `ExperienceChangeServing` as a non-sealed interface
 * whose variants did not declare `: ExperienceChangeServing` — making
 * kotlinx-serialization unable to decode `List<ExperienceChangeServing>`.
 *
 * After F-165, the post-process script (`patchKotlinGeneratorBugs.js`
 * Rule 7 in the backend repo) rewrites every one-to-one `oneOf+discriminator`
 * schema to `@Serializable @JsonClassDiscriminator("type") sealed interface`
 * with each variant carrying `@SerialName(...)` and implementing the
 * sealed parent. kotlinx-serialization auto-derives the polymorphic
 * serializer for sealed hierarchies — the runtime catch-all is no longer
 * needed for known discriminator values, and a sealed interface cannot
 * be implemented from a different package anyway (Kotlin enforces this).
 *
 * Forward-compat trade-off: a future spec change that adds a new
 * `ExperienceChangeServing` variant on the wire BEFORE the SDK regenerates
 * its types will throw at decode time on that unknown discriminator.
 * The mitigation is to regenerate types whenever the spec changes
 * (Story 1.5's regen flow) — same expectation that already applies to
 * every other typed config field.
 */

/**
 * [KSerializer] for [java.math.BigDecimal]. Reads a JSON number (or
 * numeric string, for loose parity with JavaScript's `Number(x)`
 * coercion) into a [BigDecimal]; writes a [BigDecimal] back out as the
 * raw unquoted numeric literal via [JsonPrimitive]'s number constructor.
 *
 * Registered contextually because the OpenAPI-generated model types
 * annotate many numeric fields as `@Contextual val x: java.math.BigDecimal?`
 * (traffic allocations, percentages, bucket weights, …). Without a
 * registered serializer, any config JSON carrying one of these fields
 * fails to decode.
 */
internal object BigDecimalSerializer : KSerializer<BigDecimal> {

    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.math.BigDecimal", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): BigDecimal {
        val jsonDecoder = decoder as? JsonDecoder
            ?: error(
                "BigDecimalSerializer only supports JSON decoding; got ${decoder::class}",
            )
        val element = jsonDecoder.decodeJsonElement()
        val primitive = element as? JsonPrimitive
            ?: error("BigDecimal expected JSON primitive, got $element")
        // Matches `BigDecimal(String)` — handles scientific notation,
        // plain decimals, and integers. A non-numeric string throws
        // NumberFormatException which kotlinx-serialization surfaces
        // as a decode failure (correct — malformed config).
        return BigDecimal(primitive.content)
    }

    override fun serialize(encoder: Encoder, value: BigDecimal) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: error(
                "BigDecimalSerializer only supports JSON encoding; got ${encoder::class}",
            )
        jsonEncoder.encodeJsonElement(JsonPrimitive(value))
    }
}

/**
 * SerializersModule registering [AnyAsJsonElementSerializer] as the
 * contextual serializer for [Any], [BigDecimalSerializer] for
 * [BigDecimal], and the generator-emitted [generatedPolymorphicSerializersModule]
 * — registering a polymorphic-default-deserializer (sentinel fallback)
 * for every `oneOf+discriminator` schema in the bundled Convert
 * serving OpenAPI spec, with runtime resilience to wire payloads that
 * elide or carry unknown discriminator values (F-165, F-169).
 *
 * Installed on the SDK's shared [kotlinx.serialization.json.Json]
 * instance (see `ConvertSDK.buildSharedJson`). Any module consuming
 * this `Json` automatically gets feature-variable decoding +
 * polymorphic resilience + variation-change decoding.
 *
 * Replaces the prior `+ rawRuleSerializersModule` composition: the
 * three rule-family registrations (`RuleElement`, `RuleElementNoUrl`,
 * `RuleElementAudience`) are functionally subsumed by the aggregate's
 * `<Name>Unknown` sentinels (each carries the wire payload as a raw
 * `JsonObject` — same shape RuleManager already reads via
 * `(rule as <Name>Unknown).raw["rule_type"]`). `rawRuleSerializersModule`
 * is `@Deprecated` and removed in the next minor.
 */
public val sharedSerializersModule: SerializersModule = SerializersModule {
    contextual(Any::class, AnyAsJsonElementSerializer)
    contextual(BigDecimal::class, BigDecimalSerializer)
} + generatedPolymorphicSerializersModule
