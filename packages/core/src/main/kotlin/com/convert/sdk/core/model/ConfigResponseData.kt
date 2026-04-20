/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
// Placeholder until Story 1.5 (OpenAPI type generation) — do not add fields
// manually. Story 1.5 replaces this stub with a generated type whose name is
// identical, so every reference (ConvertConfig.data, Builder.data(...), …)
// continues to compile without changes.
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Placeholder for the full server-supplied project configuration.
 *
 * Until Story 1.5 generates the real type from the backend OpenAPI spec, this
 * stub simply carries the raw JSON element it was decoded from so callers can
 * pass through static configuration without losing information.
 *
 * @property rawJson the raw configuration JSON, or `null` if unknown.
 */
@Serializable
public data class ConfigResponseData(
    val rawJson: JsonElement? = null,
)
