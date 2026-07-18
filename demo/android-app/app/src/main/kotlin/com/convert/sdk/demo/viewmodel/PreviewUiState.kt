/*
 * Convert Android SDK Demo App — PreviewUiState
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

/**
 * qs-08 (experiment-preview) demo testbed — UI-facing state for the
 * preview banner rendered on the Experiences screen. Exposed by
 * [SdkViewModel.previewState].
 */
sealed interface PreviewUiState {

    /** No preview override applied — the base context buckets normally. */
    data object Inactive : PreviewUiState

    /**
     * A preview override is active, forcing [variationId] for
     * [experienceId] on the previewed context.
     */
    data class Active(val experienceId: String, val variationId: String) : PreviewUiState

    /**
     * The last [SdkViewModel.applyPreviewParam] call received a value
     * that [com.convert.sdk.core.preview.PreviewParam.parse] could not
     * parse (AC9 — malformed input never crashes the demo).
     */
    data class Error(val message: String) : PreviewUiState
}
