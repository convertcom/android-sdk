/*
 * Convert Android SDK — core/rules
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.rules

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.generated.RuleObject
import com.convert.sdk.core.model.generated.RuleObjectAudience
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Evaluates audience / location rule trees against a visitor's attribute
 * or location-property map.
 *
 * ### JS SDK parity
 *
 * Mirrors the algorithm in `@convertcom/js-sdk-rules/rule-manager.ts`:
 *
 *  - **Top-level `OR`** — the rule set matches if *any* AND branch
 *    evaluates to `true`. Empty / missing `OR` array → "no constraints"
 *    → returns `true` (matches JS SDK's "empty rule set = eligible"
 *    behaviour; the Android story AC-1 codifies it).
 *  - **Second-level `AND`** — the AND branch matches if *every* OR_WHEN
 *    block evaluates to `true`. A single failing OR_WHEN short-circuits
 *    the branch to `false`.
 *  - **Third-level `OR_WHEN`** — the OR_WHEN block matches if *any*
 *    rule element within it evaluates to `true`. Per-element errors
 *    (unknown operator, missing attribute, malformed value) log WARN
 *    and treat that element as `false`; the OR/AND structure continues
 *    evaluating other branches (AC-7).
 *
 * ### Rule-element walker
 *
 * The generator emits `RuleElement*` as interfaces. The shared JSON
 * codec (via [rawRuleSerializersModule]) wraps each wire-level rule
 * element in a [RawRuleElementAudience] / [RawRuleElement] /
 * [RawRuleElementNoUrl] holder carrying the full [JsonObject]. The
 * walker only cares about the raw [JsonObject]; it reads:
 *
 *  - `matching.match_type: String` — dispatched via [Comparisons].
 *  - `matching.negated: Boolean` — flips the comparison result.
 *  - `key: String` — attribute-lookup key (case-sensitivity per
 *    [ConvertConfig.rules]).
 *  - `value: JsonElement` — rule-side comparison value.
 *
 * Missing / malformed `matching` or `key` / `value` is treated as
 * false + WARN per AC-7.
 *
 * ### Case sensitivity (AC-3)
 *
 * When `config.rules?.keysCaseSensitive == false`, the attribute-map
 * lookup lowercases both the rule's `key` and each attribute key. The
 * comparison operators themselves (lowercasing on the `value` side)
 * are handled inside [Comparisons] for every string operator;
 * [RuleManager] does not touch the value side.
 *
 * ### Negation (AC-4)
 *
 * The Android SDK mirrors the JS SDK's convention: per-element
 * negation is encoded in the wire payload as `matching.negated:
 * boolean`. The [RulesConfig.negation] SDK-level setting (the "not"
 * string prefix convention some SDKs expose) is NOT implemented here
 * because the JS SDK does not implement it either — it only flips
 * results via `matching.negated`.
 *
 * ### Never throws
 *
 * Every code path that could throw (missing field, malformed JSON,
 * unknown operator) is caught + logged. The evaluator returns
 * `true` / `false` deterministically.
 */
internal class RuleManager(
    private val config: ConvertConfig,
    private val logger: Logger,
) {

    /**
     * Evaluates an audience rule tree ([RuleObjectAudience]).
     *
     * @param rules the deserialised rule tree; `null` or empty → `true`.
     * @param attributes the visitor's attribute map (or any other
     *   key/value data the rule tree expects — location properties,
     *   custom segments, etc.).
     * @return `true` if the visitor matches the rule set, `false`
     *   otherwise.
     */
    fun evaluate(rules: RuleObjectAudience?, attributes: Map<String, JsonElement>): Boolean {
        val orGroups = rules?.OR
        if (orGroups.isNullOrEmpty()) return true
        return orGroups.any { orGroup ->
            evaluateAndBlock(orGroup.AND, attributes)
        }
    }

    /**
     * Location overload — [RuleObject] shape. Semantics identical to
     * [evaluate] above; only the concrete inner types differ because
     * location rule trees use different inner generated classes
     * ([com.convert.sdk.core.model.generated.RuleObjectORInner] →
     * [com.convert.sdk.core.model.generated.RuleObjectORInnerANDInner]).
     */
    fun evaluate(rules: RuleObject?, attributes: Map<String, JsonElement>): Boolean {
        val orGroups = rules?.OR
        if (orGroups.isNullOrEmpty()) return true
        return orGroups.any { orGroup ->
            evaluateLocationAndBlock(orGroup.AND, attributes)
        }
    }

    /**
     * Evaluates a list of audience AND-inner blocks. Returns `true`
     * only when EVERY block matches. A missing / empty `AND` list is
     * treated as "no inner constraints" → `true`, aligning with the
     * JS SDK's warn-and-skip behaviour.
     */
    private fun evaluateAndBlock(
        andBlocks: List<com.convert.sdk.core.model.generated.RuleObjectAudienceORInnerANDInner>?,
        attributes: Map<String, JsonElement>,
    ): Boolean {
        if (andBlocks.isNullOrEmpty()) return true
        return andBlocks.all { block ->
            val orWhen = block.OR_WHEN
            if (orWhen.isNullOrEmpty()) return@all true
            orWhen.any { element ->
                evaluateRawElement(asRawObject(element, audience = true), attributes)
            }
        }
    }

    /** [evaluateAndBlock] for the [RuleObject] / location variant. */
    private fun evaluateLocationAndBlock(
        andBlocks: List<com.convert.sdk.core.model.generated.RuleObjectORInnerANDInner>?,
        attributes: Map<String, JsonElement>,
    ): Boolean {
        if (andBlocks.isNullOrEmpty()) return true
        return andBlocks.all { block ->
            val orWhen = block.OR_WHEN
            if (orWhen.isNullOrEmpty()) return@all true
            orWhen.any { element ->
                evaluateRawElement(asRawObject(element, audience = false), attributes)
            }
        }
    }

    /**
     * Extracts the raw [JsonObject] backing a rule element. Returns
     * `null` (+ logs a WARN) when the element is not the expected
     * raw-holder type (which happens only if a future code path bypasses
     * the shared JSON codec). Reading from the raw map is what allows
     * [RuleManager] to walk rule trees the OpenAPI generator can't
     * deserialise into strong types.
     */
    private fun asRawObject(element: Any?, audience: Boolean): JsonObject? {
        return when (element) {
            is RawRuleElementAudience -> element.raw
            is RawRuleElement -> element.raw
            is RawRuleElementNoUrl -> element.raw
            null -> null
            else -> {
                val which = if (audience) "audience" else "location"
                logger.warn(
                    message = "RuleManager.evaluate(): encountered $which rule element with " +
                        "unknown holder type '${element::class.simpleName}'; treating as not eligible",
                    tag = TAG,
                )
                null
            }
        }
    }

    /**
     * Evaluates a single rule element's raw [JsonObject] against the
     * supplied attribute map. Returns `true` if the comparison matches,
     * `false` on any failure (including unknown operator, missing
     * attribute key, malformed `value`, or a `null` [raw] from a
     * skipped element).
     *
     * All failure modes log a WARN through [logger] with enough detail
     * to correlate against the config payload.
     */
    @Suppress("ReturnCount")
    private fun evaluateRawElement(
        raw: JsonObject?,
        attributes: Map<String, JsonElement>,
    ): Boolean {
        if (raw == null) return false

        val matching = raw["matching"] as? JsonObject
        val matchType = matching?.get("match_type")?.jsonPrimitive?.contentOrNull
        if (matchType == null) {
            logger.warn(
                message = "RuleManager.evaluate(): rule element missing matching.match_type — " +
                    "treating as not eligible. raw=$raw",
                tag = TAG,
            )
            return false
        }
        if (!Comparisons.isKnown(matchType)) {
            logger.warn(
                message = "RuleManager.evaluate(): unknown match_type \"$matchType\"; " +
                    "treating as not eligible",
                tag = TAG,
            )
            return false
        }

        val negation = matching["negated"]?.jsonPrimitive?.booleanOrNull == true

        val ruleKey = raw["key"]?.jsonPrimitive?.contentOrNull
        val ruleValue = raw["value"] ?: JsonNull

        val attrValue = lookupAttribute(attributes, ruleKey)
        if (attrValue == null) {
            logger.warn(
                message = "RuleManager.evaluate(): missing attribute \"$ruleKey\" " +
                    "for match_type \"$matchType\"; treating as not eligible",
                tag = TAG,
            )
            return false
        }

        return Comparisons.apply(
            matchType = matchType,
            value = attrValue,
            testAgainst = ruleValue,
            negation = negation,
        ) ?: false
    }

    /**
     * Looks up an attribute by [ruleKey], honouring the
     * `keysCaseSensitive` setting. When `false`, both the rule key and
     * the map keys are lowercased. Returns `null` when the rule key is
     * itself missing (defensive — shouldn't happen for well-formed
     * rules) or when no attribute matches.
     */
    private fun lookupAttribute(
        attributes: Map<String, JsonElement>,
        ruleKey: String?,
    ): JsonElement? {
        if (ruleKey == null) return null
        val caseSensitive = config.rules?.keysCaseSensitive ?: true
        return if (caseSensitive) {
            attributes[ruleKey]
        } else {
            val target = ruleKey.lowercase()
            attributes.entries.firstOrNull { it.key.lowercase() == target }?.value
        }
    }

    private companion object {
        /** Log tag for every WARN emission — matches the class name for trivial grep. */
        const val TAG: String = "RuleManager"
    }
}
