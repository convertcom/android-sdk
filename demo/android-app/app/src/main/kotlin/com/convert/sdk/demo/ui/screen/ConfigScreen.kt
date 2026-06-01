/*
 * Convert Android SDK Demo App — ConfigScreen (Story 7.6 AC-5 / AC-6 / AC-7)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convert.sdk.demo.ui.component.ConfigInfoPanel
import com.convert.sdk.demo.viewmodel.ConfigState
import com.convert.sdk.demo.viewmodel.SdkViewModel

/**
 * Story 7.6 AC-5 / AC-6 / AC-7 — Config screen.
 *
 * Dispatches on [SdkViewModel.configState]. Three branches:
 *
 *  - [ConfigState.Loading] (AC-6) — centred `CircularProgressIndicator`
 *    plus a "Fetching configuration..." caption. Rendered until the
 *    SDK fires its first `ready` signal or enough WARN/ERROR logs
 *    accumulate for the ViewModel to trip [ConfigState.Failed].
 *  - [ConfigState.Loaded] (AC-5) — the [ConfigInfoPanel] with the six
 *    required rows (masked SDK key, environment, active
 *    experiences/features with count + keys, formatted
 *    last-fetched timestamp, tracking Yes/No/—).
 *  - [ConfigState.Failed] (AC-7) — an error-styled [ConfigResultCard]
 *    with the `reason` from the latest WARN/ERROR log (or the
 *    10-second timeout sentinel) and the canonical
 *    `"Check network + SDK key"` hint literal.
 *
 * The screen deliberately has no tap targets — it is a read-only
 * snapshot view. Developers toggle SDK state from the other screens
 * (Experiences, Features, Conversions, Offline) and watch Config
 * update reactively through the StateFlow.
 */
@Composable
fun ConfigScreen(viewModel: SdkViewModel) {
    val configState by viewModel.configState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Config",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Read-only snapshot of the SDK's current configuration.",
            style = MaterialTheme.typography.bodyMedium,
        )
        when (val state = configState) {
            is ConfigState.Loading -> LoadingBranch()
            is ConfigState.Loaded -> ConfigInfoPanel(
                snapshot = state.snapshot,
                lastFetchedAt = state.lastFetchedAt,
            )
            is ConfigState.Failed -> FailedBranch(state)
        }
    }
}

@Composable
private fun LoadingBranch() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(48.dp))
            Text(
                text = "Fetching configuration...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FailedBranch(state: ConfigState.Failed) {
    ConfigResultCard(
        title = state.reason,
        items = listOf("Hint" to state.hint),
    )
}
