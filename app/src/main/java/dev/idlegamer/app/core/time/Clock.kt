package dev.idlegamer.app.core.time

import android.os.SystemClock

/**
 * Durations are always measured with [elapsedRealtimeMillis] (monotonic, keeps counting in deep sleep,
 * immune to the user changing the wall clock). [currentTimeMillis] is only used for display timestamps.
 */
interface Clock {
    fun elapsedRealtimeMillis(): Long
    fun currentTimeMillis(): Long
}

object SystemClocks : Clock {
    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
    override fun currentTimeMillis(): Long = System.currentTimeMillis()
}
