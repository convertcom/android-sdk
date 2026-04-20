/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * The result of bucketing a visitor into a variation of an experience.
 *
 * Mirrors the JS SDK `BucketedVariation` shape flattened into a single data
 * class. Loosely-typed `changes` payloads use [JsonElement] so the SDK can
 * round-trip arbitrary JSON without losing information and without depending
 * on reflection.
 *
 * @property id stable identifier of the variation.
 * @property key human-readable key of the variation.
 * @property name optional display name.
 * @property experienceId optional identifier of the owning experience.
 * @property experienceKey optional key of the owning experience.
 * @property experienceName optional display name of the owning experience.
 * @property bucketingAllocation allocation percentage the visitor fell into.
 * @property changes optional list of variation change payloads; shape is
 *  opaque to the SDK and forwarded to consumers as raw JSON elements.
 */
@Serializable
public data class Variation(
    val id: String,
    val key: String,
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
