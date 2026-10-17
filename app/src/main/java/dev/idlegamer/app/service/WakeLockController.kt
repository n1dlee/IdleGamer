package dev.idlegamer.app.service

import android.content.Context
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Opt-in partial wake lock (Settings → "Keep CPU awake while active"). Never held indefinitely: each
 * acquisition has a hard [TIMEOUT_MS] and is renewed only while the caller keeps asking for it, so a
 * crashed renewal loop releases the CPU by itself.
 */
class WakeLockController(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var lock: PowerManager.WakeLock? = null
    private var renewal: Job? = null

    @Synchronized
    fun setHeld(held: Boolean, scope: CoroutineScope) {
        if (held) {
            if (renewal?.isActive == true) return
            renewal = scope.launch {
                while (isActive) {
                    acquire()
                    delay(TIMEOUT_MS - RENEW_MARGIN_MS)
                }
            }
        } else {
            release()
        }
    }

    @Synchronized
    private fun acquire() {
        val current = lock ?: power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG).apply { setReferenceCounted(false) }
        lock = current
        current.acquire(TIMEOUT_MS)
    }

    @Synchronized
    fun release() {
        renewal?.cancel()
        renewal = null
        lock?.let { if (it.isHeld) it.release() }
        lock = null
    }

    val isHeld: Boolean @Synchronized get() = lock?.isHeld == true

    private companion object {
        const val TAG = "IdleGamer:activity"
        const val TIMEOUT_MS = 60 * 60_000L
        const val RENEW_MARGIN_MS = 5 * 60_000L
    }
}
