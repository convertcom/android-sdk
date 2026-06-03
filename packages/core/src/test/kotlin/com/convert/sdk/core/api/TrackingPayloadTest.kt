/*
 * Convert Android SDK — core/api TrackingPayloadTest (Story 5.6)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.config.NetworkConfig
import com.convert.sdk.core.model.BucketingEvent
import com.convert.sdk.core.model.ConversionEvent
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.GoalDataKey
import com.convert.sdk.core.model.TrackingEvent
import com.convert.sdk.core.model.VisitorEvent
import com.convert.sdk.core.model.generated.ConfigProject
import com.convert.sdk.core.model.generated.ConfigResponseData
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Story 5.6 — tracking payload CONTRACT test.
 *
 * ### HARD GATE — payload contract must not drift.
 *
 * This file is the single canonical assertion of the outbound
 * `POST /track/{sdkKey}` wire format. Any code change that perturbs the
 * serialized body — new / renamed / reordered keys, different event-type
 * tokens, altered nesting — must first be reflected in the
 * [JS SDK](https://github.com/Convertcom/javascript-sdk)'s
 * `api-manager.ts:releaseQueue()` because BOTH SDKs feed the same backend
 * pipeline. Landing a unilateral shape change here will break ingestion.
 *
 * ### Relationship to `TrackingPayloadBuilderTest`
 *
 * [TrackingPayloadBuilderTest] asserts the builder's GROUPING and
 * SEGMENTS-SNAPSHOT semantics from the VisitorEvent input side. THIS
 * file asserts the EVENT-SHAPE contract that flows THROUGH the builder —
 * `eventType: "bucketing"` / `"conversion"` tokens, the `data` sub-object
 * shape, the `goalData[]` sub-array for transaction-style conversions.
 * Together the two files cover the complete wire surface.
 *
 * ### Story-vs-implementation note
 *
 * Story 5.6's original AC text (drafted pre-Story-5.1) referenced
 * `eventType: "viewExp"` / `"hitGoal"` / `"tr"`. Story 5.1 corrected the
 * wire format to match the JS SDK's shipped contract — the tokens are
 * `"bucketing"` and `"conversion"`, and transactions fold INTO
 * `conversion` events via a `data.goalData[]` array (NOT separate `"tr"`
 * events). Assertions here encode the shipped contract, not the story
 * draft. See `TrackingPayloadBuilder.kt` KDoc and
 * `javascript-sdk/packages/api/src/api-manager.ts:208-252` for the
 * authoritative shape.
 *
 * ### Field reference — the full contract this file locks
 *
 * ```
 * {
 *   "accountId": "<string>",
 *   "projectId": "<string>",
 *   "enrichData": <bool>,          // true iff SDK has no loaded config
 *   "source": "<string>",          // absent when ConvertConfig.network.source is null
 *   "visitors": [
 *     {
 *       "visitorId": "<string>",
 *       "segments": { ... },       // absent when empty
 *       "events": [
 *         { "eventType": "bucketing",
 *           "data": { "experienceId": "<string>", "variationId": "<string>" } },
 *         { "eventType": "conversion",
 *           "data": { "goalId": "<string>" } },
 *         { "eventType": "conversion",
 *           "data": { "goalId": "<string>",
 *                     "goalData": [ { "key": "amount"|"productsCount"|"transactionId"|"customDimension1..5",
 *                                     "value": <JsonElement> } ] } }
 *       ]
 *     }
 *   ]
 * }
 * ```
 */
internal class TrackingPayloadTest {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = false
    }

    // region — fixtures

    /**
     * Story 5.1 sealed-hierarchy refactor: events are typed
     * [BucketingEvent] / [ConversionEvent] instances, not raw JsonObjects.
     * [TrackingPayloadBuilder] (Story 5.3) is responsible for emitting the
     * canonical wire shape — these fixtures provide the inputs; the
     * assertions below verify the output the builder produces.
     */
    private fun bucketingEvent(experienceId: String, variationId: String): BucketingEvent =
        BucketingEvent(experienceId = experienceId, variationId = variationId)

    /** Plain conversion (no goalData) — matches `ApiManager.enqueueConversionEvent` with goalData = null. */
    private fun conversionEvent(goalId: String): ConversionEvent =
        ConversionEvent(goalId = goalId)

    /**
     * Transaction-shaped conversion: revenue / productsCount / transactionId
     * fold into [ConversionEvent.goalData] — there is no separate
     * `TransactionEvent` type. Keys use the [GoalDataKey] enum which
     * serialises camelCase via `@SerialName`.
     */
    private fun transactionConversionEvent(
        goalId: String,
        amount: Double,
        productsCount: Int,
        transactionId: String,
    ): ConversionEvent = ConversionEvent(
        goalId = goalId,
        goalData = listOf(
            GoalData(key = GoalDataKey.AMOUNT, value = JsonPrimitive(amount)),
            GoalData(key = GoalDataKey.PRODUCTS_COUNT, value = JsonPrimitive(productsCount)),
            GoalData(key = GoalDataKey.TRANSACTION_ID, value = JsonPrimitive(transactionId)),
        ),
    )

    /**
     * Wraps a [TrackingEvent] in a [VisitorEvent] with the given visitorId
     * and an empty segments snapshot. Story 5.3's "Port Contract Amendment"
     * replaced the previous `PersistedEvent(timestampMs = ...)` shape with
     * [VisitorEvent] — timestamp metadata no longer travels on the wire.
     */
    private fun visitorEvent(
        visitorId: String,
        event: TrackingEvent,
    ): VisitorEvent = VisitorEvent(
        visitorId = visitorId,
        segments = null,
        event = event,
    )

    private fun config(): ConvertConfig = ConvertConfig(
        sdkKey = "s-key",
        data = ConfigResponseData(
            accountId = "acc-42",
            project = ConfigProject(id = "proj-7"),
        ),
        network = NetworkConfig(source = "android"),
    )

    // endregion

    /**
     * AC-2 — top-level schema assertion.
     *
     * The outbound JSON must have exactly the keys
     * `{accountId, projectId, enrichData, source, visitors}`. `visitors`
     * must be a JsonArray and every entry must carry
     * `{visitorId, events}` (segments is optional when empty).
     */
    @Test
    fun `AC-2 top-level payload has the expected keys and visitors are well-formed`() {
        val events = listOf(
            visitorEvent("v-1", bucketingEvent("exp-1", "var-a")),
        )

        val body = TrackingPayloadBuilder.build(events, config(), json)
        val payload = json.parseToJsonElement(body).jsonObject

        // Required top-level keys
        assertTrue(payload.containsKey("accountId"), "accountId must be present")
        assertTrue(payload.containsKey("projectId"), "projectId must be present")
        assertTrue(payload.containsKey("enrichData"), "enrichData must be present")
        assertTrue(payload.containsKey("source"), "source must be present when network.source is set")
        assertTrue(payload.containsKey("visitors"), "visitors must be present")

        // Primitive values
        assertEquals("acc-42", payload["accountId"]!!.jsonPrimitive.content)
        assertEquals("proj-7", payload["projectId"]!!.jsonPrimitive.content)
        assertEquals("android", payload["source"]!!.jsonPrimitive.content)
        assertFalse(
            payload["enrichData"]!!.jsonPrimitive.content.toBoolean(),
            "enrichData must be false when SDK has loaded config.data",
        )

        // visitors must be a JsonArray
        val visitors = payload["visitors"]!!.jsonArray
        assertEquals(1, visitors.size)

        // Each visitor entry must carry visitorId + events
        val v1 = visitors[0].jsonObject
        assertEquals("v-1", v1["visitorId"]!!.jsonPrimitive.content)
        assertNotNull(v1["events"], "visitor entry must carry events")
        assertTrue(v1["events"]!!.jsonArray.isNotEmpty())
    }

    /**
     * AC-3 — bucketing event shape.
     *
     * STORY-VS-IMPL CORRECTION: story draft said `eventType: "viewExp"` with
     * `data.timestamp` — shipped contract is `eventType: "bucketing"` with
     * `data: { experienceId, variationId }` and NO timestamp inside `data`.
     * Per Story 5.1's sealed-hierarchy refactor, [BucketingEvent] has no
     * timestamp field at all — there is nothing on the wire that carries
     * a per-event timestamp.
     */
    @Test
    fun `AC-3 bucketing event serializes as eventType bucketing with experienceId and variationId`() {
        val events = listOf(
            visitorEvent("v-1", bucketingEvent("exp-a", "var-b")),
        )

        val body = TrackingPayloadBuilder.build(events, config(), json)
        val payload = json.parseToJsonElement(body).jsonObject
        val event = payload["visitors"]!!.jsonArray[0].jsonObject["events"]!!.jsonArray[0].jsonObject

        assertEquals("bucketing", event["eventType"]!!.jsonPrimitive.content)

        val data = event["data"]!!.jsonObject
        assertEquals("exp-a", data["experienceId"]!!.jsonPrimitive.content)
        assertEquals("var-b", data["variationId"]!!.jsonPrimitive.content)

        // No extra keys inside data (in particular, no timestamp field)
        assertEquals(setOf("experienceId", "variationId"), data.keys)
    }

    /**
     * AC-4 — conversion event shape (plain, no goalData).
     *
     * STORY-VS-IMPL CORRECTION: story draft said `eventType: "hitGoal"` with
     * `data: { id: ... }` — shipped contract is `eventType: "conversion"`
     * with `data: { goalId: ... }`.
     */
    @Test
    fun `AC-4 conversion event serializes as eventType conversion with goalId`() {
        val events = listOf(
            visitorEvent("v-1", conversionEvent("goal-x")),
        )

        val body = TrackingPayloadBuilder.build(events, config(), json)
        val payload = json.parseToJsonElement(body).jsonObject
        val event = payload["visitors"]!!.jsonArray[0].jsonObject["events"]!!.jsonArray[0].jsonObject

        assertEquals("conversion", event["eventType"]!!.jsonPrimitive.content)

        val data = event["data"]!!.jsonObject
        assertEquals("goal-x", data["goalId"]!!.jsonPrimitive.content)
        // Plain conversion: exactly goalId, no goalData array
        assertEquals(setOf("goalId"), data.keys)
    }

    /**
     * AC-5 — transaction-shaped conversion (with goalData).
     *
     * STORY-VS-IMPL CORRECTION: story draft said a separate
     * `eventType: "tr"` event with `data: { amount, productsCount, transactionId }`.
     * Shipped contract folds transactions INTO the conversion event:
     * `eventType: "conversion"` with `data: { goalId, goalData: [{key, value}, ...] }`
     * where keys are camelCase tokens from [com.convert.sdk.core.model.GoalDataKey]
     * (`amount`, `productsCount`, `transactionId`, `customDimension1..5`).
     *
     * This shape matches the JS SDK's `api-manager.ts` and the
     * `GoalDataKey` enum's `@SerialName` camelCase bindings.
     */
    @Test
    fun `AC-5 transaction conversion event carries goalData array with camelCase keys`() {
        val events = listOf(
            visitorEvent(
                "v-1",
                transactionConversionEvent(
                    goalId = "purchase",
                    amount = 49.99,
                    productsCount = 2,
                    transactionId = "abc",
                ),
            ),
        )

        val body = TrackingPayloadBuilder.build(events, config(), json)
        val payload = json.parseToJsonElement(body).jsonObject
        val event = payload["visitors"]!!.jsonArray[0].jsonObject["events"]!!.jsonArray[0].jsonObject

        // Transaction is delivered under eventType = "conversion", not "tr"
        assertEquals("conversion", event["eventType"]!!.jsonPrimitive.content)

        val data = event["data"]!!.jsonObject
        assertEquals("purchase", data["goalId"]!!.jsonPrimitive.content)

        val goalData = data["goalData"]!!.jsonArray
        assertEquals(3, goalData.size, "goalData must have three entries")

        val byKey: Map<String, JsonObject> = goalData.associate {
            val entry = it.jsonObject
            entry["key"]!!.jsonPrimitive.content to entry
        }

        // Keys are camelCase tokens — NOT snake_case, NOT hitGoal/tr vocabulary
        assertTrue(byKey.containsKey("amount"), "goalData must contain 'amount' (camelCase)")
        assertTrue(byKey.containsKey("productsCount"), "goalData must contain 'productsCount' (camelCase)")
        assertTrue(byKey.containsKey("transactionId"), "goalData must contain 'transactionId' (camelCase)")

        // Values round-trip as primitives of the correct JSON type
        assertEquals(49.99, byKey["amount"]!!["value"]!!.jsonPrimitive.content.toDouble())
        assertEquals(2, byKey["productsCount"]!!["value"]!!.jsonPrimitive.content.toInt())
        assertEquals("abc", byKey["transactionId"]!!["value"]!!.jsonPrimitive.content)

        // Each goalData entry is the two-key {key, value} shape the backend expects
        goalData.forEach { entry ->
            assertEquals(
                setOf("key", "value"),
                entry.jsonObject.keys,
                "goalData entries must be exactly {key, value}",
            )
        }
    }

    /**
     * AC-6 — multiple visitors produce a grouped payload.
     *
     * Two visitors each with their own events. The outbound `visitors[]`
     * must have exactly one entry per visitorId, and each entry's `events[]`
     * must contain only that visitor's events.
     */
    @Test
    fun `AC-6 multi-visitor payload groups events by visitorId`() {
        val events = listOf(
            visitorEvent("v-1", bucketingEvent("exp-1", "var-a")),
            visitorEvent("v-2", bucketingEvent("exp-1", "var-b")),
            visitorEvent("v-2", conversionEvent("goal-z")),
            visitorEvent("v-1", conversionEvent("goal-y")),
        )

        val body = TrackingPayloadBuilder.build(events, config(), json)
        val payload = json.parseToJsonElement(body).jsonObject

        val visitors = payload["visitors"]!!.jsonArray
        assertEquals(2, visitors.size, "Must produce exactly one entry per visitor")

        val visitorsByIdentity: Map<String, JsonObject> = visitors.associate { vEntry ->
            val v = vEntry.jsonObject
            v["visitorId"]!!.jsonPrimitive.content to v
        }

        assertTrue(visitorsByIdentity.containsKey("v-1"))
        assertTrue(visitorsByIdentity.containsKey("v-2"))

        val v1Events = visitorsByIdentity["v-1"]!!["events"]!!.jsonArray
        val v2Events = visitorsByIdentity["v-2"]!!["events"]!!.jsonArray

        // v-1 has exactly its two events in enqueue order
        assertEquals(2, v1Events.size)
        assertEquals("bucketing", v1Events[0].jsonObject["eventType"]!!.jsonPrimitive.content)
        assertEquals(
            "exp-1",
            v1Events[0].jsonObject["data"]!!.jsonObject["experienceId"]!!.jsonPrimitive.content,
        )
        assertEquals(
            "var-a",
            v1Events[0].jsonObject["data"]!!.jsonObject["variationId"]!!.jsonPrimitive.content,
        )
        assertEquals("conversion", v1Events[1].jsonObject["eventType"]!!.jsonPrimitive.content)
        assertEquals(
            "goal-y",
            v1Events[1].jsonObject["data"]!!.jsonObject["goalId"]!!.jsonPrimitive.content,
        )

        // v-2 has exactly its two events in enqueue order
        assertEquals(2, v2Events.size)
        assertEquals("bucketing", v2Events[0].jsonObject["eventType"]!!.jsonPrimitive.content)
        assertEquals(
            "var-b",
            v2Events[0].jsonObject["data"]!!.jsonObject["variationId"]!!.jsonPrimitive.content,
        )
        assertEquals("conversion", v2Events[1].jsonObject["eventType"]!!.jsonPrimitive.content)
        assertEquals(
            "goal-z",
            v2Events[1].jsonObject["data"]!!.jsonObject["goalId"]!!.jsonPrimitive.content,
        )
    }
}
