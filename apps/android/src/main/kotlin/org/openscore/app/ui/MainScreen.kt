package org.openscore.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.openscore.app.OpenScoreApp
import org.openscore.app.R
import org.openscore.app.alerts.GameLink
import org.openscore.app.data.FavoriteFilter
import org.openscore.app.data.FavoritesStore
import org.openscore.app.data.ScoresRepository
import org.openscore.app.data.SessionPhase
import org.openscore.app.ui.common.displayName
import org.openscore.app.ui.common.iconRes
import org.openscore.app.ui.detail.MatchScreen
import org.openscore.app.ui.detail.StandingsScreen
import org.openscore.app.ui.f1.F1Screen
import org.openscore.app.ui.feed.DayState
import org.openscore.app.ui.feed.FeedKind
import org.openscore.app.ui.feed.FeedScreen
import org.openscore.app.ui.feed.FeedSpec
import org.openscore.app.ui.feed.FeedViewModel
import org.openscore.app.ui.filter.FilterSheet
import org.openscore.app.ui.nav.GameSeeds
import org.openscore.app.ui.nav.HomeKey
import org.openscore.app.ui.nav.MatchKey
import org.openscore.app.ui.nav.Navigator
import org.openscore.app.ui.nav.TableKey
import org.openscore.app.ui.nav.TeamKey
import org.openscore.app.ui.settings.SettingsScreen
import org.openscore.app.ui.team.TeamScreen
import org.openscore.model.Sport
import org.openscore.model.TeamRef
import kotlin.time.Clock

/** The four things the bottom bar switches between. */
enum class Destination(val label: String) {
    GAMES("Scores"),
    LIVE("Live"),
    FAVORITES("Following"),
    SETTINGS("Settings"),
}

/** Which sport the rail is on, and which of its leagues are ticked (none ticked = all of them). */
data class Selection(val sport: Sport = Sport.FOOTBALL, val leagueIds: Set<String> = emptySet()) {
    fun onSport(next: Sport): Selection = if (next == sport) this else Selection(next)
    fun withLeague(id: String, on: Boolean): Selection = copy(leagueIds = if (on) leagueIds + id else leagueIds - id)

    companion object {
        val Saver = listSaver<Selection, String>(
            save = { listOf(it.sport.name) + it.leagueIds },
            restore = { Selection(Sport.valueOf(it.first()), it.drop(1).toSet()) },
        )
    }
}

private const val PUSH_MS = 260
private const val POP_MS = 220

/**
 * One back stack for the whole app: the tab scaffold at the root, then the match, table and
 * team screens in the order they were opened. Every screen lives in the activity's window,
 * so pushes slide in from the right, pops slide back out, and the predictive-back gesture
 * previews the screen underneath. [link] is the game a notification tap asked for; it is
 * opened over a cleared stack and [onLinkHandled] is called.
 */
@Composable
fun MainScreen(darkMode: Boolean?, onDarkModeChange: (Boolean?) -> Unit, link: GameLink? = null, onLinkHandled: () -> Unit = {}) {
    val app = OpenScoreApp.from(LocalContext.current)
    val repository = app.repository
    val favoritesStore = app.favorites
    val favorites by favoritesStore.favorites.collectAsStateWithLifecycle()
    // Activity-scoped: the feed's held days and the seeds outlive any one screen.
    val feedViewModel: FeedViewModel = viewModel { FeedViewModel(repository) }
    val seeds: GameSeeds = viewModel()
    val backStack = rememberNavBackStack(HomeKey)
    val navigator = remember(backStack, seeds) { Navigator(backStack, seeds) }
    // Every team tap goes through here: a club with no provider behind it has no page to open.
    val openTeam: (TeamRef) -> Unit = { team -> if (repository.hasTeamPage(team)) navigator.openTeam(team) }

    LaunchedEffect(link) {
        if (link == null) return@LaunchedEffect
        navigator.openGame(MatchKey(link.leagueId, link.gameId, link.date))
        onLinkHandled()
    }

    NavDisplay(
        backStack = backStack,
        onBack = navigator::back,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = {
            // The new screen slides in over the old one, which stays put and dims.
            ContentTransform(
                targetContentEnter = slideInHorizontally(tween(PUSH_MS)) { it / 4 } + fadeIn(tween(PUSH_MS)),
                initialContentExit = fadeOut(tween(PUSH_MS, delayMillis = 60)),
            )
        },
        popTransitionSpec = {
            // The closing screen slides back out to the right, above the one it uncovers.
            ContentTransform(
                targetContentEnter = fadeIn(tween(POP_MS)),
                initialContentExit = slideOutHorizontally(tween(POP_MS)) { it / 4 } + fadeOut(tween(POP_MS)),
                targetContentZIndex = -1f,
            )
        },
        predictivePopTransitionSpec = {
            ContentTransform(
                targetContentEnter = fadeIn(tween(POP_MS)),
                initialContentExit = slideOutHorizontally(tween(POP_MS)) { it / 4 } + fadeOut(tween(POP_MS)),
                targetContentZIndex = -1f,
            )
        },
        entryProvider = entryProvider {
            entry<HomeKey> {
                HomeScreen(
                    repository = repository,
                    favoritesStore = favoritesStore,
                    feedViewModel = feedViewModel,
                    navigator = navigator,
                    darkMode = darkMode,
                    onDarkModeChange = onDarkModeChange,
                )
            }
            entry<MatchKey> { key ->
                MatchScreen(
                    key = key,
                    seed = seeds.get(key),
                    repository = repository,
                    favorites = favorites,
                    onToggleFavorite = favoritesStore::toggle,
                    onOpenTeam = openTeam,
                    onBack = navigator::back,
                )
            }
            entry<TableKey> { key ->
                val league = repository.league(key.leagueId)
                if (league != null) StandingsScreen(league = league, repository = repository, onOpenTeam = openTeam, onBack = navigator::back)
                else LaunchedEffect(key) { navigator.back() }
            }
            entry<TeamKey> { key ->
                TeamScreen(
                    team = key.toRef(),
                    repository = repository,
                    favorites = favorites,
                    onToggleFavorite = favoritesStore::toggle,
                    onOpenTeam = openTeam,
                    onOpenGame = navigator::openGame,
                    onBack = navigator::back,
                )
            }
        },
    )
}

/** The root: bottom bar, sport rail, the feeds, settings, and the filter sheet over them. */
@Composable
private fun HomeScreen(
    repository: ScoresRepository,
    favoritesStore: FavoritesStore,
    feedViewModel: FeedViewModel,
    navigator: Navigator,
    darkMode: Boolean?,
    onDarkModeChange: (Boolean?) -> Unit,
) {
    val app = OpenScoreApp.from(LocalContext.current)
    val favorites by favoritesStore.favorites.collectAsStateWithLifecycle()
    val alerts by app.alerts.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var cacheBytes by remember { mutableStateOf(0L) }

    // Keep the app's starting place stable. Following remains one tap away even when populated.
    var destination by rememberSaveable { mutableStateOf(Destination.GAMES) }
    LaunchedEffect(destination) {
        if (destination == Destination.SETTINGS) cacheBytes = repository.cacheSizeBytes()
    }
    // Saved with the screen: the root leaves composition under a detail and must come back on the same sport.
    var selection by rememberSaveable(stateSaver = Selection.Saver) { mutableStateOf(Selection()) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var showFilters by remember { mutableStateOf(false) }

    val favoriteFilter = remember(favorites) { FavoriteFilter(favorites) }
    val gamesSpec = remember(selection) {
        val chosen = repository.leagues(selection.sport).map { it.id }.filter { selection.leagueIds.isEmpty() || it in selection.leagueIds }
        FeedSpec(FeedKind.GAMES, selection.sport, chosen)
    }
    val spec = remember(destination, gamesSpec, favoriteFilter) {
        when (destination) {
            Destination.FAVORITES -> FeedSpec(FeedKind.FAVORITES, null, favoriteFilter.leaguesToFetch(repository.leagues))
            Destination.LIVE -> gamesSpec.copy(kind = FeedKind.LIVE)
            else -> gamesSpec
        }
    }
    // The Live tab's badge: what is in play today in the rail's sport, read off the day Scores already holds.
    val heldDays by remember(gamesSpec.fetchKey) { feedViewModel.days(gamesSpec) }.collectAsStateWithLifecycle()
    val liveCount = (heldDays[Clock.System.todayIn(TimeZone.currentSystemDefault())] as? DayState.Loaded)?.let { day ->
        val now = Clock.System.now()
        day.games.count { it.state.isLive } + day.sessions.count { it.phase(now) == SessionPhase.UNDER_WAY }
    } ?: 0

    Scaffold(
        bottomBar = {
            BottomBar(current = destination, liveCount = liveCount, onSelect = { picked ->
                // Tapping the tab you are on clears what is narrowing the list.
                if (picked == destination && picked == Destination.GAMES) searchQuery = ""
                destination = picked
            })
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (destination) {
                Destination.SETTINGS -> SettingsScreen(
                    favorites = favorites,
                    darkMode = darkMode,
                    onDarkModeChange = onDarkModeChange,
                    onRemoveFavorite = favoritesStore::remove,
                    alerts = alerts,
                    onToggleAlert = app.alerts::toggle,
                    onAlertsChange = app.alerts::update,
                    leagues = repository.leagues,
                    cacheBytes = cacheBytes,
                    onClearCache = {
                        scope.launch {
                            repository.clearCache()
                            cacheBytes = 0L
                        }
                    },
                )
                // Racing has no two-team games: Scores is the season view; Live and Following list its sessions.
                else -> if (selection.sport == Sport.MOTORSPORT && destination == Destination.GAMES) F1Screen(
                    repository = repository,
                    topContent = { SportRail(selected = selection.sport, onSelect = { selection = selection.onSport(it) }) },
                ) else FeedScreen(
                    spec = spec,
                    title = when (destination) {
                        Destination.FAVORITES -> "Following"
                        else -> if (selection.leagueIds.isEmpty()) "All ${selection.sport.displayName.lowercase()}" else
                            selection.leagueIds.mapNotNull { repository.league(it)?.name }.joinToString(", ")
                    },
                    topContent = if (destination == Destination.GAMES || destination == Destination.LIVE) {
                        { SportRail(selected = selection.sport, onSelect = { selection = selection.onSport(it) }) }
                    } else null,
                    searchQuery = searchQuery,
                    filterCount = if (destination == Destination.FAVORITES) 0 else selection.leagueIds.size,
                    favoriteFilter = if (destination == Destination.FAVORITES) favoriteFilter else null,
                    hasFavorites = favorites.isNotEmpty(),
                    favorites = favorites,
                    viewModel = feedViewModel,
                    repository = repository,
                    onOpenFilters = { showFilters = true },
                    onClearSearch = { searchQuery = "" },
                    onOpenGame = navigator::openGame,
                    onOpenStandings = navigator::openTable,
                    onToggleFavorite = favoritesStore::toggle,
                )
            }
        }
    }

    if (showFilters) {
        FilterSheet(
            selection = selection,
            query = searchQuery,
            leaguesBySport = Sport.entries.associateWith { repository.leagues(it) },
            favorites = favorites,
            onSelectionChange = {
                selection = it
                if (destination == Destination.FAVORITES) destination = Destination.GAMES
            },
            onQueryChange = { searchQuery = it },
            onToggleFavorite = favoritesStore::toggle,
            onDismiss = { showFilters = false },
        )
    }
}

@Composable
private fun BottomBar(current: Destination, liveCount: Int, onSelect: (Destination) -> Unit) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
        Destination.entries.forEach { destination ->
            NavigationBarItem(
                selected = destination == current,
                onClick = { onSelect(destination) },
                icon = {
                    val icon: @Composable () -> Unit = {
                        when (destination) {
                            Destination.LIVE -> Icon(painter = painterResource(R.drawable.ic_live), contentDescription = null, modifier = Modifier.size(22.dp))
                            else -> Icon(
                                imageVector = when (destination) {
                                    Destination.FAVORITES -> Icons.Default.Star
                                    Destination.SETTINGS -> Icons.Default.Settings
                                    else -> Icons.Default.DateRange
                                },
                                contentDescription = null,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                    // How many games are in play right now, before the tab is opened.
                    if (destination == Destination.LIVE && liveCount > 0) {
                        BadgedBox(badge = { Badge { Text("$liveCount") } }) { icon() }
                    } else icon()
                },
                label = { Text(destination.label, maxLines = 1) },
            )
        }
    }
}

/** The sport switcher: one chip per sport the core covers. */
@Composable
private fun SportRail(selected: Sport, onSelect: (Sport) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(Sport.entries, key = { it.name }) { sport ->
            FilterChip(
                selected = sport == selected,
                onClick = { onSelect(sport) },
                leadingIcon = {
                    Icon(painter = painterResource(sport.iconRes), contentDescription = null, modifier = Modifier.size(18.dp))
                },
                label = { Text(sport.displayName, maxLines = 1) },
            )
        }
    }
}
