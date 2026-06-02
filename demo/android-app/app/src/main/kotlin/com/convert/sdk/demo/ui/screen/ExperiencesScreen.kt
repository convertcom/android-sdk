/*
 * Convert Android SDK Demo App — ExperiencesScreen
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.convert.sdk.demo.viewmodel.ExperienceResult
import com.convert.sdk.demo.viewmodel.SdkViewModel

/**
 * Story 7.3 AC-1–AC-5 — Experiences screen.
 *
 * Layout (top → bottom):
 *  1. Title + short description.
 *  2. Row with primary [Button] "Run Experience" (calls
 *     [SdkViewModel.runSingleExperience] with the hardcoded
 *     [DEFAULT_EXPERIENCE_KEY]) and secondary [OutlinedButton]
 *     "Run Experiences" (calls [SdkViewModel.runAllExperiences]).
 *  3. Either an empty-state text ("Tap a button to run an
 *     experience.") when [SdkViewModel.results] is empty, or a
 *     [LazyColumn] of [ExperienceResultCard]s rendered newest-first.
 *
 * Note (AC-6, F-029/F-081 remediation): the result-card composable
 * lives in this same `ui/screen` package as a sibling file
 * ([ExperienceResultCard]) — NOT in a shared `ui/component` directory —
 * because the UX spec (line 698) requires `ResultCard`s to be
 * screen-specific. Each sibling Story 7.x screen owns its own card
 * tailored to its data shape.
 *
 * Each [ExperienceResultCard] derives its title + items from the
 * corresponding [ExperienceResult]:
 *  - Non-error: title = `"Experience: <experienceKey>"`, items =
 *    `[("Variation", <variationKey or "(no key)">)]`.
 *  - Error: title = [ExperienceResult.errorMessage], items =
 *    `[("Hint", <errorHint>)]`, `isError = true`.
 *
 * Side-effect of the two button taps (AC-2, AC-3): the SDK fires a
 * `bucketing` event on the pub/sub bus that the shared [SdkViewModel]
 * already subscribes to, so the Events tab in the bottom-sheet
 * inspector lights up automatically without the screen wiring
 * anything itself.
 */
@Composable
fun ExperiencesScreen(viewModel: SdkViewModel) {
    val results by viewModel.results.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Experiences",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Run an A/B experience and observe the BUCKETING event " +
                "in the inspector below.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.runSingleExperience(DEFAULT_EXPERIENCE_KEY) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Run Experience")
            }
            OutlinedButton(
                onClick = { viewModel.runAllExperiences() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Run Experiences")
            }
        }
        Spacer(Modifier.size(4.dp))
        if (results.isEmpty()) {
            Text(
                text = "Tap a button to run an experience.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { it.id }) { result ->
                    ResultCardForResult(result)
                }
            }
        }
    }
}

/**
 * Private helper that maps an [ExperienceResult] to the
 * [ExperienceResultCard] shape. Extracted so the public composable
 * stays under detekt's function-size limits and reads top-to-bottom
 * as "layout first, rendering details second".
 */
@Composable
private fun ResultCardForResult(result: ExperienceResult) {
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

/**
 * Hardcoded experience key the "Run Experience" primary button
 * targets. Documented in `demo/android-app/README.md`. When the
 * backing Convert account has no experience with this key, the
 * screen exercises the null-variation error path (AC-4) — which
 * doubles as a useful visual test of the error card.
 */
private const val DEFAULT_EXPERIENCE_KEY: String = "test-experience"
