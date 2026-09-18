package org.openscore.app.ui.filter

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.openscore.app.data.Favorite
import org.openscore.app.ui.Selection
import org.openscore.app.ui.common.displayName
import org.openscore.app.ui.common.flagEmoji
import org.openscore.app.ui.common.iconRes
import org.openscore.app.ui.theme.scoreColors
import org.openscore.model.League
import org.openscore.model.Sport

/**
 * Search, then one radio row per sport with its leagues underneath. The line under the search
 * box states the cost where the choice is made: a day is one request per league ticked.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(
    selection: Selection,
    query: String,
    leaguesBySport: Map<Sport, List<League>>,
    favorites: Set<Favorite>,
    onSelectionChange: (Selection) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleFavorite: (Favorite) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expanded by remember { mutableStateOf<Sport?>(selection.sport) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
            item(key = "controls") {
                Column {
                    OutlinedTextField(
                        value = query,
                        onValueChange = onQueryChange,
                        placeholder = { Text("Search loaded days…") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                        trailingIcon = {
                            if (query.isNotEmpty()) IconButton(onClick = { onQueryChange("") }) { Icon(Icons.Default.Clear, contentDescription = "Clear search") }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onDismiss() }),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            focusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(12.dp))
                    val total = leaguesBySport[selection.sport]?.size ?: 0
                    val picked = if (selection.leagueIds.isEmpty()) total else selection.leagueIds.size
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (selection.leagueIds.isEmpty()) "All of ${selection.sport.displayName.lowercase()} · $total ${if (total == 1) "league" else "leagues"}"
                            else "$picked of $total ${selection.sport.displayName.lowercase()} leagues",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (selection.leagueIds.isNotEmpty()) {
                            TextButton(onClick = { onSelectionChange(Selection(selection.sport)) }) { Text("Clear") }
                        }
                    }
                    HorizontalDivider()
                }
            }

            Sport.entries.forEach { sport ->
                val leagues = leaguesBySport[sport].orEmpty()
                item(key = "sport-${sport.name}") {
                    SportRow(
                        sport = sport,
                        selected = sport == selection.sport,
                        picked = if (sport == selection.sport) selection.leagueIds.size else 0,
                        expanded = expanded == sport,
                        onToggleExpand = { expanded = if (expanded == sport) null else sport },
                        onSelect = { onSelectionChange(selection.onSport(sport)) },
                    )
                }
                if (expanded == sport) {
                    items(leagues, key = { "league-${it.id}" }) { league ->
                        val favorite = Favorite.league(league)
                        LeagueRow(
                            league = league,
                            // Ticked means "narrowed to this"; nothing ticked is the whole sport.
                            checked = sport == selection.sport && league.id in selection.leagueIds,
                            isFavorite = favorites.any { it.key == favorite.key },
                            onCheckedChange = { on -> onSelectionChange(selection.onSport(sport).withLeague(league.id, on)) },
                            onToggleFavorite = { onToggleFavorite(favorite) },
                        )
                    }
                }
            }
        }
    }
}

/** The whole row is the radio, so the name is as good a target as the dot; the chevron alone expands. */
@Composable
private fun SportRow(sport: Sport, selected: Boolean, picked: Int, expanded: Boolean, onToggleExpand: () -> Unit, onSelect: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = Modifier.weight(1f).selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A radio, not a checkbox: the feed shows one sport at a time.
            RadioButton(selected = selected, onClick = null)
            Icon(painter = painterResource(sport.iconRes), contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(text = sport.displayName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (picked > 0) Text(text = "$picked", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        IconButton(onClick = onToggleExpand) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                contentDescription = if (expanded) "Collapse ${sport.displayName}" else "Show ${sport.displayName} leagues",
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun LeagueRow(league: League, checked: Boolean, isFavorite: Boolean, onCheckedChange: (Boolean) -> Unit, onToggleFavorite: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange).padding(start = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(
            text = "${flagEmoji(league.country)}  ${league.name}",
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(48.dp)) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = if (isFavorite) "Unfollow ${league.name}" else "Follow ${league.name}",
                modifier = Modifier.size(18.dp),
                tint = if (isFavorite) MaterialTheme.scoreColors.favorite else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }
    }
}
