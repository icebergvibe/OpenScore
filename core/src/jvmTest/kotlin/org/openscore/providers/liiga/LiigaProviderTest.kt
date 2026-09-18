package org.openscore.providers.liiga

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.ShootoutAttemptDetails
import org.openscore.model.hockey.Strength
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.testing.LiigaSamples
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

class LiigaProviderTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-11T10:00:00Z")
    }
    private val fetcher = LiigaSamples.register(SampleFetcher())
    private val liiga = LiigaProvider(fetcher, clock = fixedClock)

    @Test
    fun everySampleParses() {
        val dir = SampleFetcher.samplesDir("hockey", "liiga")
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "games-by-date." to LiigaGamesByDate.serializer(),
            "game." to LiigaGameDetail.serializer(),
            "games-season" to ListSerializer(LiigaGame.serializer()),
            "games-by-week" to ListSerializer(LiigaGame.serializer()),
            "games-playoffs" to ListSerializer(LiigaGame.serializer()),
            "shotmap." to ListSerializer(LiigaShot.serializer()),
            "standings" to LiigaStandings.serializer(),
            "teams-info" to LiigaTeamsInfo.serializer(),
            "players-info" to ListSerializer(LiigaLineupPlayer.serializer()),
            "player-info" to LiigaPlayerInfo.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            var text = file.readText()
            if (text.contains("\"_truncated_array\"")) {
                text = OpenScoreJson.parseToJsonElement(text).let { (it as kotlinx.serialization.json.JsonObject)["_truncated_array"].toString() }
            }
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) && !file.name.startsWith("game-preview") && !file.name.startsWith("game.players") && !file.name.startsWith("game.past") }?.second
            try {
                if (strategy != null) { OpenScoreJson.decodeFromString(strategy, text); typed++ }
                else OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 14, "typed=$typed")
    }

    @Test
    fun gamesOnADate() = runTest {
        val games = liiga.gamesOn(LocalDate(2026, 9, 1))
        assertEquals(7, games.size)
        val g = games.first()
        assertEquals("2701274", g.id)
        assertEquals("2027", g.seasonId)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals("Jukurit", g.home.name)
        assertEquals("292293444", g.home.id, "numeric half of the Liiga team id")
        assertEquals(Score(1, 4), g.score)
        assertEquals(listOf(0 to 1, 1 to 1, 0 to 2), g.periodScores.map { it.home to it.away })
        assertEquals("Mikkelin jäähalli", g.venue)
        assertNull(g.events)
        val so = games.first { it.id == LiigaSamples.SHOOTOUT_GAME_ID }
        assertEquals(GameEnding.SHOOTOUT, so.ending)
        assertEquals(5, so.periodScores.size, "placeholder periods beyond currentPeriod are dropped, played ones kept")
        assertTrue(liiga.gamesOn(LocalDate(2026, 9, 11)).isEmpty())
    }

    @Test
    fun shootoutGameDetail() = runTest {
        val g = liiga.game(LiigaSamples.SHOOTOUT_GAME_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(Score(5, 4), g.score)
        assertEquals(listOf("1", "2", "3", "OT", "SO"), g.periodScores.map { it.period.label })
        assertEquals(PeriodType.SHOOTOUT, g.periodScores.last().period.type)
        assertEquals(1 to 0, g.periodScores.last().let { it.home to it.away })
        val events = assertNotNull(g.events)
        val goals = events.filter { it.type == HockeyEventType.GOAL }
        assertEquals(8, goals.size, "4+4 regulation/OT goals; the deciding SO goal is not a GOAL")
        assertTrue(goals.all { it.players.isNotEmpty() && it.players.first().jerseyNumber != null }, "scorers resolved from the lineup")
        assertTrue(goals.all { it.score != null })
        val attempts = events.filter { it.type == HockeyEventType.SHOOTOUT_ATTEMPT }
        assertTrue(attempts.size >= 3)
        assertIs<ShootoutAttemptDetails>(attempts.first().details)
        assertEquals(PeriodType.SHOOTOUT, attempts.first().period.type)
        val penalties = events.filter { it.type == HockeyEventType.PENALTY }
        assertTrue(penalties.isNotEmpty())
        val pd = assertIs<PenaltyDetails>(penalties.first().details)
        assertEquals("Korkea maila", pd.infraction)
        assertEquals(2, pd.minutes)
        assertEquals(1.minutes + 33.seconds, penalties.first().time.elapsed)
        assertEquals(18.minutes + 27.seconds, penalties.first().time.remaining)
    }

    @Test
    fun eventsIncludeShots() = runTest {
        val events = liiga.events(LiigaSamples.FINAL_GAME_ID)
        val shots = events.filter { it.type in setOf(HockeyEventType.SHOT, HockeyEventType.MISSED_SHOT, HockeyEventType.BLOCKED_SHOT) }
        assertEquals(87, shots.size)
        assertTrue(shots.all { it.coordinates != null })
        assertTrue(events.any { it.type == HockeyEventType.GOAL })
        assertEquals(events.sortedWith(compareBy({ it.period.number }, { it.time.elapsed })), events, "chronological")
    }

    @Test
    fun goalStrengthFromFinnishCodes() {
        assertEquals(Strength.PP, LiigaMapper.strength(listOf("YV")))
        assertEquals(Strength.PP, LiigaMapper.strength(listOf("YV2", "VT")))
        assertEquals(Strength.SH, LiigaMapper.strength(listOf("AV")))
        assertEquals(Strength.PS, LiigaMapper.strength(listOf("RL")))
        assertEquals(Strength.EV, LiigaMapper.strength(listOf("TM")))
        assertEquals(Strength.EV, LiigaMapper.strength(emptyList()))
    }

    @Test
    fun preGame() = runTest {
        val g = liiga.game(LiigaSamples.PRE_GAME_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertNull(g.clock)
        assertTrue(g.periodScores.isEmpty())
        assertEquals(emptyList(), g.events)
        val lineups = liiga.lineups(LiigaSamples.PRE_GAME_ID)
        assertEquals(2, lineups.size)
        assertEquals(28, lineups.first().players.size, "projected lineup is published before the game")
    }

    @Test
    fun lineupsHaveLinesAndPairings() = runTest {
        val home = liiga.lineups(LiigaSamples.SHOOTOUT_GAME_ID).first()
        assertEquals("Sport", home.team.name)
        assertEquals(LineupGroupKind.GOALIES, home.groups.first().kind)
        assertEquals(2, home.groups.first().players.size)
        val line1 = home.groups.first { it.kind == LineupGroupKind.LINE && it.label == "Line 1" }
        assertEquals(3, line1.players.size)
        val pair1 = home.groups.first { it.kind == LineupGroupKind.PAIRING && it.label == "Pairing 1" }
        assertEquals(2, pair1.players.size)
        assertTrue(home.groups.any { it.kind == LineupGroupKind.OTHER }, "players without a line")
    }

    @Test
    fun liveClockHeuristics() {
        val base = OpenScoreJson.decodeFromString(LiigaGameDetail.serializer(), SampleFetcher.samplesDir("hockey", "liiga").resolve("game.final.json").readText()).game!!
        val live = base.copy(ended = false, finishedType = "ACTIVE_OR_NOT_STARTED", currentPeriod = 2, gameTime = 1954)
        val g = LiigaMapper.game(live, withEvents = false)
        assertEquals(GameState.LIVE, g.state)
        val clock = assertNotNull(g.clock)
        assertEquals(2, clock.period.number)
        assertEquals(754.seconds, clock.time.elapsed)
        assertEquals(446.seconds, clock.time.remaining)
        assertNull(clock.running, "Liiga has no running flag")
        assertEquals(2, g.periodScores.size)

        val breakTime = live.copy(currentPeriod = 1, gameTime = 1200)
        assertEquals(GameState.INTERMISSION, LiigaMapper.game(breakTime, withEvents = false).state)
        val secondStart = live.copy(currentPeriod = 2, gameTime = 1200)
        assertEquals(GameState.LIVE, LiigaMapper.game(secondStart, withEvents = false).state)
    }

    @Test
    fun standings() = runTest {
        val table = liiga.standings()
        assertEquals("2027", table.seasonId, "current season derived from the clock")
        assertEquals(listOf("Runkosarja"), table.groups.map { it.label })
        assertEquals(17, table.rows.size, "2026–27 Liiga has 17 teams (K-Espoo joined)")
        val top = table.rows.first()
        assertEquals("Pelicans", top.team.name)
        assertEquals(1, top.rank)
        assertEquals(9, top.points)
        assertEquals("true", top.extra["directToPlayoffs"])

        val last = liiga.standings("2026")
        assertEquals(listOf("Runkosarja", "Playoffs"), last.groups.map { it.label })
        val tappara = last.groups.first().rows.first()
        assertEquals("Tappara", tappara.team.name)
        assertEquals(60, tappara.played)
        assertEquals(38, tappara.wins, "regulation + OT wins")
        assertEquals(16, tappara.losses)
        assertEquals(6, tappara.otherLosses)
        assertEquals(117, tappara.points)
        assertEquals("35", tappara.extra["regulationWins"])
    }

    @Test
    fun teamRosterPlayer() = runTest {
        val t = liiga.team("495643563:kärpät")
        assertEquals("Kärpät", t.name)
        assertEquals("495643563", t.id)
        assertNotNull(t.ref.logoUrl)
        assertFailsWith<NotFoundException> { liiga.team("1") }

        val roster = liiga.roster(LiigaSamples.ROSTER_TEAM_ID)
        assertTrue(roster.isNotEmpty())
        assertTrue(roster.all { it.teamId == LiigaSamples.ROSTER_TEAM_ID })
        assertEquals("Aleksi Elorinne", roster.first().name)
        assertEquals("L", roster.first().handedness)

        val p = liiga.player(LiigaSamples.PLAYER_ID)
        assertEquals("Thomas Olsen", p.name)
        assertEquals("NOR", p.nationality)
        assertEquals("Oslo, NOR", p.birthPlace)
        assertEquals(LocalDate(1995, 6, 25), p.birthDate)
        assertEquals("292293444", p.teamId)
        assertEquals(10, p.ref.jerseyNumber)
    }

    @Test
    fun idsAndErrors() = runTest {
        assertEquals("2027" to "2701274", LiigaProvider.parseGameId("2701274"))
        assertEquals("2019" to "55080", LiigaProvider.parseGameId("2019:55080"))
        assertFailsWith<IllegalArgumentException> { LiigaProvider.parseGameId("abc") }
        assertFailsWith<NotFoundException> { liiga.game("2701999") }
        assertTrue(liiga.supports(Capability.LINE_GROUPS))
        assertTrue(!liiga.supports(Capability.CLOCK_RUNNING_FLAG))
    }
}
