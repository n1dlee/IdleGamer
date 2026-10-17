package dev.idlegamer.app.domain.usecase

import dev.idlegamer.app.domain.model.ActivityError
import dev.idlegamer.app.domain.model.SessionEndReason
import dev.idlegamer.app.domain.repository.GameRepository
import dev.idlegamer.app.session.ActivityEngine
import dev.idlegamer.app.session.SteamSessionManager

/** Starts/keeps the foreground service. Implemented by the Android service layer. */
interface ForegroundSessionController {
    /** Must be called from a user action while the UI is visible. Returns false if Android refused. */
    fun startForegroundSession(): Boolean
}

class StartActivityUseCase(
    private val engine: ActivityEngine,
    private val foreground: ForegroundSessionController,
    private val riskAcknowledged: suspend () -> Boolean,
) {
    suspend operator fun invoke(appIds: List<Int>): ActivityError? {
        engine.validate(appIds.distinct())?.let { return it }
        if (!riskAcknowledged()) return ActivityError.RiskNotAcknowledged
        if (!foreground.startForegroundSession()) return ActivityError.ForegroundServiceNotAllowed
        // If start fails the service sees no session open and stops itself (SteamSessionService watchdog).
        return engine.start(appIds)
    }
}

class StopActivityUseCase(private val engine: ActivityEngine) {
    suspend operator fun invoke(fromNotification: Boolean) = engine.stop(
        if (fromNotification) SessionEndReason.USER_STOPPED_NOTIFICATION else SessionEndReason.USER_STOPPED_UI,
    )
}

/**
 * Manual logout (SESSION_LIFECYCLE.md §6): stop presence → revoke (best effort) → log off → wipe vault →
 * clear account-specific cache. Activity history is kept (no secrets) and can be cleared in Settings.
 */
class LogoutUseCase(
    private val engine: ActivityEngine,
    private val session: SteamSessionManager,
    private val games: GameRepository,
) {
    suspend operator fun invoke(): SteamSessionManager.LogoutResult {
        engine.stop(SessionEndReason.LOGGED_OUT)
        val result = session.logout()
        runCatching { games.clearAll() }
        return result
    }
}
