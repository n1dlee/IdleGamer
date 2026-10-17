package dev.idlegamer.app.presentation.settings

import android.content.Intent
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.idlegamer.app.BuildConfig
import dev.idlegamer.app.R
import dev.idlegamer.app.core.di.AppContainer
import dev.idlegamer.app.domain.model.UserProfile
import dev.idlegamer.app.domain.repository.AppSettings
import dev.idlegamer.app.domain.repository.ThemeMode
import dev.idlegamer.app.presentation.appViewModelFactory
import dev.idlegamer.app.presentation.components.SectionCard
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = c.settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())
    val profile: StateFlow<UserProfile?> = c.sessionManager.profile

    private val _message = MutableStateFlow<Int?>(null)
    val message: StateFlow<Int?> = _message.asStateFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    fun logout() = viewModelScope.launch {
        _busy.value = true
        val result = c.logout()
        _busy.value = false
        _message.value = if (result.revocationConfirmed) R.string.settings_logout_done_revoked else R.string.settings_logout_done_not_revoked
    }

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { c.settingsRepository.setThemeMode(mode) }
    fun setKeepAwake(enabled: Boolean) = viewModelScope.launch { c.settingsRepository.setKeepCpuAwake(enabled) }
    fun setAutoStop(hours: Int) = viewModelScope.launch { c.settingsRepository.setAutoStopAfterHours(hours) }
    fun setAppLock(enabled: Boolean) = viewModelScope.launch { c.settingsRepository.setAppLockEnabled(enabled) }
    fun resetRisk() = viewModelScope.launch { c.settingsRepository.setRiskAcknowledged(false) }

    fun clearCache() = viewModelScope.launch {
        c.gameRepository.clearAll()
        _message.value = R.string.settings_done
    }

    fun clearHistory() = viewModelScope.launch {
        c.historyRepository.clearAll()
        c.eventLog.clearAll()
        _message.value = R.string.settings_done
    }

    suspend fun buildDiagnostics(): String = DiagnosticsReport.build(
        nowEpochMillis = c.clock.currentTimeMillis(),
        steamId = c.sessionManager.currentSteamId(),
        connection = c.sessionManager.connectionState.value,
        activity = c.activityEngine.state.value,
        events = c.eventLog.recent(200),
    )

    fun consumeMessage() {
        _message.value = null
    }
}

@Composable
fun SettingsScreen(
    onOpenLicenses: () -> Unit,
    viewModel: SettingsViewModel = viewModel(factory = appViewModelFactory(::SettingsViewModel)),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var confirmLogout by rememberSaveable { mutableStateOf(false) }
    var confirmClear by rememberSaveable { mutableStateOf<String?>(null) }
    val lockAvailable = remember {
        BiometricManager.from(context).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL,
        ) == BiometricManager.BIOMETRIC_SUCCESS
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(stringResource(R.string.settings_account)) {
            profile?.personaName?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
            profile?.steamId?.let { Text(stringResource(R.string.settings_steam_id, it.asDecimalString()), style = MaterialTheme.typography.bodySmall) }
            Button(
                onClick = { confirmLogout = true },
                enabled = !busy,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.settings_logout)) }
            Text(stringResource(R.string.settings_revoke_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.settings_revoke_text), style = MaterialTheme.typography.bodySmall)
        }

        SectionCard(stringResource(R.string.settings_session)) {
            ToggleRow(stringResource(R.string.settings_keep_awake), stringResource(R.string.settings_keep_awake_text), settings.keepCpuAwake, viewModel::setKeepAwake)
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }) {
                Text(stringResource(R.string.settings_battery))
            }
            Text(stringResource(R.string.settings_auto_stop), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 2, 4, 8, 12).forEach { h ->
                    FilterChip(
                        selected = settings.autoStopAfterHours == h,
                        onClick = { viewModel.setAutoStop(h) },
                        label = { Text(if (h == 0) stringResource(R.string.settings_auto_stop_off) else stringResource(R.string.settings_auto_stop_hours, h)) },
                    )
                }
            }
        }

        SectionCard(stringResource(R.string.settings_notifications)) {
            Text(stringResource(R.string.settings_notifications_text), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            }) { Text(stringResource(R.string.settings_open_notification_settings)) }
        }

        SectionCard(stringResource(R.string.settings_appearance)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(ThemeMode.SYSTEM to R.string.settings_theme_system, ThemeMode.DARK to R.string.settings_theme_dark, ThemeMode.LIGHT to R.string.settings_theme_light)
                    .forEach { (mode, label) ->
                        FilterChip(selected = settings.themeMode == mode, onClick = { viewModel.setTheme(mode) }, label = { Text(stringResource(label)) })
                    }
            }
        }

        SectionCard(stringResource(R.string.settings_security)) {
            ToggleRow(
                stringResource(R.string.settings_app_lock),
                stringResource(if (lockAvailable) R.string.settings_app_lock_text else R.string.settings_app_lock_unavailable),
                settings.appLockEnabled,
                viewModel::setAppLock,
                enabled = lockAvailable || settings.appLockEnabled,
            )
            OutlinedButton(onClick = viewModel::resetRisk) { Text(stringResource(R.string.settings_risk_reset)) }
        }

        SectionCard(stringResource(R.string.settings_data)) {
            OutlinedButton(onClick = { confirmClear = "cache" }) { Text(stringResource(R.string.settings_clear_cache)) }
            OutlinedButton(onClick = { confirmClear = "history" }) { Text(stringResource(R.string.settings_clear_history)) }
        }

        SectionCard(stringResource(R.string.settings_diagnostics)) {
            Text(stringResource(R.string.settings_export_text), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
                scope.launch {
                    val report = viewModel.buildDiagnostics()
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report)
                    context.startActivity(Intent.createChooser(send, null))
                }
            }) { Text(stringResource(R.string.settings_export)) }
        }

        SectionCard(stringResource(R.string.settings_about)) {
            Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME))
            Text(stringResource(R.string.settings_about_text), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onOpenLicenses) { Text(stringResource(R.string.settings_licenses)) }
        }
        Spacer(Modifier.padding(8.dp))
    }

    if (confirmLogout) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            text = { Text(stringResource(R.string.settings_logout_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    viewModel.logout()
                }) { Text(stringResource(R.string.settings_logout)) }
            },
            dismissButton = { TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmClear?.let { what ->
        AlertDialog(
            onDismissRequest = { confirmClear = null },
            text = { Text(stringResource(R.string.settings_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = null
                    if (what == "cache") viewModel.clearCache() else viewModel.clearHistory()
                }) { Text(stringResource(R.string.action_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    message?.let { res ->
        AlertDialog(
            onDismissRequest = viewModel::consumeMessage,
            text = { Text(stringResource(res)) },
            confirmButton = { TextButton(onClick = viewModel::consumeMessage) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
