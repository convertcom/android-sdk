/*
 * Convert Android SDK Demo App — ConfigResultCard
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
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Story 7.6 AC-7 — screen-specific result card for the Config screen's
 * Failed branch.
 *
 * The UX spec line 698 mandates that `ResultCard` is **screen-specific**:
 * "each screen creates result cards from its SDK operation outputs."
 * This composable lives in the same package as [ConfigScreen]
 * (`ui/screen/`) — not in a shared `ui/component/` directory — parallel
 * to [ExperienceResultCard] (Story 7.3), [FeatureResultCard]
 * (Story 7.4), and [ConversionResultCard] (Story 7.5). Each screen owns
 * its own card composable so the four screens can evolve independently
 * without coupling through a shared API.
 *
 * Unlike the experience / feature / conversion variants, the Config
 * screen ONLY ever produces an error card — the success branch renders
 * the [com.convert.sdk.demo.ui.component.ConfigInfoPanel] instead. The
 * card therefore has no `isError` parameter; it is always error-styled
 * (errorContainer background, error icon, "Error:" prefix on the merged
 * content description).
 *
 * Accessibility (AC-8): the whole card uses `Modifier.semantics`
 * (with `mergeDescendants = true`) and a single `contentDescription`
 * that concatenates the title and every `label: value` pair, so
 * TalkBack reads the card as one utterance instead of navigating the
 * user through each child `Text` (UX spec line 877).
 *
 * @param title the failure reason — typically the most recent
 *   WARN/ERROR log message captured by the ViewModel before READY
 *   fired, or the timeout sentinel "Configuration fetch timed out"
 *   when the 10-second deadline elapses with no events.
 * @param items the rows rendered underneath the title. The Config
 *   screen always supplies a single `"Hint" -> "Check network + SDK
 *   key"` pair from the [com.convert.sdk.demo.viewmodel.ConfigState.Failed]
 *   carrier; the parameter remains a list for symmetry with the sibling
 *   cards and to leave room for future hint diversification without an
 *   API change.
 * @param modifier layout modifier the caller can supply (defaults to
 *   no extra modifier). The card internally applies
 *   `.fillMaxWidth()` after the caller's modifier so padding / width
 *   constraints from the screen composable are respected.
 */
@Composable
fun ConfigResultCard(
    title: String,
    items: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val background: Color = MaterialTheme.colorScheme.errorContainer
    val foreground: Color = MaterialTheme.colorScheme.onErrorContainer

    // Build a single merged content description up-front so TalkBack
    // reads the card as one utterance. The "Error:" prefix conveys the
    // state non-visually (colour alone is insufficient for AC-8).
    val mergedDescription: String = buildString {
        append("Error: ")
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
                imageVector = Icons.Filled.Error,
                contentDescription = "Error",
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
