/*
 * Convert Android SDK — sdk
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.android

/**
 * Public re-export of the core [com.convert.sdk.core.event.SubscriptionToken]
 * so consumers import it from the SDK's top-level package alongside
 * [ConvertSDK] and [EventCallback] — they don't need to reach into
 * `com.convert.sdk.core.event` to unsubscribe from events.
 *
 * The actual class lives in `:packages:core` so that non-Android consumers
 * (tests, other JVM-only modules in the future) can reference it too;
 * the typealias here keeps the public API surface consistent with the
 * rest of the sdk package.
 */
public typealias SubscriptionToken = com.convert.sdk.core.event.SubscriptionToken
