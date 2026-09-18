package org.openscore.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamSeasonStats
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One implementation per league. All calls are read-only network calls (or cache hits)
 * and may throw [org.openscore.net.HttpException] / [ProviderException].
 *
 * Every method is optional: [capabilities] says which ones work, and the others throw
 * [UnsupportedCapabilityException].
 */
public interface LeagueProvider {
    public val league: League
    public val capabilities: Set<Capability>

    /**
     * Games on a calendar date **in the league's own convention** (the NHL uses US Eastern
     * dates, European leagues local dates). Providers document which.
     */
    public suspend fun gamesOn(date: LocalDate): List<Game>

    /** One game with as much state as one call gives, including [Game.events] where cheap. */
    public suspend fun game(id: String): Game

    public suspend fun events(gameId: String): List<GameEvent>

    public suspend fun lineups(gameId: String): List<Lineup>

    /** Current standings, or those of [seasonId] when given and supported. */
    public suspend fun standings(seasonId: String? = null): StandingsTable

    public suspend fun team(id: String): Team

    /** Games for one team in an inclusive range of the league's calendar dates. */
    public suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game>

    /** Regular-season team totals, retaining the provider's statistical notation. */
    public suspend fun teamStats(teamId: String, seasonId: String): TeamSeasonStats

    public suspend fun roster(teamId: String): List<Player>

    public suspend fun player(id: String): Player

    /**
     * Emits the game as it changes until it is finished. The default polls [game] no faster
     * than every 10 s (see docs/principles.md); push-capable providers override this.
     */
    public fun live(gameId: String, interval: Duration = 10.seconds): Flow<Game>

    public fun supports(capability: Capability): Boolean = capability in capabilities
}

public open class ProviderException(
    message: String,
    cause: Throwable? = null,
    /** League the error belongs to, when known. */
    public val leagueId: String? = null,
) : RuntimeException(message, cause)

public class NotFoundException(message: String, leagueId: String? = null) : ProviderException(message, leagueId = leagueId)

/**
 * Base class that turns every unimplemented call into [UnsupportedCapabilityException]
 * and provides the polling [live] implementation.
 */
public abstract class BaseLeagueProvider : LeagueProvider {

    protected fun unsupported(capability: Capability): Nothing =
        throw UnsupportedCapabilityException(league.id, capability)

    override suspend fun gamesOn(date: LocalDate): List<Game> = unsupported(Capability.GAMES_BY_DATE)
    override suspend fun game(id: String): Game = unsupported(Capability.GAME)
    override suspend fun events(gameId: String): List<GameEvent> = unsupported(Capability.EVENTS)
    override suspend fun lineups(gameId: String): List<Lineup> = unsupported(Capability.LINEUPS)
    override suspend fun standings(seasonId: String?): StandingsTable = unsupported(Capability.STANDINGS)
    override suspend fun team(id: String): Team = unsupported(Capability.TEAM)
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> = unsupported(Capability.TEAM_SCHEDULE)
    override suspend fun teamStats(teamId: String, seasonId: String): TeamSeasonStats = unsupported(Capability.TEAM_STATS)
    override suspend fun roster(teamId: String): List<Player> = unsupported(Capability.ROSTER)
    override suspend fun player(id: String): Player = unsupported(Capability.PLAYER)

    override fun live(gameId: String, interval: Duration): Flow<Game> {
        if (Capability.LIVE_UPDATES !in capabilities) unsupported(Capability.LIVE_UPDATES)
        return pollGame(this, gameId, interval)
    }
}

/** Polls [provider].game until it is final; never faster than the politeness floor. */
public fun pollGame(provider: LeagueProvider, gameId: String, interval: Duration): Flow<Game> = flow {
    val effective = maxOf(interval, MIN_LIVE_POLL_INTERVAL)
    var last: Game? = null
    while (true) {
        val game = provider.game(gameId)
        if (game != last) emit(game)
        last = game
        if (game.state.isTerminal) return@flow
        delay(effective)
    }
}

/** Politeness floor from docs/principles.md. */
public val MIN_LIVE_POLL_INTERVAL: Duration = 10.seconds
