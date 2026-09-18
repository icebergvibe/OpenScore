package org.openscore.app.ui.f1

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toJavaLocalDateTime
import kotlinx.datetime.toLocalDateTime
import org.openscore.app.data.ScoresRepository
import org.openscore.model.motorsport.RacingRound
import org.openscore.model.motorsport.RacingSession
import org.openscore.model.motorsport.RacingSessionKind
import org.openscore.model.motorsport.RacingStandings
import org.openscore.provider.runCatchingUnlessCancelled
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.time.Clock

/** F1's own calendar/classification surface. Jolpica does not claim to provide live timing. */
@Composable
fun F1Screen(repository: ScoresRepository, topContent: @Composable () -> Unit = {}) {
    val year = Clock.System.now().toString().take(4).toInt()
    var rounds by remember { mutableStateOf<List<RacingRound>?>(null) }
    var standings by remember { mutableStateOf<RacingStandings?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showConstructors by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun load() {
        error = null
        runCatchingUnlessCancelled { repository.f1Season(year) }.onSuccess { rounds = it.rounds }.onFailure { error = it.message ?: "Could not load Formula 1" }
        runCatchingUnlessCancelled { repository.f1Standings(year) }.onSuccess { standings = it }
    }
    LaunchedEffect(year) { load() }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Column { Text("Formula 1", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold); Text("$year season · schedules and classifications", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            IconButton(onClick = {
                rounds = null
                standings = null
                scope.launch { load() }
            }) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        topContent()
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            item {
                Text("Championship", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!showConstructors, { showConstructors = false }, { Text("Drivers") })
                    FilterChip(showConstructors, { showConstructors = true }, { Text("Constructors") })
                }
                val rows = if (showConstructors) standings?.constructors else standings?.drivers
                rows?.take(5)?.forEach { row -> Text("${row.position}. ${row.name} · ${row.points} pts${row.detail?.let { " · $it" }.orEmpty()}", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            item {
                Text("Calendar", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Times are shown in your timezone", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(rounds.orEmpty(), key = { it.id }) { round -> RoundCard(round, repository, year) }
        }
    }
}

@Composable private fun RoundCard(round: RacingRound, repository: ScoresRepository, year: Int) {
    var classification by remember(round.id) { mutableStateOf<List<String>?>(null) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text("${round.number}. ${round.name}", fontWeight = FontWeight.SemiBold)
            Text(listOfNotNull(round.circuit, round.locality, round.country).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            round.sessions.forEach { session ->
                Text("${session.name} · ${session.localStartLabel()}", Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodySmall)
            }
            val resultSession = round.sessions.lastOrNull { it.kind in setOf(RacingSessionKind.RACE, RacingSessionKind.QUALIFYING, RacingSessionKind.SPRINT) }
            if (resultSession != null) {
                FilterChip(classification != null, { if (classification == null) { classification = emptyList() } }, { Text(if (classification == null) "Show latest classification" else "Classification") }, modifier = Modifier.padding(top = 8.dp))
                if (classification != null) LaunchedEffect(round.id) { classification = runCatchingUnlessCancelled { repository.f1Classification(year, round.number, resultSession.kind).entries.take(10).map { "${it.positionText ?: "–"}. ${it.driver}${it.constructor?.let { " · $it" }.orEmpty()}${it.points?.let { " · $it pts" }.orEmpty()}" } }.getOrDefault(emptyList()) }
                classification?.forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) }
            }
        }
    }
}

private fun RacingSession.localStartLabel(): String {
    val local = startsAt.toLocalDateTime(TimeZone.currentSystemDefault()).toJavaLocalDateTime()
    return DateTimeFormatter.ofPattern("EEE, d MMM · HH:mm", Locale.getDefault()).format(local)
}
