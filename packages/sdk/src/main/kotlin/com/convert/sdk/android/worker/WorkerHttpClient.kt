/*
 * Convert Android SDK — sdk/worker
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.worker

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Lazy-initialised [OkHttpClient] singleton used by [EventFlushWorker].
 *
 * ### Why a singleton (Story 5.3 Gotcha 5)
 *
 * Creating a fresh [OkHttpClient] per worker invocation is wasteful —
 * each instance spins up a connection pool, a dispatcher, and a
 * background cleanup thread. The worker may fire multiple times in a
 * short window (rapid background/foreground transitions), and
 * WorkManager may invoke `doWork()` repeatedly on retry. Keeping one
 * client alive for the worker process's lifetime amortises that cost.
 *
 * ### Lifecycle
 *
 * [instance] is built on first access. WorkManager runs the worker in
 * the app process; when that process is eventually killed the singleton
 * goes with it. No explicit shutdown is needed — OkHttp's default
 * connection pool idles connections out.
 *
 * ### Timeouts
 *
 * Slightly more generous than the foreground client's defaults because
 * the worker runs asynchronously and there is no user waiting on the
 * response: 30s connect / 30s read. Constraints on the work request
 * (NetworkType.CONNECTED, Story 5.3 AC-5) guarantee the worker only
 * runs when a network is available, so the timeouts should rarely be
 * hit in practice.
 */
internal object WorkerHttpClient {

    private val lazyClient: Lazy<OkHttpClient> = lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    internal val instance: OkHttpClient get() = lazyClient.value

    private const val CONNECT_TIMEOUT_SECONDS: Long = 30L
    private const val READ_TIMEOUT_SECONDS: Long = 30L
}
