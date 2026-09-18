package org.openscore.app.ui.detail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.openscore.app.data.ScoresRepository
import org.openscore.app.ui.common.TeamBadge
import org.openscore.app.ui.feed.TabularFigures
import org.openscore.model.League
import org.openscore.model.Sport
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.TeamRef
import org.openscore.provider.runCatchingUnlessCancelled

sealed class TableState {
    data object Loading : TableState()
    data class Loaded(val table: StandingsTable) : TableState()
    data class Error(val message: String) : TableState()
}

/** Scoped to the screen's back-stack entry: a team page opened from a row and closed again finds the table still loaded. */
class StandingsViewModel(private val repository: ScoresRepository, private val leagueId: String) : ViewModel() {
    private val mutableState = MutableStateFlow<TableState>(TableState.Loading)
    val state: StateFlow<TableState> = mutableState.asStateFlow()

    init { load() }

    fun load() {
        mutableState.value = TableState.Loading
        viewModelScope.launch {
            mutableState.value = runCatchingUnlessCancelled { repository.standings(leagueId) }
                .fold({ TableState.Loaded(it) }, { TableState.Error(it.message ?: "Could not load the table") })
        }
    }
}

/** One column of a table: its heading, how to read it off a row, and whether it is the one that decides the order. */
private class Column(val header: String, val bold: Boolean = false, val read: (StandingsRow) -> String)

private fun columnsFor(sport: Sport, table: StandingsTable): List<Column> {
    val sample = table.rows.firstOrNull()
    return when (sport) {
        Sport.FOOTBALL -> listOf(
            Column("P") { it.played.toString() },
            Column("W") { it.wins.toString() },
            Column("D") { it.draws?.toString() ?: "–" },
            Column("L") { it.losses.toString() },
            Column("GD") { it.goalDifference?.let { d -> if (d > 0) "+$d" else "$d" } ?: "–" },
            Column("Pts", bold = true) { it.points.toString() },
        )
        Sport.HOCKEY -> listOfNotNull(
            Column("GP") { it.played.toString() },
            Column("W") { it.wins.toString() },
            Column("L") { it.losses.toString() },
            if (sample?.otherLosses != null) Column("OTL") { it.otherLosses?.toString() ?: "–" } else null,
            Column("Pts", bold = true) { it.points.toString() },
        )
        Sport.BASEBALL -> listOfNotNull(
            Column("W") { it.wins.toString() },
            Column("L") { it.losses.toString() },
            if (sample?.extra?.containsKey("pct") == true) Column("PCT", bold = true) { it.extra["pct"] ?: "–" } else null,
            if (sample?.extra?.containsKey("gamesBack") == true) Column("GB") { it.extra["gamesBack"] ?: "–" } else null,
        )
        Sport.MOTORSPORT, Sport.MMA -> emptyList()
    }
}

private fun statWidth(count: Int): Dp = if (count >= 6) 26.dp else 40.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StandingsScreen(league: League, repository: ScoresRepository, onOpenTeam: (TeamRef) -> Unit, onBack: () -> Unit) {
    val vm: StandingsViewModel = viewModel { StandingsViewModel(repository, league.id) }
    val state by vm.state.collectAsStateWithLifecycle()

    DetailScaffold(title = league.name, onBack = onBack) { contentPadding ->
        when (val s = state) {
            TableState.Loading -> Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is TableState.Error -> Message(s.message, Modifier.padding(contentPadding))
            is TableState.Loaded -> {
                val table = s.table
                if (table.groups.isEmpty()) {
                    Message("No table for this competition.", Modifier.padding(contentPadding))
                } else {
                    val columns = columnsFor(league.sport, table)
                    LazyColumn(Modifier.fillMaxSize().padding(contentPadding), contentPadding = PaddingValues(bottom = 16.dp)) {
                        table.groups.forEach { group ->
                            stickyHeader(key = "head-${group.label}", contentType = "head") {
                                TableHeader(label = group.label.takeIf { table.groups.size > 1 }, columns = columns)
                            }
                            items(group.rows, key = { "${group.label}-${it.rank}-${it.team.id}" }, contentType = { "row" }) { row ->
                                TeamRow(row, columns, repository.hasTeamPage(row.team)) { onOpenTeam(row.team) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.padding(horizontal = 16.dp, vertical = 24.dp))
}

/** Pinned while the table scrolls, so the columns stay named on a long league. */
@Composable
private fun TableHeader(label: String?, columns: List<Column>) {
    androidx.compose.foundation.layout.Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
        label?.let {
            Text(it, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(24.dp + 20.dp + 16.dp))
            Text("Team", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            columns.forEach { Stat(it.header, statWidth(columns.size), header = true) }
        }
        HorizontalDivider()
    }
}

@Composable
private fun TeamRow(row: StandingsRow, columns: List<Column>, enabled: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClickLabel = "Open ${row.team.name} team page", onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(row.rank.toString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, modifier = Modifier.width(24.dp))
        Spacer(Modifier.width(8.dp))
        TeamBadge(row.team, 20.dp)
        Spacer(Modifier.width(8.dp))
        Text(row.team.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        columns.forEach { Stat(it.read(row), statWidth(columns.size), bold = it.bold) }
    }
}

@Composable
private fun Stat(text: String, width: Dp, header: Boolean = false, bold: Boolean = false) {
    Text(
        text = text,
        style = (if (header) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall).merge(TabularFigures),
        fontWeight = if (bold) FontWeight.Bold else null,
        color = if (header || !bold) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(width),
    )
}
