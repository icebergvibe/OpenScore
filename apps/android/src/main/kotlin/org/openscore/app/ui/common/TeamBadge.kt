package org.openscore.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.openscore.model.TeamRef

/** Only https logos from the feeds are loaded; anything else falls back to the abbreviation. */
fun safeImageUrl(url: String?): String? = url?.takeIf { it.startsWith("https://") }

/** A team's crest, or a coloured disc with its abbreviation where the feed has none. */
@Composable
fun TeamBadge(team: TeamRef, size: Dp, modifier: Modifier = Modifier) {
    val url = safeImageUrl(team.logoUrl)
    if (url != null) {
        Surface(
            modifier = modifier.size(size),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
        ) {
            Box(contentAlignment = Alignment.Center) {
                AsyncImage(
                    model = url,
                    contentDescription = "${team.name} logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(size * 0.78f).clip(CircleShape),
                )
            }
        }
    } else {
        Box(
            modifier = modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = (team.abbreviation ?: team.name.take(3)).uppercase().take(3),
                color = MaterialTheme.colorScheme.onPrimary,
                fontSize = (size.value * 0.32f).sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}
