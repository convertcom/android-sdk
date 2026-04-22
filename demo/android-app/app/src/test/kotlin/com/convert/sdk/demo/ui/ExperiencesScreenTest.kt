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
 * Story 7.3 — Compose UI tests for [ExperiencesScreen]. The story
 * names the two tests verbatim:
 *
 *  - `tap run experience creates result card`
 *  - `null variation shows error card`
 *
 * Both tests inject an [SdkViewModel] wired to a
 * fake [ExperienceRunner] so no real SDK is required (the demo app's
 * unit-test platform cannot build a real [com.convert.sdk.android.ConvertSDK]
 * without an instrumented `Application.onCreate`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExperiencesScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun newVm(runner: ExperienceRunner): SdkViewModel =
        SdkViewModel(
            eventSubscriber = SilentSubscriber,
            initialNetworkOnline = true,
            experienceRunner = runner,
        )

    @Test
    fun `tap run experience creates result card`() {
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

        // Tap the primary Run Experience button.
        composeRule.onNodeWithText("Run Experience").performClick()
        composeRule.waitForIdle()

        // A non-error card renders showing the experience + variation key (AC-2).
        composeRule.onNodeWithText("Experience: test-experience").assertIsDisplayed()
        composeRule.onNodeWithText("treatment").assertIsDisplayed()
    }

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

        // Error card title references the default hardcoded experience key.
        composeRule
            .onNodeWithText("No variation for experience test-experience")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Hint").assertIsDisplayed()
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

    private object SilentSubscriber : EventSubscriber {
        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }
}
