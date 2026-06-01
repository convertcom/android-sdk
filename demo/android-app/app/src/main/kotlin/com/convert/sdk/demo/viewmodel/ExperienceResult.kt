/*
 * Convert Android SDK Demo App — ExperienceResult
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import java.util.concurrent.atomic.AtomicLong

/**
 * A single Experience-screen outcome (Story 7.3).
 *
 * Produced by [SdkViewModel.runSingleExperience] / [SdkViewModel.runAllExperiences]
 * and consumed by
 * [com.convert.sdk.demo.ui.screen.ExperienceResultCard] via the Experiences screen.
 *
 * Two shapes:
 * - **Non-error** (`isError == false`): the visitor was bucketed; [variationKey]
 *   carries the resolved variation (may be `null` when the Convert config
 *   omits the key — `Variation.key` is nullable). [errorMessage] /
 *   [errorHint] are `null`.
 * - **Error** (`isError == true`): either `runExperience` returned `null`
 *   (experience not found, not eligible, config not loaded) or
 *   `runExperiences` returned an empty list. [errorMessage] carries the
 *   user-facing headline; [errorHint] carries a suggestion the developer
 *   can act on.
 *
 * @property id monotonically-increasing unique id. Used as the
 *   `LazyColumn` `items(key = ...)` value to keep Compose stable across
 *   recompositions (mirrors [InspectorEvent.id]).
 * @property experienceKey the experience key the developer asked about.
 *   Always set — even in the run-all-empty error path, the ViewModel
 *   synthesises a string such as `"(any)"` so the card still has a
 *   title the screen can render.
 * @property variationKey the resolved variation key, or `null` when the
 *   Variation's `key` field is absent.
 * @property isError `true` for the red-bordered "no variation" card.
 * @property errorMessage human-readable headline shown as the card
 *   title when [isError]. `null` otherwise.
 * @property errorHint developer-facing suggestion shown as the
 *   "Hint" item when [isError]. `null` otherwise.
 */
data class ExperienceResult(
    val id: Long,
    val experienceKey: String,
    val variationKey: String? = null,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val errorHint: String? = null,
) {
    companion object {
        private val idSeq: AtomicLong = AtomicLong(0L)

        /** Mints the next monotonic id. Thread-safe. */
        fun nextId(): Long = idSeq.incrementAndGet()
    }
}
