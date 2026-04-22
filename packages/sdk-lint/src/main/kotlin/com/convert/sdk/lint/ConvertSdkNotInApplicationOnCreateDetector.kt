/*
 * Convert Android SDK — sdk-lint
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.lint

import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

/**
 * Skeleton — RED phase. Body returns early so the LintDetectorTest suite
 * fails (expected-trigger cases don't trigger yet).
 */
public class ConvertSdkNotInApplicationOnCreateDetector : Detector(), Detector.UastScanner {

    override fun getApplicableMethodNames(): List<String>? = null

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        // intentionally empty during RED
    }

    public companion object {
        public val ISSUE: Issue = Issue.create(
            id = "ConvertSdkNotInApplicationOnCreate",
            briefDescription = "ConvertSDK.builder(…).build() called outside Application.onCreate()",
            explanation = "placeholder",
            category = Category.USABILITY,
            priority = 5,
            severity = Severity.INFORMATIONAL,
            implementation = Implementation(
                ConvertSdkNotInApplicationOnCreateDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
