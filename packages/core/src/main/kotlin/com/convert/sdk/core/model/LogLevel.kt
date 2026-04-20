/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable

/**
 * Verbosity level for SDK logging, mirroring the JS SDK `LogLevel` enum.
 *
 * Declaration order (`TRACE` → `SILENT`) matches the JS SDK's numeric ordering
 * so consumers can compare levels if needed.
 */
@Serializable
public enum class LogLevel {

    /** Most verbose — traces every decision. */
    TRACE,

    /** Debug-level statements useful during development. */
    DEBUG,

    /** Informational messages, suitable for production. */
    INFO,

    /** Warnings — default production verbosity. */
    WARN,

    /** Errors only. */
    ERROR,

    /** Logging disabled. */
    SILENT,
}
