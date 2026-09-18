package org.openscore.providers.nhl

import kotlinx.datetime.LocalDate
import kotlinx.serialization.DeserializationStrategy
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.net.Fetcher
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * NHL via `api-web.nhle.com/v1` — see apis/hockey/nhl/README.md.
 *
 * Ids: games are the 10-digit NHL game id, teams the 3-letter abbreviation, players the
 * NHL player id. [gamesOn] takes a **US Eastern** calendar date, which is how the NHL
 * itself buckets games.
 */
public class NhlProvider(
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
        Capability.ROSTER,
        Capability.PLAYER,
        Capability.LIVE_UPDATES,
        Capability.CLOCK,
        Capability.CLOCK_RUNNING_FLAG,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
        Capability.EVENT_COORDINATES,
    )

    override suspend fun gamesOn(date: LocalDate): List<Game> =
        getJson("/score/$date", NhlScoreResponse.serializer(), LIVE_MAX_AGE).games.map(NhlMapper::game)

    override suspend fun game(id: String): Game =
        NhlMapper.game(playByPlay(id))

    override suspend fun events(gameId: String): List<GameEvent> =
        NhlMapper.events(playByPlay(gameId))

    override suspend fun lineups(gameId: String): List<Lineup> =
        NhlMapper.lineups(getJson("/gamecenter/$gameId/boxscore", NhlBoxscore.serializer(), LIVE_MAX_AGE))

    override suspend fun standings(seasonId: String?): StandingsTable {
        val path = if (seasonId == null) {
            "/standings/now"
        } else {
            val seasons = getJson("/standings-season", NhlStandingsSeasons.serializer(), STATIC_MAX_AGE)
            val season = seasons.seasons.firstOrNull { it.id.toString() == seasonId }
                ?: throw NotFoundException("NHL season $seasonId not found in /standings-season", league.id)
            "/standings/${season.standingsEnd}"
        }
        return NhlMapper.standings(getJson(path, NhlStandingsResponse.serializer(), TABLE_MAX_AGE))
    }

    override suspend fun team(id: String): Team {
        val standings = getJson("/standings/now", NhlStandingsResponse.serializer(), STATIC_MAX_AGE)
        val row = standings.standings.firstOrNull { it.teamAbbrev.default.equals(id, ignoreCase = true) }
            ?: throw NotFoundException("NHL team '$id' not in current standings", league.id)
        return NhlMapper.team(row)
    }

    override suspend fun roster(teamId: String): List<Player> =
        NhlMapper.roster(getJson("/roster/$teamId/current", NhlRoster.serializer(), STATIC_MAX_AGE), teamId)

    override suspend fun player(id: String): Player =
        NhlMapper.player(getJson("/player/$id/landing", NhlPlayerLanding.serializer(), STATIC_MAX_AGE))

    private suspend fun playByPlay(id: String): NhlPlayByPlay =
        getJson("/gamecenter/$id/play-by-play", NhlPlayByPlay.serializer(), LIVE_MAX_AGE)

    private suspend fun <T> getJson(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        // The NHL answers unknown ids with an HTML 404 page; getJson turns that into NotFoundException.
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api-web.nhle.com/v1"

        public val LEAGUE: League = League(
            id = "nhl",
            sport = Sport.HOCKEY,
            name = "National Hockey League",
            country = "US",
            websiteUrl = "https://www.nhl.com",
        )

        /** Politeness floor for live game endpoints (docs/principles.md). */
        private val LIVE_MAX_AGE = 10.seconds
        private val TABLE_MAX_AGE = 5.minutes
        private val STATIC_MAX_AGE = 1.hours
    }
}
