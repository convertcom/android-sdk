/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Key/value pair describing a single conversion-goal dimension.
 *
 * The JS SDK allows `value` to be either a number or a string, which maps to
 * [JsonElement] here so the pair can carry any JSON primitive without
 * reflection.
 *
 * @property key optional semantic key for this value; when `null` the SDK
 *  does not attach a named dimension.
 * @property value optional value for [key]. Supports numbers and strings via
 *  `JsonPrimitive`, matching the JS SDK type.
 */
@Serializable
public data class GoalData(
    val key: GoalDataKey? = null,
    val value: JsonElement? = null,
)
