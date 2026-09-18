package org.openscore.providers.mls

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.net.OpenScoreJson
import org.openscore.testing.MlsSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class MlsProviderTest {
    private val fetcher = MlsSamples.register(SampleFetcher())
    private val mls = MlsProvider(fetcher)

    @Test
    fun everySampleParses() {
        val typed: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "seasons" to MlsSeasons.serializer(), "matches.day" to MlsSchedule.serializer(),
            "match.final" to MlsMatch.serializer(), "match-events.final" to MlsEvents.serializer(),
            "match-stats" to MlsMatchStats.serializer(), "standings" to MlsStandings.serializer(),
            "roster" to MlsRoster.serializer(), "match-metadata" to MlsMetadataMatch.serializer(),
        )
        val failures = mutableListOf<String>()
        var count = 0
        for (file in SampleFetcher.samplesDir("football", "mls").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val strategy = typed.firstOrNull { file.name.startsWith(it.first) }?.second ?: JsonElement.serializer()
            try { OpenScoreJson.decodeFromString(strategy, file.readText()); count++ }
            catch (e: Exception) { failures += "${file.name}: ${e.message?.lineSequence()?.first()}" }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(count >= 10)
    }

    @Test
    fun upcomingDayAndPreGameFallback() = runTest {
        val games = mls.gamesOn(LocalDate.parse(MlsSamples.DAY))
        assertTrue(games.isNotEmpty())
        val game = games.first { it.id == MlsSamples.PRE_MATCH_ID }
        assertEquals(GameState.SCHEDULED, game.state)
        assertEquals("Inter Miami CF", game.home.name)
        assertTrue(games.all { it.home.logoUrl != null && it.away.logoUrl != null })
        assertNull(game.score)
        assertEquals(2, fetcher.requests.size, "season discovery plus one compact day request")
        assertTrue(fetcher.requests.none { it.contains("/api/matches/") }, "day listings do not fan out for presentation-only metadata")

        val detail = mls.game(MlsSamples.PRE_MATCH_ID)
        assertEquals(GameState.SCHEDULED, detail.state)
        assertEquals("MIA", detail.home.abbreviation)
        assertNotNull(detail.home.logoUrl)
        assertNull(detail.events)
    }

    @Test
    fun dayWithoutMatchesIsAnEmptySchedule() = runTest {
        val games = mls.gamesOn(LocalDate.parse("2026-09-15"))

        assertTrue(games.isEmpty())
        assertEquals(2, fetcher.requests.size, "season discovery plus the missing day request")
    }

    @Test
    fun finalGameEventsStatsAndLineups() = runTest {
        val game = mls.game(MlsSamples.FINAL_MATCH_ID)
        assertEquals(GameState.FINAL, game.state)
        assertEquals(Score(0, 5), game.score)
        assertEquals("Snapdragon Stadium", game.venue)
        assertNotNull(game.home.logoUrl)
        assertNotNull(game.away.logoUrl)
        assertNull(game.clock)
        assertEquals("54.22", game.stats["possession"]!!.home)
        assertEquals("3.1911", game.stats["xg"]!!.away)
        val events = assertNotNull(game.events)
        assertEquals(FootballEventType.GAME_END, events.last().type)
        assertTrue(events.any { it.time.period.number == 1 }, "the paginated timeline includes first-half events")
        assertTrue(events.count { it.type.isGoal } >= 2, "the sampled timeline contains the scoring events it exposes")
        val own = events.first { it.type == FootballEventType.OWN_GOAL }
        val ownDetails = assertIs<FootballGoalDetails>(own.details)
        assertEquals(GoalKind.OWN_GOAL, ownDetails.kind)
        // Godoy (San Diego) put it in his own net: the feed says `team_id` San Diego, the goal is Philadelphia's.
        assertEquals("MLS-OBJ-0000EI", ownDetails.scorer?.id)
        assertEquals(game.away, own.team)
        assertEquals(Score(0, 4), own.score)
        assertEquals("90'+4", events.last { it.type.isGoal }.time.label)

        val lineups = mls.lineups(MlsSamples.FINAL_MATCH_ID)
        assertEquals(2, lineups.size)
        assertEquals(11, lineups.first().groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertEquals("433", lineups.first().formation)
    }

    @Test
    fun standingsAndRoster() = runTest {
        val table = mls.standings()
        assertEquals(30, table.rows.size)
        assertEquals("Nashville SC", table.rows.first().team.name)
        assertEquals(54, table.rows.first().points)
        assertTrue(table.rows.all { it.team.logoUrl != null })

        val roster = mls.roster(MlsSamples.TEAM_ID)
        assertTrue(roster.size >= 20)
        assertEquals("Pablo Sisniega", roster.first().name)
        assertEquals("goalkeeper", roster.first().ref.position)
    }

    @Test
    fun clubProfileAndSeasonScheduleAcrossCompetitions() = runTest {
        val team = mls.team(MlsSamples.TEAM_ID)
        assertEquals("San Diego FC", team.ref.name)
        assertEquals("SD", team.ref.abbreviation)
        assertEquals("Snapdragon Stadium", team.arena)
        assertEquals("San Diego, CA", team.placeName)
        assertNotNull(team.ref.logoUrl)
        assertEquals("san-diego", team.ref.clubId)

        val season = mls.teamSchedule(MlsSamples.TEAM_ID, LocalDate(2026, 1, 1), LocalDate(2026, 12, 31))
        assertTrue(season.none { it.competition == "MLS Test" }, "the internal test fixtures are dropped")
        assertEquals(42, season.size)
        assertTrue(season.all { it.home.id == MlsSamples.TEAM_ID || it.away.id == MlsSamples.TEAM_ID })
        assertEquals(setOf("CONCACAF Champions Cup", "Major League Soccer - Regular Season", "Leagues Cup", "Club Friendly Matches"), season.map { it.competition }.toSet())
        assertEquals(LocalDate(2026, 2, 4), season.first().scheduleDate, "UTC dates, like the day listing")
        assertEquals(Score(4, 1), season.first().score)
        assertEquals(6, mls.teamSchedule(MlsSamples.TEAM_ID, LocalDate(2026, 9, 1), LocalDate(2026, 9, 30)).size)
    }
}
