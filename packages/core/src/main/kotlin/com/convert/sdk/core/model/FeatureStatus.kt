/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Activation state of a feature flag as returned by the Convert API.
 *
 * String values mirror the JS SDK enum
 * (`javascript-sdk/packages/enums/src/feature-status.ts`) so that JSON
 * payloads round-trip across SDKs.
 */
@Serializable
public enum class FeatureStatus {

    /** The feature is enabled for the current visitor. */
    @SerialName("enabled")
    ENABLED,

    /** The feature is disabled for the current visitor. */
    @SerialName("disabled")
    DISABLED,
}
