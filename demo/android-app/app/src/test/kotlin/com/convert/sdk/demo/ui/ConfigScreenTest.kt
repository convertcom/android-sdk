/*
 * Convert Android SDK Demo App — ConfigScreen Compose UI tests (Story 7.6 DEMO-2)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.convert.sdk.demo.ui.screen.ConfigScreen
import com.convert.sdk.demo.viewmodel.ConfigSnapshot
import com.convert.sdk.demo.viewmodel.ConfigSnapshotProvider
import com.convert.sdk.demo.viewmodel.EventSubscriber
import com.convert.sdk.demo.viewmodel.SdkViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.6 AC-5 / AC-6 / AC-7 — Compose UI tests for [ConfigScreen].
 *
 * The three branches of `viewModel.configState` each produce a
 * distinct UI — tests verify all three:
 *  - Loading → CircularProgressIndicator + "Fetching configuration..."
 *  - Loaded  → ConfigInfoPanel with snapshot fields
 *  - Failed  → error-styled ResultCard with reason + hint
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigScreenTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private fun newVm(
        subscriber: ControllableSubscriber = ControllableSubscriber(),
        provider: ConfigSnapshotProvider = StubProvider(EMPTY_SNAPSHOT),
    ): Pair<SdkViewModel, ControllableSubscriber> {
        val vm = SdkViewModel(
            eventSubscriber = subscriber,
            initialNetworkOnline = true,
            configSnapshotProvider = provider,
        )
        return vm to subscriber
    }

    @Test
    fun `loading branch shows spinner text`() {
        val (vm, _) = newVm()
        composeRule.setContent {
            MaterialTheme { ConfigScreen(viewModel = vm) }
        }
        composeRule.onNodeWithText("Fetching configuration", substring = true).assertIsDisplayed()
    }

    @Test
    fun `loaded branch shows ConfigInfoPanel after ready`() {
        val provider = StubProvider(
            ConfigSnapshot(
                sdkKey = "abcdef12-3456-7890-abcd-ef1234567890",
                environment = "staging",
                experienceKeys = listOf("exp-1"),
                featureKeys = listOf("feat-a"),
                trackingEnabled = true,
            ),
        )
        val (vm, subscriber) = newVm(provider = provider)
        composeRule.setContent {
            MaterialTheme { ConfigScreen(viewModel = vm) }
        }

        subscriber.emit("ready", mapOf("environment" to "staging"))
        composeRule.waitForIdle()

        composeRule.onNodeWithText("abcdef12...").assertIsDisplayed()
        composeRule.onNodeWithText("staging").assertIsDisplayed()
        composeRule.onNodeWithText("exp-1", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("feat-a", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Yes").assertIsDisplayed()
        // Spinner text is gone.
        composeRule.onNodeWithText("Fetching configuration", substring = true).assertDoesNotExist()
    }

    @Test
    fun `failed branch shows error card with reason and hint`() {
        val (vm, _) = newVm()
        composeRule.setContent {
            MaterialTheme { ConfigScreen(viewModel = vm) }
        }

        // Emit a WARN BEFORE any ready — flips configState to Failed.
        vm.logger.warn("fetch failed: no cached config")
        composeRule.waitForIdle()

        // Reason from the WARN message.
        composeRule.onNodeWithText("fetch failed", substring = true).assertIsDisplayed()
        // Canonical hint literal from the story.
        composeRule.onNodeWithText("Check network + SDK key", substring = true).assertIsDisplayed()
    }

    // ---------------------------------------------------------------

    private class StubProvider(private val snap: ConfigSnapshot) : ConfigSnapshotProvider {
        override fun snapshot(): ConfigSnapshot = snap
    }

    private class ControllableSubscriber : EventSubscriber {
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

    private companion object {
        val EMPTY_SNAPSHOT = ConfigSnapshot(
            sdkKey = "",
            environment = null,
            experienceKeys = emptyList(),
            featureKeys = emptyList(),
            trackingEnabled = null,
        )
    }
}
