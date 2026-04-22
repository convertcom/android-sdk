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
}
