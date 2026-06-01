/*
 * Convert Android SDK Demo App — ConfigInfoPanel (Story 7.6 AC-5)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.convert.sdk.demo.viewmodel.ConfigSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Story 7.6 AC-5 — read-only panel rendering the SDK's current
 * configuration surface. Displayed inside [com.convert.sdk.demo.ui.screen.ConfigScreen]
 * when its [com.convert.sdk.demo.viewmodel.ConfigState] is `Loaded`.
 *
 * The panel renders six rows in order (top to bottom):
 *  1. **SDK Key** — masked via [ConfigSnapshot.maskedKey] (first 8 chars + `...`).
 *  2. **Environment** — raw string; `"(not set)"` when `null`.
 *  3. **Active Experiences** — count header + comma-joined keys; `"(none)"` on empty.
 *  4. **Active Features** — same shape as experiences.
 *  5. **Config Last Fetched** — `[lastFetchedAt]` formatted as `HH:mm:ss.SSS`
 *     in the system zone (matches the inspector's time format from 7.2).
 *  6. **Tracking Enabled** — `"Yes"` / `"No"` / `"—"` for `true`/`false`/`null`.
 *
 * Accessibility (Story 7.6 AC-8):
 *  - The whole panel uses `Modifier.semantics(mergeDescendants = true)`
 *    with a single concatenated content description so TalkBack reads
 *    the snapshot as one utterance rather than navigating through 12+
 *    individual `Text` composables.
 *  - The header icon has an explicit `contentDescription` in case the
 *    merge is ever disabled.
 *
 * @param snapshot the snapshot to render. Short SDK keys (<= 8 chars)
 *   render verbatim — [ConfigSnapshot.maskedKey] returns the raw key
 *   on short inputs because masking nothing would be misleading.
 * @param lastFetchedAt wall-clock millis when the snapshot was
 *   captured. Formatted as `HH:mm:ss.SSS` for display.
 * @param modifier layout modifier the caller can supply (defaults to
 *   none). The panel internally applies `.fillMaxWidth()`.
 */
@Composable
public fun ConfigInfoPanel(
    snapshot: ConfigSnapshot,
    lastFetchedAt: Long,
    modifier: Modifier = Modifier,
) {
    val background: Color = MaterialTheme.colorScheme.surfaceVariant
    val foreground: Color = MaterialTheme.colorScheme.onSurfaceVariant
    val annotationColor: Color = MaterialTheme.colorScheme.outline

    val environmentText = snapshot.environment ?: "(not set)"
    val experiencesValue = if (snapshot.experienceKeys.isEmpty()) {
        "(none)"
    } else {
        snapshot.experienceKeys.joinToString(", ")
    }
    val featuresValue = if (snapshot.featureKeys.isEmpty()) {
        "(none)"
    } else {
        snapshot.featureKeys.joinToString(", ")
    }
    val trackingText = when (snapshot.trackingEnabled) {
        true -> "Yes"
        false -> "No"
        null -> "—"
    }
    val timestamp = remember(lastFetchedAt) {
        TIME_FORMATTER.format(Instant.ofEpochMilli(lastFetchedAt))
    }
    val experiencesAnnotation = "${snapshot.experienceKeys.size} active"
    val featuresAnnotation = "${snapshot.featureKeys.size} active"

    val mergedDescription = buildString {
        append("Config. ")
        append("SDK Key: ${snapshot.maskedKey}. ")
        append("Environment: $environmentText. ")
        append("Active Experiences: $experiencesAnnotation — $experiencesValue. ")
        append("Active Features: $featuresAnnotation — $featuresValue. ")
        append("Config Last Fetched: $timestamp. ")
        append("Tracking Enabled: $trackingText.")
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(color = background, shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = mergedDescription
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = "Config",
                tint = foreground,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = "SDK Configuration",
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                fontWeight = FontWeight.SemiBold,
            )
        }

        InfoRow(
            label = "SDK Key",
            value = snapshot.maskedKey,
            foreground = foreground,
        )
        InfoRow(
            label = "Environment",
            value = environmentText,
            foreground = foreground,
        )
        InfoRow(
            label = "Active Experiences",
            value = experiencesValue,
            annotation = experiencesAnnotation,
            annotationColor = annotationColor,
            foreground = foreground,
        )
        InfoRow(
            label = "Active Features",
            value = featuresValue,
            annotation = featuresAnnotation,
            annotationColor = annotationColor,
            foreground = foreground,
        )
        InfoRow(
            label = "Config Last Fetched",
            value = timestamp,
            foreground = foreground,
        )
        InfoRow(
            label = "Tracking Enabled",
            value = trackingText,
            foreground = foreground,
        )
    }
}

/**
 * Private row helper. Kept internal to this file — the public shape
 * callers interact with is [ConfigInfoPanel] alone.
 */
@Composable
private fun InfoRow(
    label: String,
    value: String,
    foreground: Color,
    annotation: String? = null,
    annotationColor: Color = Color.Unspecified,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = foreground,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = foreground,
            fontFamily = FontFamily.Monospace,
        )
        if (annotation != null) {
            Text(
                text = annotation,
                style = MaterialTheme.typography.labelMedium,
                color = annotationColor,
            )
        }
    }
}

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
