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
 * module (`AndroidLogger`, Story 2.1) bridges this port to Logcat; tests
 * use [NoOp] or a custom capturing fake.
 *
 * ### Visibility (Story 2.1)
 *
 * Declared `public` so that `:packages:sdk` adapters — which live in a
 * separate Gradle module and therefore a separate Kotlin `internal`
 * visibility scope — can implement this interface. Consumers of the
 * published `sdk-core` artifact should treat it as SDK-internal: the
 * adapter classes that realise this contract are themselves `internal`
 * to the sdk module, so there is no reason for application code to
 * implement this port.
 *
 * ### Optional `tag` parameter (Story 2.1)
 *
 * Every method accepts an optional `tag` — when supplied, adapters that
 * forward to a tagged sink (Android's `Log`, Java's `java.util.logging`)
 * use it as the log category; when `null`, the adapter uses its default
 * tag (`"ConvertSDK"` for `AndroidLogger`). Callers typically pass the
 * simple class name of the emitting component so Logcat filtering works
 * naturally (`logger.debug(message = "fetched", tag = "DataManager")`).
 */
public interface Logger {

    /**
     * Logs an error-level message, optionally with a [throwable].
     *
     * @param message human-readable message.
     * @param throwable associated exception, or `null` if none.
     * @param tag log category; `null` uses the adapter's default tag.
     */
    public fun error(message: String, throwable: Throwable? = null, tag: String? = null)

    /**
     * Logs a warning-level message, optionally with a [throwable].
     *
     * @param message human-readable message.
     * @param throwable associated exception, or `null` if none.
     * @param tag log category; `null` uses the adapter's default tag.
     */
    public fun warn(message: String, throwable: Throwable? = null, tag: String? = null)

    /**
     * Logs an info-level message.
     *
     * @param message human-readable message.
     * @param tag log category; `null` uses the adapter's default tag.
     */
    public fun info(message: String, tag: String? = null)

    /**
     * Logs a debug-level message.
     *
     * @param message human-readable message.
     * @param tag log category; `null` uses the adapter's default tag.
     */
    public fun debug(message: String, tag: String? = null)

    public companion object {

        /**
         * No-op [Logger] instance suitable for the SDK's default state and for
         * tests that want to ignore log output entirely.
         */
        public val NoOp: Logger = object : Logger {
            override fun error(message: String, throwable: Throwable?, tag: String?) = Unit
            override fun warn(message: String, throwable: Throwable?, tag: String?) = Unit
            override fun info(message: String, tag: String?) = Unit
            override fun debug(message: String, tag: String?) = Unit
        }
    }
}
