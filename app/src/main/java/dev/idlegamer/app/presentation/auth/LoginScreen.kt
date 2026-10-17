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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.autofill.ContentType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentType
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.idlegamer.app.R
import dev.idlegamer.app.domain.model.AuthState
import dev.idlegamer.app.domain.model.GuardCodeKind
import dev.idlegamer.app.domain.model.ReauthReason
import dev.idlegamer.app.presentation.appViewModelFactory
import dev.idlegamer.app.presentation.components.QrCodeImage
import dev.idlegamer.app.presentation.components.authErrorText
import dev.idlegamer.app.presentation.components.formatDuration
import dev.idlegamer.app.presentation.components.rememberElapsedTicker
import dev.idlegamer.app.session.SteamSessionManager
import kotlinx.coroutines.flow.StateFlow

/** Holds no secrets: the password goes straight from the text field to the session manager. */
class LoginViewModel(private val session: SteamSessionManager, val deviceName: String) : ViewModel() {
    val authState: StateFlow<AuthState> = session.authState
    fun signInWithPassword(accountName: String, password: CharArray) = session.startCredentialsLogin(accountName, password)
    fun signInWithQr() = session.startQrLogin()
    fun submitGuardCode(code: String) = session.submitGuardCode(code)
    fun cancel() = session.cancelLogin()
}

@Composable
fun LoginScreen(
    viewModel: LoginViewModel = viewModel(
        factory = appViewModelFactory { c -> LoginViewModel(c.sessionManager, c.deviceFriendlyName) },
    ),
) {
    val state by viewModel.authState.collectAsStateWithLifecycle()
    // The whole login flow shows a password field, Steam Guard prompts or a live QR: never capturable (R21).
    SecureWindow()

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
            is AuthState.AwaitingSteamGuard -> SteamGuardPanel(s, viewModel.deviceName, viewModel::submitGuardCode, viewModel::cancel)
            is AuthState.WaitingForApproval -> QrPanel(s, viewModel.deviceName, onNewCode = viewModel::signInWithQr, onCancel = viewModel::cancel)
            else -> {
                StatusMessage(s)
                CredentialsForm(onSubmit = viewModel::signInWithPassword)
                RiskNotice()
                HorizontalDivider()
                Text(stringResource(R.string.login_qr_alternative), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                OutlinedButton(onClick = viewModel::signInWithQr, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.login_sign_in_qr))
                }
            }
        }
    }
}

/**
 * Account name + password. Both live only in this composable's memory (`remember`, not `rememberSaveable`, so they
 * never enter saved instance state), are handed over as a CharArray on submit, and the field is cleared at once.
 */
@Composable
private fun CredentialsForm(onSubmit: (String, CharArray) -> Unit) {
    var accountName by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    val canSubmit = accountName.isNotBlank() && password.isNotEmpty()
    val submit = {
        if (canSubmit) {
            onSubmit(accountName.trim(), password.toCharArray())
            password = ""
        }
    }

    Text(stringResource(R.string.login_description), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    OutlinedTextField(
        value = accountName,
        onValueChange = { accountName = it.take(64).replace("\n", "") },
        label = { Text(stringResource(R.string.login_account_name)) },
        supportingText = { Text(stringResource(R.string.login_account_name_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Username },
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it.take(128).replace("\n", "") },
        label = { Text(stringResource(R.string.login_password)) },
        singleLine = true,
        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        trailingIcon = {
            IconButton(onClick = { showPassword = !showPassword }) {
                Icon(
                    if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                    contentDescription = stringResource(if (showPassword) R.string.login_hide_password else R.string.login_show_password),
                )
            }
        },
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentType = ContentType.Password },
    )
    Text(stringResource(R.string.login_password_note), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.login_sign_in))
    }
}

@Composable
private fun SteamGuardPanel(state: AuthState.AwaitingSteamGuard, deviceName: String, onSubmitCode: (String) -> Unit, onCancel: () -> Unit) {
    val now = rememberElapsedTicker()
    var code by remember { mutableStateOf("") }

    Text(stringResource(R.string.guard_title), style = MaterialTheme.typography.titleLarge)
    if (state.mobileApproval) {
        CircularProgressIndicator()
        Text(stringResource(R.string.guard_push_instructions, deviceName), textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.guard_waiting),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    state.codeKind?.let { kind ->
        if (state.mobileApproval) HorizontalDivider()
        Text(
            when (kind) {
                GuardCodeKind.MOBILE_APP -> stringResource(
                    if (state.mobileApproval) R.string.guard_code_mobile_alternative else R.string.guard_code_mobile,
                )
                GuardCodeKind.EMAIL -> state.emailHint?.let { stringResource(R.string.guard_code_email_hint, it) }
                    ?: stringResource(R.string.guard_code_email)
            },
            textAlign = TextAlign.Center,
        )
        OutlinedTextField(
            value = code,
            onValueChange = { v -> code = v.filter(Char::isLetterOrDigit).take(8).uppercase() },
            label = { Text(stringResource(R.string.guard_code_label)) },
            singleLine = true,
            isError = state.codeRejected,
            supportingText = { if (state.codeRejected) Text(stringResource(R.string.guard_code_rejected)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                capitalization = KeyboardCapitalization.Characters,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmitCode(code) }),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentType = ContentType.SmsOtpCode },
        )
        Button(onClick = { onSubmitCode(code) }, enabled = code.length >= 5 && !state.submittingCode, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.guard_submit))
        }
    }
    Text(
        stringResource(R.string.login_qr_expires, formatDuration(state.expiresAtElapsedMillis - now)),
        style = MaterialTheme.typography.bodySmall,
    )
    TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
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
private fun QrPanel(state: AuthState.WaitingForApproval, deviceName: String, onNewCode: () -> Unit, onCancel: () -> Unit) {
    val now = rememberElapsedTicker()
    // Steam accepts QR sign-in only from the Steam app's camera scanner. Handing the link to the Steam app on the
    // same phone was tried and Steam refuses it ("please scan the Steam QR code again"), so a second device is needed.
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Text(stringResource(R.string.login_second_device_required), modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
    }
    Text(stringResource(R.string.login_scan_instructions, deviceName), textAlign = TextAlign.Center)
    QrCodeImage(state.challengeUrl, contentDescription = stringResource(R.string.login_cd_qr))
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

/** FLAG_SECURE for the login flow: no screenshots, screen recording or Recents thumbnail (R21). */
@Composable
private fun SecureWindow() {
    val activity = LocalActivity.current ?: return
    DisposableEffect(Unit) {
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}
