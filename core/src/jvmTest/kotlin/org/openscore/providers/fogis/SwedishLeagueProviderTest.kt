package org.openscore.providers.fogis

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.openscore.OpenScore
import org.openscore.model.GameState
import org.openscore.provider.Capability
import org.openscore.provider.UnsupportedCapabilityException
import org.openscore.testing.AllsvenskanSamples
import org.openscore.testing.FogisSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SwedishLeagueProviderTest {

    private val fixedClock = object : Clock {
        override fun now(): Instant = Instant.parse("2026-09-12T08:00:00Z")
    }
    private val fetcher = AllsvenskanSamples.register(FogisSamples.register(SampleFetcher()))
    private val leagues = SwedishLeagueProvider.default(fetcher, FogisProvider(fetcher, clock = fixedClock))
    private val allsvenskan = leagues.first { it.league.id == "allsvenskan" }
    private val cup = leagues.first { it.league.id == "svenska-cupen" }

    @Test
    fun threeLeaguesWithHonestCapabilities() {
        assertEquals(listOf("allsvenskan", "superettan", "svenska-cupen"), leagues.map { it.league.id })
        assertTrue(allsvenskan.supports(Capability.STANDINGS))
        assertTrue(allsvenskan.supports(Capability.LINEUPS))
        assertTrue(!cup.supports(Capability.STANDINGS), "Fogis has no tables and the cup has no Sportomedia side")
        assertTrue(cup.supports(Capability.LIVE_UPDATES))
    }

    @Test
    fun gamesComeFromFogisUnderTheLeagueId() = runTest {
        val games = allsvenskan.gamesOn(LocalDate.parse(FogisSamples.DAY))
        assertTrue(games.isNotEmpty())
        assertTrue(games.all { it.leagueId == "allsvenskan" && it.competition == "Allsvenskan 2026" })
        val aik = games.first { it.home.name == "AIK" }
        assertEquals("allsvenskan", aik.home.leagueId)
        assertEquals("66036", aik.home.id, "Fogis team id, not the Sportomedia abbreviation")
        assertEquals("aik", aik.home.clubId, "resolved through the crosswalk's fogis namespace")
        assertNotNull(aik.home.logoUrl)
        assertEquals(FogisSamples.PRE_ID, aik.id)
    }

    @Test
    fun matchDetailIsFogisAndTableIsSportomedia() = runTest {
        val game = allsvenskan.game(FogisSamples.FINAL_ID)
        assertEquals("allsvenskan", game.leagueId)
        assertEquals(GameState.FINAL, game.state)
        assertNotNull(game.events)
        assertTrue(game.events.all { it.team == null || it.team.leagueId == "allsvenskan" })
        assertTrue(allsvenskan.lineups(FogisSamples.FINAL_ID).all { it.team.leagueId == "allsvenskan" })

        val table = allsvenskan.standings()
        assertEquals("allsvenskan", table.leagueId)
        val hammarby = table.rows.first { it.team.name.startsWith("Hammarby") }
        assertEquals("hammarby", hammarby.team.clubId, "the same club id a Fogis game's TeamRef gets")
        assertEquals("66592", hammarby.team.id, "table rows carry the Fogis id the games use")
        assertEquals("HAM", hammarby.team.abbreviation)
        assertNotNull(hammarby.team.logoUrl, "the total table has no crests; they come from the season's team list")
        assertEquals("allsvenskan", hammarby.team.leagueId)

        // A team page opened from a game (Fogis id) resolves through the same bridge.
        val aik = allsvenskan.team("66036")
        assertEquals("66036", aik.ref.id)
        assertEquals("AIK", aik.ref.abbreviation)
        assertEquals("aik", aik.ref.clubId)
        assertEquals("Strawberry Arena", aik.arena)
        assertTrue(allsvenskan.roster("66036").isNotEmpty())

        assertFailsWith<UnsupportedCapabilityException> { cup.standings() }
    }

    @Test
    fun defaultAggregatorServesThemAndKeepsTheUmbrellaOptIn() {
        val ids = OpenScore.default(fetcher).leagues.map { it.id }
        assertTrue(ids.containsAll(listOf("allsvenskan", "superettan", "svenska-cupen", "fogis")))
        assertEquals(setOf("allsvenskan", "superettan", "svenska-cupen"), OpenScore.DEFAULT_UMBRELLAS.getValue("fogis"))
    }

    @Test
    fun secondHalfClockComesFromTheGameResource() = runTest {
        // overview.live.second-half.xml (15:07 local): Hammarby–Brommapojkarna in the second half, with only the
        // first half's HALFSTARTED; game-info.live.second-half.xml has both.
        val f = AllsvenskanSamples.register(FogisSamples.register(SampleFetcher()))
        val dir = SampleFetcher.samplesDir("football", "fogis-livescore")
        val base = FogisProvider.DEFAULT_BASE_URL
        f.route(base + "overview-1-20260913.xml", dir.resolve("overview.live.second-half.xml"), contentType = "application/xml; charset=utf-8")
        f.route(base + "game-info-${FogisSamples.LIVE_ID}.xml", dir.resolve("game-info.live.second-half.xml"), contentType = "application/xml; charset=utf-8")
        val at = object : Clock {
            override fun now(): Instant = Instant.parse("2026-09-13T13:07:46Z")
        }
        val games = SwedishLeagueProvider.default(f, FogisProvider(f, clock = at)).first { it.league.id == "allsvenskan" }
            .gamesOn(LocalDate.parse(FogisSamples.LIVE_DAY))
        val hammarby = games.first { it.id == FogisSamples.LIVE_ID }
        assertEquals(2, hammarby.clock?.period?.number)
        // Second half kicked off 15:04:35 local; 3:11 in → the 49th minute.
        assertEquals("49'", hammarby.clock?.time?.label)
        assertEquals(listOf(2 to 1), hammarby.periodScores.map { it.home to it.away }, "the finished half; the running one is not listed")
        assertNotNull(hammarby.events, "the game resource brings the timeline with it")
        // The other second-half game has no resource routed here: the overview's game is kept rather than the day failing.
        assertTrue(games.any { it.id == "6529995" && it.state.isLive && it.clock?.time?.elapsed == null })
    }
}
