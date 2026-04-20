/*
 * Convert Android SDK — core
 * Copyright (c) 2026 Convert Insights, Inc
 * License Apache-2.0
 */
package com.convert.sdk.core.port

import com.convert.sdk.core.model.TrackingEvent

/**
 * Port abstraction for a persistent queue of [TrackingEvent]s awaiting upload.
 *
 * Operations are `suspend` because adapters typically perform file I/O on
 * `Dispatchers.IO`. The default adapter (a file-backed queue) lands in
 * Story 5.2.
 */
internal interface EventQueue {

    /**
     * Appends [events] to the persisted queue.
     */
    suspend fun persist(events: List<TrackingEvent>)

    /**
     * Returns every persisted event. The queue itself is not modified — call
     * [clear] after a successful upload to discard them.
     */
    suspend fun read(): List<TrackingEvent>

    /**
     * Removes every persisted event from the queue.
     */
    suspend fun clear()

    /**
     * Returns the number of events currently held by the queue.
     */
    suspend fun size(): Int
}
