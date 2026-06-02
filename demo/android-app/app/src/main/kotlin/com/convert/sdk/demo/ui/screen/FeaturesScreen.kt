/*
 * Convert Android SDK Demo App — FeaturesScreen (stub)
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

/** Story 7.1 stub — real impl in Story 7.4. */
@Composable
fun FeaturesScreen(viewModel: SdkViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(text = "Features", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Feature-flag evaluation with typed variables. Full implementation in Story 7.4.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
