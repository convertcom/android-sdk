/*
 * Convert Android SDK Demo App — EventInspectorSheet Compose UI tests
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
import com.convert.sdk.demo.ui.component.EventInspectorSheet
import com.convert.sdk.demo.viewmodel.EventSubscriber
import com.convert.sdk.demo.viewmodel.InspectorTab
import com.convert.sdk.demo.viewmodel.SdkViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.2 AC-8 — Compose UI tests for [EventInspectorSheet].
 *
 * Runs on Robolectric so the Compose runtime has a functioning
 * [android.content.Context] without an instrumented device. The
 * project's `:app` unit-test platform is JUnit 5 + Vintage engine
 * (same setup `:packages:sdk` uses for its Robolectric tests); the
 * Vintage engine lets JUnit 4's [createComposeRule] cohabit with
 * JUnit-Jupiter-based [com.convert.sdk.demo.viewmodel.SdkViewModelTest]
 * in one module.
 *
 * `sdk = [34]` pins Robolectric to a shadow API level it ships with
 * (4.16 bundles up to API 34). The demo's production `compileSdk` is
 * 35 but Robolectric's shadow set lags AGP; requesting 34 is the
 * same compatibility knob every SDK-module Robolectric test uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EventInspectorSheetTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun newVm(): Pair<SdkViewModel, FakeSubscriber> {
        val subscriber = FakeSubscriber()
        val vm = SdkViewModel(eventSubscriber = subscriber, initialNetworkOnline = true)
        return vm to subscriber
    }

    /** AC-8 test 1. */
    @Test
    fun `events tab shows empty state when no events`() {
        val (vm, _) = newVm()
        composeRule.setContent {
            MaterialTheme {
                EventInspectorSheet(viewModel = vm)
            }
        }
        composeRule
            .onNodeWithText("No events yet — tap an action above")
            .assertIsDisplayed()
    }

    /** AC-8 test 2. */
    @Test
    fun `events tab shows event items with badges`() {
        val (vm, subscriber) = newVm()
        // Seed one BUCKETING (QUEUED) + one CONVERSION (will be DELIVERED after release).
        subscriber.emit(
            "bucketing",
            mapOf(
                "experienceKey" to "welcome",
                "variationKey" to "control",
                "visitorId" to "v-42",
            ),
        )
        subscriber.emit("conversion", mapOf("visitorId" to "v-42", "goalKey" to "purchase"))
        // Transition the conversion to DELIVERED via the lifecycle signal.
        subscriber.emit("api.queue.released", mapOf("batchSize" to 2, "statusCode" to 200))

        composeRule.setContent {
            MaterialTheme {
                EventInspectorSheet(viewModel = vm)
            }
        }
        // Both badges visible, payload fields rendered.
        composeRule.onNodeWithText("BUCKETING", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("CONVERSION", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("welcome", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("purchase", substring = true).assertIsDisplayed()
    }

    /** AC-8 test 3. */
    @Test
    fun `logs tab shows log items with level badges`() {
        val (vm, _) = newVm()
        // Seed logs across all four levels.
        vm.logger.debug("debug-msg")
        vm.logger.info("info-msg")
        vm.logger.warn("warn-msg")
        vm.logger.error("error-msg")

        composeRule.setContent {
            MaterialTheme {
                EventInspectorSheet(viewModel = vm)
            }
        }
        // Switch to Logs tab.
        composeRule.onNodeWithText("Logs").performClick()

        composeRule.onNodeWithText("DEBUG", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("INFO", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("WARN", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("ERROR", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("debug-msg").assertIsDisplayed()
        composeRule.onNodeWithText("error-msg").assertIsDisplayed()
    }

    /** AC-8 test 4 — tab state survives composable recreation because the ViewModel carries it. */
    @Test
    fun `tab switch preserves state`() {
        val (vm, _) = newVm()
        composeRule.setContent {
            MaterialTheme {
                EventInspectorSheet(viewModel = vm)
            }
        }
        // Click Logs — the ViewModel's selectedTab flow updates.
        composeRule.onNodeWithText("Logs").performClick()
        composeRule.waitForIdle()
        assert(vm.selectedTab.value == InspectorTab.LOGS) {
            "tab selection must persist in the ViewModel, got ${vm.selectedTab.value}"
        }
        // And — crucial for AC-2 — the visible UI reflects it without the
        // test having to re-select: the Logs empty-state is rendered
        // (we seeded no logs). If the composable used a local `remember {}`
        // this would have stayed on the Events empty state after the click.
        composeRule.onNodeWithText("No logs yet").assertIsDisplayed()
    }

    // ---- Test doubles ----------------------------------------------

    /**
     * Minimal [EventSubscriber] fake — mirrors the one in
     * [com.convert.sdk.demo.viewmodel.SdkViewModelTest]. Declared here
     * so this file is self-contained.
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
            subs[event]?.forEach { it(payload) }
        }
    }
}
