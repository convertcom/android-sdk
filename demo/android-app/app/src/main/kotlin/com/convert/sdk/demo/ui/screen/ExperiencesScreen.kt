/*
 * Convert Android SDK Demo App — ExperiencesScreen (stub)
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

/**
 * Story 7.1 stub — the full implementation lands in Story 7.3.
 * Renders a placeholder so the bottom-sheet scaffold composes cleanly
 * when the nav graph starts here.
 */
@Composable
fun ExperiencesScreen(viewModel: SdkViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(text = "Experiences", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Run experiences and observe the BUCKETING events in the inspector below. " +
                "Full implementation in Story 7.3.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
