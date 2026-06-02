/*
 * Convert Android SDK — core/event
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.event

import com.convert.sdk.core.port.Logger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * In-process pub/sub bus used by SDK internals to signal lifecycle events.
 *
 * ### Story 2.1 stub
 *
 * The Story 2.1 surface is deliberately minimal:
 *
 *  - [on] / [fire] / [off] cover the request/response path for [SystemEvents.READY]
 *    from [com.convert.sdk.core.data.DataManager.setData] to `ConvertSDK.onReady`.
 *  - [off] matches subscribers by **identity** (reference equality) of the
 *    callback — two lambdas that happen to have the same body but different
 *    references are treated as distinct. Story 2.1 Gotcha 4 + AC-9 design
 *    decision: the caller must retain the exact reference it passed to [on]
 *    if it wants to unsubscribe.
 *  - Deferred-replay semantics for READY / CONFIG_UPDATED are NOT implemented
 *    here — a consumer that subscribes after [fire] has already delivered
 *    the event does not receive a replay. That feature lands in Story 2.4.
 *    Story 2.1's direct-data onReady path works around this by delaying the
 *    `dataManager.setData(...)` call to the next coroutine tick so that
 *    consumer code has a chance to register its callback first.
 *
 * ### Thread safety
 *
 * The subscriber map is guarded by a [ReentrantLock]. `synchronized` blocks
 * are disallowed by architecture §Thread-Safety-Pattern; the coroutine-aware
 * [kotlinx.coroutines.sync.Mutex] would require `on`/`fire`/`off` to be
 * `suspend fun`, which breaks the synchronous `ConvertSDK.onReady { ... }`
 * path. `ReentrantLock` is the project's compromise: non-suspending, fair,
 * and reentrant (so a callback that calls `on`/`off` from within `fire`
 * does not self-deadlock).
 *
 * Callbacks are invoked **outside** the lock — otherwise a subscriber that
 * calls back into the EventManager would either deadlock (on a non-reentrant
 * lock) or race with itself (on a reentrant lock that the original caller
 * still holds). The implementation snapshots the subscriber list under the
 * lock, then iterates and invokes outside.
 *
 * @property logger used to log subscriber exceptions so that one misbehaving
 *   listener does not suppress delivery to the rest of the chain.
 */
public class EventManager(
    private val logger: Logger = Logger.NoOp,
) {
    // Shared mutable state: the subscriber map. Guarded by [lock].
    private val subscribers: MutableMap<String, MutableList<(Map<String, Any?>) -> Unit>> =
        mutableMapOf()
    private val lock = ReentrantLock()

    /**
     * Registers [callback] to be invoked on every subsequent [fire] of [event].
     *
     * Story 2.1 does not retroactively replay events to late subscribers.
     *
     * @param event the event name to listen for.
     * @param callback invoked with the event payload when the event fires.
     */
    public fun on(event: String, callback: (Map<String, Any?>) -> Unit) {
        lock.withLock {
            subscribers.getOrPut(event) { mutableListOf() }.add(callback)
        }
    }

    /**
     * Delivers [data] to every callback currently subscribed to [event], in
     * registration order.
     *
     * Callbacks that throw are logged via [logger] and skipped — a single
     * misbehaving subscriber must not stop the rest of the chain from
     * receiving the event.
     *
     * @param event the event name to deliver.
     * @param data event payload; defaults to an empty map.
     */
    public fun fire(event: String, data: Map<String, Any?> = emptyMap()) {
        // Snapshot under lock — invoke outside. This protects against
        // ConcurrentModificationException if a subscriber calls on()/off()
        // during delivery, and against re-entrant deadlocks.
        val snapshot: List<(Map<String, Any?>) -> Unit> = lock.withLock {
            subscribers[event]?.toList() ?: return
        }
        for (callback in snapshot) {
            try {
                callback(data)
            } catch (e: Exception) {
                // Architecture §Error Recovery mandates: "All public methods:
                // wrap in try/catch, log error, return null or Unit — never
                // throw." Subscriber callbacks are consumer-supplied and
                // arbitrary; one misbehaving listener must not suppress
                // delivery to the rest of the chain. Catching `Exception`
                // (not `Throwable`) still lets JVM Errors like
                // OutOfMemoryError propagate as intended. detekt's
                // TooGenericExceptionCaught blocklist is narrowed in
                // detekt.yml to allow this subscriber-isolation pattern.
                logger.error(
                    message = "Subscriber for '$event' threw — continuing delivery",
                    throwable = e,
                    tag = "EventManager",
                )
            }
        }
    }

    /**
     * Removes [callback] from [event]'s subscriber list by reference equality.
     *
     * A callback that was never registered, or one that has already been
     * removed, is a no-op.
     *
     * @param event the event the callback was registered under.
     * @param callback the exact reference previously passed to [on].
     */
    public fun off(event: String, callback: (Map<String, Any?>) -> Unit) {
        lock.withLock {
            val list = subscribers[event] ?: return
            // Identity-based removal: we cannot use removeIf { it == callback }
            // because Kotlin function references that share a body but differ
            // in capture state may compare equal. Use removeAll with an
            // identity predicate to enforce "same reference only".
            list.removeAll { it === callback }
            if (list.isEmpty()) {
                subscribers.remove(event)
            }
        }
    }
}
