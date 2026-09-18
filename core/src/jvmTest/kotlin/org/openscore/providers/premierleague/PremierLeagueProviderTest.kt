package org.openscore.providers.premierleague

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.TeamRef
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.net.OpenScoreJson
import org.openscore.provider.NotFoundException
import org.openscore.testing.PremierLeagueSamples
import org.openscore.testing.SampleFetcher
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Clock
import kotlin.time.Instant

class PremierLeagueProviderTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-11T10:00:00Z")
    }
    private val fetcher = PremierLeagueSamples.register(SampleFetcher())
    private val pl = PremierLeagueProvider(fetcher, clock = fixedClock)

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "matches" to PlPage.serializer(PlMatch.serializer()),
            "matchweek-matches" to PlPage.serializer(PlMatch.serializer()),
            "match." to PlMatch.serializer(),
            "timeline" to ListSerializer(PlTimelineEvent.serializer()),
            "events" to PlEvents.serializer(),
            "lineups" to PlLineups.serializer(),
            "stats" to ListSerializer(PlTeamStats.serializer()),
            "standings" to PlStandings.serializer(),
            "teams.json" to PlPage.serializer(PlTeam.serializer()),
            "squad" to PlSquad.serializer(),
            "playerinfo" to PlSquadPlayer.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("football", "premier-league").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = file.readText()
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) && file.name != "match.404.json" }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 18, "typed=$typed")
    }

    @Test
    fun scoreboardDay() = runTest {
        val games = pl.gamesOn(LocalDate.parse(PremierLeagueSamples.DAY))
        assertEquals(2, games.size)
        val g = games.first()
        assertEquals("2026-09-13T13:00:00Z", g.startTime.toString(), "14:00 BST → 13:00Z")
        assertEquals(GameState.SCHEDULED, g.state)
        assertEquals("COV", g.home.abbreviation)
        assertNull(g.score)
        assertEquals("2026", g.seasonId)
    }

    @Test
    fun finalMatch() = runTest {
        val g = pl.game(PremierLeagueSamples.FINAL_MATCH_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals("Arsenal", g.home.name)
        assertEquals("ARS", g.home.abbreviation)
        assertEquals("${PremierLeagueMapper.BADGE_BASE}/t3.png", g.home.logoUrl)
        assertEquals(Score(2, 1), g.score)
        assertEquals(listOf(1 to 1, 1 to 0), g.periodScores.map { it.home to it.away })
        assertEquals("2026-09-06T15:30:00Z", g.startTime.toString())
        assertEquals("Emirates Stadium, London", g.venue)
        assertNull(g.clock)
        assertEquals("54.5", g.stats["possession"]!!.home)
        assertEquals("16", g.stats["shots"]!!.home)
        val events = assertNotNull(g.events)
        val goals = events.filter { it.type.isGoal }
        assertEquals(3, goals.size)
        assertEquals(listOf(Score(0, 1), Score(1, 1), Score(2, 1)), goals.map { it.score })
        assertEquals("2'", goals.first().time.label)
        val gd = assertIs<FootballGoalDetails>(goals.first().details)
        assertTrue(!gd.scorer!!.name.startsWith("#"), "scorer named from the lineup: ${gd.scorer.name}")
        // `events` stamps the conventional minute (2), the timeline the floor minute (1); the assist join bridges them.
        assertEquals(listOf("551210", "204480", "439509"), goals.map { assertIs<FootballGoalDetails>(it.details).assist?.id })
        assertTrue(events.any { it.type == FootballEventType.GOAL_DISALLOWED })
        val subs = events.filter { it.type == FootballEventType.SUBSTITUTION }
        assertTrue(subs.isNotEmpty())
        assertTrue(subs.all { assertIs<SubstitutionDetails>(it.details).playerOff != null }, "OFF/ON pairs merged")
        assertEquals(FootballEventType.PERIOD_START, events.first().type)
        assertEquals(FootballEventType.GAME_END, events.last().type)
        assertTrue(events.any { it.time.label!!.startsWith("90'+") }, "stoppage-time labels")
    }

    @Test
    fun preMatch() = runTest {
        val g = pl.game(PremierLeagueSamples.PRE_MATCH_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score); assertNull(g.clock)
        assertEquals(emptyList(), g.events)
        assertTrue(g.stats.isEmpty())
        assertEquals(1, fetcher.requests.size, "scheduled detail reads only the match header")
        assertTrue(pl.lineups(PremierLeagueSamples.PRE_MATCH_ID).isEmpty())
    }

    @Test
    fun lineups() = runTest {
        val lineups = pl.lineups(PremierLeagueSamples.FINAL_MATCH_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("4231", home.formation)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertEquals(9, home.groups.first { it.kind == LineupGroupKind.BENCH }.players.size)
        assertEquals("David Raya", home.groups.first().players.first().name)
        assertEquals("GK", home.groups.first().players.first().position)
        assertEquals("Mikel Arteta", home.headCoach)
    }

    @Test
    fun standingsTeamRosterPlayer() = runTest {
        val table = pl.standings()
        assertEquals("2026", table.seasonId)
        assertEquals(20, table.rows.size)
        assertEquals("Manchester City", table.rows.first().team.name)
        assertEquals(5, table.rows.first().goalDifference)

        val t = pl.team(PremierLeagueSamples.TEAM_ID)
        assertEquals("Arsenal", t.name)
        assertEquals("Emirates Stadium", t.arena)
        assertEquals("${PremierLeagueMapper.BADGE_BASE}/t3.png", t.ref.logoUrl)
        assertEquals(t.id, pl.team("ars").id)

        val roster = pl.roster(PremierLeagueSamples.TEAM_ID)
        assertEquals(25, roster.size)
        assertEquals("David Raya", roster.first().name)
        assertEquals("ES", roster.first().nationality)
        assertEquals(183, roster.first().heightCm)
        assertTrue(roster.all { it.teamId == "3" })

        val p = pl.player(PremierLeagueSamples.PLAYER_ID)
        assertEquals("Kai Havertz", p.name)
        assertEquals("219847", p.id)
        assertEquals("FW", p.ref.position)
        assertEquals("L", p.handedness)
        assertEquals("3", p.teamId)
    }

    @Test
    fun notFound() = runTest {
        assertFailsWith<NotFoundException> { pl.game("1") }
        assertEquals("2026", pl.currentSeason())
    }

    /** Leeds 4–1 Newcastle (2026-09-14): Miley's own goal is credited to Leeds and opens the scoring. */
    @Test
    fun ownGoalIsCreditedToTheBeneficiary() {
        val json = OpenScoreJson
        val dir = SampleFetcher.samplesDir("football", "premier-league")
        val timeline = json.decodeFromString(ListSerializer(PlTimelineEvent.serializer()), File(dir, "timeline.final.own-goal.json").readText())
        val grouped = json.decodeFromString(PlEvents.serializer(), File(dir, "events.final.own-goal.json").readText())
        val leeds = TeamRef("premier-league", "2", "Leeds United", "LEE")
        val newcastle = TeamRef("premier-league", "4", "Newcastle United", "NEW")
        val events = PremierLeagueMapper("premier-league").events(timeline, grouped, null, leeds, newcastle)
        val goals = events.filter { it.type.isGoal }
        assertEquals(listOf(Score(1, 0), Score(2, 0), Score(3, 0), Score(4, 0), Score(4, 1)), goals.map { it.score })
        val own = goals.first()
        assertEquals(FootballEventType.OWN_GOAL, own.type)
        assertEquals(leeds, own.team)
        assertEquals("547719", assertIs<FootballGoalDetails>(own.details).scorer?.id)
        assertEquals("32'", own.time.label)
        assertEquals(newcastle, goals.last().team)
    }
}
