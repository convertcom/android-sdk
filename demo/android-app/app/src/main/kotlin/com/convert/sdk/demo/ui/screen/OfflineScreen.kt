/*
 * Convert Android SDK Demo App — OfflineScreen (Story 7.6 AC-1..AC-4)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.convert.sdk.demo.viewmodel.ConversionResult
import com.convert.sdk.demo.viewmodel.ExperienceResult
import com.convert.sdk.demo.viewmodel.SdkViewModel

/**
 * Story 7.6 AC-1 / AC-2 / AC-3 / AC-4 — Offline screen.
 *
 * Demonstrates the SDK's offline resilience by letting the developer
 * enable airplane mode on the device (AC-1 — Story Gotcha 1: no
 * programmatic toggle is possible on modern Android), tap the two
 * action buttons while offline, observe the resulting events land in
 * the inspector with a QUEUED badge (AC-2 / AC-3), then disable
 * airplane mode and watch those events transition to DELIVERED via
 * Story 5.2's NetworkObserver → `api.queue.released` pipeline (AC-4).
 *
 * Layout (top → bottom):
 *
 *  1. Title + short description referencing airplane mode.
 *  2. Network-status banner — a coloured dot + label ("Online" /
 *     "Offline") bound to `viewModel.networkOnline`. Wrapped in
 *     `Modifier.semantics { liveRegion = Polite }` so TalkBack
 *     announces connectivity changes (AC-8).
 *  3. Action row with two buttons:
 *     - **Run Experience** (primary) — calls
 *       [SdkViewModel.runSingleExperience] with the hardcoded
 *       [DEFAULT_EXPERIENCE_KEY], mirroring the Experiences screen.
 *       The SDK emits a BUCKETING event (QUEUED while offline,
 *       transitioning to DELIVERED after reconnect).
 *     - **Buy** (secondary) — calls
 *       [SdkViewModel.trackPurchaseConversion], mirroring the
 *       Conversions screen. The SDK emits a CONVERSION event.
 *  4. Result list — unified LazyColumn of the latest experience and
 *     conversion ResultCards, newest-first.  Empty state when both
 *     lists are empty.
 *
 * The inspector bottom sheet (shared across every demo screen) renders
 * the QUEUED → DELIVERED badge transitions automatically because the
 * [SdkViewModel] already subscribes to `api.queue.released` and toggles
 * the lifecycle (Story 7.2). The Offline screen therefore does not
 * duplicate that wiring.
 */
@Composable
fun OfflineScreen(viewModel: SdkViewModel) {
    val online by viewModel.networkOnline.collectAsState()
    val experienceResults by viewModel.results.collectAsState()
    val conversionResults by viewModel.conversionResults.collectAsState()

    // Merge the two newest-first lists chronologically. Each list is
    // independently capped at SdkViewModel.RESULTS_CAP, so the combined
    // view is bounded at 2 * cap — enough for a demo loop.
    val combined: List<Any> = experienceResults + conversionResults

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Offline",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Enable airplane mode, tap the buttons below, then " +
                "re-enable connectivity. The inspector will show events " +
                "transition from QUEUED (amber) to DELIVERED (green).",
            style = MaterialTheme.typography.bodyMedium,
        )

        NetworkBanner(online = online)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(onClick = { viewModel.runSingleExperience(DEFAULT_EXPERIENCE_KEY) }) {
                Text("Run Experience")
            }
            OutlinedButton(onClick = { viewModel.trackPurchaseConversion() }) {
                Text("Buy")
            }
        }
        Spacer(Modifier.size(4.dp))

        if (combined.isEmpty()) {
            Text(
                text = "Tap Run Experience or Buy to queue an event.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    items = combined,
                    key = { result ->
                        when (result) {
                            is ExperienceResult -> "exp-${result.id}"
                            is ConversionResult -> "conv-${result.id}"
                            else -> result.hashCode()
                        }
                    },
                ) { result ->
                    OfflineResultCard(result)
                }
            }
        }
    }
}

/**
 * Story 7.6 AC-1 / AC-8 — screen-level network-status banner. Mirrors
 * the inspector's own indicator (which fires on every screen) so the
 * Offline screen's central affordance does not rely on the user having
 * the bottom sheet expanded. The `liveRegion = Polite` modifier makes
 * TalkBack announce each flip.
 */
@Composable
private fun NetworkBanner(online: Boolean) {
    val label = if (online) "Online" else "Offline"
    val description = if (online) {
        "Network: Online — events flush live."
    } else {
        "Network: Offline — events queued."
    }
    val dotColor = if (online) ONLINE_GREEN else OFFLINE_RED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = description
            },
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = dotColor, shape = CircleShape),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            text = if (online) "— events flush live" else "— events queued",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Maps either result shape to the corresponding screen-specific card.
 *
 * Per the UX spec line 698 + the Story 7.3/7.4/7.5 screen-specific
 * convention (see [ExperienceResultCard], [ConversionResultCard]),
 * `ResultCard` is no longer a shared component — each screen owns its
 * own card composable. The Offline screen renders BOTH experience and
 * conversion outcomes (one card per `Run Experience` / `Buy` tap), so
 * it delegates to the existing sibling cards rather than introducing a
 * third specialised "OfflineResultCard" — the data shapes the Offline
 * buttons produce are identical to those produced by the Experiences
 * and Conversions screens, and visual parity across screens is the
 * point.
 *
 * Kept private so the public composable stays small and reads
 * top-to-bottom as "layout first, rendering details second" (matches
 * the Experiences/Features/Conversions screen precedent).
 */
@Composable
private fun OfflineResultCard(result: Any) {
    when (result) {
        is ExperienceResult -> {
            if (result.isError) {
                ExperienceResultCard(
                    title = result.errorMessage ?: "No variation",
                    items = listOfNotNull(result.errorHint?.let { "Hint" to it }),
                    isError = true,
                )
            } else {
                ExperienceResultCard(
                    title = "Experience: ${result.experienceKey}",
                    items = listOf("Variation" to (result.variationKey ?: "(no key)")),
                )
            }
        }
        is ConversionResult -> {
            when {
                result.isError -> ConversionResultCard(
                    title = result.errorMessage ?: "Conversion failed",
                    items = listOfNotNull(result.errorHint?.let { "Hint" to it }),
                    isError = true,
                )
                result.isDedup -> ConversionResultCard(
                    title = "Conversion already tracked (dedup)",
                    items = listOf("Goal" to result.goalKey),
                )
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
    }
}

/**
 * Hardcoded experience key tapped by the Offline screen's "Run
 * Experience" button. Matches the Experiences screen's
 * [DEFAULT_EXPERIENCE_KEY] verbatim so a developer bucketed on one
 * screen remains bucketed when they return to Offline — the SDK
 * sticky-bucketing path (Story 3.2) hits the same slot.
 */
private const val DEFAULT_EXPERIENCE_KEY: String = "test-experience"

private val ONLINE_GREEN = Color(0xFF2E7D32)  // Material green 800
private val OFFLINE_RED = Color(0xFFC62828)   // Material red 800
