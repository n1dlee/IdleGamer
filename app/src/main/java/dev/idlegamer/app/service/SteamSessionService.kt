package dev.idlegamer.app.service

import android.Manifest
import android.app.ForegroundServiceStartNotAllowedException
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.idlegamer.app.IdleGamerApplication
import dev.idlegamer.app.MainActivity
import dev.idlegamer.app.R
import dev.idlegamer.app.core.logging.AppEvent
import dev.idlegamer.app.domain.model.ActivityState
import dev.idlegamer.app.domain.model.SessionEndReason
import dev.idlegamer.app.domain.usecase.ForegroundSessionController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service of type `specialUse` (ANDROID_LIMITATIONS.md §2). It owns no Steam logic: the
 * session lives in the app-scoped [dev.idlegamer.app.session.ActivityEngine]; this service only keeps the
 * process in the foreground while a user-started session is open, shows its status, and offers Stop.
 *
 * `START_NOT_STICKY`: after the system kills the process nothing restarts silently; the next app launch
 * closes the session as INTERRUPTED.
 */
class SteamSessionService : LifecycleService() {

    private val container by lazy { (application as IdleGamerApplication).container }
    private var observing = false
    private lateinit var wakeLock: WakeLockController

    override fun onCreate() {
        super.onCreate()
        wakeLock = WakeLockController(this)
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> {
                lifecycleScope.launch { container.stopActivity(fromNotification = true) }
                if (!observing) stopSelf()
            }
            else -> {
                promoteToForeground(container.activityEngine.state.value)
                if (!observing) observe()
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(state: ActivityState) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
    }

    private fun observe() {
        observing = true
        val engine = container.activityEngine
        lifecycleScope.launch {
            combine(engine.state, container.settingsRepository.settings) { state, settings -> state to settings.keepCpuAwake }
                .collect { (state, keepAwake) ->
                    notify(state)
                    wakeLock.setHeld(keepAwake && state is ActivityState.Active, lifecycleScope)
                }
        }
        lifecycleScope.launch {
            // Watchdog: if no session opens shortly after start (start failed), or once it closes, stop.
            delay(STARTUP_GRACE_MS)
            while (true) {
                engine.state.first { !it.hasOpenSession && it !is ActivityState.Stopping }
                // Switching games closes and reopens a session back-to-back; make sure it really ended.
                delay(SETTLE_MS)
                if (!engine.state.value.hasOpenSession) break
            }
            stopForegroundAndSelf()
        }
    }

    private fun stopForegroundAndSelf() {
        wakeLock.release()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Android 15+: only invoked for time-limited types; implemented defensively for any future limit. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        container.logger.event(AppEvent.FOREGROUND_SERVICE_STOPPED, "system timeout type=$fgsType")
        container.appScope.launch { container.activityEngine.stop(SessionEndReason.SYSTEM_STOPPED) }
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        wakeLock.release()
        if (container.activityEngine.state.value.hasOpenSession) {
            container.appScope.launch { container.activityEngine.stop(SessionEndReason.SYSTEM_STOPPED) }
        }
        container.logger.event(AppEvent.FOREGROUND_SERVICE_STOPPED)
        super.onDestroy()
    }

    private fun notify(state: ActivityState) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            @Suppress("MissingPermission")
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(state))
        }
    }

    private fun buildNotification(state: ActivityState): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, SteamSessionService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val (title, text) = describe(state)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, getString(R.string.action_stop), stop)
            .apply {
                if (state is ActivityState.Active) {
                    val elapsedLocal = state.accumulatedActiveMillis +
                        (android.os.SystemClock.elapsedRealtime() - state.activeSinceElapsedMillis)
                    setUsesChronometer(true)
                    setWhen(System.currentTimeMillis() - elapsedLocal)
                    setShowWhen(true)
                }
            }
            .build()
    }

    private fun describe(state: ActivityState): Pair<String, String> = when (state) {
        is ActivityState.Active -> getString(R.string.notif_active_title) to
            resources.getQuantityString(R.plurals.notif_active_text, state.appIds.size, state.appIds.size)
        is ActivityState.Reconnecting -> getString(R.string.notif_reconnecting_title) to getString(R.string.notif_reconnecting_text)
        is ActivityState.Starting -> getString(R.string.notif_starting_title) to getString(R.string.notif_starting_text)
        is ActivityState.Error -> getString(R.string.notif_error_title) to getString(R.string.notif_error_text)
        ActivityState.Stopping -> getString(R.string.notif_stopping_title) to ""
        else -> getString(R.string.app_name) to getString(R.string.notif_idle_text)
    }

    companion object {
        const val ACTION_START = "dev.idlegamer.app.action.START_SESSION"
        const val ACTION_STOP = "dev.idlegamer.app.action.STOP_SESSION"
        private const val CHANNEL_ID = "session"
        private const val NOTIFICATION_ID = 1001
        private const val STARTUP_GRACE_MS = 10_000L
        private const val SETTLE_MS = 1_500L

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.channel_session), NotificationManager.IMPORTANCE_LOW).apply {
                        description = context.getString(R.string.channel_session_description)
                        setShowBadge(false)
                    },
                )
            }
        }
    }
}

/** Starts [SteamSessionService] from a foreground UI action. */
class AndroidForegroundSessionController(private val context: Context) : ForegroundSessionController {
    override fun startForegroundSession(): Boolean = try {
        ContextCompat.startForegroundService(
            context,
            Intent(context, SteamSessionService::class.java).setAction(SteamSessionService.ACTION_START),
        )
        true
    } catch (e: IllegalStateException) {
        // Android 12+: ForegroundServiceStartNotAllowedException (subclass of IllegalStateException).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && e !is ForegroundServiceStartNotAllowedException) throw e
        false
    }
}
