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
import kotlin.time.Duration.Companion.hours
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

/**
 * Polls [provider].game until it is final; never faster than the politeness floor.
 *
 * A failed read does not end the flow. A phone changing cell, or a feed dropping one request,
 * is not the end of the game, and a collector that treats the flow ending as "nothing more to
 * say" would sit on a stale score for as long as the screen stayed open. Only
 * [MAX_CONSECUTIVE_FAILURES] failures in a row are taken as real, and the last of them is
 * thrown; one good read resets the count.
 *
 * The loop also stops after [LIVE_POLL_CEILING] of polling. A terminal state is what normally
 * ends it, and [org.openscore.model.GameState.SUSPENDED] deliberately is not terminal, because
 * a suspended game resumes - so a game abandoned without its feed ever saying so would
 * otherwise be polled for as long as anyone had it open.
 */
public fun pollGame(provider: LeagueProvider, gameId: String, interval: Duration): Flow<Game> = flow {
    val budget = LivePollBudget(interval)
    var last: Game? = null
    while (budget.open) {
        val game = budget.read { provider.game(gameId) }.getOrNull()
        if (game != null) {
            if (game != last) emit(game)
            last = game
            if (game.state.isTerminal) return@flow
        }
        budget.wait()
    }
}

/**
 * What keeps a live poll honest, shared by [pollGame] and by the providers whose feeds need a
 * loop of their own (MLB's document diffing, UEFA's change hash, Bundesliga's stream). Written
 * once because every one of those loops needs the same two bounds and none of them is the
 * interesting part of the provider.
 */
public class LivePollBudget(interval: Duration) {

    /** The wait between ticks, never under the politeness floor. */
    public val interval: Duration = maxOf(interval, MIN_LIVE_POLL_INTERVAL)

    private var failures = 0

    /**
     * The delays this budget has issued, which is what [LIVE_POLL_CEILING] counts; time spent
     * waiting on the provider is the fetcher's own bounded business.
     */
    private var polled = Duration.ZERO

    /** Whether the loop may run again. False once the ceiling is reached. */
    public val open: Boolean get() = polled < LIVE_POLL_CEILING

    /**
     * One read of the feed. A failure comes back as a failed [Result] so the loop can treat it
     * as "no news this tick" rather than the end of the game; [MAX_CONSECUTIVE_FAILURES] in a
     * row is no longer transient and the last one is thrown. One success forgives the rest.
     *
     * The result is a [Result] rather than a nullable value because a read that legitimately
     * answers null (UEFA's livescore, which has no entry for a game that has not started) must
     * not be mistaken for a read that failed.
     */
    public suspend fun <T> read(block: suspend () -> T): Result<T> {
        val result = runCatchingUnlessCancelled(block)
        if (result.isSuccess) {
            failures = 0
            return result
        }
        if (++failures >= MAX_CONSECUTIVE_FAILURES) throw result.exceptionOrNull()!!
        return result
    }

    /**
     * Waits out [duration] (the poll interval by default) and charges it against the ceiling.
     * A stream-backed provider passes its own reconnect pause instead, so its idle time counts
     * towards the same bound as a polling provider's.
     */
    public suspend fun wait(duration: Duration = interval) {
        delay(duration)
        polled += duration
    }
}

/** Politeness floor from docs/principles.md. */
public val MIN_LIVE_POLL_INTERVAL: Duration = 10.seconds

/**
 * Failed reads in a row before [pollGame] gives up on a game. Enough to ride out a lost
 * connection or a feed's bad minute at the 10 s floor, short enough that a reader is not left
 * watching a frozen score indefinitely.
 */
public const val MAX_CONSECUTIVE_FAILURES: Int = 5

/** How long [pollGame] keeps polling a game that never reaches a terminal state. */
public val LIVE_POLL_CEILING: Duration = 8.hours
