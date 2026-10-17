package dev.idlegamer.app.presentation.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import dev.idlegamer.app.domain.model.ActivitySessionRecord
import dev.idlegamer.app.domain.model.SessionEndReason
import dev.idlegamer.app.presentation.appViewModelFactory
import dev.idlegamer.app.presentation.components.dateTime
import dev.idlegamer.app.presentation.components.formatDuration
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class HistoryUi(val sessions: List<ActivitySessionRecord> = emptyList(), val names: Map<Int, String> = emptyMap())

class HistoryViewModel(c: AppContainer) : ViewModel() {
    val state: StateFlow<HistoryUi> = combine(c.historyRepository.observeSessions(), c.gameRepository.observeGames()) { s, g ->
        HistoryUi(s, g.associate { it.appId to it.name })
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUi())
}

@Composable
fun HistoryScreen(viewModel: HistoryViewModel = viewModel(factory = appViewModelFactory(::HistoryViewModel))) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item { Text(stringResource(R.string.history_note), style = MaterialTheme.typography.bodySmall) }
        if (state.sessions.isEmpty()) {
            item { Text(stringResource(R.string.history_empty), style = MaterialTheme.typography.bodyLarge) }
        }
        items(state.sessions, key = { it.id }) { s -> SessionRow(s, state.names) }
    }
}

@Composable
private fun SessionRow(s: ActivitySessionRecord, names: Map<Int, String>) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(dateTime(s.startedAtEpochMillis), style = MaterialTheme.typography.titleSmall)
            Text(s.appIds.joinToString { names[it] ?: "App $it" }, style = MaterialTheme.typography.bodyMedium)
            Text(formatDuration(s.activeMillis) + " · " + reasonText(s), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun reasonText(s: ActivitySessionRecord): String = when (s.endReason) {
    null -> stringResource(R.string.history_running)
    SessionEndReason.USER_STOPPED_UI -> stringResource(R.string.history_reason_USER_STOPPED_UI)
    SessionEndReason.USER_STOPPED_NOTIFICATION -> stringResource(R.string.history_reason_USER_STOPPED_NOTIFICATION)
    SessionEndReason.LOGGED_OUT -> stringResource(R.string.history_reason_LOGGED_OUT)
    SessionEndReason.AUTH_LOST -> stringResource(R.string.history_reason_AUTH_LOST)
    SessionEndReason.AUTO_STOP_LIMIT -> stringResource(R.string.history_reason_AUTO_STOP_LIMIT)
    SessionEndReason.SYSTEM_STOPPED -> stringResource(R.string.history_reason_SYSTEM_STOPPED)
    SessionEndReason.INTERRUPTED -> stringResource(R.string.history_reason_INTERRUPTED)
    SessionEndReason.ERROR -> stringResource(R.string.history_reason_ERROR)
}
