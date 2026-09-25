package org.openscore.app.ui.detail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.shadow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openscore.model.Lineup
import org.openscore.model.LineupGroupKind
import org.openscore.model.PlayerRef

/** A full football pitch: home attacks towards the top, away towards the bottom. */
@Composable
internal fun FootballFormationPitch(home: Lineup, away: Lineup) {
    val homeFormation = home.formationOrNull() ?: return
    val awayFormation = away.formationOrNull() ?: return
    val playerColor = Color(0xff626863)

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.64f)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xff247146)),
    ) {
        PitchMarkings()
        formationPositions(homeFormation.lines, attackUp = true).zip(homeFormation.starters).forEach { (position, player) ->
            PitchPlayer(player, position, playerColor)
        }
        formationPositions(awayFormation.lines, attackUp = false).zip(awayFormation.starters).forEach { (position, player) ->
            PitchPlayer(player, position, playerColor)
        }
    }
}

@Composable
private fun PitchMarkings() {
    val lineColor = Color.White.copy(alpha = 0.76f)
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(width = 1.5.dp.toPx())
        val inset = 10.dp.toPx()
        val left = inset
        val top = inset
        val right = size.width - inset
        val bottom = size.height - inset
        val pitchWidth = right - left
        val pitchHeight = bottom - top
        val center = Offset(size.width / 2, size.height / 2)
        val penaltyWidth = pitchWidth * .64f
        val penaltyLeft = center.x - penaltyWidth / 2
        val penaltyDepth = pitchHeight * .18f
        val goalAreaWidth = pitchWidth * .34f
        val goalAreaLeft = center.x - goalAreaWidth / 2
        val goalAreaDepth = pitchHeight * .075f

        drawRect(lineColor, Offset(left, top), Size(pitchWidth, pitchHeight), style = stroke)
        drawLine(lineColor, Offset(left, center.y), Offset(right, center.y), strokeWidth = stroke.width)
        drawCircle(lineColor, radius = pitchWidth * .15f, center = center, style = stroke)
        drawCircle(lineColor, radius = 2.5.dp.toPx(), center = center)

        fun end(topEnd: Boolean) {
            val penaltyY = if (topEnd) top else bottom - penaltyDepth
            val goalAreaY = if (topEnd) top else bottom - goalAreaDepth
            drawRect(lineColor, Offset(penaltyLeft, penaltyY), Size(penaltyWidth, penaltyDepth), style = stroke)
            drawRect(lineColor, Offset(goalAreaLeft, goalAreaY), Size(goalAreaWidth, goalAreaDepth), style = stroke)
            val spotY = if (topEnd) top + penaltyDepth * .62f else bottom - penaltyDepth * .62f
            drawCircle(lineColor, radius = 2.5.dp.toPx(), center = Offset(center.x, spotY))
            val arcTop = if (topEnd) top + penaltyDepth * .72f else bottom - penaltyDepth * 1.28f
            drawArc(
                color = lineColor,
                startAngle = if (topEnd) 35f else 215f,
                sweepAngle = 110f,
                useCenter = false,
                topLeft = Offset(center.x - pitchWidth * .12f, arcTop),
                size = Size(pitchWidth * .24f, pitchHeight * .12f),
                style = stroke,
            )
        }
        end(topEnd = true)
        end(topEnd = false)

    }
}

@Composable
private fun BoxWithConstraintsScope.PitchPlayer(player: PlayerRef, position: PitchPosition, color: Color) {
    val markerWidth = 68.dp
    Box(
        modifier = Modifier
            .offset(x = maxWidth * position.x - markerWidth / 2, y = maxHeight * position.y - 17.dp)
            .width(markerWidth),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .shadow(6.dp, CircleShape, clip = false)
                    .clip(CircleShape)
                    .background(color),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = player.jerseyNumber?.toString() ?: "–",
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            PlayerLabel(player)
        }
    }
}

@Composable
private fun PlayerLabel(player: PlayerRef) {
    Text(
        text = player.name.substringAfterLast(' '),
        color = Color.White,
        style = MaterialTheme.typography.labelSmall.copy(
            shadow = Shadow(color = Color.Black.copy(alpha = .65f), offset = Offset(0f, 1.5f), blurRadius = 3f),
        ),
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth(),
    )
}

private data class PitchPosition(val x: Float, val y: Float)

/** Placement bounds: the keeper alone on the goal line, the outfield rows spread between the back line and the front line. */
private const val PITCH_GOAL_LINE = .93f
private const val PITCH_BACK_LINE = .80f
private const val PITCH_FRONT_LINE = .55f
private const val PITCH_TOUCHLINE = .10f
private const val PITCH_MAX_GAP = .30f

/** The starters in formation order, with the formation already parsed into its rows. */
private data class Formation(val starters: List<PlayerRef>, val lines: List<Int>)

private fun Lineup.formationOrNull(): Formation? {
    val starters = groups.firstOrNull { it.kind == LineupGroupKind.STARTERS }?.players ?: return null
    val lines = formation?.filter(Char::isDigit)?.map(Char::digitToInt) ?: return null
    if (starters.size < 11 || lines.sum() != 10) return null
    return Formation(starters, lines)
}

private fun formationPositions(lines: List<Int>, attackUp: Boolean): List<PitchPosition> {
    // Home starts at the bottom and attacks up; away is its mirror. The outfield rows spread
    // evenly from the back line (in front of the keeper) to just before halfway.
    val yFor = { ownGoalDepth: Float -> if (attackUp) ownGoalDepth else 1f - ownGoalDepth }
    return buildList {
        add(PitchPosition(.5f, yFor(PITCH_GOAL_LINE)))
        lines.forEachIndexed { index, count ->
            val depth = PITCH_BACK_LINE - index.toFloat() / (lines.size - 1).coerceAtLeast(1) * (PITCH_BACK_LINE - PITCH_FRONT_LINE)
            // A back four reaches towards the touchlines; narrow attacking rows remain compact.
            val gap = if (count <= 1) PITCH_MAX_GAP else ((1f - 2 * PITCH_TOUCHLINE) / (count - 1)).coerceAtMost(PITCH_MAX_GAP)
            repeat(count) { player ->
                val across = .5f + (player - (count - 1) / 2f) * gap
                add(PitchPosition(if (attackUp) across else 1f - across, yFor(depth)))
            }
        }
    }
}
