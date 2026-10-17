package dev.idlegamer.app.presentation.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import dev.idlegamer.app.R
import dev.idlegamer.app.core.di.AppContainer
import dev.idlegamer.app.core.logging.AppEvent
import dev.idlegamer.app.domain.model.ConnectionState
import dev.idlegamer.app.domain.model.Game
import dev.idlegamer.app.domain.model.LibraryFilter
import dev.idlegamer.app.domain.model.LibrarySort
import dev.idlegamer.app.domain.model.SteamError
import dev.idlegamer.app.domain.model.SteamException
import dev.idlegamer.app.domain.repository.LibraryLayout
import dev.idlegamer.app.presentation.appViewModelFactory
import dev.idlegamer.app.presentation.components.formatMinutes
import dev.idlegamer.app.presentation.components.relativeTime
import dev.idlegamer.app.presentation.components.steamErrorText
import dev.idlegamer.app.presentation.theme.LocalStatusColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LibraryUiState(
    val games: List<Game> = emptyList(),
    val totalGames: Int = 0,
    val selectedCount: Int = 0,
    val query: String = "",
    val filter: LibraryFilter = LibraryFilter.ALL,
    val sort: LibrarySort = LibrarySort.NAME,
    val layout: LibraryLayout = LibraryLayout.LIST,
    val isRefreshing: Boolean = false,
    val lastRefreshEpochMillis: Long? = null,
    val refreshedThisSession: Boolean = false,
    val online: Boolean = false,
    val error: SteamError? = null,
    val selectionLimitHit: Boolean = false,
    val loaded: Boolean = false,
)

class LibraryViewModel(private val c: AppContainer) : ViewModel() {
    private val local = MutableStateFlow(LibraryUiState())

    val state: StateFlow<LibraryUiState> = combine(
        local,
        c.gameRepository.observeGames(),
        c.gameRepository.observeLastRefresh(),
        c.settingsRepository.settings,
        c.sessionManager.connectionState,
    ) { ui, games, lastRefresh, settings, connection ->
        val filtered = games
            .filter { ui.query.isBlank() || it.name.contains(ui.query.trim(), ignoreCase = true) || it.appId.toString() == ui.query.trim() }
            .filter {
                when (ui.filter) {
                    LibraryFilter.ALL -> true
                    LibraryFilter.SELECTED -> it.isSelected
                    LibraryFilter.PLAYED -> (it.playtimeForeverMinutes ?: 0) > 0
                    LibraryFilter.NEVER_PLAYED -> it.playtimeForeverMinutes == 0
                    LibraryFilter.MANUAL -> it.ownership == Game.Ownership.UNKNOWN
                }
            }
            .let { list ->
                when (settings.librarySort) {
                    LibrarySort.NAME -> list.sortedBy { it.name.lowercase() }
                    LibrarySort.PLAYTIME -> list.sortedByDescending { it.playtimeForeverMinutes ?: -1 }
                    LibrarySort.RECENTLY_PLAYED -> list.sortedByDescending { it.lastPlayedEpochSeconds ?: 0 }
                }
            }
        ui.copy(
            games = filtered,
            totalGames = games.size,
            selectedCount = games.count { it.isSelected },
            sort = settings.librarySort,
            layout = settings.libraryLayout,
            lastRefreshEpochMillis = lastRefresh,
            online = connection is ConnectionState.Authenticated,
            loaded = true,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    init {
        // Load from Steam once per process as soon as the session is authenticated.
        viewModelScope.launch {
            c.sessionManager.connectionState
                .filter { it is ConnectionState.Authenticated }
                .distinctUntilChangedBy { (it as ConnectionState.Authenticated).steamId }
                .first()
            if (!refreshedInProcess) refresh()
        }
    }

    fun refresh() = viewModelScope.launch {
        val steamId = c.sessionManager.currentSteamId()
        if (steamId == null || c.sessionManager.connectionState.value !is ConnectionState.Authenticated) {
            local.update { it.copy(error = SteamError.NetworkUnavailable) }
            return@launch
        }
        local.update { it.copy(isRefreshing = true, error = null) }
        try {
            c.gameRepository.refreshFromSteam(steamId)
            refreshedInProcess = true
            local.update { it.copy(isRefreshing = false, refreshedThisSession = true) }
            c.logger.event(AppEvent.LIBRARY_REFRESH, "ok")
        } catch (e: CancellationException) {
            throw e
        } catch (e: SteamException) {
            local.update { it.copy(isRefreshing = false, error = e.error) }
            c.logger.event(AppEvent.LIBRARY_REFRESH, "failed: ${e.error}")
        }
    }

    fun setQuery(q: String) = local.update { it.copy(query = q.take(100)) }
    fun setFilter(f: LibraryFilter) = local.update { it.copy(filter = f) }
    fun setSort(s: LibrarySort) = viewModelScope.launch { c.settingsRepository.setLibrarySort(s) }
    fun toggleLayout() = viewModelScope.launch {
        c.settingsRepository.setLibraryLayout(if (state.value.layout == LibraryLayout.LIST) LibraryLayout.GRID else LibraryLayout.LIST)
    }

    fun toggle(game: Game) = viewModelScope.launch {
        if (!game.isSelected && state.value.selectedCount >= Game.MAX_CONCURRENT_GAMES) {
            local.update { it.copy(selectionLimitHit = true) }
            return@launch
        }
        local.update { it.copy(selectionLimitHit = false) }
        c.gameRepository.setSelected(game.appId, !game.isSelected)
    }

    fun clearSelection() = viewModelScope.launch { c.gameRepository.clearSelection() }

    /** @return false if [input] is not a valid App ID. */
    fun addManual(input: String): Boolean {
        val appId = Game.parseAppId(input) ?: return false
        viewModelScope.launch { c.gameRepository.addManualGame(appId) }
        return true
    }

    fun removeManual(game: Game) = viewModelScope.launch { c.gameRepository.removeManualGame(game.appId) }

    private companion object {
        @Volatile var refreshedInProcess = false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel = viewModel(factory = appViewModelFactory(::LibraryViewModel))) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showAdd by rememberSaveable { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Toolbar(state, viewModel, onAdd = { showAdd = true })
        FilterRow(state.filter, viewModel::setFilter)
        StatusBanner(state)
        PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = Modifier.fillMaxSize()) {
            when {
                !state.loaded || (state.isRefreshing && state.totalGames == 0) -> PlaceholderList()
                state.totalGames == 0 -> CenteredText(stringResource(R.string.lib_empty))
                state.games.isEmpty() -> CenteredText(stringResource(R.string.lib_no_matches))
                state.layout == LibraryLayout.GRID -> LazyVerticalGrid(
                    columns = GridCells.Adaptive(160.dp),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.games, key = { it.appId }) { game -> GameGridItem(game, onToggle = { viewModel.toggle(game) }) }
                }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
                    items(state.games, key = { it.appId }) { game ->
                        GameListItem(game, onToggle = { viewModel.toggle(game) }, onRemove = { viewModel.removeManual(game) })
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddAppIdDialog(onAdd = viewModel::addManual, onDismiss = { showAdd = false })
    }
}

@Composable
private fun Toolbar(state: LibraryUiState, viewModel: LibraryViewModel, onAdd: () -> Unit) {
    var sortOpen by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = state.query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier.weight(1f),
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (state.query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Filled.Clear, contentDescription = stringResource(R.string.action_close)) }
                }
            },
            placeholder = { Text(stringResource(R.string.lib_search)) },
        )
        Box {
            IconButton(onClick = { sortOpen = true }) { Icon(Icons.Filled.Sort, contentDescription = stringResource(R.string.lib_sort)) }
            DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                listOf(
                    LibrarySort.NAME to R.string.lib_sort_name,
                    LibrarySort.PLAYTIME to R.string.lib_sort_playtime,
                    LibrarySort.RECENTLY_PLAYED to R.string.lib_sort_recent,
                ).forEach { (sort, label) ->
                    DropdownMenuItem(
                        text = { Text(stringResource(label) + if (sort == state.sort) "  ✓" else "") },
                        onClick = {
                            viewModel.setSort(sort)
                            sortOpen = false
                        },
                    )
                }
            }
        }
        IconButton(onClick = viewModel::toggleLayout) {
            if (state.layout == LibraryLayout.LIST) {
                Icon(Icons.Filled.GridView, contentDescription = stringResource(R.string.lib_layout_grid))
            } else {
                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.lib_layout_list))
            }
        }
        IconButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.lib_add_manual)) }
    }
    Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.lib_selected_count, state.selectedCount, Game.MAX_CONCURRENT_GAMES),
            style = MaterialTheme.typography.labelMedium,
            color = if (state.selectionLimitHit) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (state.selectedCount > 0) TextButton(onClick = viewModel::clearSelection) { Text(stringResource(R.string.lib_clear_selection)) }
    }
}

@Composable
private fun FilterRow(current: LibraryFilter, onSelect: (LibraryFilter) -> Unit) {
    val filters = listOf(
        LibraryFilter.ALL to R.string.lib_filter_all,
        LibraryFilter.SELECTED to R.string.lib_filter_selected,
        LibraryFilter.PLAYED to R.string.lib_filter_played,
        LibraryFilter.NEVER_PLAYED to R.string.lib_filter_never,
        LibraryFilter.MANUAL to R.string.lib_filter_manual,
    )
    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(filters) { (filter, label) ->
            FilterChip(selected = filter == current, onClick = { onSelect(filter) }, label = { Text(stringResource(label)) })
        }
    }
}

@Composable
private fun StatusBanner(state: LibraryUiState) {
    val colors = LocalStatusColors.current
    val (text, color) = when {
        state.error != null -> stringResource(R.string.lib_refresh_failed, steamErrorText(state.error)) to MaterialTheme.colorScheme.error
        state.refreshedThisSession && state.lastRefreshEpochMillis != null ->
            stringResource(R.string.lib_fresh, relativeTime(state.lastRefreshEpochMillis)) to colors.ok
        state.lastRefreshEpochMillis != null ->
            stringResource(R.string.lib_cached, relativeTime(state.lastRefreshEpochMillis)) to colors.warning
        !state.online -> stringResource(R.string.lib_offline) to colors.warning
        else -> stringResource(R.string.lib_never_refreshed) to colors.neutral
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

@Composable
private fun GameListItem(game: Game, onToggle: () -> Unit, onRemove: () -> Unit) {
    val selectLabel = stringResource(if (game.isSelected) R.string.lib_deselect_cd else R.string.lib_select_cd, game.name)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClickLabel = selectLabel, onClick = onToggle)
            .background(if (game.isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else androidx.compose.ui.graphics.Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .semantics { stateDescription = if (game.isSelected) "selected" else "not selected" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = game.iconUrl,
            contentDescription = null,
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(game.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(game), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (game.ownership == Game.Ownership.UNKNOWN) {
            TextButton(onClick = onRemove) { Text(stringResource(R.string.action_remove)) }
        }
        Icon(
            if (game.isSelected) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (game.isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

@Composable
private fun GameGridItem(game: Game, onToggle: () -> Unit) {
    val selectLabel = stringResource(if (game.isSelected) R.string.lib_deselect_cd else R.string.lib_select_cd, game.name)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClickLabel = selectLabel, onClick = onToggle)
            .semantics { stateDescription = if (game.isSelected) "selected" else "not selected" },
        colors = CardDefaults.cardColors(
            containerColor = if (game.isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Box {
            AsyncImage(
                model = game.headerImageUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(460f / 215f).background(MaterialTheme.colorScheme.surfaceVariant),
            )
            if (game.isSelected) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                )
            }
        }
        Column(Modifier.padding(8.dp)) {
            Text(game.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle(game), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun subtitle(game: Game): String = when {
    game.ownership == Game.Ownership.UNKNOWN -> stringResource(R.string.lib_app_id, game.appId) + " · " + stringResource(R.string.lib_manual_badge)
    game.playtimeForeverMinutes != null ->
        stringResource(R.string.lib_app_id, game.appId) + " · " + stringResource(R.string.lib_playtime, formatMinutes(game.playtimeForeverMinutes))
    else -> stringResource(R.string.lib_app_id, game.appId) + " · " + stringResource(R.string.lib_playtime_unknown)
}

@Composable
private fun PlaceholderList() {
    LazyColumn(Modifier.fillMaxSize()) {
        items(8) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
                Spacer(Modifier.width(12.dp))
                Column {
                    Box(Modifier.width(180.dp).height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                    Spacer(Modifier.height(6.dp))
                    Box(Modifier.width(110.dp).height(10.dp).background(MaterialTheme.colorScheme.surfaceVariant))
                }
            }
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    // Scrollable so pull-to-refresh still works on an empty list.
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(32.dp)) {
        item { Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun AddAppIdDialog(onAdd: (String) -> Boolean, onDismiss: () -> Unit) {
    var input by rememberSaveable { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    LaunchedEffect(input) { invalid = false }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.lib_add_manual)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { v -> input = v.filter(Char::isDigit).take(8) },
                    singleLine = true,
                    isError = invalid,
                    label = { Text(stringResource(R.string.lib_add_manual_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.semantics { contentDescription = "App ID" },
                )
                Text(stringResource(R.string.lib_add_manual_note), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (onAdd(input)) onDismiss() else invalid = true }) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
