package org.openscore.app.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.openscore.app.R
import org.openscore.app.data.Favorite
import org.openscore.app.data.ScoresRepository
import org.openscore.app.ui.common.groupSubtitle
import org.openscore.app.ui.common.TeamBadge
import org.openscore.app.ui.common.groupTitle
import org.openscore.app.ui.common.scoreLabel
import org.openscore.app.ui.common.statusLabel
import org.openscore.app.ui.feed.StatusBadge
import org.openscore.app.ui.feed.TabularFigures
import org.openscore.app.ui.nav.MatchKey
import org.openscore.app.ui.theme.scoreColors
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.Lineup
import org.openscore.model.PeriodScore
import org.openscore.model.Sport
import org.openscore.model.StatPair
import org.openscore.model.TeamRef
import org.openscore.model.baseball.BaseballSituation
import org.openscore.model.combat.FightMethod
import org.openscore.model.combat.FightSituation
import org.openscore.model.scoreboardPresentation

private val SECTION_SPACING = 16.dp

/**
 * A match, opened from its card or by id from a notification. Its state lives in a
 * [MatchViewModel] scoped to this screen's back-stack entry, so a team page opened from here
 * and closed again finds the match as it was. Live updates follow the screen's lifecycle.
 */
@Composable
fun MatchScreen(
    key: MatchKey,
    seed: Game?,
    repository: ScoresRepository,
    favorites: Set<Favorite>,
    onToggleFavorite: (Favorite) -> Unit,
    onOpenTeam: (TeamRef) -> Unit,
    onBack: () -> Unit,
) {
    val vm: MatchViewModel = viewModel { MatchViewModel(repository, key, seed) }
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { vm.followLive() }
    }

    val league = repository.league(key.leagueId)
    val sport = league?.sport ?: Sport.FOOTBALL
    val title = groupTitle(league, key.leagueId)
    val current = state.game
    val leagueFavorite = league?.let(Favorite::league)
    DetailScaffold(
        title = title,
        onBack = onBack,
        actions = {
            // The title is the competition, so its star lives up here rather than in a third chip below.
            if (leagueFavorite != null) {
                val following = favorites.any { it.key == leagueFavorite.key }
                IconButton(onClick = { onToggleFavorite(leagueFavorite) }) {
                    Icon(
                        painter = if (following) rememberVectorPainter(Icons.Default.Star) else painterResource(R.drawable.ic_star_outline),
                        contentDescription = if (following) "Unfollow ${leagueFavorite.label}" else "Follow ${leagueFavorite.label}",
                        tint = if (following) MaterialTheme.scoreColors.favorite else LocalContentColor.current,
                    )
                }
            }
        },
    ) { contentPadding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) {
                val error = state.error
                if (error != null) Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else CircularProgressIndicator()
            }
            return@DetailScaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(contentPadding).padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = SECTION_SPACING),
            verticalArrangement = Arrangement.spacedBy(SECTION_SPACING),
        ) {
            item { Header(current, sport, competition = groupSubtitle(league, current.competition), canOpenTeam = repository::hasTeamPage, onOpenTeam = onOpenTeam) }

            item {
                FollowRow(
                    options = listOf(Favorite.team(current.home, sport), Favorite.team(current.away, sport)),
                    favorites = favorites,
                    onToggleFavorite = onToggleFavorite,
                )
            }

            if (state.loading) item(key = "detail-loading") {
                Box(Modifier.fillMaxWidth().animateItem(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            state.error?.let { item { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }

            // What happened first — the score by period, then the goals and cards — and only
            // then who played and the numbers behind it; a finished match is opened for its goals.
            if (current.periodScores.isNotEmpty()) item { PeriodScoresTable(current, sport) }

            (current.situation as? BaseballSituation)?.takeIf { current.state.isLive }?.let { s -> item { SituationCard(s) } }
            (current.situation as? FightSituation)?.let { s -> item { FightSection(current, s) } }

            val keyEvents = state.events?.filter { it.isKeyEvent() }.orEmpty()
            if (keyEvents.isNotEmpty()) item { EventsSection(current, keyEvents, sport) }

            if (current.stats.isNotEmpty()) item { StatsSection(current) }

            // Before kick-off the probable pitchers are the preview; afterwards the credits are a footnote.
            val participants = current.preview != null || current.credits.isNotEmpty()
            if (participants && !current.state.hasStarted) item { MatchParticipants(current) }

            val lu = state.lineups
            if (!lu.isNullOrEmpty()) item { LineupsSection(current, lu, sport) }

            if (participants && current.state.hasStarted) item { MatchParticipants(current) }
        }
    }
}

@Composable
private fun Header(game: Game, sport: Sport, competition: String?, canOpenTeam: (TeamRef) -> Boolean, onOpenTeam: (TeamRef) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        competition?.let { Text(text = it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            TeamColumn(game.home, canOpenTeam(game.home), onOpenTeam)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(text = game.scoreLabel(), style = MaterialTheme.typography.headlineLarge.merge(TabularFigures), fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                StatusBadge(game.statusLabel(sport))
            }
            TeamColumn(game.away, canOpenTeam(game.away), onOpenTeam)
        }
        game.venue?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun TeamColumn(team: TeamRef, enabled: Boolean, onOpenTeam: (TeamRef) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(100.dp).clickable(enabled = enabled, onClickLabel = "Open ${team.name} team page") { onOpenTeam(team) }) {
        TeamBadge(team, size = 56.dp)
        Spacer(Modifier.height(4.dp))
        Text(text = team.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center, maxLines = 2)
        if (enabled) Text("Team page ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
}

/** Following from here rather than only by long-press: by now you know whether you care. One chip per side, sharing the row. */
@Composable
private fun FollowRow(options: List<Favorite>, favorites: Set<Favorite>, onToggleFavorite: (Favorite) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { favorite ->
            val selected = favorites.any { it.key == favorite.key }
            FilterChip(
                selected = selected,
                onClick = { onToggleFavorite(favorite) },
                modifier = Modifier.weight(1f),
                leadingIcon = { Icon(Icons.Default.Star, contentDescription = null, modifier = Modifier.size(16.dp)) },
                label = { Text(favorite.label, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.scoreColors.favorite.copy(alpha = 0.14f),
                    selectedLabelColor = MaterialTheme.scoreColors.favorite,
                    selectedLeadingIconColor = MaterialTheme.scoreColors.favorite,
                ),
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}

/** Which side is which, repeated at the top of each section far from the header. */
@Composable
private fun TeamSidesRow(game: Game) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            TeamBadge(game.home, 16.dp)
            Spacer(Modifier.width(4.dp))
            Text(game.home.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Text(game.away.name, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.End, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(4.dp))
            TeamBadge(game.away, 16.dp)
        }
    }
}

/** Periods across, the two sides down, the total last. Baseball's visitors bat first and so go on top. */
@Composable
private fun PeriodScoresTable(game: Game, sport: Sport) {
    val scoreboard = game.scoreboardPresentation(sport)
    val periods = scoreboard.periods
    val awayFirst = sport == Sport.BASEBALL
    val rows = if (awayFirst) listOf(game.away to { p: PeriodScore -> p.away }, game.home to { p: PeriodScore -> p.home })
    else listOf(game.home to { p: PeriodScore -> p.home }, game.away to { p: PeriodScore -> p.away })
    val totals = if (awayFirst) listOf(game.score?.away, game.score?.home) else listOf(game.score?.home, game.score?.away)
    val cell = 28.dp

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                periods.forEach { p ->
                    Text(p.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.width(cell))
                }
                Text(scoreboard.totalLabel, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(cell + 6.dp))
            }
            rows.forEachIndexed { i, (team, pick) ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(team.abbreviation ?: team.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    periods.forEach { p ->
                        Text("${pick(p.score)}", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.width(cell))
                    }
                    Text(totals[i]?.toString() ?: "–", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(cell + 6.dp))
                }
            }
            scoreboard.note?.let { note ->
                Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** The live baseball state a clock cannot say: half-inning, outs, count, bases, who is up. */
@Composable
private fun SituationCard(s: BaseballSituation) {
    SectionCard("Now") {
        Text(
            text = "${s.half.name.lowercase().replaceFirstChar { it.uppercase() }} ${s.inning} · ${s.outs} out · count ${s.balls}-${s.strikes}",
            style = MaterialTheme.typography.bodyMedium,
        )
        val bases = listOf("1st" to s.onFirst, "2nd" to s.onSecond, "3rd" to s.onThird).mapNotNull { (base, runner) -> runner?.let { base to it } }
        if (bases.isNotEmpty()) {
            Text(text = bases.joinToString(" · ") { (base, runner) -> "$base: ${runner.name}" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        listOfNotNull(s.batter?.let { "At bat: ${it.name}" }, s.pitcher?.let { "Pitching: ${it.name}" }).forEach {
            Text(text = it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The bout — division, rounds, belt, card slot — and, once it is over, how it ended and the judges' cards. */
@Composable
private fun FightSection(game: Game, bout: FightSituation) {
    val result = bout.result
    SectionCard(if (result != null) "Result" else "Bout") {
        result?.let { r ->
            val headline = when {
                r.winner != null -> "${r.winner!!.name} def. ${(if (r.winner == game.home) game.away else game.home).name}"
                r.method == FightMethod.NO_CONTEST || r.method == FightMethod.OVERTURNED -> "No contest"
                else -> "Draw"
            }
            Text(headline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            val how = listOfNotNull(r.methodLabel, r.detail, r.round?.let { round -> "R$round" + (r.time?.let { t -> " $t" } ?: "") })
            Text(how.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.notes?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            val bonuses = r.homeBonuses.map { "$it: ${game.home.name}" } + r.awayBonuses.map { "$it: ${game.away.name}" } +
                listOfNotNull("Fight of the Night".takeIf { r.fightOfTheNight })
            bonuses.forEach { Text("🏅 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            r.scorecards.forEach { card ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(card.judge, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${card.home}–${card.away}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        val format = listOfNotNull(
            bout.weightClass,
            "${bout.scheduledRounds} rounds",
            bout.title,
            bout.cardSegment?.let { seg -> if (seg.startsWith("Prelims")) "Prelims" else "$seg card" },
        )
        Text(format.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun MatchParticipants(game: Game) {
    SectionCard(game.preview?.label ?: "Match credits") {
        game.preview?.let { preview ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(preview.home?.name ?: "TBD", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(game.home.abbreviation ?: game.home.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("vs", modifier = Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(preview.away?.name ?: "TBD", fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
                    Text(game.away.abbreviation ?: game.away.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        game.credits.forEach { credit ->
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(credit.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(credit.player.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Both line-ups beside each other, home on the left, so they read against one another. */
@Composable
private fun LineupsSection(game: Game, lineups: List<Lineup>, sport: Sport) {
    val home = lineups.firstOrNull { it.team.id == game.home.id } ?: lineups.getOrNull(0)
    val away = lineups.firstOrNull { it.team.id == game.away.id && it !== home } ?: lineups.getOrNull(1)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Column {
            if (sport == Sport.FOOTBALL && home != null && away != null) {
                FootballFormationPitch(home, away)
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp).height(IntrinsicSize.Min)) {
                LineupColumn(home, game.home, Modifier.weight(1f).padding(horizontal = 12.dp))
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)))
                LineupColumn(away, game.away, Modifier.weight(1f).padding(horizontal = 12.dp))
            }
        }
    }
}

@Composable
private fun LineupColumn(lineup: Lineup?, team: TeamRef, modifier: Modifier) {
    Column(modifier) {
        Text(team.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        lineup?.formationLabel?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        lineup?.groups?.forEach { group ->
            Text(text = group.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            group.players.forEachIndexed { i, p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(p.jerseyNumber?.toString().orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(p.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    p.position?.let {
                        Spacer(Modifier.width(4.dp))
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                if (i < group.players.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            }
        }
        lineup?.headCoach?.let {
            Text("Coach: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

private const val MIN_BAR_SHARE = 0.035f

/** Each stat is the two values with a bar split in their proportion; the side ahead is picked out in colour. */
@Composable
private fun StatsSection(game: Game) {
    SectionCard("Match stats") {
        TeamSidesRow(game)
        game.stats.forEach { (key, pair) -> StatRow(key, statLabel(key), pair) }
    }
}

@Composable
private fun StatRow(key: String, label: String, pair: StatPair) {
    val share = statShare(pair.home, pair.away)
    val lead = MaterialTheme.colorScheme.primary
    val trail = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    val homeAhead = share != null && share > 0.5f
    val awayAhead = share != null && share < 0.5f
    val neutral = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
    val behind = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)

    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(statValue(key, pair.home), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (homeAhead) lead else trail, maxLines = 1, modifier = Modifier.widthIn(min = 52.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(statValue(key, pair.away), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (awayAhead) lead else trail, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.widthIn(min = 52.dp))
    }
    if (share != null) {
        val s = share.coerceIn(MIN_BAR_SHARE, 1f - MIN_BAR_SHARE)
        Row(Modifier.fillMaxWidth().padding(top = 4.dp).height(5.dp)) {
            Box(Modifier.weight(s).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (homeAhead) lead else if (awayAhead) behind else neutral))
            Spacer(Modifier.width(3.dp))
            Box(Modifier.weight(1f - s).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(if (awayAhead) lead else if (homeAhead) behind else neutral))
        }
    }
}

/** Goals, cards, penalties and changes, by period, each pushed to the side it belongs to. */
@Composable
private fun EventsSection(game: Game, events: List<GameEvent>, sport: Sport) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    val scoreColors = MaterialTheme.scoreColors
    val ordered = if (events.all { it.sortOrder != null }) events.sortedBy { it.sortOrder } else events
    val sections = ordered.groupBy { it.period.label }.entries.toList()

    SectionCard("Match events") {
        TeamSidesRow(game)
        Spacer(Modifier.height(4.dp))
        sections.forEachIndexed { sectionIndex, (periodLabel, group) ->
            if (sectionIndex > 0) HorizontalDivider(Modifier.padding(top = 10.dp), thickness = 1.dp, color = onSurface.copy(alpha = 0.3f))
            Text(periodLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
            group.forEachIndexed { index, event ->
                val line = event.present(sport, onSurface, scoreColors)
                val atHome = event.team?.id == game.home.id
                val atAway = event.team?.id == game.away.id
                val align = if (atAway) TextAlign.End else TextAlign.Start
                val scoreLabel = event.score?.takeIf { event.type.isGoal }?.let { "${it.home}-${it.away}" }

                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(line.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(44.dp))
                    if (scoreLabel != null && !atAway) { ScoreSlot(scoreLabel, TextAlign.Start); Spacer(Modifier.width(8.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = if (line.icon.isBlank()) line.actor else if (atAway) "${line.actor} ${line.icon}" else "${line.icon} ${line.actor}",
                            style = MaterialTheme.typography.bodySmall,
                            textAlign = align,
                            color = line.color ?: onSurface,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        line.detail?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, textAlign = align, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth(), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (scoreLabel != null && atAway) { Spacer(Modifier.width(8.dp)); ScoreSlot(scoreLabel, TextAlign.End) }
                }
                if (index < group.lastIndex) HorizontalDivider(Modifier.padding(vertical = 2.dp), color = onSurface.copy(alpha = 0.1f))
            }
        }
    }
}

@Composable
private fun ScoreSlot(label: String, align: TextAlign) {
    Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.widthIn(min = 44.dp), textAlign = align, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
}
