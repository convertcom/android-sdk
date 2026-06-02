/*
 * Convert Android SDK Demo App — SdkViewModel
 * Copyright (c) 2026 Convert Insights, Inc.
 * License: Apache-2.0
 */
package com.convert.sdk.demo.viewmodel

import androidx.lifecycle.ViewModel
import com.convert.sdk.core.event.SystemEvents
import com.convert.sdk.core.model.LogLevel
import com.convert.sdk.core.port.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Story 7.1 AC-9 / Story 7.2 — shared ViewModel that aggregates SDK
 * state for every demo screen.
 *
 * ### Responsibilities
 *
 * - Subscribes to the SDK's internal pub/sub for the five observable
 *   system events ([SystemEvents.READY], [SystemEvents.CONFIG_UPDATED],
 *   [SystemEvents.BUCKETING], [SystemEvents.CONVERSION],
 *   [SystemEvents.API_QUEUE_RELEASED]) and exposes a newest-first
 *   [events] flow for the Events tab.
 *
 * - Publishes a [Logger] handle (backed by the internal [demoLogger])
 *   that fan-outs every log call to the SDK's normal Logcat adapter
 *   AND to the Logs tab's [logs] flow — full-fidelity "what the SDK
 *   sees" for developers.
 *
 * - Tracks online / offline state in [networkOnline] for the Offline
 *   screen's status indicator. Story 7.1 leaves the actual connectivity
 *   observation as a stub — [setNetworkOnline] is called by
 *   [com.convert.sdk.demo.ui.screen.OfflineScreen] in Story 7.5 when
 *   the developer toggles airplane mode; the initial value is a
 *   caller-supplied default.
 *
 * - (Story 7.2) Tracks each networked event's delivery lifecycle
 *   ([EventLifecycle]): `bucketing` and `conversion` start QUEUED
 *   when observed; a 2xx `api.queue.released` transitions every
 *   currently-QUEUED event to DELIVERED. Non-networked events
 *   (`ready`, `config.updated`) carry [EventLifecycle.NONE] and
 *   render without a status badge.
 *
 * - (Story 7.2) Holds the active inspector [InspectorTab] so the
 *   developer's tab choice survives screen navigation (the
 *   [EventInspectorSheet] is recomposed on every route change, so a
 *   `remember {}` here would reset to the default every time).
 *
 * ### Testing
 *
 * This class is constructable with a fake [EventSubscriber] so tests
 * avoid the Android [android.content.Context] dependency required by
 * [com.convert.sdk.android.ConvertSDK.builder]. The real factory lives
 * in [com.convert.sdk.demo.DemoApplication] which wires a production
 * subscriber against the SDK singleton.
 *
 * @property eventSubscriber the subscription surface — production
 *   wraps the real SDK, tests provide an in-memory fake.
 * @property initialNetworkOnline seed value for [networkOnline].
 *   Default `true` because the app is expected to launch with
 *   connectivity; the Offline screen drives subsequent updates.
 */
class SdkViewModel(
    eventSubscriber: EventSubscriber,
    initialNetworkOnline: Boolean = true,
) : ViewModel() {

    private val _events = MutableStateFlow<List<InspectorEvent>>(emptyList())

    /**
     * Newest-first list of events captured from the SDK bus. Consumed
     * by [com.convert.sdk.demo.ui.component.EventInspectorSheet]'s
     * Events tab.
     */
    val events: StateFlow<List<InspectorEvent>> = _events.asStateFlow()

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())

    /**
     * Newest-first list of log entries captured from the SDK's
     * [Logger] adapter. Consumed by the Logs tab.
     */
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _networkOnline = MutableStateFlow(initialNetworkOnline)

    /**
     * Current online/offline indicator state. The Offline screen's
     * "Online ✓ / Offline ✗" banner binds to this.
     */
    val networkOnline: StateFlow<Boolean> = _networkOnline.asStateFlow()

    private val _selectedTab = MutableStateFlow(InspectorTab.EVENTS)

    /**
     * Story 7.2 AC-2 — active inspector tab. Stored in the ViewModel
     * so it survives screen navigation; a `remember {}` inside the
     * composable would reset whenever the user changes screens.
     */
    val selectedTab: StateFlow<InspectorTab> = _selectedTab.asStateFlow()

    /**
     * Internal logger that both forwards to any delegate the caller
     * wired AND appends to the [logs] flow. Production wires the
     * SDK's [com.convert.sdk.android.adapter.AndroidLogger] as the
     * delegate; tests typically pass no delegate and just observe the
     * flow.
     */
    private val demoLogger: Logger = object : Logger {
        override fun error(message: String, throwable: Throwable?, tag: String?) {
            appendLog(LogLevel.ERROR, message, tag, throwable)
        }

        override fun warn(message: String, throwable: Throwable?, tag: String?) {
            appendLog(LogLevel.WARN, message, tag, throwable)
        }

        override fun info(message: String, tag: String?) {
            appendLog(LogLevel.INFO, message, tag)
        }

        override fun debug(message: String, tag: String?) {
            appendLog(LogLevel.DEBUG, message, tag)
        }
    }

    /**
     * Public [Logger] handle — the real demo's
     * [com.convert.sdk.demo.logger.DemoLogger] delegates to this so
     * every SDK log call surfaces in the Logs tab.
     */
    val logger: Logger get() = demoLogger

    private val subscriptionTokens: MutableList<AutoCloseable> = mutableListOf()

    init {
        // Subscribe to the five system events named in AC-9. We keep
        // the AutoCloseable tokens alive for the lifetime of the
        // ViewModel — `onCleared()` tears them down. Tests exercise
        // the subscription side-effect via FakeEventSubscriber.
        OBSERVED_EVENTS.forEach { event ->
            val token = eventSubscriber.subscribe(event) { payload ->
                onSdkEvent(event, payload)
            }
            subscriptionTokens += token
        }
    }

    /**
     * Called by the Offline screen (Story 7.5) when connectivity
     * changes. Story 7.1 only wires the API; the actual
     * [android.net.ConnectivityManager] observation lands later.
     */
    fun setNetworkOnline(online: Boolean) {
        _networkOnline.value = online
    }

    /** Story 7.2 AC-2 — updates the active inspector tab. */
    fun selectTab(tab: InspectorTab) {
        _selectedTab.value = tab
    }

    private fun onSdkEvent(event: String, payload: Map<String, Any?>) {
        // Route API_QUEUE_RELEASED through the resolver — it is a
        // meta-signal (not user-visible content) that drives lifecycle
        // transitions on previously-queued events.
        if (event == SystemEvents.API_QUEUE_RELEASED) {
            onApiQueueReleased(payload)
            return
        }
        val lifecycle = when (event) {
            SystemEvents.BUCKETING, SystemEvents.CONVERSION -> EventLifecycle.QUEUED
            else -> EventLifecycle.NONE
        }
        val captured = InspectorEvent(
            id = InspectorEvent.nextId(),
            eventName = event,
            payload = payload,
            lifecycle = lifecycle,
        )
        _events.update { listOf(captured) + it }
    }

    /**
     * Story 7.2 AC-7 — when `api.queue.released` fires with a 2xx
     * status, every currently-QUEUED networked event is considered
     * delivered; transition them in place. Non-2xx statuses leave the
     * events in QUEUED so the developer can still see what was in
     * flight (the next successful flush will resolve them).
     */
    private fun onApiQueueReleased(payload: Map<String, Any?>) {
        val status = (payload["statusCode"] as? Number)?.toInt() ?: return
        if (status !in 200..299) return
        _events.update { list ->
            list.map { event ->
                if (event.lifecycle == EventLifecycle.QUEUED) {
                    event.copy(lifecycle = EventLifecycle.DELIVERED)
                } else {
                    event
                }
            }
        }
    }

    private fun appendLog(
        level: LogLevel,
        message: String,
        tag: String?,
        throwable: Throwable? = null,
    ) {
        val entry = LogEntry(
            id = LogEntry.nextId(),
            level = level,
            message = message,
            tag = tag,
            throwable = throwable,
        )
        _logs.update { listOf(entry) + it }
    }

    override fun onCleared() {
        super.onCleared()
        subscriptionTokens.forEach { runCatching { it.close() } }
        subscriptionTokens.clear()
    }

    private companion object {
        /**
         * The five system events AC-9 requires the inspector to
         * observe. Reads the JS-SDK-canon string values directly from
         * [SystemEvents] rather than duplicating the constants here.
         */
        val OBSERVED_EVENTS: List<String> = listOf(
            SystemEvents.READY,
            SystemEvents.CONFIG_UPDATED,
            SystemEvents.BUCKETING,
            SystemEvents.CONVERSION,
            SystemEvents.API_QUEUE_RELEASED,
        )
    }
}
