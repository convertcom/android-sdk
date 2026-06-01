/*
 * Convert Android SDK Demo App — ConversionResult
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import java.util.concurrent.atomic.AtomicLong

/**
 * A single Conversions-screen outcome (Story 7.5).
 *
 * Produced by [SdkViewModel.trackPurchaseConversion] and consumed by
 * [com.convert.sdk.demo.ui.screen.ConversionResultCard] via the
 * Conversions screen.
 *
 * Three shapes — mirroring 7.4's [FeatureResult] style:
 *
 *  - **Non-dedup, non-error** (`isDedup == false`, `isError == false`):
 *    the first successful `trackConversion` for this goal key in the
 *    current session. [amount] and [productsCount] carry the goal-data
 *    values sent to the SDK (`10.3` and `2` for the hardcoded demo
 *    goal per AC-1).
 *  - **Dedup** (`isDedup == true`): a repeat tap for the same goal key
 *    that the SDK silently skipped per Story 4.3. The card renders
 *    "Conversion already tracked (dedup)" (AC-3); [amount] and
 *    [productsCount] are ignored by the screen.
 *  - **Error** (`isError == true`): reserved for future failure modes
 *    (e.g. SDK not ready, configuration missing). Not produced by the
 *    current [SdkViewModel.trackPurchaseConversion] path but included
 *    so the [com.convert.sdk.demo.ui.screen.ConversionResultCard]
 *    red-border style is available if a later story wires one up.
 *
 * @property id monotonically-increasing unique id. Used as the
 *   `LazyColumn` `items(key = ...)` value to keep Compose stable across
 *   recompositions (mirrors [InspectorEvent.id] / [FeatureResult.id]).
 * @property goalKey the goal key the developer asked the SDK to track
 *   (for the demo: `"purchase-goal"`).
 * @property amount the `AMOUNT` goal-data value sent to the SDK, or
 *   `null` for dedup / error results.
 * @property productsCount the `PRODUCTS_COUNT` goal-data value sent to
 *   the SDK, or `null` for dedup / error results.
 * @property isDedup `true` when this card represents a second tap that
 *   the SDK silently skipped per Story 4.3 AC-6 dedup semantics.
 * @property isError `true` for the red-bordered failure card.
 * @property errorMessage human-readable headline shown as the card
 *   title when [isError]. `null` otherwise.
 * @property errorHint developer-facing suggestion shown as the "Hint"
 *   item when [isError]. `null` otherwise.
 */
public data class ConversionResult(
    val id: Long,
    val goalKey: String,
    val amount: Double? = null,
    val productsCount: Int? = null,
    val isDedup: Boolean = false,
    val isError: Boolean = false,
    val errorMessage: String? = null,
    val errorHint: String? = null,
) {
    public companion object {
        private val idSeq: AtomicLong = AtomicLong(0L)

        /** Mints the next monotonic id. Thread-safe. Independent counter
         *  from [FeatureResult.nextId] and [ExperienceResult.nextId] so
         *  the three result streams in the same ViewModel do not share
         *  state. */
        public fun nextId(): Long = idSeq.incrementAndGet()
    }
}
