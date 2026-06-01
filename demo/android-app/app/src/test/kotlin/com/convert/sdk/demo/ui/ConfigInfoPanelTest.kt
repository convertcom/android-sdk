/*
 * Convert Android SDK Demo App — ConfigInfoPanel Compose UI tests (Story 7.6 DEMO-2)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.convert.sdk.demo.ui.component.ConfigInfoPanel
import com.convert.sdk.demo.viewmodel.ConfigSnapshot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Story 7.6 AC-5 — Compose UI tests for [ConfigInfoPanel]. Runs on
 * Robolectric matching 7.2 / 7.3 / 7.4 / 7.5 Compose tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ConfigInfoPanelTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `panel renders masked sdk key for long key`() {
        val snap = ConfigSnapshot(
            sdkKey = "abcdef12-3456-7890-abcd-ef1234567890",
            environment = "production",
            experienceKeys = listOf("welcome", "checkout"),
            featureKeys = listOf("banner"),
            trackingEnabled = true,
        )
        composeRule.setContent {
            MaterialTheme {
                ConfigInfoPanel(snapshot = snap, lastFetchedAt = 1_700_000_000_000L)
            }
        }
        // Masked, never full.
        composeRule.onNodeWithText("abcdef12...").assertIsDisplayed()
        composeRule.onNodeWithText(snap.sdkKey).assertDoesNotExist()
    }

    @Test
    fun `panel renders environment value`() {
        val snap = ConfigSnapshot(
            sdkKey = "abcdef12-3456",
            environment = "staging",
            experienceKeys = emptyList(),
            featureKeys = emptyList(),
            trackingEnabled = false,
        )
        composeRule.setContent {
            MaterialTheme {
                ConfigInfoPanel(snapshot = snap, lastFetchedAt = 1_700_000_000_000L)
            }
        }
        composeRule.onNodeWithText("staging").assertIsDisplayed()
    }

    @Test
    fun `panel renders experience count and keys`() {
        val snap = ConfigSnapshot(
            sdkKey = "k",
            environment = null,
            experienceKeys = listOf("exp-one", "exp-two", "exp-three"),
            featureKeys = emptyList(),
            trackingEnabled = null,
        )
        composeRule.setContent {
            MaterialTheme {
                ConfigInfoPanel(snapshot = snap, lastFetchedAt = 0L)
            }
        }
        // Count row. Loose substring match — the exact copy is a
        // rendering detail owned by the composable.
        composeRule.onNodeWithText("3", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("exp-one", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("exp-two", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("exp-three", substring = true).assertIsDisplayed()
    }

    @Test
    fun `panel renders feature count and keys`() {
        val snap = ConfigSnapshot(
            sdkKey = "k",
            environment = null,
            experienceKeys = emptyList(),
            featureKeys = listOf("feat-a", "feat-b"),
            trackingEnabled = true,
        )
        composeRule.setContent {
            MaterialTheme {
                ConfigInfoPanel(snapshot = snap, lastFetchedAt = 0L)
            }
        }
        composeRule.onNodeWithText("feat-a", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("feat-b", substring = true).assertIsDisplayed()
    }

    @Test
    fun `panel renders Yes for trackingEnabled true`() {
        val snap = ConfigSnapshot(
            sdkKey = "k", environment = null,
            experienceKeys = emptyList(), featureKeys = emptyList(),
            trackingEnabled = true,
        )
        composeRule.setContent {
            MaterialTheme {
                ConfigInfoPanel(snapshot = snap, lastFetchedAt = 0L)
            }
        }
        composeRule.onNodeWithText("Yes").assertIsDisplayed()
    }

    @Test
    fun `panel renders No for trackingEnabled false`() {
        val snap = ConfigSnapshot(
            sdkKey = "k", environment = null,
            experienceKeys = emptyList(), featureKeys = emptyList(),
            trackingEnabled = false,
        )
        composeRule.setContent {
            MaterialTheme {
                ConfigInfoPanel(snapshot = snap, lastFetchedAt = 0L)
            }
        }
        composeRule.onNodeWithText("No").assertIsDisplayed()
    }

    @Test
    fun `panel renders em-dash for null trackingEnabled`() {
        val snap = ConfigSnapshot(
            sdkKey = "k", environment = null,
            experienceKeys = emptyList(), featureKeys = emptyList(),
            trackingEnabled = null,
        )
        composeRule.setContent {
            MaterialTheme {
                ConfigInfoPanel(snapshot = snap, lastFetchedAt = 0L)
            }
        }
        composeRule.onNodeWithText("—").assertIsDisplayed()
    }

    @Test
    fun `panel renders formatted timestamp row`() {
        val snap = ConfigSnapshot(
            sdkKey = "k", environment = null,
            experienceKeys = emptyList(), featureKeys = emptyList(),
            trackingEnabled = null,
        )
        composeRule.setContent {
            MaterialTheme {
                // 2026-04-22 at 16:01:02.345 UTC, but format is HH:mm:ss.SSS
                // in the system zone — we assert the "Config Last Fetched"
                // label is visible and don't pin the exact clock-face which
                // depends on the CI's local zone.
                ConfigInfoPanel(snapshot = snap, lastFetchedAt = 1_713_801_662_345L)
            }
        }
        composeRule.onNodeWithText("Config Last Fetched", substring = true).assertIsDisplayed()
    }
}
