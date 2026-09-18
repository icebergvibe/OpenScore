package org.openscore.providers.espn

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import org.openscore.clubs.Clubs
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.providers.bundesliga.BundesligaProvider
import org.openscore.providers.uefa.ChampionsLeagueProvider
import org.openscore.providers.uefa.EuropaLeagueProvider
import org.openscore.testing.BundesligaSamples
import org.openscore.testing.EspnSamples
import org.openscore.testing.SampleFetcher
import org.openscore.testing.UefaSamples
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class EspnRostersTest {
    private val fetcher = EspnSamples.register(UefaSamples.register(BundesligaSamples.register(SampleFetcher())))
    private val rosters = EspnRosters(fetcher)
    /** Inside 2026/27, so the UEFA providers ask ESPN for season 2026. */
    private val clock = object : Clock { override fun now(): Instant = Instant.parse("2026-09-16T20:00:00Z") }

    @Test
    fun bayernSquadFromTheSample() = runTest {
        val squad = rosters.roster(EspnRosters.BUNDESLIGA_SLUG, EspnSamples.BAYERN, EspnSamples.SEASON)
        assertEquals(25, squad.size)
        assertEquals(mapOf<String?, Int>("GK" to 3, "DF" to 9, "MF" to 10, "FW" to 3), squad.groupBy { it.ref.position }.mapValues { it.value.size })
        val neuer = squad.first { it.ref.name == "Manuel Neuer" }
        assertEquals("espn", neuer.ref.leagueId, "ESPN's athlete ids live in ESPN's id space")
        assertEquals("84774", neuer.ref.id)
        assertEquals(1, neuer.ref.jerseyNumber)
        assertEquals(LocalDate(1986, 3, 27), neuer.birthDate)
        assertEquals(193, neuer.heightCm)
        assertEquals(92, neuer.weightKg)
        assertEquals(EspnSamples.BAYERN, neuer.teamId)
        assertTrue(squad.all { it.ref.name.isNotBlank() })
    }

    @Test
    fun providersAnswerThroughTheCrosswalk() = runTest {
        val bundesliga = BundesligaProvider(fetcher, rosters = rosters)
        assertTrue(bundesliga.supports(Capability.ROSTER))
        assertFalse(BundesligaProvider(fetcher).supports(Capability.ROSTER), "without an ESPN source the DFL feed has no squad")
        assertEquals("132", Clubs.club("bundesliga", "DFL-CLU-00000G")?.ids?.get("espn"))
        assertEquals(25, bundesliga.roster("DFL-CLU-00000G").size)
        assertFailsWith<NotFoundException> { bundesliga.roster("DFL-CLU-NOPE") }

        val europa = EuropaLeagueProvider(fetcher, clock = clock, rosters = rosters)
        assertEquals(31, europa.roster("50111").size, "Sturm Graz through the uefa.europa slug")
        val champions = ChampionsLeagueProvider(fetcher, clock = clock, rosters = rosters)
        assertEquals(25, champions.roster("50037").size, "Bayern's UEFA id resolves to the same ESPN club")
    }
}
