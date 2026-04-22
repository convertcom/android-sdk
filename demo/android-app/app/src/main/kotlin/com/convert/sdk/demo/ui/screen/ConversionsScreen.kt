/*
 * Convert Android SDK Demo App — ConversionsScreen (stub)
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

/** Story 7.1 stub — real impl in Story 7.5 (or wherever the conversion journey lands). */
@Composable
fun ConversionsScreen(viewModel: SdkViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(text = "Conversions", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Tap Buy to fire a CONVERSION event. Full implementation in a later 7.x story.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
