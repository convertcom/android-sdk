/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.FeatureStatus
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigFeature
import com.convert.sdk.core.model.generated.ExperienceChangeFullStackFeatureBaseAllOfData
import com.convert.sdk.core.model.generated.ExperienceChangeFullStackFeatureServing
import com.convert.sdk.core.model.generated.ExperienceChangeServing
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Resolves feature flags by routing through the SDK's existing experience
 * bucketing pipeline. Story 4.1.
 *
 * ### Wire-format reality (Gotcha #1, verified)
 *
 * The OpenAPI-generated [ConfigFeature] carries only the feature's
 * declaration (`id`, `key`, `name`, `variables` — a list of *type*
 * descriptors, **not** values). The per-variation feature payload lives
 * on [ExperienceVariationConfig.changes], in entries whose `type` equals
 * `"fullStackFeature"` — carrying a [ExperienceChangeFullStackFeatureBaseAllOfData]
 * with `featureId` and `variablesData`. This means there is NO
 * "standalone feature" wire representation: every feature is resolved
 * through an experience, exactly as the JS SDK's `FeatureManager.runFeatures`
 * (`javascript-sdk/packages/js-sdk/src/feature-manager.ts:327-462`) does.
 *
 * ### Resolution algorithm (JS SDK parity)
 *
 *  1. Look up the declared [ConfigFeature] by key in
 *     [com.convert.sdk.core.data.DataManager.data]`.features`. If absent,
 *     return `null` + WARN (AC-7 — unknown feature).
 *  2. Iterate `data.experiences` in declaration order. For each experience
 *     that carries a `fullStackFeature` change referencing the declared
 *     feature's id, invoke [ConvertContext.runExperience] with
 *     [enableTracking]. The context method handles sticky lookup, audience
 *     / location gating (Story 3.4), bucketing (Story 3.2), persistence,
 *     outbound enqueue, and [com.convert.sdk.core.event.SystemEvents.BUCKETING]
 *     fire — no duplication here.
 *  3. When `runExperience` returns a non-null [com.convert.sdk.core.model.Variation],
 *     re-index the bucketed variation in the original config to read its
 *     `changes` list. The first matching `fullStackFeature` change whose
 *     `data.featureId` equals the declared feature's id produces an
 *     `ENABLED` [Feature] with `variablesData` converted to a
 *     `Map<String, JsonElement>`.
 *  4. If the visitor is bucketed into a variation that does NOT expose
 *     the feature (or no experience matches), return a `DISABLED`
 *     [Feature] carrying the declared id/key/name and `null` variables
 *     (AC-8). JS SDK parity (`feature-manager.ts:206-211`).
 *
 * ### Multi-experience deterministic winner (readiness Q3)
 *
 * When several experiences expose the same feature and the visitor is
 * bucketed into more than one, the **first match in declaration order**
 * wins. JS SDK returns an array in this case; the Android SDK's
 * [ConvertContext.runFeature] returns `Feature?`, so we resolve the tie
 * deterministically rather than surface an ambiguous first-or-array
 * type.
 *
 * ### No separate feature event (AC-6)
 *
 * This class never fires a "feature evaluated" event — the
 * [com.convert.sdk.core.event.SystemEvents.BUCKETING] event emitted by
 * `runExperience` IS the feature-evaluation signal. Observers subscribe
 * to `BUCKETING` to see feature resolution happen.
 *
 * @property sdk back-reference to the owning [ConvertSDK]; reads config
 *   through `sdk.dataManager`, routes bucketing through
 *   `context.runExperience`.
 * @property logger shared logger port; emits WARN on unknown feature
 *   keys (AC-7) and malformed `variablesData` payloads.
 */
internal class FeatureManager(
    private val sdk: ConvertSDK,
    private val logger: Logger,
) {

    /**
     * Resolves the feature identified by [featureKey] for the visitor
     * represented by [context]. See class-level KDoc for the algorithm.
     *
     * @param context the caller's [ConvertContext]; used to route
     *   `runExperience` calls (so sticky + rule gate + persistence +
     *   event fire apply exactly once per experience per visitor).
     * @param featureKey merchant-defined feature key.
     * @param enableTracking when `true`, each triggered `runExperience`
     *   call enqueues its outbound bucketing event; when `false`, the
     *   outbound queue is suppressed but sticky + internal events still
     *   fire. Mirrors `runExperience`'s per-call tracking flag.
     * @return the resolved [Feature]; `null` when [featureKey] is not
     *   declared in the current config. When declared but the visitor
     *   is not bucketed into any variation exposing the feature, returns
     *   a [Feature] with [FeatureStatus.DISABLED].
     */
    @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
    fun evaluate(
        context: ConvertContext,
        featureKey: String,
        enableTracking: Boolean = true,
    ): Feature? {
        // ReturnCount — the algorithm has four natural exits (config-
        // missing, unknown feature, matched-variation, declared-but-
        // not-surfaced). Collapsing them into a single return point
        // via a nullable local would hurt readability without any
        // semantic win.
        //
        // LoopWithTooManyJumpStatements — the per-experience loop has
        // three `continue`s that short-circuit known skip cases (no key,
        // experience doesn't expose feature, visitor not bucketed). Each
        // is an independent guard; flattening them into a nested `if`
        // chain would add depth without clarifying intent. Suppression
        // mirrors the `@Suppress("ReturnCount", "TooGenericExceptionCaught")`
        // pattern used by `ConvertContext.runExperiences`.
        val data = sdk.dataManager.data ?: run {
            logger.debug(
                message = "FeatureManager.evaluate: config not loaded, returning null",
                tag = TAG,
            )
            return null
        }
        val declared = data.features?.firstOrNull { it.key == featureKey }
        if (declared == null) {
            logger.warn(
                message = "FeatureManager.evaluate: feature not declared for key '$featureKey'",
                tag = TAG,
            )
            return null
        }
        val featureId = declared.id
        val experiences = data.experiences ?: emptyList()

        // Walk experiences in declaration order; first fullStackFeature
        // change matching this feature id + a non-null bucketing wins.
        for (experience in experiences) {
            val expKey = experience.key ?: continue
            if (!experienceExposesFeature(experience, featureId)) continue
            val variation = context.runExperience(expKey, enableTracking) ?: continue
            val change = findFeatureChange(experience, variation.id, featureId) ?: continue
            return buildEnabledFeature(declared, experience, change.`data`)
        }

        // Declared but nothing bucketed → DISABLED (AC-8, JS SDK parity).
        return disabledFeature(declared)
    }

    /**
     * Resolves every declared feature for the visitor. Equivalent to
     * calling [evaluate] for each feature key declared in the config,
     * preserving declaration order.
     *
     * Features declared without a `key` are skipped (partial config
     * entries). Features whose evaluation returns `null` (shouldn't
     * happen given the per-call contract — we only iterate declared
     * keys) are dropped via `mapNotNull`.
     *
     * @param context the caller's [ConvertContext].
     * @param enableTracking per-call tracking flag; see [evaluate].
     * @return list of resolved features; empty when no features are
     *   declared or the config is not yet loaded.
     */
    fun evaluateAll(
        context: ConvertContext,
        enableTracking: Boolean = true,
    ): List<Feature> {
        val declared = sdk.dataManager.data?.features ?: return emptyList()
        return declared.mapNotNull { feature ->
            feature.key?.let { key -> evaluate(context, key, enableTracking) }
        }
    }

    /**
     * Returns `true` when any variation on [experience] exposes a
     * `fullStackFeature` change referencing [featureId].
     */
    @Suppress("ReturnCount")
    private fun experienceExposesFeature(
        experience: ConfigExperience,
        featureId: String?,
    ): Boolean {
        // Three early returns for defensive guards (null feature id → no
        // match; null variations → no match; then the actual search).
        if (featureId == null) return false
        val variations = experience.variations ?: return false
        return variations.any { variation ->
            variation.changes?.any { change -> isFullStackFeatureMatch(change, featureId) } == true
        }
    }

    /**
     * Finds the `fullStackFeature` change on the given variation (by id)
     * referencing [featureId]. Returns `null` when the variation has no
     * such change.
     */
    @Suppress("ReturnCount")
    private fun findFeatureChange(
        experience: ConfigExperience,
        variationId: String?,
        featureId: String?,
    ): ExperienceChangeFullStackFeatureServing? {
        // Defensive guards mirror experienceExposesFeature — caller-side
        // nullability lines up with wire shape. Collapsing these guards
        // into chained safe calls would obscure the three independent
        // "nothing to find" cases.
        if (featureId == null || variationId == null) return null
        val variation = experience.variations?.firstOrNull { it.id == variationId } ?: return null
        return variation.changes?.firstOrNull { change ->
            isFullStackFeatureMatch(change, featureId)
        } as? ExperienceChangeFullStackFeatureServing
    }

    /**
     * Predicate: `true` when [change] is a `fullStackFeature` change
     * whose `data.featureId` (Int) matches [featureId] (String). The
     * backend emits `featureId` as an integer in the wire format but the
     * declared feature's `id` is a string — both carry the same numeric
     * value, so we normalise via `toString`. F-165 made
     * `ExperienceChangeServing` a sealed interface; pattern-match on
     * the concrete `fullStackFeature` variant rather than reading a
     * `type` discriminator field that no longer exists.
     */
    private fun isFullStackFeatureMatch(
        change: ExperienceChangeServing,
        featureId: String,
    ): Boolean {
        val featureChange = change as? ExperienceChangeFullStackFeatureServing ?: return false
        return featureChange.data?.featureId?.toString() == featureId
    }

    /**
     * Builds an [FeatureStatus.ENABLED] [Feature] from the declared
     * metadata, owning experience, and the change payload carrying
     * `variablesData`. Variables are decoded from the generated `Any?`
     * field via [decodeVariables] — see the `AnyAsJsonElementSerializer`
     * discussion for the shape invariant.
     */
    private fun buildEnabledFeature(
        declared: ConfigFeature,
        experience: ConfigExperience,
        data: ExperienceChangeFullStackFeatureBaseAllOfData?,
    ): Feature = Feature(
        id = declared.id,
        key = declared.key,
        name = declared.name,
        status = FeatureStatus.ENABLED,
        variables = decodeVariables(data?.variablesData),
        experienceId = experience.id,
        experienceKey = experience.key,
        experienceName = experience.name,
    )

    /**
     * Builds a [FeatureStatus.DISABLED] [Feature] from the declared
     * metadata — no experience/variation information, no variables
     * (AC-8).
     */
    private fun disabledFeature(declared: ConfigFeature): Feature = Feature(
        id = declared.id,
        key = declared.key,
        name = declared.name,
        status = FeatureStatus.DISABLED,
        variables = null,
    )

    /**
     * Decodes the generated `@Contextual variablesData: Any?` field into
     * a `Map<String, JsonElement>`. With the sdk's shared
     * `AnyAsJsonElementSerializer` registered, the runtime value is
     * always a [JsonElement] — usually [JsonObject]. Other shapes
     * (`JsonArray`, primitives, `null`) are logged + treated as empty to
     * keep the public surface non-throwing.
     */
    @Suppress("ReturnCount")
    private fun decodeVariables(raw: Any?): Map<String, JsonElement>? {
        // Three exits reflect three wire-shape outcomes: absent (null →
        // disabled), present but wrong shape (warn + empty), well-formed
        // (map). Treating each as a distinct return keeps the intent
        // visible.
        if (raw == null) return null
        val obj = raw as? JsonObject
        if (obj == null) {
            logger.warn(
                message = "FeatureManager.decodeVariables: variablesData is not a JsonObject " +
                    "(got ${raw::class.simpleName}); returning empty map",
                tag = TAG,
            )
            return emptyMap()
        }
        return obj.toMap()
    }

    private companion object {
        const val TAG: String = "FeatureManager"

        /**
         * Wire-level discriminator emitted by the Convert backend for
         * feature changes. Matches
         * [com.convert.sdk.core.model.generated.ExperienceChangeServing.Type.FULL_STACK_FEATURE].
         */
        const val FULLSTACK_FEATURE_TYPE: String = "fullStackFeature"
    }
}
