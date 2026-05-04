/*
 * Convert Android SDK — sdk/worker
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.convert.sdk.android.adapter.FileEventQueue
import com.convert.sdk.core.api.TrackingPayloadBuilder
import com.convert.sdk.core.config.ApiConfig
import com.convert.sdk.core.config.ApiEndpoint
import com.convert.sdk.core.config.ConvertConfig
import com.convert.sdk.core.model.generated.ConfigProject
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * WorkManager [CoroutineWorker] that delivers any events persisted on
 * disk after the host process was killed.
 *
 * ### Role (Story 5.3 AC-1)
 *
 * When the host app moves to background, [com.convert.sdk.android.ConvertSDK]
 * attempts an immediate flush, then persists anything still in the
 * in-memory queue and enqueues this worker as a unique one-off job. If
 * the OS eventually kills the app, WorkManager still runs the worker
 * after its next network-available tick and ships whatever is on disk.
 *
 * ### Contract
 *
 *  1. Empty queue → [Result.success].
 *  2. Non-empty queue + HTTP 2xx → clear queue, [Result.success].
 *  3. HTTP non-2xx or [IOException] → [Result.retry]. WorkManager handles
 *     exponential backoff per AC-6 (`BackoffPolicy.EXPONENTIAL`, 30s
 *     initial delay). Queue is NOT cleared on retry.
 *  4. Missing required input data (sdkKey, projectId, accountId,
 *     trackEndpoint) → [Result.success]. This is a mis-configuration on
 *     the enqueue side; returning `retry` would cause an infinite loop.
 *     A warn is logged so the ops team can spot it.
 *
 * ### Why input data, not FileConfigCache (Story 5.3 Gotcha 2)
 *
 * The worker may run in a fresh process after app kill. It does NOT
 * have access to the in-memory [ConvertConfig] that the foreground
 * [com.convert.sdk.core.api.ApiManager] holds. Options were:
 *  (a) persist a config snapshot alongside events.json, or
 *  (b) pass `sdkKey` / `projectId` / `accountId` / `trackEndpoint` as
 *      [androidx.work.Data] on the WorkRequest.
 * Option (b) wins: WorkManager persists the Data bundle durably (same
 * sqlite store as the work row), the values are small strings, and the
 * enqueue site ([com.convert.sdk.android.ConvertSDK]'s onStop handler)
 * always has them in memory.
 *
 * The `trackEndpoint` value passed in MUST be the fully-substituted URL
 * (placeholder `[project_id]` already replaced). Keeping URL
 * interpolation at the enqueue site means the worker has zero
 * configuration logic of its own.
 *
 * ### Why this worker does NOT use [com.convert.sdk.core.api.ApiManager.flushNow]
 *
 * The foreground ApiManager owns an in-memory queue, a retry scheduler,
 * and a live event-bus wiring — none of which the worker has access to.
 * Reusing it would force ApiManager to hold onto a static reference,
 * creating a leak when the foreground SDK is garbage-collected. Rebuilding
 * the POST body from [TrackingPayloadBuilder] (introduced in Story 5.3
 * exactly to enable this path) keeps the two flush paths isolated.
 */
internal class EventFlushWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    private val fileEventQueue: FileEventQueue = FileEventQueue(
        context = appContext,
        logger = Logger.NoOp,
    )

    @Suppress("ReturnCount")
    override suspend fun doWork(): Result {
        val sdkKey = inputData.getString(KEY_SDK_KEY)
        val projectId = inputData.getString(KEY_PROJECT_ID)
        val accountId = inputData.getString(KEY_ACCOUNT_ID)
        val trackEndpoint = inputData.getString(KEY_TRACK_ENDPOINT)

        if (hasMissingInput(sdkKey, projectId, accountId, trackEndpoint)) {
            Log.w(
                TAG,
                "missing required input data (sdkKey/projectId/accountId/trackEndpoint); " +
                    "returning success to avoid infinite retry",
            )
            return Result.success()
        }
        // After the guard above, every input is non-null and non-empty;
        // extracting to local !! vals lets the rest of the method
        // reference them as non-null without fighting Kotlin's smart-cast
        // scope (the helper doesn't contract-promise the flow).
        val checkedSdkKey: String = sdkKey!!
        val checkedProjectId: String = projectId!!
        val checkedAccountId: String = accountId!!
        val checkedTrackEndpoint: String = trackEndpoint!!

        val events = fileEventQueue.read()
        if (events.isEmpty()) return Result.success()

        // Build a minimal ConvertConfig carrying only the fields
        // TrackingPayloadBuilder reads (accountId, project.id, source).
        // `source` is deliberately omitted — foreground flushes from the
        // host app's ApiManager drive it via NetworkConfig; the background
        // worker path has no equivalent (the foreground ApiManager's
        // enqueueAll/flush would have tagged the event with its source
        // already through the segments map, which this worker preserves).
        val workerConfig = ConvertConfig(
            sdkKey = checkedSdkKey,
            data = ConfigResponseData(
                accountId = checkedAccountId,
                project = ConfigProject(id = checkedProjectId),
            ),
            api = ApiConfig(endpoint = ApiEndpoint(track = checkedTrackEndpoint)),
        )

        val payload = TrackingPayloadBuilder.build(events, workerConfig, JSON)
        val url = buildTrackUrl(checkedTrackEndpoint, checkedSdkKey, checkedProjectId)

        val request = Request.Builder()
            .url(url)
            .post(payload.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        return withContext(Dispatchers.IO) {
            try {
                WorkerHttpClient.instance.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        fileEventQueue.clear()
                        Result.success()
                    } else {
                        Log.w(TAG, "track POST non-2xx: ${response.code}")
                        Result.retry()
                    }
                }
            } catch (t: IOException) {
                Log.w(TAG, "track POST failed (${t::class.simpleName}: ${t.message})")
                Result.retry()
            }
        }
    }

    private fun hasMissingInput(
        sdkKey: String?,
        projectId: String?,
        accountId: String?,
        trackEndpoint: String?,
    ): Boolean = sdkKey.isNullOrEmpty() ||
        projectId.isNullOrEmpty() ||
        accountId.isNullOrEmpty() ||
        trackEndpoint.isNullOrEmpty()

    /**
     * Interpolates `[project_id]` in [trackEndpoint] if the placeholder
     * is still present (defensive — the enqueue site should have done
     * this already), then appends `track/{sdkKey}`.
     */
    private fun buildTrackUrl(trackEndpoint: String, sdkKey: String, projectId: String): String {
        val withProject = trackEndpoint.replace(TEMPLATE_PROJECT_ID, projectId)
        val normalised = withProject.trimEnd('/')
        return "$normalised/track/$sdkKey"
    }

    internal companion object {
        private const val TAG: String = "EventFlushWorker"

        /**
         * Unique work name used by [com.convert.sdk.android.ConvertSDK] when
         * enqueuing this worker via `enqueueUniqueWork`.
         *
         * **This is a stable SDK constant — once released it must not be changed.**
         * Changing it leaves orphaned workers in the host app's WorkManager
         * store that will never be cancelled by [ExistingWorkPolicy.REPLACE].
         *
         * `ExistingWorkPolicy.REPLACE` ensures the latest event snapshot is
         * always flushed (last-enqueue-wins): rapid foreground/background
         * transitions collapse into a single pending worker rather than
         * accumulating. [Source: WorkManager ExistingWorkPolicy —
         * https://developer.android.com/reference/androidx/work/ExistingWorkPolicy]
         *
         * Addresses F-126: rationale and AndroidX citation for the unique
         * work name and REPLACE policy choice.
         */
        internal const val UNIQUE_WORK_NAME: String = "convert-event-flush"

        /** Input-data key carrying the host app's SDK key. */
        internal const val KEY_SDK_KEY: String = "convert.sdkKey"

        /** Input-data key carrying the merchant project id. */
        internal const val KEY_PROJECT_ID: String = "convert.projectId"

        /** Input-data key carrying the merchant account id. */
        internal const val KEY_ACCOUNT_ID: String = "convert.accountId"

        /**
         * Input-data key carrying the fully-substituted track endpoint
         * URL (placeholder `[project_id]` already replaced).
         */
        internal const val KEY_TRACK_ENDPOINT: String = "convert.trackEndpoint"

        private const val TEMPLATE_PROJECT_ID: String = "[project_id]"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * Minimal [Json] instance used only to encode the payload —
         * matches the foreground ApiManager's config (`ignoreUnknownKeys`,
         * `explicitNulls = false`, `encodeDefaults = false`).
         */
        private val JSON: Json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = false
        }
    }
}
