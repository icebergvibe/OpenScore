package org.openscore.app.ui.feed

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import kotlinx.datetime.TimeZone
import org.openscore.app.Fixtures
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Sport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TimelineRowsTest {

    private val today = LocalDate(2026, 9, 13)
    private val dates = (-3..3).map { today.plus(it, DateTimeUnit.DAY) }
    private val leagues = listOf(
        League("premier-league", Sport.FOOTBALL, "Premier League", "GB", TimeZone.of("Europe/London")),
        League("serie-a", Sport.FOOTBALL, "Serie A", "IT", TimeZone.of("Europe/Rome")),
    )
    private val spec = FeedSpec(FeedKind.GAMES, Sport.FOOTBALL, leagues.map { it.id })

    private fun rows(days: Map<LocalDate, DayState>, spec: FeedSpec = this.spec, query: String = "") =
        buildTimelineRows(dates, days, spec, query, null, today, leagues)

    @Test
    fun `unfetched days collapse into one scanning row that stands for today`() {
        val rows = rows(emptyMap())
        assertEquals(1, rows.size)
        val scan = assertIs<TimelineRow.Scanning>(rows.single())
        assertEquals(dates, scan.dates)
        assertEquals(today, scan.date)
    }

    @Test
    fun `a loaded day groups its games under league headers in provider order`() {
        val games = listOf(
            Fixtures.game("serie-a", "s1"),
            Fixtures.game("premier-league", "p1"),
            Fixtures.game("premier-league", "p2"),
        )
        val rows = rows(mapOf(today to DayState.Loaded(games, emptyList())))
        val kinds = rows.map { it::class.simpleName }
        assertEquals(
            listOf("Scanning", "DayHeader", "LeagueHeader", "Match", "Match", "LeagueHeader", "Match", "Scanning"),
            kinds,
        )
        val first = rows[2] as TimelineRow.LeagueHeader
        assertEquals("Premier League", first.title)
        assertEquals(2, first.count)
        assertTrue((rows.first() as TimelineRow.Scanning).earlier)
        assertTrue(!(rows.last() as TimelineRow.Scanning).earlier)
    }

    @Test
    fun `a day with nothing yet but leagues pending is still loading`() {
        val partial = mapOf(today to DayState.Loaded(emptyList(), emptyList(), pending = listOf("serie-a")))
        val scan = assertIs<TimelineRow.Scanning>(rows(partial).single())
        assertTrue(scan.loading)
        assertTrue(today in scan.dates)

        val live = rows(partial, FeedSpec(FeedKind.LIVE, Sport.FOOTBALL, spec.leagueIds))
        assertIs<TimelineRow.Scanning>(live[1], "the live feed waits rather than saying nothing is live")

        // Once a league has games, the day is drawn with them while the rest are still to come.
        val some = mapOf(today to DayState.Loaded(listOf(Fixtures.game("premier-league", "p1")), emptyList(), pending = listOf("serie-a")))
        assertEquals(listOf("Scanning", "DayHeader", "LeagueHeader", "Match", "Scanning"), rows(some).map { it::class.simpleName })
    }

    @Test
    fun `an empty today remains an explained anchor`() {
        val empty = mapOf(today to DayState.Loaded(emptyList(), emptyList()))
        val schedule = rows(empty)
        assertIs<TimelineRow.DayHeader>(schedule[1])
        assertEquals(today, schedule[1].date)
        assertEquals("No games today", assertIs<TimelineRow.Empty>(schedule[2]).message)

        // Live spans only the day or two it asks for, and says so once they have all answered.
        val live = buildTimelineRows(listOf(today), empty, spec.copy(kind = FeedKind.LIVE), "", null, today, leagues)
        assertIs<TimelineRow.DayHeader>(live[0])
        assertIs<TimelineRow.Empty>(live[1])
        assertEquals(2, live.size)
    }

    @Test
    fun `the live feed keeps only games in play`() {
        val games = listOf(
            Fixtures.game("premier-league", "p1", state = GameState.LIVE),
            Fixtures.game("premier-league", "p2", state = GameState.SCHEDULED),
        )
        val rows = rows(mapOf(today to DayState.Loaded(games, emptyList())), spec.copy(kind = FeedKind.LIVE))
        assertEquals(listOf("p1"), rows.filterIsInstance<TimelineRow.Match>().map { it.game.id })
        assertTrue(rows.none { it is TimelineRow.Scanning })
    }

    @Test
    fun `the live feed folds every date it spans into one section and waits for them all`() {
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        val live = spec.copy(kind = FeedKind.LIVE)
        val running = mapOf(yesterday to DayState.Loaded(listOf(Fixtures.game("premier-league", "late", state = GameState.LIVE)), emptyList()))
        val waiting = buildTimelineRows(listOf(yesterday, today), running, live, "", null, today, leagues)
        // Today is still unfetched, but the game already known is shown rather than a spinner.
        assertEquals(listOf("late"), waiting.filterIsInstance<TimelineRow.Match>().map { it.game.id })
        assertEquals(today, waiting.first().date)

        val nothing = buildTimelineRows(listOf(yesterday, today), mapOf(yesterday to DayState.Loading), live, "", null, today, leagues)
        assertIs<TimelineRow.Scanning>(nothing[1])
    }

    @Test
    fun `search narrows by team name`() {
        val games = listOf(
            Fixtures.game("premier-league", "p1", home = Fixtures.team("premier-league", "ars", "Arsenal")),
            Fixtures.game("premier-league", "p2"),
        )
        val rows = rows(mapOf(today to DayState.Loaded(games, emptyList())), query = "arsenal")
        assertEquals(listOf("p1"), rows.filterIsInstance<TimelineRow.Match>().map { it.game.id })
    }

    @Test
    fun `today remains the anchor when it is empty`() {
        val yesterday = today.plus(-1, DateTimeUnit.DAY)
        val tomorrow = today.plus(1, DateTimeUnit.DAY)
        val days = mapOf(
            yesterday to DayState.Loaded(listOf(Fixtures.game(id = "y")), emptyList()),
            today to DayState.Loaded(emptyList(), emptyList()),
            tomorrow to DayState.Loaded(listOf(Fixtures.game(id = "t")), emptyList()),
        )
        val rows = rows(days)
        val anchor = rows[rows.anchorIndex(today)]
        assertIs<TimelineRow.DayHeader>(anchor)
        assertEquals(today, anchor.date)
    }
}
