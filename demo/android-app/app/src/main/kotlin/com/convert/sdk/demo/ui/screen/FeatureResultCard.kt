/*
 * Convert Android SDK Demo App — FeatureResultCard
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Story 7.4 — a single row rendered in a [FeatureResultCard].
 *
 * Extends the basic `label + value` shape with an optional
 * [trailingAnnotation] used by the Features screen to display the
 * typed-variable `[Type]` suffix in the UX spec's `labelMedium` +
 * `outline` color (AC-4). When `null` the row renders as a plain
 * two-column layout.
 *
 * @property label short row label (e.g. `"Status"`, `"buttonColor"`).
 * @property value value text rendered in monospace (e.g. `"enabled"`,
 *   `"\"blue\""`, `"3"`).
 * @property trailingAnnotation optional secondary annotation shown to
 *   the right of the value in `labelMedium` typography and
 *   `colorScheme.outline` color. For typed variables this is `"[string]"`,
 *   `"[integer]"`, etc. — using the JS SDK canonical lowercase type
 *   vocabulary (F-030 remediation) — per Story 7.4 AC-4.
 */
public data class FeatureResultCardItem(
    val label: String,
    val value: String,
    val trailingAnnotation: String? = null,
)

/**
 * Story 7.4 AC-4 — screen-specific result card for the Features screen.
 *
 * The UX spec line 698 mandates that result cards are **screen-specific**:
 * "each screen creates result cards from its SDK operation outputs."
 * This composable lives in the same package as [FeaturesScreen]
 * (`ui/screen/`) — not in a shared `ui/component/` directory — parallel
 * to [ExperienceResultCard] which serves the Experiences screen.
 *
 * Each row may carry an optional [FeatureResultCardItem.trailingAnnotation]
 * rendered in `labelMedium` typography and `colorScheme.outline` color,
 * positioned to the right of the value. The annotation also feeds the
 * card's merged content description so TalkBack announces typed
 * variables with their `[Type]` suffix.
 *
 * Two styles:
 * - **Non-error** (`isError = false`, default): surfaceVariant background,
 *   a tick icon. Used for enabled/disabled feature cards with typed
 *   variable rows.
 * - **Error** (`isError = true`): errorContainer background, an error icon;
 *   the merged content description is prefixed with "Error:" so TalkBack
 *   announces the state explicitly (colour alone is not enough for
 *   accessibility).
 *
 * Accessibility (AC-5): the whole card uses `Modifier.semantics`
 * (with `mergeDescendants = true`) and a single `contentDescription`
 * that concatenates the title and every `label: value [annotation]`
 * triple, so TalkBack reads the card as one utterance.
 *
 * @param title the card title. Non-error cards use `"Feature: <key>"`;
 *   error cards use a full error sentence.
 * @param items the rows rendered underneath the title.
 * @param isError `true` to use the error palette and icon and to
 *   prefix the merged content description with `"Error: "`.
 * @param modifier layout modifier the caller can supply.
 */
@Composable
fun FeatureResultCard(
    title: String,
    items: List<FeatureResultCardItem>,
    isError: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val background: Color = if (isError) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val foreground: Color = if (isError) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // AC-4: annotations use the `outline` color role regardless of the
    // card's error state — they are metadata, not content. The same
    // colour in success and error cards keeps the type-label styling
    // consistent across both paths.
    val annotationColor: Color = MaterialTheme.colorScheme.outline
    val statusIcon: ImageVector = if (isError) Icons.Filled.Error else Icons.Filled.CheckCircle
    val statusIconDescription: String = if (isError) "Error" else "Success"

    // Build a single merged content description up-front so TalkBack
    // reads the card as one utterance (AC-5). Error cards include an
    // "Error:" prefix so the state is conveyed non-visually. AC-4: when
    // an item carries a trailing annotation, append it after the value
    // so TalkBack announces typed variables with their `[Type]` suffix.
    val mergedDescription: String = buildString {
        if (isError) append("Error: ")
        append(title)
        if (items.isNotEmpty()) {
            append(". ")
            append(
                items.joinToString(separator = ". ") { item ->
                    if (item.trailingAnnotation != null) {
                        "${item.label}: ${item.value} ${item.trailingAnnotation}"
                    } else {
                        "${item.label}: ${item.value}"
                    }
                },
            )
        }
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
                imageVector = statusIcon,
                contentDescription = statusIconDescription,
                tint = foreground,
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                fontWeight = FontWeight.SemiBold,
            )
        }
        items.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = item.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = item.value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = foreground,
                    fontFamily = FontFamily.Monospace,
                )
                item.trailingAnnotation?.let { annotation ->
                    Text(
                        text = annotation,
                        style = MaterialTheme.typography.labelMedium,
                        color = annotationColor,
                    )
                }
            }
        }
    }
}
