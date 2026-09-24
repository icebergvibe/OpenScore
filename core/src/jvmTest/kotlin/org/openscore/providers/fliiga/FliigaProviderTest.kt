package org.openscore.providers.fliiga

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.openscore.clubs.Clubs
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.LineupGroupKind
import org.openscore.model.PeriodType
import org.openscore.model.Sport
import org.openscore.model.StageKind
import org.openscore.model.floorball.FloorballEventType
import org.openscore.model.floorball.GoalDetails
import org.openscore.model.floorball.PenaltyDetails
import org.openscore.model.floorball.ShotDetails
import org.openscore.model.floorball.ShotOutcome
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.testing.FliigaSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class FliigaProviderTest {

    private val fetcher = FliigaSamples.register(SampleFetcher())

    /** Days after the last captured result, so the records answer without a single card read. */
    private fun fliiga(now: String = "2026-09-23T20:00:00Z") = FliigaProvider(fetcher, clock = fixed(now))

    private fun fixed(iso: String) = object : Clock {
        override fun now(): Instant = Instant.parse(iso)
    }

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "matches.page" to ListSerializer(FlWpMatch.serializer()),
            "match-detail" to FlMatch.serializer(),
            "match-summary" to FlSummary.serializer(),
            "match-by-slug" to ListSerializer(FlWpMatch.serializer()),
            "standings" to ListSerializer(FlStandingsRow.serializer()),
            "player-points" to ListSerializer(FlPlayerRow.serializer()),
            "goalkeepers" to ListSerializer(FlPlayerRow.serializer()),
            "teams" to ListSerializer(FlWpTeam.serializer()),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("floorball", "f-liiga").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            var text = file.readText()
            if (text.contains("\"_truncated_array\"")) text = (OpenScoreJson.parseToJsonElement(text) as JsonObject)["_truncated_array"].toString()
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) {
                    OpenScoreJson.decodeFromString(strategy, text)
                    typed++
                } else {
                    OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
                }
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message}"
            }
        }
        assertTrue(failures.isEmpty(), "samples that do not parse: $failures")
        assertEquals(13, typed, "only the WordPress player record has no DTO: the leaderboards carry more")
    }

    @Test
    fun leagueIsFloorball() {
        assertEquals(Sport.FLOORBALL, FliigaProvider.LEAGUE.sport)
        assertEquals("f-liiga", FliigaProvider.LEAGUE.id)
    }

    /** Only the men's records of the current season, and only the pages that hold them. */
    @Test
    fun theSeasonIsReadFromTheMatchRecords() = runTest {
        val season = fliiga().season()
        assertEquals(FliigaSamples.SEASON_ID, season.id)
        assertEquals(168, season.matches.size)
        assertTrue(season.matches.all { it.meta.series == "Miehet" })
        assertTrue(fetcher.requests.any { it.contains("page=3") }, "paging stops at the first page with no men's records")
    }

    @Test
    fun aSettledDayNeedsNoCardRead() = runTest {
        val before = fetcher.requests.size
        val games = fliiga().gamesOn(LocalDate.parse(FliigaSamples.FINAL_DAY))
        assertTrue(fetcher.requests.drop(before).none { it.contains("match_live_data") }, "a recorded result is read off the record")
        assertEquals(2, games.size)
        assertEquals(listOf(FliigaSamples.OTHER_FINAL_GAME_ID, FliigaSamples.FINAL_GAME_ID), games.map { it.id })
        assertTrue(games.all { it.state == GameState.FINAL })
        val ols = games.last()
        assertEquals(3, ols.score?.home)
        assertEquals(4, ols.score?.away)
        assertEquals("OLS", ols.home.name)
        assertEquals(FliigaSamples.TEAM_ID, ols.home.id, "teams are the stable TorneoPal club id")
        assertEquals("ols", ols.home.clubId)
        assertEquals("o2-jyvaskyla", ols.away.clubId)
        assertEquals(StageKind.REGULAR, ols.stage)
        assertNull(ols.ending, "the record does not say how a match was decided")
    }

    /** Inside the window after the throw-off, the compact card is the state and the score. */
    @Test
    fun aMatchJustStartedIsReadFromTheCard() = runTest {
        val before = fetcher.requests.size
        val games = fliiga(now = "2026-09-20T16:00:00Z").gamesOn(LocalDate.parse(FliigaSamples.FINAL_DAY))
        val cards = fetcher.requests.drop(before).filter { it.contains("match_live_data") }
        assertEquals(2, cards.size, "both matches are inside the result window at 19:00 Helsinki")
        assertEquals(2, games.size)
        // The captured cards were taken after the matches finished, so they say Played.
        assertTrue(games.all { it.state == GameState.FINAL })
        assertEquals(14, games.first().score?.home)
        assertEquals("Played", games.first().rawState)
        assertEquals("Kastellin monitoimitalon liikuntasali A kenttä 2", games.last().venue, "the card adds the venue the record has no room for")
    }

    @Test
    fun futureFixturesAreScheduledAndCostNothingExtra() = runTest {
        val before = fetcher.requests.size
        val games = fliiga().gamesOn(LocalDate.parse(FliigaSamples.SCHEDULED_DAY))
        assertTrue(fetcher.requests.drop(before).none { it.contains("match_live_data") })
        assertEquals(3, games.size)
        assertTrue(games.all { it.state == GameState.SCHEDULED })
        assertTrue(games.all { it.score == null }, "an unwritten record is zero goals, not a 0-0 draw")
        assertEquals(listOf("929730", "929741", "929731"), games.map { it.id }, "sorted by throw-off")
    }

    @Test
    fun aFinishedMatchCarriesPeriodsEventsAndHowItEnded() = runTest {
        val g = fliiga().game(FliigaSamples.FINAL_GAME_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(3, g.score?.home)
        assertEquals(4, g.score?.away)
        assertEquals(GameEnding.OVERTIME, g.ending)
        // `overtime` in the feed is the score entering overtime, so the extra period is derived.
        assertEquals(listOf("1", "2", "3", "OT"), g.periodScores.map { it.period.label })
        assertEquals(listOf(1 to 2, 1 to 0, 1 to 1, 0 to 1), g.periodScores.map { it.home to it.away })
        assertEquals(PeriodType.OVERTIME, g.periodScores.last().period.type)
        assertEquals("41", g.stats["shots"]?.home)
        assertEquals("Kastellin monitoimitalon liikuntasali A kenttä 2", g.venue)

        val events = g.events!!
        assertEquals(7, events.count { it.type == FloorballEventType.GOAL })
        assertEquals(1, events.count { it.type == FloorballEventType.PENALTY })
        assertEquals(0, events.count { it.rawType == "vaihto" }, "the rows that register the squad are not game events")
        assertEquals(0, events.count { it.rawType == "hallinta" || it.rawType == "plus" || it.rawType == "miinus" })

        val opener = events.first { it.type == FloorballEventType.GOAL }
        assertEquals("Kalle Lintala", opener.players.first().name)
        assertEquals(1, opener.score?.home)
        assertEquals("1", opener.period.label)
        assertEquals("2:05", opener.time.label, "the label is cumulative match time, as the scoreboard shows it")
        assertEquals(125, opener.time.elapsed?.inWholeSeconds)
        val goal = assertIs<GoalDetails>(opener.details)
        assertEquals("Riku Ruonakangas", goal.assists.single().name)
        assertEquals("Miika Aaltokallio", goal.goalie?.name, "the goalie comes from the conceding row behind the scoring shot")
        assertEquals("OLS", opener.team?.name)

        val winner = events.last { it.type == FloorballEventType.GOAL }
        assertEquals("OT", winner.period.label)
        assertEquals("O2-Jyväskylä", winner.team?.name)

        val penalty = assertIs<PenaltyDetails>(events.single { it.type == FloorballEventType.PENALTY }.details)
        assertEquals("Luukas Hyvärinen", penalty.player?.name)
        assertEquals(2, penalty.minutes)
        assertEquals("Mailarike", penalty.infraction)
        assertEquals("2min", penalty.severity)

        val shot = events.first { it.type == FloorballEventType.SHOT }
        val shotDetails = assertIs<ShotDetails>(shot.details)
        assertEquals(ShotOutcome.ON_GOAL, shotDetails.outcome)
        assertEquals("Miika Aaltokallio", shotDetails.goalie?.name)
        assertEquals(326.0, shot.coordinates?.x)
        assertEquals(-101.0, shot.coordinates?.y)
        assertTrue(events.count { it.coordinates != null } > 50)

        // The match's own boundary rows carry a pseudo-period; they are pinned to a real one.
        assertEquals("1", events.single { it.type == FloorballEventType.GAME_START }.period.label)
        assertEquals("OT", events.single { it.type == FloorballEventType.GAME_END }.period.label)
    }

    @Test
    fun aMatchBeforeItsThrowOffHasLineupsButNoResult() = runTest {
        val g = fliiga().game(FliigaSamples.SCHEDULED_GAME_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertNull(g.ending)
        assertTrue(g.periodScores.isEmpty())
        assertTrue(g.stats.isEmpty(), "an all-zero lineup is not a statistic")
        assertEquals(0, g.events!!.size, "the only rows are the twenty that register each squad")

        val lineups = fliiga().lineups(FliigaSamples.SCHEDULED_GAME_ID)
        assertEquals(2, lineups.size)
        assertTrue(lineups.all { it.players.size == 20 })
        val home = lineups.first()
        assertEquals("SPV", home.team.name)
        assertEquals("Isomäki Jori", home.headCoach)
        assertEquals("Goalkeepers", home.groups.first().label)
        assertEquals(LineupGroupKind.GOALIES, home.groups.first().kind)
        assertEquals(listOf("Line 1", "Line 2", "Line 3", "Line 4"), home.groups.drop(1).map { it.label })
        assertTrue(home.groups.drop(1).all { it.kind == LineupGroupKind.LINE })
        // A line is three forwards and two backs, in the order a line-up sheet lists them.
        assertEquals(listOf("VL", "KH", "OL", "VP", "OP"), home.groups[1].players.map { it.position })
    }

    @Test
    fun standingsCarryTheRegulationAndOvertimeSplit() = runTest {
        val table = fliiga().standings()
        assertEquals(FliigaSamples.SEASON_ID, table.seasonId)
        assertEquals(12, table.rows.size)
        assertEquals("Runkosarja", table.groups.single().label, "the feed shouts its group name")
        val top = table.rows.first()
        assertEquals("Nokian KrP", top.team.name)
        assertEquals("374", top.team.id)
        assertEquals("nokian-krp", top.team.clubId)
        assertEquals(9, top.points)
        assertEquals("3", top.extra["regulationWins"])
        // O2-Jyväskylä's overtime win over OLS is worth two points, and OLS keeps one.
        val o2 = table.rows.single { it.team.name == "O2-Jyväskylä" }
        assertEquals(2, o2.wins)
        assertEquals("1", o2.extra["overtimeWins"])
        assertEquals(5, o2.points)
        val ols = table.rows.single { it.team.name == "OLS" }
        assertEquals(1, ols.otherLosses)
        assertEquals(4, ols.points)
        assertTrue(table.rows.all { it.played == it.wins + it.losses + (it.otherLosses ?: 0) })
    }

    @Test
    fun teamRosterAndPlayer() = runTest {
        val fliiga = fliiga()
        val team = fliiga.team(FliigaSamples.TEAM_ID)
        assertEquals("OLS", team.name)
        assertEquals("ols", team.ref.clubId)
        assertEquals("FI", team.country)
        assertTrue(team.ref.logoUrl != null)

        val roster = fliiga.roster(FliigaSamples.TEAM_ID)
        assertEquals(17, roster.size, "fourteen outfield players kept in the truncated board, plus three goalkeepers")
        assertEquals(3, roster.count { it.ref.position == "MV" })
        assertTrue(roster.all { it.teamId == FliigaSamples.TEAM_ID })

        val player = fliiga.player(FliigaSamples.PLAYER_ID)
        assertEquals("Valtteri Viitakoski", player.name)
        assertEquals(39, player.ref.jerseyNumber)
        assertEquals("1996-04-30", player.birthDate.toString())
        assertEquals("374", player.teamId)
        assertFailsWith<NotFoundException> { fliiga.player("1") }
    }

    @Test
    fun teamScheduleFiltersTheSeasonByTeamAndHelsinkiDate() = runTest {
        val games = fliiga().teamSchedule(FliigaSamples.TEAM_ID, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-11-30"))
        assertEquals(12, games.size)
        assertTrue(games.all { it.home.id == FliigaSamples.TEAM_ID || it.away.id == FliigaSamples.TEAM_ID })
        assertTrue(games.zipWithNext().all { (a, b) -> a.startTime <= b.startTime })
        assertFailsWith<NotFoundException> { fliiga().teamSchedule("nope", LocalDate.parse("2026-09-01"), LocalDate.parse("2026-11-30")) }
    }

    @Test
    fun liveIsNotClaimedUntilAMatchHasBeenCaptured() = runTest {
        val fliiga = fliiga()
        assertTrue(Capability.LIVE_UPDATES !in fliiga.capabilities)
        assertTrue(Capability.CLOCK !in fliiga.capabilities)
        assertTrue(Capability.INTERMISSION_STATE !in fliiga.capabilities)
        assertFailsWith<UnsupportedCapabilityException> { fliiga.live(FliigaSamples.FINAL_GAME_ID) }
        assertFailsWith<UnsupportedCapabilityException> { fliiga.teamStats(FliigaSamples.TEAM_ID, FliigaSamples.SEASON_ID) }
    }

    @Test
    fun everyFliigaClubIsInTheCrosswalk() {
        val table = OpenScoreJson.decodeFromString(
            ListSerializer(FlStandingsRow.serializer()),
            SampleFetcher.samplesDir("floorball", "f-liiga").resolve("standings.json").readText(),
        )
        assertEquals(12, table.size)
        val missing = table.filter { Clubs.clubId("f-liiga", it.club!!.id) == null }
        assertTrue(missing.isEmpty(), "clubs missing from the crosswalk: ${missing.map { it.clubName }}")
    }
}
