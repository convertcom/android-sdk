/*
 * Convert Android SDK Demo App — ConfigScreen (stub)
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convert.sdk.demo.viewmodel.SdkViewModel

/** Story 7.1 stub — full read-only Config panel (project ID, active experiences/features) lands later. */
@Composable
fun ConfigScreen(viewModel: SdkViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(text = "Config", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Read-only SDK configuration panel. Full implementation in a later 7.x story.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
