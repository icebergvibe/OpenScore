package org.openscore.app.ui.feed

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.TimeZone
import org.openscore.app.data.RacingSessionEntry
import org.openscore.app.data.SessionPhase
import org.openscore.app.ui.common.StatusLabel
import org.openscore.app.ui.common.StatusTone
import org.openscore.app.ui.common.timeLabel
import kotlin.time.Instant

/**
 * One racing session in a feed: the session and its round, and where the schedule says it is.
 * There is no live timing behind it, so the card opens nothing; the season view under Scores
 * has the classification once the session is run.
 */
@Composable
fun SessionCard(entry: RacingSessionEntry, now: Instant, modifier: Modifier = Modifier) {
    val status = entry.statusLabel(now)
    val accent = statusAccent(status)
    val shape = RoundedCornerShape(18.dp)
    Card(
        modifier = modifier.fillMaxWidth().padding(vertical = 1.dp).border(1.dp, accent.copy(alpha = 0.10f), shape),
        shape = shape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 6.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(3.dp).height(40.dp).clip(RoundedCornerShape(3.dp)).background(accent))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.session.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(entry.round.name, entry.round.circuit ?: entry.round.locality).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.widthIn(min = 58.dp, max = 82.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusBadge(status)
                if (status.tone != StatusTone.SCHEDULED) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = entry.startsAt.timeLabel(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** Start time before, "Now" while the schedule says the session is on, "Ended" after — the schedule is all there is. */
fun RacingSessionEntry.statusLabel(now: Instant, zone: TimeZone = TimeZone.currentSystemDefault()): StatusLabel = when (phase(now)) {
    SessionPhase.UPCOMING -> StatusLabel(startsAt.timeLabel(zone), StatusTone.SCHEDULED)
    SessionPhase.UNDER_WAY -> StatusLabel("Now", StatusTone.LIVE)
    SessionPhase.OVER -> StatusLabel("Ended", StatusTone.DONE)
}
