package dev.idlegamer.app.domain.repository

import dev.idlegamer.app.domain.model.LibrarySort
import kotlinx.coroutines.flow.Flow

enum class ThemeMode { SYSTEM, DARK, LIGHT }

enum class LibraryLayout { LIST, GRID }

/** Non-sensitive preferences only. Credentials never go here. */
data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val libraryLayout: LibraryLayout = LibraryLayout.LIST,
    val librarySort: LibrarySort = LibrarySort.NAME,
    val keepCpuAwake: Boolean = false,
    /** 0 = no automatic stop. */
    val autoStopAfterHours: Int = 0,
    val riskAcknowledged: Boolean = false,
    val appLockEnabled: Boolean = false,
    /**
     * Random per-install value sent as `obfuscated_private_ip` in CMsgClientLogon so this session does not
     * collide with (and kick) the user's desktop client. Not a secret.
     */
    val loginId: Int = 0,
)

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun current(): AppSettings
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setLibraryLayout(layout: LibraryLayout)
    suspend fun setLibrarySort(sort: LibrarySort)
    suspend fun setKeepCpuAwake(enabled: Boolean)
    suspend fun setAutoStopAfterHours(hours: Int)
    suspend fun setRiskAcknowledged(acknowledged: Boolean)
    suspend fun setAppLockEnabled(enabled: Boolean)

    /** Returns the stable login id, creating it on first use. */
    suspend fun loginId(): Int
}
