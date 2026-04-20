/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.android

import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.Variation

/**
 * Per-visitor execution context.
 *
 * Instances are created exclusively through [ConvertSDK.createContext]. This
 * class holds the visitor identifier plus mutable visitor state (attributes,
 * location properties, segments). Real behavior — bucketing, feature lookup,
 * conversion tracking — lands in later stories; this skeleton returns
 * placeholder values so downstream code can reference the type.
 */
public class ConvertContext internal constructor(
    /** Stable identifier of the visitor this context represents. */
    public val visitorId: String,
) {

    private var attributes: Map<String, Any?>? = null
    private var locationProperties: Map<String, Any?>? = null
    private var defaultSegments: Map<String, String>? = null
    private var customSegments: Map<String, Any?>? = null

    /**
     * Runs a single experience and returns the bucketed variation, if any.
     *
     * @param experienceKey key of the experience to run.
     * @param enableTracking when `true`, fires the `viewExp` tracking event.
     * @return the bucketed [Variation], or `null` if the visitor does not
     *  match any variation.
     */
    // TODO(Story 3.2 / 3.3): wire to BucketingManager + EventManager.
    @JvmOverloads
    public fun runExperience(
        experienceKey: String,
        enableTracking: Boolean = true,
    ): Variation? {
        return null
    }

    /**
     * Runs every eligible experience for the current visitor.
     *
     * The `@JvmOverloads` annotation produces both `runExperiences()` and
     * `runExperiences(boolean)` JVM signatures, so Java consumers can call
     * either form — matching the AC-5 requirement.
     *
     * @param enableTracking when `true`, fires `viewExp` events for matches.
     * @return the list of bucketed variations; empty in the skeleton.
     */
    // TODO(Story 3.3): wire to BucketingManager + EventManager.
    @JvmOverloads
    public fun runExperiences(enableTracking: Boolean = true): List<Variation> {
        return emptyList()
    }

    /**
     * Resolves a single feature flag for the current visitor.
     *
     * @param featureKey key of the feature to resolve.
     * @return the [Feature] result, or `null` if the feature is unknown.
     */
    // TODO(Story 4.1): wire to FeatureManager.
    public fun runFeature(featureKey: String): Feature? {
        return null
    }

    /**
     * Resolves every feature flag for the current visitor.
     *
     * @return the list of [Feature] results; empty in the skeleton.
     */
    // TODO(Story 4.1): wire to FeatureManager.
    public fun runFeatures(): List<Feature> {
        return emptyList()
    }

    /**
     * Records a conversion for [goalKey] along with optional [goalData].
     *
     * @param goalKey key of the goal being converted.
     * @param goalData optional list of goal data entries (amount, products,
     *  custom dimensions, etc.).
     */
    // TODO(Story 4.2): wire to EventManager.
    @JvmOverloads
    public fun trackConversion(
        goalKey: String,
        goalData: List<GoalData>? = null,
    ) {
        // Intentionally empty — real implementation lands in Story 4.2.
    }

    /**
     * Sets the default segment values for the visitor.
     *
     * @param segments map of segment key → value.
     * @return this context, to allow chaining.
     */
    // TODO(Story 4.4): forward to SegmentsManager.
    public fun setDefaultSegments(segments: Map<String, String>): ConvertContext {
        this.defaultSegments = segments
        return this
    }

    /**
     * Sets the caller-supplied custom segment overrides.
     *
     * @param customSegments loosely-typed segment map.
     * @return this context, to allow chaining.
     */
    // TODO(Story 4.4): forward to SegmentsManager.
    public fun setCustomSegments(customSegments: Map<String, Any?>): ConvertContext {
        this.customSegments = customSegments
        return this
    }

    /**
     * Sets the attribute map used during rule evaluation.
     *
     * @param attributes loosely-typed attribute map.
     * @return this context, to allow chaining.
     */
    // TODO(Story 3.1): forward to DataManager.
    public fun setAttributes(attributes: Map<String, Any?>): ConvertContext {
        this.attributes = attributes
        return this
    }

    /**
     * Sets the location-match properties used for page targeting.
     *
     * @param properties loosely-typed property map.
     * @return this context, to allow chaining.
     */
    // TODO(Story 3.4): forward to LocationManager.
    public fun setLocationProperties(properties: Map<String, Any?>): ConvertContext {
        this.locationProperties = properties
        return this
    }
}
