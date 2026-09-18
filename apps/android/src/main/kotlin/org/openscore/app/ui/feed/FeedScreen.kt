package org.openscore.app.ui.feed

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import org.openscore.app.R
import org.openscore.app.data.Favorite
import org.openscore.app.data.FavoriteFilter
import org.openscore.app.data.ScoresRepository
import org.openscore.app.ui.theme.scoreColors
import org.openscore.model.Game
import org.openscore.model.League
import org.openscore.provider.Capability
import kotlin.time.Clock

/** How far the timeline reaches either side of today. Reach, not fetching: a day costs nothing until scrolled to. */
private const val TIMELINE_DAYS_BACK = 90
private const val TIMELINE_DAYS_FORWARD = 90

/** Days a scanning row fetches when it comes into view, and how many such fetches a scroll buys before asking to be tapped. */
private const val SCAN_BATCH = 2
private const val SCAN_BUDGET = 6

/** How far Following looks ahead on its own: a followed side's next game is usually within the fortnight. */
private const val FOLLOWING_LOOKAHEAD_DAYS = 14

/** How often a day with games in play reloads while on screen. */
private const val AUTO_REFRESH_MS = 60_000L

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    spec: FeedSpec,
    title: String,
    topContent: (@Composable () -> Unit)? = null,
    searchQuery: String,
    filterCount: Int,
    favoriteFilter: FavoriteFilter?,
    hasFavorites: Boolean,
    favorites: Set<Favorite>,
    viewModel: FeedViewModel,
    repository: ScoresRepository,
    onOpenFilters: () -> Unit,
    onClearSearch: () -> Unit,
    onOpenGame: (Game) -> Unit,
    onOpenStandings: (League) -> Unit,
    onToggleFavorite: (Favorite) -> Unit,
) {
    val zone = TimeZone.currentSystemDefault()
    // State, not a constant: the app is routinely left open across midnight.
    var today by remember { mutableStateOf(Clock.System.todayIn(zone)) }
    // Ticks with the poll: a racing session's "Now" is read off the schedule, not a feed.
    var now by remember { mutableStateOf(Clock.System.now()) }
    val scope = rememberCoroutineScope()

    val dates = remember(today, spec.kind) {
        if (spec.kind == FeedKind.LIVE) {
            FeedViewModel.liveDatesFor(today, Clock.System.now().toLocalDateTime(zone).hour)
        } else {
            (-TIMELINE_DAYS_BACK..TIMELINE_DAYS_FORWARD).map { today.plus(it, DateTimeUnit.DAY) }
        }
    }

    LaunchedEffect(spec, today) {
        viewModel.openFeed(spec, today)
        // Live spans a league's yesterday as well in a local morning; it is one section, so both days are asked at once.
        if (spec.kind == FeedKind.LIVE) dates.forEach(viewModel::loadDay)
    }

    val days by remember(spec) { viewModel.days(spec) }.collectAsStateWithLifecycle()
    val inFlight by viewModel.inFlight.collectAsStateWithLifecycle()
    val rows = remember(dates, days, spec, searchQuery, favoriteFilter, today, now) {
        buildTimelineRows(dates, days, spec, searchQuery, favoriteFilter, today, repository.leagues, now)
    }

    // One scroll position per feed, so switching sports does not inherit where the last one was left.
    val listState = rememberSaveable(spec, saver = LazyListState.Saver) { LazyListState() }
    // Future days start as an explicit action. A drag opts into bounded automatic look-ahead;
    // merely opening a quiet sport must not query every league for a week of empty dates.
    // Following is the exception: it asks only the followed leagues, and what it is for is the
    // next game, so it walks ahead until it finds one rather than opening on an empty today.
    var scanBudget by remember(spec) { mutableIntStateOf(if (spec.kind == FeedKind.FAVORITES) FOLLOWING_LOOKAHEAD_DAYS else 0) }
    // Where the running look-ahead batch ends. Without it a quiet batch would swap its spinner
    // for a fresh "Load later days" row before the visibility trigger started the next one, so
    // an intentional look-ahead would flash between a label and a spinner for every empty day.
    var continuingForwardScanAt by remember(spec) { mutableStateOf<LocalDate?>(null) }
    // Saved with the list position: coming back from a match must not re-anchor a scrolled list on today.
    var anchored by rememberSaveable(spec) { mutableStateOf(false) }
    var showDateDialog by remember { mutableStateOf(false) }
    var followTarget by remember { mutableStateOf<Game?>(null) }

    // Settle on today once it has resolved, unless the reader has scrolled already.
    LaunchedEffect(rows, spec) {
        if (anchored) return@LaunchedEffect
        if (listState.isScrollInProgress) { anchored = true; return@LaunchedEffect }
        val index = rows.anchorIndex(today)
        if (index >= 0) { anchored = true; listState.scrollToItem(index) }
    }

    // A drag refills the scan budget: scrolling into unknown days is the deliberate act it waits for.
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.filter { it }.collect { scanBudget = SCAN_BUDGET }
    }

    val visibleDate by remember(rows) {
        derivedStateOfDate(listState, rows, today)
    }
    // The header button and a pull both re-ask the day under the top of the list (Live: every day it spans).
    val refresh = { if (spec.kind == FeedKind.LIVE) dates.forEach(viewModel::refreshDay) else viewModel.refreshDay(visibleDate) }
    var pulled by remember { mutableStateOf(false) }
    LaunchedEffect(inFlight) { if (inFlight == 0) pulled = false }

    // The poll: today reloads while something is in play, and only while this screen is resumed.
    val latestDays by rememberUpdatedState(days)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, spec) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(AUTO_REFRESH_MS)
                now = Clock.System.now()
                val date = now.toLocalDateTime(zone).date
                if (date != today) today = date
                for (day in if (spec.kind == FeedKind.LIVE) dates else listOf(date)) {
                    if (latestDays[day] is DayState.Loaded) viewModel.refreshLive(day, isToday = day == date)
                }
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        FeedHeader(
            title = title,
            updating = inFlight > 0,
            filterCount = filterCount,
            searchQuery = searchQuery,
            onOpenFilters = onOpenFilters,
            // Live is "now"; there is no other day to jump to.
            onPickDate = if (spec.kind == FeedKind.LIVE) null else ({ showDateDialog = true }),
            onRefresh = refresh,
            onClearSearch = onClearSearch,
        )
        topContent?.invoke()

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .navigationBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            if (spec.kind == FeedKind.FAVORITES && !hasFavorites) {
                EmptyState(
                    "Nothing followed yet.\n\nLong-press a game to follow a team, or tap the star on a league header. " +
                        "Following is filtered on your device from the same listings the other tabs load.",
                )
            } else PullToRefreshBox(
                isRefreshing = pulled && inFlight > 0,
                onRefresh = { pulled = true; refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
                    rows.forEach { row ->
                        when (row) {
                            is TimelineRow.DayHeader -> stickyHeader(key = row.key, contentType = "header") {
                                DayHeader(row.date, today, label = if (spec.kind == FeedKind.LIVE) "Live now" else null)
                            }

                            is TimelineRow.Scanning -> item(key = row.key, contentType = "scan") {
                                val load = {
                                    val take = (if (row.earlier) row.dates.takeLast(SCAN_BATCH) else row.dates.take(SCAN_BATCH))
                                    take.forEach(viewModel::loadDay)
                                }
                                // The following unfetched row starts immediately after this batch.
                                // It only exists as a continuation when this batch is exhausted without
                                // placing games between the reader and that row.
                                val nextForwardScanAt = row.dates.getOrNull(SCAN_BATCH)
                                val continuing = !row.earlier && continuingForwardScanAt == row.dates.firstOrNull()
                                // Coming into view fetches the next few days after a short dwell, so a fling
                                // does not fetch every day it passes; the budget stops a quiet stretch from
                                // being walked automatically forever. Only forward: a short list has the
                                // earlier row permanently in view, and the past is asked for by tapping.
                                // Keyed on the row's shape, not just its first day: the first frame has one
                                // row spanning the whole window, and the hunt splits it a moment later — an
                                // effect still keyed to the original would fetch the window's first days.
                                // A row that spans today is resolved by openFeed.
                                LaunchedEffect(row.key, row.earlier, row.loading, continuing, scanBudget) {
                                    if (row.loading || row.earlier || today in row.dates) return@LaunchedEffect
                                    if (scanBudget <= 0) {
                                        // The continuation has reached its limit; turn it back into the
                                        // explicit affordance rather than leaving an idle spinner behind.
                                        if (continuing) continuingForwardScanAt = null
                                        return@LaunchedEffect
                                    }
                                    // Once the reader has opted into a scan, carry it through consecutive
                                    // quiet batches. The first automatic batch still waits for a short dwell.
                                    if (!continuing) delay(400)
                                    if (row.loading || scanBudget <= 0) return@LaunchedEffect
                                    scanBudget -= SCAN_BATCH
                                    continuingForwardScanAt = nextForwardScanAt
                                    load()
                                }
                                DayScanning(
                                    loading = row.loading || continuing,
                                    earlier = row.earlier,
                                    onLoad = {
                                        scanBudget = SCAN_BUDGET
                                        continuingForwardScanAt = if (row.earlier) null else nextForwardScanAt
                                        load()
                                    },
                                    modifier = Modifier.animateItem(),
                                )
                            }

                            is TimelineRow.Failed -> item(key = row.key, contentType = "message") {
                                DayError(row.message, onRetry = { viewModel.refreshDay(row.date) }, modifier = Modifier.animateItem())
                            }

                            is TimelineRow.Empty -> item(key = row.key, contentType = "message") {
                                DayMessage(row.message, modifier = Modifier.animateItem())
                            }

                            is TimelineRow.Partial -> item(key = row.key, contentType = "partial") {
                                PartialDay(
                                    row.failed,
                                    leagueName = { repository.league(it)?.name ?: it },
                                    onRetry = { viewModel.refreshDay(row.date) },
                                    modifier = Modifier.animateItem(),
                                )
                            }

                            is TimelineRow.LeagueHeader -> item(key = row.key, contentType = "league") {
                                val league = row.league
                                val favorite = league?.let(Favorite::league)
                                LeagueHeader(
                                    row = row,
                                    isFavorite = favorite != null && favorites.any { it.key == favorite.key },
                                    onToggleFavorite = favorite?.let { { onToggleFavorite(it) } },
                                    onOpenStandings = league?.takeIf { repository.supports(it.id, Capability.STANDINGS) }?.let { { onOpenStandings(it) } },
                                    modifier = Modifier.animateItem(),
                                )
                            }

                            is TimelineRow.Session -> item(key = row.key, contentType = "session") {
                                SessionCard(entry = row.entry, now = now, modifier = Modifier.animateItem())
                            }

                            is TimelineRow.Match -> item(key = row.key, contentType = "match") {
                                val sport = repository.league(row.game.leagueId)?.sport ?: spec.sport ?: org.openscore.model.Sport.FOOTBALL
                                MatchCard(
                                    game = row.game,
                                    sport = sport,
                                    onClick = { onOpenGame(row.game) },
                                    onLongClick = { followTarget = row.game },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                    }
                }
            }

            // Shown only when today is off screen, pointing the way you would have to scroll.
            AnimatedTodayButton(
                visible = visibleDate != today && spec.kind != FeedKind.LIVE,
                pointsDown = visibleDate < today,
                modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp),
                onClick = {
                    viewModel.loadDay(today)
                    scope.launch { listState.animateScrollToItem(rows.indexOfDay(today).coerceAtLeast(0)) }
                },
            )
        }
    }

    if (showDateDialog) {
        JumpToDateDialog(
            today = today,
            onDateSelected = { date ->
                showDateDialog = false
                anchored = true
                viewModel.loadDay(date)
                scope.launch { listState.scrollToItem(rows.indexOfDay(date).coerceAtLeast(0)) }
            },
            onDismiss = { showDateDialog = false },
        )
    }

    followTarget?.let { game ->
        FollowDialog(
            game = game,
            league = repository.league(game.leagueId),
            favorites = favorites,
            onToggleFavorite = onToggleFavorite,
            onDismiss = { followTarget = null },
        )
    }
}

@Composable
private fun AnimatedTodayButton(visible: Boolean, pointsDown: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(180)) + scaleIn(tween(180), initialScale = 0.92f),
        exit = fadeOut(tween(120)) + scaleOut(tween(120), targetScale = 0.92f),
    ) {
        ExtendedFloatingActionButton(
            onClick = onClick,
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Icon(
                imageVector = if (pointsDown) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text("Today")
        }
    }
}

/** The day under the top of the list: the first visible row that is a day rather than a run of unfetched ones. */
private fun derivedStateOfDate(listState: LazyListState, rows: List<TimelineRow>, today: LocalDate) =
    androidx.compose.runtime.derivedStateOf {
        listState.layoutInfo.visibleItemsInfo
            .asSequence()
            .mapNotNull { rows.getOrNull(it.index) }
            .firstOrNull { it !is TimelineRow.Scanning }
            ?.date ?: today
    }

@Composable
private fun FeedHeader(
    title: String,
    updating: Boolean,
    filterCount: Int,
    searchQuery: String,
    onOpenFilters: () -> Unit,
    onPickDate: (() -> Unit)?,
    onRefresh: () -> Unit,
    onClearSearch: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    // The IconButton touch target is still the Material-recommended size; only the
                    // visible button is compact. This keeps neighbouring circles from touching while
                    // making the three actions read as one tidy control group.
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        HeaderActionButton(onClick = onOpenFilters) {
                            BadgedBox(badge = { if (filterCount > 0) Badge { Text("$filterCount") } }) {
                                Icon(painter = painterResource(R.drawable.ic_filter), contentDescription = "Filters", modifier = Modifier.size(18.dp))
                            }
                        }
                        if (onPickDate != null) HeaderActionButton(onClick = onPickDate) {
                            Icon(imageVector = Icons.Default.DateRange, contentDescription = "Jump to date", modifier = Modifier.size(18.dp))
                        }
                        HeaderActionButton(onClick = onRefresh) {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Refresh this day", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                if (searchQuery.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    InputChip(
                        selected = true,
                        onClick = onClearSearch,
                        label = { Text(text = "“$searchQuery”", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingIcon = { Icon(imageVector = Icons.Default.Clear, contentDescription = "Clear search", modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.height(30.dp),
                    )
                }
            }
            // A hairline of progress while any day is being fetched; the slot is always there so nothing shifts.
            Box(Modifier.fillMaxWidth().height(2.dp)) {
                if (updating) LinearProgressIndicator(modifier = Modifier.fillMaxSize(), strokeCap = StrokeCap.Butt, gapSize = 0.dp)
            }
        }
    }
}

@Composable
private fun HeaderActionButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun JumpToDateDialog(today: LocalDate, onDateSelected: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    // The picker works in UTC midnights; the day it hands back is read the same way.
    val state = rememberDatePickerState(initialSelectedDateMillis = today.toEpochDays() * 86_400_000L)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onDateSelected(LocalDate.fromEpochDays(Math.floorDiv(it, 86_400_000L))) }
            }) { Text("Go") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, showModeToggle = false)
    }
}

/** Long-press on a game: follow either side, or the league. Stored on the device only. */
@Composable
private fun FollowDialog(
    game: Game,
    league: League?,
    favorites: Set<Favorite>,
    onToggleFavorite: (Favorite) -> Unit,
    onDismiss: () -> Unit,
) {
    val sport = league?.sport ?: return
    val options = listOf(Favorite.team(game.home, sport), Favorite.team(game.away, sport), Favorite.league(league))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Follow") },
        text = {
            Column {
                Text(
                    text = "Followed teams and leagues appear together in Following. This is stored on your device only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                options.forEach { favorite ->
                    val selected = favorites.any { it.key == favorite.key }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { onToggleFavorite(favorite) }) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = if (selected) "Unfollow ${favorite.label}" else "Follow ${favorite.label}",
                                tint = if (selected) MaterialTheme.scoreColors.favorite else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                            )
                        }
                        Text(text = favorite.label, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
    )
}
