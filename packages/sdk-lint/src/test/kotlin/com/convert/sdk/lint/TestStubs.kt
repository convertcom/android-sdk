/*
 * Convert Android SDK — sdk-lint
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.lint

import com.android.tools.lint.checks.infrastructure.TestFile
import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin

/**
 * Inline Kotlin stubs for the types the tests reference. `LintDetectorTest`
 * runs against the provided files plus whatever stubs we hand it — the full
 * Android SDK is intentionally absent so tests stay fast.
 */
internal object TestStubs {

    /**
     * Minimal stand-in for `android.content.Context`. Only used as the
     * argument type to `ConvertSDK.builder(context)` — no methods are
     * referenced in the test fixtures.
     */
    val contextStub: TestFile = kotlin(
        """
        package android.content
        open class Context
        """.trimIndent(),
    )

    /**
     * Minimal `android.app.Application` + `Activity` stubs. The
     * not-in-Application-onCreate detector walks UAST ancestors and
     * checks the enclosing class's supertype chain, so the stub must
     * model the real `Application extends ContextWrapper extends Context`
     * inheritance on just enough of the classes we exercise.
     */
    val applicationAndActivityStub: TestFile = kotlin(
        """
        package android.app
        import android.content.Context
        open class Application : Context() {
            open fun onCreate() {}
        }
        open class Activity : Context() {
            open fun onCreate() {}
        }
        """.trimIndent(),
    )

    /**
     * Simplified `com.convert.sdk.android.ConvertSDK` facade. Models
     * exactly the public surface the detectors need to see:
     *   - `ConvertSDK.builder(context)` returns a `Builder`.
     *   - `Builder.sdkKey(String)` returns `Builder` (chain).
     *   - `Builder.build()` returns `ConvertSDK`.
     *   - Instance methods `createContext`, `on`, `off`, `onReady`,
     *     `setTrackingEnabled`, `isTrackingEnabled`.
     *
     * The real class lives in `:packages:sdk`; we don't depend on the
     * compiled SDK here because lint-tests drives its own mini
     * classpath at test time.
     */
    val convertSdkStub: TestFile = kotlin(
        """
        package com.convert.sdk.android

        import android.content.Context

        class ConvertSDK internal constructor() {
            fun createContext(): Any = this
            fun on(event: String, callback: Any): ConvertSDK = this
            fun off(event: String, callback: Any): ConvertSDK = this
            fun onReady(callback: Any): ConvertSDK = this
            fun setTrackingEnabled(enabled: Boolean) {}
            fun isTrackingEnabled(): Boolean = true

            class Builder internal constructor() {
                fun sdkKey(value: String): Builder = this
                fun build(): ConvertSDK = ConvertSDK()
            }

            companion object {
                @JvmStatic
                fun builder(context: Context): Builder = Builder()
            }
        }
        """.trimIndent(),
    )
}
