/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Well-known conversion-goal payload keys accepted by the Convert tracking
 * API.
 *
 * **String values are camelCase, NOT snake_case.** The tracking API
 * deliberately uses camelCase for these specific keys, mirroring the JS SDK
 * `GoalDataKey` enum
 * (`javascript-sdk/packages/enums/src/goal-data-key.ts`). Any change here
 * must be reflected in the JS SDK first — payload compatibility is
 * enforced by a round-trip test in `GoalDataTest`.
 */
@Serializable
public enum class GoalDataKey {

    /** Monetary amount associated with the goal. */
    @SerialName("amount")
    AMOUNT,

    /** Number of products associated with the goal (camelCase JSON). */
    @SerialName("productsCount")
    PRODUCTS_COUNT,

    /** Merchant-defined transaction identifier (camelCase JSON). */
    @SerialName("transactionId")
    TRANSACTION_ID,

    /** First custom dimension (camelCase JSON). */
    @SerialName("customDimension1")
    CUSTOM_DIMENSION_1,

    /** Second custom dimension (camelCase JSON). */
    @SerialName("customDimension2")
    CUSTOM_DIMENSION_2,

    /** Third custom dimension (camelCase JSON). */
    @SerialName("customDimension3")
    CUSTOM_DIMENSION_3,

    /** Fourth custom dimension (camelCase JSON). */
    @SerialName("customDimension4")
    CUSTOM_DIMENSION_4,

    /** Fifth custom dimension (camelCase JSON). */
    @SerialName("customDimension5")
    CUSTOM_DIMENSION_5,
}
