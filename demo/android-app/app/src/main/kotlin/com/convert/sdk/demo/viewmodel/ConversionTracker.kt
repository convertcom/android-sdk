/*
 * Convert Android SDK Demo App — ConversionTracker contract
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import com.convert.sdk.core.model.GoalData

/**
 * Narrow seam the [SdkViewModel] uses to call the SDK's
 * conversion-tracking surface (Story 7.5). Parallels [FeatureRunner]
 * and [ExperienceRunner] one-to-one.
 *
 * Production wires this to a lambda that delegates to a
 * [com.convert.sdk.android.ConvertContext] obtained from
 * `ConvertSDK.createContext()`. Tests supply a simple fake so the
 * ViewModel can be exercised without building a real SDK (which
 * requires an Android [android.content.Context]).
 *
 * Keeping the interface tiny — only the single method the screen drives —
 * lets the ViewModel ignore everything else `ConvertContext` exposes
 * and keeps the test double trivial.
 */
public interface ConversionTracker {

    /**
     * Mirrors [com.convert.sdk.android.ConvertContext.trackConversion].
     * Tracks a conversion against [goalKey] with the supplied
     * [goalData] payload.
     *
     * Firing the internal `conversion` event on the SDK's pub/sub bus
     * (which the Event Inspector subscribes to) is a side-effect of the
     * underlying `trackConversion` call, NOT something this contract
     * specifies — the ViewModel relies on that side-effect to light up
     * the inspector but does not assert it here, so tests can use a
     * minimal fake.
     *
     * Dedup semantics (Story 4.3 AC-6) live inside the SDK and are not
     * exposed through this port. The demo ViewModel tracks its own
     * display-only "already tracked" state via a local `Set<String>` —
     * see [SdkViewModel.trackPurchaseConversion] for the rationale.
     */
    public fun trackConversion(goalKey: String, goalData: List<GoalData>)
}
