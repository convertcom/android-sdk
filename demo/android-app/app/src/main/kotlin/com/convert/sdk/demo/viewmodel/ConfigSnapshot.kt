/*
 * Convert Android SDK Demo App — ConfigSnapshot + ConfigSnapshotProvider (Story 7.6)
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

/**
 * Story 7.6 AC-5 — immutable snapshot of the SDK's current configuration
 * surfaced on the Config screen.
 *
 * The demo intentionally exposes a reduced view rather than the raw
 * [com.convert.sdk.core.model.generated.ConfigResponseData], because:
 *
 *  1. `ConfigResponseData` lives in the SDK's core module and carries
 *     dozens of fields that are not relevant to a developer exploring
 *     the SDK (audiences, locations, goals, integrations, …).
 *  2. `sdk.dataManager` is `internal` to the SDK module — the demo
 *     cannot read it directly. The Config screen instead consumes what
 *     the SDK's **public** API surfaces (tracking status) plus the
 *     per-visitor eligible sets returned by
 *     [com.convert.sdk.android.ConvertContext.runExperiences] /
 *     `runFeatures` (active keys).
 *
 * All fields are captured at snapshot time — a follow-up
 * `ready`/`config.updated` event builds a fresh snapshot, and the
 * ViewModel publishes it as a new [ConfigState.Loaded].
 *
 * @property sdkKey the configured SDK key, as supplied to
 *   `ConvertSDK.Builder.sdkKey(...)`. Only [maskedKey] is ever rendered
 *   on screen (AC-5 security rule); [sdkKey] is kept for tests and the
 *   merged contentDescription.
 * @property environment the SDK's active environment tag
 *   (`"staging"`, `"production"`, etc.). `null` when the SDK has not
 *   been configured with one — the READY event payload still carries
 *   the default, but the snapshot stays honest.
 * @property experienceKeys the `key` of every experience the current
 *   visitor is eligible to see. Counts + list map 1:1 to AC-5's
 *   "Active Experiences: count + list of experience keys" rule.
 * @property featureKeys the `key` of every feature the current visitor
 *   is eligible to evaluate. Same "count + list" rule as experiences.
 * @property trackingEnabled the SDK's `isTrackingEnabled()` state. `null`
 *   when the SDK has not wired its `ApiManager` yet — rendered as "—"
 *   on screen so the user isn't misled into thinking tracking is off.
 */
public data class ConfigSnapshot(
    val sdkKey: String,
    val environment: String?,
    val experienceKeys: List<String>,
    val featureKeys: List<String>,
    val trackingEnabled: Boolean?,
) {

    /**
     * AC-5 — masked SDK key for on-screen display. Rules:
     *
     *  - Keys longer than 8 characters render as `<first 8 chars>...`
     *    so a typical `"abcdef12-3456-7890-abcd-ef1234567890"` shows
     *    only `"abcdef12..."`.
     *  - Keys of length 8 or fewer (including the empty string) render
     *    verbatim. Masking a 3-character placeholder like `"x"` into
     *    `"..."` would be actively misleading — nothing has actually
     *    been masked.
     */
    val maskedKey: String
        get() = if (sdkKey.length > MASK_PREFIX_LENGTH) {
            sdkKey.take(MASK_PREFIX_LENGTH) + MASK_ELLIPSIS
        } else {
            sdkKey
        }

    private companion object {
        const val MASK_PREFIX_LENGTH: Int = 8
        const val MASK_ELLIPSIS: String = "..."
    }
}

/**
 * Story 7.6 DEMO-1 — narrow port the [SdkViewModel] calls whenever it
 * needs a fresh [ConfigSnapshot].
 *
 * Parallels [ExperienceRunner] / [FeatureRunner] / [ConversionTracker]
 * one-to-one: production wires a real SDK-backed implementation in
 * [com.convert.sdk.demo.DemoApplication]; tests provide a trivial fake
 * so the ViewModel can be exercised without building a real SDK (which
 * requires an Android [android.content.Context]).
 *
 * Kept as a `fun interface` so the factory site in [DemoApplication]
 * can lambda-literal it without an anonymous object.
 */
public fun interface ConfigSnapshotProvider {

    /**
     * Returns the current [ConfigSnapshot]. Called by [SdkViewModel]
     * on every observed `ready` / `config.updated` system event. The
     * implementation MUST be synchronous — the ViewModel runs it on
     * the SDK's event-dispatch thread, same as every other event
     * callback.
     *
     * An implementation that cannot produce a meaningful snapshot
     * (e.g. the SDK has not finished its first fetch) should return a
     * snapshot with empty lists and `trackingEnabled = null` rather
     * than throw — the ViewModel relies on a non-throwing contract
     * to keep the Config screen responsive.
     */
    public fun snapshot(): ConfigSnapshot
}

/**
 * Story 7.6 AC-5 / AC-6 / AC-7 — the Config screen's three rendering
 * branches, as a sealed hierarchy consumed by
 * [com.convert.sdk.demo.ui.screen.ConfigScreen] via
 * `viewModel.configState.collectAsState()`.
 *
 * The state machine is linear in the happy path:
 * `Loading → Loaded` (one transition, on the first `ready` event). It
 * can also branch to `Loading → Failed` when a WARN/ERROR log
 * accumulates **before** `ready` ever fires — this is the "no cached
 * config + no network" path the story's Gotcha 3 describes. Once
 * `Loaded` is reached, subsequent WARN/ERROR logs do NOT downgrade to
 * `Failed` — the app already has a usable config.
 */
public sealed class ConfigState {

    /**
     * Story 7.6 AC-6 — initial state. The ConfigScreen renders a
     * `CircularProgressIndicator` and the "Fetching configuration..."
     * caption until the SDK fires `ready` (or enough WARN/ERROR logs
     * accumulate to trip [Failed]).
     */
    public object Loading : ConfigState()

    /**
     * Story 7.6 AC-5 — the SDK has fired at least one `ready` event.
     * The UI renders the [ConfigInfoPanel] with these details.
     *
     * @property snapshot the captured configuration surface.
     * @property lastFetchedAt wall-clock millis when the ViewModel
     *   observed the `ready` / `config.updated` event that produced
     *   this snapshot. Rendered as `HH:mm:ss.SSS` in the panel's
     *   "Config Last Fetched" row.
     */
    public data class Loaded(
        val snapshot: ConfigSnapshot,
        val lastFetchedAt: Long,
    ) : ConfigState()

    /**
     * Story 7.6 AC-7 — ViewModel has not observed `ready` yet and at
     * least one WARN or ERROR log entry has accumulated. The UI
     * renders an error-styled ResultCard with [reason] + [hint].
     *
     * @property reason a human-readable failure description — usually
     *   the most recent WARN/ERROR log message.
     * @property hint actionable remediation text — always the same
     *   "Check network + SDK key" literal from the story.
     */
    public data class Failed(
        val reason: String,
        val hint: String,
    ) : ConfigState()
}
