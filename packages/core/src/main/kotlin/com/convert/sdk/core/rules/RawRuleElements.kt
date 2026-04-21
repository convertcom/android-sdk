/*
 * Convert Android SDK — core/rules
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.rules

import com.convert.sdk.core.model.generated.JsConditionMatchRuleAllOfMatching
import com.convert.sdk.core.model.generated.JsConditionMatchRulesTypes
import com.convert.sdk.core.model.generated.RuleElement
import com.convert.sdk.core.model.generated.RuleElementAudience
import com.convert.sdk.core.model.generated.RuleElementNoUrl
import com.convert.sdk.core.model.generated.VisitorDataExistsMatchRuleAllOfMatching
import com.convert.sdk.core.model.generated.VisitorDataExistsMatchRulesTypes
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic

/**
 * Raw-JSON holders for the three rule-element interfaces the OpenAPI
 * generator emits (`RuleElement`, `RuleElementAudience`,
 * `RuleElementNoUrl`).
 *
 * ### Why these exist
 *
 * The generator produces `interface` types for each rule-element
 * polymorphic union (instead of sealed classes or discriminated data
 * classes). kotlinx-serialization cannot deserialise an interface
 * without either (a) a `@Serializable` annotation + sealed hierarchy,
 * (b) a registered polymorphic module with a class discriminator, or
 * (c) a registered default deserializer that takes over when no
 * discriminator is present.
 *
 * Real config payloads emitted by the Convert backend carry rule
 * elements without a wire-level discriminator — the element type is
 * encoded implicitly in the `rule_type` field. Rather than patching
 * the generator output (which would be regenerated away next release),
 * we take route (c): a polymorphic module with a "catch-all" default
 * deserializer that reads any rule element as a [JsonObject] and wraps
 * it in a concrete class that implements the interface with stub
 * accessors.
 *
 * ### What the concrete classes carry
 *
 * Each holder class:
 *  - Stores the full raw [JsonObject] of the rule element (for the
 *    [RuleManager] walker to read `rule_type`, `matching.match_type`,
 *    `matching.negated`, `key`, and `value` without strong typing).
 *  - Implements every interface member with a stub that `error(...)`s
 *    when accessed through the strongly-typed path. The SDK never
 *    reaches these paths — only the walker touches the raw map — so
 *    the stubs exist purely to satisfy the interface contract.
 *
 * ### Public API vs internal
 *
 * The holder classes and their serializers are `internal` — consumer
 * code never references them directly. The shared JSON codec in
 * `ConvertSDK.buildSharedJson` registers them via
 * [rawRuleSerializersModule] so every config deserialisation picks
 * them up transparently.
 */

/**
 * [RuleElementAudience] realisation that keeps the full raw
 * [JsonObject] of the wire-level rule element. The strongly-typed
 * accessors all stub to `error(...)`; the SDK's only consumer of
 * this type is [RuleManager], which reads the raw map directly.
 */
internal data class RawRuleElementAudience(val raw: JsonObject) : RuleElementAudience {
    override val ruleType: VisitorDataExistsMatchRulesTypes
        get() = error("RawRuleElementAudience.ruleType is not materialised; read raw[\"rule_type\"]")
    override val value: Boolean? get() = null
    override val matching: VisitorDataExistsMatchRuleAllOfMatching? get() = null
    override val key: String? get() = null
}

/**
 * [RuleElement] realisation. Same role as [RawRuleElementAudience] for
 * site-area / non-audience rule trees.
 */
internal data class RawRuleElement(val raw: JsonObject) : RuleElement {
    override val ruleType: JsConditionMatchRulesTypes
        get() = error("RawRuleElement.ruleType is not materialised; read raw[\"rule_type\"]")
    override val value: String? get() = null
    override val matching: JsConditionMatchRuleAllOfMatching? get() = null
    override val key: String? get() = null
}

/**
 * [RuleElementNoUrl] realisation. Same role as [RawRuleElementAudience]
 * for the "no URL rules" tree variants (segment bucketed, goal
 * triggered, etc. that avoid URL-shaped match rules).
 */
internal data class RawRuleElementNoUrl(val raw: JsonObject) : RuleElementNoUrl {
    override val ruleType: VisitorDataExistsMatchRulesTypes
        get() = error("RawRuleElementNoUrl.ruleType is not materialised; read raw[\"rule_type\"]")
    override val value: Boolean? get() = null
    override val matching: VisitorDataExistsMatchRuleAllOfMatching? get() = null
    override val key: String? get() = null
}

/**
 * [SerializersModule] that registers "catch-all" deserializers for the
 * three rule-element interfaces. Installed on the shared JSON codec via
 * `Json { serializersModule = rawRuleSerializersModule }`.
 *
 * The registered `defaultDeserializer` fires whenever kotlinx-serialization
 * encounters a polymorphic field of one of the interfaces and the wire
 * payload carries no class discriminator — which is always the case for
 * Convert backend config payloads.
 *
 * Marked `public` so the `sdk` module's shared-JSON builder and tests in
 * both `core` and `sdk` scopes can wire it in. Consumers of the published
 * `sdk-core` artifact should still treat this as an SDK-internal
 * extension point — the only expected caller is the SDK's own shared
 * codec assembly.
 */
public val rawRuleSerializersModule: SerializersModule = SerializersModule {
    polymorphic(RuleElementAudience::class) {
        defaultDeserializer { RawRuleElementAudienceSerializer }
    }
    polymorphic(RuleElement::class) {
        defaultDeserializer { RawRuleElementSerializer }
    }
    polymorphic(RuleElementNoUrl::class) {
        defaultDeserializer { RawRuleElementNoUrlSerializer }
    }
}

/** Shared serial descriptor — identical for all three raw serializers. */
private val jsonObjectDescriptor: SerialDescriptor = JsonObject.serializer().descriptor

/**
 * Catch-all [KSerializer] for [RuleElementAudience]. Decodes any
 * [JsonObject] into [RawRuleElementAudience]; encodes back to the raw
 * JSON (useful for re-serialising cached configs).
 */
internal object RawRuleElementAudienceSerializer : KSerializer<RuleElementAudience> {
    override val descriptor: SerialDescriptor = jsonObjectDescriptor
    override fun deserialize(decoder: Decoder): RuleElementAudience =
        RawRuleElementAudience(JsonObject.serializer().deserialize(decoder))

    override fun serialize(encoder: Encoder, value: RuleElementAudience) {
        val raw = (value as? RawRuleElementAudience)?.raw ?: buildJsonObject {}
        JsonObject.serializer().serialize(encoder, raw)
    }
}

/** Catch-all [KSerializer] for [RuleElement]. See [RawRuleElementAudienceSerializer]. */
internal object RawRuleElementSerializer : KSerializer<RuleElement> {
    override val descriptor: SerialDescriptor = jsonObjectDescriptor
    override fun deserialize(decoder: Decoder): RuleElement =
        RawRuleElement(JsonObject.serializer().deserialize(decoder))

    override fun serialize(encoder: Encoder, value: RuleElement) {
        val raw = (value as? RawRuleElement)?.raw ?: buildJsonObject {}
        JsonObject.serializer().serialize(encoder, raw)
    }
}

/** Catch-all [KSerializer] for [RuleElementNoUrl]. See [RawRuleElementAudienceSerializer]. */
internal object RawRuleElementNoUrlSerializer : KSerializer<RuleElementNoUrl> {
    override val descriptor: SerialDescriptor = jsonObjectDescriptor
    override fun deserialize(decoder: Decoder): RuleElementNoUrl =
        RawRuleElementNoUrl(JsonObject.serializer().deserialize(decoder))

    override fun serialize(encoder: Encoder, value: RuleElementNoUrl) {
        val raw = (value as? RawRuleElementNoUrl)?.raw ?: buildJsonObject {}
        JsonObject.serializer().serialize(encoder, raw)
    }
}
