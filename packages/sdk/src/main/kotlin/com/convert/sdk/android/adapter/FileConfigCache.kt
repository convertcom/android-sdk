/*
 * Convert Android SDK — sdk/adapter
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.content.Context
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.Logger

/**
 * Story 2.2 RED-phase stub — all operations no-op. The GREEN phase lands
 * the atomic-write / corruption-recovery logic.
 */
internal class FileConfigCache(
    @Suppress("UnusedPrivateProperty") private val context: Context,
    @Suppress("UnusedPrivateProperty") private val logger: Logger,
) {
    @Suppress("UnusedParameter")
    suspend fun write(config: ConfigResponseData) {
        // Intentionally unimplemented — RED phase.
    }

    suspend fun read(): ConfigResponseData? = null

    suspend fun delete() {
        // Intentionally unimplemented — RED phase.
    }
}
