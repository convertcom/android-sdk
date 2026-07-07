/*
 * Convert Android SDK — core/preview
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.preview

import com.convert.sdk.core.model.Variation
import com.convert.sdk.core.model.generated.ConfigExperience

/**
 * Forced-variation decision primitive — qs-02 / AND-3 (AC4, AC5).
 *
 * Mirrors the JS SDK's `DataManager.getPreviewDecision`
 * (`javascript-sdk/packages/data/src/data-manager.ts:1080-1095`): given the
 * preview experience and a target variation id, produce a decision AS that
 * variation while bypassing audiences, segments, locations, the environment
 * check, experience status, variation status/traffic filters, stored
 * (sticky) decisions, and the bucketing hash.
 *
 * ### Why this is a bypass by construction, not by relaxed filtering
 *
 * [resolve] never calls [com.convert.sdk.core.bucketing.BucketingManager.resolveVariationId]
 * (or any of the status/traffic filters in `BucketingLayoutResolver` — those
 * stay intact for normal bucketing) and never reads `experience.status`,
 * `experience.environment`, `experience.audiences`, `experience.locations`,
 * or a variation's own `status` / `trafficAllocation`. It also takes no
 * `visitorId` parameter at all, so there is structurally no hash input to
 * bypass — the signature itself rules out consulting the bucketing hash or
 * any per-visitor stored (sticky) decision. The only gate is a plain lookup
 * of [variationId] inside [experience]'s variations list.
 *
 * ### Return shape
 *
 * Identical to a normal bucketed decision — mirrors
 * `ConvertContext.toPublicVariation`'s field mapping exactly, with
 * `bucketingAllocation = null` (matching the sticky fast-path convention:
 * no fresh hash was computed for this decision) and `changes = null`
 * (Story 3.3 territory, not copied by the mirrored function either).
 *
 * ### Scope note
 *
 * This is the primitive only. Wiring `ConvertContext.setPreview`,
 * per-context preview state, the `?exp=` fetch-on-miss, and zero-trace
 * tracking/persistence suppression are AND-5 / AND-6.
 */
public object PreviewDecision {

    /**
     * Resolves the forced decision for [variationId] within [experience].
     *
     * @param experience the preview experience — may be any status
     *   (including draft/paused), from any environment, sourced from the
     *   normal config or a dedicated `?exp=` fetch.
     * @param variationId the target variation id, as parsed from the
     *   `convert_preview={experienceId}.{variationId}` deep-link value by
     *   [PreviewParam.parse].
     * @return the forced [Variation], or `null` when [variationId] is not
     *   present in [experience]'s variations list (AND-5 handles the
     *   inert-on-bad-input warning log for this case).
     */
    @JvmStatic
    public fun resolve(experience: ConfigExperience, variationId: String): Variation? {
        val variation = experience.variations?.firstOrNull { it.id == variationId } ?: return null
        return Variation(
            id = variation.id,
            key = variation.key,
            name = variation.name,
            experienceId = experience.id,
            experienceKey = experience.key,
            experienceName = experience.name,
            bucketingAllocation = null,
            changes = null,
        )
    }
}
