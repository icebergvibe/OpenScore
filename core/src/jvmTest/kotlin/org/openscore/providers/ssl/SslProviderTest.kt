package org.openscore.providers.ssl

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import org.openscore.clubs.Clubs
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.Sport
import org.openscore.model.StageKind
import org.openscore.net.OpenScoreJson
import org.openscore.provider.Capability
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.providers.sportality.SptAthleteGroup
import org.openscore.providers.sportality.SptFilter
import org.openscore.providers.sportality.SptGameInfoResponse
import org.openscore.providers.sportality.SptProfilePage
import org.openscore.providers.sportality.SptSchedule
import org.openscore.providers.sportality.SptTeam
import org.openscore.testing.SampleFetcher
import org.openscore.testing.SslSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SslProviderTest {

    private val fetcher = SslSamples.register(SampleFetcher())

    /** Long after everything in the samples, so no game is treated as due. */
    private fun ssl(now: String = "2026-10-01T12:00:00Z") = SslProvider(fetcher, clock = fixed(now))

    private fun fixed(iso: String) = object : Clock {
        override fun now(): Instant = Instant.parse(iso)
    }

    @Test
    fun everySampleParses() {
        val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
            "season-series-game-types-filter" to SptFilter.serializer(),
            "game-schedule" to SptSchedule.serializer(),
            "game-info." to SptGameInfoResponse.serializer(),
            "league-standings" to SslStandings.serializer(),
            "all-teams" to ListSerializer(SptTeam.serializer()),
            "athletes-by-team" to ListSerializer(SptAthleteGroup.serializer()),
            "athlete-profile-page" to SptProfilePage.serializer(),
            "promo-bar-stats" to SslPromoStats.serializer(),
        )
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in SampleFetcher.samplesDir("floorball", "ssl").listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }) {
            val strategy = strategies.firstOrNull { (p, _) -> file.name.startsWith(p) }?.second
            try {
                if (strategy != null) {
                    OpenScoreJson.decodeFromString(strategy, file.readText())
                    typed++
                } else {
                    OpenScoreJson.decodeFromString(JsonElement.serializer(), file.readText())
                }
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message}"
            }
        }
        assertTrue(failures.isEmpty(), "samples that do not parse: $failures")
        assertEquals(9, typed, "every DTO the provider uses is exercised by a sample")
    }

    @Test
    fun leagueIsFloorball() {
        assertEquals(Sport.FLOORBALL, SslProvider.LEAGUE.sport)
        assertEquals("ssl", SslProvider.LEAGUE.id)
    }

    @Test
    fun dayInTheScoreboardWindowKeepsOnlyTheMensSeries() = runTest {
        val games = ssl().gamesOn(LocalDate.parse(SslSamples.FINAL_DAY))
        assertEquals(1, games.size, "the scoreboard mixes SSL Dam into the same day")
        val g = games.single()
        assertEquals(SslSamples.FINAL_GAME_ID, g.id)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(10, g.score?.home)
        assertEquals(6, g.score?.away)
        assertEquals(GameEnding.REGULATION, g.ending)
        assertEquals(StageKind.REGULAR, g.stage)
        assertEquals("Nyhemshallen - Mullsjö", g.venue)
        assertEquals("Mullsjö AIS", g.home.name)
        assertEquals(SslSamples.TEAM_ID, g.home.id, "the scoreboard's display code is resolved to the platform UUID")
        assertEquals("mullsjo-ais", g.home.clubId)
    }

    @Test
    fun unplayedScoreboardRowsCarryNoScore() = runTest {
        val games = ssl(now = "2026-09-21T12:00:00Z").gamesOn(LocalDate.parse(SslSamples.SCHEDULED_DAY))
        assertEquals(2, games.size)
        assertTrue(games.all { it.state == GameState.SCHEDULED })
        // The scoreboard writes `0` rather than leaving the result out; it must not become 0-0.
        assertTrue(games.all { it.score == null })
        assertTrue(games.all { it.ending == null })
    }

    /** A game whose start has passed while the scoreboard still says unplayed is read from `game-info`. */
    @Test
    fun aDueGameIsResolvedFromTheDetailRoute() = runTest {
        val before = fetcher.requests.size
        val games = ssl(now = "2026-09-22T17:15:00Z").gamesOn(LocalDate.parse(SslSamples.SCHEDULED_DAY))
        val asked = fetcher.requests.drop(before).filter { it.contains("/game-info/") }
        assertEquals(2, asked.size, "both games have started by 17:15Z")
        // The captured detail still says `pre_game`, which is what a feed that has not opened says.
        assertTrue(games.all { it.state == GameState.SCHEDULED })
        assertTrue(games.all { it.score == null })
    }

    @Test
    fun aDayOutsideTheWindowComesFromTheSeasonSchedule() = runTest {
        val before = fetcher.requests.size
        val games = ssl().gamesOn(LocalDate.parse(SslSamples.SCHEDULE_ONLY_DAY))
        assertTrue(fetcher.requests.drop(before).any { it.contains("/game-schedule?") })
        assertEquals(5, games.size)
        assertTrue(games.all { it.state == GameState.FINAL })
        assertEquals(listOf("IBF Falun", "IBK Lund", "IBK Dalen", "AIK IBF", "Linköping IBK"), games.map { it.home.name })
        assertTrue(games.all { it.home.clubId != null && it.away.clubId != null })
    }

    @Test
    fun aFinishedGameCarriesItsPostGameTotals() = runTest {
        val g = ssl().game(SslSamples.FINAL_GAME_ID)
        assertEquals(GameState.FINAL, g.state)
        assertEquals(10, g.score?.home)
        assertEquals(6, g.score?.away)
        assertEquals("27", g.stats["shotsOnGoal"]?.home)
        assertEquals("22", g.stats["shotsOnGoal"]?.away)
        assertEquals("16", g.stats["saves"]?.home)
        assertEquals("4", g.stats["penaltyMinutes"]?.away)
        assertTrue("goals" !in g.stats, "the promo bar's goals only repeat the score")
        assertNull(g.events, "SSL has no event source at all, which is not the same as none yet")
        assertTrue(g.periodScores.isEmpty())
        assertNull(g.clock)
    }

    @Test
    fun aScheduledGameHasNoScoreAndNoTotals() = runTest {
        val before = fetcher.requests.size
        val g = ssl().game(SslSamples.SCHEDULED_GAME_ID)
        assertEquals(GameState.SCHEDULED, g.state)
        assertNull(g.score)
        assertTrue(g.stats.isEmpty())
        assertTrue(fetcher.requests.drop(before).none { it.contains("promo-bar-stats") }, "totals are only asked for once a game is over")
    }

    @Test
    fun standingsSplitRegulationAndOvertimeResults() = runTest {
        val table = ssl().standings()
        assertEquals(SslSamples.SEASON_ID, table.seasonId)
        assertEquals(14, table.rows.size)
        assertEquals(StageKind.REGULAR, table.stage)
        val falun = table.rows.first()
        assertEquals(1, falun.rank)
        assertEquals("IBF Falun", falun.team.name)
        assertEquals("ibf-falun", falun.team.clubId)
        assertEquals(1, falun.wins)
        assertEquals(3, falun.points, "a regulation win is three points")
        assertEquals("Playoff", falun.extra["group"])
        // The one game decided after regulation: RegT=1 on both sides, and OTW says who took it.
        val kalmarsund = table.rows.single { it.team.name == "FBC Kalmarsund" }
        assertEquals(1, kalmarsund.wins)
        assertEquals(0, kalmarsund.otherLosses)
        assertEquals("0", kalmarsund.extra["regulationWins"])
        assertEquals("1", kalmarsund.extra["overtimeWins"])
        assertEquals("1", kalmarsund.extra["tiedAfterRegulation"])
        assertEquals(2, kalmarsund.points)
        val linkoping = table.rows.single { it.team.name == "Linköping IBK" }
        assertEquals(0, linkoping.wins)
        assertEquals(0, linkoping.losses, "a loss after regulation is not a regulation loss")
        assertEquals(1, linkoping.otherLosses)
        assertEquals(1, linkoping.points)
        assertTrue(table.rows.all { it.played == it.wins + it.losses + (it.otherLosses ?: 0) })
        assertEquals("Relegation", table.rows.last().extra["group"])
    }

    @Test
    fun teamRosterAndPlayer() = runTest {
        val ssl = ssl()
        val team = ssl.team(SslSamples.TEAM_ID)
        assertEquals("Mullsjö AIS", team.name)
        assertEquals("MAIS", team.ref.abbreviation)
        assertEquals("mullsjo-ais", team.ref.clubId)
        assertTrue(team.ref.logoUrl!!.endsWith("mul1_mul.svg"))

        val roster = ssl.roster(SslSamples.TEAM_ID)
        assertEquals(8, roster.size, "the sample keeps two athletes per positional group")
        assertEquals(listOf("GK", "GK", "D", "D", "F", "F", "C", "C"), roster.map { it.ref.position })
        assertEquals(SslSamples.TEAM_ID, roster.first().teamId)

        val player = ssl.player(SslSamples.PLAYER_ID)
        assertEquals("Samuel Jönsson Dolke", player.name)
        assertEquals(72, player.ref.jerseyNumber)
        assertEquals("SE", player.nationality)
    }

    @Test
    fun teamScheduleFiltersTheSeasonByTeamAndSwedishDate() = runTest {
        val ssl = ssl()
        val all = ssl.teamSchedule(SslSamples.TEAM_ID, LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-30"))
        assertEquals(1, all.size, "the schedule sample keeps eight of the season's games")
        assertEquals(SslSamples.FINAL_GAME_ID, all.single().id)
        assertTrue(ssl.teamSchedule(SslSamples.TEAM_ID, LocalDate.parse("2026-09-21"), LocalDate.parse("2026-09-30")).isEmpty())
    }

    @Test
    fun theCapabilitiesAreTheOnesTheFeedCanBack() = runTest {
        val ssl = ssl()
        assertEquals(
            setOf(
                Capability.GAMES_BY_DATE, Capability.GAME, Capability.STANDINGS,
                Capability.TEAM, Capability.TEAM_SCHEDULE, Capability.ROSTER, Capability.PLAYER,
            ),
            ssl.capabilities,
        )
        assertFailsWith<UnsupportedCapabilityException> { ssl.events(SslSamples.FINAL_GAME_ID) }
        assertFailsWith<UnsupportedCapabilityException> { ssl.lineups(SslSamples.FINAL_GAME_ID) }
        assertFailsWith<UnsupportedCapabilityException> { ssl.live(SslSamples.FINAL_GAME_ID) }
    }

    @Test
    fun everySslClubIsInTheCrosswalk() {
        val teams = OpenScoreJson.decodeFromString(
            ListSerializer(SptTeam.serializer()),
            SampleFetcher.samplesDir("floorball", "ssl").resolve("all-teams.json").readText(),
        )
        assertEquals(14, teams.size)
        val missing = teams.filter { Clubs.clubId("ssl", it.uuid) == null }
        assertTrue(missing.isEmpty(), "clubs missing from the crosswalk: ${missing.map { it.teamNames.long }}")
    }
}
