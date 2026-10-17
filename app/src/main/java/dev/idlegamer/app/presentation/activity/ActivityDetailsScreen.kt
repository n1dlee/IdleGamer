package dev.idlegamer.app.presentation.activity

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.idlegamer.app.R
import dev.idlegamer.app.core.di.AppContainer
import dev.idlegamer.app.domain.model.ActivityState
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.EventLogEntry
import dev.idlegamer.app.domain.model.Game
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.domain.model.SteamException
import dev.idlegamer.app.presentation.appViewModelFactory
import dev.idlegamer.app.presentation.components.SectionCard
import dev.idlegamer.app.presentation.components.StatusDot
import dev.idlegamer.app.presentation.components.connectionColor
import dev.idlegamer.app.presentation.components.connectionText
import dev.idlegamer.app.presentation.components.dateTime
import dev.idlegamer.app.presentation.components.formatDuration
import dev.idlegamer.app.presentation.components.formatMinutes
import dev.idlegamer.app.presentation.components.rememberElapsedTicker
import dev.idlegamer.app.presentation.components.steamErrorText
import dev.idlegamer.app.presentation.dashboard.ActivityStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class Verification(val atEpochMillis: Long, val minutesByApp: Map<Int, Int>, val error: SteamError?)

class ActivityDetailsViewModel(private val c: AppContainer) : ViewModel() {
    val activity: StateFlow<ActivityState> = c.activityEngine.state
    val connection: StateFlow<ConnectionState> = c.sessionManager.connectionState
    val online: StateFlow<Boolean> = c.networkMonitor.isOnline
    val games: StateFlow<List<Game>> = c.gameRepository.observeGames().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val events: StateFlow<List<EventLogEntry>> = c.eventLog.observeRecent(40).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _verification = MutableStateFlow<Verification?>(null)
    val verification: StateFlow<Verification?> = _verification.asStateFlow()

    fun stop() = viewModelScope.launch { c.stopActivity(fromNotification = false) }

    fun verify(appIds: List<Int>) = viewModelScope.launch {
        val steamId = c.sessionManager.currentSteamId() ?: return@launch
        _verification.value = try {
            Verification(c.clock.currentTimeMillis(), c.gameRepository.verifyPlaytime(steamId, appIds), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SteamException) {
            Verification(c.clock.currentTimeMillis(), emptyMap(), e.error)
        }
    }
}

@Composable
fun ActivityDetailsScreen(viewModel: ActivityDetailsViewModel = viewModel(factory = appViewModelFactory(::ActivityDetailsViewModel))) {
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    val games by viewModel.games.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    val verification by viewModel.verification.collectAsStateWithLifecycle()
    val now = rememberElapsedTicker()

    val appIds = when (val a = activity) {
        is ActivityState.Active -> a.appIds
        is ActivityState.Reconnecting -> a.appIds
        is ActivityState.Error -> a.appIds
        is ActivityState.Starting -> a.appIds
        is ActivityState.Stopped -> a.summary?.appIds.orEmpty()
        else -> emptyList()
    }
    val names = games.associate { it.appId to it.name }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard(stringResource(R.string.details_title)) {
                ActivityStatus(activity, now)
                val startedAt = (activity as? ActivityState.Active)?.sessionStartedElapsedMillis
                    ?: (activity as? ActivityState.Reconnecting)?.sessionStartedElapsedMillis
                startedAt?.let { Text(stringResource(R.string.details_started, formatDuration(now - it))) }
                StatusDot(connectionColor(connection), connectionText(connection, now))
                Text(stringResource(R.string.details_network, stringResource(if (online) R.string.details_online else R.string.details_offline)))
                appIds.forEach { id -> Text("• ${names[id] ?: stringResource(R.string.lib_app_id, id)} (${stringResource(R.string.lib_app_id, id)})") }
                if (activity.hasOpenSession) Button(onClick = viewModel::stop) { Text(stringResource(R.string.action_stop)) }
            }
        }
        item {
            SectionCard(stringResource(R.string.details_remote)) {
                Text(stringResource(R.string.details_verify_note), style = MaterialTheme.typography.bodySmall)
                OutlinedButton(
                    onClick = { viewModel.verify(appIds) },
                    enabled = appIds.isNotEmpty() && connection is ConnectionState.Authenticated,
                ) { Text(stringResource(R.string.details_verify)) }
                verification?.let { v ->
                    v.error?.let { Text(steamErrorText(it), color = MaterialTheme.colorScheme.error) }
                    v.minutesByApp.forEach { (appId, minutes) ->
                        Text(stringResource(R.string.details_verified, dateTime(v.atEpochMillis), "${names[appId] ?: appId}: ${formatMinutes(minutes)}"))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.details_events), style = MaterialTheme.typography.titleMedium) }
        items(events, key = { it.id }) { e ->
            Text("${dateTime(e.timestampEpochMillis)}  ${e.type}  ${e.message}", style = MaterialTheme.typography.bodySmall)
            HorizontalDivider()
        }
    }
}
