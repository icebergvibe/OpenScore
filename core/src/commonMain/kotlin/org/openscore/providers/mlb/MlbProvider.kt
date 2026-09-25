package org.openscore.providers.mlb

import kotlinx.datetime.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamSeasonStats
import org.openscore.net.Fetcher
import org.openscore.net.OpenScoreJson
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.LivePollBudget
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import org.openscore.provider.decodeJson
import org.openscore.provider.getJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * MLB via `statsapi.mlb.com` — see apis/baseball/mlb/README.md.
 *
 * Ids: games are the `gamePk`, teams and players the numeric Stats API ids (`147`,
 * `592450`), all as strings. [gamesOn] takes the game's **official (local) date**, which is
 * how the schedule itself is bucketed — a late West-coast game stays on its local day.
 *
 * Baseball has no clock: while live, [Game.clock] only names the inning and its half, and
 * [Game.situation] carries the [org.openscore.model.baseball.BaseballSituation] (outs,
 * count, runners, batter, pitcher).
 */
public class MlbProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
) : BaseLeagueProvider() {

    override val league: League = LEAGUE

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.TEAM_SCHEDULE,
        Capability.TEAM_STATS,
        Capability.ROSTER,
        Capability.PLAYER,
        Capability.LIVE_UPDATES,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
        Capability.EVENT_COORDINATES,
    )

    override suspend fun gamesOn(date: LocalDate): List<Game> =
        MlbMapper.games(getJson(schedulePath(date), MlbSchedule.serializer(), SCHEDULE_MAX_AGE))

    override suspend fun game(id: String): Game = MlbMapper.game(feed(id))

    /**
     * Starts with one complete feed, then asks MLB for RFC 6902 deltas from its timestamp.
     * The first payload remains comprehensive (events, situation and linescore); follow-ups are
     * normally only a few patch operations instead of another multi-hundred-kilobyte document.
     * If MLB loses the cursor or changes the patch shape, a full feed safely re-establishes it.
     */
    override fun live(gameId: String, interval: Duration): Flow<Game> = flow {
        val budget = LivePollBudget(maxOf(interval, LIVE_MAX_AGE))
        var document = feedDocument(gameId)
        var current = decodeFeed(document, gameId)
        var game = MlbMapper.game(current)
        emit(game)

        while (budget.open && !game.state.isTerminal) {
            budget.wait()
            // A failed diff leaves `document` and `current` untouched, so the next tick asks
            // from the same timestamp and picks up everything that was missed.
            val next = budget.read {
                val fetched = nextFeedDocument(gameId, current.metaData.timeStamp, document)
                val decoded = decodeFeed(fetched, gameId)
                document = fetched
                current = decoded
                MlbMapper.game(decoded)
            }.getOrNull() ?: continue
            if (next != game) emit(next)
            game = next
        }
    }

    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        require(startDate <= endDate) { "The schedule's start date must be on or before its end date" }
        val path = "/v1/schedule?sportId=1&teamId=$teamId&startDate=$startDate&endDate=$endDate&hydrate=$SCHEDULE_HYDRATE"
        return MlbMapper.games(getJson(path, MlbSchedule.serializer(), TABLE_MAX_AGE))
            .filter { it.home.id == teamId || it.away.id == teamId }
            .distinctBy { it.id }
            .sortedBy { it.startTime }
    }

    override suspend fun teamStats(teamId: String, seasonId: String): TeamSeasonStats =
        MlbMapper.teamStats(
            getJson("/v1/teams/$teamId/stats?stats=season&group=hitting,pitching&season=$seasonId", MlbTeamStats.serializer(), TABLE_MAX_AGE),
            teamId, seasonId,
        )

    override suspend fun events(gameId: String): List<GameEvent> = MlbMapper.events(feed(gameId))

    override suspend fun lineups(gameId: String): List<Lineup> =
        MlbMapper.lineups(gameId, getJson("/v1/game/$gameId/boxscore", MlbBoxscore.serializer(), LIVE_MAX_AGE))

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId?.let { "&season=$it" } ?: ""
        return MlbMapper.standings(getJson("/v1/standings?leagueId=103,104&hydrate=team,division$season", MlbStandings.serializer(), TABLE_MAX_AGE))
    }

    override suspend fun team(id: String): Team {
        val teams = getJson("/v1/teams/$id", MlbTeamsResponse.serializer(), STATIC_MAX_AGE).teams
        return MlbMapper.team(teams.firstOrNull() ?: throw NotFoundException("MLB team '$id' not found", league.id))
    }

    override suspend fun roster(teamId: String): List<Player> =
        MlbMapper.roster(getJson("/v1/teams/$teamId/roster", MlbRoster.serializer(), STATIC_MAX_AGE), teamId)

    override suspend fun player(id: String): Player {
        val people = getJson("/v1/people/$id?hydrate=currentTeam", MlbPeopleResponse.serializer(), STATIC_MAX_AGE).people
        return MlbMapper.player(people.firstOrNull() ?: throw NotFoundException("MLB person '$id' not found", league.id))
    }

    /** The live feed answers an unknown gamePk with `200 {gamePk: 0, …}` rather than a 404. */
    private suspend fun feed(id: String): MlbLiveFeed {
        val feed = getJson("/v1.1/game/$id/feed/live", MlbLiveFeed.serializer(), LIVE_MAX_AGE)
        checkFound(feed, id)
        return feed
    }

    private suspend fun feedDocument(id: String): JsonElement = jsonDocument("/v1.1/game/$id/feed/live")

    private suspend fun nextFeedDocument(id: String, timestamp: String?, current: JsonElement): JsonElement {
        if (timestamp == null) return feedDocument(id)
        return try {
            val update = jsonDocument("/v1.1/game/$id/feed/live/diffPatch?startTimecode=$timestamp")
            if (update is JsonArray) MlbJsonPatch.apply(current, update) else update
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // A timestamp can expire or a patch can be dropped. A full document is the stable
            // recovery point and keeps the live flow correct without an intermediary server.
            feedDocument(id)
        }
    }

    private suspend fun jsonDocument(path: String): JsonElement {
        val response = fetcher.get(baseUrl + path, maxAge = LIVE_MAX_AGE)
        if (response.status == 404) throw NotFoundException("${league.id}: ${response.url} → 404", league.id)
        response.requireSuccess()
        return decodeJson(response, JsonElement.serializer(), league.id)
    }

    private fun decodeFeed(document: JsonElement, id: String): MlbLiveFeed = try {
        OpenScoreJson.decodeFromJsonElement(MlbLiveFeed.serializer(), document).also { checkFound(it, id) }
    } catch (e: SerializationException) {
        throw ProviderException("${league.id}: cannot parse live feed for $id: ${e.message}", e, league.id)
    } catch (e: IllegalArgumentException) {
        throw ProviderException("${league.id}: cannot parse live feed for $id: ${e.message}", e, league.id)
    }

    private fun checkFound(feed: MlbLiveFeed, id: String) {
        if (feed.gamePk == 0L || feed.gameData.teams == null) throw NotFoundException("MLB game '$id' not found", league.id)
    }

    private fun schedulePath(date: LocalDate) = "/v1/schedule?sportId=1&date=$date&hydrate=$SCHEDULE_HYDRATE"

    private suspend fun <T> getJson(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://statsapi.mlb.com/api"

        /** Full team objects, the R/H/E line and inning state, probable pitchers and decisions in one scoreboard call. */
        public const val SCHEDULE_HYDRATE: String = "team,linescore,probablePitcher,decisions"

        public val LEAGUE: League = League(
            id = "mlb",
            sport = Sport.BASEBALL,
            name = "Major League Baseball",
            country = "US",
            zone = TimeZone.of("America/New_York"),
            websiteUrl = "https://www.mlb.com",
        )

        /** The feed itself asks for 10 s (`metaData.wait`, `Cache-Control: max-age=10`). */
        private val LIVE_MAX_AGE = 10.seconds
        /** `/schedule` is served with `max-age=20`. */
        private val SCHEDULE_MAX_AGE = 20.seconds
        private val TABLE_MAX_AGE = 5.minutes
        private val STATIC_MAX_AGE = 1.hours
    }
}
