/*
 * Convert Android SDK Demo App — ExperienceRunner contract
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import com.convert.sdk.core.model.Variation

/**
 * Narrow seam the [SdkViewModel] uses to call the SDK's
 * bucketing surface (Story 7.3).
 *
 * Production wires this to a lambda that delegates to a
 * [com.convert.sdk.android.ConvertContext] obtained from
 * `ConvertSDK.createContext()`. Tests supply a simple fake so the
 * ViewModel can be exercised without building a real SDK (which
 * requires an Android [android.content.Context]).
 *
 * Keeping this interface tiny — only the two methods the screen drives —
 * lets the ViewModel ignore everything else `ConvertContext` exposes
 * (attribute setters, conversion tracking, feature evaluation …) and
 * keeps the test double trivial.
 */
interface ExperienceRunner {

    /**
     * Mirrors [com.convert.sdk.android.ConvertContext.runExperience].
     * Returns the bucketed [Variation] or `null` when the visitor is
     * not bucketed, the experience is unknown, or the SDK is not ready.
     *
     * Firing the internal `bucketing` event on the SDK's pub/sub bus
     * (which the Event Inspector subscribes to) is a side-effect of the
     * underlying `runExperience` call, NOT something this contract
     * specifies — the ViewModel relies on that side-effect to light up
     * the inspector but does not assert it here, so tests can use a
     * minimal fake.
     */
    fun runExperience(experienceKey: String): Variation?

    /**
     * Mirrors [com.convert.sdk.android.ConvertContext.runExperiences].
     * Returns every variation the visitor is bucketed into, or an empty
     * list when the visitor is in no experiences (or the config is not
     * ready). Never returns `null`.
     */
    fun runExperiences(): List<Variation>
}
