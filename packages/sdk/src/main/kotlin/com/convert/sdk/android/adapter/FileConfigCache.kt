/*
 * Convert Android SDK — sdk/adapter
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android.adapter

import android.content.Context
import com.convert.sdk.core.model.generated.ConfigResponseData
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * On-disk cache of the last successfully-fetched configuration.
 *
 * ### Role (Story 2.2 AC-4 through AC-8)
 *
 * The cache is strictly a **fallback for offline cold starts**, not a
 * performance optimisation. The CDN is the cache-control authority for
 * freshness; the SDK always attempts a live fetch first
 * ([com.convert.sdk.core.api.ApiManager.fetchConfig]) and only falls back
 * to [read] when that fetch fails. A successful fetch always overwrites
 * the cached copy via [write] so that the next offline cold start picks
 * up the freshest-known config.
 *
 * ### Storage location (AC-4, NFR9)
 *
 * Writes land under the app-private `context.filesDir/convert-sdk/`
 * directory. On Android, `filesDir` is a per-app sandbox with mode 0700
 * on the parent — no other app on the device can read it without root.
 * The cached JSON therefore needs no additional encryption or permission
 * wrapping (NFR9). Multiple user accounts on the same device each get
 * their own `filesDir`; the cache is per-user/per-app as expected.
 *
 * ### Atomic write (AC-4, Gotcha 3)
 *
 * [write] serialises to `config.json.tmp` and then calls [File.renameTo]
 * to atomically swap it into place as `config.json`. On Android's
 * ext4/f2fs filesystems `renameTo` is atomic — if the process dies
 * mid-write, `config.json` still refers to the previous valid version.
 * If `renameTo` itself fails (a rare edge case — almost always a
 * filesystem-level error), the method falls back to a non-atomic direct
 * write and logs a WARN. Worst case: a `.tmp` file remains and gets
 * overwritten on the next successful write; `read` ignores it.
 *
 * ### Corruption recovery (AC-4 / AC-8, NFR13)
 *
 * [read] treats any parse failure as corruption: it logs a WARN with
 * the literal message `"FileConfigCache: corrupted cache file at
 * ${cacheFile.path}, deleting and recovering"`, deletes the broken
 * file, and returns `null`. The SDK then behaves as if the cache never
 * existed. This is important because a partially-written file from a
 * crash or a version mismatch (e.g. a user downgrades the SDK and the
 * old parser can't handle new schema) must not prevent the SDK from
 * functioning once network is restored.
 *
 * The WARN level (rather than ERROR) is mandated by NFR13:
 * *"Corrupted local state must be detected, logged at WARN, and
 * auto-recovered without crashing"* (architecture.md §Non-Functional
 * Requirements). Story 2.2 AC-4, applying F-139 option a, adopts the
 * NFR13 wording verbatim.
 *
 * ### Threading (AC-4, Gotcha 2)
 *
 * Every public operation dispatches onto [Dispatchers.IO] via
 * [withContext]. Callers typically launch them inside the SDK's
 * `CoroutineScope` (e.g. `scope.launch { fileConfigCache.write(...) }`)
 * — those scopes generally run on `Dispatchers.Default`, so the explicit
 * [withContext] switch is what keeps blocking disk I/O off
 * computation threads.
 *
 * ### Secret non-leakage (AC-9)
 *
 * The cache stores ONLY [ConfigResponseData], which is a server-returned
 * payload with no credential fields by design. It never sees
 * `sdkKey` or `sdkKeySecret`. Tests assert that authorization-shaped
 * substrings are absent from the written bytes.
 *
 * ### Serialization (Story 2.2 AC-12, F-172)
 *
 * The [json] instance is INJECTED by [com.convert.sdk.android.ConvertSDK.Builder.build]
 * — the shared `sharedJson` constructed there registers
 * [com.convert.sdk.core.internal.bigDecimalSerializersModule]
 * (`serializersModule = bigDecimalSerializersModule`) so that the
 * encode path below does not throw
 * `kotlinx.serialization.SerializationException: Serializer for class
 * 'BigDecimal' is not found` when [ConfigResponseData] carries a
 * non-null `@Contextual java.math.BigDecimal?` field (e.g.
 * [com.convert.sdk.core.model.generated.ConfigProjectSettings.minOrderValue],
 * `maxOrderValue`).
 *
 * `FileConfigCache` MUST NOT instantiate its own [Json] — Story 2.2
 * AC-12 records the F-172 post-mortem (verbatim 2026-05-07 demo run on
 * staging account 10035569 / project 10034190) and forbids the private
 * Json pattern that masked the missing serializer module from the
 * encode path. Tests that construct `FileConfigCache` MUST pass a
 * [Json] whose `serializersModule` includes
 * [com.convert.sdk.core.internal.bigDecimalSerializersModule] (or an
 * aggregate that subsumes it).
 *
 * @property context application context — used only for `filesDir`.
 *   The cache never retains any reference past the enclosing
 *   [withContext] coroutine and never touches UI resources.
 * @property logger sink for INFO/ERROR/WARN messages. Callers can pass
 *   [Logger.NoOp] in tests that don't care about log output.
 * @property json shared [Json] instance from
 *   [com.convert.sdk.android.ConvertSDK.Builder.build]'s `sharedJson`
 *   block. MUST register
 *   [com.convert.sdk.core.internal.bigDecimalSerializersModule] (or
 *   any aggregate that subsumes it) — see Story 2.2 AC-12 / F-172.
 */
internal class FileConfigCache(
    private val context: Context,
    private val logger: Logger,
    private val json: Json,
) {

    private val cacheDir: File = File(context.filesDir, CACHE_DIRNAME)
    private val cacheFile: File = File(cacheDir, CACHE_FILENAME)
    private val tmpFile: File = File(cacheDir, CACHE_FILENAME_TMP)

    /**
     * Writes [config] to disk atomically. Creates the parent directory if
     * it doesn't already exist. On any exception, logs a WARN and
     * silently fails — cache writes are purely opportunistic and must
     * never throw to the caller (AC-5: "fire-and-forget").
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun write(config: ConfigResponseData) = withContext(Dispatchers.IO) {
        try {
            // Ensure the parent exists — mkdirs is idempotent.
            if (!cacheDir.exists() && !cacheDir.mkdirs()) {
                logger.warn(
                    message = "FileConfigCache.write: failed to create dir ${cacheDir.path}",
                    tag = TAG,
                )
                return@withContext
            }

            val serialised = json.encodeToString(ConfigResponseData.serializer(), config)

            // Atomic write: write to .tmp, then rename.
            tmpFile.writeText(serialised)
            val renamed = tmpFile.renameTo(cacheFile)
            if (!renamed) {
                logger.warn(
                    message = "FileConfigCache.write: renameTo failed; falling back to direct write",
                    tag = TAG,
                )
                // Fallback: write directly to the final path.
                cacheFile.writeText(serialised)
                // Clean up the leftover tmp so repeated falls back don't
                // accumulate garbage.
                tmpFile.delete()
            }
        } catch (t: Throwable) {
            logger.warn(
                message = "FileConfigCache.write: failed (${t.message})",
                throwable = t,
                tag = TAG,
            )
            // Never rethrow — writes are fire-and-forget.
        }
    }

    /**
     * Reads the cached config. Returns:
     *  - `null` and logs INFO with the literal message
     *    `"FileConfigCache: no cache file found"` when the file is
     *    absent (expected on first launch — Story 2.2 AC-4).
     *  - `null` and logs WARN with the literal message
     *    `"FileConfigCache: corrupted cache file at ${cacheFile.path},
     *    deleting and recovering"` + deletes the file when the file
     *    exists but can't be parsed (corruption recovery — NFR13,
     *    Story 2.2 AC-4 / F-139 option a).
     *  - The parsed [ConfigResponseData] on success.
     */
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend fun read(): ConfigResponseData? = withContext(Dispatchers.IO) {
        if (!cacheFile.exists()) {
            // Story 2.2 AC-4 (F-139 option a): exact literal — operators
            // grep aggregated logs for this string.
            logger.info(
                message = "FileConfigCache: no cache file found",
                tag = TAG,
            )
            return@withContext null
        }

        val raw = try {
            cacheFile.readText()
        } catch (t: Throwable) {
            // I/O failure (permission denied, mounted-FS gone) is distinct
            // from JSON corruption — keep at ERROR level. Delete the file
            // so a subsequent successful fetch can re-seed cleanly.
            logger.error(
                message = "FileConfigCache.read: failed to read ${cacheFile.path}: ${t.message}",
                throwable = t,
                tag = TAG,
            )
            cacheFile.delete()
            return@withContext null
        }

        // Empty file is corruption-like — treat uniformly with unparseable
        // JSON per NFR13 (WARN + auto-recover).
        if (raw.isBlank()) {
            logger.warn(
                message = "FileConfigCache: corrupted cache file at ${cacheFile.path}, " +
                    "deleting and recovering",
                tag = TAG,
            )
            cacheFile.delete()
            return@withContext null
        }

        return@withContext try {
            json.decodeFromString(ConfigResponseData.serializer(), raw)
        } catch (t: Throwable) {
            // Story 2.2 AC-4 (F-139 option a): exact literal mandated by
            // NFR13 — "Corrupted local state must be detected, logged at
            // WARN, and auto-recovered without crashing".
            logger.warn(
                message = "FileConfigCache: corrupted cache file at ${cacheFile.path}, " +
                    "deleting and recovering",
                throwable = t,
                tag = TAG,
            )
            cacheFile.delete()
            null
        }
    }

    /**
     * Deletes the cache file. Used by corruption recovery (internally) and
     * by callers that want to force a cold re-fetch on next launch (e.g.
     * the consumer clears the cache from their app settings). No-op when
     * the file is already absent.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun delete() = withContext(Dispatchers.IO) {
        try {
            if (cacheFile.exists()) {
                cacheFile.delete()
            }
        } catch (t: Throwable) {
            logger.warn(
                message = "FileConfigCache.delete: failed (${t.message})",
                throwable = t,
                tag = TAG,
            )
        }
        Unit
    }

    companion object {
        private const val TAG: String = "FileConfigCache"

        /** Subdirectory under `context.filesDir` that holds the cache file. */
        internal const val CACHE_DIRNAME: String = "convert-sdk"

        /** Filename of the persisted config JSON. */
        internal const val CACHE_FILENAME: String = "config.json"

        /** Filename of the temporary staging file used for atomic writes. */
        internal const val CACHE_FILENAME_TMP: String = "config.json.tmp"
    }
}
