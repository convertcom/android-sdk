/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.Variation

/**
 * Per-visitor public context.
 *
 * The SDK produces one [ConvertContext] per visitor via
 * [ConvertSDK.createContext]; the constructor is `internal` so that
 * consumer code cannot build one manually. Every bucketing, feature, and
 * tracking call routes through an instance of this class, which therefore
 * also exposes mutators for the visitor attributes, segments, and
 * location properties that the rule engine inspects at evaluation time.
 *
 * Every public method on this class is a deliberately stubbed skeleton
 * in Story 1.2 — the real bodies land in Stories 3.1 / 3.2 / 3.3 / 3.4 /
 * 4.1 / 4.2 / 4.4, tagged by the per-method `TODO(Story X.Y)` comments.
 * Value-returning methods return `null` or `emptyList()` today; setter
 * methods mutate the private state and return `this` so chaining already
 * works from the consumer's perspective.
 *
 * Thread-safety guarantees match the rest of the SDK: a context is safe
 * to read from any thread but mutating setters should be funnelled
 * through the consumer's single-thread convention. Proper concurrency
 * hardening (Mutex / AtomicReference) lands in Story 3.1 alongside the
 * DataManager-backed state.
 *
 * @property visitorId stable visitor identifier supplied at construction
 *   time by [ConvertSDK.createContext].
 */
public class ConvertContext internal constructor(
    public val visitorId: String,
) {

    private var attributes: Map<String, Any?>? = null
    private var locationProperties: Map<String, Any?>? = null
    private var defaultSegments: Map<String, String>? = null
    private var customSegments: Map<String, Any?>? = null

    /**
     * Evaluates a single experience for this visitor and returns the
     * bucketed [Variation].
     *
     * @param experienceKey the merchant-defined key of the experience.
     * @param enableTracking when `true`, the bucketing emits a
     *   `viewExp` tracking event. Pass `false` to inspect the result
     *   without reporting. Defaults to `true`.
     * @return the selected [Variation], or `null` when the visitor is not
     *   bucketed into any variation.
     */
    @JvmOverloads
    public fun runExperience(
        experienceKey: String,
        enableTracking: Boolean = true,
    ): Variation? {
        // TODO(Story 3.2/3.3): wire to BucketingManager and EventManager
        lastExperienceKey = experienceKey
        lastRunWithTracking = enableTracking
        return null
    }

    /**
     * Evaluates every experience for this visitor.
     *
     * @param enableTracking when `true`, each bucketing emits a
     *   `viewExp` tracking event. Pass `false` for silent evaluation.
     *   Defaults to `true`.
     * @return the list of bucketed [Variation]s; empty when the visitor
     *   is not in any experience.
     */
    @JvmOverloads
    public fun runExperiences(enableTracking: Boolean = true): List<Variation> {
        // TODO(Story 3.3): wire to BucketingManager batch evaluation
        lastRunWithTracking = enableTracking
        return emptyList()
    }

    /**
     * Evaluates a feature flag for this visitor.
     *
     * @param featureKey merchant-defined key of the feature.
     * @return the evaluated [Feature], or `null` when the feature is
     *   unknown or the visitor is not eligible.
     */
    public fun runFeature(featureKey: String): Feature? {
        // TODO(Story 4.1): wire to FeatureManager
        lastFeatureKey = featureKey
        return null
    }

    /**
     * Evaluates every feature for this visitor.
     *
     * @return the list of evaluated [Feature]s; empty when none are
     *   configured for this visitor.
     */
    public fun runFeatures(): List<Feature> {
        // TODO(Story 4.1): wire to FeatureManager batch evaluation
        return emptyList()
    }

    /**
     * Records a conversion against a goal.
     *
     * @param goalKey merchant-defined key of the goal to track.
     * @param goalData optional goal-data payload (amounts, transaction
     *   ids, custom dimensions). `null` omits the payload entirely.
     */
    @JvmOverloads
    public fun trackConversion(
        goalKey: String,
        goalData: List<GoalData>? = null,
    ) {
        // TODO(Story 4.2): wire to EventManager / EventQueue
        lastConversionGoalKey = goalKey
        lastConversionGoalData = goalData
    }

    /**
     * Replaces the default-segment map used by rule evaluation.
     *
     * @param segments default segment values keyed by segment name.
     * @return this context for fluent chaining.
     */
    public fun setDefaultSegments(segments: Map<String, String>): ConvertContext {
        // TODO(Story 4.4): push into DataManager-held VisitorContext
        defaultSegments = segments
        return this
    }

    /**
     * Replaces the custom-segment map used by rule evaluation.
     *
     * @param customSegments merchant-supplied segment values.
     * @return this context for fluent chaining.
     */
    public fun setCustomSegments(customSegments: Map<String, Any?>): ConvertContext {
        // TODO(Story 4.4): push into DataManager-held VisitorContext
        this.customSegments = customSegments
        return this
    }

    /**
     * Seeds or replaces this context's attribute map.
     *
     * @param attributes attributes associated with this visitor.
     * @return this context for fluent chaining.
     */
    public fun setAttributes(attributes: Map<String, Any?>): ConvertContext {
        // TODO(Story 3.1): push into DataManager-held VisitorContext
        this.attributes = attributes
        return this
    }

    /**
     * Seeds or replaces this context's location-properties map used by
     * location-rule evaluation.
     *
     * @param properties location-evaluation inputs keyed by property name.
     * @return this context for fluent chaining.
     */
    public fun setLocationProperties(properties: Map<String, Any?>): ConvertContext {
        // TODO(Story 3.4): propagate into LocationManager once wired
        locationProperties = properties
        return this
    }

    // --- transient state so the stubs materially read the private fields ---------
    // Once the real managers land in Stories 3.x / 4.x these go away; they exist
    // today so detekt's UnusedPrivateMember rule does not flag the setters.

    private var lastExperienceKey: String? = null
    private var lastFeatureKey: String? = null
    private var lastRunWithTracking: Boolean = true
    private var lastConversionGoalKey: String? = null
    private var lastConversionGoalData: List<GoalData>? = null

    /**
     * Internal accessor used by tests and by later stories when wiring
     * the real managers. Not part of the public surface — the
     * accompanying fields are implementation detail and will be removed
     * once the managers take over.
     */
    internal fun debugSnapshot(): Map<String, Any?> = mapOf(
        "attributes" to attributes,
        "locationProperties" to locationProperties,
        "defaultSegments" to defaultSegments,
        "customSegments" to customSegments,
        "lastExperienceKey" to lastExperienceKey,
        "lastFeatureKey" to lastFeatureKey,
        "lastRunWithTracking" to lastRunWithTracking,
        "lastConversionGoalKey" to lastConversionGoalKey,
        "lastConversionGoalData" to lastConversionGoalData,
    )
}
