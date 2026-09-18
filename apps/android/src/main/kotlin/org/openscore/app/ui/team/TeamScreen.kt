package org.openscore.app.ui.team

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.datetime.toJavaLocalDate
import kotlinx.datetime.toLocalDateTime
import org.openscore.app.R
import org.openscore.app.data.Favorite
import org.openscore.app.data.ScoresRepository
import org.openscore.app.ui.common.TeamBadge
import org.openscore.app.ui.common.safeImageUrl
import org.openscore.app.ui.common.statusLabel
import org.openscore.app.ui.detail.DetailScaffold
import org.openscore.app.ui.feed.MatchCard
import org.openscore.app.ui.feed.StatusBadge
import org.openscore.app.ui.theme.scoreColors
import org.openscore.model.Game
import org.openscore.model.Player
import org.openscore.model.Sport
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.TeamRef
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock

private val gameDateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d MMM", Locale.getDefault())

/** A club's page. Its [TeamViewModel] is scoped to the screen's back-stack entry and goes with it. */
@Composable
fun TeamScreen(
    team: TeamRef,
    repository: ScoresRepository,
    favorites: Set<Favorite>,
    onToggleFavorite: (Favorite) -> Unit,
    onOpenGame: (Game) -> Unit,
    onOpenTeam: (TeamRef) -> Unit,
    onBack: () -> Unit,
) {
    val sport = repository.league(team.leagueId)?.sport ?: Sport.FOOTBALL
    val vm: TeamViewModel = viewModel { TeamViewModel(repository, team) }
    val today = vm.today
    val state by vm.state.collectAsStateWithLifecycle()
    // The page is about the club; the header names the league the profile and squad come from.
    val league = remember(state.home.leagueId) { repository.league(state.home.leagueId) }
    val season = remember(state.home.leagueId, today) { seasonWindow(state.home.leagueId, today).label }
    val canSchedule = state.games.supported
    val canStandings = state.standings.supported
    val canRoster = state.roster.supported
    val canStats = state.stats.supported
    val tabs = remember(canSchedule, canStandings, canRoster, canStats) {
        buildList {
            add(TeamTab.OVERVIEW)
            if (canSchedule) add(TeamTab.GAMES)
            if (canStandings) add(TeamTab.STANDINGS)
            if (canRoster) add(TeamTab.ROSTER)
            if (canStats) add(TeamTab.STATS)
        }
    }
    val scrollStates = TeamTab.entries.map { rememberLazyListState() }
    var tab by rememberSaveable(team.leagueId, team.id) { mutableStateOf(TeamTab.OVERVIEW) }
    val currentTab = tab.takeIf { it in tabs } ?: TeamTab.OVERVIEW
    var gameFilter by rememberSaveable(team.leagueId, team.id) { mutableStateOf(GameFilter.UPCOMING) }
    var now by remember { mutableStateOf(Clock.System.now()) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            now = Clock.System.now()
            vm.refresh()
            vm.refreshScores()
            var ticks = 0
            while (true) {
                delay(60_000)
                now = Clock.System.now()
                vm.refreshScores()
                if (++ticks % 5 == 0) vm.refresh()
            }
        }
    }
    val ref = state.profile.data?.ref ?: team
    val homeStandings = state.homeStandings
    val ownRow = homeStandings?.table?.rowFor(team)
    val homeGroup = homeStandings?.table?.groups?.firstOrNull { group -> group.rows.any { it.team.isSameClub(team) } }
    val games = state.games.data.orEmpty()
    val upcoming = teamGames(games, team, GameFilter.UPCOMING, now)
    val latest = teamGames(games, team, GameFilter.RESULTS, now)

    val favorite = Favorite.team(ref, sport)
    val following = favorites.any { it.key == favorite.key }
    DetailScaffold(
        title = ref.name,
        onBack = onBack,
        actions = { IconButton(onClick = vm::refresh) { Icon(Icons.Default.Refresh, contentDescription = "Refresh team page") } },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                val badge = if (dark) ref.copy(logoUrl = state.profile.data?.logoDarkUrl ?: ref.logoUrl) else ref
                TeamBadge(badge, 64.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("${league?.name ?: state.home.leagueId} · $season", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    val line = ownRow?.let { "${recordFor(it, sport)} · #${it.rank} in ${shortGroupName(homeGroup?.label.orEmpty())}" }
                        ?: state.profile.data?.division?.let(::shortGroupName)
                    line?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                // Following is the one thing to do with a team from here, so it sits in the header, not under it.
                FilledTonalIconToggleButton(checked = following, onCheckedChange = { onToggleFavorite(favorite) }) {
                    Icon(
                        painter = if (following) rememberVectorPainter(Icons.Default.Star) else painterResource(R.drawable.ic_star_outline),
                        contentDescription = if (following) "Unfollow ${ref.name}" else "Follow ${ref.name}",
                        tint = if (following) MaterialTheme.scoreColors.favorite else LocalContentColor.current,
                    )
                }
            }
            PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(currentTab), edgePadding = 0.dp) {
                tabs.forEach { item -> Tab(selected = currentTab == item, onClick = { tab = item }, text = { Text(item.label) }) }
            }
            // A separate scroll position per tab, preserved when switching between them.
            AnimatedContent(
                targetState = currentTab,
                modifier = Modifier.weight(1f),
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "team tab",
            ) { shownTab ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = scrollStates[shownTab.ordinal],
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (shownTab) {
                        TeamTab.OVERVIEW -> {
                            if (canStandings) {
                                item { SectionStatus(state.standings, "Season record", { vm.retry(TeamPart.STANDINGS) }) }
                                ownRow?.let { row -> item { RecordCard(row, sport) } }
                            }
                            if (canSchedule) {
                                item { SectionStatus(state.games, "Games", { vm.retry(TeamPart.GAMES) }) }
                                val featured = upcoming.firstOrNull { it.state.isLive } ?: upcoming.firstOrNull()
                                item {
                                    SectionTitle(if (featured?.state?.isLive == true) "Live now" else "Next game", "All games") { tab = TeamTab.GAMES }
                                    if (featured == null && !state.games.loading && state.games.error == null) Muted("No upcoming games scheduled for $season.")
                                    featured?.let { game ->
                                        Muted(game.dateLabel())
                                        MatchCard(game, sport, onClick = { onOpenGame(game) }, onLongClick = { onOpenGame(game) })
                                    }
                                }
                                val form = recentForm(games, team)
                                if (form.isNotEmpty()) item {
                                    TeamCard("Recent form") {
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            form.forEach { game -> ResultChip(game.resultFor(team)!!, Modifier.clickable(onClickLabel = "Open ${game.away.name} at ${game.home.name}") { onOpenGame(game) }) }
                                        }
                                        Muted("Last ${form.size} completed games · latest on right")
                                    }
                                }
                                if (latest.isNotEmpty()) {
                                    item { SectionTitle("Recent results", "View all") { gameFilter = GameFilter.RESULTS; tab = TeamTab.GAMES } }
                                    items(latest.take(3), key = { "recent-${it.leagueId}-${it.id}" }) { game -> TeamGameRow(game, team, sport, competitionLabel(game, state.home.leagueId, repository)) { onOpenGame(game) } }
                                }
                            }
                            // One card per competition the club is in: its own group of each table, home league first.
                            state.standings.data.orEmpty().forEach { entry ->
                                val group = entry.table.groups.firstOrNull { g -> g.rows.any { it.team.isSameClub(team) } } ?: return@forEach
                                item(key = "table-${entry.league.id}") {
                                    TeamCard(tableTitle(entry, group, single = state.standings.data?.size == 1)) {
                                        DivisionTable(group, team, sport, onOpenTeam)
                                        TextButton(onClick = { tab = TeamTab.STANDINGS }) { Text("All standings") }
                                    }
                                }
                            }
                            item { SectionStatus(state.profile, "Team details", { vm.retry(TeamPart.PROFILE) }) }
                            state.profile.data?.let { profile -> item {
                                TeamCard("Team details") {
                                    profile.arena?.let { InfoRow("Home venue", it) }
                                    profile.placeName?.let { InfoRow("Location", it) }
                                    profile.conference?.let { InfoRow("League", it) }
                                    profile.division?.let { InfoRow("Division", shortGroupName(it)) }
                                }
                            } }
                        }
                        TeamTab.GAMES -> {
                            item {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(GameFilter.entries) { filter -> FilterChip(selected = gameFilter == filter, onClick = { gameFilter = filter }, label = { Text(filter.label) }) }
                                }
                                Muted("$season season · times are local")
                            }
                            item { SectionStatus(state.games, "Games", { vm.retry(TeamPart.GAMES) }) }
                            val shown = teamGames(games, team, gameFilter, now)
                            if (shown.isEmpty() && !state.games.loading && state.games.error == null) item { Muted("No ${gameFilter.label.lowercase()} for $season.") }
                            items(shown, key = { "${it.leagueId}-${it.id}" }) { game -> TeamGameRow(game, team, sport, competitionLabel(game, state.home.leagueId, repository)) { onOpenGame(game) } }
                        }
                        TeamTab.STANDINGS -> {
                            item { Muted("$season season standings") }
                            item { SectionStatus(state.standings, "Standings", { vm.retry(TeamPart.STANDINGS) }) }
                            val entries = state.standings.data.orEmpty()
                            if (entries.isEmpty() && !state.standings.loading && state.standings.error == null) item { Muted("Standings aren't available yet.") }
                            entries.forEach { entry ->
                                val groups = entry.table.groups.sortedBy { g -> if (g.rows.any { it.team.isSameClub(team) }) 0 else 1 }
                                items(groups, key = { "${entry.league.id}-${it.label}" }) { group -> TeamCard(tableTitle(entry, group, single = entries.size == 1)) { DivisionTable(group, entry.team, sport, onOpenTeam) } }
                            }
                            item { Muted(standingsLegend(sport)) }
                        }
                        TeamTab.ROSTER -> {
                            item { Muted("Roster${state.roster.data?.let { " · ${it.size} players" }.orEmpty()}") }
                            item { SectionStatus(state.roster, "Roster", { vm.retry(TeamPart.ROSTER) }) }
                            val roster = state.roster.data.orEmpty()
                            if (roster.isEmpty() && !state.roster.loading && state.roster.error == null) item { Muted("No active roster available.") }
                            val groups = when (sport) {
                                Sport.BASEBALL -> roster.groupBy(::rosterGroup)
                                Sport.FOOTBALL -> roster.groupBy(::footballRosterGroup)
                                else -> roster.groupBy { it.ref.position ?: "Players" }
                            }
                            val groupOrder = when (sport) {
                                Sport.BASEBALL -> listOf("Pitchers", "Catchers", "Infielders", "Outfielders", "Designated hitters", "Two-way players", "Other players")
                                Sport.FOOTBALL -> FOOTBALL_ROSTER_ORDER
                                else -> groups.keys.sorted()
                            }
                            groupOrder.forEach { group ->
                                groups[group]?.let { players ->
                                    item(key = group) { SectionTitle("$group · ${players.size}") }
                                    // Providers should supply one row per player, but keeping the
                                    // visible row index in the key also prevents malformed remote
                                    // data from taking down a team page.
                                    itemsIndexed(players.sortedBy { it.name }, key = { index, player -> "player-$group-${player.id}-$index" }) { _, player -> PlayerRow(player) }
                                }
                            }
                        }
                        TeamTab.STATS -> {
                            item { Muted("$season season · team totals") }
                            item { SectionStatus(state.stats, "Statistics", { vm.retry(TeamPart.STATS) }) }
                            val groups = state.stats.data?.groups.orEmpty()
                            if (groups.isEmpty() && !state.stats.loading && state.stats.error == null) item { Muted("Season statistics aren't available yet.") }
                            items(groups, key = { it.key }) { group ->
                                TeamCard(group.label) {
                                    group.stats.forEachIndexed { index, stat ->
                                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                        InfoRow(stat.label, stat.value)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun shortGroupName(label: String): String = label.replace("American League", "AL").replace("National League", "NL")

/** A single-competition club keeps the plain group name; with several, each card says which competition it is. */
private fun tableTitle(entry: LeagueStandings, group: StandingsGroup, single: Boolean): String {
    val groupName = shortGroupName(group.label)
    if (single) return groupName
    return if (groupName.equals(entry.league.name, ignoreCase = true) || entry.table.groups.size == 1) entry.league.name else "${entry.league.name} · $groupName"
}

/** Under a game row: the competition when it is not the club's home league, or the round the feed names. */
private fun competitionLabel(game: Game, homeLeagueId: String, repository: ScoresRepository): String? {
    val leagueName = repository.league(game.leagueId)?.name
    val competition = game.competition?.takeIf { c -> leagueName == null || !c.startsWith(leagueName) }
    return when {
        game.leagueId != homeLeagueId -> listOfNotNull(leagueName ?: game.leagueId, competition).joinToString(" · ")
        else -> competition
    }
}

private fun recordFor(row: StandingsRow, sport: Sport): String = when (sport) {
    Sport.FOOTBALL -> "${row.wins}–${row.draws ?: 0}–${row.losses}"
    else -> "${row.wins}–${row.losses}"
}

private fun standingsLegend(sport: Sport): String = when (sport) {
    Sport.FOOTBALL -> "W wins · D draws · L losses · PTS points"
    Sport.HOCKEY -> "W wins · L losses · OTL overtime/shootout losses · PTS points"
    Sport.BASEBALL -> "W wins · L losses · PCT win percentage · GB games behind"
    Sport.MOTORSPORT -> ""
}
private fun Game.dateLabel(): String = gameDateFormat.format((scheduleDate ?: startTime.toLocalDateTime(leagueTimeZone(leagueId)).date).toJavaLocalDate())


@Composable
private fun SectionTitle(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun TeamCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            content()
        }
    }
}

@Composable
private fun <T> SectionStatus(section: TeamSection<T>, label: String, onRetry: () -> Unit) {
    AnimatedVisibility(
        visible = section.loading,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Muted("Loading ${label.lowercase()}…")
        }
    }
    section.error?.let { error ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$label: $error", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f))
            TextButton(onClick = onRetry) { Text("Retry") }
        }
    }
}

/** Baseball reads its record as a percentage and a streak; the goal sports as points and a goal difference. */
private fun recordTiles(row: StandingsRow, sport: Sport): List<Pair<String, String>> = when (sport) {
    Sport.BASEBALL -> listOf("Record" to recordFor(row, sport), "Win %" to (row.extra["pct"] ?: "–"), "Streak" to (row.extra["streak"] ?: "–"))
    else -> listOf(
        "Record" to recordFor(row, sport),
        "Points" to row.points.toString(),
        "Goal diff" to (row.goalDifference ?: (row.goalsFor?.let { gf -> row.goalsAgainst?.let { ga -> gf - ga } }))?.let { if (it > 0) "+$it" else it.toString() }.orEmpty().ifEmpty { "–" },
    )
}

@Composable
private fun RecordCard(row: StandingsRow, sport: Sport) {
    TeamCard("Season record") {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            recordTiles(row, sport).forEach { (label, value) ->
                Column {
                    Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Muted(label)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            listOf("home" to "Home", "away" to "Away", "last10" to "Last 10", "form" to "Form").forEach { (key, label) -> row.extra[key]?.let { Muted("$label $it") } }
            if (sport != Sport.BASEBALL) Muted("Played ${row.played}")
        }
    }
}

@Composable
private fun ResultChip(result: String, modifier: Modifier = Modifier) {
    val win = result == "W"
    val background = if (win) MaterialTheme.colorScheme.primaryContainer else if (result == "L") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
    val foreground = if (win) MaterialTheme.colorScheme.onPrimaryContainer else if (result == "L") MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Box(modifier.size(32.dp).clip(CircleShape).background(background).semantics { contentDescription = when (result) { "W" -> "Win"; "L" -> "Loss"; else -> "Tie" } }, contentAlignment = Alignment.Center) {
        Text(result, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = foreground)
    }
}

@Composable
private fun TeamGameRow(game: Game, team: TeamRef, sport: Sport, competition: String?, onClick: () -> Unit) {
    val home = game.home.isSameClub(team)
    val opponent = if (home) game.away else game.home
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Muted(game.dateLabel())
            StatusBadge(game.statusLabel(sport))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (home) "vs" else "@", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
            TeamBadge(opponent, 28.dp)
            Spacer(Modifier.width(8.dp))
            Text(opponent.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, modifier = Modifier.weight(1f))
            game.score?.let { score ->
                Text(if (home) "${score.home}–${score.away}" else "${score.away}–${score.home}", fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 8.dp))
            }
            game.resultFor(team)?.let { ResultChip(it) }
        }
        competition?.let { Muted(it) }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
private fun DivisionTable(group: StandingsGroup, selected: TeamRef, sport: Sport, onOpenTeam: (TeamRef) -> Unit) {
    val columns = when (sport) {
        Sport.FOOTBALL -> listOf("W", "D", "L", "PTS")
        Sport.HOCKEY -> listOf("W", "L", "OTL", "PTS")
        Sport.BASEBALL -> listOf("W", "L", "PCT", "GB")
        Sport.MOTORSPORT -> emptyList()
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Team", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        columns.forEach { TableValue(it) }
    }
    group.rows.forEach { row ->
        val isSelected = row.team.isSameClub(selected)
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small)
                .background(if (isSelected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow)
                .clickable(enabled = !isSelected) { onOpenTeam(row.team) }.padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(row.rank.toString(), style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(16.dp), textAlign = TextAlign.Center)
            TeamBadge(row.team, 20.dp)
            Text(row.team.abbreviation ?: row.team.name, style = MaterialTheme.typography.labelMedium, fontWeight = if (isSelected) FontWeight.Bold else null, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
            val values = when (sport) {
                Sport.FOOTBALL -> listOf(row.wins.toString(), row.draws?.toString() ?: "–", row.losses.toString(), row.points.toString())
                Sport.HOCKEY -> listOf(row.wins.toString(), row.losses.toString(), row.otherLosses?.toString() ?: "–", row.points.toString())
                Sport.BASEBALL -> listOf(row.wins.toString(), row.losses.toString(), row.extra["pct"] ?: "–", row.extra["gamesBack"] ?: "–")
                Sport.MOTORSPORT -> emptyList()
            }
            values.forEach { TableValue(it) }
        }
    }
}

@Composable
private fun TableValue(value: String) {
    Text(value, style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.width(38.dp))
}

@Composable
private fun PlayerRow(player: Player) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(safeImageUrl(player.ref.headshotUrl), contentDescription = null, modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(player.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            player.ref.position?.let { Muted(it) }
        }
        player.ref.jerseyNumber?.let { Muted("#$it") }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun Muted(text: String) { Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
