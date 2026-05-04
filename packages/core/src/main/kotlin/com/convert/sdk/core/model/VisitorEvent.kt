/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Per-event wrapper that retains the visitor identity and segment snapshot
 * alongside the queued [TrackingEvent].
 *
 * Introduced by the Story 5.3 "Port Contract Amendment" (which spans
 * Stories 1.2 + 5.2 + 5.3): the [com.convert.sdk.core.port.EventQueue]
 * port persists `List<VisitorEvent>` so that visitor metadata survives
 * process death — when the foreground returns and the SDK reads the
 * persisted queue back, every event carries the visitor it was emitted
 * for and the segments that were active at the moment of emission.
 *
 * Equality is structural (data class) so the type is safe to use as a
 * deduplication key (see Story 5.2 AC-6 dedup discussion).
 *
 * @property visitorId the visitor id active when the event was recorded.
 * @property segments custom segment snapshot active when the event was
 *   recorded; `null` when the visitor had no custom segments.
 * @property event the underlying tracking event payload.
 */
@Serializable
public data class VisitorEvent(
    val visitorId: String,
    val segments: Map<String, JsonElement>? = null,
    val event: TrackingEvent,
)
