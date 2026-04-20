/*
 * Convert Android SDK — core/data
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.data

import com.convert.sdk.core.event.EventManager
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.generated.ConfigResponseData

/**
 * Holds the SDK's currently-loaded configuration and notifies subscribers
 * when it changes.
 *
 * ### Story 2.1 scope
 *
 * Story 2.1 implements the minimum surface needed by the Builder and the
 * `ConvertSDK.onReady` path:
 *
 *  - [data] is the currently-loaded [ConfigResponseData], or `null` when
 *    neither direct-data mode nor a successful config fetch has seeded it.
 *  - [setData] stores the config and fires [SystemEvents.READY] via the
 *    shared [EventManager] so that subscribers (chiefly `ConvertSDK.onReady`)
 *    unblock.
 *  - [hasData] is the cheap `data != null` predicate used by `ConvertSDK`'s
 *    late-subscriber replay fallback (Story 2.4's proper replay lands later).
 *
 * Visitor-state logic (visitor id persistence, attribute store, segment
 * cache) is deferred to Story 3.1.
 *
 * ### Thread safety
 *
 * [setData] is called at most once per event-loop tick under Story 2.1's
 * current wiring (direct-data path in `Builder.build()` or the config fetch
 * completion in Story 2.2 — which itself is serialised by the SDK scope).
 * A `var data` without a lock is therefore safe today. If multiple writers
 * arrive in Story 2.3 (polling refresh) an `AtomicReference` swap will
 * suffice — flag as carryover.
 *
 * @property eventManager the shared event bus to publish READY events on.
 * @property environment the active environment (e.g. `"staging"`, `"prod"`)
 *   — included in the READY payload so subscribers can branch on it.
 */
public class DataManager(
    private val eventManager: EventManager,
    private val environment: String,
) {

    public var data: ConfigResponseData? = null
        private set

    /**
     * Stores the supplied [data] and fires [SystemEvents.READY] with a
     * payload of `{ "environment": environment }`.
     *
     * Calling [setData] a second time is legal — the stored config is
     * overwritten and READY fires again. Story 2.3's refresh loop will
     * switch this second fire to [SystemEvents.CONFIG_UPDATED] once
     * deferred-replay is in place.
     */
    public fun setData(data: ConfigResponseData) {
        this.data = data
        eventManager.fire(
            event = SystemEvents.READY,
            data = mapOf("environment" to environment),
        )
    }

    /**
     * Returns `true` once [setData] has seeded this manager with a
     * configuration, `false` on a fresh instance.
     */
    public fun hasData(): Boolean = data != null
}
