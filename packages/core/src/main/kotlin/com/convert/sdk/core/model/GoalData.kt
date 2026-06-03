/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Single goal-data entry accompanying a conversion tracking call.
 *
 * Mirrors the JS SDK's `GoalData`
 * (`javascript-sdk/packages/types/src/GoalData.ts`). The [value] type is
 * [JsonElement] so that callers can pass `Int`, `Double`, or `String`
 * primitives without the SDK owning a discriminated union.
 *
 * Both fields are nullable because the tracking API permits partial
 * payloads (for example, a bare goal key with no value).
 *
 * @property key one of the well-known [GoalDataKey] tokens identifying
 *   this data point. May be `null` for payloads that omit the key.
 * @property value loosely-typed value associated with [key]. May be `null`.
 */
@Serializable
public data class GoalData(
    val key: GoalDataKey? = null,
    val value: JsonElement? = null,
)
