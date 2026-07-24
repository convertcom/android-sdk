/*
 * Convert Android SDK — core/rules
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.rules

/**
 * Resolves whether the visitor is currently bucketed into a target
 * experience, for the `bucketed_into_experience_key` mutual-exclusion
 * rule element handled by [RuleManager] (AND-1, qs-03).
 *
 * ### Android storage-shape reality divergence
 *
 * [experienceKey] is the experience **KEY**, not its numeric id. This
 * matches Android's `StoreData.bucketing` shape, which is keyed by
 * experience KEY (see `StoreData.kt`, read site `ConvertContext.kt`).
 * The qs-03 spec's normative algorithm keys by `id.toString()` — that is
 * the JS SDK's storage shape, not Android's. This divergence is
 * intentional and logged in full at
 * `ai-driven-product-dev/work/2026-07-15-android-sdk-mutual-exclusion/decision-log.md`.
 *
 * ### Three-state contract
 *
 * The nullable [Boolean] return collapses three logical states:
 *  - `null` — the target experience KEY is not a known/served experience
 *    (unresolvable target) — the caller logs a WARN naming the key.
 *  - `false` — the target experience is known, but the visitor has no
 *    bucketing entry for it.
 *  - `true` — the target experience is known and the visitor is
 *    bucketed into it.
 */
public fun interface BucketedExperienceResolver {
    /**
     * Returns whether the visitor is bucketed into the experience
     * identified by [experienceKey] — see the three-state contract on
     * the enclosing interface KDoc.
     */
    public fun isBucketed(experienceKey: String): Boolean?
}
