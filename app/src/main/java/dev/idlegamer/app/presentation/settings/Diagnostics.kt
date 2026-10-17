package dev.idlegamer.app.presentation.settings

import android.os.Build
import dev.idlegamer.app.BuildConfig
import dev.idlegamer.app.core.logging.Redactor
import dev.idlegamer.app.domain.model.ActivityState
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.EventLogEntry
import dev.idlegamer.app.domain.model.SteamId

/**
 * Builds the user-exported diagnostics report. Contains no credentials by construction (only state kinds
 * and already-redacted journal lines) and is passed through [Redactor] once more as a safety net.
 */
object DiagnosticsReport {
    fun build(
        nowEpochMillis: Long,
        steamId: SteamId?,
        connection: ConnectionState,
        activity: ActivityState,
        events: List<EventLogEntry>,
    ): String {
        val text = buildString {
            appendLine("IdleGamer diagnostics")
            appendLine("generated=${java.time.Instant.ofEpochMilli(nowEpochMillis)}")
            appendLine("app=${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${if (BuildConfig.DEBUG) "debug" else "release"}")
            appendLine("android=${Build.VERSION.RELEASE} (api ${Build.VERSION.SDK_INT}) device=${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("account=${steamId?.masked() ?: "none"}")
            appendLine("connection=${connection.kind}")
            appendLine("activity=${activity.kind}")
            appendLine()
            appendLine("recent events (newest first):")
            events.forEach { e ->
                appendLine("${java.time.Instant.ofEpochMilli(e.timestampEpochMillis)} ${e.type} ${e.message}")
            }
        }
        return Redactor.redact(text)
    }
}
