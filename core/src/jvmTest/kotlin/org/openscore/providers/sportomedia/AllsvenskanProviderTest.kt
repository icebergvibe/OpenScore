package org.openscore.providers.sportomedia

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.net.OpenScoreJson
import org.openscore.provider.NotFoundException
import org.openscore.testing.AllsvenskanSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AllsvenskanProviderTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-12T10:00:00Z")
    }
    private val fetcher = AllsvenskanSamples.register(SampleFetcher())
    private val sv = AllsvenskanProvider(fetcher, clock = fixedClock)

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "matches-for-league" to GqlResponse.serializer(SmMatchesForLeagueData.serializer()),
            "match." to GqlResponse.serializer(SmMatchData.serializer()),
            "lineups." to GqlResponse.serializer(SmLineupsData.serializer()),
            "match-stats" to GqlResponse.serializer(SmMatchStatsData.serializer()),
            "standings-for-league" to GqlResponse.serializer(SmStandingsData.serializer()),
            "teams-for-league" to GqlResponse.serializer(SmTeamsData.serializer()),
            "team.json" to GqlResponse.serializer(SmTeamData.serializer()),
            "squad.json" to GqlResponse.serializer(SmSquadData.serializer()),
            "player.json" to GqlResponse.serializer(SmPlayerData.serializer()),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("football", "allsvenskan").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = file.readText()
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 16, "typed=$typed")
    }

    @Test
    fun scoreboardDay() = runTest {
        val games = sv.gamesOn(LocalDate.parse(AllsvenskanSamples.DAY))
        assertEquals(1, games.size, "one Friday match in the round window")
        val g = games.single()
        assertEquals(AllsvenskanSamples.FINAL_ID, g.id)
        assertEquals(GameState.FINAL, g.state)
        assertEquals("BK Häcken", g.home.name)
        assertEquals("BKH", g.home.id)
        assertEquals(Score(1, 1), g.score)
        assertEquals("2026", g.seasonId)
        assertTrue(fetcher.requests.single().contains("query="))
    }

    @Test
    fun finalMatch() = runTest {
        val g = sv.game(AllsvenskanSamples.FINAL_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(1, 1), g.score)
        assertEquals("Nordic Wellness Arena", g.venue)
        assertNull(g.clock)
        assertEquals("60", g.stats["possession"]!!.home)
        assertEquals("14", g.stats["shots"]!!.home)
        assertEquals(listOf(0 to 1, 1 to 0), g.periodScores.map { it.home to it.away })
        val events = assertNotNull(g.events)
        assertEquals(FootballEventType.PERIOD_START, events.first().type, "newest-first feed reversed")
        assertEquals(FootballEventType.GAME_END, events.last().type)
        val goals = events.filter { it.type.isGoal }
        assertEquals(2, goals.size)
        val second = goals.last()
        assertEquals("56'", second.time.label)
        assertEquals(10.minutes + 34.seconds, second.time.elapsed, "gameTime 3334 s − 2700")
        assertEquals(Score(1, 1), second.score)
        val gd = assertIs<FootballGoalDetails>(second.details)
        assertEquals("Julius Lindberg", gd.scorer!!.name)
        assertTrue(gd.scorer.id.all { it.isDigit() }, "scorer resolved to a Fogis id through the lineup: ${gd.scorer.id}")
        assertEquals("David Seger", gd.assist!!.name)
        val sub = events.first { it.type == FootballEventType.SUBSTITUTION }
        assertNotNull(assertIs<SubstitutionDetails>(sub.details).playerOff)
        assertTrue(events.any { it.type == FootballEventType.YELLOW_CARD })
        assertTrue(events.any { it.rawType == "SHOT" && it.type == FootballEventType.OTHER }, "shots kept as OTHER with the raw type")
        assertEquals(2, events.count { it.type == FootballEventType.PERIOD_START })
    }

    @Test
    fun liveHeuristics() {
        val m = OpenScoreJson.decodeFromString(GqlResponse.serializer(SmMatchData.serializer()), SampleFetcher.samplesDir("football", "allsvenskan").resolve("match.final.json").readText()).data!!.match!!.match!!
        val mapper = SportomediaMapper("allsvenskan")
        val live = m.copy(status = "ONGOING", extendedStatus = "ONGOING", period = "PERIOD_SECOND_HALF", matchMinute = 67, matchMinuteWithStoppageTime = "67'")
        val g = mapper.game(live, withEvents = false)
        assertEquals(GameState.LIVE, g.state)
        assertEquals("2H", g.clock!!.period.label)
        assertEquals("67'", g.clock.time.label)
        assertEquals(22.minutes, g.clock.time.elapsed)
        val ht = m.copy(status = "ONGOING", period = null, matchEvents = listOf(SmEvent(type = "PERIOD_RESULT", period = "PERIOD_FIRST_HALF", minuteWithStoppageTime = "45+2'")) + m.matchEvents)
        assertEquals(GameState.INTERMISSION, mapper.game(ht, withEvents = false).state)
        val pre = m.copy(status = "UPCOMING", extendedStatus = "UPCOMING_STARTING")
        assertEquals(GameState.PRE_GAME, mapper.game(pre, withEvents = false).state)
    }

    @Test
    fun preMatchAndLineups() = runTest {
        val g = sv.game(AllsvenskanSamples.PRE_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score); assertNull(g.clock)
        assertEquals(emptyList(), g.events)
        assertTrue(g.stats.isEmpty(), "matchStats errors before kick-off → no stats")
        assertTrue(sv.lineups(AllsvenskanSamples.PRE_ID).isEmpty(), "formation 'fallback' + empty arrays")

        val lineups = sv.lineups(AllsvenskanSamples.FINAL_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("4231", home.formation)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertEquals(9, home.groups.first { it.kind == LineupGroupKind.BENCH }.players.size)
        assertEquals("GK", home.groups.first().players.first().position)
        assertEquals("3-4-3".replace("-", ""), lineups.last().formation)
    }

    @Test
    fun standingsTeamRosterPlayer() = runTest {
        val table = sv.standings()
        assertEquals("2026", table.seasonId)
        assertEquals(16, table.rows.size)
        val top = table.rows.first()
        assertEquals("IK Sirius", top.team.name)
        assertEquals(45, top.points)
        assertEquals(48, top.goalsFor)
        assertEquals(21, top.goalDifference)
        assertEquals("WLWWW".length, top.extra["form"]!!.length)

        val t = sv.team(AllsvenskanSamples.TEAM)
        assertEquals("AIK", t.name)
        assertEquals("Strawberry Arena", t.arena)

        val roster = sv.roster(AllsvenskanSamples.TEAM)
        assertEquals(28, roster.size)
        assertEquals("Kristoffer Nordfeldt", roster.first().name)
        assertEquals("GK", roster.first().ref.position)
        assertEquals("Sweden", roster.first().nationality)
        assertTrue(roster.all { it.teamId == "AIK" })

        val p = sv.player(AllsvenskanSamples.PLAYER_ID)
        assertEquals("Andronikos Kakoullis", p.name)
        assertEquals("FW", p.ref.position)
        assertEquals(LocalDate(2001, 5, 3), p.birthDate)
    }

    @Test
    fun notFoundAndSeasons() = runTest {
        assertFailsWith<NotFoundException> { sv.game("1") }
        assertEquals(2026, sv.currentSeason())
    }
}
