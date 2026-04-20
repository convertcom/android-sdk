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
 * Bucketed feature flag returned by
 * [com.convert.sdk.android.ConvertContext.runFeature].
 *
 * Shape mirrors the JS SDK's `BucketedFeature`
 * (`javascript-sdk/packages/types/src/BucketedFeature.ts`), flattened to
 * a single data class. The computed [enabled] property matches how
 * consumer code usually checks activation state.
 *
 * @property id stable feature identifier.
 * @property key merchant-defined feature key.
 * @property name human-readable feature name.
 * @property status activation state.
 * @property variables feature-variable key/value pairs; `JsonElement` is
 *   used because variables are loosely typed across consumers.
 * @property experienceId stable identifier of the owning experience.
 * @property experienceKey merchant-defined key of the owning experience.
 * @property experienceName human-readable name of the owning experience.
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
    /**
     * `true` when [status] is [FeatureStatus.ENABLED]. Convenience accessor
     * matching the JS SDK's idiomatic check.
     */
    val enabled: Boolean get() = status == FeatureStatus.ENABLED
}
