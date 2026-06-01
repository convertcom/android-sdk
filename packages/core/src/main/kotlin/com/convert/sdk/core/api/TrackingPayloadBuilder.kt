/*
 * Convert Android SDK — core/api
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.BucketingEvent
import com.convert.sdk.core.model.ConversionEvent
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.VisitorEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Stateless helper that assembles the outbound `/track/{sdkKey}` POST body
 * from a list of [VisitorEvent]s.
 *
 * ### Why it exists (Story 5.3)
 *
 * [ApiManager.flush] builds the same body from in-memory `VisitorEvent`s —
 * but [com.convert.sdk.android.worker.EventFlushWorker] (Story 5.3 AC-1)
 * runs in a WorkManager-provided context where `ApiManager` may not be
 * reachable (worker lives in a fresh process after the app is killed) and
 * reads persisted events from disk as [VisitorEvent] (Story 5.2/5.3's
 * port type). Extracting the payload shape here means both the
 * in-memory and disk paths produce byte-identical bodies; the wire format
 * is a single-source-of-truth concern.
 *
 * ### Wire shape
 *
 * The layout mirrors the JS SDK's `releaseQueue()` body (see
 * `javascript-sdk/packages/api/src/api-manager.ts:208-252`):
 *
 * ```json
 * {
 *   "accountId": "...",
 *   "projectId": "...",
 *   "enrichData": false,
 *   "source": "android",      // omitted if null
 *   "visitors": [
 *     {
 *       "visitorId": "v-1",
 *       "segments": { ... },   // omitted if empty/null
 *       "events": [ {...}, {...} ]
 *     }
 *   ]
 * }
 * ```
 *
 * Events group by `visitorId`. Within a group the `events` array
 * preserves enqueue order. `segments` on the visitor entry is snapshotted
 * from the LAST enqueue for that visitor — matching ApiManager.flush's
 * behaviour.
 *
 * ### Why not a simple `@Serializable` data class?
 *
 * The outbound body mixes static fields (accountId / projectId / source /
 * enrichData) with a map-of-arrays shape that the JS SDK produces
 * imperatively. A `@Serializable` wrapper would either (a) force the
 * backend to accept a slightly different JSON key order (Kotlin class
 * declaration order vs JS object-literal order — some backend log
 * analyzers anchor on key order), or (b) require a custom
 * `KSerializer` that rebuilds the map — at which point the hand-rolled
 * `buildJsonObject` form is clearer. [JsonObject] is a stable output
 * surface with deterministic key iteration since kotlinx-serialization
 * 1.6; production parity with the JS SDK is trivially verifiable by
 * reading the generated JSON.
 */
public object TrackingPayloadBuilder {

    /**
     * Builds the JSON string to POST to `/track/{sdkKey}`.
     *
     * @param events the events to ship; may be empty.
     * @param config the SDK configuration — `accountId` / `projectId` /
     *   `source` / the `enrichData` flag are pulled from here.
     * @param json the shared [Json] instance used to serialise the output.
     * @return the complete JSON body ready to be handed to
     *   [com.convert.sdk.core.port.HttpClient.post].
     */
    public fun build(
        events: List<VisitorEvent>,
        config: ConvertConfig,
        json: Json,
    ): String {
        val grouped: Map<String, List<VisitorEvent>> = events.groupBy { it.visitorId }
        val visitorsArray = buildJsonArray {
            grouped.forEach { (visitorId, groupEvents) ->
                add(buildVisitorEntry(visitorId, groupEvents))
            }
        }
        val payload = buildJsonObject {
            put(KEY_ACCOUNT_ID, config.data?.accountId ?: "")
            put(KEY_PROJECT_ID, config.data?.project?.id ?: "")
            put(KEY_ENRICH_DATA, config.data == null)
            config.network?.source?.let { put(KEY_SOURCE, it) }
            put(KEY_VISITORS, visitorsArray)
        }
        return json.encodeToString(JsonObject.serializer(), payload)
    }

    private fun buildVisitorEntry(visitorId: String, events: List<VisitorEvent>): JsonObject {
        val lastSegments: Map<String, JsonElement>? = events.last().segments
        val eventsArray = buildJsonArray {
            events.forEach { add(buildEventJson(it)) }
        }
        val segmentsObj = if (!lastSegments.isNullOrEmpty()) {
            buildJsonObject { lastSegments.forEach { (k, v) -> put(k, v) } }
        } else {
            null
        }
        return buildJsonObject {
            put(KEY_VISITOR_ID, visitorId)
            segmentsObj?.let { put(KEY_SEGMENTS, it) }
            put(KEY_EVENTS, eventsArray)
        }
    }

    /**
     * Converts a [VisitorEvent]'s [com.convert.sdk.core.model.TrackingEvent]
     * to the wire-format [JsonObject] for inclusion in the `events` array.
     */
    private fun buildEventJson(ve: VisitorEvent): JsonObject = when (val te = ve.event) {
        is BucketingEvent -> buildJsonObject {
            put(KEY_EVENT_TYPE, EVENT_TYPE_BUCKETING)
            put(
                KEY_DATA,
                buildJsonObject {
                    put(KEY_EXPERIENCE_ID, te.experienceId)
                    put(KEY_VARIATION_ID, te.variationId)
                },
            )
        }
        is ConversionEvent -> buildJsonObject {
            put(KEY_EVENT_TYPE, EVENT_TYPE_CONVERSION)
            put(KEY_DATA, buildConversionData(te.goalId, te.goalData))
        }
    }

    private fun buildConversionData(
        goalId: String,
        goalData: List<com.convert.sdk.core.model.GoalData>?,
    ): JsonObject {
        if (goalData.isNullOrEmpty()) {
            return buildJsonObject { put(KEY_GOAL_ID, goalId) }
        }
        val goalDataArray = buildJsonArray {
            goalData.forEach { entry ->
                val entryObj = buildJsonObject {
                    entry.key?.let { k -> put(KEY_KEY, serialNameFor(k)) }
                    put(KEY_VALUE, entry.value ?: JsonNull)
                }
                add(entryObj)
            }
        }
        return buildJsonObject {
            put(KEY_GOAL_ID, goalId)
            put(KEY_GOAL_DATA, goalDataArray)
        }
    }

    /**
     * Maps a [GoalDataKey] enum value to its on-wire camelCase form,
     * matching the JS SDK's `GoalDataKey` enum `@SerialName` values.
     */
    private fun serialNameFor(key: GoalDataKey): String = when (key) {
        GoalDataKey.AMOUNT -> "amount"
        GoalDataKey.PRODUCTS_COUNT -> "productsCount"
        GoalDataKey.TRANSACTION_ID -> "transactionId"
        GoalDataKey.CUSTOM_DIMENSION_1 -> "customDimension1"
        GoalDataKey.CUSTOM_DIMENSION_2 -> "customDimension2"
        GoalDataKey.CUSTOM_DIMENSION_3 -> "customDimension3"
        GoalDataKey.CUSTOM_DIMENSION_4 -> "customDimension4"
        GoalDataKey.CUSTOM_DIMENSION_5 -> "customDimension5"
    }

    // Wire-shape key constants — identical to ApiManager's private companion
    // to preserve byte-for-byte parity. Extracted here so both call sites
    // reference the same names.
    private const val KEY_ACCOUNT_ID: String = "accountId"
    private const val KEY_PROJECT_ID: String = "projectId"
    private const val KEY_ENRICH_DATA: String = "enrichData"
    private const val KEY_SOURCE: String = "source"
    private const val KEY_VISITORS: String = "visitors"
    private const val KEY_VISITOR_ID: String = "visitorId"
    private const val KEY_SEGMENTS: String = "segments"
    private const val KEY_EVENTS: String = "events"
    private const val KEY_EVENT_TYPE: String = "eventType"
    private const val KEY_DATA: String = "data"
    private const val KEY_EXPERIENCE_ID: String = "experienceId"
    private const val KEY_VARIATION_ID: String = "variationId"
    private const val KEY_GOAL_ID: String = "goalId"
    private const val KEY_GOAL_DATA: String = "goalData"
    private const val KEY_KEY: String = "key"
    private const val KEY_VALUE: String = "value"

    private const val EVENT_TYPE_BUCKETING: String = "bucketing"
    private const val EVENT_TYPE_CONVERSION: String = "conversion"
}
