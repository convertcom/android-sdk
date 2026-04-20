/*
 * Convert Android SDK — core/api
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.api

import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.HttpClient
import com.convert.sdk.core.port.Logger
import kotlinx.serialization.json.Json

/**
 * Story 2.2 RED-phase stub — fails all tests by returning null without
 * issuing any HTTP call. Implementation lands in the GREEN phase.
 */
internal class ApiManager(
    private val httpClient: HttpClient,
    private val logger: Logger,
    private val config: ConvertConfig,
    private val json: Json,
) {
    @Suppress("UnusedPrivateMember")
    suspend fun fetchConfig(): ConfigResponseData? {
        // Intentionally unimplemented — RED phase.
        return null
    }
}
