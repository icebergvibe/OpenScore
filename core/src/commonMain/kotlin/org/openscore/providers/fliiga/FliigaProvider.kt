package org.openscore.providers.fliiga

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.Sport
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.net.Fetcher
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.Dates
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import org.openscore.provider.getJson
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * F-Liiga Men, Finland's top floorball division, from fliiga.com - see
 * apis/floorball/f-liiga/README.md.
 *
 * The site is WordPress over TorneoPal and publishes three things without a key: the
 * `ottelut` records, which hold the season's fixtures and the id bridge in their ACF meta;
 * `admin-ajax.php?action=match_live_data`, the compact card the site itself polls every 15 s;
 * and `action=match_teams`, the whole match - lineups, lines, events with coordinates and
 * period scores - in one 15 KB (gzipped) document.
 *
 * So a day listing costs two small JSON reads for the season plus one card per match that
 * should be under way, and a match screen costs one document. The heavy route is never the
 * score poll.
 *
 * Ids: a game is its TorneoPal match id (`929774`), a team its stable TorneoPal **club** id
 * (`393`) rather than the season-team id the match documents carry, and a player their
 * TorneoPal player id. `seasonId` is the `2026-2027` form, which is also what every
 * scoreboard action takes as its `season` parameter. Dates are Helsinki calendar dates.
 *
 * Not claimed: the game-centre WebSocket at `wss://fliiga.api.jj-net.com/ws/matches/{id}` has
 * not been captured in play, and the `Live`, `Break` and `Penalties` statuses this maps come
 * from the site's own script rather than from a recorded response. Until a live capture,
 * there is no `LIVE_UPDATES`, `CLOCK` or `INTERMISSION_STATE`.
 */
public class FliigaProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.System,
) : BaseLeagueProvider() {

    override val league: League = LEAGUE

    private val mapper = FliigaMapper(league.id)

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.TEAM_SCHEDULE,
        Capability.ROSTER,
        Capability.PLAYER,
        Capability.PERIOD_SCORES,
        Capability.EVENT_COORDINATES,
        Capability.LINE_GROUPS,
    )

    // ---- games -------------------------------------------------------------------------------

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val season = season()
        val rows = season.matches.filter { it.startsOn(date) }
        if (rows.isEmpty()) return emptyList()
        val index = teamIndex(season.id)
        val now = clock.now()
        return resolve(rows.mapNotNull { mapper.game(it, index, now) })
    }

    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val season = season()
        val index = teamIndex(season.id)
        val team = index.byId(teamId) ?: throw NotFoundException("F-Liiga team '$teamId' not in this season", league.id)
        // Without a WordPress id there is nothing to match the records on; a null one would match every null.
        val wpId = team.wpId ?: throw NotFoundException("F-Liiga team '$teamId' has no record in the table", league.id)
        val rows = season.matches.filter { m ->
            (m.meta.homeWpId == wpId || m.meta.awayWpId == wpId) && m.startDate()?.let { it in startDate..endDate } == true
        }
        val now = clock.now()
        return resolve(rows.mapNotNull { mapper.game(it, index, now) })
    }

    /** The whole match document: result, period scores, lineups and the event list. */
    override suspend fun game(id: String): Game {
        val m = match(id)
        val game = mapper.game(m, teamIndex(m.season?.name))
        return game.copy(events = mapper.events(m, game.home, game.away))
    }

    override suspend fun events(gameId: String): List<GameEvent> {
        val m = match(gameId)
        val game = mapper.game(m, teamIndex(m.season?.name))
        return mapper.events(m, game.home, game.away)
    }

    override suspend fun lineups(gameId: String): List<Lineup> {
        val m = match(gameId)
        val game = mapper.game(m, teamIndex(m.season?.name))
        return mapper.lineups(m, game.home, game.away)
    }

    /**
     * A schedule row whose result the WordPress record has not been given yet is read from the
     * compact card, the only route with a state. Records are written after the final whistle,
     * and sometimes only hours later, so a match inside [RESULT_WINDOW] of its start is always
     * asked about even when the record looks complete.
     */
    private suspend fun resolve(games: List<Game>): List<Game> {
        val now = clock.now()
        return coroutineScope {
            games.map { g ->
                async {
                    val age = now - g.startTime
                    val ask = when {
                        age.isNegative() -> false
                        // Under way, or over recently enough that the record may not have caught up.
                        age < RESULT_WINDOW -> true
                        // A record that never got a result: worth one more look for a day, then left alone.
                        else -> g.state == GameState.SCHEDULED && age < STALE_WINDOW
                    }
                    if (!ask) g else runCatchingUnlessCancelled { mapper.merge(g, summary(g.id)) }.getOrDefault(g)
                }
            }.awaitAll()
        }.sortedBy { it.startTime }
    }

    // ---- standings / teams / players ------------------------------------------------------------

    override suspend fun standings(seasonId: String?): StandingsTable {
        val season = seasonId ?: season().id
        val rows = table(season)
        if (rows.isEmpty()) throw NotFoundException("F-Liiga: no table for $season", league.id)
        return mapper.standings(rows, season)
    }

    override suspend fun team(id: String): Team {
        val index = teamIndex(null)
        return mapper.team(index.byId(id) ?: throw NotFoundException("F-Liiga team '$id' not found", league.id))
    }

    /**
     * The squad as the season's leaderboards list it, outfield players and goalkeepers
     * together - including the members who have not played yet. The boards name the team but
     * carry no team id, so they are matched on the display name the table uses for it.
     */
    override suspend fun roster(teamId: String): List<Player> {
        val season = season().id
        val index = teamIndex(season)
        val team = index.byId(teamId) ?: throw NotFoundException("F-Liiga team '$teamId' not found", league.id)
        return squad(season).filter { it.teamName.equals(team.name, ignoreCase = true) }.map { mapper.player(it, index) }
    }

    override suspend fun player(id: String): Player {
        val season = season().id
        val row = squad(season).firstOrNull { it.id == id } ?: throw NotFoundException("F-Liiga player '$id' not found", league.id)
        return mapper.player(row, teamIndex(season))
    }

    // ---- reads -------------------------------------------------------------------------------

    /** The current men's season: its `2026-2027` id and every fixture the records hold for it. */
    public suspend fun season(): FlSeasonSchedule {
        val rows = mutableListOf<FlWpMatch>()
        for (page in 1..MAX_PAGES) {
            // The records are created a season at a time, so ordering by id puts the newest first.
            val batch = wpMatches(page)
            val men = batch.filter { it.meta.series.equals(SERIES, ignoreCase = true) }
            // A page past the current season's block has nothing left to add.
            if (men.isEmpty() && rows.isNotEmpty()) break
            rows += men
            if (batch.size < PAGE_SIZE) break
        }
        if (rows.isEmpty()) throw ProviderException("F-Liiga: no $SERIES matches in the match records", leagueId = league.id)
        // Several seasons can be listed at once around a changeover; the one today belongs to wins.
        val now = clock.now()
        val season = rows
            .mapNotNull { m -> m.startInstant()?.let { m.meta.season to (it - now).absoluteValue } }
            .minByOrNull { it.second }?.first
            ?: rows.first().meta.season
            ?: throw ProviderException("F-Liiga: match records carry no season", leagueId = league.id)
        return FlSeasonSchedule(season, rows.filter { it.meta.season == season })
    }

    private suspend fun wpMatches(page: Int): List<FlWpMatch> = get(
        "/wp-json/wp/v2/ottelut?per_page=$PAGE_SIZE&page=$page&orderby=id&order=desc&_fields=$MATCH_FIELDS",
        ListSerializer(FlWpMatch.serializer()), SCHEDULE_MAX_AGE,
    )

    /** The table joined to the WordPress team records: club ids, crests and the season-team bridge. */
    private suspend fun teamIndex(seasonId: String?): FlTeamIndex {
        val season = seasonId ?: season().id
        val rows = table(season)
        val wpIds = rows.mapNotNull { it.wpId }.distinct()
        // Without the bridge a match document's season-team ids fall back to the display name.
        val wp = if (wpIds.isEmpty()) emptyList() else runCatchingUnlessCancelled { wpTeams(wpIds) }.getOrElse { emptyList() }
        return FlTeamIndex.from(rows, wp)
    }

    private suspend fun table(season: String): List<FlStandingsRow> =
        get(action("fliiga_scoreboard_standings", season), ListSerializer(FlStandingsRow.serializer()), TABLE_MAX_AGE)

    private suspend fun wpTeams(ids: List<Int>): List<FlWpTeam> = get(
        "/wp-json/wp/v2/joukkueet?include=${ids.joinToString(",")}&per_page=${ids.size}&orderby=include&_fields=$TEAM_FIELDS",
        ListSerializer(FlWpTeam.serializer()), STATIC_MAX_AGE,
    )

    /** Both leaderboards: the points board lists outfield players, the other goalkeepers. */
    private suspend fun squad(season: String): List<FlPlayerRow> = coroutineScope {
        val skaters = async { leaderboard("fliiga_scoreboard_points", season) }
        val goalies = async { leaderboard("fliiga_scoreboard_goalkeepers", season) }
        skaters.await() + goalies.await()
    }

    private suspend fun leaderboard(name: String, season: String): List<FlPlayerRow> =
        get(action(name, season), ListSerializer(FlPlayerRow.serializer()), TABLE_MAX_AGE)

    private suspend fun summary(id: String): FlSummary {
        val s = get("/wp-admin/admin-ajax.php?action=match_live_data&lang=en&match_id=$id", FlSummary.serializer(), LIVE_MAX_AGE)
        if (s.id.isBlank()) throw NotFoundException("F-Liiga match '$id' not found", league.id)
        return s
    }

    private suspend fun match(id: String): FlMatch {
        val m = get("/wp-admin/admin-ajax.php?action=match_teams&match_id=$id", FlMatch.serializer(), LIVE_MAX_AGE)
        if (m.id.isBlank()) throw NotFoundException("F-Liiga match '$id' not found", league.id)
        return m
    }

    /** Every scoreboard action takes the same four parameters; `phase` is empty for the regular season. */
    private fun action(name: String, season: String): String =
        "/wp-admin/admin-ajax.php?action=$name&season=$season&phase=&series=$SERIES_PARAM&lang=en"

    /** The AJAX routes answer `Content-Type: text/html` with a JSON body, which [getJson] tolerates. */
    private suspend fun <T> get(path: String, strategy: DeserializationStrategy<T>, maxAge: Duration): T =
        fetcher.getJson(baseUrl + path, strategy, maxAge, league.id)

    private fun FlWpMatch.startInstant(): Instant? = Dates.instantOrNull(meta.startTime)

    private fun FlWpMatch.startDate(): LocalDate? = startInstant()?.toLocalDateTime(HELSINKI)?.date

    private fun FlWpMatch.startsOn(date: LocalDate): Boolean = startDate() == date

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://fliiga.com"

        public val LEAGUE: League = League(
            id = "f-liiga",
            sport = Sport.FLOORBALL,
            name = "F-Liiga",
            country = "FI",
            websiteUrl = "https://fliiga.com",
        )

        /** `_sarja` in the match records; `series` in the scoreboard actions. */
        private const val SERIES = "Miehet"
        private const val SERIES_PARAM = "miehet"

        /** Only the id bridge is asked for: the records' SEO meta is several times its size. */
        public const val MATCH_FIELDS: String =
            "id,slug,modified,meta._torneopal_id,meta._ottelu_aika,meta._kotijoukkue_torneopal_id,meta._kotijoukkue_wp_id," +
                "meta._vierasjoukkue_torneopal_id,meta._vierasjoukkue_wp_id,meta._kotimaalit,meta._vierasmaalit," +
                "meta._kausi,meta._sarja,meta._ryhma_nimi,meta._kierros_nimi,meta._yleisomaara"

        public const val TEAM_FIELDS: String =
            "id,slug,link,title,meta._torneopal_id,meta._liittyva_seura_torneopal_id,meta._liittyva_seura_wp_id"

        public const val PAGE_SIZE: Int = 100
        /** Two pages hold a twelve-team season; the third is the guard against an unexpected order. */
        private const val MAX_PAGES = 4

        private val HELSINKI = TimeZone.of("Europe/Helsinki")
        /** How long after its start a match is always asked about rather than read off the record. */
        private val RESULT_WINDOW = 3.hours
        /** How long a match whose record never got a result is still worth asking about. */
        private val STALE_WINDOW = 24.hours
        private val LIVE_MAX_AGE = 15.seconds
        private val TABLE_MAX_AGE = 5.minutes
        private val SCHEDULE_MAX_AGE = 1.hours
        private val STATIC_MAX_AGE = 24.hours
    }
}

/** The current season's id and the match records that belong to it. */
public data class FlSeasonSchedule(val id: String, val matches: List<FlWpMatch>)
