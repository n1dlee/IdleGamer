package dev.idlegamer.app.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import dev.idlegamer.app.domain.model.LibrarySort
import dev.idlegamer.app.domain.repository.AppSettings
import dev.idlegamer.app.domain.repository.LibraryLayout
import dev.idlegamer.app.domain.repository.SettingsRepository
import dev.idlegamer.app.domain.repository.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.SecureRandom

/**
 * Non-sensitive preferences. The DataStore is created with a `ReplaceFileCorruptionHandler` (see
 * AppContainer) so a corrupted file resets to defaults instead of crashing (R7).
 */
class DataStoreSettingsRepository(private val store: DataStore<Preferences>) : SettingsRepository {

    private object Keys {
        val theme = stringPreferencesKey("theme_mode")
        val layout = stringPreferencesKey("library_layout")
        val sort = stringPreferencesKey("library_sort")
        val keepCpuAwake = booleanPreferencesKey("keep_cpu_awake")
        val autoStopHours = intPreferencesKey("auto_stop_hours")
        val riskAck = booleanPreferencesKey("risk_acknowledged")
        val appLock = booleanPreferencesKey("app_lock_enabled")
        val loginId = intPreferencesKey("login_id")
        val lastAccount = longPreferencesKey("last_account_steam_id")
    }

    override val settings: Flow<AppSettings> = store.data
        .catch { e -> if (e is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw e }
        .map { p ->
            AppSettings(
                themeMode = p[Keys.theme].toEnum(ThemeMode.DARK),
                libraryLayout = p[Keys.layout].toEnum(LibraryLayout.LIST),
                librarySort = p[Keys.sort].toEnum(LibrarySort.NAME),
                keepCpuAwake = p[Keys.keepCpuAwake] ?: false,
                autoStopAfterHours = (p[Keys.autoStopHours] ?: 0).coerceIn(0, 24),
                riskAcknowledged = p[Keys.riskAck] ?: false,
                appLockEnabled = p[Keys.appLock] ?: false,
                loginId = p[Keys.loginId] ?: 0,
            )
        }

    override suspend fun current(): AppSettings = settings.first()

    override suspend fun setThemeMode(mode: ThemeMode) = put { it[Keys.theme] = mode.name }
    override suspend fun setLibraryLayout(layout: LibraryLayout) = put { it[Keys.layout] = layout.name }
    override suspend fun setLibrarySort(sort: LibrarySort) = put { it[Keys.sort] = sort.name }
    override suspend fun setKeepCpuAwake(enabled: Boolean) = put { it[Keys.keepCpuAwake] = enabled }
    override suspend fun setAutoStopAfterHours(hours: Int) = put { it[Keys.autoStopHours] = hours.coerceIn(0, 24) }
    override suspend fun setRiskAcknowledged(acknowledged: Boolean) = put { it[Keys.riskAck] = acknowledged }
    override suspend fun setAppLockEnabled(enabled: Boolean) = put { it[Keys.appLock] = enabled }

    override suspend fun loginId(): Int {
        var id = 0
        store.edit { p ->
            id = p[Keys.loginId]?.takeIf { it != 0 } ?: (SecureRandom().nextInt() or 1).also { p[Keys.loginId] = it }
        }
        return id
    }

    /** Last account that signed in (public SteamID64), used to detect account switches. */
    suspend fun lastAccountSteamId(): Long? = store.data.first()[Keys.lastAccount]

    suspend fun setLastAccountSteamId(raw: Long) = put { it[Keys.lastAccount] = raw }

    private suspend fun put(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        store.edit(block)
    }

    private inline fun <reified E : Enum<E>> String?.toEnum(default: E): E =
        this?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default
}
