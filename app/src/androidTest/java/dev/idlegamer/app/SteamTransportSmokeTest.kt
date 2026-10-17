package dev.idlegamer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.idlegamer.app.core.logging.AppEvent
import dev.idlegamer.app.core.logging.AppLogger
import dev.idlegamer.app.data.steam.GatewayStatus
import dev.idlegamer.app.data.steam.JavaSteamGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in network test against the real Steam CM network. It opens and closes a **transport connection only**:
 * no account, no logon, no presence. Run with:
 *
 *     ./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.steamNetwork=true
 */
@RunWith(AndroidJUnit4::class)
class SteamTransportSmokeTest {
    @Test fun connectsToSteamCmAndDisconnects() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("steamNetwork") == "true")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val logger = object : AppLogger {
            override fun debug(tag: String, message: String) = Unit
            override fun warn(tag: String, message: String, throwable: Throwable?) = Unit
            override fun error(tag: String, message: String, throwable: Throwable?) = Unit
            override fun event(event: AppEvent, details: String) = Unit
        }
        val gateway = JavaSteamGateway(scope, logger, verboseLibraryLogs = false)
        try {
            gateway.connect()
            assertTrue(gateway.status.value is GatewayStatus.Connected)
            gateway.disconnect()
            assertTrue(gateway.status.value is GatewayStatus.Disconnected)
        } finally {
            scope.cancel()
        }
    }
}
