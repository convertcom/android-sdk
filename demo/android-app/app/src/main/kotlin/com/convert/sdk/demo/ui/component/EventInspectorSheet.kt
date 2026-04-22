/*
 * Convert Android SDK Demo App — EventInspectorSheet (stub)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convert.sdk.demo.viewmodel.SdkViewModel

/**
 * Story 7.1 stub — the persistent bottom-sheet inspector. The full
 * two-tab (Events / Logs), newest-first LazyColumn, QUEUED/DELIVERED
 * badges, and Events-tab / Logs-tab layout land in Story 7.2. This
 * stub renders just enough to keep [com.convert.sdk.demo.MainActivity]'s
 * BottomSheetScaffold composable clean — a peek title + a live count
 * of captured events — so AC-4 / AC-10 build cleanly.
 */
@Composable
fun EventInspectorSheet(viewModel: SdkViewModel) {
    val events by viewModel.events.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val online by viewModel.networkOnline.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp)
            .padding(16.dp),
    ) {
        Text(text = "Event Inspector", style = MaterialTheme.typography.titleMedium)
        Text(
            text = "Network: ${if (online) "Online" else "Offline"}",
            style = MaterialTheme.typography.labelMedium,
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        Text(
            text = "Events: ${events.size}   Logs: ${logs.size}",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            text = "Full two-tab inspector lands in Story 7.2.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
