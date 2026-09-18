package org.openscore.app.ui.feed

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openscore.app.ui.common.StatusLabel
import org.openscore.app.ui.common.StatusTone
import org.openscore.app.ui.common.TeamBadge
import org.openscore.app.ui.common.halfTimeLabel
import org.openscore.app.ui.common.mark
import org.openscore.app.ui.common.progress
import org.openscore.app.ui.common.statusLabel
import org.openscore.app.ui.theme.scoreColors
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.Sport
import org.openscore.model.TeamRef
import org.openscore.model.baseball.BaseballSituation
import org.openscore.model.combat.FightSituation

/** Digits of equal width, so a score column and a running clock do not shift as they change. */
val TabularFigures = TextStyle(fontFeatureSettings = "tnum")

/** One fixture: team names get the wide column; scores line up at the edge and status stays scannable. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MatchCard(game: Game, sport: Sport, onClick: () -> Unit, onLongClick: () -> Unit, modifier: Modifier = Modifier) {
    val status = game.statusLabel(sport)
    val accent = statusAccent(status)
    val shape = RoundedCornerShape(18.dp)
    val haptics = LocalHapticFeedback.current
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp)
            .border(1.dp, accent.copy(alpha = 0.10f), shape)
            .combinedClickable(
                onClick = onClick,
                onClickLabel = "Open match",
                onLongClickLabel = "Follow a team or the league",
                onLongClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongClick()
                },
            ),
        shape = shape,
        // A live game already has a green status pill and leading signal. Tinting and lifting the
        // entire card made a busy score list read as a rendering error rather than a live cue.
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(52.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(10.dp))
            val showScore = game.score != null && game.state != GameState.SCHEDULED
            // A fight has no score; once decided, each corner shows how it came out.
            val result = (game.situation as? FightSituation)?.result
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TeamInfo(game.home, if (showScore) game.score?.home else null, result?.homeOutcome?.mark())
                TeamInfo(game.away, if (showScore) game.score?.away else null, result?.awayOutcome?.mark())
            }
            Spacer(Modifier.width(10.dp))
            GameMeta(game, sport, status)
        }
    }
}

@Composable
internal fun statusAccent(label: StatusLabel): Color = when (label.tone) {
    StatusTone.LIVE -> MaterialTheme.scoreColors.live
    StatusTone.BREAK -> MaterialTheme.scoreColors.breakTime
    StatusTone.DONE -> MaterialTheme.colorScheme.onSurfaceVariant
    StatusTone.SCHEDULED -> MaterialTheme.colorScheme.primary
    StatusTone.OFF -> MaterialTheme.colorScheme.outline
}

@Composable
private fun TeamInfo(team: TeamRef, score: Int?, mark: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamBadge(team, size = 24.dp)
        Spacer(Modifier.width(8.dp))
        Text(
            text = team.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        (score?.toString() ?: mark)?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.titleMedium.merge(TabularFigures),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.End,
                color = if (mark == "L") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.width(30.dp),
            )
        }
    }
}

@Composable
private fun GameMeta(game: Game, sport: Sport, status: StatusLabel) {
    Column(
        modifier = Modifier.widthIn(min = 58.dp, max = 82.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        game.halfTimeLabel(sport)?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(3.dp))
        }
        StatusBadge(status)
        // A baseball scoreboard says outs and runners where a clock would go.
        (game.situation as? BaseballSituation)?.takeIf { game.state.isLive }?.let { s ->
            Spacer(Modifier.height(3.dp))
            Text(
                text = "${s.outs} out · ${s.balls}-${s.strikes}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        // A fight card says the division and whether a belt is on the line where a clock would go.
        (game.situation as? FightSituation)?.let { bout ->
            val line = listOfNotNull(bout.weightClass, bout.title?.let { "Title" }).joinToString(" · ")
            if (line.isNotEmpty()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = line,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        game.preview?.takeIf { game.state == org.openscore.model.GameState.SCHEDULED || game.state == org.openscore.model.GameState.PRE_GAME }?.let { preview ->
            val home = preview.home?.name ?: "TBD"
            val away = preview.away?.name ?: "TBD"
            Spacer(Modifier.height(3.dp))
            Text(
                text = "$home · $away",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        game.progress(sport)?.let {
            Spacer(Modifier.height(3.dp))
            ProgressSliver(it)
        }
    }
}

/** A sliver under the clock: did I miss it, at a glance. */
@Composable
private fun ProgressSliver(progress: Float) {
    Box(
        modifier = Modifier
            .width(36.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.scoreColors.goal),
        )
    }
}

@Composable
fun StatusBadge(label: StatusLabel) {
    val color = statusAccent(label)
    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.1f)) {
        Text(
            text = label.text,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
            style = MaterialTheme.typography.bodySmall.merge(TabularFigures),
            fontWeight = FontWeight.Medium,
            color = color,
            maxLines = 1,
        )
    }
}
