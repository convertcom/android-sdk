/*
 * Convert Android SDK Demo App — OfflineScreen (stub)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convert.sdk.demo.viewmodel.SdkViewModel

/**
 * Story 7.1 stub — renders the current online/offline indicator from
 * the shared ViewModel. The real offline journey (airplane-mode demo,
 * queue size, flush confirmation) lands in Story 7.6.
 */
@Composable
fun OfflineScreen(viewModel: SdkViewModel) {
    val online by viewModel.networkOnline.collectAsState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(text = "Offline", style = MaterialTheme.typography.titleLarge)
        Text(
            text = if (online) "Network: Online" else "Network: Offline",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
