/*
 * Convert Android SDK Demo App — FeaturesScreen Compose UI tests (Story 7.4)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.FeatureStatus
import com.convert.sdk.demo.ui.screen.FeaturesScreen
import com.convert.sdk.demo.viewmodel.EventSubscriber
import com.convert.sdk.demo.viewmodel.FeatureRunner
import com.convert.sdk.demo.viewmodel.SdkViewModel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.4 AC-6 — Compose UI tests for [FeaturesScreen].
 *
 * The story names one test verbatim:
 *  - `typed variables rendered with correct type annotations`
 *
 * Remaining tests cover the other five AC exhaustively (Run Feature
 * tap, Run Features batch, null-feature error card, empty placeholder).
 *
 * All tests inject an [SdkViewModel] wired to a fake [FeatureRunner] so
 * no real SDK is needed — the demo app's unit-test platform cannot
 * build a real [com.convert.sdk.android.ConvertSDK] without an
 * instrumented `Application.onCreate`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FeaturesScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun newVm(runner: FeatureRunner): SdkViewModel =
        SdkViewModel(
            eventSubscriber = SilentSubscriber,
            initialNetworkOnline = true,
            featureRunner = runner,
        )

    private fun feature(
        key: String,
        enabled: Boolean,
        variables: Map<String, JsonElement>? = null,
    ): Feature = Feature(
        id = "$key-id",
        key = key,
        name = key,
        status = if (enabled) FeatureStatus.ENABLED else FeatureStatus.DISABLED,
        variables = variables,
    )

    @Test
    fun `typed variables rendered with correct type annotations`() {
        // The canonical AC-2 example: four typed variables, one per JS SDK
        // canonical type label, PLUS a whole-number double surfacing the
        // F-110 boundary (1.0 must render as [float], not [integer] —
        // catches any future regression of the strict-first ordering in
        // typeLabel()). The screen must show the name, the formatted
        // value, AND the [type] annotation for every variable.
        val runner = FakeFeatureRunner(
            single = feature(
                key = "test-feature",
                enabled = true,
                variables = mapOf(
                    "buttonColor" to JsonPrimitive("blue"),
                    "maxRetries" to JsonPrimitive(3),
                    "showBanner" to JsonPrimitive(true),
                    "discountFactor" to JsonPrimitive(0.15),
                    // F-110 boundary: a Double whose content has no
                    // fractional part. If the type detector ever flips
                    // the longOrNull/doubleOrNull ordering, this fixture
                    // will fail before any release ships.
                    "shippingMultiplier" to JsonPrimitive(1.0),
                ),
            ),
        )
        val vm = newVm(runner)

        composeRule.setContent {
            MaterialTheme {
                FeaturesScreen(viewModel = vm)
            }
        }

        composeRule.onNodeWithText("Run Feature").performClick()
        composeRule.waitForIdle()

        // Card title names the feature.
        composeRule.onNodeWithText("Feature: test-feature").assertIsDisplayed()

        // Every variable renders its name + formatted value + [type]
        // annotation in three separate Text nodes (per AC-4's two-style
        // requirement — the annotation is labelMedium + outline color,
        // not folded into the value text). Labels are JS SDK canonical
        // lowercase (F-030).
        composeRule.onNodeWithText("buttonColor").assertIsDisplayed()
        composeRule.onNodeWithText("\"blue\"").assertIsDisplayed()
        composeRule.onNodeWithText("[string]").assertIsDisplayed()

        composeRule.onNodeWithText("maxRetries").assertIsDisplayed()
        composeRule.onNodeWithText("3").assertIsDisplayed()
        composeRule.onNodeWithText("[integer]").assertIsDisplayed()

        composeRule.onNodeWithText("showBanner").assertIsDisplayed()
        composeRule.onNodeWithText("true").assertIsDisplayed()
        composeRule.onNodeWithText("[boolean]").assertIsDisplayed()

        composeRule.onNodeWithText("discountFactor").assertIsDisplayed()
        composeRule.onNodeWithText("0.15").assertIsDisplayed()

        // F-110 boundary assertion — whole-number double MUST render as
        // [float], not [integer]. The previous fixture only used 0.15
        // which would pass even when the bug existed.
        composeRule.onNodeWithText("shippingMultiplier").assertIsDisplayed()
        composeRule.onNodeWithText("1.0").assertIsDisplayed()
        // Two [float] annotations now render (0.15 + 1.0). The card uses
        // mergeDescendants = true (so TalkBack reads it as one
        // utterance), which hides per-row Text nodes in the default
        // merged semantics tree. Walk the unmerged tree so each row's
        // trailing-annotation Text is individually addressable.
        val floatLabels = composeRule
            .onAllNodesWithText("[float]", useUnmergedTree = true)
            .fetchSemanticsNodes()
        assert(floatLabels.size >= 2) {
            "expected at least 2 [float] annotations (one per double variable); got ${floatLabels.size}"
        }
    }

    @Test
    fun `tap run feature creates result card`() {
        val runner = FakeFeatureRunner(
            single = feature(key = "test-feature", enabled = true),
        )
        val vm = newVm(runner)

        composeRule.setContent {
            MaterialTheme {
                FeaturesScreen(viewModel = vm)
            }
        }

        // No card before tap.
        composeRule.onNodeWithText("Feature: test-feature").assertDoesNotExist()

        composeRule.onNodeWithText("Run Feature").performClick()
        composeRule.waitForIdle()

        // Card renders title and enabled status (AC-2).
        composeRule.onNodeWithText("Feature: test-feature").assertIsDisplayed()
        composeRule.onNodeWithText("enabled").assertIsDisplayed()
    }

    @Test
    fun `null feature shows error card (AC-5)`() {
        val runner = FakeFeatureRunner(single = null)
        val vm = newVm(runner)

        composeRule.setContent {
            MaterialTheme {
                FeaturesScreen(viewModel = vm)
            }
        }

        composeRule.onNodeWithText("Run Feature").performClick()
        composeRule.waitForIdle()

        // Error card title references the default hardcoded feature key.
        composeRule
            .onNodeWithText("No feature for key test-feature")
            .assertIsDisplayed()
        composeRule.onNodeWithText("Hint").assertIsDisplayed()
    }

    @Test
    fun `empty placeholder visible when no results`() {
        val vm = newVm(FakeFeatureRunner(single = null))
        composeRule.setContent {
            MaterialTheme {
                FeaturesScreen(viewModel = vm)
            }
        }
        composeRule
            .onNodeWithText("Tap a button to run a feature.", substring = true)
            .assertIsDisplayed()
    }

    @Test
    fun `run features adds one card per feature (AC-3)`() {
        val runner = FakeFeatureRunner(
            all = listOf(
                feature(key = "feat-a", enabled = true),
                feature(key = "feat-b", enabled = false),
            ),
        )
        val vm = newVm(runner)

        composeRule.setContent {
            MaterialTheme {
                FeaturesScreen(viewModel = vm)
            }
        }

        composeRule.onNodeWithText("Run Features").performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Feature: feat-a").assertIsDisplayed()
        composeRule.onNodeWithText("Feature: feat-b").assertIsDisplayed()
        composeRule.onNodeWithText("enabled").assertIsDisplayed()
        composeRule.onNodeWithText("disabled").assertIsDisplayed()
    }

    // ------------------------------------------------------------------

    private class FakeFeatureRunner(
        private val single: Feature? = null,
        private val all: List<Feature> = emptyList(),
    ) : FeatureRunner {
        override fun runFeature(featureKey: String): Feature? = single
        override fun runFeatures(): List<Feature> = all
    }

    private object SilentSubscriber : EventSubscriber {
        override fun subscribe(
            event: String,
            callback: (Map<String, Any?>) -> Unit,
        ): AutoCloseable = AutoCloseable { }
    }
}
