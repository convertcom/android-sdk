/*
 * Convert Android SDK Demo App — EventInspectorSheet
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.demo.viewmodel.EventLifecycle
import com.convert.sdk.demo.viewmodel.InspectorEvent
import com.convert.sdk.demo.viewmodel.InspectorTab
import com.convert.sdk.demo.viewmodel.LogEntry
import com.convert.sdk.demo.viewmodel.SdkViewModel
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Story 7.2 — the persistent bottom-sheet Event Inspector.
 *
 * Layout (top → bottom):
 *   1. Network-status pill (AC-1) — `Online` / `Offline` with a
 *      coloured dot, wrapped in a `Modifier.semantics { liveRegion =
 *      LiveRegionMode.Polite }` so TalkBack announces connectivity
 *      changes.
 *   2. `TabRow` with "Events" + "Logs" tabs (AC-2). The active tab is
 *      read from `SdkViewModel.selectedTab` so it persists across
 *      screen navigation (Gotcha 3 — `remember {}` would reset).
 *   3. The selected tab's content: a `LazyColumn` of event items
 *      (AC-3) or log items (AC-4), newest first; or an empty-state
 *      message (AC-5) when the list is empty.
 *
 * The sheet itself is a child of [com.convert.sdk.demo.MainActivity]'s
 * `BottomSheetScaffold` so the drag-to-fullscreen gesture (AC-6) is
 * handled upstream.
 */
@Composable
fun EventInspectorSheet(viewModel: SdkViewModel) {
    val events by viewModel.events.collectAsState()
    val logs by viewModel.logs.collectAsState()
    val online by viewModel.networkOnline.collectAsState()
    val selectedTab by viewModel.selectedTab.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 200.dp),
    ) {
        NetworkStatusIndicator(
            online = online,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )

        // TabRow's `selectedTabIndex` must match the child Tab order
        // declared below. We use a fixed list in this exact order and
        // derive the index from that list — avoiding an
        // [InspectorTab.ordinal] dependency that would silently skew
        // the indicator if the enum members were reordered.
        val tabOrder = remember { listOf(InspectorTab.EVENTS, InspectorTab.LOGS) }
        TabRow(
            selectedTabIndex = tabOrder.indexOf(selectedTab).coerceAtLeast(0),
        ) {
            tabOrder.forEach { tab ->
                Tab(
                    selected = selectedTab == tab,
                    onClick = { viewModel.selectTab(tab) },
                    text = { Text(tabLabel(tab)) },
                )
            }
        }

        HorizontalDivider()

        when (selectedTab) {
            InspectorTab.EVENTS -> EventsTab(events = events)
            InspectorTab.LOGS -> LogsTab(logs = logs)
        }
    }
}

private fun tabLabel(tab: InspectorTab): String = when (tab) {
    InspectorTab.EVENTS -> "Events"
    InspectorTab.LOGS -> "Logs"
}

/* ---------------- Network status ---------------- */

@Composable
private fun NetworkStatusIndicator(
    online: Boolean,
    modifier: Modifier = Modifier,
) {
    val label = if (online) "Online" else "Offline"
    val dotColor = if (online) ONLINE_GREEN else OFFLINE_RED
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .semantics {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Network status: $label"
            },
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color = dotColor, shape = CircleShape),
        )
        Text(text = label, style = MaterialTheme.typography.labelLarge)
    }
}

/* ---------------- Events tab ---------------- */

@Composable
private fun EventsTab(events: List<InspectorEvent>) {
    if (events.isEmpty()) {
        EmptyState(text = "No events yet — tap an action above")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(events, key = { it.id }) { event ->
            EventRow(event)
        }
    }
}

@Composable
private fun EventRow(event: InspectorEvent) {
    val timestamp = remember(event.timestampMs) { TIME_FORMATTER.format(millisToLocal(event.timestampMs)) }
    val eventBadgeLabel = event.eventName.uppercase()
    val lifecycleLabel = when (event.lifecycle) {
        EventLifecycle.QUEUED -> "QUEUED"
        EventLifecycle.FLUSHING -> "FLUSHING"
        EventLifecycle.DELIVERED -> "DELIVERED"
        EventLifecycle.NONE -> null
    }
    val lifecycleColor = when (event.lifecycle) {
        EventLifecycle.QUEUED -> BADGE_AMBER
        EventLifecycle.FLUSHING -> BADGE_BLUE
        EventLifecycle.DELIVERED -> BADGE_GREEN
        EventLifecycle.NONE -> Color.Transparent
    }
    val contentDesc = buildString {
        append(eventBadgeLabel)
        if (lifecycleLabel != null) append(" $lifecycleLabel")
        append(" at $timestamp: ")
        append(renderPayload(event))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .semantics { contentDescription = contentDesc },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Badge(
                label = eventBadgeLabel,
                color = typeBadgeColor(event.eventName),
            )
            if (lifecycleLabel != null) {
                Badge(label = lifecycleLabel, color = lifecycleColor)
            }
            Text(
                text = timestamp,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = renderPayload(event),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/**
 * Renders the key-value pairs the UX spec calls out for BUCKETING /
 * CONVERSION. For unknown event names we fall back to a compact
 * `k=v` dump of the full payload so developers still see the data.
 */
private fun renderPayload(event: InspectorEvent): String {
    val p = event.payload
    return when (event.eventName) {
        "bucketing" -> listOfNotNull(
            p["experienceKey"]?.let { "experienceKey=$it" },
            p["variationKey"]?.let { "variationKey=$it" },
            p["visitorId"]?.let { "visitorId=$it" },
        ).joinToString(" ")
        "conversion" -> listOfNotNull(
            p["visitorId"]?.let { "visitorId=$it" },
            p["goalKey"]?.let { "goalKey=$it" },
            p["goalData"]?.let { "goalData=$it" },
        ).joinToString(" ")
        else -> p.entries.joinToString(" ") { (k, v) -> "$k=$v" }
    }
}

private fun typeBadgeColor(eventName: String): Color = when (eventName) {
    "bucketing" -> TYPE_BUCKETING
    "conversion" -> TYPE_CONVERSION
    else -> TYPE_SYSTEM
}

/* ---------------- Logs tab ---------------- */

@Composable
private fun LogsTab(logs: List<LogEntry>) {
    if (logs.isEmpty()) {
        EmptyState(text = "No logs yet")
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(logs, key = { it.id }) { entry ->
            LogRow(entry)
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    val timestamp = remember(entry.timestampMs) { TIME_FORMATTER.format(millisToLocal(entry.timestampMs)) }
    val levelLabel = entry.level.name
    val levelColor = when (entry.level) {
        LogLevel.ERROR -> LEVEL_ERROR
        LogLevel.WARN -> LEVEL_WARN
        LogLevel.INFO -> LEVEL_INFO
        LogLevel.DEBUG -> LEVEL_DEBUG
        LogLevel.TRACE, LogLevel.SILENT -> LEVEL_DEBUG // fall back to gray
    }
    val desc = "$levelLabel at $timestamp: ${entry.message}"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = desc },
    ) {
        Badge(label = levelLabel, color = levelColor)
        Text(
            text = timestamp,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
        )
        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
        )
    }
}

/* ---------------- Shared ---------------- */

@Composable
private fun Badge(label: String, color: Color) {
    Box(
        modifier = Modifier
            .background(color = color, shape = RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/* ---------------- Constants ---------------- */

private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private fun millisToLocal(ms: Long): Instant = Instant.ofEpochMilli(ms)

private val ONLINE_GREEN = Color(0xFF2E7D32)   // Material green 800
private val OFFLINE_RED = Color(0xFFC62828)    // Material red 800

// Type badges (BUCKETING / CONVERSION / SYSTEM) — distinct but neutral.
private val TYPE_BUCKETING = Color(0xFF5E35B1) // Material deep-purple 600
private val TYPE_CONVERSION = Color(0xFF00897B) // Material teal 600
private val TYPE_SYSTEM = Color(0xFF546E7A)    // Material blue-grey 600

// Lifecycle badges (AC-7) — amber / blue / green.
private val BADGE_AMBER = Color(0xFFFFA000)
private val BADGE_BLUE = Color(0xFF1976D2)
private val BADGE_GREEN = Color(0xFF2E7D32)

// Log level badges (AC-4) — gray / blue / amber / red.
private val LEVEL_DEBUG = Color(0xFF757575)    // gray 600
private val LEVEL_INFO = Color(0xFF1976D2)     // blue 700
private val LEVEL_WARN = Color(0xFFFFA000)     // amber 700
private val LEVEL_ERROR = Color(0xFFC62828)    // red 800
