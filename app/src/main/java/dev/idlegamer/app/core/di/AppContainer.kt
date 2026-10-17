package dev.idlegamer.app.core.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import dev.idlegamer.app.BuildConfig
import dev.idlegamer.app.core.logging.AppLogger
import dev.idlegamer.app.core.logging.DefaultAppLogger
import dev.idlegamer.app.core.network.AndroidNetworkMonitor
import dev.idlegamer.app.core.network.NetworkMonitor
import dev.idlegamer.app.core.security.FileSecureCredentialStore
import dev.idlegamer.app.core.security.KeystoreCredentialCipher
import dev.idlegamer.app.core.time.Clock
import dev.idlegamer.app.core.time.SystemClocks
import dev.idlegamer.app.data.local.AppDatabase
import dev.idlegamer.app.data.local.DataStoreSettingsRepository
import dev.idlegamer.app.data.repository.RoomActivityHistoryRepository
import dev.idlegamer.app.data.repository.RoomEventLogRepository
import dev.idlegamer.app.data.repository.RoomGameRepository
import dev.idlegamer.app.data.steam.JavaSteamGateway
import dev.idlegamer.app.data.steam.SteamGateway
import dev.idlegamer.app.domain.repository.ActivityHistoryRepository
import dev.idlegamer.app.domain.repository.EventLogRepository
import dev.idlegamer.app.domain.repository.GameRepository
import dev.idlegamer.app.domain.repository.SecureCredentialStore
import dev.idlegamer.app.domain.usecase.LogoutUseCase
import dev.idlegamer.app.domain.usecase.StartActivityUseCase
import dev.idlegamer.app.domain.usecase.StopActivityUseCase
import dev.idlegamer.app.service.AndroidForegroundSessionController
import dev.idlegamer.app.session.ActivityEngine
import dev.idlegamer.app.session.SteamSessionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.io.File

/**
 * Manual dependency injection. A single-module personal app does not justify Hilt's annotation
 * processing (and its AGP 9 built-in-Kotlin migration risk, R24); every dependency is visible here.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val clock: Clock = SystemClocks

    val database: AppDatabase = Room.databaseBuilder(appContext, AppDatabase::class.java, AppDatabase.NAME)
        .addMigrations(*AppDatabase.MIGRATIONS)
        .build()

    val eventLog: EventLogRepository = RoomEventLogRepository(database.events())

    val logger: AppLogger = DefaultAppLogger(
        verbose = BuildConfig.DEBUG,
        clock = clock,
        scope = appScope,
        journal = { eventLog },
    )

    val settingsRepository = DataStoreSettingsRepository(
        PreferenceDataStoreFactory.create(
            corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { appContext.preferencesDataStoreFile("settings") },
        ),
    )

    val networkMonitor: NetworkMonitor = AndroidNetworkMonitor(appContext)

    /** Vault lives in `noBackupFilesDir`: excluded from Auto Backup even if rules were misconfigured. */
    val credentialStore: SecureCredentialStore = FileSecureCredentialStore(
        vaultFile = File(appContext.noBackupFilesDir, "vault/session.bin"),
        cipher = KeystoreCredentialCipher(),
        io = Dispatchers.IO,
    )

    val steamGateway: SteamGateway = JavaSteamGateway(appScope, logger, verboseLibraryLogs = BuildConfig.DEBUG)

    /** Shown in the Steam app's approval prompt and in Steam's device list. Honest, not spoofed. */
    val deviceFriendlyName: String = "IdleGamer (${Build.MANUFACTURER} ${Build.MODEL})".take(64)

    val gameRepository: GameRepository = RoomGameRepository(database.games(), steamGateway, clock)
    val historyRepository: ActivityHistoryRepository = RoomActivityHistoryRepository(database.sessions())

    val sessionManager = SteamSessionManager(
        gateway = steamGateway,
        credentialStore = credentialStore,
        network = networkMonitor,
        clock = clock,
        logger = logger,
        scope = appScope,
        deviceFriendlyName = deviceFriendlyName,
        loginId = { settingsRepository.loginId() },
        onAccountSignedIn = { steamId ->
            // No implicit account switching: a different account never inherits the previous one's cache.
            val previous = settingsRepository.lastAccountSteamId()
            if (previous != null && previous != steamId.raw) {
                gameRepository.clearAll()
            }
            settingsRepository.setLastAccountSteamId(steamId.raw)
        },
    )

    val activityEngine = ActivityEngine(
        session = sessionManager,
        steamEvents = steamGateway.events,
        history = historyRepository,
        clock = clock,
        logger = logger,
        scope = appScope,
        riskAcknowledged = { settingsRepository.current().riskAcknowledged },
        autoStopAfterHours = { settingsRepository.current().autoStopAfterHours },
    )

    val startActivity = StartActivityUseCase(
        engine = activityEngine,
        foreground = AndroidForegroundSessionController(appContext),
        riskAcknowledged = { settingsRepository.current().riskAcknowledged },
    )
    val stopActivity = StopActivityUseCase(activityEngine)
    val logout = LogoutUseCase(activityEngine, sessionManager, gameRepository)
}
