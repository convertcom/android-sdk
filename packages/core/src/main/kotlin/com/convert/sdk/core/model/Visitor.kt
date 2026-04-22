/*
 * Convert Android SDK — core/model
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.model

import com.convert.sdk.core.model.generated.VisitorTrackingEvents
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Visitor slice of an outbound tracking payload.
 *
 * Mirrors the JS SDK's `Visitor` (`javascript-sdk/packages/types/src/Visitor.ts`).
 * One tracking batch contains one or more [Visitor] entries; each carries
 * the visitor's current segment snapshot plus the events emitted since
 * the previous flush.
 *
 * ### Event type — Story 5.1 decision
 *
 * [events] is typed as the OpenAPI-generated
 * [com.convert.sdk.core.model.generated.VisitorTrackingEvents] — the same
 * wire shape the JS SDK uses
 * (`javascript-sdk/packages/types/src/config/types.gen.ts:2738-2744`):
 *
 * ```
 * { eventType: 'bucketing' | 'conversion', data: { … } }
 * ```
 *
 * Story 5.1's readiness gate rejected the draft spec's
 * `viewExp`/`hitGoal`/`tr` discriminator — those tokens don't exist in
 * the JS SDK wire contract. There is NO separate transaction event type;
 * transactions are a `conversion` event with a `goalData` array (see
 * [com.convert.sdk.core.model.generated.VisitorTrackingEventsData.goalData]).
 *
 * Consequence: the previous flat `TrackingEvent(eventType, data, timestamp)`
 * model has been retired. The batcher in
 * [com.convert.sdk.core.api.ApiManager] wraps each event in an internal
 * `VisitorEvent(visitorId, segments, event)` envelope, then groups by
 * visitor at flush time so every [Visitor] object in the outbound request
 * matches the JS SDK's `releaseQueue()` shape.
 *
 * @property visitorId stable visitor identifier shared across SDKs.
 * @property segments custom segment values snapshotted for this visitor.
 * @property events events recorded for this visitor since the previous
 *   flush; order is preserved. Each element is a generated
 *   [VisitorTrackingEvents] so the serialized form matches the JS SDK.
 */
@Serializable
public data class Visitor(
    val visitorId: String,
    val segments: Map<String, JsonElement>? = null,
    val events: List<VisitorTrackingEvents>,
)
