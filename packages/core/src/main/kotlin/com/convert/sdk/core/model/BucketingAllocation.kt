/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable

/**
 * Result of a single deterministic bucketing decision produced by
 * [com.convert.sdk.core.bucketing.BucketingManager].
 *
 * Mirrors the JS SDK's `BucketingAllocation`
 * (`javascript-sdk/packages/types/src/BucketingAllocation.ts`): exactly two
 * non-null fields — the selected variation's id and the numeric allocation
 * value produced by the hash pipeline.
 *
 * Both fields are non-nullable because an allocation instance is only
 * constructed when the bucketing engine has successfully resolved a
 * variation — partial allocations are represented by returning `null`
 * from the producing call, not by nulling these fields.
 *
 * ### Why `bucketingAllocation` is [Int], not [Double]
 *
 * The JS SDK's bucketing pipeline produces a basis-of-10000 integer via
 * `parseInt(String(val), 10)` after the floating-point division. Storing
 * the same truncated integer here keeps the Kotlin and JS SDKs
 * bit-identical on the bucketing-allocation value exchanged with
 * downstream consumers (Story 3.5 validates this across 50+ shared test
 * vectors).
 *
 * @property variationId the id of the selected variation, as it appears
 *   in the backing experience's variations list.
 * @property bucketingAllocation the truncated hash-derived value in the
 *   `0..maxTraffic` range (default `maxTraffic = 10000`). Emitted verbatim
 *   to the tracking API so that upstream analytics see a stable integer.
 */
@Serializable
public data class BucketingAllocation(
    public val variationId: String,
    public val bucketingAllocation: Int,
)
