/*
 * Convert Android SDK — core/internal
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.internal

import com.convert.sdk.core.model.generated.ExperienceChangeFullStackFeatureBaseAllOfData
import com.convert.sdk.core.model.generated.ExperienceChangeServing
import com.convert.sdk.core.rules.rawRuleSerializersModule
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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.contextual
import kotlinx.serialization.modules.plus
import kotlinx.serialization.modules.polymorphic
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
 * Concrete implementation of [ExperienceChangeServing] that backs the
 * catch-all polymorphic deserialiser. Wraps the raw [JsonObject] so
 * callers can read `id`, `type`, and — when the change is a
 * `fullStackFeature` — the `data` object carrying `featureId` and
 * `variablesData`.
 *
 * The generated [ExperienceChangeServing] interface is `interface` (not
 * `sealed interface`) and the OpenAPI-generated concrete types
 * ([com.convert.sdk.core.model.generated.ExperienceChangeFullStackFeatureServing]
 * et al.) do NOT declare `: ExperienceChangeServing`. That leaves
 * kotlinx-serialization unable to decode the `List<ExperienceChangeServing>`
 * field on [com.convert.sdk.core.model.generated.ExperienceVariationConfig]
 * — any config carrying `changes` would fail at decode time without a
 * polymorphic module.
 *
 * This holder bridges that gap: it captures the raw JSON and exposes the
 * fields the SDK actually needs (type, data). Story 4.1 (feature resolution)
 * is the first story that reads `changes` — future stories that need
 * richer change semantics (DOM changes, custom code, etc.) can extend
 * the holder rather than re-generating types.
 *
 * @property raw the raw JSON object the wire payload arrived as; kept
 *   intact so callers needing unusual fields can read them directly.
 */
internal class RawExperienceChangeServing(
    val raw: JsonObject,
) : ExperienceChangeServing {

    override val id: Int? = raw["id"]?.jsonPrimitive?.intOrNull

    override val type: String? = raw["type"]?.jsonPrimitive?.contentOrNull

    /**
     * Parsed `data` sub-object when this is a `fullStackFeature` change.
     * Null when the change is a non-feature type or the `data` field is
     * absent / malformed.
     *
     * The full-stack-feature data carries `featureId` (Int) and
     * `variablesData` (the raw JsonElement of variable name → value).
     * We lazily decode the sub-object rather than eagerly — many
     * changes in a config will never be read, so avoiding the decode
     * until a consumer asks keeps the load path cheap.
     */
    override val data: ExperienceChangeFullStackFeatureBaseAllOfData? by lazy {
        val dataObj = raw["data"] as? JsonObject ?: return@lazy null
        val featureId = dataObj["feature_id"]?.jsonPrimitive?.intOrNull
        val variablesData = dataObj["variables_data"]
        ExperienceChangeFullStackFeatureBaseAllOfData(
            featureId = featureId,
            variablesData = variablesData,
        )
    }
}

/**
 * Catch-all [KSerializer] for [ExperienceChangeServing]. Decodes any
 * [JsonObject] into a [RawExperienceChangeServing]; encodes back to the
 * raw JSON so round-tripping the config through
 * [com.convert.sdk.android.adapter.FileConfigCache] preserves every
 * field.
 */
internal object RawExperienceChangeServingSerializer : KSerializer<ExperienceChangeServing> {

    override val descriptor: SerialDescriptor = JsonObject.serializer().descriptor

    override fun deserialize(decoder: Decoder): ExperienceChangeServing =
        RawExperienceChangeServing(JsonObject.serializer().deserialize(decoder))

    override fun serialize(encoder: Encoder, value: ExperienceChangeServing) {
        val raw = (value as? RawExperienceChangeServing)?.raw ?: buildJsonObject {}
        JsonObject.serializer().serialize(encoder, raw)
    }
}

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
 * [BigDecimal], the [ExperienceChangeServing] catch-all polymorphic
 * deserialiser, and the [rawRuleSerializersModule] so all rule-element
 * and feature-variable fields decode consistently.
 *
 * Installed on the SDK's shared [kotlinx.serialization.json.Json]
 * instance (see `ConvertSDK.buildSharedJson`). Any module consuming
 * this `Json` automatically gets feature-variable decoding + raw-rule
 * deserialisation + variation-change decoding.
 */
public val sharedSerializersModule: SerializersModule = SerializersModule {
    contextual(Any::class, AnyAsJsonElementSerializer)
    contextual(BigDecimal::class, BigDecimalSerializer)
    polymorphic(ExperienceChangeServing::class) {
        defaultDeserializer { RawExperienceChangeServingSerializer }
    }
} + rawRuleSerializersModule
