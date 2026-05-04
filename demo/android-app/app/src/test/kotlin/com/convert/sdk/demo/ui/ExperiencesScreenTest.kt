/*
 * Convert Android SDK Demo App — ExperiencesScreen Compose UI tests (Story 7.3)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.Variation
import com.convert.sdk.demo.ui.screen.ExperiencesScreen
import com.convert.sdk.demo.viewmodel.EventSubscriber
import com.convert.sdk.demo.viewmodel.ExperienceRunner
import com.convert.sdk.demo.viewmodel.SdkViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.3 — Compose UI tests for [ExperiencesScreen]. The corrected
 * story (Task 3) names three tests verbatim:
 *
 *  - `tap run experience shows result card with correct experience key and variation key`
 *  - `tap run experience simultaneously pushes bucketing event with matching details to inspector`
 *  - `null variation shows error card`
 *
 * Tests inject an [SdkViewModel] wired to fakes for both
 * [ExperienceRunner] and [EventSubscriber] so no real SDK is required
 * (the demo app's unit-test platform cannot build a real
 * [com.convert.sdk.android.ConvertSDK] without an instrumented
 * `Application.onCreate`).
 *
 * The "simultaneously pushes bucketing event" test mirrors production:
 * the production [ExperienceRunner] fires `SystemEvents.BUCKETING` on
 * the SDK pub/sub bus as a side-effect of calling `runExperience` —
 * the [SdkViewModel] subscribes to that event and surfaces it via
 * [SdkViewModel.events]. The fake runner here drives a paired fake
 * subscriber to emit the same event, so the test can assert both
 * card-render AND inspector-event correlation in a single flow.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExperiencesScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun newVm(
        runner: ExperienceRunner,
        subscriber: EventSubscriber = SilentSubscriber,
    ): SdkViewModel =
        SdkViewModel(
            eventSubscriber = subscriber,
            initialNetworkOnline = true,
            experienceRunner = runner,
        )

    /** Story 7.3 Task 3 test 1 (AC-2). */
    @Test
    fun `tap run experience shows result card with correct experience key and variation key`() {
        val runner = FakeRunner(
            single = Variation(key = "treatment", experienceKey = "test-experience"),
        )
        val vm = newVm(runner)

        composeRule.setContent {
            MaterialTheme {
                ExperiencesScreen(viewModel = vm)
            }
        }

        // No cards initially (AC-1).
        composeRule.onNodeWithText("Experience: test-experience").assertDoesNotExist()
        composeRule.onNodeWithText("treatment").assertDoesNotExist()

        // Tap the primary Run Experience button.
        composeRule.onNodeWithText("Run Experience").performClick()
        composeRule.waitForIdle()

        // Card renders the EXACT experience key and variation key from
        // the runner — not just "any card is displayed". F-109 fix:
        // these assertions catch a bug where the wrong key/variation is
        // rendered (the previous test only asserted card existence).
        composeRule.onNodeWithText("Experience: test-experience").assertIsDisplayed()
        composeRule.onNodeWithText("Variation").assertIsDisplayed()
        composeRule.onNodeWithText("treatment").assertIsDisplayed()
        // Cross-check via the ViewModel: only one result, with the
        // expected fields, no error.
        val r = vm.results.value.single()
        assert(r.experienceKey == "test-experience") {
            "expected experienceKey 'test-experience', got '${r.experienceKey}'"
        }
        assert(r.variationKey == "treatment") {
            "expected variationKey 'treatment', got '${r.variationKey}'"
        }
        assert(!r.isError) { "non-null variation must NOT be an error result" }
    }

    /** Story 7.3 Task 3 test 2 (AC-2 — inspector correlation). */
    @Test
    fun `tap run experience simultaneously pushes bucketing event with matching details to inspector`() {
        // The production runner fires SystemEvents.BUCKETING on the SDK
        // bus when it buckets a visitor. We model that with a paired
        // fake: the runner returns a Variation AND emits a matching
        // bucketing payload through the same subscriber the ViewModel
        // observes.
        val subscriber = FakeSubscriber()
        val runner = EmittingFakeRunner(
            subscriber = subscriber,
            variation = Variation(key = "treatment", experienceKey = "test-experience"),
            visitorId = "v-42",
        )
        val vm = newVm(runner = runner, subscriber = subscriber)

        composeRule.setContent {
            MaterialTheme {
                ExperiencesScreen(viewModel = vm)
            }
        }

        composeRule.onNodeWithText("Run Experience").performClick()
        composeRule.waitForIdle()

        // (a) the result card renders for the bucketed variation.
        composeRule.onNodeWithText("Experience: test-experience").assertIsDisplayed()
        composeRule.onNodeWithText("treatment").assertIsDisplayed()

        // (b) simultaneously, exactly one BUCKETING event lands in the
        // inspector — and its payload matches the bucketed details
        // (experience key + variation key). This is the correlation
        // F-109 demanded: card details and inspector event must agree.
        val events = vm.events.value
        assert(events.size == 1) {
            "expected exactly one inspector event, got ${events.size}: " +
                events.map { it.eventName }
        }
        val event = events.single()
        assert(event.eventName == SystemEvents.BUCKETING) {
            "expected event name '${SystemEvents.BUCKETING}', got '${event.eventName}'"
        }
        assert(event.payload["experienceKey"] == "test-experience") {
            "BUCKETING event experienceKey must match the card — got " +
                "'${event.payload["experienceKey"]}'"
        }
        assert(event.payload["variationKey"] == "treatment") {
            "BUCKETING event variationKey must match the card — got " +
                "'${event.payload["variationKey"]}'"
        }
    }

    /** Story 7.3 Task 3 test 3 (AC-4). */
    @Test
    fun `null variation shows error card`() {
        val runner = FakeRunner(single = null)
        val vm = newVm(runner)

        composeRule.setContent {
            MaterialTheme {
                ExperiencesScreen(viewModel = vm)
            }
        }

        composeRule.onNodeWithText("Run Experience").performClick()
        composeRule.waitForIdle()

        // The corrected AC-4 requires the error-styled card to contain
        // an error type, error message, and suggestion text. The
        // SdkViewModel renders all three: error type via `isError = true`
        // (drives the error icon + ErrorContainer palette in
        // ExperienceResultCard); error message as the card title;
        // suggestion text as the "Hint" item.
        val r = vm.results.value.single()
        assert(r.isError) { "null variation must produce an error result (error type)" }
        assert(!r.errorMessage.isNullOrBlank()) {
            "error result must include an error message"
        }
        assert(!r.errorHint.isNullOrBlank()) {
            "error result must include suggestion text (errorHint)"
        }
        // Visible UI: the error card title + Hint row are both rendered.
        composeRule.onNodeWithText(r.errorMessage!!).assertIsDisplayed()
        composeRule.onNodeWithText("Hint").assertIsDisplayed()
        composeRule.onNodeWithText(r.errorHint!!).assertIsDisplayed()
    }

    @Test
    fun `empty placeholder visible when no results`() {
        val vm = newVm(FakeRunner(single = null))
        composeRule.setContent {
            MaterialTheme {
                ExperiencesScreen(viewModel = vm)
            }
        }
        // The placeholder stays visible until the first tap (developer cue).
        composeRule
            .onNodeWithText("Tap a button to run an experience.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `run experiences adds one card per variation`() {
        val runner = FakeRunner(
            all = listOf(
                Variation(key = "v-a", experienceKey = "exp-a"),
                Variation(key = "v-b", experienceKey = "exp-b"),
            ),
        )
        val vm = newVm(runner)

        composeRule.setContent {
            MaterialTheme {
                ExperiencesScreen(viewModel = vm)
            }
        }

        composeRule.onNodeWithText("Run Experiences").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Experience: exp-a").assertIsDisplayed()
        composeRule.onNodeWithText("Experience: exp-b").assertIsDisplayed()
    }

    // ------------------------------------------------------------------

    private class FakeRunner(
        private val single: Variation? = null,
        private val all: List<Variation> = emptyList(),
    ) : ExperienceRunner {
        override fun runExperience(experienceKey: String): Variation? = single
        override fun runExperiences(): List<Variation> = all
    }

    /**
     * Fake runner that mirrors the production-runner contract: a
     * successful `runExperience` call has the side-effect of emitting
     * a BUCKETING event onto the SDK pub/sub bus. The paired
     * [FakeSubscriber] is the same instance the ViewModel subscribes
     * to, so the event reaches the inspector exactly as it would in
     * production.
     */
    private class EmittingFakeRunner(
        private val subscriber: FakeSubscriber,
        private val variation: Variation?,
        private val visitorId: String,
    ) : ExperienceRunner {
        override fun runExperience(experienceKey: String): Variation? {
            if (variation != null) {
                subscriber.emit(
                    SystemEvents.BUCKETING,
                    mapOf(
                        "experienceKey" to (variation.experienceKey ?: experienceKey),
                        "variationKey" to variation.key,
                        "visitorId" to visitorId,
                    ),
                )
            }
            return variation
        }

        override fun runExperiences(): List<Variation> =
            variation?.let { listOf(it) } ?: emptyList()
    }

    private object SilentSubscriber : EventSubscriber {
        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }

    /**
     * In-memory pub/sub fake — same shape as the
     * [com.convert.sdk.demo.ui.EventInspectorSheetTest] fake. Holds
     * subscriptions per event name so [emit] can synchronously
     * dispatch to every callback.
     */
    private class FakeSubscriber : EventSubscriber {
        private val subs: MutableMap<String, MutableList<(Map<String, Any?>) -> Unit>> =
            mutableMapOf()

        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable {
            subs.getOrPut(event) { mutableListOf() }.add(callback)
            return AutoCloseable { subs[event]?.remove(callback) }
        }

        fun emit(event: String, payload: Map<String, Any?>) {
            subs[event]?.toList()?.forEach { it(payload) }
        }
    }
}
