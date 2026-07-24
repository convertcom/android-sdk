/*
 * Convert Android SDK — core/preview
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.preview

/**
 * Numeric-only segment pattern used to validate both the `experienceId` and
 * `variationId` parts of a preview param value. Mirrors the JS SDK's
 * `parsePreviewParam` (`packages/js-sdk/src/parse-preview-param.ts`).
 */
private val NUMERIC_ONLY: Regex = Regex("^[0-9]+$")

/**
 * Pure parsing for the canonical preview deep-link query param — qs-02 / AC9.
 *
 * The host app extracts the VALUE of `convert_preview={experienceId}.{variationId}`
 * from its deep link / App Link and hands the parsed pair to the SDK; this
 * object performs only the value-parsing step (it never sees the full URL or
 * the `convert_preview=` key).
 */
public object PreviewParam {

    /**
     * Parses a preview param value of the form `"{experienceId}.{variationId}"`
     * (mirrors the web tracking script's force-param
     * `_conv_eforce={experienceId}.{variationId}`).
     *
     * Splits on the first dot only; both segments must be non-empty,
     * numeric-only (`[0-9]+`) strings. Any other shape (missing dot, extra
     * dot, empty segment, non-numeric segment, or whitespace anywhere that
     * breaks the numeric-only check) returns `null`.
     *
     * Pure function: never throws, has no side effects, performs no I/O or
     * logging.
     *
     * @param value the already-extracted param value (the host app strips
     *   the `convert_preview=` prefix before calling this).
     * @return the `(experienceId, variationId)` pair on success, else `null`.
     */
    @JvmStatic
    public fun parse(value: String): Pair<String, String>? {
        val dotIndex = value.indexOf('.')
        val hasSingleDot = dotIndex != -1 && value.indexOf('.', dotIndex + 1) == -1
        if (!hasSingleDot) return null

        val experienceId = value.substring(0, dotIndex)
        val variationId = value.substring(dotIndex + 1)
        val isNumericPair = NUMERIC_ONLY.matches(experienceId) && NUMERIC_ONLY.matches(variationId)

        return if (isNumericPair) experienceId to variationId else null
    }
}
