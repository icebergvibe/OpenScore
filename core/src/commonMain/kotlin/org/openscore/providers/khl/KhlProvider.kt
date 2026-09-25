package org.openscore.providers.khl

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.plus
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.net.Fetcher
import org.openscore.net.OpenScoreJson
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import org.openscore.provider.decodeJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * KHL via the official mobile-app API on webcaster.pro — see apis/hockey/khl/README.md.
 *
 * Ids: games are webcaster event ids (`3000051`, not the khl.ru id), teams webcaster team
 * ids, `seasonId` is a stage id (season × phase, e.g. `407` = Regular 2026/27). Dates are
 * Moscow dates. No clock is published (only the current period). Rosters and player
 * profiles are not supported: `players_v2.json` ignores its team filter and there is no
 * per-player endpoint. Live push is MQTT (not implemented) — `live()` polls.
 */
public class KhlProvider(
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
        Capability.LIVE_UPDATES,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
    )

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val from = date.atStartOfDayIn(league.zone).epochSeconds
        val to = date.plus(1, DateTimeUnit.DAY).atStartOfDayIn(league.zone).epochSeconds
        val url = "$baseUrl/events_v2.json?locale=en&order_direction=asc&q[start_at_gt_time_from_unixtime]=$from&q[start_at_lt_time_from_unixtime]=$to"
        val events = get(url, ListSerializer(KhlEventWrapper.serializer()), LIVE_MAX_AGE)
        return events.map { KhlMapper.game(it.event, withEvents = false) }.sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game = KhlMapper.game(detail(id), withEvents = true)

    override suspend fun events(gameId: String): List<GameEvent> {
        val e = detail(gameId)
        return KhlMapper.events(e, KhlMapper.stage(e))
    }

    override suspend fun lineups(gameId: String): List<Lineup> = KhlMapper.lineups(detail(gameId))

    override suspend fun standings(seasonId: String?): StandingsTable {
        val stageId = seasonId?.toIntOrNull()
            ?: get("$baseUrl/data.json", KhlData.serializer(), STATIC_MAX_AGE).current_stage_id
            ?: throw ProviderException("KHL: data.json has no current_stage_id", leagueId = league.id)
        val seasons = get("$baseUrl/tables_v2.json?locale=en&stage_id=$stageId", ListSerializer(KhlSeasonTables.serializer()), TABLE_MAX_AGE)
        val stage = seasons.flatMap { it.stages }.firstOrNull { it.id == stageId }
            ?: throw NotFoundException("KHL stage $stageId not in tables_v2", league.id)
        if (stage.regular == null) throw ProviderException("KHL stage $stageId is a playoff bracket, not a table", leagueId = league.id)
        return KhlMapper.standings(stage, stageId.toString())
    }

    override suspend fun team(id: String): Team {
        val teams = get("$baseUrl/teams_v2.json?locale=en", ListSerializer(KhlTeamWrapper.serializer()), 24.hours)
        val t = teams.firstOrNull { it.team.id.toString() == id }
            ?: throw NotFoundException("KHL team '$id' not found", league.id)
        return KhlMapper.team(t.team)
    }

    private suspend fun detail(id: String): KhlEvent =
        get("$baseUrl/event_v2.json?id=$id&locale=en", KhlEventWrapper.serializer(), LIVE_MAX_AGE).event

    /** The API answers errors with HTTP 200 and `{"error": {...}}`; detect that before decoding. */
    private suspend fun <T> get(url: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T {
        val response = fetcher.get(url, maxAge = maxAge)
        if (response.status == 404) throw NotFoundException("KHL: $url → 404", league.id)
        response.requireSuccess()
        val body = response.body.trimStart()
        if (body.startsWith("{") && body.contains("\"error\"")) {
            val err = runCatching { OpenScoreJson.decodeFromString(KhlErrorResponse.serializer(), body).error }.getOrNull()
            if (err != null) {
                if (err.code == 404) throw NotFoundException("KHL: $url → ${err.message}", league.id)
                throw ProviderException("KHL: $url → error ${err.code}: ${err.message}", leagueId = league.id)
            }
        }
        return decodeJson(response, strategy, league.id)
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://khl.api.webcaster.pro/api/khl_mobile"
        public val LEAGUE: League = League("khl", Sport.HOCKEY, "KHL", "RU", TimeZone.of("Europe/Moscow"), "https://www.khl.ru")
        private val LIVE_MAX_AGE = 10.seconds
        private val TABLE_MAX_AGE = 10.minutes
        private val STATIC_MAX_AGE = 1.hours
    }
}
