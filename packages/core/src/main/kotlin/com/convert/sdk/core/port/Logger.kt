/*
 * Convert Android SDK — core/port
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.port

/**
 * Logging abstraction for the core module.
 *
 * The core module never imports `android.util.Log`. An adapter in the SDK
 * module (`AndroidLogger`, Story 2.2) bridges this port to Logcat; tests
 * use [NoOp] or a custom capturing fake.
 */
internal interface Logger {

    /**
     * Logs an error-level message, optionally with a [throwable].
     *
     * @param message human-readable message.
     * @param throwable associated exception, or `null` if none.
     */
    fun error(message: String, throwable: Throwable? = null)

    /**
     * Logs a warning-level message, optionally with a [throwable].
     *
     * @param message human-readable message.
     * @param throwable associated exception, or `null` if none.
     */
    fun warn(message: String, throwable: Throwable? = null)

    /**
     * Logs an info-level message.
     *
     * @param message human-readable message.
     */
    fun info(message: String)

    /**
     * Logs a debug-level message.
     *
     * @param message human-readable message.
     */
    fun debug(message: String)

    companion object {

        /**
         * No-op [Logger] instance suitable for the SDK's default state and for
         * tests that want to ignore log output entirely.
         */
        val NoOp: Logger = object : Logger {
            override fun error(message: String, throwable: Throwable?) = Unit
            override fun warn(message: String, throwable: Throwable?) = Unit
            override fun info(message: String) = Unit
            override fun debug(message: String) = Unit
        }
    }
}
