package org.openscore.app.ui.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.toLocalDateTime
import org.openscore.app.data.ScoresRepository
import org.openscore.model.Game
import org.openscore.model.League
import org.openscore.model.Player
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.TeamSeasonStats
import org.openscore.provider.Capability
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Clock

data class TeamSection<T>(
    val data: T? = null,
    val loading: Boolean = true,
    val error: String? = null,
    /** False means no provider can supply this section, rather than that the request failed. */
    val supported: Boolean = true,
)

/** One competition's table, with the ref the club is known by in it. */
data class LeagueStandings(val league: League, val team: TeamRef, val table: StandingsTable)

data class TeamPageState(
    /** The club's ref in every league it may play in, domestic first (see [clubSources]). */
    val sources: List<TeamRef>,
    /**
     * The sources whose table names the club this season, plus competitions without a table
     * (cups); null until the tables have answered. Games come from these.
     */
    val members: List<TeamRef>? = null,
    /** The league the profile and squad are read from: the club's domestic league where it has one. */
    val home: TeamRef,
    val profile: TeamSection<Team> = TeamSection(),
    val games: TeamSection<List<Game>> = TeamSection(),
    val standings: TeamSection<List<LeagueStandings>> = TeamSection(),
    val roster: TeamSection<List<Player>> = TeamSection(),
    val stats: TeamSection<TeamSeasonStats> = TeamSection(),
) {
    val homeStandings: LeagueStandings? get() = standings.data?.firstOrNull { it.league.id == home.leagueId } ?: standings.data?.firstOrNull()
}

enum class TeamPart { PROFILE, GAMES, STANDINGS, ROSTER, STATS }

/**
 * A club's page across every competition it plays in. The tables are read first, because they
 * say which of the candidate competitions the club is actually in this season (a Premier League
 * club is in one of the three UEFA competitions at most); the other sections follow from that.
 * Independent requests keep a partial API failure local to its section.
 */
class TeamViewModel(private val repository: ScoresRepository, val team: TeamRef, val today: LocalDate = Clock.System.now().toLocalDateTime(leagueTimeZone(team.leagueId)).date) : ViewModel() {
    private val sources = clubSources(team, repository.leagues)
    private val mutableState = MutableStateFlow(initialState())
    val state = mutableState.asStateFlow()
    private val jobs = mutableMapOf<TeamPart, Job>()

    init { refresh() }

    fun refresh() {
        retry(TeamPart.STANDINGS)
        retry(TeamPart.PROFILE)
        retry(TeamPart.ROSTER)
        retry(TeamPart.STATS)
        retry(TeamPart.GAMES)
    }

    fun retry(part: TeamPart) {
        if (!supports(part)) return
        if (jobs[part]?.isActive == true) return
        jobs[part] = viewModelScope.launch {
            when (part) {
                TeamPart.STANDINGS -> loadStandings()
                TeamPart.PROFILE -> load({ it.profile }, { s, v -> s.copy(profile = v) }) { repository.team(home()) }
                TeamPart.ROSTER -> load({ it.roster }, { s, v -> s.copy(roster = v) }) { repository.roster(rosterSource()) }
                TeamPart.STATS -> load({ it.stats }, { s, v -> s.copy(stats = v) }) { repository.teamStats(home(), seasonWindow(home().leagueId, today).start.year) }
                TeamPart.GAMES -> load({ it.games }, { s, v -> s.copy(games = v) }) { loadGames() }
            }
        }
    }

    private fun supports(part: TeamPart): Boolean = sources.any { repository.supports(it.leagueId, capability(part)) }

    private fun capability(part: TeamPart): Capability = when (part) {
        TeamPart.PROFILE -> Capability.TEAM
        TeamPart.GAMES -> Capability.TEAM_SCHEDULE
        TeamPart.STANDINGS -> Capability.STANDINGS
        TeamPart.ROSTER -> Capability.ROSTER
        TeamPart.STATS -> Capability.TEAM_STATS
    }

    private fun initialState(): TeamPageState {
        fun <T> section(part: TeamPart): TeamSection<T> = TeamSection(loading = supports(part), supported = supports(part))
        return TeamPageState(
            sources = sources,
            home = sources.first(),
            profile = section(TeamPart.PROFILE),
            games = section(TeamPart.GAMES),
            standings = section(TeamPart.STANDINGS),
            roster = section(TeamPart.ROSTER),
            stats = section(TeamPart.STATS),
        )
    }

    /**
     * Every candidate table at once; the ones naming the club are kept, home league first.
     * A table that fails to load is treated as unknown membership: the club keeps that
     * league as a candidate for games, and the section reports the error if none answered.
     */
    private suspend fun loadStandings() {
        mutableState.update { it.copy(standings = it.standings.copy(loading = true, error = null)) }
        val results = coroutineScope {
            sources.filter { repository.supports(it.leagueId, Capability.STANDINGS) }.map { ref ->
                async { ref to runCatchingUnlessCancelled { repository.standings(ref.leagueId) } }
            }.awaitAll()
        }
        val tables = results.mapNotNull { (ref, r) -> r.getOrNull()?.let { t -> repository.league(ref.leagueId)?.let { LeagueStandings(it, ref, t) } } }
        val failed = results.filter { (_, r) -> r.isFailure }.map { it.first.leagueId }.toSet()
        val inTable = tables.filter { it.table.rowFor(it.team) != null }
        // A table that answered with rows decides membership; an empty one (pre-season) or a failed read does not.
        val tabled = tables.filter { it.table.rows.isNotEmpty() }.map { it.league.id }.toSet()
        val members = sources.filter { s -> s.leagueId !in tabled || inTable.any { it.league.id == s.leagueId } }
        val home = members.firstOrNull { repository.league(it.leagueId)?.isDomestic == true && it.leagueId in inTable.map { m -> m.league.id } }
            ?: members.firstOrNull { repository.league(it.leagueId)?.isDomestic == true }
            ?: sources.first()
        val homeChanged = home.leagueId != mutableState.value.home.leagueId
        val ordered = inTable.sortedWith(compareBy({ if (it.league.id == home.leagueId) 0 else 1 }, { sources.indexOfFirst { s -> s.leagueId == it.league.id } }))
        mutableState.update {
            it.copy(
                members = members, home = home,
                standings = TeamSection(
                    data = ordered, loading = false,
                    error = if (tables.isEmpty() && failed.isNotEmpty()) "Couldn't update this section." else null,
                ),
            )
        }
        // The profile and squad were requested from the first candidate before the tables said
        // which league is home; a club opened from a cup or a UEFA game is re-read from its own league.
        if (homeChanged) {
            jobs.remove(TeamPart.PROFILE)?.cancel(); retry(TeamPart.PROFILE)
            jobs.remove(TeamPart.ROSTER)?.cancel(); retry(TeamPart.ROSTER)
            jobs.remove(TeamPart.STATS)?.cancel(); retry(TeamPart.STATS)
        }
    }

    private fun home(): TeamRef = mutableState.value.home.takeIf { repository.supports(it.leagueId, Capability.TEAM) }
        ?: sources.first { repository.supports(it.leagueId, Capability.TEAM) }

    private fun rosterSource(): TeamRef = mutableState.value.home.takeIf { repository.supports(it.leagueId, Capability.ROSTER) }
        ?: sources.first { repository.supports(it.leagueId, Capability.ROSTER) }

    /** The season's games from every competition the club is in, each in that league's season window. */
    private suspend fun loadGames(): List<Game> {
        // Membership is what keeps a Premier League club's page from asking three UEFA competitions for its games.
        if (mutableState.value.members == null) jobs[TeamPart.STANDINGS]?.join()
        val leagues = (mutableState.value.members ?: sources).filter { repository.supports(it.leagueId, Capability.TEAM_SCHEDULE) }
        val results = coroutineScope {
            leagues.map { ref ->
                async {
                    val window = seasonWindow(ref.leagueId, today)
                    runCatchingUnlessCancelled { repository.teamSchedule(ref, window.start, window.end) }
                }
            }.awaitAll()
        }
        if (results.isNotEmpty() && results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
        return results.flatMap { it.getOrDefault(emptyList()) }.filter { it.belongsTo(team) }.distinctBy { it.leagueId to it.id }.sortedBy { it.startTime }
    }

    private suspend fun <T> load(read: (TeamPageState) -> TeamSection<T>, write: (TeamPageState, TeamSection<T>) -> TeamPageState, fetch: suspend () -> T) {
        mutableState.update { write(it, read(it).copy(loading = true, error = null)) }
        try {
            val result = fetch()
            mutableState.update { write(it, TeamSection(data = result, loading = false)) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            mutableState.update { write(it, read(it).copy(loading = false, error = "Couldn't update this section.")) }
        }
    }

    /** Only the small day listings are refreshed while the page is visible, at the feed's 60 s cadence. */
    suspend fun refreshScores() {
        jobs[TeamPart.GAMES]?.join()
        val held = mutableState.value.games.data ?: return
        val now = Clock.System.now()
        for ((leagueId, dates) in scoreRefreshDates(held, now)) {
            for (date in dates) {
                try {
                    val result = repository.gamesOn(date, listOf(leagueId)).last()
                    if (result.errors.isNotEmpty()) throw IllegalStateException()
                    val updates = result.games.filter { it.belongsTo(team) }
                    mutableState.update { state ->
                        val merged = (state.games.data.orEmpty().associateBy { it.leagueId to it.id } + updates.associateBy { it.leagueId to it.id }).values.sortedBy { it.startTime }
                        state.copy(games = TeamSection(data = merged, loading = false))
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutableState.update { it.copy(games = it.games.copy(error = "Scores couldn't refresh. Showing the last update.")) } }
            }
        }
    }
}
