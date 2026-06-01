/*
 * Convert Android SDK Demo App — FeatureResult
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import java.util.concurrent.atomic.AtomicLong

/**
 * A single Features-screen outcome (Story 7.4).
 *
 * Produced by [SdkViewModel.runFeature] / [SdkViewModel.runFeatures] and
 * consumed by [com.convert.sdk.demo.ui.screen.FeatureResultCard] via the
 * Features screen.
 *
 * Two shapes — identical in spirit to 7.3's [ExperienceResult]:
 *
 *  - **Non-error** (`isError == false`): the feature evaluated.
 *    [enabled] carries its activation state, [experienceKey] carries
 *    the owning experience's key (features resolve through experience
 *    bucketing per Story 4.1 AC-3), and [variables] carries the typed
 *    variable rows the card renders as `name: value [Type]`.
 *  - **Error** (`isError == true`): `runFeature` returned `null`
 *    (feature unknown, not eligible, or config not loaded) or
 *    `runFeatures` returned an empty list. [errorMessage] + [errorHint]
 *    carry user-facing copy — the screen does not inspect [enabled]
 *    / [experienceKey] / [variables] on error cards.
 *
 * @property id monotonically-increasing unique id. Used as the
 *   `LazyColumn` `items(key = ...)` value to keep Compose stable across
 *   recompositions (mirrors [InspectorEvent.id] / [ExperienceResult.id]).
 * @property featureKey the feature key the developer asked about.
 *   Always set — even in the run-all-empty error path, the ViewModel
 *   synthesises a string such as `"(none)"` so the card still has a
 *   title the screen can render.
 * @property enabled non-error: the feature's activation state
 *   (`true` == ENABLED, `false` == DISABLED).
 *   Error: ignored — the screen hides the `Status` row on error cards.
 * @property experienceKey the owning experience's key when the feature
 *   resolved via bucketing, or `null` when absent (orphan features or
 *   config-only metadata).
 * @property variables the typed-variable rows; empty for disabled
 *   features (whose `Feature.variables` map is `null`) and for error
 *   results.
 * @property isError `true` for the red-bordered "no feature" /
 *   "no eligible features" card.
 * @property errorMessage human-readable headline shown as the card
 *   title when [isError]. `null` otherwise.
 * @property errorHint developer-facing suggestion shown as the "Hint"
 *   item when [isError]. `null` otherwise.
 */
public data class FeatureResult(
    val id: Long,
    val featureKey: String,
    val enabled: Boolean,
    val experienceKey: String? = null,
    val variables: List<TypedVariable> = emptyList(),
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val errorHint: String? = null,
) {
    public companion object {
        private val idSeq: AtomicLong = AtomicLong(0L)

        /** Mints the next monotonic id. Thread-safe. Independent counter
         *  from [ExperienceResult.nextId] so the two result streams in
         *  the same ViewModel do not share state. */
        public fun nextId(): Long = idSeq.incrementAndGet()
    }
}
