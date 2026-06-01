/*
 * Convert Android SDK Demo App — FeaturesScreen
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
import com.convert.sdk.demo.BuildConfig
import com.convert.sdk.demo.viewmodel.FeatureResult
import com.convert.sdk.demo.viewmodel.SdkViewModel

/**
 * Story 7.4 AC-1–AC-6 — Features screen.
 *
 * Layout (top → bottom):
 *  1. Title + short description.
 *  2. Row with primary [Button] "Run Feature" (calls
 *     [SdkViewModel.runFeature] with the hardcoded
 *     [DEFAULT_FEATURE_KEY]) and secondary [OutlinedButton]
 *     "Run Features" (calls [SdkViewModel.runFeatures]).
 *  3. Either an empty-state text ("Tap a button to run a
 *     feature.") when [SdkViewModel.featureResults] is empty, or a
 *     [LazyColumn] of [FeatureResultCard]s rendered newest-first.
 *
 * Each [FeatureResultCard] derives its title + items from the corresponding
 * [FeatureResult]:
 *  - Non-error: title = `"Feature: <featureKey>"`, items start with
 *    a `Status` row (`"enabled"` / `"disabled"`), then (when present)
 *    an `Experience` row with the owning experience's key, followed
 *    by one typed-variable row per entry in [FeatureResult.variables].
 *    Each variable row carries a `trailingAnnotation` of
 *    `"[<Type>]"` that the [FeatureResultCard] renders in `labelMedium` +
 *    `outline` color per AC-4.
 *  - Error: title = [FeatureResult.errorMessage], items =
 *    `[("Hint", <errorHint>)]`, `isError = true`.
 *
 * Side-effect of the two button taps (AC-2, AC-3): the SDK fires
 * `bucketing` events on its pub/sub bus (features resolve through
 * experience bucketing per Story 4.1 AC-3). The shared [SdkViewModel]
 * already subscribes to that event, so the Events tab in the
 * bottom-sheet inspector lights up automatically without the screen
 * wiring anything itself.
 */
@Composable
fun FeaturesScreen(viewModel: SdkViewModel) {
    val featureResults by viewModel.featureResults.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "Features",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            text = "Evaluate a feature flag and observe its enabled/disabled " +
                "status plus typed variables rendered as name: value [Type].",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.runFeature(DEFAULT_FEATURE_KEY) },
                modifier = Modifier.weight(1f),
            ) {
                Text("Run Feature")
            }
            OutlinedButton(
                onClick = { viewModel.runFeatures() },
                modifier = Modifier.weight(1f),
            ) {
                Text("Run Features")
            }
        }
        Spacer(Modifier.size(4.dp))
        if (featureResults.isEmpty()) {
            Text(
                text = "Tap a button to run a feature.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(featureResults, key = { it.id }) { result ->
                    ResultCardForFeatureResult(result)
                }
            }
        }
    }
}

/**
 * Private helper that maps a [FeatureResult] to the [FeatureResultCard]
 * shape. Extracted so the public composable stays under detekt's
 * function-size limits and reads top-to-bottom as "layout first,
 * rendering details second".
 *
 * The mapping preserves the order of [FeatureResult.variables] so
 * developers see rows in the same order the Convert config declared
 * them — which matches the API response ordering (a `LinkedHashMap`
 * survives `kotlinx.serialization`'s default deserialisation).
 */
@Composable
private fun ResultCardForFeatureResult(result: FeatureResult) {
    if (result.isError) {
        FeatureResultCard(
            title = result.errorMessage ?: "No feature",
            items = listOfNotNull(
                result.errorHint?.let { FeatureResultCardItem(label = "Hint", value = it) },
            ),
            isError = true,
        )
        return
    }

    val items = buildList {
        add(
            FeatureResultCardItem(
                label = "Status",
                value = if (result.enabled) "enabled" else "disabled",
            ),
        )
        result.experienceKey?.let { key ->
            add(FeatureResultCardItem(label = "Experience", value = key))
        }
        result.variables.forEach { variable ->
            add(
                FeatureResultCardItem(
                    label = variable.name,
                    value = variable.value,
                    trailingAnnotation = "[${variable.typeLabel}]",
                ),
            )
        }
    }

    FeatureResultCard(
        title = "Feature: ${result.featureKey}",
        items = items,
    )
}

/**
 * Feature key the "Run Feature" primary button targets. Documented in
 * `demo/android-app/README.md`. Story 7.7 redirected the source of
 * truth to [BuildConfig.convertFeatureKey] so a developer can override
 * the key from `local.properties`; the fallback literal `"test-feature"`
 * matches the pre-existing default and keeps the `FeaturesScreenTest`
 * click-path assertions green without changes.
 *
 * When the backing Convert account has no feature with this key, the
 * screen exercises the null-feature error path (AC-5) — which doubles
 * as a useful visual test of the error card.
 *
 * `const` dropped because [BuildConfig] fields are `static final` Java
 * strings but NOT Kotlin compile-time constants — `val` is the correct
 * replacement. Runtime semantics are identical.
 */
private val DEFAULT_FEATURE_KEY: String = BuildConfig.convertFeatureKey
