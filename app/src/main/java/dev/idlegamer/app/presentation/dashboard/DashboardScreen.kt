package dev.idlegamer.app.presentation.dashboard

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.idlegamer.app.R
import dev.idlegamer.app.core.di.AppContainer
import dev.idlegamer.app.domain.model.ActivityError
import dev.idlegamer.app.domain.model.ActivityState
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.Game
import dev.idlegamer.app.domain.model.RemotePresence
import dev.idlegamer.app.domain.model.UserProfile
import dev.idlegamer.app.presentation.appViewModelFactory
import dev.idlegamer.app.presentation.components.SectionCard
import dev.idlegamer.app.presentation.components.StatusDot
import dev.idlegamer.app.presentation.components.activityErrorText
import dev.idlegamer.app.presentation.components.connectionColor
import dev.idlegamer.app.presentation.components.connectionText
import dev.idlegamer.app.presentation.components.dateTime
import dev.idlegamer.app.presentation.components.formatDuration
import dev.idlegamer.app.presentation.components.rememberElapsedTicker
import dev.idlegamer.app.presentation.theme.LocalStatusColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class DashboardViewModel(private val c: AppContainer) : ViewModel() {
    val profile: StateFlow<UserProfile?> = c.sessionManager.profile
    val connection: StateFlow<ConnectionState> = c.sessionManager.connectionState
    val activity: StateFlow<ActivityState> = c.activityEngine.state
    val selectedGames: StateFlow<List<Game>> = c.gameRepository.observeGames()
        .map { games -> games.filter { it.isSelected } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val riskAcknowledged: StateFlow<Boolean> = c.settingsRepository.settings.map { it.riskAcknowledged }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _lastError = MutableStateFlow<ActivityError?>(null)
    val lastError: StateFlow<ActivityError?> = _lastError.asStateFlow()

    fun start() = viewModelScope.launch {
        _lastError.value = c.startActivity(selectedGames.value.map { it.appId })
    }

    fun stop() = viewModelScope.launch {
        _lastError.value = null
        c.stopActivity(fromNotification = false)
    }

    fun acknowledgeRiskAndStart() = viewModelScope.launch {
        c.settingsRepository.setRiskAcknowledged(true)
        _lastError.value = c.startActivity(selectedGames.value.map { it.appId })
    }

    fun retryConnection() = c.sessionManager.retryNow()
}

@Composable
fun DashboardScreen(
    onOpenLibrary: () -> Unit,
    onOpenDetails: () -> Unit,
    viewModel: DashboardViewModel = viewModel(factory = appViewModelFactory(::DashboardViewModel)),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val selected by viewModel.selectedGames.collectAsStateWithLifecycle()
    val riskAck by viewModel.riskAcknowledged.collectAsStateWithLifecycle()
    val lastError by viewModel.lastError.collectAsStateWithLifecycle()
    val now = rememberElapsedTicker()
    var showRisk by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    var notificationsGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationsGranted = granted
        if (riskAck) viewModel.start() else showRisk = true
    }
    val onStart: () -> Unit = {
        when {
            !notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            !riskAck -> showRisk = true
            else -> viewModel.start()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileHeader(profile)

        SectionCard(stringResource(R.string.dash_connection)) {
            StatusDot(connectionColor(connection), connectionText(connection, now))
            val error = (connection as? ConnectionState.Failed)?.error ?: (connection as? ConnectionState.Reconnecting)?.lastError
            error?.let { Text(dev.idlegamer.app.presentation.components.steamErrorText(it), style = MaterialTheme.typography.bodySmall) }
            if (connection is ConnectionState.Failed) {
                OutlinedButton(onClick = viewModel::retryConnection) { Text(stringResource(R.string.dash_reconnect)) }
            }
        }

        SectionCard(stringResource(R.string.dash_activity)) {
            ActivityStatus(activity, now)
            lastError?.let {
                Text(
                    activityErrorText(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (!notificationsGranted) {
                Text(stringResource(R.string.dash_notifications_denied), style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (activity.hasOpenSession || activity is ActivityState.Stopping) {
                    Button(onClick = viewModel::stop, enabled = activity !is ActivityState.Stopping) { Text(stringResource(R.string.action_stop)) }
                    OutlinedButton(onClick = onOpenDetails) { Text(stringResource(R.string.dash_details)) }
                } else {
                    Button(onClick = onStart, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.action_start)) }
                }
            }
        }

        SectionCard(stringResource(R.string.dash_selected_games)) {
            if (selected.isEmpty()) {
                Text(stringResource(R.string.dash_no_selection))
                OutlinedButton(onClick = onOpenLibrary) { Text(stringResource(R.string.dash_open_library)) }
            } else {
                selected.forEach { game ->
                    Text("${game.name}  ·  ${stringResource(R.string.lib_app_id, game.appId)}", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }

    if (showRisk) {
        RiskDialog(
            onAccept = {
                showRisk = false
                viewModel.acknowledgeRiskAndStart()
            },
            onDismiss = { showRisk = false },
        )
    }
}

@Composable
private fun ProfileHeader(profile: UserProfile?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(
            model = profile?.avatarUrl,
            contentDescription = null,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                profile?.personaName ?: stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(stringResource(R.string.app_tagline), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ActivityStatus(activity: ActivityState, nowElapsed: Long) {
    val colors = LocalStatusColors.current
    when (activity) {
        ActivityState.Idle -> StatusDot(colors.neutral, stringResource(R.string.act_idle))
        is ActivityState.Starting -> StatusDot(colors.warning, stringResource(R.string.act_starting))
        is ActivityState.Active -> {
            StatusDot(colors.ok, stringResource(R.string.act_active))
            val live = activity.accumulatedActiveMillis + (nowElapsed - activity.activeSinceElapsedMillis)
            Text(stringResource(R.string.dash_local_time, formatDuration(live)))
            when (val r = activity.remote) {
                RemotePresence.Unconfirmed -> Text(stringResource(R.string.dash_remote_unconfirmed), style = MaterialTheme.typography.bodySmall)
                is RemotePresence.EchoedByPersonaUpdate ->
                    Text(stringResource(R.string.dash_remote_echoed, r.appId, dateTime(r.atEpochMillis)), style = MaterialTheme.typography.bodySmall)
            }
        }
        is ActivityState.Reconnecting -> {
            StatusDot(colors.warning, stringResource(R.string.act_reconnecting))
            Text(stringResource(R.string.dash_local_time, formatDuration(activity.accumulatedActiveMillis)))
        }
        ActivityState.Stopping -> StatusDot(colors.warning, stringResource(R.string.act_stopping))
        is ActivityState.Stopped -> {
            StatusDot(colors.neutral, stringResource(R.string.act_stopped))
            activity.summary?.let {
                Text(stringResource(R.string.dash_last_summary, formatDuration(it.activeMillis), it.endReason.name.lowercase().replace('_', ' ')))
            }
        }
        is ActivityState.Error -> {
            StatusDot(colors.error, stringResource(R.string.act_error))
            Text(activityErrorText(activity.error), style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.dash_local_time, formatDuration(activity.accumulatedActiveMillis)))
        }
    }
}

@Composable
fun RiskDialog(onAccept: () -> Unit, onDismiss: () -> Unit) {
    var checked by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.risk_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.risk_body))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = checked, onCheckedChange = { checked = it })
                    Text(stringResource(R.string.risk_checkbox), style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onAccept, enabled = checked) { Text(stringResource(R.string.action_confirm)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
