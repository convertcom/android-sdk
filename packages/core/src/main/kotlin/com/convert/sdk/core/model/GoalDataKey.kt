/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Keys describing the semantic meaning of a [GoalData] value.
 *
 * JSON values are camelCase — NOT snake_case — to match the tracking API and
 * the JS SDK `GoalDataKey` enum. Do not change these without updating every
 * downstream consumer.
 */
@Serializable
public enum class GoalDataKey {

    /** Monetary amount, typically a transaction value. */
    @SerialName("amount")
    AMOUNT,

    /** Number of products in the transaction. */
    @SerialName("productsCount")
    PRODUCTS_COUNT,

    /** Stable identifier for the transaction. */
    @SerialName("transactionId")
    TRANSACTION_ID,

    /** Custom dimension slot 1. */
    @SerialName("customDimension1")
    CUSTOM_DIMENSION_1,

    /** Custom dimension slot 2. */
    @SerialName("customDimension2")
    CUSTOM_DIMENSION_2,

    /** Custom dimension slot 3. */
    @SerialName("customDimension3")
    CUSTOM_DIMENSION_3,

    /** Custom dimension slot 4. */
    @SerialName("customDimension4")
    CUSTOM_DIMENSION_4,

    /** Custom dimension slot 5. */
    @SerialName("customDimension5")
    CUSTOM_DIMENSION_5,
}
