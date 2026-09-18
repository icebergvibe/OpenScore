package org.openscore.app.ui.feed

import kotlinx.datetime.LocalDate
import org.openscore.LeagueError
import org.openscore.app.data.FavoriteFilter
import org.openscore.app.data.RacingSessionEntry
import org.openscore.app.data.SessionPhase
import org.openscore.app.ui.common.groupSubtitle
import org.openscore.app.ui.common.groupTitle
import org.openscore.model.Game
import org.openscore.model.League
import kotlin.time.Clock
import kotlin.time.Instant

/** One line of the timeline. Every row knows its day, which is what the Today button and the visible-date label read. */
sealed class TimelineRow(val key: String, val date: LocalDate) {
    class DayHeader(date: LocalDate) : TimelineRow("day-$date", date)

    /** One row standing for a run of days that have not been fetched. */
    class Scanning(val dates: List<LocalDate>, val loading: Boolean, val earlier: Boolean, today: LocalDate) :
        TimelineRow("scan-${dates.first()}", if (today in dates) today else dates.first())

    class Failed(date: LocalDate, val message: String) : TimelineRow("failed-$date", date)

    class Empty(date: LocalDate, val message: String) : TimelineRow("empty-$date", date)

    /** Leagues that did not answer for an otherwise loaded day. */
    class Partial(date: LocalDate, val failed: List<LeagueError>) : TimelineRow("partial-$date", date)

    class LeagueHeader(
        date: LocalDate,
        val league: League?,
        val leagueId: String,
        val competition: String?,
        val title: String,
        val subtitle: String?,
        val count: Int,
    ) : TimelineRow("league-$date-$leagueId-${competition.orEmpty()}", date)

    class Match(date: LocalDate, val game: Game) : TimelineRow("game-${game.leagueId}-${game.id}", date)

    /** A racing session (practice, qualifying, sprint, race): an entry to read, not a match to open. */
    class Session(date: LocalDate, val entry: RacingSessionEntry) : TimelineRow("session-${entry.leagueId}-${entry.id}", date)
}

/**
 * Flattens the window of days into what the list draws. Days not yet fetched collapse into one
 * scanning row each side of what is known; fetched empty dates are dropped except for today,
 * which stays visible as the timeline's anchor and explains the quiet state. Live is always one
 * section for "now", so it likewise says "nothing live" instead of vanishing.
 */
fun buildTimelineRows(
    dates: List<LocalDate>,
    days: Map<LocalDate, DayState>,
    spec: FeedSpec,
    query: String,
    favorites: FavoriteFilter?,
    today: LocalDate,
    leagueOrder: List<League>,
    /** When "now" is, for the sessions Live keeps: a racing series has no live state, only a schedule. */
    now: Instant = Clock.System.now(),
): List<TimelineRow> {
    val leagues = leagueOrder.associateBy { it.id }
    val order = leagueOrder.withIndex().associate { it.value.id to it.index }
    val rows = ArrayList<TimelineRow>()
    val pending = ArrayList<LocalDate>()
    var pendingLoading = false
    val needle = query.trim().lowercase()

    fun filtered(games: List<Game>): List<Game> {
        var out = FeedViewModel.filterForKind(spec, games)
        if (favorites != null) out = out.filter(favorites::matches)
        if (needle.isNotEmpty()) out = out.filter { it.matches(needle, leagues[it.leagueId]) }
        return out
    }

    fun filteredSessions(sessions: List<RacingSessionEntry>): List<RacingSessionEntry> {
        var out = if (spec.kind == FeedKind.LIVE) sessions.filter { it.phase(now) == SessionPhase.UNDER_WAY } else sessions
        if (favorites != null) out = out.filter { favorites.followsLeague(it.leagueId) }
        if (needle.isNotEmpty()) out = out.filter { it.matches(needle) }
        return out
    }

    /** One section per league (and competition): a header, then its games — or, for a racing series, its sessions. */
    fun groups(date: LocalDate, games: List<Game>, sessions: List<RacingSessionEntry>) {
        val sections = games.groupBy { it.leagueId to it.competition }.map { (key, group) -> Triple(key.first, key.second, group.map { TimelineRow.Match(date, it) }) } +
            sessions.groupBy { it.leagueId }.map { (leagueId, group) -> Triple(leagueId, null, group.map { TimelineRow.Session(date, it) }) }
        sections
            .sortedWith(compareBy({ order[it.first] ?: Int.MAX_VALUE }, { it.second }))
            .forEach { (leagueId, competition, entries) ->
                val league = leagues[leagueId]
                rows += TimelineRow.LeagueHeader(
                    date = date,
                    league = league,
                    leagueId = leagueId,
                    competition = competition,
                    title = groupTitle(league, leagueId),
                    subtitle = groupSubtitle(league, competition),
                    count = entries.size,
                )
                rows += entries
            }
    }

    // Live is "now", not a calendar: every date it spans (a league's yesterday can still be
    // running in a local morning) is folded into one section under today.
    if (spec.kind == FeedKind.LIVE) {
        val states = dates.map { days[it] }
        val loading = states.any { it == null || it is DayState.Loading || (it is DayState.Loaded && it.pending.isNotEmpty()) }
        val loaded = states.filterIsInstance<DayState.Loaded>()
        val games = filtered(loaded.flatMap { it.games }).distinctBy { it.leagueId to it.id }
        val sessions = filteredSessions(loaded.flatMap { it.sessions }).distinctBy { it.leagueId to it.id }
        rows += TimelineRow.DayHeader(today)
        when {
            games.isNotEmpty() || sessions.isNotEmpty() -> groups(today, games, sessions)
            loading -> rows += TimelineRow.Scanning(dates, loading = true, earlier = false, today = today)
            else -> rows += TimelineRow.Empty(today, if (needle.isEmpty()) "Nothing live right now" else "Nothing live matches “$query”")
        }
        val failed = loaded.flatMap { it.failed }.distinctBy { it.leagueId }
        if (failed.isNotEmpty()) rows += TimelineRow.Partial(today, failed)
        states.filterIsInstance<DayState.Error>().firstOrNull()?.let { rows += TimelineRow.Failed(today, it.message) }
        return rows
    }

    fun flush() {
        if (pending.isEmpty()) return
        rows += TimelineRow.Scanning(pending.toList(), pendingLoading, earlier = pending.last() < today, today = today)
        pending.clear()
        pendingLoading = false
    }

    for (date in dates) {
        when (val state = days[date]) {
            null -> pending += date
            DayState.Loading -> { pending += date; pendingLoading = true }
            is DayState.Error -> {
                flush()
                rows += TimelineRow.DayHeader(date)
                rows += TimelineRow.Failed(date, state.message)
            }
            is DayState.Loaded -> {
                val games = filtered(state.games)
                val sessions = filteredSessions(state.sessions)
                val empty = games.isEmpty() && sessions.isEmpty()
                // Nothing yet from the leagues that have answered: still a loading day until the rest are in.
                if (empty && state.pending.isNotEmpty()) { pending += date; pendingLoading = true; continue }
                flush()
                // Keep today's resolved empty state visible. Without an anchor the timeline would
                // open on two unexplained "load days" rows, which is especially confusing in Following.
                if (empty) {
                    if (date == today) {
                        rows += TimelineRow.DayHeader(date)
                        val message = when {
                            needle.isNotEmpty() -> "No games today match “$query”"
                            favorites != null -> "No games for the teams and leagues you follow today"
                            else -> "No games today"
                        }
                        rows += TimelineRow.Empty(date, message)
                        if (state.failed.isNotEmpty()) rows += TimelineRow.Partial(date, state.failed)
                    }
                    continue
                }
                rows += TimelineRow.DayHeader(date)
                groups(date, games, sessions)
                if (state.failed.isNotEmpty()) rows += TimelineRow.Partial(date, state.failed)
            }
        }
    }
    flush()
    return rows
}

private fun Game.matches(needle: String, league: League?): Boolean =
    listOfNotNull(home.name, away.name, home.abbreviation, away.abbreviation, competition, league?.name, venue)
        .any { it.lowercase().contains(needle) }

/** The first day heading on or after [today]: where the list settles at launch. -1 until one exists. */
fun List<TimelineRow>.anchorIndex(today: LocalDate): Int =
    indexOfFirst { it is TimelineRow.DayHeader && it.date >= today }

/** The heading for [date], or the scanning row that stands for it. */
fun List<TimelineRow>.indexOfDay(date: LocalDate): Int {
    val exact = indexOfFirst { (it is TimelineRow.DayHeader && it.date == date) || (it is TimelineRow.Scanning && date in it.dates) }
    if (exact >= 0) return exact
    return indexOfFirst { it is TimelineRow.DayHeader && it.date > date }.takeIf { it >= 0 } ?: lastIndex
}
