package dev.idlegamer.app.core.logging

import android.util.Log
import dev.idlegamer.app.core.time.Clock
import dev.idlegamer.app.domain.repository.EventLogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** User-visible lifecycle events recorded in the local diagnostic journal (Phase 12). */
enum class AppEvent {
    APP_INITIALIZED,
    AUTH_INITIATED,
    AUTH_SUCCEEDED,
    AUTH_FAILED,
    AUTH_CANCELLED,
    AUTH_HANDOFF,
    CONNECTION_ESTABLISHED,
    CONNECTION_LOST,
    RECONNECT_SCHEDULED,
    SESSION_EXPIRED,
    LOGGED_OUT,
    TOKEN_REVOCATION,
    START_REQUESTED,
    STOP_REQUESTED,
    ACTIVITY_STATE,
    FOREGROUND_SERVICE_STOPPED,
    STORAGE_FAILURE,
    LIBRARY_REFRESH,
    INTERRUPTED_SESSIONS_RECOVERED,
}

interface AppLogger {
    fun debug(tag: String, message: String)
    fun warn(tag: String, message: String, throwable: Throwable? = null)
    fun error(tag: String, message: String, throwable: Throwable? = null)

    /** Records a lifecycle event in the persistent journal and Logcat. [details] is redacted. */
    fun event(event: AppEvent, details: String = "")
}

/**
 * Central logger. Every string passes through [Redactor]. Debug lines reach Logcat only in debug builds;
 * release builds log WARN/ERROR without stack traces (which could carry library payloads).
 */
class DefaultAppLogger(
    private val verbose: Boolean,
    private val clock: Clock,
    private val scope: CoroutineScope,
    private val journal: () -> EventLogRepository,
) : AppLogger {

    private val pending = Channel<Pair<AppEvent, String>>(capacity = 256)

    init {
        scope.launch {
            for ((event, details) in pending) {
                runCatching { journal().append(event.name, details, clock.currentTimeMillis()) }
                    .onFailure { Log.w(TAG, "journal write failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    override fun debug(tag: String, message: String) {
        if (verbose) Log.d("$TAG/$tag", Redactor.redact(message))
    }

    override fun warn(tag: String, message: String, throwable: Throwable?) {
        Log.w("$TAG/$tag", line(message, throwable))
    }

    override fun error(tag: String, message: String, throwable: Throwable?) {
        Log.e("$TAG/$tag", line(message, throwable))
    }

    override fun event(event: AppEvent, details: String) {
        val clean = Redactor.redact(details).take(500)
        if (verbose) Log.i("$TAG/event", "${event.name} $clean")
        pending.trySend(event to clean)
    }

    private fun line(message: String, throwable: Throwable?): String {
        val base = Redactor.redact(message)
        return if (throwable == null) base else "$base | ${Redactor.describe(throwable)}"
    }

    private companion object {
        const val TAG = "IdleGamer"
    }
}
