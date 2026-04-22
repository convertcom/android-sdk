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
 * Verifies [ConvertSdkNotInApplicationOnCreateDetector] flags
 * `ConvertSDK.builder(…).build()` calls whose enclosing method is NOT
 * `android.app.Application.onCreate`. Severity is INFORMATIONAL per
 * Gotcha 3 of the story — the rule fires on test / demo / scratch code
 * too, which is a feature for the "real" wiring path but a false
 * positive everywhere else. Setting severity low keeps the noise
 * manageable.
 */
class ConvertSdkNotInApplicationOnCreateDetectorTest {

    private fun lintTask() = lint()
        .allowMissingSdk()
        .issues(ConvertSdkNotInApplicationOnCreateDetector.ISSUE)
        .files(
            TestStubs.contextStub,
            TestStubs.applicationAndActivityStub,
            TestStubs.convertSdkStub,
        )

    // -------- Expected-trigger cases ----------------------------------

    /**
     * Build called from an `Activity.onCreate` — wrong lifecycle host,
     * must trigger.
     */
    @Test
    fun `flags build called inside Activity onCreate`() {
        lintTask()
            .files(
                TestStubs.contextStub,
                TestStubs.applicationAndActivityStub,
                TestStubs.convertSdkStub,
                kotlin(
                    """
                    package com.example

                    import android.app.Activity
                    import com.convert.sdk.android.ConvertSDK

                    class MainActivity : Activity() {
                        override fun onCreate() {
                            ConvertSDK.builder(this).sdkKey("k").build()
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expect(
                """
                src/com/example/MainActivity.kt:7: Information: Initialise ConvertSDK from Application.onCreate so the lifecycle observers, offline queue drain and WorkManager registration bind to the real application lifecycle. Calling build() from elsewhere (Activity.onCreate, fragments, helpers) works but may miss background lifecycle transitions. [ConvertSdkNotInApplicationOnCreate]
                            ConvertSDK.builder(this).sdkKey("k").build()
                            ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
                0 errors, 0 warnings
                """.trimIndent(),
            )
    }

    /**
     * Build called from a top-level helper — definitely not
     * Application.onCreate.
     */
    @Test
    fun `flags build called from top level function`() {
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

                    fun init(ctx: Context) {
                        ConvertSDK.builder(ctx).sdkKey("k").build()
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectCount(1, com.android.tools.lint.detector.api.Severity.INFORMATIONAL)
    }

    // -------- Expected-no-trigger cases --------------------------------

    /**
     * Canonical happy path — `.build()` inside `Application.onCreate`
     * must stay clean.
     */
    @Test
    fun `does not flag build inside Application onCreate`() {
        lintTask()
            .files(
                TestStubs.contextStub,
                TestStubs.applicationAndActivityStub,
                TestStubs.convertSdkStub,
                kotlin(
                    """
                    package com.example

                    import android.app.Application
                    import com.convert.sdk.android.ConvertSDK

                    class MyApp : Application() {
                        override fun onCreate() {
                            ConvertSDK.builder(this).sdkKey("k").build()
                        }
                    }
                    """.trimIndent(),
                ),
            )
            .run()
            .expectClean()
    }
}
