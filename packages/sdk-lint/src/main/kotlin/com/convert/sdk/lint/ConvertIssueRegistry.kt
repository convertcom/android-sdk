/*
 * Convert Android SDK — sdk-lint
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Issue

/**
 * Lint-service entry point — discovered via the
 * `META-INF/services/com.android.tools.lint.client.api.IssueRegistry`
 * descriptor bundled in this module's JAR. AGP's lint tool instantiates
 * this class reflectively on every `lintDebug`/`lintRelease` run.
 *
 * Registers every custom Convert Android SDK detector so consumers that
 * apply the SDK's AAR (Story 6.3 AC-3 wires this JAR via `lintPublish` in
 * `:packages:sdk`'s `build.gradle.kts`) pick up the rules automatically —
 * no extra consumer-side dependency is required.
 */
@Suppress("UnstableApiUsage")
public class ConvertIssueRegistry : IssueRegistry() {

    /**
     * Every issue published by this registry. The list is the
     * single source of truth — the lint tool walks it to build its
     * rule catalogue, and the test suites reference the same
     * [Issue] constants to seed `TestLintTask.issues(...)`.
     */
    override val issues: List<Issue> = listOf(
        ConvertSdkInitializedBeforeUseDetector.ISSUE,
        ConvertSdkNotInApplicationOnCreateDetector.ISSUE,
    )

    /**
     * API level this registry compiled against. Lint uses this to warn
     * (or skip) when an older lint runtime tries to load a newer
     * registry. Story 6.3 pins lint-api to 32.1.0 (tracking AGP 9.1.0);
     * [CURRENT_API] is the constant exposed by that exact build.
     */
    override val api: Int = CURRENT_API

    /**
     * Minimum lint API this registry is known to work against. Kept
     * equal to [api] — the registry is built against 32.1.0 and we
     * make no attempt to be backward-compatible with earlier lint
     * releases because the ABI is already ABI-coupled to AGP 9.1.0.
     */
    override val minApi: Int = CURRENT_API

    /**
     * Vendor metadata shown in lint reports and IDE inspection UIs.
     * Helps consumers identify which third-party SDK surfaced a given
     * warning when multiple AARs publish their own rules.
     */
    override val vendor: Vendor = Vendor(
        vendorName = "Convert Insights, Inc.",
        feedbackUrl = "https://github.com/convertcom/android-sdk/issues",
        contact = "support@convert.com",
    )
}
