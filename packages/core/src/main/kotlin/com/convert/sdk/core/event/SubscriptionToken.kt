/*
 * Convert Android SDK — core/event
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.core.event

/**
 * Opaque handle returned by [EventManager.on] identifying a specific
 * subscription.
 *
 * Consumers call [EventManager.off] (or the corresponding public
 * `ConvertSDK.off`) with the token to remove exactly the subscription
 * they registered — without having to keep a reference to the lambda
 * they passed in. This is the primary unsubscribe mechanism for both
 * Java and Kotlin consumers.
 *
 * ### Identity semantics
 *
 * Tokens have no content. Each call to `on(...)` produces a fresh
 * [SubscriptionToken] whose equality is identity-based (the default
 * from [Any]). Two distinct tokens — even those produced by the same
 * (event, callback) pair called twice — are unequal. Callers must
 * keep the token reference returned by `on` to unsubscribe with it.
 *
 * ### Why a class, not an opaque type
 *
 * A concrete class gives Java and Kotlin consumers a named type to
 * store in fields / pass across boundaries. An `Any`-typed handle or
 * a `typealias` would compile but would erode API readability and
 * leak the internal representation through stack traces and IDE hints.
 *
 * The constructor is `internal` so only [EventManager] (and its tests)
 * can mint tokens — consumer code cannot forge one.
 */
public class SubscriptionToken internal constructor()
