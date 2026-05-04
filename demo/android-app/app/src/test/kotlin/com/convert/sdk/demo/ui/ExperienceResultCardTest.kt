/*
 * Convert Android SDK Demo App — ExperienceResultCard Compose UI tests (Story 7.3)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.convert.sdk.demo.ui.screen.ExperienceResultCard
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.3 AC-6 — Compose UI tests for the screen-specific
 * [ExperienceResultCard] composable. Runs on Robolectric (same stack
 * 7.2's [EventInspectorSheetTest] uses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExperienceResultCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `non-error card renders title and all label-value rows`() {
        composeRule.setContent {
            MaterialTheme {
                ExperienceResultCard(
                    title = "Experience: test-experience",
                    items = listOf(
                        "Variation" to "treatment",
                        "Allocation" to "5000",
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Experience: test-experience").assertIsDisplayed()
        composeRule.onNodeWithText("Variation").assertIsDisplayed()
        composeRule.onNodeWithText("treatment").assertIsDisplayed()
        composeRule.onNodeWithText("Allocation").assertIsDisplayed()
        composeRule.onNodeWithText("5000").assertIsDisplayed()
    }

    @Test
    fun `error card renders with error title and hint row`() {
        composeRule.setContent {
            MaterialTheme {
                ExperienceResultCard(
                    title = "No variation for experience missing-exp",
                    items = listOf("Hint" to "Check audience eligibility"),
                    isError = true,
                )
            }
        }

        composeRule.onNodeWithText("No variation for experience missing-exp").assertIsDisplayed()
        composeRule.onNodeWithText("Hint").assertIsDisplayed()
        composeRule.onNodeWithText("Check audience eligibility").assertIsDisplayed()
    }

    @Test
    fun `card has merged content description covering title and items (AC-5)`() {
        composeRule.setContent {
            MaterialTheme {
                ExperienceResultCard(
                    title = "Experience: test-experience",
                    items = listOf("Variation" to "treatment"),
                )
            }
        }

        // A single merged-descendants node must expose a content description
        // that bundles the title and the label/value pairs — so TalkBack reads
        // the card as one utterance, not three.
        val matches = composeRule
            .onAllNodesWithContentDescription(
                "Experience: test-experience",
                substring = true,
                useUnmergedTree = false,
            )
            .fetchSemanticsNodes()
        assert(matches.isNotEmpty()) {
            "expected a merged node whose contentDescription starts with the title"
        }
        // Verify the merged description also contains the variation value —
        // confirming mergeDescendants=true produced a single utterance.
        val withItem = composeRule
            .onAllNodesWithContentDescription(
                "Variation: treatment",
                substring = true,
                useUnmergedTree = false,
            )
            .fetchSemanticsNodes()
        assert(withItem.isNotEmpty()) {
            "expected the merged contentDescription to include 'Variation: treatment' " +
                "so TalkBack reads the card as a single unit"
        }
    }

    @Test
    fun `error card content description distinguishes the error state for TalkBack`() {
        composeRule.setContent {
            MaterialTheme {
                ExperienceResultCard(
                    title = "No variation for experience missing-exp",
                    items = listOf("Hint" to "Check audience eligibility"),
                    isError = true,
                )
            }
        }

        // Error cards must announce themselves as errors — not just by colour.
        val matches = composeRule
            .onAllNodesWithContentDescription(
                "Error",
                substring = true,
                useUnmergedTree = false,
            )
            .fetchSemanticsNodes()
        assert(matches.isNotEmpty()) {
            "expected the error card's merged content description to contain the word 'Error' " +
                "so TalkBack announces it as an error state"
        }
    }
}
