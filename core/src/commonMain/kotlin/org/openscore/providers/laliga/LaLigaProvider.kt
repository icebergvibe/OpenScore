package org.openscore.providers.laliga

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
import org.openscore.net.HttpException
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.Dates
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The two public page-embedded subscription keys (docs/principles.md exception). The
 * documented values are tried first; a `401` means LaLiga rotated them, and [discover]
 * reads the current pair from laliga.com. The page is 800 KB, so it is not read up front.
 */
public data class LaLigaKeys(val publicService: String, val webview: String) {
    public companion object {
        public val DOCUMENTED: LaLigaKeys = LaLigaKeys("c13c3a8e2f6b46da9c5c425cf61fab3e", "ee7fcd5c543f4485ba2a48856fc7ece9")
        private val BACKEND = Regex("\"backendSubscription\":\"([0-9a-f]{32})\"")
        private val WEBVIEW = Regex("\"webviewSubscription\":\"([0-9a-f]{32})\"")

        /** Reads `__NEXT_DATA__.runtimeConfig` from the public page; falls back to [DOCUMENTED]. */
        public suspend fun discover(fetcher: Fetcher, pageUrl: String = "https://www.laliga.com/en-GB"): LaLigaKeys {
            val page = runCatchingUnlessCancelled { fetcher.get(pageUrl, maxAge = 24.hours) }.getOrNull() ?: return DOCUMENTED
            if (!page.isSuccess) return DOCUMENTED
            val backend = BACKEND.find(page.body)?.groupValues?.get(1) ?: return DOCUMENTED
            val webview = WEBVIEW.find(page.body)?.groupValues?.get(1) ?: return DOCUMENTED
            return LaLigaKeys(backend, webview)
        }
    }
}

/**
 * LaLiga via `apim.laliga.com` — see apis/football/la-liga/README.md.
 *
 * Ids: matches are the public-service **slug** (the webview endpoints that need the int
 * id / Opta id are resolved through the match header), teams are team slugs, players
 * player slugs. `seasonId` is the subscription slug (`laliga-easports-2026`). Dates are
 * Spanish local dates. Edge caching caps polling at 30 s.
 */
public class LaLigaProvider(
    private val fetcher: Fetcher,
    private val keys: LaLigaKeys? = null,
    private val competitionSlug: String = PRIMERA_DIVISION,
    override val league: League = LEAGUE,
    private val publicUrl: String = DEFAULT_PUBLIC_URL,
    private val webviewUrl: String = DEFAULT_WEBVIEW_URL,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    private val mapper = LaLigaMapper(league.id)
    private var resolvedKeys: LaLigaKeys = keys ?: LaLigaKeys.DOCUMENTED

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.ROSTER,
        Capability.TEAM_SCHEDULE,
        Capability.PLAYER,
        Capability.LIVE_UPDATES,
        Capability.CLOCK,
        Capability.CLOCK_RUNNING_FLAG,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
    )

    /**
     * Runs [call] with the current keys; on a `401` reads the page for fresh ones and tries
     * once more. The page is cached for a day by the fetcher, so a 401 that is not a rotation
     * (the same keys on the page) costs nothing after the first look.
     */
    private suspend fun <T> withKeys(call: suspend (LaLigaKeys) -> T): T =
        try {
            call(resolvedKeys)
        } catch (e: HttpException) {
            if (e.status != 401) throw e
            val fresh = LaLigaKeys.discover(fetcher)
            if (fresh == resolvedKeys) throw e
            resolvedKeys = fresh
            call(fresh)
        }

    private suspend fun subscription(): LlSubscription =
        public("/api/v1/subscriptions?competitionSlug=$competitionSlug", LlSubscriptions.serializer(), STATIC_MAX_AGE).subscriptions.firstOrNull()
            ?: throw NotFoundException("${league.name}: no subscriptions for $competitionSlug", league.id)

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        // The subscription and the calendar are independent; only the week listing needs both.
        val (sub, cal) = coroutineScope {
            val sub = async { subscription() }
            val cal = async { public("/api/v1/calendar?startDate=$date&endDate=$date&competitionSlug=$competitionSlug", LlCalendar.serializer(), STATIC_MAX_AGE) }
            sub.await() to cal.await()
        }
        val weeks = cal.calendars.flatMap { it.calendar_gameweeks }.filter { it.has_games && (it.competition?.slug == competitionSlug || it.competition == null) }.mapNotNull { it.gameweek?.week }.distinct()
        val listed = weeks.flatMap { week ->
            public("/api/v1/matches?subscriptionSlug=${sub.slug}&week=$week&limit=100&orderField=date&orderType=asc", LlMatches.serializer(), LIVE_MAX_AGE).matches
        }.filter { m -> m.date?.let { Dates.instant(it).toLocalDateTime(MADRID).date } == date }
        // The week listing says only `FirstHalf`/`SecondHalf` while a match runs (no `match_time`,
        // no `period_started`; checked 2026-09-13 during Celta–Málaga). The 2.6 KB match resource
        // has the second-precision period timestamps, so live entries are read from it instead.
        val now = clock.now()
        return coroutineScope {
            listed.map { m ->
                async {
                    val slug = m.slug
                    val source = if (slug != null && mapper.gameState(m.status).isLive) runCatchingUnlessCancelled { header(slug) }.getOrDefault(m) else m
                    mapper.game(source, now, seasonId = sub.slug)
                }
            }.awaitAll()
        }.sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game {
        val header = header(id)
        val state = mapper.gameState(header.status)
        if (!state.hasStarted) {
            // Event and Opta-stat resources are empty before kick-off. The webview header
            // already carries the complete scheduled scorecard and usually its season.
            val season = header.subscription?.slug ?: subscription().slug
            return mapper.game(header, clock.now(), LlEvents(), stats = null, seasonId = season)
        }
        return coroutineScope {
            val events = async { webview("/api/web/matches/${header.id}/events", LlEvents.serializer(), LIVE_MAX_AGE) }
            val stats = async { header.opta_id?.let { webview("/api/web/matches/opta/$it/stats", LlMatchStats.serializer(), STATS_MAX_AGE) } }
            val season = async { header.subscription?.slug ?: subscription().slug }
            mapper.game(header, clock.now(), events.await(), stats.await(), seasonId = season.await())
        }
    }

    override suspend fun events(gameId: String): List<GameEvent> {
        val header = header(gameId)
        val events = webview("/api/web/matches/${header.id}/events", LlEvents.serializer(), LIVE_MAX_AGE)
        return mapper.events(events, header, mapper.teamRef(header.home_team), mapper.teamRef(header.away_team))
    }

    override suspend fun lineups(gameId: String): List<Lineup> {
        val header = header(gameId)
        val lineups = webview("/api/web/matches/${header.id}/lineups", LlLineups.serializer(), LINEUP_MAX_AGE)
        return mapper.lineups(gameId, lineups, header, mapper.teamRef(header.home_team), mapper.teamRef(header.away_team))
    }

    override suspend fun standings(seasonId: String?): StandingsTable {
        val slug = seasonId ?: subscription().slug
        return mapper.standings(public("/api/v1/subscriptions/$slug/standing", LlStandings.serializer(), TABLE_MAX_AGE), slug, league.name)
    }

    override suspend fun team(id: String): Team =
        mapper.team(public("/api/v1/teams/$id", LlTeamWrapper.serializer(), STATIC_MAX_AGE).team)

    /** `teamSlug=` narrows the season's fixtures to one club — 38 rows in one call; live rows keep the listing's coarse state. */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val sub = subscription()
        val now = clock.now()
        return public("/api/v1/matches?subscriptionSlug=${sub.slug}&teamSlug=$teamId&limit=100&orderField=date&orderType=asc", LlMatches.serializer(), SCHEDULE_MAX_AGE).matches
            .map { mapper.game(it, now, seasonId = sub.slug) }
            .filter { it.startTime.toLocalDateTime(MADRID).date in startDate..endDate }
            .sortedBy { it.startTime }
    }

    override suspend fun roster(teamId: String): List<Player> {
        val year = subscription().year ?: clock.now().toLocalDateTime(MADRID).year
        val squad = public("/api/v1/teams/$teamId/squad?seasonYear=$year&limit=50", LlSquads.serializer(), STATIC_MAX_AGE)
        return squad.squads.mapNotNull { row -> row.person?.let { mapper.player(it, row.shirt_number, row.position?.name, teamId) } }
    }

    override suspend fun player(id: String): Player {
        val p = public("/api/v1/players/$id", LlPlayerWrapper.serializer(), STATIC_MAX_AGE).player
        return mapper.player(p, p.squad?.shirt_number, p.squad?.position?.name, p.team?.slug)
    }

    private suspend fun header(slug: String): LlMatch =
        webview("/api/web/matches/$slug", LlMatchWrapper.serializer(), LIVE_MAX_AGE).match

    private suspend fun <T> public(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T = withKeys { keys ->
        fetcher.getJson(publicUrl + path, strategy, maxAge, league.id, headers = mapOf("Ocp-Apim-Subscription-Key" to keys.publicService, "Content-Language" to "en"))
    }

    private suspend fun <T> webview(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T = withKeys { keys ->
        fetcher.getJson(webviewUrl + path, strategy, maxAge, league.id, headers = mapOf("Ocp-Apim-Subscription-Key" to keys.webview, "Content-Language" to "en"))
    }

    public companion object {
        public const val DEFAULT_PUBLIC_URL: String = "https://apim.laliga.com/public-service"
        public const val DEFAULT_WEBVIEW_URL: String = "https://apim.laliga.com/webview"
        public const val PRIMERA_DIVISION: String = "primera-division"
        public val LEAGUE: League = League("la-liga", Sport.FOOTBALL, "LaLiga", "ES", "https://www.laliga.com")
        private val MADRID = TimeZone.of("Europe/Madrid")
        private val LIVE_MAX_AGE = 30.seconds
        private val STATS_MAX_AGE = 3.minutes
        private val LINEUP_MAX_AGE = 5.minutes
        private val TABLE_MAX_AGE = 1.minutes
        private val SCHEDULE_MAX_AGE = 2.minutes
        private val STATIC_MAX_AGE = 1.hours
    }
}
