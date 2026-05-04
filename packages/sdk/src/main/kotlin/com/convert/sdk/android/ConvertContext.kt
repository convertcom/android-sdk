/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.Variation
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Per-visitor public context.
 *
 * The SDK produces one [ConvertContext] per visitor via
 * [ConvertSDK.createContext]; the constructor is `internal` so that
 * consumer code cannot build one manually. Every bucketing, feature, and
 * tracking call routes through an instance of this class, which therefore
 * also exposes mutators for the visitor attributes, segments, and
 * location properties that the rule engine inspects at evaluation time.
 *
 * ### Story 3.1 — real setter bodies
 *
 *  - All four setters (`setAttributes`, `setLocationProperties`,
 *    `setDefaultSegments`, `setCustomSegments`) now have real bodies
 *    implementing **replace-not-merge** semantics. Two successive calls
 *    replace the previous map — consumers wanting a merge must merge
 *    themselves via `ctx.setAttributes(oldAttrs + newAttrs)`.
 *  - Backing fields are `@Volatile`: single-reference swaps of immutable
 *    `Map` instances are visible to other threads without needing a
 *    `Mutex`. Readers see a stable snapshot; writers never race because
 *    each setter does a single reference write (no read-modify-write).
 *  - Internal accessors (`currentAttributes()`, `currentLocationProperties()`,
 *    `currentDefaultSegments()`, `currentCustomSegments()`) coerce the
 *    `Map<String, Any?>` stored from the public surface into
 *    `Map<String, JsonElement>` for the rule engine (Story 3.4) and
 *    tracking payload builder (Story 5.1). String / Number / Boolean /
 *    null map to their `JsonPrimitive` / `JsonNull` equivalents; existing
 *    `JsonElement` values pass through verbatim; any other type falls
 *    back to its `toString()` representation (Gotcha 7).
 *
 * The non-setter methods (`runExperience`, `runExperiences`, `runFeature`,
 * `runFeatures`, `trackConversion`) remain skeletons with `TODO(Story ...)`
 * tags — the real bodies land in Stories 3.2 / 3.3 / 4.1 / 4.2.
 *
 * ### Privacy
 *
 * No PII (NFR8). `visitorId` is a UUID v4 (auto-generated via
 * [java.util.UUID.randomUUID] when [ConvertSDK.createContext] is called
 * without args) or a caller-supplied opaque identifier — never a name,
 * email, or other personal datum. The `randomUUID()` method is
 * specified to return a version-4 (random) UUID — see
 * [Java SE 11 — `java.util.UUID.randomUUID`](https://docs.oracle.com/en/java/docs/api/java.base/java/util/UUID.html#randomUUID())
 * — which is what backs the no-correlation guarantee.
 *
 * @property visitorId stable visitor identifier supplied at construction
 *   time by [ConvertSDK.createContext].
 */
public class ConvertContext internal constructor(
    public val visitorId: String,
) {

    /**
     * Replace-not-merge attribute store. `@Volatile` ensures the write
     * in [setAttributes] is visible to concurrent readers calling
     * [currentAttributes] without a full mutex — setters do a single
     * reference swap of an immutable Map, so volatile visibility is
     * sufficient (Gotcha 6).
     */
    @Volatile private var attributes: Map<String, Any?>? = null

    @Volatile private var locationProperties: Map<String, Any?>? = null

    @Volatile private var defaultSegments: Map<String, String>? = null

    @Volatile private var customSegments: Map<String, Any?>? = null

    /**
     * Evaluates a single experience for this visitor and returns the
     * bucketed [Variation].
     *
     * @param experienceKey the merchant-defined key of the experience.
     * @param enableTracking when `true`, the bucketing emits a
     *   `viewExp` tracking event. Pass `false` to inspect the result
     *   without reporting. Defaults to `true`.
     * @return the selected [Variation], or `null` when the visitor is not
     *   bucketed into any variation.
     */
    @JvmOverloads
    public fun runExperience(
        experienceKey: String,
        enableTracking: Boolean = true,
    ): Variation? {
        // TODO(Story 3.2/3.3): wire to BucketingManager and EventManager
        lastExperienceKey = experienceKey
        lastRunWithTracking = enableTracking
        return null
    }

    /**
     * Evaluates every experience for this visitor.
     *
     * @param enableTracking when `true`, each bucketing emits a
     *   `viewExp` tracking event. Pass `false` for silent evaluation.
     *   Defaults to `true`.
     * @return the list of bucketed [Variation]s; empty when the visitor
     *   is not in any experience.
     */
    @JvmOverloads
    public fun runExperiences(enableTracking: Boolean = true): List<Variation> {
        // TODO(Story 3.3): wire to BucketingManager batch evaluation
        lastRunWithTracking = enableTracking
        return emptyList()
    }

    /**
     * Evaluates a feature flag for this visitor.
     *
     * @param featureKey merchant-defined key of the feature.
     * @return the evaluated [Feature], or `null` when the feature is
     *   unknown or the visitor is not eligible.
     */
    public fun runFeature(featureKey: String): Feature? {
        // TODO(Story 4.1): wire to FeatureManager
        lastFeatureKey = featureKey
        return null
    }

    /**
     * Evaluates every feature for this visitor.
     *
     * @return the list of evaluated [Feature]s; empty when none are
     *   configured for this visitor.
     */
    public fun runFeatures(): List<Feature> {
        // TODO(Story 4.1): wire to FeatureManager batch evaluation
        return emptyList()
    }

    /**
     * Records a conversion against a goal.
     *
     * @param goalKey merchant-defined key of the goal to track.
     * @param goalData optional goal-data payload (amounts, transaction
     *   ids, custom dimensions). `null` omits the payload entirely.
     */
    @JvmOverloads
    public fun trackConversion(
        goalKey: String,
        goalData: List<GoalData>? = null,
    ) {
        // TODO(Story 4.2): wire to EventManager / EventQueue
        lastConversionGoalKey = goalKey
        lastConversionGoalData = goalData
    }

    /**
     * Replaces (does not merge) the default-segment map used by rule
     * evaluation. Successive calls replace the previous value:
     *
     *     ctx.setDefaultSegments(mapOf("plan" to "free"))
     *     ctx.setDefaultSegments(mapOf("tier" to "gold"))
     *     // currentDefaultSegments() == {"tier": "gold"}  // "plan" is gone
     *
     * Callers wanting a merge must merge themselves:
     * `ctx.setDefaultSegments(old + new)`.
     *
     * @param segments default segment values keyed by segment name.
     * @return this context for fluent chaining.
     */
    public fun setDefaultSegments(segments: Map<String, String>): ConvertContext {
        defaultSegments = segments
        return this
    }

    /**
     * Replaces (does not merge) the custom-segment map used by rule
     * evaluation. Same replace-semantics as [setDefaultSegments].
     *
     * @param customSegments merchant-supplied segment values.
     * @return this context for fluent chaining.
     */
    public fun setCustomSegments(customSegments: Map<String, Any?>): ConvertContext {
        this.customSegments = customSegments
        return this
    }

    /**
     * Replaces (does not merge) this context's attribute map. Successive
     * calls replace the previous value:
     *
     *     ctx.setAttributes(mapOf("plan" to "premium"))
     *     ctx.setAttributes(mapOf("tier" to "gold"))
     *     // currentAttributes() == {"tier": JsonPrimitive("gold")}  // "plan" is gone
     *
     * @param attributes attributes associated with this visitor.
     * @return this context for fluent chaining.
     */
    public fun setAttributes(attributes: Map<String, Any?>): ConvertContext {
        this.attributes = attributes
        return this
    }

    /**
     * Replaces (does not merge) this context's location-properties map
     * used by location-rule evaluation. Same replace-semantics as
     * [setAttributes].
     *
     * @param properties location-evaluation inputs keyed by property name.
     * @return this context for fluent chaining.
     */
    public fun setLocationProperties(properties: Map<String, Any?>): ConvertContext {
        locationProperties = properties
        return this
    }

    /**
     * Returns the current attributes coerced to `Map<String, JsonElement>`
     * for the rule engine / tracking payload. Returns `emptyMap()` when
     * no attributes have been set.
     *
     * Consumer-supplied values are coerced as follows:
     *
     *  - `null` → [JsonNull]
     *  - `String` / `Number` / `Boolean` → matching [JsonPrimitive]
     *  - Existing [JsonElement] → passed through verbatim
     *  - Any other object → `JsonPrimitive(value.toString())` (Gotcha 7)
     *
     * Internal to the SDK; consumers only see the `Map<String, Any?>`
     * they passed in.
     */
    internal fun currentAttributes(): Map<String, JsonElement> =
        toJsonElementMap(attributes)

    /**
     * Returns the current location properties coerced to
     * `Map<String, JsonElement>`. Same coercion rules as
     * [currentAttributes].
     */
    internal fun currentLocationProperties(): Map<String, JsonElement> =
        toJsonElementMap(locationProperties)

    /**
     * Returns the current default-segments map, or an empty map when
     * unset. No coercion is needed — default segments are already
     * `Map<String, String>`.
     */
    internal fun currentDefaultSegments(): Map<String, String> =
        defaultSegments ?: emptyMap()

    /**
     * Returns the current custom-segments map coerced to
     * `Map<String, JsonElement>`. Same coercion rules as
     * [currentAttributes].
     */
    internal fun currentCustomSegments(): Map<String, JsonElement> =
        toJsonElementMap(customSegments)

    // --- transient state so the non-setter stubs materially read the private fields ----
    // Once the real managers land in Stories 3.x / 4.x these go away; they exist
    // today so detekt's UnusedPrivateMember rule does not flag the stub methods.

    private var lastExperienceKey: String? = null
    private var lastFeatureKey: String? = null
    private var lastRunWithTracking: Boolean = true
    private var lastConversionGoalKey: String? = null
    private var lastConversionGoalData: List<GoalData>? = null

    /**
     * Internal accessor used by tests and by later stories when wiring
     * the real managers. Not part of the public surface — the
     * accompanying fields are implementation detail and will be removed
     * once the managers take over.
     */
    internal fun debugSnapshot(): Map<String, Any?> = mapOf(
        "attributes" to attributes,
        "locationProperties" to locationProperties,
        "defaultSegments" to defaultSegments,
        "customSegments" to customSegments,
        "lastExperienceKey" to lastExperienceKey,
        "lastFeatureKey" to lastFeatureKey,
        "lastRunWithTracking" to lastRunWithTracking,
        "lastConversionGoalKey" to lastConversionGoalKey,
        "lastConversionGoalData" to lastConversionGoalData,
    )

    private companion object {
        /**
         * Lifts a `Map<String, Any?>?` into `Map<String, JsonElement>`,
         * applying the Story 3.1 Gotcha 7 coercion table. Returns the
         * empty map when [source] is `null`.
         */
        fun toJsonElementMap(source: Map<String, Any?>?): Map<String, JsonElement> =
            source?.mapValues { (_, value) -> toJsonElement(value) } ?: emptyMap()

        /**
         * Single-value coercion helper — see [toJsonElementMap] for the
         * public contract. Kept separate so detekt's `LongMethod` rule
         * does not light up on the `when` block.
         */
        fun toJsonElement(value: Any?): JsonElement = when (value) {
            null -> JsonNull
            is JsonElement -> value
            is String -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            else -> JsonPrimitive(value.toString())
        }
    }
}
