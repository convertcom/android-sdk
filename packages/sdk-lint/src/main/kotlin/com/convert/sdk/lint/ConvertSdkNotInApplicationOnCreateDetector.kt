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
import org.jetbrains.uast.UClass
import org.jetbrains.uast.UMethod
import org.jetbrains.uast.getContainingUClass
import org.jetbrains.uast.getContainingUMethod

/**
 * Flags `ConvertSDK.builder(…).build()` calls whose enclosing method is
 * NOT `android.app.Application.onCreate`. Initialising the SDK from a
 * real Application subclass is strongly recommended because the lifecycle
 * observer (`ProcessLifecycleOwner`), the WorkManager flush registration
 * and the network observer all bind to the application process lifecycle
 * — and those observers miss the `ON_START` transition if they attach
 * after the process is already foregrounded.
 *
 * ### Heuristic
 *
 * For every `build()` call the detector does three cheap checks:
 *
 *  1. The invoked method's name is `build`.
 *  2. The receiver's qualified type is `com.convert.sdk.android.ConvertSDK.Builder`.
 *  3. The enclosing [UMethod] is overridden in a class whose (possibly
 *     transitive) superclass is `android.app.Application`, AND its name
 *     is exactly `onCreate`.
 *
 * If (1) & (2) match but (3) doesn't — the call is reported.
 *
 * ### Severity
 *
 * [Severity.INFORMATIONAL] per story 6.3 Gotcha 3 — the rule fires on
 * test code, demo code, scratch code, and any legitimate non-Application
 * setup path. Keeping the severity low reduces the noise-to-signal ratio
 * in the IDE while still surfacing the advice on the happy path.
 *
 * Consumers can suppress via `lintOptions { disable
 * "ConvertSdkNotInApplicationOnCreate" }` per AC-4 of the story.
 */
@Suppress("UnstableApiUsage")
public class ConvertSdkNotInApplicationOnCreateDetector : Detector(), Detector.UastScanner {

    /**
     * Narrow the visitor to `build` invocations so lint's dispatcher
     * skips everything else. Matching only by method name is a
     * deliberate over-approximation — the receiver-type check inside
     * [visitMethodCall] filters out non-Convert builders.
     */
    override fun getApplicableMethodNames(): List<String> = listOf(BUILDER_METHOD)

    @Suppress("ReturnCount") // Three guard clauses: missing owner,
    // non-Convert builder, already in Application.onCreate.
    override fun visitMethodCall(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        // Filter by declaring class FQN. `method.containingClass?.qualifiedName`
        // is resolved by the Kotlin / Java PSI and is the reliable way to
        // distinguish ConvertSDK.Builder.build() from the many other
        // `.build()` calls (OkHttpClient, Retrofit, etc.) that would
        // otherwise flood this detector.
        val owner = method.containingClass?.qualifiedName ?: return
        if (owner != CONVERT_BUILDER_FQN) return

        if (isInsideApplicationOnCreate(node)) return

        context.report(
            issue = ISSUE,
            scope = node,
            location = context.getLocation(node),
            message = REPORT_MESSAGE,
        )
    }

    /**
     * Walks UAST ancestors upwards from [node]:
     *
     *   `.build()` call  →  enclosing [UMethod]  →  enclosing [UClass]
     *
     * Returns true iff that method is named `onCreate` AND the class
     * (or one of its supers, transitively) is `android.app.Application`.
     * Any other shape — top-level functions, `Activity.onCreate`, tests,
     * helper classes — returns false and the caller reports the issue.
     */
    @Suppress("ReturnCount") // Four guard clauses: no enclosing method,
    // wrong method name, no enclosing class, non-Application class.
    private fun isInsideApplicationOnCreate(node: UCallExpression): Boolean {
        val method: UMethod = node.getContainingUMethod() ?: return false
        if (method.name != ON_CREATE) return false
        val containingClass: UClass = method.getContainingUClass() ?: return false
        return containingClass.isSubclassOf(APPLICATION_FQN)
    }

    /**
     * Walks the class's supertype chain via PSI. `PsiClass.supers`
     * returns the direct superclass + interfaces; we iterate that list
     * breadth-first until we hit [APPLICATION_FQN] or exhaust the chain.
     * Breadth-first is safe because Kotlin only allows single
     * inheritance — we don't need cycle detection — but we cap the loop
     * with a visited set anyway to stay defensive against malformed
     * stub trees.
     */
    private fun UClass.isSubclassOf(fqn: String): Boolean {
        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<com.intellij.psi.PsiClass>()
        queue.add(this.javaPsi)
        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            val name = current.qualifiedName
            if (name == fqn) return true
            if (name != null && !visited.add(name)) continue
            current.supers.forEach(queue::add)
        }
        return false
    }

    /**
     * Issue metadata. Consumed by AGP's lint tool to build the rule
     * catalogue, by the IDE for inline inspection tooltips, and by the
     * future documentation-generation pipeline (the [explanation]
     * multi-line string becomes human-readable Markdown in the rule
     * reference page).
     */
    public companion object {
        private const val BUILDER_METHOD = "build"
        private const val ON_CREATE = "onCreate"
        private const val CONVERT_BUILDER_FQN = "com.convert.sdk.android.ConvertSDK.Builder"
        private const val APPLICATION_FQN = "android.app.Application"

        /** Text reported at the call site. Kept on its own line so the
         *  tests can assert against it exactly. */
        internal const val REPORT_MESSAGE =
            "Initialise ConvertSDK from Application.onCreate so the lifecycle " +
                "observers, offline queue drain and WorkManager registration " +
                "bind to the real application lifecycle. Calling build() " +
                "from elsewhere (Activity.onCreate, fragments, helpers) works " +
                "but may miss background lifecycle transitions."

        /**
         * Public [Issue] constant — referenced by [ConvertIssueRegistry]
         * and the detector's own test suite so all three sites see the
         * same metadata.
         */
        public val ISSUE: Issue = Issue.create(
            id = "ConvertSdkNotInApplicationOnCreate",
            briefDescription = "ConvertSDK.builder(…).build() called outside Application.onCreate()",
            explanation = """
                The Convert Android SDK wires several long-lived collaborators
                at `build()` time: a `ProcessLifecycleOwner` observer that
                drains the offline event queue on foreground, a `WorkManager`
                registration for the periodic flush, and (on API 24+) a
                network-callback observer. These components attach to the
                *application* process lifecycle.

                When `ConvertSDK.builder(…).build()` runs later than
                `Application.onCreate()` — for example from the first
                `Activity.onCreate`, a dependency-injection entry point,
                or a lazy helper — the observer misses the initial
                `ON_START` transition. You'll usually see this as the
                first queued event not flushing until the second
                foreground cycle.

                **Fix.** Move the `ConvertSDK.builder(…).build()` call
                into a class that extends `android.app.Application` and
                registers it in the manifest's `<application android:name="…" />`.

                This rule is *informational* — it also fires on test and
                demo code. Suppress with
                `lintOptions { disable "ConvertSdkNotInApplicationOnCreate" }`
                if the call site is intentional (e.g. a lab-grade sample
                that builds the SDK from a fragment).
            """.trimIndent(),
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
