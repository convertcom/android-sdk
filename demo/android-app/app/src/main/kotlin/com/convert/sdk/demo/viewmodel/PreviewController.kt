/*
 * Convert Android SDK Demo App — PreviewController contract
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

/**
 * qs-08 (experiment-preview) demo testbed — narrow seam the [SdkViewModel]
 * uses to drive the SDK's preview surface from a deep link or manual
 * in-app input.
 *
 * Production wires this to [com.convert.sdk.demo.DemoApplication.previewController],
 * which builds a DEDICATED preview [com.convert.sdk.android.ConvertContext]
 * isolated from the pre-warmed base context ([com.convert.sdk.demo.DemoApplication.contextDeferred]).
 * There is no SDK-level "clear preview" API
 * ([com.convert.sdk.android.ConvertContext.setPreview] is a one-way forced
 * decision for the lifetime of the context it is called on) — the demo
 * achieves a working Clear by dropping the dedicated preview context and
 * falling back to the base one, rather than mutating a shared context.
 *
 * Tests supply a simple recording fake so [SdkViewModel] can be exercised
 * without building a real SDK (which requires an Android
 * [android.content.Context]).
 */
interface PreviewController {

    /**
     * Applies a forced preview decision for [experienceId] / [variationId]
     * (numeric-id strings; mirrors
     * [com.convert.sdk.android.ConvertContext.setPreview]).
     */
    fun setPreview(experienceId: String, variationId: String)

    /**
     * Clears the active preview override, if any, restoring normal
     * bucketing/tracking/persistence on the base context.
     */
    fun clearPreview()

    /** Whether a preview override is currently active. */
    fun isPreviewActive(): Boolean
}

/**
 * Default [PreviewController] used by the production [SdkViewModel]
 * constructor when the caller does not supply one. Mirrors the other
 * `NoOp*` defaults in `SdkViewModel.kt` — the real SDK-backed impl is
 * wired in [com.convert.sdk.demo.DemoApplication]; the no-op exists so
 * existing tests keep constructing [SdkViewModel] with a minimal
 * parameter list.
 */
internal object NoOpPreviewController : PreviewController {
    override fun setPreview(experienceId: String, variationId: String) {
        // No-op — matches NoOpExperienceRunner / NoOpFeatureRunner / etc.
    }

    override fun clearPreview() {
        // No-op.
    }

    override fun isPreviewActive(): Boolean = false
}
