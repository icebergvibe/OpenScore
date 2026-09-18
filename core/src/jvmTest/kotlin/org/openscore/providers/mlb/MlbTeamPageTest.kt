package org.openscore.providers.mlb

import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.openscore.feed.FeedMapper
import org.openscore.model.GameState
import org.openscore.provider.Capability
import org.openscore.testing.MlbSamples
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MlbTeamPageTest {
    private val fetcher = MlbSamples.register(SampleFetcher())
    private val provider = MlbProvider(fetcher)

    @Test
    fun teamSeasonIncludesResultsAndFixturesWithOfficialDates() = runTest {
        val games = provider.teamSchedule("147", LocalDate(2026, 1, 1), LocalDate(2026, 12, 31))
        assertEquals(6, games.size) // Captured season response trimmed to September 10–15.
        assertTrue(games.all { it.home.id == "147" || it.away.id == "147" })
        assertEquals(4, games.count { it.state == GameState.FINAL })
        assertEquals(2, games.count { it.state == GameState.SCHEDULED })
        assertEquals(games.sortedBy { it.startTime }, games)
        assertEquals(LocalDate(2026, 9, 10), games.first().scheduleDate)
        assertEquals("2026-09-10", FeedMapper.game(games.first()).scheduleDate)
        assertEquals("New York Yankees", games.first().home.name)
        assertTrue(provider.supports(Capability.TEAM_SCHEDULE))
    }

    @Test
    fun reversedDatesFailBeforeMakingARequest() = runTest {
        assertFailsWith<IllegalArgumentException> { provider.teamSchedule("147", LocalDate(2026, 9, 15), LocalDate(2026, 9, 10)) }
        assertTrue(fetcher.requests.isEmpty())
    }

    @Test
    fun statsKeepRatesAndInningsAsStrings() = runTest {
        val stats = provider.teamStats("147", "2026")
        assertEquals("147", stats.teamId)
        assertEquals("2026", stats.seasonId)
        assertEquals(listOf("Batting", "Pitching"), stats.groups.map { it.label })
        val batting = stats.groups.first().stats.associate { it.key to it.value }
        val pitching = stats.groups.last().stats.associate { it.key to it.value }
        assertEquals(".235", batting["avg"])
        assertEquals("204", batting["homeRuns"])
        assertEquals("3.24", pitching["era"])
        assertEquals("1332.0", pitching["inningsPitched"])
        assertEquals(".235", FeedMapper.teamStats(stats).groups.first().stats.first().value)
        assertTrue(provider.supports(Capability.TEAM_STATS))
    }

    @Test
    fun absentStatsAreNotFabricatedOrTakenFromAnotherTeamOrSeason() {
        assertTrue(MlbMapper.teamStats(MlbTeamStats(), "147", "2026").groups.isEmpty())
        val response = MlbTeamStats(listOf(MlbStatGroup(MlbStatName("hitting"), listOf(
            MlbStatSplit("2025", MlbTeam(147), mapOf("avg" to JsonPrimitive(".999"))),
            MlbStatSplit("2026", MlbTeam(121), mapOf("avg" to JsonPrimitive(".888"))),
            MlbStatSplit("2026", MlbTeam(147), mapOf("avg" to JsonNull, "homeRuns" to JsonPrimitive(0))),
        ))))
        val stats = MlbMapper.teamStats(response, "147", "2026").groups.single().stats
        assertEquals(listOf("homeRuns"), stats.map { it.key })
        assertEquals("0", stats.single().value)
    }
}
