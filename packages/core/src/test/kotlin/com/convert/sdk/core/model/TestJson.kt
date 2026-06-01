/*
 * Convert Android SDK — core/model test helpers
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.json.Json

/**
 * Shared `Json` configuration used by every model round-trip test.
 *
 * - `ignoreUnknownKeys = true` tolerates fields added by newer config
 *   responses without breaking older SDK consumers.
 * - `encodeDefaults = false` keeps wire payloads small by omitting
 *   properties whose runtime value equals their declared default.
 * - `explicitNulls = false` drops `null` properties from the output, which
 *   matches the JS SDK's payload shape.
 */
internal val testJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    explicitNulls = false
    // TrackingEvent sealed hierarchy uses "eventType" as the polymorphic
    // discriminant (AC-5) — must match the JS SDK wire contract.
    classDiscriminator = "eventType"
}
