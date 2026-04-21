/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.Feature
import com.convert.sdk.core.model.GoalData
import com.convert.sdk.core.model.Variation
import com.convert.sdk.core.model.generated.ConfigAudience
import com.convert.sdk.core.model.generated.ConfigExperience
import com.convert.sdk.core.model.generated.ConfigLocation
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.model.generated.ExperienceVariationConfig
import com.convert.sdk.core.model.generated.VariationStatuses
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
    internal val sdk: ConvertSDK? = null,
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
     * bucketed [Variation] — Story 3.2 AC-6 / AC-7 / AC-9 / AC-10.
     *
     * ### Algorithm
     *
     *  1. **Config-ready gate (AC-6 step 1).** If the SDK has no loaded
     *     configuration ([com.convert.sdk.core.data.DataManager.hasData]
     *     is `false`), return `null` after a DEBUG trace. This happens
     *     when the caller reaches the context before the initial config
     *     fetch completes — correct fallback is to act as if the
     *     experience is not running.
     *  2. **Experience lookup (AC-6 step 2, AC-9).** Find the
     *     [ConfigExperience] by [experienceKey]. Missing key (including
     *     empty-string key) → WARN, return `null`. Also covers the
     *     `experiences == null` and `experiences == []` cases.
     *  3. **Sticky bucketing (AC-6 step 3, AC-7).** If the visitor's
     *     [com.convert.sdk.core.model.StoreData.bucketing] map already
     *     holds an entry for [experienceKey], look up the matching
     *     variation by id within the experience's variations list, fire
     *     [SystemEvents.BUCKETING] when [enableTracking] is `true` (the
     *     internal observation event mirrors the new-bucketing path so
     *     debug observers see ALL bucketing activity — Story 3.2 AC-6
     *     step 3 with the F-035 remediation), and return the resolved
     *     variation. Sticky never enqueues a network bucketing event
     *     (matches Story 3.2 AC-7) and never fires when
     *     [enableTracking] is `false` (Story 3.2 AC-10 with the F-134
     *     remediation). This is the "returning visitor" path that
     *     NFR2's <5ms bound relies on (sticky lookup is O(1)).
     *  4. **Rule evaluation (AC-6 step 4).** Story 3.4 lands the real
     *     audience/location gate. For this story we assume the visitor
     *     passes all gates — the caller gets the bucketed variation.
     *  5. **Bucketing (AC-6 step 5).** Derive the `Map<variationId, Double>`
     *     wheel from `experience.variations` — filter out non-RUNNING
     *     variations (and zero-allocation variations — same semantics as
     *     the JS SDK's `data-manager.ts:620-637`); call
     *     [com.convert.sdk.core.bucketing.BucketingManager.getBucketForVisitor].
     *     A `null` allocation means the visitor was not bucketed (common
     *     when the sum of traffic allocations is below 100); log DEBUG
     *     and return `null`.
     *  6. **Persist (AC-6 step 6, AC-7).** Write the sticky decision via
     *     [com.convert.sdk.core.data.DataManager.updateBucketing].
     *     Atomicity is handled inside `updateBucketing` (Story 3.2 SDK-3).
     *  7. **Tracking enqueue (AC-6 step 6, AC-10).** When [enableTracking]
     *     is `true`, enqueue a bucketing tracking event via
     *     [com.convert.sdk.core.api.ApiManager.enqueueBucketingEvent].
     *     The real payload construction is Story 5.1 — the current stub
     *     is a no-op.
     *  8. **Internal event fire (AC-6 step 6, AC-10).** Fire
     *     [SystemEvents.BUCKETING] **only when [enableTracking] is
     *     `true`** — the internal bus is also gated by the per-call
     *     tracking flag so observers do not receive misleading
     *     bucketing signals during silent evaluation runs (Story 3.2
     *     AC-10 with the F-134 remediation: setting
     *     `enableTracking = false` suppresses both the network enqueue
     *     AND the internal event fire).
     *
     * ### Never throws
     *
     * Every failure mode returns `null` (or in the sticky/fresh-bucket
     * cases, the resolved variation). An unset [sdk] reference (pure-JVM
     * test construction path) also yields `null`.
     *
     * @param experienceKey the merchant-defined key of the experience.
     * @param enableTracking when `true`, the bucketing emits a
     *   `viewExp` tracking event. Pass `false` to inspect the result
     *   without reporting. Defaults to `true`.
     * @return the selected [Variation], or `null` when the visitor is not
     *   bucketed, the experience is unknown, or the SDK is not yet ready.
     */
    @JvmOverloads
    @Suppress("ReturnCount")
    public fun runExperience(
        experienceKey: String,
        enableTracking: Boolean = true,
    ): Variation? {
        lastExperienceKey = experienceKey
        lastRunWithTracking = enableTracking

        val sdk = this.sdk ?: return null

        // Step 1 — config-ready gate.
        if (!sdk.dataManager.hasData()) {
            sdk.logger.debug(
                message = "ConvertContext.runExperience: config not loaded, returning null",
                tag = TAG,
            )
            return null
        }

        // Step 2 — experience lookup.
        val experience = sdk.dataManager.data?.experiences
            ?.firstOrNull { it.key == experienceKey }
        if (experience == null) {
            sdk.logger.warn(
                message = "ConvertContext.runExperience: experience not found for key " +
                    "'$experienceKey'",
                tag = TAG,
            )
            return null
        }

        // Step 3 — sticky lookup. Fires SystemEvents.BUCKETING when
        // enableTracking is true (F-035: sticky parity with new-bucketing
        // path so observers see ALL bucketing activity); never enqueues a
        // network event on the sticky path (AC-7); never fires anything
        // when enableTracking is false (F-134).
        resolveSticky(sdk, experience, experienceKey, enableTracking)?.let { return it }

        // Step 4 — audience / location rule evaluation (Story 3.4).
        // Gate BEFORE bucketing: visitors failing either gate must not
        // affect traffic-allocation counts (JS SDK parity — filter then
        // bucket, never bucket and then discard).
        val data = sdk.dataManager.data
        if (data == null ||
            !passesAudienceGate(sdk, this, experience, experienceKey, data) ||
            !passesLocationGate(sdk, this, experience, experienceKey, data)
        ) {
            return null
        }

        // Steps 5-8 — bucketing, persist, enqueue, fire.
        return allocateAndRecord(sdk, experience, experienceKey, enableTracking)
    }

    /**
     * Tries to resolve the visitor's sticky bucket for [experienceKey]. When
     * the sticky id still matches a variation in the current config, fires
     * [SystemEvents.BUCKETING] (gated by [enableTracking]) and returns the
     * public [Variation]; otherwise returns `null` so the caller re-buckets.
     *
     * ### F-035 / F-134 remediation
     *
     * Story 3.2 AC-6 step 3 (post-remediation) requires the internal
     * BUCKETING event to fire on sticky recall **exactly as for the
     * new-bucketing path**, so debug observers see all bucketing
     * activity. AC-10 (post-remediation) requires that ALL bucketing
     * signals — network enqueue AND internal event — are suppressed
     * when [enableTracking] is `false`. The combination: fire on the
     * sticky path **iff** [enableTracking] is `true`.
     *
     * Note that the sticky path NEVER calls
     * [com.convert.sdk.core.api.ApiManager.enqueueBucketingEvent] —
     * sticky bucketing is by definition a returning visitor and the
     * outbound view-experience event was already enqueued on the
     * original bucketing call (Story 3.2 AC-7).
     *
     * Extracted from [runExperience] so the main method stays under
     * detekt's `LongMethod` ceiling.
     */
    @Suppress("ReturnCount")
    private fun resolveSticky(
        sdk: ConvertSDK,
        experience: ConfigExperience,
        experienceKey: String,
        enableTracking: Boolean,
    ): Variation? {
        val stored = sdk.dataManager.getStoreData(visitorId).bucketing?.get(experienceKey)
            ?: return null
        val variation = experience.variations?.firstOrNull { it.id == stored }
        if (variation == null) {
            sdk.logger.debug(
                message = "ConvertContext.runExperience: sticky variation '$stored' no longer in " +
                    "config for experience '$experienceKey'; re-bucketing",
                tag = TAG,
            )
            // Caller will re-bucket on the next step.
            return null
        }
        if (enableTracking) {
            sdk.eventManager.fire(
                event = SystemEvents.BUCKETING,
                data = mapOf(
                    "experienceKey" to experienceKey,
                    "variationKey" to variation.key,
                    "visitorId" to visitorId,
                ),
            )
        }
        // Sticky path does not re-compute the hash, so the bucketingAllocation
        // value is unknown — pass null to match the JS SDK's sticky branch
        // (`bucketingAllocation` is only populated on the fresh-bucket path).
        return toPublicVariation(experience, variation, bucketingAllocationValue = null)
    }

    /**
     * Runs the full bucketing + persist + enqueue + event-fire path once
     * the sticky lookup has missed. Returns the selected [Variation], or
     * `null` if the visitor is not bucketed into any variation.
     *
     * Split from [runExperience] to keep it inside detekt's line limit.
     */
    @Suppress("ReturnCount")
    private fun allocateAndRecord(
        sdk: ConvertSDK,
        experience: ConfigExperience,
        experienceKey: String,
        enableTracking: Boolean,
    ): Variation? {
        val buckets = buildBuckets(experience.variations)
        if (buckets.isEmpty()) {
            sdk.logger.debug(
                message = "ConvertContext.runExperience: experience '$experienceKey' has no " +
                    "eligible variations",
                tag = TAG,
            )
            return null
        }
        val allocation = sdk.bucketingManager.getBucketForVisitor(
            buckets = buckets,
            visitorId = visitorId,
            experienceId = experience.id.orEmpty(),
        ) ?: run {
            sdk.logger.debug(
                message = "ConvertContext.runExperience: visitor not bucketed into experience " +
                    "'$experienceKey'",
                tag = TAG,
            )
            return null
        }
        val variation = experience.variations?.firstOrNull { it.id == allocation.variationId }
            ?: return null

        // Persist sticky (atomic under DataManager's visitor lock).
        sdk.dataManager.updateBucketing(
            visitorId = visitorId,
            experienceKey = experienceKey,
            variationId = allocation.variationId,
        )

        // Outbound tracking AND internal event bus — both gated by the
        // per-call flag (F-134 remediation: AC-10 suppresses the internal
        // BUCKETING fire when enableTracking is false so observers do not
        // receive misleading bucketing signals during silent runs).
        // Story 5.4 also gates the network enqueue by the SDK-level
        // tracking toggle on ApiManager.
        if (enableTracking) {
            sdk.apiManager?.enqueueBucketingEvent(
                visitorId = visitorId,
                experienceId = experience.id.orEmpty(),
                variationId = allocation.variationId,
            )
            sdk.eventManager.fire(
                event = SystemEvents.BUCKETING,
                data = mapOf(
                    "experienceKey" to experienceKey,
                    "variationKey" to variation.key,
                    "visitorId" to visitorId,
                ),
            )
        }
        return toPublicVariation(
            experience = experience,
            variation = variation,
            bucketingAllocationValue = allocation.bucketingAllocation.toDouble(),
        )
    }

    /**
     * Builds the `variationId -> percentage` map consumed by
     * [com.convert.sdk.core.bucketing.BucketingManager.getBucketForVisitor].
     *
     * Matches the JS SDK's `data-manager.ts:620-637` filter chain:
     *  - Drop variations whose `status` is set AND not `RUNNING`
     *    (unset status defaults to "eligible", same as JS).
     *  - Drop zero-traffic variations (stopped variations in disguise).
     *  - Use the variation's `trafficAllocation` as-is. Although the
     *    OpenAPI schema describes the field as `0..10000`, the actual
     *    CDN-emitted values are percentages `0..100` — the JS SDK's
     *    `* 100` multiplier inside `selectBucket` assumes percentages,
     *    and real config fixtures (tests/test-config.json across the
     *    JS SDK) carry `50.0` for a 50% variation. We mirror that
     *    interpretation.
     *
     * Insertion order is preserved (the generated
     * [ExperienceVariationConfig] list is a plain [List], so iteration
     * is declaration order — which the bucketing engine relies on).
     */
    private fun buildBuckets(
        variations: List<ExperienceVariationConfig>?,
    ): Map<String, Double> =
        variations
            ?.asSequence()
            ?.filter { it.id != null }
            ?.filter { it.status == null || it.status == VariationStatuses.RUNNING }
            ?.map { it to (it.trafficAllocation?.toDouble() ?: DEFAULT_VARIATION_PCT) }
            ?.filter { (_, allocation) -> allocation > 0.0 }
            ?.associateByTo(
                destination = linkedMapOf(),
                keySelector = { (variation, _) -> variation.id!! },
                valueTransform = { (_, allocation) -> allocation },
            )
            ?: emptyMap()

    /**
     * Lifts a [ConfigExperience] + [ExperienceVariationConfig] pair into
     * the SDK's public [Variation] shape. Populates `experienceId` /
     * `experienceKey` / `experienceName` on the variation from the parent
     * experience so callers get a self-describing object without needing
     * to look up the experience separately.
     *
     * [bucketingAllocationValue] is the hash-pipeline value (range
     * `0..maxTraffic`) the JS SDK surfaces as `bucketingAllocation` on
     * the returned BucketedVariation — not the variation's configured
     * traffic percentage. `null` when the caller took the sticky fast
     * path (JS SDK matches this — sticky bucketing returns
     * `bucketingAllocation: undefined`).
     *
     * `changes` is intentionally not copied — Story 3.3 adds the full
     * change-list plumbing.
     */
    private fun toPublicVariation(
        experience: ConfigExperience,
        variation: ExperienceVariationConfig,
        bucketingAllocationValue: Double?,
    ): Variation = Variation(
        id = variation.id,
        key = variation.key,
        name = variation.name,
        experienceId = experience.id,
        experienceKey = experience.key,
        experienceName = experience.name,
        bucketingAllocation = bucketingAllocationValue,
        changes = null,
    )

    /**
     * Evaluates every experience in the currently-loaded config for this
     * visitor — Story 3.3 AC-1 through AC-7.
     *
     * ### Algorithm
     *
     *  1. **Config-ready gate (AC-1).** An unset [sdk] reference (test
     *     path) or [com.convert.sdk.core.data.DataManager.hasData] being
     *     `false` yields an empty list — matches the config-not-ready
     *     fallback used by [runExperience].
     *  2. **Iterate in declaration order (AC-1, Gotcha 2).** The
     *     `experiences` list is walked top-to-bottom. The resulting list
     *     preserves that ordering; consumers that need alphabetical or
     *     custom ordering sort themselves.
     *  3. **Delegate to [runExperience] (AC-2, AC-3, AC-4, AC-5).** Each
     *     element calls `runExperience(key, enableTracking)`, which
     *     handles sticky lookup, bucketing, persistence, tracking gate,
     *     and `SystemEvents.BUCKETING` fire. Null returns (non-allocated,
     *     unknown key, empty wheel) are dropped via [mapNotNull].
     *  4. **Exception isolation (AC-7 test 5).** Each per-experience
     *     call is wrapped in a `try/catch`: if bucketing for experience
     *     A throws, the loop logs, drops A, and continues with B. Today
     *     [runExperience] never throws (every failure path returns null),
     *     but the catch is defensive per the architecture's
     *     "never leak exceptions to the caller" rule.
     *
     * ### Per-call tracking
     *
     * `enableTracking` passes verbatim into each [runExperience] call.
     * When `false`, no experience in the batch enqueues an outbound
     * bucketing event — matches Story 3.2 AC-10 semantics per
     * experience, applied across the batch.
     *
     * ### Return contract (Gotcha 3)
     *
     * Always returns a [List] — never `null`. Empty when the visitor is
     * in no experiences (or config is not ready).
     *
     * @param enableTracking when `true`, each bucketing emits a
     *   `viewExp` tracking event. Pass `false` for silent evaluation.
     *   Defaults to `true`.
     * @return the list of bucketed [Variation]s in config-declaration
     *   order; empty when the visitor is not in any experience.
     */
    @JvmOverloads
    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    public fun runExperiences(enableTracking: Boolean = true): List<Variation> {
        lastRunWithTracking = enableTracking

        val sdk = this.sdk ?: return emptyList()
        if (!sdk.dataManager.hasData()) {
            sdk.logger.debug(
                message = "ConvertContext.runExperiences: config not loaded, returning empty",
                tag = TAG,
            )
            return emptyList()
        }
        val experiences = sdk.dataManager.data?.experiences ?: return emptyList()

        return experiences.mapNotNull { experience ->
            val key = experience.key ?: return@mapNotNull null
            try {
                runExperience(key, enableTracking)
            } catch (t: Throwable) {
                // Belt-and-suspenders: runExperience today never throws
                // (every failure returns null), but isolating each
                // iteration keeps one bad experience config from poisoning
                // the batch. Matches the architecture error-handling rule:
                // "All public methods: wrap in try/catch, log error, return
                // null or Unit." Detekt's TooGenericExceptionCaught is
                // suppressed here intentionally — the whole point of this
                // catch is to prevent any unexpected runtime failure from
                // breaking the batch, so narrowing to specific exceptions
                // would defeat the guarantee.
                sdk.logger.error(
                    message = "ConvertContext.runExperiences: experience '$key' threw " +
                        "during evaluation; skipping",
                    throwable = t,
                    tag = TAG,
                )
                null
            }
        }
    }

    /**
     * Evaluates a feature flag for this visitor — Story 4.1 AC-2.
     *
     * Delegates to [FeatureManager.evaluate], which resolves the feature
     * by walking the currently-loaded experiences in declaration order
     * and returning the first variation whose `fullStackFeature` change
     * references this feature. Each bucketing decision goes through
     * [runExperience], so sticky lookups, audience / location gating,
     * persistence, outbound-event enqueue, and
     * [com.convert.sdk.core.event.SystemEvents.BUCKETING] fire apply
     * exactly as they would for a direct `runExperience` call — there
     * is no separate "feature evaluated" event (AC-6).
     *
     * Returns:
     *  - `null` when [featureKey] is not declared or the SDK has no
     *    loaded config yet (AC-7).
     *  - A [Feature] with [com.convert.sdk.core.model.FeatureStatus.DISABLED]
     *    when the feature is declared but the visitor is not bucketed
     *    into any variation exposing it (AC-8).
     *  - A [Feature] with [com.convert.sdk.core.model.FeatureStatus.ENABLED]
     *    and its `variables` map populated when the visitor is bucketed
     *    into a variation carrying the feature.
     *
     * @param featureKey merchant-defined key of the feature.
     * @return the evaluated [Feature], or `null` when the feature is
     *   unknown or the SDK is not yet ready.
     */
    public fun runFeature(featureKey: String): Feature? {
        lastFeatureKey = featureKey
        val sdk = this.sdk ?: return null
        return sdk.featureManager.evaluate(
            context = this,
            featureKey = featureKey,
            enableTracking = true,
        )
    }

    /**
     * Evaluates every declared feature for this visitor — Story 4.1 AC-3.
     *
     * Equivalent to calling [runFeature] for every
     * [com.convert.sdk.core.model.generated.ConfigFeature] declared in
     * the loaded config, preserving declaration order. Returns an empty
     * list when the SDK has no loaded config yet.
     *
     * @return the list of evaluated [Feature]s; empty when none are
     *   configured or the config is not loaded.
     */
    public fun runFeatures(): List<Feature> {
        val sdk = this.sdk ?: return emptyList()
        return sdk.featureManager.evaluateAll(
            context = this,
            enableTracking = true,
        )
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
        /** Log tag for [runExperience] DEBUG/WARN emissions. */
        const val TAG: String = "ConvertContext"

        /**
         * Traffic percentage applied to a variation whose
         * `trafficAllocation` field is absent. Matches JS SDK
         * `data-manager.ts:635` (`variation?.traffic_allocation || 100.0`) —
         * when no allocation is specified, the variation gets 100%
         * (i.e. the whole wheel).
         */
        const val DEFAULT_VARIATION_PCT: Double = 100.0

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

/**
 * Tag used by the gate helpers below when emitting DEBUG messages.
 * Matches the `ConvertContext.TAG` constant so operators see a single,
 * consistent tag regardless of whether the DEBUG came from
 * [ConvertContext.runExperience] or one of these helpers.
 */
private const val GATE_TAG: String = "ConvertContext"

/**
 * Audience gate (Story 3.4 AC-5) — resolves each ID in
 * `experience.audiences` against `data.audiences`, evaluates each
 * resolved [ConfigAudience]'s rules against [context]'s
 * `currentAttributes`, returns `true` when ANY audience matches (JS SDK
 * parity — `data-manager.ts:1110-1143`'s `selectAudiences` uses OR
 * semantics across the audience list).
 *
 * Empty / null `experience.audiences` skips the gate (no-constraint
 * semantics). A referenced ID missing from the audiences lookup counts
 * as a failed audience (DEBUG-logged — config is malformed).
 *
 * Lives at file scope so [ConvertContext] stays under detekt's
 * `TooManyFunctions` threshold — same rationale as
 * [launchInitialDataSeed] in [ConvertSDK].
 */
private fun passesAudienceGate(
    sdk: ConvertSDK,
    context: ConvertContext,
    experience: ConfigExperience,
    experienceKey: String,
    data: ConfigResponseData,
): Boolean {
    val audienceIds = experience.audiences
    if (audienceIds.isNullOrEmpty()) return true
    val audiencesById = data.audiences
        ?.mapNotNull { audience -> audience.id?.let { id -> id to audience } }
        ?.toMap()
        .orEmpty()
    val anyMatch = audienceIds.any { audienceId ->
        val audience = audiencesById[audienceId]
        if (audience == null) {
            sdk.logger.debug(
                message = "ConvertContext.runExperience: audience '$audienceId' referenced by " +
                    "experience '$experienceKey' not found in config",
                tag = GATE_TAG,
            )
            false
        } else {
            sdk.ruleManager.evaluate(audience.rules, context.currentAttributes())
        }
    }
    if (!anyMatch) {
        sdk.logger.debug(
            message = "ConvertContext.runExperience: visitor not in audience for '$experienceKey'",
            tag = GATE_TAG,
        )
    }
    return anyMatch
}

/**
 * Location gate (Story 3.4 AC-6) — same shape as [passesAudienceGate]
 * but resolves [ConfigLocation] from `data.locations` and evaluates
 * against [context]'s `currentLocationProperties`. Additional special
 * case: non-empty `experience.locations` but no location properties on
 * the context → fail with DEBUG.
 */
@Suppress("ReturnCount")
private fun passesLocationGate(
    sdk: ConvertSDK,
    context: ConvertContext,
    experience: ConfigExperience,
    experienceKey: String,
    data: ConfigResponseData,
): Boolean {
    val locationIds = experience.locations
    if (locationIds.isNullOrEmpty()) return true
    val locationProps = context.currentLocationProperties()
    if (locationProps.isEmpty()) {
        sdk.logger.debug(
            message = "ConvertContext.runExperience: experience '$experienceKey' has " +
                "location rules but no location properties set on context",
            tag = GATE_TAG,
        )
        return false
    }
    val locationsById = data.locations
        ?.mapNotNull { location -> location.id?.let { id -> id to location } }
        ?.toMap()
        .orEmpty()
    val anyMatch = locationIds.any { locationId ->
        val location = locationsById[locationId]
        if (location == null) {
            sdk.logger.debug(
                message = "ConvertContext.runExperience: location '$locationId' referenced by " +
                    "experience '$experienceKey' not found in config",
                tag = GATE_TAG,
            )
            false
        } else {
            sdk.ruleManager.evaluate(location.rules, locationProps)
        }
    }
    if (!anyMatch) {
        sdk.logger.debug(
            message = "ConvertContext.runExperience: no location match for '$experienceKey'",
            tag = GATE_TAG,
        )
    }
    return anyMatch
}
