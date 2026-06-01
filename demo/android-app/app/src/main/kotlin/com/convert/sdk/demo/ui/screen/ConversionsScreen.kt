/*
 * Convert Android SDK Demo App — ConversionsScreen
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convert.sdk.demo.viewmodel.ConversionResult
import com.convert.sdk.demo.viewmodel.SdkViewModel

/**
 * Story 7.5 AC-1–AC-5 — Conversions screen.
 *
 * Layout (top → bottom):
 *  1. Title + short description.
 *  2. Primary [Button] "Buy" that calls
 *     [SdkViewModel.trackPurchaseConversion] (tracks the hardcoded
 *     `"purchase-goal"` with AMOUNT=10.3 and PRODUCTS_COUNT=2 per AC-1).
 *  3. Either an empty-state text ("Tap Buy to track a conversion.")
 *     when [SdkViewModel.conversionResults] is empty, or a [LazyColumn]
 *     of [ConversionResultCard]s rendered newest-first.
 *
 * Each [ConversionResultCard] derives its title + items from the corresponding
 * [ConversionResult]:
 *  - **Non-dedup** (first tap): title = `"Conversion tracked: <goalKey>"`,
 *    items = `[("Amount", <value>), ("ProductsCount", <value>)]`.
 *  - **Dedup** (repeat tap): title = `"Conversion already tracked (dedup)"`,
 *    items = `[("Goal", <goalKey>)]`. This satisfies AC-3's visible
 *    dedup surface — the Logs tab shows the accompanying DEBUG line
 *    automatically because [SdkViewModel.trackPurchaseConversion]
 *    pushes it via the demo logger.
 *  - **Error** (reserved): title = [ConversionResult.errorMessage],
 *    items = `[("Hint", <errorHint>)]`, `isError = true`. Not produced
 *    by the current flow but wired for future stories.
 *
 * Side-effect of the Buy tap (AC-2, AC-4): the SDK fires a `conversion`
 * event on its pub/sub bus (when called against a real SDK) which the
 * shared [SdkViewModel] already subscribes to — so the Events tab in
 * the bottom-sheet inspector lights up automatically without the screen
 * wiring anything itself. On dedup taps, the SDK's own guard suppresses
 * the CONVERSION event per Story 4.3 AC-6, so no new event appears;
 * the DEBUG log surfaces in the Logs tab instead.
 */
@Composable
fun ConversionsScreen(viewModel: SdkViewModel) {
    val conversionResults by viewModel.conversionResults.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Conversions",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Tap Buy to fire a CONVERSION event. A repeat tap " +
                "surfaces the dedup path — the SDK silently skips the " +
                "repeat track per Story 4.3.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.trackPurchaseConversion() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Buy")
            }
        }
        Spacer(Modifier.size(4.dp))
        if (conversionResults.isEmpty()) {
            Text(
                text = "Tap Buy to track a conversion.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(conversionResults, key = { it.id }) { result ->
                    ResultCardForConversionResult(result)
                }
            }
        }
    }
}

/**
 * Private helper that maps a [ConversionResult] to the
 * [ConversionResultCard] shape. Extracted so the public composable
 * stays under detekt's function-size limits and reads top-to-bottom as
 * "layout first, rendering details second".
 */
@Composable
private fun ResultCardForConversionResult(result: ConversionResult) {
    when {
        result.isError -> {
            ConversionResultCard(
                title = result.errorMessage ?: "Conversion failed",
                items = listOfNotNull(
                    result.errorHint?.let { "Hint" to it },
                ),
                isError = true,
            )
        }
        result.isDedup -> {
            ConversionResultCard(
                title = "Conversion already tracked (dedup)",
                items = listOf("Goal" to result.goalKey),
            )
        }
        else -> {
            val items = buildList {
                result.amount?.let { add("Amount" to it.toString()) }
                result.productsCount?.let { add("ProductsCount" to it.toString()) }
            }
            ConversionResultCard(
                title = "Conversion tracked: ${result.goalKey}",
                items = items,
            )
        }
    }
}
