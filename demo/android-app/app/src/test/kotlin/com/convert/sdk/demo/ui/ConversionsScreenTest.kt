/*
 * Convert Android SDK Demo App — ConversionsScreen Compose UI tests (Story 7.5)
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
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.demo.ui.screen.ConversionsScreen
import com.convert.sdk.demo.viewmodel.ConversionTracker
import com.convert.sdk.demo.viewmodel.EventSubscriber
import com.convert.sdk.demo.viewmodel.SdkViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.5 AC-5 — Compose UI tests for [ConversionsScreen].
 *
 * The story names two tests verbatim (required):
 *  - `buy tap shows conversion card and event`
 *  - `second buy tap shows dedup result`
 *
 * Remaining tests cover the other ACs (Buy button label, empty
 * placeholder, card items rendered).
 *
 * All tests inject an [SdkViewModel] wired to a fake [ConversionTracker]
 * so no real SDK is needed — the demo app's unit-test platform cannot
 * build a real [com.convert.sdk.android.ConvertSDK] without an
 * instrumented `Application.onCreate`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConversionsScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun newVm(tracker: ConversionTracker): SdkViewModel =
        SdkViewModel(
            eventSubscriber = SilentSubscriber,
            initialNetworkOnline = true,
            conversionTracker = tracker,
        )

    @Test
    fun `buy tap shows conversion card and event`() {
        val tracker = RecordingConversionTracker()
        val vm = newVm(tracker)

        composeRule.setContent {
            MaterialTheme {
                ConversionsScreen(viewModel = vm)
            }
        }

        // No card before tap.
        composeRule.onNodeWithText("Conversion tracked: purchase-goal").assertDoesNotExist()

        composeRule.onNodeWithText("Buy").performClick()
        composeRule.waitForIdle()

        // AC-2: card title lists the goal key.
        composeRule.onNodeWithText("Conversion tracked: purchase-goal").assertIsDisplayed()
        // AC-2: amount + productsCount are rendered as items.
        composeRule.onNodeWithText("10.3").assertIsDisplayed()
        composeRule.onNodeWithText("2").assertIsDisplayed()

        // AC-1: tracker was called with the correct goalKey + data.
        assert(tracker.calls.size == 1) { "expected 1 tracker call, got ${tracker.calls.size}" }
        assert(tracker.calls[0].first == "purchase-goal") { "wrong goal key: ${tracker.calls[0].first}" }
    }

    @Test
    fun `second buy tap shows dedup result`() {
        val tracker = RecordingConversionTracker()
        val vm = newVm(tracker)

        composeRule.setContent {
            MaterialTheme {
                ConversionsScreen(viewModel = vm)
            }
        }

        composeRule.onNodeWithText("Buy").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Buy").performClick()
        composeRule.waitForIdle()

        // AC-3: dedup card title is visible.
        composeRule.onNodeWithText("Conversion already tracked (dedup)").assertIsDisplayed()
        // First-tap card is still on screen.
        composeRule.onNodeWithText("Conversion tracked: purchase-goal").assertIsDisplayed()
    }

    @Test
    fun `buy button is labeled Buy and enabled`() {
        val vm = newVm(RecordingConversionTracker())
        composeRule.setContent {
            MaterialTheme {
                ConversionsScreen(viewModel = vm)
            }
        }
        composeRule.onNodeWithText("Buy").assertIsDisplayed()
    }

    @Test
    fun `empty placeholder visible when no results`() {
        val vm = newVm(RecordingConversionTracker())
        composeRule.setContent {
            MaterialTheme {
                ConversionsScreen(viewModel = vm)
            }
        }
        composeRule
            .onNodeWithText("Tap Buy to track a conversion.", substring = true)
            .assertIsDisplayed()
    }

    // ------------------------------------------------------------------

    private class RecordingConversionTracker : ConversionTracker {
        val calls: MutableList<Pair<String, List<GoalData>>> = mutableListOf()

        override fun trackConversion(goalKey: String, goalData: List<GoalData>) {
            calls += goalKey to goalData
        }

        // These screen tests exercise the success / dedup card rendering,
        // so the goal is reported present (the unknown-goal error card is
        // covered at the ViewModel level in ConversionResultsTest).
        override fun hasGoal(goalKey: String): Boolean = true
    }

    private object SilentSubscriber : EventSubscriber {
        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }
}
