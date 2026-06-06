/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

/**
 * The User-Agent the SDK announces on every outbound request.
 *
 * The Convert metrics endpoint runs a User-Agent bot filter on every
 * incoming `/v1/track` request before any tracking work happens. OkHttp's
 * default `okhttp/<version>` User-Agent is classified as a bot by the
 * server's `isbot` dependency, so the server replies `200 OK` with
 * `{code: BOT}` and silently discards the events while the SDK believes
 * delivery succeeded.
 *
 * The metrics-endpoint gate (`bots.js` `isConvertAgentUA`) whitelists any
 * request whose `User-Agent` header contains `ConvertAgent/` — the trailing
 * slash is required and a version token is expected. Announcing
 * `ConvertAgent/1.0` therefore exempts every SDK request from the bot
 * filter.
 *
 * The name and value match the PHP SDK (`ApiManager::CONVERT_AGENT_USER_AGENT`)
 * and the JS SDK (`CONVERT_AGENT_USER_AGENT` in `packages/utils/src/http-client.ts`)
 * exactly, keeping the announcement consistent across SDKs.
 *
 * `internal` is sufficient: both call sites (the foreground
 * [com.convert.sdk.android.adapter.OkHttpClientAdapter] and the background
 * [com.convert.sdk.android.worker.EventFlushWorker]) live in the `sdk`
 * module.
 */
internal const val CONVERT_AGENT_USER_AGENT: String = "ConvertAgent/1.0"
