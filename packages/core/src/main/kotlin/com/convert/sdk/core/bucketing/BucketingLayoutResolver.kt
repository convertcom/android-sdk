/*
 * Convert Android SDK — core/bucketing
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.bucketing

import com.convert.sdk.core.model.BucketingAllocation
import com.convert.sdk.core.model.VariationAllocation
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.model.generated.VariationStatuses
import java.math.BigDecimal

/**
 * Anchored-vs-packed layout resolution — qs-01 / contract v12.
 *
 * This file is the single testable seam through which BOTH the golden-vector
 * parity suite AND [com.convert.sdk.android.ConvertContext] (Phase 2) decide
 * which bucketing layout an experience uses and build that layout's inputs.
 * Housing the gate + allocation builders in `:packages:core` (rather than
 * only in the Android-only `:packages:sdk` module, as the original grounded
 * design sketched) lets the golden-vector parity test — which lives beside
 * the fixture in `:packages:core` — exercise the FULL version-gated decision
 * without standing up a Robolectric `ConvertSDK` harness. See the qs-01
 * decision log for the full rationale.
 *
 * Mirrors the JS SDK's `DataManager` fresh-bucketing branch
 * (`packages/data/src/data-manager.ts`): `_retrieveBucketing`'s
 * `isAnchoredLayout` gate, `_buildVariationAllocations`, and
 * `_buildPackedBuckets`.
 */

/** JS-parity default weight for a variation with no `traffic_allocation` set (`isNaN(ta) -> 100`). */
private const val DEFAULT_VARIATION_PCT: Double = 100.0

/**
 * Anchored-layout gate threshold — qs-01 / contract v12 AC1. `version` must
 * compare strictly greater than this (via [BigDecimal.compareTo], never
 * `equals`) to activate the anchored layout. Mirrors the JS SDK's
 * `Number(experience.version) > 11`.
 */
private val ANCHORED_LAYOUT_VERSION_THRESHOLD: BigDecimal = BigDecimal("11")

/**
 * Anchored-vs-packed GATE — qs-01 / contract v12 AC1. `version > 11` runs
 * the anchored layout; `version <= 11`, missing, or non-numeric keeps the
 * packed cumulative walk. Uses [BigDecimal.compareTo] (via the `>`
 * operator), never `equals` — `BigDecimal("11.0") != BigDecimal("11")` under
 * `equals`, but both must compare `<= 11` here.
 *
 * @param version the experience's `version` field, already coerced from the
 *   wire's numeric-or-numeric-string form by [com.convert.sdk.core.internal.BigDecimalSerializer].
 * @return `true` iff the anchored layout (contract v12) should run.
 */
internal fun isAnchoredLayout(version: BigDecimal?): Boolean =
    version != null && version > ANCHORED_LAYOUT_VERSION_THRESHOLD

/**
 * Builds the ordered [VariationAllocation] list the anchored layout
 * consumes — qs-01 / contract v12 AC5. Mirrors the JS SDK's
 * `_buildVariationAllocations`: null-id entries are dropped (JS:
 * `if (!variation?.id) return allocations;`), remaining entries keep config
 * order, `allocation` defaults absent/non-numeric `traffic_allocation` to
 * `100.0`, and `active` is `false` for a non-`RUNNING` status OR an explicit
 * zero allocation (AC4 — never defaults a stopped/zero arm back to 100%).
 *
 * @param variations the experience's variations, in declaration order.
 * @return the anchored allocation inputs, in the same order as [variations]
 *   (minus null-id entries).
 */
internal fun buildVariationAllocations(
    variations: List<ExperienceVariationConfig>?,
): List<VariationAllocation> =
    variations
        ?.mapNotNull { variation ->
            val id = variation.id ?: return@mapNotNull null
            val allocation = variation.trafficAllocation?.toDouble() ?: DEFAULT_VARIATION_PCT
            val statusOk = variation.status == null || variation.status == VariationStatuses.RUNNING
            VariationAllocation(
                id = id,
                allocation = allocation,
                active = statusOk && allocation > 0.0,
            )
        }
        ?: emptyList()

/**
 * Builds the `variationId -> percentage` map for the packed layout — the
 * frozen `version <= 11` path (AC6). This is a straight, unmodified port of
 * [com.convert.sdk.android.ConvertContext]'s existing `buildBuckets` filter
 * chain (itself a mirror of the JS SDK's `_buildPackedBuckets`), relocated
 * here so [resolveVariationId] has a single packed-vs-anchored branch point
 * that both the parity test and (Phase 2) `ConvertContext` share. Not a
 * Phase-1 stub: this is already-shipped, already-tested behaviour being
 * moved, not new logic.
 *
 * @param variations the experience's variations, in declaration order.
 * @return ordered map of eligible variation id to traffic percentage.
 */
internal fun buildPackedBuckets(variations: List<ExperienceVariationConfig>?): Map<String, Double> =
    variations
        ?.asSequence()
        ?.filter { it.id != null }
        ?.filter { it.status == null || it.status == VariationStatuses.RUNNING }
        ?.map { it to (it.trafficAllocation?.toDouble() ?: DEFAULT_VARIATION_PCT) }
        ?.filter { (_, allocation) -> allocation > 0.0 }
        ?.associateByTo(
            destination = linkedMapOf(),
            keySelector = { (variation, _) -> variation.id!! },
            valueTransform = { (_, allocation) -> allocation },
        )
        ?: emptyMap()

/**
 * The single version-gated bucketing decision — qs-01 / contract v12. Both
 * the golden-vector parity test (`AnchoredBucketingParityTest`) and, from
 * Phase 2 onward, [com.convert.sdk.android.ConvertContext.allocateAndRecord]
 * call this one function so there is never a divergent duplicate of the
 * gate + allocation-building logic.
 *
 * @param version the experience's `version` field (see [isAnchoredLayout]).
 * @param variations the experience's variations, in declaration order.
 * @param visitorId the visitor's opaque stable identifier.
 * @param experienceId the experience's stable identifier — empty when
 *   `excludeExperienceIdHash` is set.
 * @return a [BucketingAllocation] on success, or `null` when the visitor is
 *   not bucketed into any variation.
 */
public fun BucketingManager.resolveVariationId(
    version: BigDecimal?,
    variations: List<ExperienceVariationConfig>?,
    visitorId: String,
    experienceId: String = "",
): BucketingAllocation? =
    if (isAnchoredLayout(version)) {
        getBucketForVisitorAnchored(
            allocations = buildVariationAllocations(variations),
            visitorId = visitorId,
            experienceId = experienceId,
        )
    } else {
        val buckets = buildPackedBuckets(variations)
        if (buckets.isEmpty()) {
            null
        } else {
            getBucketForVisitor(
                buckets = buckets,
                visitorId = visitorId,
                experienceId = experienceId,
            )
        }
    }
