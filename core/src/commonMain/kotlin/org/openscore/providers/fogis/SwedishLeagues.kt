package org.openscore.providers.fogis

import kotlinx.datetime.TimeZone
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.net.Fetcher
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.runCatchingUnlessCancelled
import org.openscore.providers.sportomedia.AllsvenskanProvider
import org.openscore.providers.sportomedia.SmTeam
import org.openscore.providers.sportomedia.SportomediaProvider
import org.openscore.providers.sportomedia.SuperettanProvider
import kotlin.time.Duration

/** The competitions [FogisProvider] can be narrowed to; see [FogisProvider.tournamentIds]. */
public enum class FogisCompetition { ALLSVENSKAN, SUPERETTAN, SVENSKA_CUPEN }

/**
 * One Swedish competition as a league of its own: games, live state, events and lineups from
 * the FA's Fogis livescore, tables (and team/roster/player) from Sportomedia where that
 * league has them.
 *
 * Why Fogis for the games and not allsvenskan.se: polling the same match (AIK–Västerås,
 * 2026-09-13) through both at the same cadence, Sportomedia answered 29 of the first 100 live
 * ticks with HTTP 429 or a timeout, reported kick-off four minutes late and left `matchMinute`
 * on 13' for three minutes, while Fogis answered every tick with the minute advancing and
 * three times the events. Fogis has no standings, so those stay with Sportomedia.
 *
 * Every team id under this league is the Fogis team id — the games carry them natively, and
 * the Sportomedia tables and team profiles are re-keyed through the feed's own `fogisId`
 * bridge (`teamsForLeague`), so a table row, a fixture and a team page all name one team
 * by one id (the crosswalk's `fogis` namespace), and `team()`/`roster()` accept it.
 */
public class SwedishLeagueProvider(
    override val league: League,
    private val competition: FogisCompetition,
    private val fogis: FogisProvider,
    /** Standings / team / roster / player, or null for a competition without a table. */
    private val tables: SportomediaProvider? = null,
) : BaseLeagueProvider() {

    override val capabilities: Set<Capability> =
        fogis.capabilities + Capability.TEAM_SCHEDULE + (tables?.capabilities?.intersect(TABLE_CAPABILITIES) ?: emptySet())

    /**
     * The day overview gives a live game's first-half kick-off but never the second half's
     * `HALFSTARTED` (checked 2026-09-13 15:07 local: Hammarby–Brommapojkarna in the second half,
     * one half-start in the overview, two in `game-info`), so a game whose overview clock has
     * no elapsed time is read from its own resource — a few KB, cached like the overview.
     */
    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val listed = fogis.gamesOn(date, fogis.tournamentIds(competition))
        return coroutineScope {
            listed.map { g ->
                async {
                    val needsClock = g.state.isLive && g.clock?.time?.elapsed == null
                    relabel(if (needsClock) runCatchingUnlessCancelled { fogis.game(g.id) }.getOrDefault(g) else g)
                }
            }.awaitAll()
        }
    }

    override suspend fun game(id: String): Game = relabel(fogis.game(id))

    /** Fogis's schedule files name teams without crests; the Sportomedia team list (cached) supplies them by Fogis id. */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> = coroutineScope {
        val crests = async { tables?.let { t -> teamsOrEmpty(t).mapNotNull { team -> team.fogisId?.let { it.toString() to team } }.toMap() }.orEmpty() }
        val games = fogis.teamSchedule(teamId, fogis.tournamentIds(competition), startDate, endDate)
        val byFogisId = crests.await()
        fun dress(ref: TeamRef): TeamRef = byFogisId[ref.id]?.let { t -> ref.copy(leagueId = league.id, abbreviation = t.abbrv, logoUrl = ref.logoUrl ?: t.logoImageUrl) } ?: relabel(ref)
        games.map { g -> g.copy(leagueId = league.id, home = dress(g.home), away = dress(g.away)) }
    }

    override suspend fun events(gameId: String): List<GameEvent> = fogis.events(gameId).map(::relabel)

    override suspend fun lineups(gameId: String): List<Lineup> = fogis.lineups(gameId).map { it.copy(team = relabel(it.team)) }

    override fun live(gameId: String, interval: Duration): Flow<Game> = fogis.live(gameId, interval).map(::relabel)

    override suspend fun standings(seasonId: String?): StandingsTable = coroutineScope {
        val t = tables()
        val directory = async { fogisIds(t) }
        val table = t.standings(seasonId)
        val ids = directory.await()
        table.copy(leagueId = league.id, groups = table.groups.map { g -> g.copy(rows = g.rows.map { r -> r.copy(team = rekey(r.team, ids)) }) })
    }

    override suspend fun team(id: String): Team {
        val t = tables()
        val team = t.team(id)
        return team.copy(ref = rekey(team.ref, fogisIds(t)))
    }

    override suspend fun roster(teamId: String): List<Player> = tables().roster(teamId)

    override suspend fun player(id: String): Player = tables().player(id)

    private fun tables(): SportomediaProvider = tables ?: unsupported(Capability.STANDINGS)

    /** Sportomedia abbreviation → Fogis team id for this season, from the cached team list. */
    private suspend fun fogisIds(t: SportomediaProvider): Map<String, String> =
        teamsOrEmpty(t).mapNotNull { team -> team.fogisId?.let { team.abbrv to it.toString() } }.toMap()

    /** The season's team list, or nothing when the (optional) directory cannot be read: crests and the id bridge are decoration. */
    private suspend fun teamsOrEmpty(t: SportomediaProvider): List<SmTeam> = runCatchingUnlessCancelled { t.teams() }.getOrDefault(emptyList())

    /** A Sportomedia ref (abbreviation id) as this league's ref (Fogis id); an unbridged team keeps its abbreviation and club. */
    private fun rekey(ref: TeamRef, fogisIds: Map<String, String>): TeamRef {
        val fogisId = fogisIds[ref.id] ?: return ref.copy(leagueId = league.id)
        return TeamRef(league.id, fogisId, ref.name, ref.abbreviation, ref.logoUrl)
    }

    private fun relabel(ref: TeamRef): TeamRef = ref.copy(leagueId = league.id)

    private fun relabel(event: GameEvent): GameEvent = event.copy(team = event.team?.let(::relabel))

    private fun relabel(game: Game): Game = game.copy(
        leagueId = league.id,
        home = relabel(game.home),
        away = relabel(game.away),
        events = game.events?.map(::relabel),
    )

    public companion object {
        private val TABLE_CAPABILITIES = setOf(Capability.STANDINGS, Capability.TEAM, Capability.ROSTER, Capability.PLAYER)

        public val SVENSKA_CUPEN: League = League("svenska-cupen", Sport.FOOTBALL, "Svenska Cupen", "SE", TimeZone.of("Europe/Stockholm"), "https://www.svenskfotboll.se/")

        /** The three Swedish leagues the default aggregator serves, sharing one [FogisProvider]. */
        public fun default(fetcher: Fetcher, fogis: FogisProvider = FogisProvider(fetcher)): List<SwedishLeagueProvider> = listOf(
            SwedishLeagueProvider(AllsvenskanProvider.LEAGUE, FogisCompetition.ALLSVENSKAN, fogis, AllsvenskanProvider(fetcher)),
            SwedishLeagueProvider(SuperettanProvider.LEAGUE, FogisCompetition.SUPERETTAN, fogis, SuperettanProvider(fetcher)),
            SwedishLeagueProvider(SVENSKA_CUPEN, FogisCompetition.SVENSKA_CUPEN, fogis),
        )
    }
}
