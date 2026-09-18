package org.openscore.providers.mls

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
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
import kotlin.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * MLS through the public APIs used by mlssoccer.com. The stats host returns no-store,
 * so the shared fetcher never persists its bodies. The provider does not yet advertise
 * live updates: a live response still needs a committed replay sample.
 */
public class MlsProvider(
    private val fetcher: Fetcher,
    override val league: League = LEAGUE,
    private val statsUrl: String = DEFAULT_STATS_URL,
    private val metadataUrl: String = DEFAULT_METADATA_URL,
) : BaseLeagueProvider() {
    private val mapper = MlsMapper(league.id)

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE, Capability.GAME, Capability.EVENTS, Capability.LINEUPS,
        Capability.STANDINGS, Capability.TEAM, Capability.TEAM_SCHEDULE, Capability.ROSTER,
        Capability.CLOCK, Capability.CLOCK_RUNNING_FLAG, Capability.INTERMISSION_STATE,
    )

    private suspend fun currentSeason(): String =
        stats("/competitions/$COMPETITION_ID/seasons", MlsSeasons.serializer(), STATIC_MAX_AGE).seasons
            .maxByOrNull { it.season ?: Int.MIN_VALUE }?.season_id
            ?: throw NotFoundException("${league.name}: no seasons", league.id)

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val season = currentSeason()
        val schedule = try {
            stats("/matches/seasons/$season?competition_id=$COMPETITION_ID&match_date=$date&per_page=1000",
                MlsSchedule.serializer(), LIVE_MAX_AGE).schedule
        } catch (_: NotFoundException) {
            // Unlike its other list routes, MLS answers 404 instead of an empty schedule when
            // a valid season has no matches on the requested date.
            return emptyList()
        }
        // The schedule is already a complete scorecard, so no row is enriched from sportapi here:
        // that would turn one day read into 1 + number-of-games requests solely for crests.
        // Match metadata is the scheduled-detail fallback, where it costs one intentional
        // request. List screens use initials when this compact feed has no logo.
        return schedule.map { mapper.schedule(it, date) }.sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game {
        val match = try {
            stats("/matches/$id", MlsMatch.serializer(), LIVE_MAX_AGE)
        } catch (_: NotFoundException) {
            // MLS does not create the stats resource for an upcoming fixture. Its own page
            // reads sportapi metadata, which is sufficient for a scheduled game card.
            return mapper.scheduledMetadata(metadata("/api/matches/$id", MlsMetadataMatch.serializer(), STATIC_MAX_AGE))
        }
        return coroutineScope {
            val events = async { allEvents(id) }
            val matchStats = async { stats("/statistics/clubs/matches/$id", MlsMatchStats.serializer(), LIVE_MAX_AGE) }
            mapper.game(match, events.await(), matchStats.await())
        }
    }

    override suspend fun events(gameId: String): List<GameEvent> {
        val match = stats("/matches/$gameId", MlsMatch.serializer(), LIVE_MAX_AGE)
        return mapper.events(allEvents(gameId),
            mapper.team(match.home?.team_id, match.home?.team_name, match.home?.team_three_letter_code),
            mapper.team(match.away?.team_id, match.away?.team_name, match.away?.team_three_letter_code))
    }

    /** MLS defaults this route to 20 newest-first rows, which can omit the entire first half. */
    private suspend fun allEvents(gameId: String): MlsEvents {
        val events = mutableListOf<MlsEvent>()
        val seenTokens = mutableSetOf<String>()
        var token: String? = null
        do {
            val cursor = token?.let { "&page_token=${it.encodeURLParameter()}" }.orEmpty()
            val page = stats("/matches/$gameId/key_events?per_page=1000$cursor", MlsEvents.serializer(), LIVE_MAX_AGE)
            events += page.events
            token = page.next_page_token?.takeIf { it.isNotBlank() && seenTokens.add(it) }
        } while (token != null)
        return MlsEvents(events)
    }

    override suspend fun lineups(gameId: String): List<Lineup> =
        mapper.lineups(gameId, stats("/matches/$gameId", MlsMatch.serializer(), LIVE_MAX_AGE))

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId ?: currentSeason()
        return mapper.standings(stats("/competitions/$COMPETITION_ID/seasons/$season/standings", MlsStandings.serializer(), TABLE_MAX_AGE), season, league.name)
    }

    override suspend fun team(id: String): Team = mapper.team(stats("/clubs/$id", MlsClub.serializer(), STATIC_MAX_AGE))

    /**
     * The club's season across every competition the feed files it under (regular season,
     * playoffs, Leagues Cup, CONCACAF, friendlies), the way mlssoccer.com's club schedule reads
     * it (`match_date[gte]`/`[lte]` + `team_id`); `match_date` is a UTC date, like the day listing.
     */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val season = currentSeason()
        val rows = stats("/matches/seasons/$season?match_date%5Bgte%5D=$startDate&match_date%5Blte%5D=$endDate&team_id=$teamId&per_page=200&sort=match_date",
            MlsSchedule.serializer(), TABLE_MAX_AGE).schedule
        // "MLS Test" (MLS-COM-00002R) is an internal fixture set the club listing includes as if played.
        return rows.filter { it.match_scheduled != false && it.planned_kickoff_time != null && it.competition_name != "MLS Test" }
            .map { mapper.schedule(it, Instant.parse(it.planned_kickoff_time!!).toLocalDateTime(TimeZone.UTC).date) }
            .filter { it.scheduleDate!! in startDate..endDate }
            .sortedBy { it.startTime }
    }

    override suspend fun roster(teamId: String): List<Player> =
        mapper.roster(stats("/players/seasons/${currentSeason()}/clubs/$teamId?per_page=100", MlsRoster.serializer(), STATIC_MAX_AGE), teamId)

    private suspend fun <T> stats(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(statsUrl + path, strategy, maxAge, league.id)

    private suspend fun <T> metadata(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(metadataUrl + path, strategy, maxAge, league.id)

    public companion object {
        public const val DEFAULT_STATS_URL: String = "https://stats-api.mlssoccer.com"
        public const val DEFAULT_METADATA_URL: String = "https://sportapi.mlssoccer.com"
        public const val COMPETITION_ID: String = "MLS-COM-000001"
        public val LEAGUE: League = League("mls", Sport.FOOTBALL, "Major League Soccer", "US", "https://www.mlssoccer.com/")
        private val LIVE_MAX_AGE: Duration = 10.seconds
        private val TABLE_MAX_AGE: Duration = 5.minutes
        private val STATIC_MAX_AGE: Duration = 1.hours
    }
}
