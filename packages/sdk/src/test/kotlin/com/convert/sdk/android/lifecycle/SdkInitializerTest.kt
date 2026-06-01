/*
 * Convert Android SDK — sdk/lifecycle SdkInitializer tests (Story 5.3)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.lifecycle

import androidx.lifecycle.ProcessLifecycleInitializer
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Story 5.3 AC-8 tests for [SdkInitializer].
 *
 * The initializer's sole purpose is to declare a dependency on
 * [ProcessLifecycleInitializer] so that App Startup finishes initializing
 * that framework service BEFORE any SDK code runs. The initializer's
 * `create()` body is a deliberate no-op: the SDK is bootstrapped only
 * when the host app calls `ConvertSDK.builder(context).build()` in
 * `Application.onCreate`.
 *
 * **F-127 correction:** `WorkManagerInitializer` is NOT listed as a
 * dependency. As of WorkManager 2.6+, that class no longer exists as a
 * valid App Startup initializer — WorkManager self-initializes via its
 * own startup mechanism in `work-runtime` 2.11.1. Listing it would cause
 * a compile error or a runtime initialization failure.
 *
 * Tests assert that:
 *  1. `create(context)` returns [Unit] and does not throw.
 *  2. `dependencies()` lists exactly [ProcessLifecycleInitializer] and
 *     nothing else — the only framework service the SDK needs initialized
 *     before any `ConvertSDK.builder()` call can run safely.
 */
@RunWith(RobolectricTestRunner::class)
internal class SdkInitializerTest {

    @Test
    fun `create returns Unit without throwing`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val initializer = SdkInitializer()
        // Return value is Unit — calling it should simply complete.
        val result: Unit = initializer.create(context)
        // Unit is a singleton; the assertion is that we got here without
        // an exception. Keeping the explicit assertNotNull to make the
        // expectation visible in the test report.
        assertNotNull(result)
    }

    @Test
    fun `dependencies lists only ProcessLifecycleInitializer`() {
        val initializer = SdkInitializer()
        val deps = initializer.dependencies()

        assertEquals(
            "dependencies() must list exactly one dependency: ProcessLifecycleInitializer " +
                "(WorkManagerInitializer removed per F-127 — not valid in work-runtime 2.6+)",
            1,
            deps.size,
        )
        assertTrue(
            "ProcessLifecycleInitializer must be in the dependency list",
            deps.contains(ProcessLifecycleInitializer::class.java),
        )
    }
}
