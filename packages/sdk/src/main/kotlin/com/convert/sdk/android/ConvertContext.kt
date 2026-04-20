/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

/**
 * Per-visitor public context.
 *
 * Story 1.2 ships a minimal stub so that [ConvertSDK] can return a
 * compiling instance. The full public surface (runExperience,
 * runFeatures, trackConversion, setLocationProperties, …) lands in
 * the companion SDK-2 task — those additional methods and the fields
 * backing them are appended to this class without changing the
 * constructor or existing fields.
 *
 * Constructor is `internal` — only [ConvertSDK.createContext] may
 * produce instances.
 *
 * @property visitorId stable visitor identifier for this context.
 */
public class ConvertContext internal constructor(
    public val visitorId: String,
) {

    private var attributes: Map<String, Any?>? = null

    /**
     * Seeds or replaces this context's attribute map.
     *
     * TODO(Story 3.1): propagate to the DataManager-held VisitorContext.
     *
     * @param attributes attributes associated with this visitor.
     * @return this context, enabling fluent chaining.
     */
    public fun setAttributes(attributes: Map<String, Any?>): ConvertContext {
        this.attributes = attributes
        return this
    }
}
