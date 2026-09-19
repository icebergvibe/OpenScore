package org.openscore.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import org.openscore.app.R
import org.openscore.app.alerts.AlertSettings
import org.openscore.app.alerts.LEAD_TIME_CHOICES
import org.openscore.app.alerts.Notifications
import org.openscore.app.data.Favorite
import org.openscore.app.ui.common.displayName
import org.openscore.model.League

private const val FAVORITES_PREVIEW = 6

@Composable
fun SettingsScreen(
    favorites: Set<Favorite>,
    /** Null follows the phone. */
    darkMode: Boolean?,
    onDarkModeChange: (Boolean?) -> Unit,
    onRemoveFavorite: (Favorite) -> Unit,
    alerts: AlertSettings,
    onToggleAlert: (Favorite) -> Unit,
    onAlertsChange: ((AlertSettings) -> AlertSettings) -> Unit,
    leagues: List<League>,
    cacheBytes: Long,
    onClearCache: () -> Unit,
) {
    val sorted = favorites.sortedWith(compareBy({ it.sport.displayName }, { it is Favorite.Team }, { it.label.lowercase() }))
    var showAll by rememberSaveable { mutableStateOf(false) }
    val shown = if (showAll) sorted else sorted.take(FAVORITES_PREVIEW)
    val alerting = sorted.count(alerts::notifies)

    // Android 13 gates notifications behind a runtime permission; it is asked for the moment the
    // first bell goes on, which is when the answer means something.
    val context = LocalContext.current
    var systemEnabled by remember { mutableStateOf(Notifications.enabled(context)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { systemEnabled = Notifications.enabled(context) }
    fun ensurePermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(alerts.keys) { systemEnabled = Notifications.enabled(context) }

    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppLogo()
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        HorizontalDivider()

        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
                item {
                    ThemeRow(darkMode = darkMode, onDarkModeChange = onDarkModeChange)
                    HorizontalDivider()
                }

                item { SectionHeader("Following", "${sorted.size} followed" + if (alerting > 0) " · $alerting notify" else "") }
                if (sorted.isEmpty()) {
                    item {
                        Text(
                            text = "Nothing followed yet. Long-press a game to follow a team, or use the stars on league headers and in the filter sheet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                } else {
                    items(shown, key = { it.key }) { favorite ->
                        FavoriteRow(
                            favorite = favorite,
                            alerting = alerts.notifies(favorite),
                            onToggleAlert = {
                                if (!alerts.notifies(favorite)) ensurePermission()
                                onToggleAlert(favorite)
                            },
                            onRemove = { onRemoveFavorite(favorite) },
                        )
                    }
                    if (sorted.size > FAVORITES_PREVIEW) {
                        item {
                            TextButton(onClick = { showAll = !showAll }) { Text(if (showAll) "Show fewer" else "Show all ${sorted.size}") }
                        }
                    }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    SectionHeader(
                        "Offline scores",
                        "${cacheSizeLabel(cacheBytes)}: the days you have opened, as last read, and HockeyAllsvenskan's season. Results are kept a week, fixtures an hour; a day with a game running is always read live. Match details are not stored.",
                    )
                    TextButton(onClick = onClearCache, enabled = cacheBytes > 0) { Text("Clear offline scores") }
                }

                item {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    SectionHeader(
                        "Notifications",
                        "Posted by the app itself from a daily read of the same listings the feeds use — no push service, nothing about you leaves the phone. " +
                            "Tap the bell on a followed team or league above to turn it on.",
                    )
                    if (!systemEnabled && alerting > 0) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "Notifications are blocked for OpenScore in Android's settings.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }) { Text("Open") }
                        }
                    }
                    ToggleRow("Pre-game reminder", alerts.kickoff, { on -> if (on) ensurePermission(); onAlertsChange { it.copy(kickoff = on) } }, "Before a followed game starts, and if it is called off")
                    if (alerts.kickoff) {
                        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            LEAD_TIME_CHOICES.forEach { minutes ->
                                FilterChip(
                                    selected = alerts.leadMinutes == minutes,
                                    onClick = { onAlertsChange { it.copy(leadMinutes = minutes) } },
                                    label = { Text(if (minutes < 60) "$minutes min" else "1 h") },
                                )
                            }
                        }
                    }
                    ToggleRow("Match start", alerts.started, { on -> if (on) ensurePermission(); onAlertsChange { it.copy(started = on) } }, "When the feed says it is under way — the reminder fires on the scheduled time, this on a delayed one actually starting")
                    ToggleRow("Goals and runs", alerts.goals, { on -> if (on) ensurePermission(); onAlertsChange { it.copy(goals = on) } }, "Polls followed games every few minutes while they run")
                    ToggleRow("Cards and penalties", alerts.cards, { on -> if (on) ensurePermission(); onAlertsChange { it.copy(cards = on) } }, "Bookings, and hockey's majors and misconducts (not every minor); costs a request per game per poll")
                    ToggleRow("Half time and breaks", alerts.breaks, { on -> if (on) ensurePermission(); onAlertsChange { it.copy(breaks = on) } }, "Only leagues whose feed reports a break")
                    ToggleRow("Final result", alerts.results, { on -> if (on) ensurePermission(); onAlertsChange { it.copy(results = on) } }, "The score when a followed game finishes")
                }

                item {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    SectionHeader(
                        "Where the scores come from",
                        "${leagues.size} leagues, read from the public APIs behind the leagues' own sites and, where noted below, one supplementary source. No key, no account, no server in between — nothing about you is sent anywhere.",
                    )
                }
                items(leagues, key = { it.id }) { league ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(text = league.name, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                text = league.sport.displayName + (league.websiteUrl?.let { " · ${it.removePrefix("https://").removePrefix("www.").trimEnd('/')}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item {
                    // Sources that are not leagues, so the list above cannot name them.
                    Text(
                        text = "Also read: ESPN (site.web.api.espn.com) for Bundesliga and UEFA squads only; the Swedish FA's Fogis livescore (svenskfotboll.se) for Allsvenskan and Superettan games, with their tables and squads from allsvenskan.se; the KHL's mobile-app API (khl.api.webcaster.pro); the DEL's mobile-app API (del-services.appticore.com), with crests from penny-del.org; Formula 1 from Jolpica F1 (api.jolpi.ca), an open-source, community-run successor to the Ergast API — github.com/jolpica/jolpica-f1.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }

                item {
                    Spacer(Modifier.height(24.dp))
                    HorizontalDivider()
                    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() }
                    SectionHeader(
                        "About",
                        (version?.let { "Version $it · " } ?: "") +
                            "OpenScore is free software under the GNU GPL v3 or later. It is not affiliated with any league; the APIs it reads are unofficial and may change without notice.",
                    )
                    Spacer(Modifier.height(48.dp))
                }
            }
        }
    }
}

private fun cacheSizeLabel(bytes: Long): String = when {
    bytes < 1024L * 1024 -> "${(bytes / 1024).coerceAtLeast(1)} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(Modifier.padding(top = 16.dp, bottom = 8.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FavoriteRow(favorite: Favorite, alerting: Boolean, onToggleAlert: () -> Unit, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(text = favorite.label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                text = favorite.sport.displayName + " · " + when (favorite) {
                    is Favorite.Team -> if (favorite.clubId != null) "Club, every league it plays in" else "Team"
                    is Favorite.League -> "League"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleAlert) {
            Icon(
                imageVector = if (alerting) Icons.Filled.Notifications else Icons.Outlined.Notifications,
                contentDescription = if (alerting) "Stop notifying about ${favorite.label}" else "Notify about ${favorite.label}",
                modifier = Modifier.size(20.dp),
                tint = if (alerting) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onRemove) {
            Icon(imageVector = Icons.Default.Clear, contentDescription = "Stop following ${favorite.label}", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Three states, not a switch: once "dark" has been chosen there has to be a way back to following the phone. */
@Composable
private fun ThemeRow(darkMode: Boolean?, onDarkModeChange: (Boolean?) -> Unit) {
    val options = listOf<Pair<String, Boolean?>>("System" to null, "Light" to false, "Dark" to true)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(text = "Appearance", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, (label, value) ->
                SegmentedButton(
                    selected = darkMode == value,
                    onClick = { onDarkModeChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                    label = { Text(label) },
                )
            }
        }
    }
}

/** The whole row flips the switch, so the label is as good a target as the knob. */
@Composable
private fun ToggleRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** The launcher icon beside the name, assembled from the adaptive icon's own two halves. */
@Composable
private fun AppLogo() {
    Box(
        modifier = Modifier.size(30.dp).clip(CircleShape).background(colorResource(R.color.icon_background)),
        contentAlignment = Alignment.Center,
    ) {
        Image(painter = painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.size(44.dp))
    }
}
