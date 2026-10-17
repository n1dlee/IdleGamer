package dev.idlegamer.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import dev.idlegamer.app.core.di.AppContainer
import dev.idlegamer.app.core.logging.AppEvent
import dev.idlegamer.app.core.security.CryptoProviders
import dev.idlegamer.app.service.SteamSessionService
import dev.idlegamer.app.session.SteamSessionManager
import kotlinx.coroutines.launch

class IdleGamerApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Must precede the first JavaSteam class load (CryptoHelper's static initialiser).
        CryptoProviders.installBouncyCastle()
        container = AppContainer(this)
        SteamSessionService.ensureChannel(this)

        container.appScope.launch {
            container.activityEngine.recoverInterruptedSessions()
            container.sessionManager.initialize()
            container.eventLog.prune(
                olderThanEpochMillis = container.clock.currentTimeMillis() - JOURNAL_RETENTION_MS,
                keepAtMost = JOURNAL_MAX_ROWS,
            )
            container.logger.event(AppEvent.APP_INITIALIZED, "version ${BuildConfig.VERSION_NAME}")
        }

        // A visible UI asks for a live session (library, profile); releasing it has a grace period so
        // rotating the screen or a quick app switch does not drop the connection.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = container.sessionManager.requestDemand(SteamSessionManager.Demand.UI)
            override fun onStop(owner: LifecycleOwner) = container.sessionManager.releaseDemand(SteamSessionManager.Demand.UI)
        })
    }

    private companion object {
        const val JOURNAL_RETENTION_MS = 14L * 24 * 3600 * 1000
        const val JOURNAL_MAX_ROWS = 1000
    }
}
