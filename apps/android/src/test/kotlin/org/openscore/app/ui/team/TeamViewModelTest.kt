package org.openscore.app.ui.team

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.datetime.LocalDate
import org.openscore.OpenScore
import org.openscore.app.Fixtures
import org.openscore.app.data.ScoresRepository
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Score
import org.openscore.model.Sport
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.TeamRef
import org.openscore.model.Player
import org.openscore.model.PlayerRef
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamSeasonStats
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.providers.mlb.MlbProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class TeamViewModelTest {
    @Test
    fun aFailedRosterDoesNotBlockTheProfileOrScheduleAndCanBeRetriedIndependently() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val ref = Fixtures.team("mlb", "147")
            val scheduleReady = CompletableDeferred<Unit>()
            var failRoster = true
            var profileRequests = 0
            val provider = object : BaseLeagueProvider() {
                override val league = MlbProvider.LEAGUE
                override val capabilities = setOf(Capability.TEAM, Capability.TEAM_SCHEDULE, Capability.TEAM_STATS, Capability.STANDINGS, Capability.ROSTER)
                override suspend fun team(id: String): Team { profileRequests++; return Team(ref) }
                override suspend fun roster(teamId: String): List<Player> {
                    if (failRoster) error("offline")
                    return listOf(Player(PlayerRef("mlb", "592450", "Aaron Judge"), teamId = teamId))
                }
                override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
                    assertEquals(LocalDate(2026, 1, 1), startDate)
                    assertEquals(LocalDate(2026, 12, 31), endDate)
                    scheduleReady.await()
                    return emptyList()
                }
                override suspend fun standings(seasonId: String?) = StandingsTable("mlb", seasonId, null, emptyList(), "division")
                override suspend fun teamStats(teamId: String, seasonId: String) = TeamSeasonStats("mlb", teamId, seasonId, emptyList())
            }
            val vm = TeamViewModel(ScoresRepository(OpenScore(listOf(provider))), ref, LocalDate(2026, 9, 14))
            store.put("team", vm)
            val partial = vm.state.first { it.profile.data != null && it.roster.error != null }
            assertEquals(ref, partial.profile.data?.ref)
            assertTrue(partial.games.loading)
            assertFalse(partial.roster.loading)
            failRoster = false
            vm.retry(TeamPart.ROSTER)
            val retried = vm.state.first { it.roster.data != null }
            assertNull(retried.roster.error)
            assertEquals("Aaron Judge", retried.roster.data?.single()?.name)
            assertEquals(1, profileRequests)
            scheduleReady.complete(Unit)
            val done = vm.state.first { !it.games.loading }
            assertEquals(emptyList(), done.games.data)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    /** Arsenal opened from a Champions League tie: the page is the club's, home league Premier League, games from both. */
    @Test
    fun aClubPageSpansEveryCompetitionTheTablesPlaceItIn() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val arsenalPl = TeamRef("premier-league", "3", "Arsenal", "ARS")
            val arsenalUefa = TeamRef("ucl", "52280", "Arsenal", "ARS")
            val scheduleRequests = ArrayList<String>()
            val profileRequests = ArrayList<String>()
            fun table(leagueId: String, vararg teams: TeamRef) = StandingsTable(leagueId, null, null,
                listOf(StandingsGroup("League", teams.mapIndexed { i, t -> StandingsRow(t, i + 1, 4, 3, 1, 0, points = 9) })), "league")
            fun provider(league: League, own: TeamRef?, others: List<TeamRef>, schedule: List<Game>) = object : BaseLeagueProvider() {
                override val league = league
                override val capabilities = setOf(Capability.TEAM, Capability.TEAM_SCHEDULE, Capability.STANDINGS, Capability.ROSTER)
                override suspend fun standings(seasonId: String?) = table(league.id, *listOfNotNull(own).toTypedArray(), *others.toTypedArray())
                override suspend fun team(id: String): Team { profileRequests += "${league.id}/$id"; return Team(own ?: error("not here")) }
                override suspend fun roster(teamId: String) = listOf(Player(PlayerRef(league.id, "p1", "Player One"), teamId = teamId))
                override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
                    scheduleRequests += "${league.id}/$teamId ${startDate}..${endDate}"
                    return schedule
                }
            }
            val napoli = TeamRef("ucl", "50136", "Napoli")
            val chelsea = TeamRef("premier-league", "8", "Chelsea")
            val plGame = Fixtures.game(leagueId = "premier-league", id = "2645215", home = arsenalPl, away = chelsea, state = GameState.FINAL, score = Score(2, 1))
            val uclGame = Fixtures.game(leagueId = "ucl", id = "2049560", home = napoli, away = arsenalUefa, state = GameState.FINAL, score = Score(1, 2), startTime = Instant.parse("2026-09-09T19:00:00Z"))
            val repository = ScoresRepository(OpenScore(listOf(
                provider(League("premier-league", Sport.FOOTBALL, "Premier League", "GB"), arsenalPl, listOf(chelsea), listOf(plGame)),
                provider(League("ucl", Sport.FOOTBALL, "Champions League", "EU"), arsenalUefa, listOf(napoli), listOf(uclGame)),
                provider(League("uel", Sport.FOOTBALL, "Europa League", "EU"), null, listOf(TeamRef("uel", "50033", "Sparta Praha")), emptyList()),
            )))
            val vm = TeamViewModel(repository, arsenalUefa, LocalDate(2026, 9, 16))
            store.put("team", vm)
            val done = vm.state.first { it.games.data != null && it.profile.data != null && it.roster.data != null && it.standings.data != null }
            assertEquals("premier-league", done.home.leagueId)
            assertEquals(listOf("premier-league", "ucl"), done.members?.map { it.leagueId })
            assertEquals(listOf("premier-league", "ucl"), done.standings.data?.map { it.league.id }, "the Europa League table does not name the club")
            assertEquals(listOf("ucl/2049560", "premier-league/2645215"), done.games.data?.map { "${it.leagueId}/${it.id}" })
            assertEquals(listOf("premier-league/3 2026-07-01..2027-06-30", "ucl/52280 2026-07-01..2027-06-30"), scheduleRequests.sorted(), "no schedule is asked of a competition the club is not in")
            assertEquals("premier-league/3", profileRequests.last(), "profile and squad come from the domestic league")
            assertEquals("premier-league", done.roster.data?.single()?.ref?.leagueId)
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }

    /**
     * A club that left a league keeps its id there (HockeyAllsvenskan clubs are still known to
     * the SHL platform). The squad must not be read from a competition whose table does not name
     * the club: the section reports that nobody serves it, rather than an error from the wrong one.
     */
    @Test
    fun theSquadIsNotReadFromACompetitionTheClubHasLeft() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val store = ViewModelStore()
        try {
            val leksandHa = TeamRef("hockeyallsvenskan", "LIF", "Leksand", "LIF", clubId = "leksand")
            val leksandShl = TeamRef("shl", "9541-95418PpkP", "Leksand", "LIF", clubId = "leksand")
            val modo = TeamRef("hockeyallsvenskan", "MODO", "MoDo", "MoDo", clubId = "modo")
            val farjestad = TeamRef("shl", "752c-752c12zB7Z", "Färjestad", clubId = "farjestad")
            val rosterRequests = ArrayList<String>()
            fun table(leagueId: String, vararg teams: TeamRef) = StandingsTable(leagueId, null, null,
                listOf(StandingsGroup("League", teams.mapIndexed { i, t -> StandingsRow(t, i + 1, 3, 3, 0, points = 9) })), "league")
            val ha = object : BaseLeagueProvider() {
                override val league = League("hockeyallsvenskan", Sport.HOCKEY, "HockeyAllsvenskan", "SE")
                override val capabilities = setOf(Capability.TEAM, Capability.TEAM_SCHEDULE, Capability.STANDINGS)
                override suspend fun standings(seasonId: String?) = table(league.id, leksandHa, modo)
                override suspend fun team(id: String) = Team(leksandHa, arena = "Tegera Arena")
                override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate) = emptyList<Game>()
            }
            val shl = object : BaseLeagueProvider() {
                override val league = League("shl", Sport.HOCKEY, "SHL", "SE")
                override val capabilities = setOf(Capability.TEAM, Capability.TEAM_SCHEDULE, Capability.STANDINGS, Capability.ROSTER)
                // Leksand plays in HockeyAllsvenskan this season, so the SHL table does not name it.
                override suspend fun standings(seasonId: String?) = table(league.id, farjestad)
                override suspend fun team(id: String) = Team(leksandShl)
                override suspend fun roster(teamId: String): List<Player> {
                    rosterRequests += teamId
                    return listOf(Player(PlayerRef("shl", "p1", "Last Season")))
                }
                override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate) = emptyList<Game>()
            }
            val repository = ScoresRepository(OpenScore(listOf(ha, shl)))
            val vm = TeamViewModel(repository, leksandHa, LocalDate(2026, 9, 24))
            store.put("team", vm)

            val done = vm.state.first { it.profile.data != null && it.standings.data != null && !it.roster.loading }
            assertEquals("hockeyallsvenskan", done.home.leagueId)
            assertEquals(listOf("hockeyallsvenskan"), done.members?.map { it.leagueId }, "the SHL table does not name the club")
            assertFalse(done.roster.supported, "no competition the club is in serves a squad")
            assertNull(done.roster.error, "an absent squad is not a failed one")
            assertEquals(emptyList(), rosterRequests, "the league the club has left is never asked")
        } finally {
            store.clear()
            Dispatchers.resetMain()
        }
    }
}
