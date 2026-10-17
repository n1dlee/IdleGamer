package dev.idlegamer.app.presentation.auth

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.idlegamer.app.R
import dev.idlegamer.app.core.logging.AppEvent
import dev.idlegamer.app.core.logging.AppLogger
import dev.idlegamer.app.core.steamapp.SteamAppHandoff
import dev.idlegamer.app.domain.model.AuthState
import dev.idlegamer.app.domain.model.ReauthReason
import dev.idlegamer.app.presentation.appViewModelFactory
import dev.idlegamer.app.presentation.components.QrCodeImage
import dev.idlegamer.app.presentation.components.authErrorText
import dev.idlegamer.app.presentation.components.formatDuration
import dev.idlegamer.app.presentation.components.rememberElapsedTicker
import dev.idlegamer.app.session.SteamSessionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class LoginViewModel(
    private val session: SteamSessionManager,
    private val handoff: SteamAppHandoff,
    private val logger: AppLogger,
    val deviceName: String,
) : ViewModel() {
    val authState: StateFlow<AuthState> = session.authState

    private val _steamApp = MutableStateFlow(SteamAppHandoff.Availability.NOT_INSTALLED)
    /** Whether the challenge can be approved in the Steam app on this same phone. Rechecked on resume. */
    val steamApp: StateFlow<SteamAppHandoff.Availability> = _steamApp.asStateFlow()

    private val _handoffFailed = MutableStateFlow(false)
    val handoffFailed: StateFlow<Boolean> = _handoffFailed.asStateFlow()

    fun refreshSteamAppAvailability() {
        _steamApp.value = handoff.availability()
    }

    fun start() {
        _handoffFailed.value = false
        session.startQrLogin()
    }

    fun cancel() = session.cancelQrLogin()

    fun approveInSteamApp() {
        val url = (authState.value as? AuthState.WaitingForApproval)?.challengeUrl ?: return
        val opened = handoff.open(url)
        _handoffFailed.value = !opened
        logger.event(AppEvent.AUTH_HANDOFF, if (opened) "opened official Steam app" else "Steam app handoff failed")
    }
}

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = viewModel(
        factory = appViewModelFactory { c -> LoginViewModel(c.sessionManager, c.steamAppHandoff, c.logger, c.deviceFriendlyName) },
    ),
) {
    val state by viewModel.authState.collectAsStateWithLifecycle()
    val steamApp by viewModel.steamApp.collectAsStateWithLifecycle()
    val handoffFailed by viewModel.handoffFailed.collectAsStateWithLifecycle()
    SecureWindow(enabled = state is AuthState.WaitingForApproval)
    // Rechecked on every resume: the user may install Steam while the login screen is open.
    LifecycleResumeEffect(Unit) {
        viewModel.refreshSteamAppAvailability()
        onPauseOrDispose { }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)

        when (val s = state) {
            AuthState.Initializing -> Progress(stringResource(R.string.login_initializing), onCancel = viewModel::cancel)
            AuthState.Authenticating -> Progress(stringResource(R.string.login_authenticating), onCancel = null)
            is AuthState.WaitingForApproval -> QrPanel(
                state = s,
                deviceName = viewModel.deviceName,
                steamApp = steamApp,
                handoffFailed = handoffFailed,
                onApproveInSteamApp = viewModel::approveInSteamApp,
                onNewCode = viewModel::start,
                onCancel = viewModel::cancel,
            )
            else -> {
                StatusMessage(s)
                Text(stringResource(R.string.login_description), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                RiskNotice()
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.login_sign_in))
                }
            }
        }
    }
}

@Composable
private fun StatusMessage(state: AuthState) {
    val text = when (state) {
        is AuthState.Failed -> authErrorText(state.error)
        is AuthState.ReauthenticationRequired -> when (state.reason) {
            ReauthReason.TokenRejected -> stringResource(R.string.login_reauth_token_rejected)
            ReauthReason.LocalCredentialsUnavailable -> stringResource(R.string.login_reauth_local_unavailable)
            ReauthReason.SessionRevoked -> stringResource(R.string.login_reauth_revoked)
        }
        else -> null
    } ?: return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            text,
            modifier = Modifier
                .padding(16.dp)
                .semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun RiskNotice() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text(stringResource(R.string.login_risk_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.login_risk_text), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Progress(label: String, onCancel: (() -> Unit)?) {
    CircularProgressIndicator()
    Text(label, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    onCancel?.let { OutlinedButton(onClick = it) { Text(stringResource(R.string.action_cancel)) } }
}

@Composable
private fun QrPanel(
    state: AuthState.WaitingForApproval,
    deviceName: String,
    steamApp: SteamAppHandoff.Availability,
    handoffFailed: Boolean,
    onApproveInSteamApp: () -> Unit,
    onNewCode: () -> Unit,
    onCancel: () -> Unit,
) {
    val now = rememberElapsedTicker()
    val sameDevice = steamApp == SteamAppHandoff.Availability.AVAILABLE && SteamAppHandoff.isValidChallengeUrl(state.challengeUrl)

    if (sameDevice) {
        // Primary path when Steam Mobile is on this phone: a phone cannot scan its own screen.
        Text(stringResource(R.string.login_same_device_instructions, deviceName), textAlign = TextAlign.Center)
        Button(onClick = onApproveInSteamApp, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.login_open_steam_app))
        }
        if (handoffFailed) {
            Text(stringResource(R.string.login_handoff_failed), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Text(
            stringResource(R.string.login_waiting_for_approval),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        HorizontalDivider()
        Text(stringResource(R.string.login_or_other_device), style = MaterialTheme.typography.titleSmall)
    } else {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Text(
                stringResource(
                    when (steamApp) {
                        SteamAppHandoff.Availability.UNTRUSTED_SIGNATURE -> R.string.login_steam_app_untrusted
                        SteamAppHandoff.Availability.CANNOT_HANDLE_LINK, SteamAppHandoff.Availability.AVAILABLE ->
                            R.string.login_steam_app_cannot_handle
                        SteamAppHandoff.Availability.NOT_INSTALLED -> R.string.login_steam_app_missing
                    },
                ),
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Text(stringResource(R.string.login_scan_instructions, deviceName), textAlign = TextAlign.Center)
    QrCodeImage(state.challengeUrl, contentDescription = stringResource(R.string.login_cd_qr), size = if (sameDevice) 200.dp else 260.dp)
    Text(
        stringResource(R.string.login_qr_expires, formatDuration(state.expiresAtElapsedMillis - now)),
        style = MaterialTheme.typography.bodySmall,
    )
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Text(stringResource(R.string.login_qr_warning), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
    OutlinedButton(onClick = onNewCode, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.login_new_code)) }
    OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_cancel)) }
}

/** FLAG_SECURE while a live QR is shown: no screenshots, screen recording or Recents thumbnail (R21). */
@Composable
private fun SecureWindow(enabled: Boolean) {
    val activity = LocalActivity.current ?: return
    DisposableEffect(enabled) {
        if (enabled) activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
