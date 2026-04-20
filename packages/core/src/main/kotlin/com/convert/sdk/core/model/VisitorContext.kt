/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Runtime visitor state consumed by the bucketing and rule engines.
 *
 * This is a pure data holder — it is not a port and does not own any behavior.
 * Managers (Stories 3.x) will accept or produce this type when they need to
 * resolve experiences and features for a particular visitor.
 *
 * @property visitorId stable identifier for the visitor.
 * @property attributes caller-supplied attributes used for rule evaluation.
 * @property locationProperties properties used for location/page matching.
 * @property defaultSegments string-valued segment defaults (e.g., browser,
 *  device); mirrors the JS SDK `defaultSegments` shape.
 * @property customSegments loosely-typed caller-supplied segment overrides.
 */
@Serializable
public data class VisitorContext(
    val visitorId: String,
    val attributes: Map<String, JsonElement>? = null,
    val locationProperties: Map<String, JsonElement>? = null,
    val defaultSegments: Map<String, String>? = null,
    val customSegments: Map<String, JsonElement>? = null,
)
