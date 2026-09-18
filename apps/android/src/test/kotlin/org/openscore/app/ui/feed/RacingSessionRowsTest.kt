package org.openscore.app.ui.feed

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import org.openscore.app.Fixtures
import org.openscore.app.data.Favorite
import org.openscore.app.data.FavoriteFilter
import org.openscore.app.data.RacingSessionEntry
import org.openscore.app.data.SessionPhase
import org.openscore.app.data.sessionsOn
import org.openscore.model.League
import org.openscore.model.Sport
import org.openscore.model.motorsport.RacingRound
import org.openscore.model.motorsport.RacingSeason
import org.openscore.model.motorsport.RacingSession
import org.openscore.model.motorsport.RacingSessionKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/** A racing series in the feeds: sessions on their days, "now" read off the schedule alone. */
class RacingSessionRowsTest {

    private val today = LocalDate(2026, 9, 26)
    private val dates = (-1..1).map { today.plus(it, DateTimeUnit.DAY) }
    private val f1 = League("f1", Sport.MOTORSPORT, "Formula 1", "INT")
    private val leagues = listOf(League("premier-league", Sport.FOOTBALL, "Premier League", "GB"), f1)

    private val qualifying = RacingSession("f1-2026-17-qualifying", "2026-17", "Qualifying", RacingSessionKind.QUALIFYING, Instant.parse("2026-09-26T12:00:00Z"))
    private val race = RacingSession("f1-2026-17-race", "2026-17", "Race", RacingSessionKind.RACE, Instant.parse("2026-09-27T11:00:00Z"))
    private val round = RacingRound("2026-17", 17, "Azerbaijan Grand Prix", "Baku City Circuit", "Baku", "Azerbaijan", listOf(qualifying, race))
    private val season = RacingSeason(2026, listOf(round))

    @Test
    fun `a season is cut into the sessions of one day in the reader's zone`() {
        val utc = season.sessionsOn(today, TimeZone.UTC, "f1")
        assertEquals(listOf("Qualifying"), utc.map { it.session.name })
        // Sunday's race at 11:00Z is still Saturday evening twelve hours behind UTC.
        val west = season.sessionsOn(today, TimeZone.of("Etc/GMT+12"), "f1")
        assertEquals(listOf("Qualifying", "Race"), west.map { it.session.name })
    }

    @Test
    fun `the phase follows the schedule and the session kind's window`() {
        val entry = RacingSessionEntry("f1", round, race)
        assertEquals(SessionPhase.UPCOMING, entry.phase(race.startsAt - 1.minutes))
        assertEquals(SessionPhase.UNDER_WAY, entry.phase(race.startsAt))
        assertEquals(SessionPhase.UNDER_WAY, entry.phase(race.startsAt + 2.hours))
        assertEquals(SessionPhase.OVER, entry.phase(race.startsAt + 2.hours + 31.minutes))
    }

    @Test
    fun `following lists a followed series' sessions under its own header`() {
        val day = DayState.Loaded(
            games = listOf(Fixtures.game("premier-league", "p1")),
            failed = emptyList(),
            sessions = listOf(RacingSessionEntry("f1", round, qualifying)),
        )
        val spec = FeedSpec(FeedKind.FAVORITES, null, listOf("premier-league", "f1"))
        val followingF1 = FavoriteFilter(setOf(Favorite.league(f1)))
        val rows = buildTimelineRows(dates, mapOf(today to day), spec, "", followingF1, today, leagues, now = qualifying.startsAt - 1.hours)
        assertEquals(listOf("Scanning", "DayHeader", "LeagueHeader", "Session", "Scanning"), rows.map { it::class.simpleName })
        val header = assertIs<TimelineRow.LeagueHeader>(rows[2])
        assertEquals("Formula 1", header.title)
        assertEquals(1, header.count)
        assertEquals("Qualifying", assertIs<TimelineRow.Session>(rows[3]).entry.session.name)

        // A series that is not followed contributes nothing, even when its sessions were loaded.
        val followingPl = FavoriteFilter(setOf(Favorite.league(leagues[0])))
        val plOnly = buildTimelineRows(dates, mapOf(today to day), spec, "", followingPl, today, leagues, now = qualifying.startsAt)
        assertEquals(listOf("p1"), plOnly.filterIsInstance<TimelineRow.Match>().map { it.game.id })
        assertEquals(0, plOnly.count { it is TimelineRow.Session })
    }

    @Test
    fun `live keeps only the sessions the schedule says are under way`() {
        val day = DayState.Loaded(emptyList(), emptyList(), sessions = listOf(RacingSessionEntry("f1", round, qualifying)))
        val live = FeedSpec(FeedKind.LIVE, Sport.MOTORSPORT, listOf("f1"))
        val during = buildTimelineRows(listOf(today), mapOf(today to day), live, "", null, today, leagues, now = qualifying.startsAt + 10.minutes)
        assertEquals(listOf("DayHeader", "LeagueHeader", "Session"), during.map { it::class.simpleName })

        val before = buildTimelineRows(listOf(today), mapOf(today to day), live, "", null, today, leagues, now = qualifying.startsAt - 10.minutes)
        assertEquals("Nothing live right now", assertIs<TimelineRow.Empty>(before[1]).message)
    }

    @Test
    fun `search reaches the round, circuit and session`() {
        val day = DayState.Loaded(emptyList(), emptyList(), sessions = listOf(RacingSessionEntry("f1", round, qualifying)))
        val spec = FeedSpec(FeedKind.FAVORITES, null, listOf("f1"))
        val following = FavoriteFilter(setOf(Favorite.league(f1)))
        fun sessionsFor(query: String) = buildTimelineRows(dates, mapOf(today to day), spec, query, following, today, leagues, now = qualifying.startsAt).count { it is TimelineRow.Session }
        assertEquals(1, sessionsFor("baku"))
        assertEquals(1, sessionsFor("qualif"))
        assertEquals(0, sessionsFor("monza"))
    }
}
