/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.port

/**
 * Port abstraction for SDK logging.
 *
 * The core module logs exclusively through this interface so that adapters can
 * forward messages to Android's `Log` (Story 2.2), a file logger, or a test
 * spy without coupling the core to any specific logging implementation.
 */
internal interface Logger {

    /**
     * Emits an error-level message, optionally with an associated [throwable].
     */
    fun error(message: String, throwable: Throwable? = null)

    /**
     * Emits a warning-level message, optionally with an associated [throwable].
     */
    fun warn(message: String, throwable: Throwable? = null)

    /**
     * Emits an informational message.
     */
    fun info(message: String)

    /**
     * Emits a debug-level message.
     */
    fun debug(message: String)

    companion object {
        /**
         * Default no-op [Logger] used before the real adapter is wired, or in
         * tests that do not care about log output.
         */
        val NoOp: Logger = object : Logger {
            override fun error(message: String, throwable: Throwable?) = Unit
            override fun warn(message: String, throwable: Throwable?) = Unit
            override fun info(message: String) = Unit
            override fun debug(message: String) = Unit
        }
    }
}
