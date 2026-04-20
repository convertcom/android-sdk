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
 * Feature flag result returned to consumers.
 *
 * Flattens the JS SDK `BucketedFeature` union into a single data class. The
 * derived [enabled] property is the preferred way to check whether a feature
 * is on — it reflects [status] rather than any owning-experience metadata.
 *
 * @property id optional stable identifier of the feature.
 * @property key optional key of the feature.
 * @property name optional display name of the feature.
 * @property status whether the feature is enabled or disabled for the caller.
 * @property variables optional loosely-typed variables exposed by the feature.
 * @property experienceId optional identifier of the owning experience.
 * @property experienceKey optional key of the owning experience.
 * @property experienceName optional display name of the owning experience.
 */
@Serializable
public data class Feature(
    val id: String? = null,
    val key: String? = null,
    val name: String? = null,
    val status: FeatureStatus,
    val variables: Map<String, JsonElement>? = null,
    @SerialName("experience_id")
    val experienceId: String? = null,
    @SerialName("experience_key")
    val experienceKey: String? = null,
    @SerialName("experience_name")
    val experienceName: String? = null,
) {

    /** `true` when [status] is [FeatureStatus.ENABLED]. */
    val enabled: Boolean get() = status == FeatureStatus.ENABLED
}
