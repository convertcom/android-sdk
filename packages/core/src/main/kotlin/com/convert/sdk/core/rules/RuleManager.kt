/*
 * Convert Android SDK — core/rules
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.rules

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.generated.RuleElementAudienceUnknown
import com.convert.sdk.core.model.generated.RuleElementNoUrlUnknown
import com.convert.sdk.core.model.generated.RuleElementUnknown
import com.convert.sdk.core.model.generated.RuleObject
import com.convert.sdk.core.model.generated.RuleObjectAudience
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * Evaluates audience / location rule trees against a visitor's attribute
 * or location-property map.
 *
 * ### JS SDK parity
 *
 * Mirrors the algorithm in `@convertcom/js-sdk-rules/rule-manager.ts`:
 *
 *  - **Top-level `OR`** — the rule set matches if *any* AND branch
 *    evaluates to `true`. **Empty / missing `OR` property → log WARN
 *    (`RuleManager.isRuleMatched(): rule not valid`) and return `false`**
 *    (matches JS SDK `rule-manager.ts:116-153`: an absent / empty `OR`
 *    is treated as an INVALID rule, not as "no constraints"; F-024).
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
 * Mirrors the JS SDK's `keys_case_sensitive` quirk verbatim: the
 * constructor does `config?.rules?.keys_case_sensitive || true` (see
 * `rule-manager.ts:57-58`), which means **any falsy value — including
 * an explicit `false` — coalesces to `true`**. The Android port uses
 * `takeIf { it == true } ?: true` so `false` and `null` both resolve
 * to `true` (case-sensitive lookup). This is a known JS SDK quirk;
 * we mirror it for cross-SDK parity rather than fixing it.
 *
 * When the resolved flag is `false` (effectively never via config —
 * see [keysCaseSensitiveOverride] for the test-only injection seam),
 * the attribute-map lookup lowercases both the rule's `key` and each
 * attribute key. The comparison operators themselves (lowercasing on
 * the `value` side) are handled inside [Comparisons] for every string
 * operator; [RuleManager] does not touch the value side.
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
public class RuleManager(
    private val config: ConvertConfig,
    private val logger: Logger,
) {

    /**
     * Evaluates an audience rule tree ([RuleObjectAudience]).
     *
     * @param rules the deserialised rule tree; `null` or an empty / absent
     *   `OR` array logs WARN (`RuleManager.isRuleMatched(): rule not valid`)
     *   and returns `false`, mirroring JS SDK `rule-manager.ts:116-153`
     *   (F-024 — empty rule sets are INVALID, not "no constraints").
     * @param attributes the visitor's attribute map (or any other
     *   key/value data the rule tree expects — location properties,
     *   custom segments, etc.).
     * @return `true` if the visitor matches the rule set, `false`
     *   otherwise.
     */
    public fun evaluate(rules: RuleObjectAudience?, attributes: Map<String, JsonElement>): Boolean {
        val orGroups = rules?.OR
        if (orGroups.isNullOrEmpty()) {
            warnRuleNotValid()
            return false
        }
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
     *
     * `null` or empty / absent `OR` → WARN + `false`, same as the
     * audience overload (F-024).
     */
    public fun evaluate(rules: RuleObject?, attributes: Map<String, JsonElement>): Boolean {
        val orGroups = rules?.OR
        if (orGroups.isNullOrEmpty()) {
            warnRuleNotValid()
            return false
        }
        return orGroups.any { orGroup ->
            evaluateLocationAndBlock(orGroup.AND, attributes)
        }
    }

    /**
     * Emits the JS-SDK-parity WARN for an invalid rule set (no `OR`
     * property or an empty `OR` array). Phrasing mirrors JS SDK
     * `rule-manager.ts:147-151`'s `ERROR_MESSAGES.RULE_NOT_VALID` log
     * line exactly so cross-SDK log scraping stays uniform.
     */
    private fun warnRuleNotValid() {
        logger.warn(
            message = "RuleManager.isRuleMatched(): rule not valid (missing or empty OR property)",
            tag = TAG,
        )
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
            // F-169: the SDK's shared Json instance now composes
            // `+ generatedPolymorphicSerializersModule` (replacing the
            // hand-written `+ rawRuleSerializersModule`), so wire-decoded
            // rule elements arrive as the generator-emitted
            // `<Name>Unknown` sentinels. The `RawRuleElement*` cases
            // remain for tests and any code path that constructs the
            // hand-written shim directly (e.g., `Json { serializersModule
            // = rawRuleSerializersModule }` in `RuleManagerTest`).
            is RuleElementAudienceUnknown -> element.raw
            is RuleElementUnknown -> element.raw
            is RuleElementNoUrlUnknown -> element.raw
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
        val matchType = (matching?.get("match_type") as? JsonPrimitive)?.contentOrNull
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

        val negation = (matching["negated"] as? JsonPrimitive)?.booleanOrNull == true

        val ruleKey = (raw["key"] as? JsonPrimitive)?.contentOrNull
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
     * Test-only injection seam (F-108): when non-`null`, this value
     * **bypasses** the JS-SDK falsy-coalesce on
     * `config.rules?.keysCaseSensitive` and is used directly as the
     * resolved flag. Production code never sets this — it exists solely
     * so unit tests can reach the case-insensitive lookup path that the
     * coalesce makes unreachable through normal config construction
     * (the JS SDK's `|| DEFAULT_KEYS_CASE_SENSITIVE` quirk turns any
     * falsy config value, including an explicit `false`, back into
     * `true`).
     *
     * `internal` visibility keeps it out of the published `sdk-core`
     * surface; only test code in the same module can poke at it.
     */
    @Suppress("VariableNaming")
    internal var keysCaseSensitiveOverride: Boolean? = null

    /**
     * Resolves the effective `keys_case_sensitive` flag. Mirrors the JS
     * SDK quirk at `rule-manager.ts:57-58` verbatim: any falsy value
     * (including an explicit `false`) coalesces back to `true`. The
     * `takeIf { it == true }` chain captures the JS `||` truthiness
     * check — only an explicit `true` survives; `false` and `null`
     * both land on `true`.
     *
     * The [keysCaseSensitiveOverride] test-only seam, when non-`null`,
     * preempts the coalesce so unit tests can exercise the otherwise
     * unreachable case-insensitive branch.
     */
    private fun resolvedKeysCaseSensitive(): Boolean =
        keysCaseSensitiveOverride
            ?: (config.rules?.keysCaseSensitive?.takeIf { it == true } ?: true)

    /**
     * Looks up an attribute by [ruleKey], honouring the resolved
     * `keysCaseSensitive` flag (see [resolvedKeysCaseSensitive] for the
     * JS-SDK falsy-coalesce semantics). When `false`, both the rule key
     * and the map keys are lowercased. Returns `null` when the rule key
     * is itself missing (defensive — shouldn't happen for well-formed
     * rules) or when no attribute matches.
     */
    private fun lookupAttribute(
        attributes: Map<String, JsonElement>,
        ruleKey: String?,
    ): JsonElement? {
        if (ruleKey == null) return null
        return if (resolvedKeysCaseSensitive()) {
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
