/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Runtime visitor state consumed by the bucketing and rule engines.
 *
 * This is a pure data holder — not a manager, port, or coroutine scope.
 * Instances are short-lived: the SDK re-builds one before each call to a
 * bucketing/feature method using the latest attributes the consumer has
 * set on [com.convert.sdk.android.ConvertContext].
 *
 * @property visitorId stable visitor identifier.
 * @property attributes free-form visitor attributes used by rule matching.
 * @property locationProperties inputs used to match against location rules.
 * @property defaultSegments built-in segment values (country, device, …).
 * @property customSegments merchant-supplied segment values.
 */
@Serializable
public data class VisitorContext(
    val visitorId: String,
    val attributes: Map<String, JsonElement>? = null,
    val locationProperties: Map<String, JsonElement>? = null,
    val defaultSegments: Map<String, String>? = null,
    val customSegments: Map<String, JsonElement>? = null,
)
