/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable

/**
 * Logger severity levels.
 *
 * Enum names mirror the JS SDK `LogLevel` enum
 * (`javascript-sdk/packages/enums/src/log-level.ts`). When serialized,
 * kotlinx.serialization emits the Kotlin name (e.g. `"ERROR"`). The JS SDK
 * uses numeric ordinals internally but serializes the enum name when it
 * crosses the wire — both SDKs therefore interoperate on the `name` form.
 *
 * The Kotlin ordinal order differs from the JS source order and is **not**
 * load-bearing: severity comparison will be introduced in Story 2.2 via an
 * explicit `level: Int` property when the logger adapter lands.
 */
@Serializable
public enum class LogLevel {

    /** Critical failures; always surfaced in production builds. */
    ERROR,

    /** Recoverable problems; surfaced by default. */
    WARN,

    /** High-level informational messages. */
    INFO,

    /** Fine-grained progress messages for development builds. */
    DEBUG,

    /** Extremely verbose tracing output. */
    TRACE,

    /** Disables all logging. */
    SILENT,
}
