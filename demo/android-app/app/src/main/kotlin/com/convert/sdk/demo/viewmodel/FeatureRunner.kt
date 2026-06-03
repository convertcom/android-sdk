/*
 * Convert Android SDK Demo App — FeatureRunner contract
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import com.convert.sdk.core.model.Feature

/**
 * Narrow seam the [SdkViewModel] uses to call the SDK's feature-flag
 * surface (Story 7.4). Parallels [ExperienceRunner] one-to-one.
 *
 * Production wires this to a lambda that delegates to a
 * [com.convert.sdk.android.ConvertContext] obtained from
 * `ConvertSDK.createContext()`. Tests supply a simple fake so the
 * ViewModel can be exercised without building a real SDK (which
 * requires an Android [android.content.Context]).
 *
 * Keeping the interface tiny — only the two methods the screen drives —
 * lets the ViewModel ignore everything else `ConvertContext` exposes
 * and keeps the test double trivial.
 */
public interface FeatureRunner {

    /**
     * Mirrors [com.convert.sdk.android.ConvertContext.runFeature].
     * Returns the evaluated [Feature] or `null` when the feature is
     * unknown or the SDK is not ready.
     *
     * Firing the internal `bucketing` event on the SDK's pub/sub bus
     * (which the Event Inspector subscribes to) is a side-effect of the
     * underlying `runFeature` call, NOT something this contract
     * specifies — the ViewModel relies on that side-effect to light up
     * the inspector but does not assert it here, so tests can use a
     * minimal fake.
     */
    public fun runFeature(featureKey: String): Feature?

    /**
     * Mirrors [com.convert.sdk.android.ConvertContext.runFeatures].
     * Returns every evaluated [Feature] for this visitor, or an empty
     * list when no features are configured or the config is not loaded.
     * Never returns `null`.
     */
    public fun runFeatures(): List<Feature>
}
