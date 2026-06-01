/*
 * Convert Android SDK Demo App — OfflineScreen Compose UI tests (Story 7.6 DEMO-3)
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
import com.convert.sdk.core.model.Variation
import com.convert.sdk.demo.ui.screen.OfflineScreen
import com.convert.sdk.demo.viewmodel.ConversionTracker
import com.convert.sdk.demo.viewmodel.EventSubscriber
import com.convert.sdk.demo.viewmodel.ExperienceRunner
import com.convert.sdk.demo.viewmodel.SdkViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.6 AC-1 / AC-2 / AC-3 / AC-9 — Compose UI tests for
 * [OfflineScreen].
 *
 * Robolectric cannot simulate real airplane-mode toggles, so the
 * tests drive the screen via direct ViewModel state mutations:
 *  - `setNetworkOnline(false)` flips the network banner label (AC-1).
 *  - Tapping "Run Experience" fires the ExperienceRunner fake and
 *    inserts an ExperienceResult which renders as a ResultCard (AC-2).
 *  - Tapping "Buy" fires the ConversionTracker fake and inserts a
 *    ConversionResult which renders as a ResultCard (AC-3).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OfflineScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun newVm(
        experienceRunner: ExperienceRunner = StubExperienceRunner(),
        conversionTracker: ConversionTracker = RecordingConversionTracker(),
    ): SdkViewModel = SdkViewModel(
        eventSubscriber = SilentSubscriber,
        initialNetworkOnline = true,
        experienceRunner = experienceRunner,
        conversionTracker = conversionTracker,
    )

    @Test
    fun `network banner shows online label initially`() {
        val vm = newVm()
        composeRule.setContent {
            MaterialTheme { OfflineScreen(viewModel = vm) }
        }
        // Exact match — avoids catching the screen title "Online" wouldn't
        // be in; "Online" is a standalone banner label.
        composeRule.onNodeWithText("Online").assertIsDisplayed()
        // The descriptive suffix is part of the banner — proves we rendered
        // the banner, not some other place.
        composeRule.onNodeWithText("— events flush live", substring = true).assertIsDisplayed()
    }

    @Test
    fun `network banner switches to offline when vm reports offline`() {
        val vm = newVm()
        composeRule.setContent {
            MaterialTheme { OfflineScreen(viewModel = vm) }
        }
        vm.setNetworkOnline(false)
        composeRule.waitForIdle()
        // Descriptive suffix disambiguates from the screen title "Offline".
        composeRule.onNodeWithText("— events queued", substring = true).assertIsDisplayed()
    }

    @Test
    fun `empty state visible when no results`() {
        val vm = newVm()
        composeRule.setContent {
            MaterialTheme { OfflineScreen(viewModel = vm) }
        }
        // The exact copy is a rendering detail — loose substring match.
        composeRule
            .onNodeWithText("Tap", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `run experience tap shows result card`() {
        val runner = StubExperienceRunner(
            variation = Variation(id = "v-1", key = "treatment"),
        )
        val vm = newVm(experienceRunner = runner)
        composeRule.setContent {
            MaterialTheme { OfflineScreen(viewModel = vm) }
        }

        composeRule.onNodeWithText("Run Experience").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Experience: test-experience").assertIsDisplayed()
        composeRule.onNodeWithText("treatment").assertIsDisplayed()
    }

    @Test
    fun `buy tap shows conversion card`() {
        val tracker = RecordingConversionTracker()
        val vm = newVm(conversionTracker = tracker)
        composeRule.setContent {
            MaterialTheme { OfflineScreen(viewModel = vm) }
        }

        composeRule.onNodeWithText("Buy").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Conversion tracked: purchase-goal").assertIsDisplayed()
        composeRule.onNodeWithText("10.3").assertIsDisplayed()
    }

    @Test
    fun `both buttons yield two cards visible together`() {
        val runner = StubExperienceRunner(
            variation = Variation(id = "v-1", key = "treatment"),
        )
        val vm = newVm(experienceRunner = runner)
        composeRule.setContent {
            MaterialTheme { OfflineScreen(viewModel = vm) }
        }

        composeRule.onNodeWithText("Run Experience").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Buy").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Experience: test-experience").assertIsDisplayed()
        composeRule.onNodeWithText("Conversion tracked: purchase-goal").assertIsDisplayed()
    }

    // ---------------------------------------------------------------

    private class StubExperienceRunner(
        private val variation: Variation? = null,
    ) : ExperienceRunner {
        override fun runExperience(experienceKey: String): Variation? = variation
        override fun runExperiences(): List<Variation> = emptyList()
    }

    private class RecordingConversionTracker : ConversionTracker {
        val calls: MutableList<String> = mutableListOf()
        override fun trackConversion(
            goalKey: String,
            goalData: List<com.convert.sdk.core.model.GoalData>,
        ) {
            calls += goalKey
        }

        override fun hasGoal(goalKey: String): Boolean = true
    }

    private object SilentSubscriber : EventSubscriber {
        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }
}
