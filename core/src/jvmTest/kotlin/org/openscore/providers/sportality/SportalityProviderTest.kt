package org.openscore.providers.sportality

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.ShootoutAttemptDetails
import org.openscore.model.hockey.Strength
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.testing.SampleFetcher
import org.openscore.testing.SportalitySamples
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
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Replay coverage for the Sportality provider still used by SHL. */
abstract class SportalityProviderTestBase(
    private val samples: SportalitySamples,
    private val leagueId: String,
    private val expectedTeams: Int,
    private val finalHomeName: String,
    private val finalScore: Score,
    private val playerName: String,
) {
    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-11T10:00:00Z")
    }
    protected val fetcher = samples.register(SampleFetcher())
    protected abstract val provider: SportalityProvider
    protected val clock: Clock get() = fixedClock

    private fun sample(name: String): String {
        val text = File(SampleFetcher.samplesDir("hockey", samples.sampleDir), name).readText()
        return if (text.contains("\"_truncated_array\"")) (OpenScoreJson.parseToJsonElement(text) as JsonObject)["_truncated_array"].toString() else text
    }

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "season-series-game-types-filter" to SptFilter.serializer(),
            "gameheader" to MapSerializer(String.serializer(), ListSerializer(SptHeaderGame.serializer())),
            "game-schedule" to SptSchedule.serializer(),
            "game-info." to SptGameInfoResponse.serializer(),
            "game-overview" to SptOverview.serializer(),
            "play-by-play" to ListSerializer(SptEvent.serializer()),
            "boxscore" to SptBoxscore.serializer(),
            "league-standings" to SptStandings.serializer(),
            "all-teams" to ListSerializer(SptTeam.serializer()),
            "athletes-by-team" to ListSerializer(SptAthleteGroup.serializer()),
            "athlete-profile-page" to SptProfilePage.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("hockey", samples.sampleDir).listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val text = sample(file.name)
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ } else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 13, "typed=$typed")
    }

    @Test
    fun scoreboardFromGameheader() = runTest {
        val games = provider.gamesOn(LocalDate.parse(samples.headerDate))
        assertEquals(7, games.size)
        val g = games.first()
        assertEquals(leagueId, g.leagueId)
        assertEquals(samples.preGameId, g.id)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertTrue(g.home.id.contains('-') || g.home.id.length == 10, "team id resolved to the platform UUID: ${g.home.id}")
        assertNotNull(g.home.abbreviation)
        assertEquals(samples.ssgtCurrent, g.seasonId)
        assertTrue(fetcher.requests.none { it.contains("game-overview") }, "no overview calls for games that have not started")
        assertTrue(games.all { it.home.id != it.home.abbreviation }, "every gameheader team resolved through all-teams")
    }

    @Test
    fun scoreboardOutsideHeaderWindowUsesSchedule() = runTest {
        val games = provider.gamesOn(LocalDate(2026, 10, 3))
        // The truncated schedule sample only holds the first games; the point is the fallback path and the mapping.
        assertTrue(fetcher.requests.any { it.contains("game-schedule") })
        assertTrue(games.all { it.state == GameState.SCHEDULED && it.score == null })
    }

    @Test
    fun finalGame() = runTest {
        val g = provider.game(samples.finalGameId)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals(finalHomeName, g.home.name)
        assertEquals(finalScore, g.score)
        assertEquals(listOf("1", "2", "3"), g.periodScores.map { it.period.label })
        assertEquals(finalScore.home, g.periodScores.sumOf { it.home })
        assertEquals(finalScore.away, g.periodScores.sumOf { it.away })
        assertNull(g.clock)
        val events = assertNotNull(g.events)
        assertTrue(events.size > 50)
        assertEquals(events.sortedWith(compareBy({ it.period.number }, { it.time.elapsed })), events, "chronological, oldest first")
        assertEquals(HockeyEventType.PERIOD_START, events.first().type)
        assertEquals(HockeyEventType.PERIOD_END, events.last().type)
        val goals = events.filter { it.type == HockeyEventType.GOAL }
        assertEquals(finalScore.home + finalScore.away, goals.size)
        assertTrue(goals.all { it.score != null && it.coordinates != null && it.players.isNotEmpty() })
        val gd = assertIs<GoalDetails>(goals.first().details)
        assertNotNull(gd.strength)
        val penalty = events.first { it.type == HockeyEventType.PENALTY && it.players.isNotEmpty() }
        val pd = assertIs<PenaltyDetails>(penalty.details)
        assertEquals(2, pd.minutes)
        assertNotNull(pd.infraction)
        assertEquals("Minor", pd.severity)
        assertTrue(events.any { it.type == HockeyEventType.SHOT })
        assertTrue(events.any { it.type == HockeyEventType.GOALIE_CHANGE })
    }

    @Test
    fun preGameHasEmptyBodies() = runTest {
        val g = provider.game(samples.preGameId)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertNull(g.clock)
        assertEquals(emptyList(), g.events)
        assertTrue(g.periodScores.isEmpty())
        assertTrue(provider.lineups(samples.preGameId).isEmpty())
    }

    @Test
    fun shootoutEvents() {
        val info = OpenScoreJson.decodeFromString(SptGameInfoResponse.serializer(), sample("game-info.final.json"))
        val pbp = OpenScoreJson.decodeFromString(ListSerializer(SptEvent.serializer()), sample("play-by-play.final-shootout.json"))
        val mapper = SportalityMapper(leagueId)
        val overview = SptOverview(gameUuid = samples.shootoutGameId, homeGoals = pbp.first().homeGoals ?: 0, awayGoals = 0, state = "GameEnded", time = SptOverviewTime(99, "00:00"))
        val g = mapper.game(info.copy(gameInfo = info.gameInfo.copy(gameUuid = samples.shootoutGameId, shootout = true, overtime = true)), overview, pbp)
        assertEquals(GameEnding.SHOOTOUT, g.ending)
        assertEquals(listOf("1", "2", "3", "OT", "SO"), g.periodScores.map { it.period.label })
        assertEquals(PeriodType.SHOOTOUT, g.periodScores.last().period.type)
        val events = assertNotNull(g.events)
        val attempts = events.filter { it.type == HockeyEventType.SHOOTOUT_ATTEMPT }
        assertTrue(attempts.size >= 6)
        assertTrue(attempts.any { assertIs<ShootoutAttemptDetails>(it.details).scored })
        assertTrue(events.none { it.type == HockeyEventType.GOAL && it.period.type == PeriodType.SHOOTOUT }, "shootout goals are attempts, not goals")
        assertEquals(5, attempts.first().period.number)
    }

    @Test
    fun liveClockFromOverview() {
        val mapper = SportalityMapper(leagueId)
        val info = OpenScoreJson.decodeFromString(SptGameInfoResponse.serializer(), sample("game-info.final.json"))
        val live = SptOverview(gameUuid = "x", homeGoals = 1, awayGoals = 0, state = "Ongoing", time = SptOverviewTime(2, "12:34"))
        val g = mapper.game(info, live, emptyList())
        assertEquals(GameState.LIVE, g.state)
        assertEquals(Score(1, 0), g.score)
        val clock = assertNotNull(g.clock)
        assertEquals("2", clock.period.label)
        assertEquals(12.minutes + 34.seconds, clock.time.elapsed)
        assertEquals(7.minutes + 26.seconds, clock.time.remaining)
        assertNull(clock.running)
        assertEquals(GameState.INTERMISSION, mapper.game(info, live.copy(state = "PeriodBreak"), emptyList()).state)
        assertEquals("SO", mapper.game(info, live.copy(time = SptOverviewTime(99, "00:00")), emptyList()).clock!!.period.label)
    }

    @Test
    fun lineupsWithLines() = runTest {
        val lineups = provider.lineups(samples.finalGameId)
        assertEquals(2, lineups.size)
        val home = lineups.first()
        assertEquals(finalHomeName, home.team.name)
        assertEquals(LineupGroupKind.GOALIES, home.groups.first().kind)
        assertEquals(2, home.groups.first().players.size)
        assertEquals(3, home.groups.first { it.label == "Line 1" }.players.size)
        assertEquals(2, home.groups.first { it.label == "Pairing 1" }.players.size)
        assertTrue(home.players.all { it.jerseyNumber != null && it.position != null && !it.name.startsWith("#") })
    }

    @Test
    fun standingsCurrentAndPrevious() = runTest {
        val empty = provider.standings()
        assertEquals(samples.ssgtCurrent, empty.seasonId)
        assertTrue(empty.rows.isEmpty(), "2026–27 has not started in the samples")
        val table = provider.standings(samples.ssgtPrevious)
        assertEquals(expectedTeams, table.rows.size)
        val top = table.rows.first()
        assertEquals(1, top.rank)
        assertEquals(top.played, top.wins + top.losses + (top.otherLosses ?: 0), "W+OTW+L+OTL = GP")
        assertNotNull(top.extra["group"])
        assertTrue(top.team.id.contains('-'))
    }

    @Test
    fun teamRosterPlayer() = runTest {
        val t = provider.team(samples.teamId)
        assertEquals(samples.teamId, t.id)
        assertNotNull(t.ref.abbreviation)
        assertNotNull(t.ref.logoUrl)
        assertEquals(t.id, provider.team(t.ref.abbreviation).id, "lookup by display code works too")
        assertFailsWith<NotFoundException> { provider.team("nope") }

        val roster = provider.roster(samples.teamId)
        assertEquals(6, roster.size, "truncated sample: 2 per position group")
        assertEquals(listOf("GK", "GK", "D", "D", "F", "F"), roster.map { it.ref.position })
        assertTrue(roster.all { it.teamId == samples.teamId })

        val p = provider.player(samples.playerId)
        assertEquals(playerName, p.name)
        assertNotNull(p.birthDate)
        assertNotNull(p.heightCm)
        assertEquals(samples.teamId, p.teamId)
    }

    @Test
    fun notFoundAndCapabilities() = runTest {
        assertFailsWith<NotFoundException> { provider.game("does-not-exist") }
        assertTrue(provider.supports(Capability.LINE_GROUPS))
        assertTrue(provider.supports(Capability.INTERMISSION_STATE))
        assertTrue(!provider.supports(Capability.LIVE_PUSH), "SSE payloads not captured yet")
        assertEquals(Strength.PP, SportalityMapper(leagueId).strength("PP1", false))
        assertEquals(Strength.SH, SportalityMapper(leagueId).strength("SH2", false))
        assertEquals(Strength.PS, SportalityMapper(leagueId).strength("EQ", true))
    }
}

class ShlProviderTest : SportalityProviderTestBase(
    SportalitySamples.SHL, "shl", expectedTeams = 14, finalHomeName = "Frölunda HC", finalScore = Score(2, 1), playerName = "Jani Lampinen",
) {
    override val provider = ShlProvider(fetcher, clock = clock)
}
