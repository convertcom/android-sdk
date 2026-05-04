/*
 * Convert Android SDK Demo App — FeatureResultCard Compose UI tests (Story 7.4)
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
import com.convert.sdk.demo.ui.screen.FeatureResultCard
import com.convert.sdk.demo.ui.screen.FeatureResultCardItem
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.4 AC-4/AC-5 — Compose UI tests for the screen-specific
 * [FeatureResultCard] composable. Parallel to [ExperienceResultCardTest]
 * for [com.convert.sdk.demo.ui.screen.ExperienceResultCard].
 *
 * Runs on Robolectric (same stack 7.2's [EventInspectorSheetTest] uses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeatureResultCardTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `non-error card renders title and all label-value rows`() {
        composeRule.setContent {
            MaterialTheme {
                FeatureResultCard(
                    title = "Feature: test-feature",
                    items = listOf(
                        FeatureResultCardItem(label = "Status", value = "enabled"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Feature: test-feature").assertIsDisplayed()
        composeRule.onNodeWithText("Status").assertIsDisplayed()
        composeRule.onNodeWithText("enabled").assertIsDisplayed()
    }

    @Test
    fun `error card renders with error title and hint row`() {
        composeRule.setContent {
            MaterialTheme {
                FeatureResultCard(
                    title = "No feature for key missing",
                    items = listOf(FeatureResultCardItem(label = "Hint", value = "Check config")),
                    isError = true,
                )
            }
        }

        composeRule.onNodeWithText("No feature for key missing").assertIsDisplayed()
        composeRule.onNodeWithText("Hint").assertIsDisplayed()
        composeRule.onNodeWithText("Check config").assertIsDisplayed()
    }

    @Test
    fun `typed variable rows render label value and trailing annotation (AC-4)`() {
        composeRule.setContent {
            MaterialTheme {
                FeatureResultCard(
                    title = "Feature: test-feature",
                    items = listOf(
                        FeatureResultCardItem(label = "Status", value = "enabled"),
                        FeatureResultCardItem(
                            label = "buttonColor",
                            value = "\"blue\"",
                            trailingAnnotation = "[String]",
                        ),
                        FeatureResultCardItem(
                            label = "maxRetries",
                            value = "3",
                            trailingAnnotation = "[Int]",
                        ),
                    ),
                )
            }
        }

        // Title + first row (no annotation) render.
        composeRule.onNodeWithText("Feature: test-feature").assertIsDisplayed()
        composeRule.onNodeWithText("Status").assertIsDisplayed()
        composeRule.onNodeWithText("enabled").assertIsDisplayed()

        // Rows with annotations render label, value, AND the separate
        // [Type] annotation text (AC-4's two-style requirement).
        composeRule.onNodeWithText("buttonColor").assertIsDisplayed()
        composeRule.onNodeWithText("\"blue\"").assertIsDisplayed()
        composeRule.onNodeWithText("[String]").assertIsDisplayed()
        composeRule.onNodeWithText("maxRetries").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("[Int]").assertIsDisplayed()
    }

    @Test
    fun `annotations are included in merged content description for TalkBack (AC-5)`() {
        composeRule.setContent {
            MaterialTheme {
                FeatureResultCard(
                    title = "Feature: test-feature",
                    items = listOf(
                        FeatureResultCardItem(
                            label = "buttonColor",
                            value = "\"blue\"",
                            trailingAnnotation = "[String]",
                        ),
                    ),
                )
            }
        }

        // TalkBack must hear the annotation — otherwise visually-impaired
        // developers would not know a variable was a String vs an Int.
        val matches = composeRule
            .onAllNodesWithContentDescription(
                "buttonColor: \"blue\" [String]",
                substring = true,
                useUnmergedTree = false,
            )
            .fetchSemanticsNodes()
        assert(matches.isNotEmpty()) {
            "expected merged contentDescription to include the trailing annotation " +
                "so TalkBack announces the variable's type"
        }
    }

    @Test
    fun `row with no annotation renders without trailing text`() {
        composeRule.setContent {
            MaterialTheme {
                FeatureResultCard(
                    title = "Feature: test-feature",
                    items = listOf(
                        FeatureResultCardItem(label = "Status", value = "enabled"),
                    ),
                )
            }
        }

        composeRule.onNodeWithText("Status").assertIsDisplayed()
        composeRule.onNodeWithText("enabled").assertIsDisplayed()
    }

    @Test
    fun `error card content description contains Error prefix for TalkBack (AC-5)`() {
        composeRule.setContent {
            MaterialTheme {
                FeatureResultCard(
                    title = "No feature for key missing",
                    items = listOf(
                        FeatureResultCardItem(label = "Hint", value = "Check config"),
                    ),
                    isError = true,
                )
            }
        }
        val matches = composeRule
            .onAllNodesWithContentDescription(
                "Error",
                substring = true,
                useUnmergedTree = false,
            )
            .fetchSemanticsNodes()
        assert(matches.isNotEmpty()) {
            "error cards must carry the Error prefix in their merged content description"
        }
    }
}
