/*
 * Convert Android SDK — sdk-lint
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.lint

import com.android.tools.lint.checks.infrastructure.TestFiles.kotlin
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.jupiter.api.Test

/**
 * Exercises [ConvertSdkInitializedBeforeUseDetector] against inline Kotlin
 * fixtures. The detector is a *heuristic* — it flags SDK method calls
 * whose receiver was not provably bound from a `.build()` chain in the
 * same compilation unit (local variable or `this`-field initialised from
 * `.build()`). The test cases below describe the false-positive /
 * false-negative profile concretely so a future bump to true flow
 * analysis can measure the delta.
 */
class ConvertSdkInitializedBeforeUseDetectorTest {

    private fun lintTask() = lint()
        .allowMissingSdk()
        .issues(ConvertSdkInitializedBeforeUseDetector.ISSUE)
        .files(
            TestStubs.contextStub,
            TestStubs.applicationAndActivityStub,
            TestStubs.convertSdkStub,
        )

    // -------- Expected-trigger cases ----------------------------------

    /**
     * SDK instance is a function parameter — the detector has no
     * visible `.build()` binding in the file and must flag the call.
     */
    @Test
    fun `flags SDK method call when receiver is a parameter with no build in scope`() {
        lintTask()
            .files(
                TestStubs.contextStub,
                TestStubs.applicationAndActivityStub,
                TestStubs.convertSdkStub,
                kotlin(
                    """
                    package com.example

                    import com.convert.sdk.android.ConvertSDK

                    fun usage(sdk: ConvertSDK) {
                        sdk.createContext()
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectWarningCount(1)
    }

    /**
     * SDK is assigned from a non-builder source (a function return).
     * No `.build()` anywhere in the file — must trigger.
     */
    @Test
    fun `flags SDK method call when receiver has no builder-build origin`() {
        lintTask()
            .files(
                TestStubs.contextStub,
                TestStubs.applicationAndActivityStub,
                TestStubs.convertSdkStub,
                kotlin(
                    """
                    package com.example

                    import com.convert.sdk.android.ConvertSDK

                    fun obtain(): ConvertSDK = TODO()

                    fun boot() {
                        val sdk = obtain()
                        sdk.on("ready", Any())
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectWarningCount(1)
    }

    // -------- Expected-no-trigger cases --------------------------------

    /**
     * Canonical happy path — local var bound directly from the
     * fluent builder chain. Detector must stay quiet.
     */
    @Test
    fun `does not flag when SDK is bound from builder build chain locally`() {
        lintTask()
            .files(
                TestStubs.contextStub,
                TestStubs.applicationAndActivityStub,
                TestStubs.convertSdkStub,
                kotlin(
                    """
                    package com.example

                    import android.content.Context
                    import com.convert.sdk.android.ConvertSDK

                    fun boot(ctx: Context) {
                        val sdk = ConvertSDK.builder(ctx).sdkKey("k").build()
                        sdk.createContext()
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectClean()
    }

    /**
     * Class-level field initialised from the builder chain. The
     * detector must recognise the field binding and not flag method
     * calls that reference it.
     */
    @Test
    fun `does not flag when SDK field is initialised from builder build chain`() {
        lintTask()
            .files(
                TestStubs.contextStub,
                TestStubs.applicationAndActivityStub,
                TestStubs.convertSdkStub,
                kotlin(
                    """
                    package com.example

                    import android.content.Context
                    import com.convert.sdk.android.ConvertSDK

                    class App(ctx: Context) {
                        private val sdk: ConvertSDK =
                            ConvertSDK.builder(ctx).sdkKey("k").build()

                        fun start() {
                            sdk.on("ready", Any())
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectClean()
    }

    /**
     * Unrelated builder-style chains must not false-positive.
     */
    @Test
    fun `does not flag unrelated builder chains on non-SDK types`() {
        lintTask()
            .files(
                TestStubs.contextStub,
                TestStubs.applicationAndActivityStub,
                TestStubs.convertSdkStub,
                kotlin(
                    """
                    package com.example

                    class Other {
                        fun on(event: String, callback: Any): Other = this
                    }

                    fun go() {
                        val other = Other()
                        other.on("x", Any())
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectClean()
    }
}
