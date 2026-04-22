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
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression
import org.jetbrains.uast.UElement
import org.jetbrains.uast.UQualifiedReferenceExpression
import org.jetbrains.uast.UVariable
import org.jetbrains.uast.toUElement
import org.jetbrains.uast.tryResolve

/**
 * Flags direct Convert SDK method calls whose receiver was not provably
 * bound from a `ConvertSDK.builder(…)…build()` chain in the same
 * compilation unit.
 *
 * ### Heuristic (not a full flow analysis)
 *
 * Story 6.3 explicitly sanctions a simpler heuristic than a full UAST
 * flow analysis (see sprint conductor note and `Dev Notes → Lint Rule
 * Authoring Is Hard`). Full "was `.build()` called before *this line*"
 * reachability analysis would need to track every branch, loop, lambda
 * escape and field escape — well out of scope for the MVP.
 *
 * The heuristic this detector uses:
 *
 *  1. Target calls whose method name is one of the SDK's public instance
 *     methods ([APPLICABLE_METHODS]).
 *  2. Check the receiver's declared type FQN is
 *     `com.convert.sdk.android.ConvertSDK` — rules out every unrelated
 *     builder-pattern call.
 *  3. Resolve the receiver expression to a local variable or field.
 *     - If the receiver is *itself* a `ConvertSDK.builder(…)…build()`
 *       chain, clean.
 *     - Otherwise, if the resolved declaration has an initialiser that is
 *       a `build()` call on a `ConvertSDK.Builder`, clean.
 *     - Any other shape (parameter, `fun foo(): ConvertSDK` return,
 *       unresolved) — report.
 *
 * ### False-positive / false-negative profile
 *
 *  - **False positive** when the SDK is injected via DI and the field
 *    initialiser is a DI container lookup, not a builder chain.
 *    Consumers can suppress with
 *    `lintOptions { disable "ConvertSdkInitializedBeforeUse" }` per AC-4.
 *  - **False negative** when the SDK is reached through intermediate
 *    indirection that the detector cannot resolve to a local
 *    declaration — for example a `companion object` property that
 *    delegates to `lazy { otherObject.sdk }`, a property with a
 *    custom getter, or a cross-file import whose initialiser lives
 *    in a different compilation unit. The detector inspects only the
 *    resolved declaration's initialiser in the current file; full
 *    inter-procedural flow analysis is explicitly out of scope for
 *    the MVP (see `Dev Notes → Lint Rule Authoring Is Hard`).
 *
 * ### Severity
 *
 * [Severity.WARNING] per story 6.3 AC-2. Warning (not error) so consumer
 * builds still pass — the rule guides, it doesn't block.
 */
@Suppress("UnstableApiUsage")
public class ConvertSdkInitializedBeforeUseDetector : Detector(), Detector.UastScanner {

    /**
     * Only dispatch into [visitMethodCall] for the six Convert SDK
     * public instance methods listed in [APPLICABLE_METHODS]. AGP's
     * lint indexes this list against method-call sites in the
     * compilation unit and invokes us per hit — which is dramatically
     * cheaper than a pure UAST visitor over every call.
     */
    override fun getApplicableMethodNames(): List<String> = APPLICABLE_METHODS

    @Suppress("ReturnCount") // 3 early-return guards (declaring-class
    // FQN check, unknown owner, bound-receiver confirmation). Splitting
    // into a nested `if` ladder hurts readability more than the guard
    // style hurts the single-exit rule.
    override fun visitMethodCall(
        context: JavaContext,
        node: UCallExpression,
        method: PsiMethod,
    ) {
        // Confirm the resolved method lives on com.convert.sdk.android.ConvertSDK.
        // Without this check we'd match `createContext()` on `android.content.Context`
        // and many other classes.
        val owner = method.containingClass?.qualifiedName ?: return
        if (owner != CONVERT_SDK_FQN) return

        if (isReceiverBoundFromBuilderBuild(node)) return

        context.report(
            issue = ISSUE,
            scope = node,
            location = context.getLocation(node),
            message = REPORT_MESSAGE,
        )
    }

    /**
     * Answers the heuristic question: *does this call's receiver trace
     * back to a `ConvertSDK.builder(…)…build()` chain in the same
     * compilation unit?*
     *
     * Returns true when:
     *  - The receiver expression itself is a `.build()` chain, OR
     *  - The receiver resolves to a local variable / field whose
     *    initialiser is a `.build()` chain on a `ConvertSDK.Builder`.
     */
    @Suppress("ReturnCount") // Guard clauses for missing receiver,
    // direct chain match, unresolved declaration, non-variable
    // declaration, and missing initialiser. Each exit maps to a
    // distinct "why this isn't a build-binding" rationale.
    private fun isReceiverBoundFromBuilderBuild(node: UCallExpression): Boolean {
        val receiver = receiverFor(node) ?: return false

        // Receiver is literally a chain: `ConvertSDK.builder(ctx).sdkKey("k").build().createContext()`
        if (isBuildChain(receiver)) return true

        // Receiver resolves to a variable / field — inspect its initialiser.
        // `tryResolve()` hands back the PSI declaration; lifting that PSI
        // into UAST via `toUElement()` lets us reuse the existing
        // `isBuildChain` matcher on the initialiser expression, regardless
        // of whether the declaration is a Kotlin `val`, a Kotlin field,
        // or a Java local variable / field.
        val resolved: PsiElement = receiver.tryResolve() ?: return false
        val uVariable = resolved.toUElement() as? UVariable ?: return false
        val initializer: UElement = uVariable.uastInitializer ?: return false
        return isBuildChain(initializer)
    }

    /**
     * Extracts the qualifier of a `receiver.method(args)` call:
     *   `sdk.on("e", cb)` → `sdk`
     *   `ConvertSDK.builder(ctx).build().on("e", cb)` → the whole chain
     */
    private fun receiverFor(call: UCallExpression): UElement? {
        val parent = call.uastParent
        if (parent is UQualifiedReferenceExpression && parent.selector === call) {
            return parent.receiver
        }
        return null
    }

    /**
     * True when [element] is a method call whose innermost selector is
     * `build()` on a `com.convert.sdk.android.ConvertSDK.Builder`.
     * Recurses through the fluent-chain qualifier so
     * `builder(ctx).sdkKey("k").build()` matches.
     */
    @Suppress("ReturnCount") // Chain of four guard clauses — not a
    // terminal call, not `build`, unresolved, wrong declaring class.
    private fun isBuildChain(element: UElement): Boolean {
        val call = asTerminalCall(element) ?: return false
        if (call.methodName != "build") return false
        val resolved = call.resolve() ?: return false
        return resolved.containingClass?.qualifiedName == CONVERT_BUILDER_FQN
    }

    /**
     * Walks a possibly-qualified expression down to its terminal
     * call. `builder().sdkKey().build()` — the UAST root is the
     * outer qualified-reference, the terminal call is `build()`.
     */
    private fun asTerminalCall(element: UElement): UCallExpression? = when (element) {
        is UCallExpression -> element
        is UQualifiedReferenceExpression -> asTerminalCall(element.selector)
        else -> null
    }

    /**
     * Issue metadata. `explanation` reads as a mini-playbook so the IDE
     * tooltip is self-contained — no need for the reader to chase
     * external docs when they hit the warning.
     */
    public companion object {
        private const val CONVERT_SDK_FQN = "com.convert.sdk.android.ConvertSDK"
        private const val CONVERT_BUILDER_FQN = "com.convert.sdk.android.ConvertSDK.Builder"

        private val APPLICABLE_METHODS: List<String> = listOf(
            "createContext",
            "on",
            "off",
            "onReady",
            "setTrackingEnabled",
            "isTrackingEnabled",
        )

        /** Text reported at every unbound call site. */
        internal const val REPORT_MESSAGE =
            "ConvertSDK method called on a receiver that does not trace back " +
                "to a ConvertSDK.builder(…).build() chain in this file. " +
                "Bind the SDK instance to a local variable or field " +
                "initialised from the builder before calling its methods."

        /**
         * Public [Issue] constant — referenced by [ConvertIssueRegistry]
         * and the detector's test suite. AC-5 of story 6.3 requires a
         * multi-line markdown `explanation`; the documentation pipeline
         * surfaces it verbatim on the rule reference page.
         */
        public val ISSUE: Issue = Issue.create(
            id = "ConvertSdkInitializedBeforeUse",
            briefDescription = "Convert SDK method invoked before ConvertSDK.builder(…).build()",
            explanation = """
                This rule flags calls to `ConvertSDK` instance methods whose
                receiver is not provably initialised from
                `ConvertSDK.builder(context)…build()` in the same
                compilation unit (Kotlin or Java file).

                Internally, the detector does a local check — not a full
                flow analysis. Specifically:

                  - The receiver of the call is resolved to its declaration.
                  - If the declaration is a local variable or field with
                    an initialiser that is itself a `.build()` chain
                    on a `ConvertSDK.Builder`, the call is considered safe.
                  - If the declaration is a parameter, a `var` assigned
                    later, the return value of an opaque helper, or the
                    receiver cannot be resolved, the call is reported.

                **Why bother?** Constructing `ConvertSDK` via the builder
                is the only way to pass in an `android.content.Context`,
                a tracking endpoint override, or a custom data refresh
                interval. Skipping the builder means the SDK is either
                not initialised at all (NPE at method time) or was
                initialised with defaults that don't match the
                environment (pointing at production from a staging build).

                **Known false positives.** Dependency-injected SDK fields
                will trip this rule: the field's initialiser is a DI
                container lookup, not a builder chain. Suppress on the
                field with `@Suppress("ConvertSdkInitializedBeforeUse")`
                or project-wide via
                `lintOptions { disable "ConvertSdkInitializedBeforeUse" }`.
            """.trimIndent(),
            category = Category.USABILITY,
            priority = 7,
            severity = Severity.WARNING,
            implementation = Implementation(
                ConvertSdkInitializedBeforeUseDetector::class.java,
                Scope.JAVA_FILE_SCOPE,
            ),
        )
    }
}
