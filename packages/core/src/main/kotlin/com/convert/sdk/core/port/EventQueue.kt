/*
 * Convert Android SDK — core/port
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.port

import com.convert.sdk.core.model.VisitorEvent

/**
 * Durable queue of [VisitorEvent]s waiting to be batched and shipped to
 * the Convert tracking API.
 *
 * All operations are `suspend` because the concrete adapter
 * (`FileEventQueue`, Story 5.2) performs file I/O which must be dispatched
 * on [kotlinx.coroutines.Dispatchers.IO]. Pure-JVM test adapters may back
 * this with an in-memory list and return immediately.
 *
 * **Port Contract Amendment (per Story 5.3 AC-2, spans Stories 1.2 + 5.2 + 5.3):**
 * the queue is typed in [VisitorEvent] (not [com.convert.sdk.core.model.TrackingEvent])
 * so per-event visitor identity and segment snapshots survive process death.
 */
public interface EventQueue {

    /**
     * Appends [events] to the tail of the queue, persisting them before
     * returning so that a process death does not lose them.
     *
     * @param events the events to enqueue; may be empty (no-op).
     */
    suspend fun persist(events: List<VisitorEvent>)

    /**
     * Reads the full queue contents without draining it.
     *
     * @return all events currently persisted, in enqueue order.
     */
    suspend fun read(): List<VisitorEvent>

    /**
     * Removes every event from the queue. Callers typically call this after
     * a successful batch upload.
     */
    suspend fun clear()

    /**
     * Returns the number of events currently persisted.
     *
     * @return queue size.
     */
    suspend fun size(): Int

    /**
     * Atomically reads all events from the queue **and** removes them in a
     * single critical section so no concurrent [persist] call can interleave
     * between the read and the deletion.
     *
     * Atomicity property: because read + delete happen under one lock that
     * [persist] also contends, no [persist] can interleave between the read
     * and the delete.  The returned set equals the removed set; a [persist]
     * arriving mid-drain blocks, then appends to the freshly-emptied queue.
     *
     * On corruption / absent / blank file: returns [emptyList] and deletes
     * the bad file — the same recovery branches as [read].  No exception
     * escapes this call.
     *
     * @return the list of events that were in the queue at the moment of the
     *   atomic claim, in enqueue order.  Empty when the queue was already
     *   empty or contained only corrupt data.
     */
    suspend fun drain(): List<VisitorEvent>
}
