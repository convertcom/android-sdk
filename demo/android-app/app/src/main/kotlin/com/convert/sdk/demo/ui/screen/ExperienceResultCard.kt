/*
 * Convert Android SDK Demo App — ExperienceResultCard
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
 * Story 7.3 AC-6 — screen-specific result card for the Experiences
 * screen.
 *
 * The UX spec line 698 mandates that `ResultCard` is **screen-specific**:
 * "each screen creates result cards from its SDK operation outputs."
 * This composable lives in the same package as [ExperiencesScreen]
 * (`ui/screen/`) — not in a shared `ui/component/` directory — to make
 * the screen-scoped ownership obvious at the package level. Each
 * sibling screen (Features, Conversions, Offline) implements its own
 * card composable tailored to its data shape, so each screen can
 * evolve independently without coupling through a shared API.
 *
 * The composable supports the variants the UX spec lists for the
 * Experiences screen (lines 642–654):
 *
 * - **Variation result** (`isError = false`, default): surfaceVariant
 *   background, a tick icon. Used when the runner produced a bucketed
 *   variation. Title is typically `"Experience: <key>"`; items render
 *   `Variation → <variationKey>`.
 * - **Multi-result**: the screen produces one card per variation when
 *   "Run Experiences" returns multiple — each rendered with the same
 *   non-error styling above.
 * - **Error**: `isError = true`. Uses the errorContainer background
 *   and an error icon; the merged content description is prefixed with
 *   "Error:" so TalkBack announces the state explicitly (colour alone
 *   is not enough for accessibility).
 *
 * Accessibility (AC-5): the whole card uses `Modifier.semantics`
 * (with `mergeDescendants = true`) and a single `contentDescription`
 * that concatenates the title and every `label: value` pair, so
 * TalkBack reads the card as one utterance instead of navigating the
 * user through each child `Text` (UX spec line 877: "Use
 * `Modifier.semantics` to group related content in `ResultCard`
 * (e.g., variation key + value as one announcement)").
 *
 * @param title the card title. Non-error cards typically use the form
 *   `"Experience: <key>"`; error cards use a full error sentence.
 * @param items the rows rendered underneath the title. Each entry's
 *   `first` is the label (e.g. `"Variation"`), `second` is the value
 *   (e.g. `"treatment"`). Values render in monospace so ids/keys read
 *   cleanly.
 * @param isError `true` to use the error palette and icon and to
 *   prefix the merged content description with `"Error: "`.
 * @param modifier layout modifier the caller can supply (defaults to
 *   no extra modifier). The card internally applies
 *   `.fillMaxWidth()` after the caller's modifier so padding / width
 *   constraints from the screen composable are respected.
 */
@Composable
fun ExperienceResultCard(
    title: String,
    items: List<Pair<String, String>>,
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
    val statusIcon: ImageVector = if (isError) Icons.Filled.Error else Icons.Filled.CheckCircle
    val statusIconDescription: String = if (isError) "Error" else "Success"

    // Build a single merged content description up-front so TalkBack
    // reads the card as one utterance (AC-5). Error cards include an
    // "Error:" prefix so the state is conveyed non-visually.
    val mergedDescription: String = buildString {
        if (isError) append("Error: ")
        append(title)
        if (items.isNotEmpty()) {
            append(". ")
            append(items.joinToString(separator = ". ") { (label, value) -> "$label: $value" })
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
        items.forEach { (label, value) ->
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
            }
        }
    }
}
