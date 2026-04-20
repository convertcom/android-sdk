/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Per-visitor persisted state held in the [com.convert.sdk.core.port.DataStore].
 *
 * Mirrors the JS SDK's `StoreData`
 * (`javascript-sdk/packages/types/src/StoreData.ts`). Every section is
 * nullable so that brand-new visitors can be represented as
 * `StoreData()`.
 *
 * @property bucketing map from experience key to the bucketed variation id
 *   for this visitor.
 * @property locations location keys that the visitor currently matches.
 * @property segments custom segment values already resolved for this visitor.
 * @property goals map from goal key to `true` once the goal has been
 *   tracked for this visitor (used for one-time-goal deduplication).
 */
@Serializable
public data class StoreData(
    val bucketing: Map<String, String>? = null,
    val locations: List<String>? = null,
    val segments: Map<String, JsonElement>? = null,
    val goals: Map<String, Boolean>? = null,
)
