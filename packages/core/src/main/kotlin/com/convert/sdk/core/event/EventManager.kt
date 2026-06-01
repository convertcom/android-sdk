/*
 * Convert Android SDK — core/event
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.event

import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-process pub/sub bus used by SDK internals to signal lifecycle events.
 *
 * ### Story 2.4 — full pub/sub surface
 *
 * This class now implements the complete Story 2.4 contract:
 *
 *  - [on] returns a [SubscriptionToken]; callers unsubscribe with
 *    [off] `(event, token)`. The token is the primary unsubscribe key.
 *  - [off] also accepts a callback reference for kotlin-consumer
 *    convenience — identity-based removal per Story 2.1 Gotcha 4.
 *  - [fire] snapshots the subscriber list under the lock, then
 *    dispatches each callback via `scope.launch { ... }` so the
 *    callback runs on a background dispatcher, never inline with the
 *    caller of `fire`. Exceptions thrown by callbacks are caught and
 *    logged; one misbehaving subscriber never stops the chain.
 *  - [READY] and [CONFIG_UPDATED] are **replayable**: the last payload
 *    fired for each is stored in [lastEventData]; a late subscriber
 *    registered for a replayable event with a prior fire is dispatched
 *    the stored payload on its next tick. All other events are
 *    one-shot.
 *
 * ### Thread safety
 *
 * The subscriber map is guarded by a [ReentrantLock]. `synchronized`
 * blocks are disallowed by architecture §Thread-Safety-Pattern; the
 * coroutine-aware [kotlinx.coroutines.sync.Mutex] would force `on` /
 * `fire` / `off` to be `suspend fun`, which breaks the synchronous
 * `ConvertSDK.onReady { ... }` path. `ReentrantLock` is the project's
 * compromise: non-suspending, fair, and reentrant (so a callback that
 * calls `on` / `off` from within `fire` does not self-deadlock).
 *
 * Callbacks are invoked **outside** the lock on the injected [scope].
 * Holding the lock during dispatch would deadlock if a subscriber
 * called back into the EventManager, and would risk starving other
 * threads while a slow callback ran. The implementation:
 *
 *   1. Snapshot the subscriber list under the lock.
 *   2. Record the fire payload into [lastEventData] for replayable
 *      events — still under the lock, so late subscribers racing the
 *      fire see a consistent "was fired / was not fired" view.
 *   3. Release the lock.
 *   4. For each snapshot entry, `scope.launch` a dispatch coroutine
 *      that catches and logs any exception.
 *
 * ### Dispatch scope
 *
 * Every dispatch — including the replay dispatch in [on] — runs on
 * [scope]. By default the scope is a module-private
 * `SupervisorJob() + Dispatchers.Default` so the existing no-arg
 * callers (DataManager, unit tests) keep working; the SDK Builder
 * injects the shared `sdk.scope` so production dispatch shares the
 * SDK's coroutine graph and survives the same cancellation semantics.
 *
 * If [scope] has been cancelled, `scope.launch` fails silently — the
 * launched coroutine never starts, the caller of [fire] is unaffected.
 * This matches the Story 2.4 contract: fire is always a safe call, no
 * matter what state the background dispatcher is in.
 *
 * ### Why `data: Map<String, Any?>` payloads are immutable by convention
 *
 * Callers build a fresh immutable map for each [fire]. The map
 * reference is captured by every dispatched coroutine — a mutation
 * after `fire` returns would be observed non-deterministically by
 * subscribers. Callers are expected to allocate once per fire; the
 * project-wide `mapOf(...)` pattern satisfies this automatically.
 *
 * @property logger used to log subscriber exceptions so that one
 *   misbehaving listener does not suppress delivery to the rest of
 *   the chain.
 * @property scope the [CoroutineScope] all callback dispatch runs on.
 *   Defaults to a module-private `SupervisorJob() + Dispatchers.Default`
 *   so no-arg callers (DataManager, legacy tests) keep working without
 *   explicitly plumbing a scope; the Builder injects the shared SDK
 *   scope in production.
 */
public class EventManager(
    private val logger: Logger = Logger.NoOp,
    private val scope: CoroutineScope = defaultScope(),
) {

    /**
     * A registered subscription — pairs the caller's callback with the
     * opaque [SubscriptionToken] returned to them.
     */
    private data class Subscription(
        val token: SubscriptionToken,
        val callback: (Map<String, Any?>) -> Unit,
    )

    /**
     * Subscriber map: event name → list of [Subscription]s in
     * registration order. Guarded by [lock].
     */
    private val subscribers: MutableMap<String, MutableList<Subscription>> = mutableMapOf()

    /**
     * Lock guarding [subscribers] and the replayable-event writes into
     * [lastEventData]. `ReentrantLock` so a subscriber that calls back
     * into `on` / `off` during `fire` dispatch does not self-deadlock
     * — even though `fire` releases the lock before dispatch, a
     * subscriber might still be invoked by a nested direct fire from
     * another path (defensive).
     */
    private val lock = ReentrantLock()

    /**
     * Last payload per replayable event name. Populated by [fire] when
     * the event is in [replayableEvents]; consulted by [on] to schedule
     * an immediate replay for late subscribers.
     *
     * `ConcurrentHashMap` gives us safe get/put outside the lock — we
     * write under the lock (so the "did fire happen yet" view is
     * consistent with the subscriber snapshot) but read outside so
     * `on`'s replay check doesn't serialise against `fire`.
     */
    private val lastEventData = ConcurrentHashMap<String, Map<String, Any?>>()

    /**
     * Events that are replayed to late subscribers (Story 2.4 AC-3).
     *
     * Only [SystemEvents.READY] and [SystemEvents.CONFIG_UPDATED] are
     * replayed. Every other event — including [SystemEvents.BUCKETING],
     * [SystemEvents.CONVERSION], [SystemEvents.API_QUEUE_RELEASED],
     * [SystemEvents.SEGMENTS] — represents a transient signal for
     * observation; replaying it weeks later (e.g. on app restart) would
     * confuse consumers. Arbitrary string events are also non-replayable.
     */
    private val replayableEvents: Set<String> = setOf(
        SystemEvents.READY,
        SystemEvents.CONFIG_UPDATED,
    )

    /**
     * Registers [callback] to be invoked on every subsequent [fire] of
     * [event] (and immediately, on the [scope] dispatcher, if [event]
     * is in [replayableEvents] and a prior [fire] recorded a payload).
     *
     * @param event the event name to listen for.
     * @param callback invoked with the event payload when the event fires.
     * @return a [SubscriptionToken] — pass to [off] to unsubscribe this
     *   exact registration without having to hold the [callback]
     *   reference.
     */
    public fun on(
        event: String,
        callback: (Map<String, Any?>) -> Unit,
    ): SubscriptionToken {
        val token = SubscriptionToken()
        // Add the subscription and capture the replay payload under the
        // same lock so a concurrent `fire` cannot land in the interval
        // between "subscription added" and "replay payload checked"
        // — otherwise a race on READY/CONFIG_UPDATED would deliver the
        // event twice (once from the concurrent fire's snapshot, once
        // from our post-lock replay read). See Story 2.4 AC-3 replay
        // semantics: "immediate callback with the last event data" —
        // not duplicated, and never mixed with a concurrent broadcast
        // of the SAME payload.
        val replay: Map<String, Any?>? = lock.withLock {
            subscribers.getOrPut(event) { mutableListOf() }.add(Subscription(token, callback))
            if (event in replayableEvents) lastEventData[event] else null
        }
        // Dispatch outside the lock on [scope] so the callback runs on
        // the same thread discipline as regular fires and never inline
        // with the caller of [on].
        if (replay != null) {
            dispatch(event, callback, replay)
        }
        return token
    }

    /**
     * Delivers [data] to every callback currently subscribed to [event],
     * in registration order, via [scope]'s dispatcher.
     *
     * Callbacks that throw are logged via [logger] and skipped — a single
     * misbehaving subscriber must not stop the rest of the chain from
     * receiving the event.
     *
     * If [event] is in [replayableEvents], [data] is also recorded in
     * [lastEventData] so late subscribers to this event can replay it.
     *
     * @param event the event name to deliver.
     * @param data event payload; defaults to an empty map. Callers must
     *   treat [data] as immutable after the call — it is captured by
     *   every dispatched coroutine.
     */
    public fun fire(event: String, data: Map<String, Any?> = emptyMap()) {
        val snapshot: List<Subscription> = lock.withLock {
            if (event in replayableEvents) {
                lastEventData[event] = data
            }
            subscribers[event]?.toList() ?: return
        }
        for (subscription in snapshot) {
            dispatch(event, subscription.callback, data)
        }
    }

    /**
     * Removes the subscription identified by [token] from [event]'s
     * subscriber list. A [token] that was never registered (or has
     * already been removed) is a no-op.
     *
     * @param event the event the callback was registered under.
     * @param token the token [on] returned for the registration to remove.
     */
    public fun off(event: String, token: SubscriptionToken) {
        lock.withLock {
            val list = subscribers[event] ?: return
            list.removeAll { it.token === token }
            if (list.isEmpty()) {
                subscribers.remove(event)
            }
        }
    }

    /**
     * Removes [callback] from [event]'s subscriber list by reference
     * equality — convenient for Kotlin consumers that still hold the
     * lambda they passed in. A callback that was never registered, or
     * one that has already been removed, is a no-op.
     *
     * Note: if the same callback reference was registered multiple
     * times, all matching registrations are removed — mirroring the
     * previous-Story 2.1 behaviour.
     *
     * @param event the event the callback was registered under.
     * @param callback the exact reference previously passed to [on].
     */
    public fun off(event: String, callback: (Map<String, Any?>) -> Unit) {
        lock.withLock {
            val list = subscribers[event] ?: return
            list.removeAll { it.callback === callback }
            if (list.isEmpty()) {
                subscribers.remove(event)
            }
        }
    }

    /**
     * Dispatches a single [callback] invocation on [scope] with
     * exception containment.
     *
     * Extracted so [on]'s replay path and [fire]'s broadcast path
     * share one code path — they have identical semantics (same scope,
     * same exception handling, same log tag).
     *
     * A cancelled [scope] silently drops the launch; the caller of
     * [fire] / [on] is unaffected. Story 2.4 AC-5: fire is always safe.
     */
    private fun dispatch(
        event: String,
        callback: (Map<String, Any?>) -> Unit,
        data: Map<String, Any?>,
    ) {
        scope.launch {
            try {
                callback(data)
            } catch (e: Exception) {
                // Architecture §Error Recovery: public methods catch,
                // log, and swallow. Subscriber callbacks are arbitrary
                // consumer code; one misbehaving listener must not
                // suppress delivery to others. Catching `Exception`
                // (not `Throwable`) still lets JVM `Error`s like
                // OutOfMemoryError propagate as intended. detekt's
                // TooGenericExceptionCaught blocklist is narrowed in
                // detekt.yml to allow this subscriber-isolation
                // pattern.
                logger.error(
                    message = "Subscriber for '$event' threw — continuing delivery",
                    throwable = e,
                    tag = "EventManager",
                )
            }
        }
    }

    private companion object {

        /**
         * Default dispatch scope used when no [CoroutineScope] is
         * supplied at construction. Production code (via the SDK
         * Builder) always injects the shared `sdk.scope`; this
         * fallback keeps DataManager's default constructor and the
         * pure-JVM smoke tests working without plumbing a scope
         * through every call site.
         */
        private fun defaultScope(): CoroutineScope =
            CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
