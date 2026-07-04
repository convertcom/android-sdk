/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable

/**
 * One variation's weight + activity state, as consumed by the anchored
 * bucketing layout (qs-01 / contract v12).
 *
 * Mirrors the JS SDK's `VariationAllocation`
 * (`@convertcom/js-sdk-types`, `packages/types/src/VariationAllocation.ts`):
 * exactly the three fields the anchored algorithm needs. Callers build an
 * ordered [List] (never a [Map] — inactive arms and declaration order both
 * matter for anchor stability) via the layout resolver's allocation
 * builder, then hand it to
 * [com.convert.sdk.core.bucketing.BucketingManager.getBucketRanges] /
 * [com.convert.sdk.core.bucketing.BucketingManager.getBucketForVisitorAnchored].
 *
 * @property id the variation id, as it appears in the backing experience's
 *   variations list.
 * @property allocation the resolved weight in `0..100` traffic-percentage
 *   units — already defaulted (`isNaN(ta) ? 100.0 : ta` in the JS
 *   reference) so this field is never `NaN`. Kept for **every** entry
 *   (active and inactive) because [active]`false` entries still contribute
 *   their weight to the anchor space (anchor stability under stops).
 * @property active whether this entry claims a non-zero-width range in the
 *   anchored layout. `false` for a `stopped` variation or an explicit
 *   `traffic_allocation: 0` — the entry's weight still counts toward
 *   `totalWeight`, but its range width is forced to zero.
 */
@Serializable
public data class VariationAllocation(
    public val id: String,
    public val allocation: Double,
    public val active: Boolean,
)
