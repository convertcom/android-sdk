/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Bucketed variation returned by [com.convert.sdk.android.ConvertContext.runExperience].
 *
 * The shape mirrors the JS SDK's `BucketedVariation`
 * (`javascript-sdk/packages/types/src/BucketedVariation.ts`) flattened
 * into a single data class. Field JSON names use snake_case except for
 * fields explicitly marked with `@SerialName`.
 *
 * Every field is nullable because the JS SDK's `ExperienceVariationConfig`
 * declares `id`, `key`, and `name` as optional — the Convert config API
 * can legitimately omit them. A non-nullable type here would throw on
 * deserialisation the first time the backend sent a partial response.
 *
 * @property id stable variation identifier; may be absent in partial responses.
 * @property key merchant-defined variation key; may be absent in partial responses.
 * @property name human-readable variation name.
 * @property experienceId stable identifier of the owning experience.
 * @property experienceKey merchant-defined key of the owning experience.
 * @property experienceName human-readable name of the owning experience.
 * @property bucketingAllocation hash-pipeline value that placed this
 *   visitor in this variation, range `0..maxTraffic` (default `10000`).
 *   Matches JS SDK's `BucketedVariation.bucketingAllocation`: the raw
 *   output of `BucketingManager.getValueVisitorBased`. `null` when the
 *   variation was resolved via the sticky fast-path (no fresh hash).
 * @property changes opaque list of variation changes; shape is loose because
 *   the Convert API allows DOM/JS/style/custom change payloads interchangeably.
 */
@Serializable
public data class Variation(
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
    @SerialName("experience_id")
    val experienceId: String? = null,
    @SerialName("experience_key")
    val experienceKey: String? = null,
    @SerialName("experience_name")
    val experienceName: String? = null,
    @SerialName("bucketing_allocation")
    val bucketingAllocation: Double? = null,
    val changes: List<JsonElement>? = null,
)
