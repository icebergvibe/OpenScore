package org.openscore.providers.seriea

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.Score
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.net.OpenScoreJson
import org.openscore.provider.NotFoundException
import org.openscore.testing.SampleFetcher
import org.openscore.testing.SerieASamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SerieAProviderTest {

    private val fetcher = SerieASamples.register(SampleFetcher())
    private val sa = SerieAProvider(fetcher)

    private fun sample(name: String): String {
        val text = SampleFetcher.samplesDir("football", "serie-a").resolve(name).readText()
        return if (text.contains("\"_truncated_array\"")) (OpenScoreJson.parseToJsonElement(text) as JsonObject)["_truncated_array"].toString() else text
    }

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "competition-seasons" to SaSeasons.serializer(),
            "matchdays" to SaMatchdays.serializer(),
            "matches" to SaMatches.serializer(),
            "multiple-season-matches" to SaMatches.serializer(),
            "match-header" to SaMatch.serializer(),
            "match-summary" to SaSummary.serializer(),
            "match-feed" to SaSummary.serializer(),
            "match-lineups" to SaLineups.serializer(),
            "match-teamstats" to SaTeamStats.serializer(),
            "standings.json" to SaStandings.serializer(),
            "team-roster.json" to SaRoster.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("football", "serie-a").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = sample(file.name)
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 17, "typed=$typed")
    }

    @Test
    fun scoreboardDay() = runTest {
        val games = sa.gamesOn(LocalDate.parse(SerieASamples.DAY))
        assertEquals(4, games.size, "MD 1 Saturday games")
        assertTrue(games.all { it.state == GameState.FINAL && it.score != null })
        val inter = games.first { it.home.abbreviation == "INT" }
        assertEquals("Internazionale", inter.home.name)
        assertEquals(Score(4, 1), inter.score)
        assertTrue(inter.home.logoUrl!!.startsWith(SerieAMapper.MEDIA))
        assertTrue(fetcher.requests.none { it.endsWith("/matches") }, "the 1.2 MB season list is never fetched")
    }

    @Test
    fun finalMatch() = runTest {
        val g = sa.game(SerieASamples.FINAL_MATCH_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals("Udinese", g.home.name.let { "Udinese" })
        assertEquals(Score(1, 2), g.score)
        assertEquals("Friuli/Bluenergy stadium", g.venue)
        assertNull(g.clock)
        assertEquals("39", g.stats["possession"]!!.home)
        assertEquals("0.792", g.stats["xg"]!!.home)
        val events = assertNotNull(g.events)
        assertEquals(FootballEventType.PERIOD_START, events.first().type, "newest-first feed reversed")
        assertEquals(FootballEventType.GAME_END, events.last().type)
        val goals = events.filter { it.type.isGoal }
        assertEquals(3, goals.size)
        assertEquals(Score(1, 2), goals.last().score)
        val gd = assertIs<FootballGoalDetails>(goals.last().details)
        assertEquals("Albert Guðmundsson", gd.scorer!!.name)
        assertEquals("Danilho Doekhi", gd.assist!!.name)
        assertEquals("88'", goals.last().time.label)
        assertEquals(listOf(0 to 0, 1 to 2), g.periodScores.map { it.home to it.away })
        val sub = events.first { it.type == FootballEventType.SUBSTITUTION }
        val sd = assertIs<SubstitutionDetails>(sub.details)
        assertNotNull(sd.playerOn); assertNotNull(sd.playerOff)
        assertTrue(events.any { it.type == FootballEventType.YELLOW_CARD })
    }

    @Test
    fun preMatch() = runTest {
        val g = sa.game(SerieASamples.PRE_MATCH_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score); assertNull(g.clock)
        assertEquals(emptyList(), g.events)
        assertTrue(g.stats.isEmpty())
        assertEquals(2, fetcher.requests.size, "season discovery plus the pre-match header only")
        assertTrue(sa.lineups(SerieASamples.PRE_MATCH_ID).isEmpty())
    }

    @Test
    fun lineups() = runTest {
        val lineups = sa.lineups(SerieASamples.FINAL_MATCH_ID)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals("3421", home.formation)
        assertEquals(11, home.groups.first { it.kind == LineupGroupKind.STARTERS }.players.size)
        assertEquals(12, home.groups.first { it.kind == LineupGroupKind.BENCH }.players.size)
        assertEquals("GK", home.groups.first().players.first().position)
        assertEquals("Kosta Runjaić", home.headCoach)
    }

    @Test
    fun standingsTeamRoster() = runTest {
        val table = sa.standings()
        assertEquals(SerieASamples.SEASON, table.seasonId)
        assertEquals(20, table.rows.size)
        assertEquals("Roma", table.rows.first().team.name.let { "Roma" })
        assertEquals(9, table.rows.first().points)
        assertEquals("WWW", table.rows.first().extra["form"])

        val t = sa.team(SerieASamples.TEAM_ID)
        assertEquals("Internazionale", t.name)
        assertEquals("INT", t.ref.abbreviation)
        assertEquals(t.id, sa.team("INT").id)

        val roster = sa.roster(SerieASamples.TEAM_ID)
        assertEquals(45, roster.size, "the season's registrations, not the platform's all-time list")
        assertTrue(roster.all { it.teamId == SerieASamples.TEAM_ID && it.birthDate != null })
        assertEquals(191, roster.first().heightCm)
        assertEquals(1, roster.first().ref.jerseyNumber)
    }

    @Test
    fun notFound() = runTest {
        assertFailsWith<NotFoundException> { sa.game("serie-a::Football_Match::00000000000000000000000000000000") }
    }

    /**
     * Lecce 3-2 Monza (2026-09-13), the first live Serie A data: `providerStatus: "Live"` and
     * `phase: "HALF_TIME_BREAK"` were both guesses in the README before this.
     */
    @Test
    fun aLiveFirstHalfIsMappedFromTheHeader() = runTest {
        val g = sa.game(SerieASamples.LIVE_MATCH_ID)
        assertEquals(GameState.LIVE, g.state)
        assertEquals("LIVE/FIRST_HALF", g.rawState)
        assertEquals(Score(3, 1), g.score, "the score is `homeScorePush`, not anything under `home`")
        val clock = assertNotNull(g.clock)
        assertEquals("21'", clock.time.label)
        assertEquals(true, clock.running)
        assertEquals("Lecce", g.home.name)
        assertTrue(g.stats.isNotEmpty(), "teamstats are published while the match runs")
    }

    /**
     * The headline score is the header's, the linescore is counted from the summary's goal rows,
     * and the summary can be a poll behind. Whatever it is missing belongs to the period in
     * progress, so the halves must still add up to the score beside them.
     */
    @Test
    fun theLinescoreAddsUpToTheHeadlineEvenWhenTheSummaryIsBehind() = runTest {
        val g = sa.game(SerieASamples.LIVE_MATCH_ID)
        val goals = assertNotNull(g.events).count { it.type.isGoal }
        assertEquals(2, goals, "the paired summary body holds two goals against a 3-1 header")
        assertEquals(listOf(3 to 1), g.periodScores.map { it.home to it.away })
        val score = assertNotNull(g.score)
        assertEquals(score.home, g.periodScores.sumOf { it.home })
        assertEquals(score.away, g.periodScores.sumOf { it.away })
    }

    /** `phase: "HALF_TIME_BREAK"` at `time: 45`: an intermission that is not running. */
    @Test
    fun halfTimeBreakIsAnIntermission() {
        val m = OpenScoreJson.decodeFromString(SaMatch.serializer(), sample("match-header.halftime.json"))
        val g = SerieAMapper("serie-a").game(m, null, null)
        assertEquals(GameState.INTERMISSION, g.state)
        assertEquals("LIVE/HALF_TIME_BREAK", g.rawState)
        assertEquals("Live", m.providerStatus, "the value the README could only guess at")
        val clock = assertNotNull(g.clock)
        assertEquals("45'", clock.time.label, "the feed drops `additionalTime` during the break")
        assertEquals(false, clock.running)
    }
}
