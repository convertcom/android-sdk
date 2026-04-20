/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Status of a feature flag returned by the SDK.
 *
 * JSON values match the JS SDK `FeatureStatus` enum.
 */
@Serializable
public enum class FeatureStatus {

    /** Feature is enabled for the current visitor. */
    @SerialName("enabled")
    ENABLED,

    /** Feature is disabled (either explicitly or because no experience matched). */
    @SerialName("disabled")
    DISABLED,
}
