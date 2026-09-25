package org.openscore.providers.sportality

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import org.openscore.cache.NoopSeasonScheduleStore
import org.openscore.cache.SeasonScheduleStore
import org.openscore.cache.SeasonSnapshot
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Player
import org.openscore.model.PlayerNames
import org.openscore.model.Sport
import org.openscore.model.StageKind
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.TeamSeasonStats
import org.openscore.model.TeamStat
import org.openscore.model.TeamStatGroup
import org.openscore.net.Fetcher
import org.openscore.net.OpenScoreJson
import org.openscore.net.OpenScoreRequestJson
import org.openscore.net.QueryFetcher
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.LivePollBudget
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import org.openscore.provider.getJson
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * HockeyAllsvenskan's current Next.js/Strapi site, introduced before the 2026–27 season.
 *
 * The site has no day listing. Its match page carries the whole season in one 1.5 MB
 * (127 KB gzipped) `no-store` response, and `/api/game?slug=` answers one ~700-byte document
 * per game. So the season is imported as a snapshot — kept in memory and, through
 * [scheduleStore], across processes — and every day is answered from it:
 *
 * - the page is read again when the snapshot is older than [SCHEDULE_MAX_AGE], for kick-off
 *   changes and for results the per-game path was never asked for;
 * - a game the snapshot cannot vouch for — due, under way, or over within [RESULT_WINDOW]
 *   without a result yet — is read from the game route at the live floor and merged back, a
 *   final record durably;
 * - a game the snapshot has as over, cancelled or postponed is never asked about again, and a
 *   season import never regresses such a game (the page can lag the game route);
 * - when the page cannot be read, the old snapshot is served rather than an error, and the
 *   page is not retried for [REFRESH_RETRY].
 *
 * Lineups come from the game's own page (`/games/{slug}/view`, ~20 KB gzipped, `no-store`),
 * whose server-rendered lineup component lists every dressed player with today's position,
 * line, number and letters, about two hours before the puck drop; read on demand and kept
 * [LINEUP_MAX_AGE] in the fetcher.
 *
 * Three of the site's routes answer only a `POST`, and three things depend on them: the squad
 * ([roster]), the stats the table has no column for ([teamStats]) and the live play-by-play
 * ([events]), and the table itself since 2026-09-25 ([standings]). They are reached through
 * [QueryFetcher], the named read-only exception; handed a plain [Fetcher] this provider simply
 * does not claim `ROSTER`, `LIVE_UPDATES`, `STANDINGS` or `TEAM_STATS`. `EVENTS` is claimed
 * either way, because the game page carries the same play-by-play document inline.
 */
public class HockeyAllsvenskanProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.System,
    private val scheduleStore: SeasonScheduleStore = NoopSeasonScheduleStore,
) : BaseLeagueProvider() {

    /** Set when the fetcher can ask a question behind a `POST`; null leaves those routes shut. */
    private val queryFetcher: QueryFetcher? = fetcher as? QueryFetcher

    override val league: League = LEAGUE
    private val mapper = HockeyAllsvenskanMapper(league)
    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.PERIOD_SCORES,
        Capability.LINEUPS,
        Capability.LINE_GROUPS,
        Capability.TEAM,
        Capability.TEAM_SCHEDULE,
        Capability.PLAYER,
        // The game page carries the play-by-play inline, so this one needs no POST.
        Capability.EVENTS,
    ) + if (queryFetcher != null) {
        // None of these has a GET route any more: the squad and play-by-play never had one, and
        // the table stopped having one when `/pages/tabell` went client-rendered on 2026-09-25.
        setOf(Capability.ROSTER, Capability.LIVE_UPDATES, Capability.STANDINGS, Capability.TEAM_STATS)
    } else {
        emptySet()
    }

    /** The season as last imported, games keyed by slug in schedule order. */
    private class Snapshot(val seasonId: String, val savedAt: Instant, val games: Map<String, Game>) {
        constructor(saved: SeasonSnapshot) : this(saved.seasonId, saved.savedAt, saved.games.associateBy { it.id })
        fun isStale(now: Instant): Boolean = now - savedAt >= SCHEDULE_MAX_AGE
    }

    /** Guards imports and merges; reads of [snapshot] outside it see the last completed one. */
    private val scheduleLock = Mutex()
    @Volatile private var snapshot: Snapshot? = null
    private var refreshFailedAt: Instant? = null

    /**
     * Game slug to StatNet game number, which the play-by-play route needs and no core model
     * field has room for. Every season import fills it for all 364 games, and every read of the
     * game route for that game; a durable snapshot restored from the store does not, so a cold
     * start's first [events] call for a game nobody has read yet takes the game page instead -
     * which answers and leaves the number behind for every call after it.
     */
    private val gameNumbers = HashMap<String, String>()

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val season = season()
        val onDate = season.games.values.filter { it.localDate() == date }.sortedWith(compareBy({ it.startTime }, { it.id }))
        val byId = refreshOpen(onDate)
        if (byId.isEmpty()) return onDate
        // A game route can move a game to another day; the day asked for keeps only what is still on it.
        return onDate.map { byId[it.id] ?: it }.filter { it.localDate() == date }
    }

    /**
     * The game document alone: period, score, period scores, no clock. Deliberately without the
     * play-by-play - a timeline costs a second read and a day listing must never pay for it.
     * [events] is the separate call, `Game.events` stays null here to say "not loaded", and
     * [live] is what puts the two together.
     */
    override suspend fun game(id: String): Game {
        val fetched = runCatchingUnlessCancelled { fetchGame(id) }
        fetched.getOrNull()?.let { game ->
            merge(listOf(game))
            return game
        }
        // A finished game's snapshot record is complete (score, period scores, decision), so it
        // stands in for the game route when that cannot be read. Anything still open must not.
        val held = scheduleLock.withLock { held()?.games?.get(id) }
        if (held != null && held.state.isTerminal) return held
        throw fetched.exceptionOrNull()!!
    }

    /**
     * The game document at the live floor, with the play-by-play beside it.
     *
     * The document has no clock and does not move on a penalty, a shot or a goalie change, so
     * the timeline cannot wait for it to change the way SHL's waits for its overview: it is read
     * whenever the document moved (a goal, a new period, the end) and otherwise every
     * [EVENTS_REFRESH]. Emitted with its events, so a caller never has to ask separately - and
     * before this, one that asked only when the score changed kept a penalty off the timeline
     * until the next goal.
     */
    override fun live(gameId: String, interval: Duration): Flow<Game> {
        if (Capability.LIVE_UPDATES !in capabilities) unsupported(Capability.LIVE_UPDATES)
        return flow {
            val budget = LivePollBudget(interval)
            var document: Game? = null
            var events: List<GameEvent>? = null
            var sinceEvents = Duration.INFINITE
            var emitted: Game? = null
            while (budget.open) {
                val read = budget.read { game(gameId) }.getOrNull()
                if (read != null) {
                    val moved = read != document
                    document = read
                    if (read.state.hasStarted && (moved || sinceEvents >= EVENTS_REFRESH)) {
                        // A failed read keeps the timeline already shown; the next tick asks again.
                        budget.read { mapper.events(playByPlay(read, LIVE_MAX_AGE), read) }.getOrNull()?.let {
                            events = it
                            sinceEvents = Duration.ZERO
                        }
                    }
                    val next = read.copy(events = events)
                    if (next != emitted) emit(next)
                    emitted = next
                    if (next.state.isTerminal) return@flow
                }
                budget.wait()
                sinceEvents += budget.interval
            }
        }
    }

    /**
     * The game's play-by-play: goals with both assists and the running score, penalties with the
     * infraction and its minutes, shots, goalie changes and timeouts.
     *
     * Two routes serve the same document. `POST /api/play-by-play` is 24 KB and is what the site
     * itself polls; the game page carries the whole thing inline as the play-by-play component's
     * `initialData`, at 235 KB. The POST needs the game's StatNet number, which is on the season
     * page and the game page and nowhere else - so the page answers the first time and the POST
     * every time after, which matters because this is also the live path.
     */
    override suspend fun events(gameId: String): List<GameEvent> {
        // `season()`, not the held snapshot: the import is what learns the game's StatNet number,
        // and without it every call would pay the 235 KB page instead of the 24 KB POST.
        val game = season().games[gameId] ?: game(gameId)
        return mapper.events(playByPlay(game, if (game.state.isTerminal) FINISHED_EVENTS_MAX_AGE else LIVE_MAX_AGE), game)
    }

    private suspend fun playByPlay(game: Game, maxAge: Duration): HaPlayByPlay {
        val number = scheduleLock.withLock { gameNumbers[game.id] }
        val query = queryFetcher
        if (number == null || query == null) return pagePlayByPlay(game.id)
        val response = query.query(
            "$baseUrl/api/play-by-play",
            body = OpenScoreRequestJson.encodeToString(HaPollPayload.serializer(), HaPollPayload(number, game.home.id, game.away.id, game.startTime.toString())),
            maxAge = maxAge,
        )
        // StatNet answers 404 for a game it has no sheet for yet, which is every game until the
        // opening face-off. That is a state, not a failure: there are no events yet.
        if (response.status == 404) return HaPlayByPlay()
        response.requireSuccess()
        return OpenScoreJson.decodeFromString(HaPlayByPlay.serializer(), response.body)
    }

    /** The page's copy, and the StatNet number with it so the cheap route can be used next time. */
    private suspend fun pagePlayByPlay(gameId: String): HaPlayByPlay {
        val body = gamePage(gameId)
        decodeGameNumber(gameId, body)
        // The component is absent until the game has something to show.
        if (!body.contains(PLAY_BY_PLAY_COMPONENT)) return HaPlayByPlay()
        return decodeObject(body, "\"initialData\":", HaPlayByPlay.serializer())
    }

    private suspend fun gamePage(gameId: String): String {
        val response = fetcher.get("$baseUrl/games/$gameId/view?_rsc=openscore", headers = mapOf("RSC" to "1"), maxAge = LINEUP_MAX_AGE)
        if (response.status == 404) throw NotFoundException("${league.name} game '$gameId' has no page", league.id)
        return response.requireSuccess().body
    }

    private suspend fun decodeGameNumber(gameId: String, body: String) {
        val number = GAME_NUMBER.find(body)?.groupValues?.get(1) ?: return
        scheduleLock.withLock { gameNumbers[gameId] = number }
    }

    /**
     * The dressed players of both sides from the game page, in the site's line structure:
     * goalies (the starter first), then one LINE per forward line and one PAIRING per defence
     * pair; officials are listed in the same arrays and left out. Empty until the club sheets
     * are published (about two hours before the game).
     */
    override suspend fun lineups(gameId: String): List<Lineup> {
        val game = scheduleLock.withLock { held()?.games?.get(gameId) } ?: game(gameId)
        val body = gamePage(gameId)
        // The page is the only route to the StatNet number; take it whenever it passes through.
        decodeGameNumber(gameId, body)
        // The page is rendered without the lineup component until the sheets exist.
        if (!body.contains("\"homeLineups\":")) return emptyList()
        val home = decodeArray(body, "\"homeLineups\":", ListSerializer(HaLineupEntry.serializer()))
        val away = decodeArray(body, "\"awayLineups\":", ListSerializer(HaLineupEntry.serializer()))
        return listOfNotNull(mapper.lineup(gameId, game.home, home), mapper.lineup(gameId, game.away, away))
    }

    /**
     * The league table, from `POST /api/league-standings-all-time` ([standingsRows]): the
     * running season when [seasonId] is left out, any past one by its id. The rows give only
     * the display code (`ÖIK`), so ids, names and crests are joined from the season snapshot,
     * and a club the snapshot has no game for keeps the display code. Special teams come from
     * the team leaderboard, which takes no season, so only the running season has them.
     */
    override suspend fun standings(seasonId: String?): StandingsTable {
        val current = season().seasonId
        val year = seasonYear(seasonId ?: current)
            ?: throw NotFoundException("${league.name} cannot read a season out of '${seasonId ?: current}'", league.id)
        val asked = seasonId ?: current
        val rows = standingsRows(year)
        if (rows.isEmpty()) throw ProviderException("${league.name} table was empty", leagueId = league.id)
        val clubs = clubsByLabel()
        // Special teams only for the running season: the leaderboard route takes no season.
        val special = if (asked == current) specialTeams() else emptyMap()
        return StandingsTable(
            leagueId = league.id,
            seasonId = asked,
            stage = StageKind.REGULAR,
            groups = listOf(StandingsGroup(league.name, rows.mapIndexed { i, row -> mapper.standingsRow(row, i, clubs, special) })),
            grouping = "league",
        )
    }

    /**
     * Power play and penalty kill for all fourteen clubs, keyed by StatNet id. Two calls for
     * the league, shared by every club page, and never allowed to fail the table.
     */
    private suspend fun specialTeams(): Map<String, HaSpecialTeams> {
        val query = queryFetcher ?: return emptyMap()
        val (pp, pk) = coroutineScope {
            val a = async { runCatchingUnlessCancelled { teamMetricRows(query, "PPPerc") }.getOrNull().orEmpty() }
            val b = async { runCatchingUnlessCancelled { teamMetricRows(query, "PKPerc") }.getOrNull().orEmpty() }
            a.await() to b.await()
        }
        return buildMap {
            (pp.keys + pk.keys).forEach { put(it, HaSpecialTeams(pp[it], pk[it])) }
        }
    }

    /**
     * Identity and home rink, both already in the season snapshot: every game row on the season
     * page carries its two clubs' names, crests and arenas, keyed by the StatNet id. So a club
     * page costs nothing beyond the schedule the feed has read anyway.
     */
    override suspend fun team(id: String): Team {
        val games = season().games.values
        val atHome = games.filter { it.home.id.equals(id, ignoreCase = true) }
        val ref = atHome.firstOrNull()?.home
            ?: games.firstOrNull { it.away.id.equals(id, ignoreCase = true) }?.away
            ?: throw NotFoundException("${league.name} team '$id' is not in this season", league.id)
        // Where the club plays most, so a one-off game in a borrowed arena is not taken for home.
        val arena = atHome.mapNotNull { it.venue }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        return Team(ref = ref, arena = arena, country = COUNTRY)
    }

    /** The club's games from the snapshot, with the ones in play re-read as [gamesOn] does. */
    override suspend fun teamSchedule(teamId: String, startDate: LocalDate, endDate: LocalDate): List<Game> {
        val theirs = season().games.values
            .filter { it.home.id.equals(teamId, ignoreCase = true) || it.away.id.equals(teamId, ignoreCase = true) }
            .filter { it.localDate() in startDate..endDate }
            .sortedWith(compareBy({ it.startTime }, { it.id }))
        val byId = refreshOpen(theirs)
        if (byId.isEmpty()) return theirs
        return theirs.map { byId[it.id] ?: it }.filter { it.localDate() in startDate..endDate }
    }

    /**
     * What the table says about one club - record, goals, special teams - and, when the fetcher
     * can ask behind a `POST`, the two columns the table has no room for plus the club's
     * leading scorer and goaltender.
     *
     * The team leaderboard is one call for all 14 clubs, so the league shares it; the player
     * leaderboard is one call per club per metric, which is why only two are asked for.
     */
    override suspend fun teamStats(teamId: String, seasonId: String): TeamSeasonStats {
        val year = seasonYear(seasonId)
            ?: throw NotFoundException("${league.name} cannot read a season out of '$seasonId'", league.id)
        val clubs = clubsByLabel()
        // The row is keyed by the CMS spelling, so the club's own is what finds it. A club this
        // season's snapshot does not know - a past season's relegated side - has the table's
        // display code as its id ([standings]), so that is what finds its row.
        val label = clubs.entries.firstOrNull { it.value.id.equals(teamId, ignoreCase = true) }?.key
        val rows = standingsRows(year)
        val row = rows.firstOrNull { label != null && (it.statNetClubLabel ?: it.teamCode)?.lowercase() == label }
            ?: rows.firstOrNull { label == null && it.teamCode.equals(teamId, ignoreCase = true) }
            ?: throw NotFoundException("${league.name} team '$teamId' is not in the table", league.id)
        val id = label?.let { clubs[it]?.id } ?: teamId
        // The row's own two percentage columns are empty now, so special teams come from the
        // same leaderboard the table uses, and the rest of the club's numbers from the row. Like
        // every leaderboard here it takes no season, so a past season is its row alone - as in
        // [standings], rather than last year's record beside this year's power play.
        val running = year == seasonYear(season().seasonId)
        val special = if (running) specialTeams()[id.lowercase()] else null
        val extra = if (running) queryFetcher?.let { leaderboardStats(it, id) }.orEmpty() else emptyList()
        val groups = listOf(
            TeamStatGroup("record", "Record", listOfNotNull(
                mapper.stat("gamesPlayed", "GP", row.gamesPlayed),
                mapper.stat("wins", "W", row.wins),
                mapper.stat("losses", "L", row.losses),
                mapper.stat("overtimeWins", "OTW", row.overtimeWins),
                mapper.stat("overtimeLosses", "OTL", row.overtimeLosses),
                mapper.stat("shootoutWins", "SOW", row.shootoutWins),
                mapper.stat("shootoutLosses", "SOL", row.shootoutLosses),
                mapper.stat("points", "PTS", row.totalPoints),
            )),
            TeamStatGroup("goals", "Goals", listOfNotNull(
                mapper.stat("goalsFor", "GF", row.goals),
                mapper.stat("goalsAgainst", "GA", row.goalsAgainst),
                mapper.stat("goalDifference", "Diff", row.goalDifference),
            )),
            TeamStatGroup("specialTeams", "Special teams", listOfNotNull(
                mapper.stat("powerPlay", "PP%", row.powerPlayPerc?.takeIf { it.isNotBlank() } ?: special?.powerPlay),
                mapper.stat("penaltyKill", "PK%", row.penaltyKillPerc?.takeIf { it.isNotBlank() } ?: special?.penaltyKill),
            )),
        ).plus(extra).filter { it.stats.isNotEmpty() }
        return TeamSeasonStats(league.id, id, seasonId, groups)
    }

    /**
     * The leaderboard-only numbers, asked for together and never allowed to fail the screen: a
     * club page that shows its record and table position is worth more than an error because
     * one of four supplementary calls did not answer.
     */
    private suspend fun leaderboardStats(query: QueryFetcher, teamId: String): List<TeamStatGroup> = coroutineScope {
        val shots = async { runCatchingUnlessCancelled { teamMetric(query, "SOG", teamId) }.getOrNull() }
        val saves = async { runCatchingUnlessCancelled { teamMetric(query, "SVSPerc", teamId) }.getOrNull() }
        val scorer = async { runCatchingUnlessCancelled { leader(query, teamId, "TP", goalkeeper = false) }.getOrNull() }
        val goalie = async { runCatchingUnlessCancelled { leader(query, teamId, "SVSPerc", goalkeeper = true) }.getOrNull() }
        listOf(
            TeamStatGroup("shooting", "Shooting", listOfNotNull(
                mapper.stat("shotsOnGoal", "SOG", shots.await()),
                mapper.stat("savePercentage", "SV%", saves.await()),
            )),
            TeamStatGroup("leaders", "Leaders", listOfNotNull(
                scorer.await()?.let { TeamStat("pointsLeader", "Points", it) },
                goalie.await()?.let { TeamStat("savePercentageLeader", "SV%", it) },
            )),
        )
    }

    /** One column of the 14-club team leaderboard. The whole table comes back, so it is cached for the league. */
    private suspend fun teamMetric(query: QueryFetcher, metric: String, teamId: String): String? =
        teamMetricRows(query, metric)[teamId.lowercase()]

    /** That column as StatNet id to value, which is how both callers want it. */
    private suspend fun teamMetricRows(query: QueryFetcher, metric: String): Map<String, String> {
        val response = query.query(
            "$baseUrl/api/team-leaderboard",
            body = OpenScoreRequestJson.encodeToString(HaTeamLeaderRequest.serializer(), HaTeamLeaderRequest(metric = metric)),
            maxAge = TABLE_MAX_AGE,
        ).requireSuccess()
        return OpenScoreJson.decodeFromString(HaTeamLeaderboard.serializer(), response.body)
            .teams.mapNotNull { row -> row.teamID?.lowercase()?.let { id -> row.metricValue?.let { id to it } } }
            .toMap()
    }

    /** The club's top player by one metric, as `name value`; rank 1 is the first row. */
    private suspend fun leader(query: QueryFetcher, teamId: String, metric: String, goalkeeper: Boolean): String? {
        val response = query.query(
            "$baseUrl/api/player-leaderboard",
            body = OpenScoreRequestJson.encodeToString(
                HaPlayerLeaderRequest.serializer(),
                HaPlayerLeaderRequest(team = teamId, metric = metric, isGoalkeeper = goalkeeper),
            ),
            maxAge = TABLE_MAX_AGE,
        ).requireSuccess()
        val top = OpenScoreJson.decodeFromString(HaPlayerLeaderboard.serializer(), response.body)
            .players.minByOrNull { it.rank ?: Int.MAX_VALUE } ?: return null
        val name = top.player?.takeIf { it.isNotBlank() } ?: PlayerNames.fromParts(top.firstName, top.familyName)
        val value = top.metricValue?.takeIf { it.isNotBlank() } ?: return null
        return if (name.isBlank()) value else "$name $value"
    }

    /**
     * A club's whole squad, the one thing no `GET` on this site reaches.
     *
     * The route is keyed by the **CMS short name** (`MoDo`, `ÖIK`, `MORA`, `NVIF`), not the
     * StatNet id (`MODO`, `OSIK`, `MIK`, `NYB`) the core calls a club by, and the wrong one
     * answers `200` with an empty list rather than an error - so the join has to be right and
     * nothing will say when it is not. The season snapshot carries both for all fourteen clubs
     * (`TeamRef.abbreviation` is the CMS spelling), so the translation costs no request and
     * does not depend on the table page.
     */
    override suspend fun roster(teamId: String): List<Player> {
        val query = queryFetcher ?: unsupported(Capability.ROSTER)
        val ref = season().games.values.firstNotNullOfOrNull { game ->
            listOf(game.home, game.away).firstOrNull { it.id.equals(teamId, ignoreCase = true) }
        } ?: throw NotFoundException("${league.name} team '$teamId' is not in this season", league.id)
        val label = ref.abbreviation?.takeIf { it.isNotBlank() }
            ?: throw ProviderException("${league.name} has no CMS spelling for '$teamId'", leagueId = league.id)
        val response = query.query(
            "$baseUrl/api/all-players",
            body = OpenScoreRequestJson.encodeToString(HaSquadRequest.serializer(), HaSquadRequest(teamShortName = label)),
            maxAge = ROSTER_MAX_AGE,
        ).requireSuccess()
        val squad = OpenScoreJson.decodeFromString(HaSquadResponse.serializer(), response.body)
        // The wrong spelling is a 200 with no players, so an empty squad is not "a small club":
        // it is the one symptom the join being wrong ever produces.
        if (squad.players.isEmpty()) {
            throw ProviderException("${league.name} squad for '$teamId' (asked as '$label') was empty", leagueId = league.id)
        }
        return squad.players.map { mapper.squadPlayer(it, ref.id) }
    }

    /**
     * One player's profile page, keyed by its slug (`patrik-zackrisson`). A lineup entry carries
     * that slug beside the StatNet id it is keyed by; the two id spaces are the site's, not ours.
     *
     * An unknown slug is answered with the site's not-found page under a `200`, so it is the
     * profile component's presence that decides, never the status.
     */
    override suspend fun player(id: String): Player {
        val response = fetcher.get("$baseUrl/players/$id?_rsc=openscore", headers = mapOf("RSC" to "1"), maxAge = PLAYER_MAX_AGE)
        if (response.status == 404) throw NotFoundException("${league.name} player '$id' has no page", league.id)
        response.requireSuccess()
        if (!response.body.contains(PLAYER_COMPONENT)) {
            throw NotFoundException("${league.name} player '$id' has no profile page", league.id)
        }
        val props = decodeObject(response.body, PLAYER_COMPONENT, HaPlayerProps.serializer())
        val data = props.playerData
            ?: throw ProviderException("${league.name} profile page for '$id' carried no player", leagueId = league.id)
        return mapper.player(data, id, props.careerStats?.playerInfo)
    }

    /** Re-reads the games the snapshot cannot vouch for, merges them, and returns them by id. */
    private suspend fun refreshOpen(games: List<Game>): Map<String, Game> {
        val now = clock.now()
        val refreshed = coroutineScope {
            games.filter { it.needsRefresh(now) }
                .map { g -> async { runCatchingUnlessCancelled { fetchGame(g.id) }.getOrNull() } }
                .awaitAll().filterNotNull()
        }
        if (refreshed.isEmpty()) return emptyMap()
        merge(refreshed)
        return refreshed.associateBy { it.id }
    }

    /**
     * The table, which stopped being a `GET` on 2026-09-25: `/pages/tabell` used to
     * server-render a `stats.league-standings` component carrying all fourteen rows, and now
     * renders a shell that fetches them. The rows come from the route that shell calls.
     *
     * Two things the old component had and this does not. It gives `teamCode` (`NVIF`, `ÖIK`,
     * `MORA`), the display spelling, where the old row also carried the StatNet id (`NYB`,
     * `OSIK`, `MIK`) the core keys clubs by - so the season snapshot does that join, as it does
     * for the squad. And its `power_play_perc` / `penalty_kill_perc` are empty strings in every
     * season observed, current or finished, so those two come from the team leaderboard.
     *
     * What it gained: a season that is not the running one. The old page ignored every
     * parameter it was given; this answers 2025 with the finished table.
     */
    private suspend fun standingsRows(season: Int): List<HaStandingsRow> {
        val query = queryFetcher ?: unsupported(Capability.STANDINGS)
        val response = query.query(
            "$baseUrl/api/league-standings-all-time",
            body = OpenScoreRequestJson.encodeToString(HaStandingsRequest.serializer(), HaStandingsRequest(season = season)),
            maxAge = TABLE_MAX_AGE,
        ).requireSuccess()
        return OpenScoreJson.decodeFromString(HaStandingsResponse.serializer(), response.body).standings
    }

    /** `season_2026` to 2026, which is what the standings route wants. */
    private fun seasonYear(seasonId: String?): Int? = seasonId?.substringAfterLast('_')?.toIntOrNull()

    /**
     * The clubs of the running season, keyed by the CMS short name the standings and squad
     * routes both spell them with. This is the only thing that knows `ÖIK` is `OSIK`.
     */
    private suspend fun clubsByLabel(): Map<String, TeamRef> = buildMap {
        season().games.values.forEach { game ->
            listOf(game.home, game.away).forEach { ref ->
                ref.abbreviation?.takeIf { it.isNotBlank() }?.let { putIfAbsent(it.lowercase(), ref) }
            }
        }
    }

    private suspend fun fetchGame(id: String): Game {
        val response = fetcher.getJson(
            "$baseUrl/api/game?slug=$id",
            HaGamesResponse.serializer(),
            LIVE_MAX_AGE,
            league.id,
        )
        val row = response.data.firstOrNull()
            ?: throw NotFoundException("${league.name} game '$id' not found", league.id)
        // The document carries the play-by-play route's key too (this season's games at least),
        // so after a cold start from the durable snapshot the first timeline is the 24 KB POST
        // rather than the 235 KB game page. Never called under [scheduleLock], which is not reentrant.
        row.statNetGameNumber?.let { number -> scheduleLock.withLock { gameNumbers[row.slug] = number } }
        return mapper.game(row)
    }

    /**
     * Still open, and the snapshot may already be behind: kicked off (or due) but not yet marked
     * over. Bounded to [RESULT_WINDOW] after kick-off: anything older is the page's to correct on
     * its next import, so a game the site never closes does not cost a request on every visit.
     */
    private fun Game.needsRefresh(now: Instant): Boolean =
        !state.isTerminal && startTime <= now && startTime > now - RESULT_WINDOW

    private fun Game.localDate(): LocalDate = scheduleDate ?: startTime.toLocalDateTime(league.zone).date

    /** The snapshot, imported or re-imported from the page when there is none or it is too old. */
    private suspend fun season(): Snapshot {
        snapshot?.takeIf { !it.isStale(clock.now()) }?.let { return it }
        return scheduleLock.withLock {
            val now = clock.now()
            val held = held()
            if (held != null && !held.isStale(now)) return@withLock held
            val failedAt = refreshFailedAt
            if (held != null && failedAt != null && now - failedAt < REFRESH_RETRY) return@withLock held
            val imported = runCatchingUnlessCancelled { importSeason(held, now) }
            imported.getOrNull()?.let { fresh ->
                // Memory first: a store that cannot be written must not cost another 1.5 MB read.
                snapshot = fresh
                refreshFailedAt = null
                scheduleStore.save(league.id, SeasonSnapshot(fresh.seasonId, fresh.savedAt, fresh.games.values.toList()))
                return@withLock fresh
            }
            // Offline, or the page changed shape: the old schedule beats an empty day.
            if (held == null) throw imported.exceptionOrNull()!!
            refreshFailedAt = now
            held
        }
    }

    /** What is in memory or, failing that, durably stored; never the network. Call under [scheduleLock]. */
    private suspend fun held(): Snapshot? =
        snapshot ?: scheduleStore.load(league.id)?.let(::Snapshot)?.also { snapshot = it }

    private suspend fun importSeason(previous: Snapshot?, now: Instant): Snapshot {
        val response = fetcher.get(
            "$baseUrl/pages/matcher?_rsc=openscore",
            headers = mapOf("RSC" to "1"),
            maxAge = SCHEDULE_MAX_AGE,
        ).requireSuccess()
        val rows = decodeGamesArray(response.body)
        // Every row carries the play-by-play route's game key. Taking them here is the whole
        // reason `events()` usually costs 24 KB rather than the 235 KB game page.
        rows.forEach { row -> row.statNetGameNumber?.let { gameNumbers[row.slug] = it } }
        val games = rows.map(mapper::game)
        if (games.isEmpty()) throw ProviderException("${league.name} season schedule was empty", leagueId = league.id)
        val seasonId = games.mapNotNull(Game::seasonId).distinct().singleOrNull()
            ?: throw ProviderException("${league.name} schedule did not contain exactly one season", leagueId = league.id)
        // The game route answered "over" already; the page must not reopen it.
        val settled = previous?.takeIf { it.seasonId == seasonId }?.games.orEmpty()
        return Snapshot(seasonId, now, games.associateBy { it.id }.mapValues { (id, game) ->
            settled[id]?.takeIf { it.state.isTerminal && !game.state.isTerminal } ?: game
        })
    }

    /** Folds fresh game documents into the snapshot; the ones that are over are also written through. */
    private suspend fun merge(games: List<Game>) {
        val season = scheduleLock.withLock {
            val current = held() ?: return
            snapshot = Snapshot(current.seasonId, current.savedAt, current.games + games.associateBy { it.id })
            current.seasonId
        }
        val settled = games.filter { it.state.isTerminal && it.seasonId == season }
        if (settled.isNotEmpty()) scheduleStore.update(league.id, season, settled)
    }

    private fun decodeGamesArray(body: String): List<HaGame> = decodeArray(body, "\"games\":", ListSerializer(HaGame.serializer()))

    /** The JSON array that follows [marker] in a React Server Components payload. */
    private fun <T> decodeArray(body: String, marker: String, strategy: DeserializationStrategy<T>): T =
        decodeValue(body, marker, '[', ']', strategy)

    /** The JSON object that follows [marker], which for a component is its props. */
    private fun <T> decodeObject(body: String, marker: String, strategy: DeserializationStrategy<T>): T =
        decodeValue(body, marker, '{', '}', strategy)

    /** The JSON value that follows [marker] in a React Server Components payload, by bracket matching. */
    private fun <T> decodeValue(body: String, marker: String, open: Char, close: Char, strategy: DeserializationStrategy<T>): T {
        val markerAt = body.indexOf(marker)
        if (markerAt < 0) throw ProviderException("${league.name} page contained no $marker payload", leagueId = league.id)
        val start = body.indexOf(open, markerAt + marker.length)
        if (start < 0) throw ProviderException("${league.name} $marker payload was malformed", leagueId = league.id)
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until body.length) {
            val char = body[index]
            if (inString) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') inString = false
            } else {
                when (char) {
                    '"' -> inString = true
                    open -> depth++
                    close -> if (--depth == 0) {
                        val json = body.substring(start, index + 1)
                        return runCatching { OpenScoreJson.decodeFromString(strategy, json) }
                            .getOrElse { throw ProviderException("${league.name} $marker payload could not be decoded", it, league.id) }
                    }
                }
            }
        }
        throw ProviderException("${league.name} $marker payload was truncated", leagueId = league.id)
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://hockeyallsvenskan.se"
        public val LEAGUE: League = League("hockeyallsvenskan", Sport.HOCKEY, "HockeyAllsvenskan", "SE", TimeZone.of("Europe/Stockholm"), DEFAULT_BASE_URL)
        private val LIVE_MAX_AGE = 10.seconds
        /** The 1.5 MB season page: kick-off changes and late results can wait this long. */
        private val SCHEDULE_MAX_AGE = 6.hours
        /** After kick-off, the game route (not the page) is what says a game is over. */
        private val RESULT_WINDOW = 24.hours
        /** With a snapshot to serve, a page that could not be read is not asked again sooner. */
        private val REFRESH_RETRY = 5.minutes
        /** Sheets are published about two hours before the game and rarely change after. */
        private val LINEUP_MAX_AGE = 10.minutes
        /** A table only moves as games finish. */
        private val TABLE_MAX_AGE = 5.minutes
        /** A profile is its bio plus season totals: an hour old is still a profile. */
        private val PLAYER_MAX_AGE = 1.hours
        /** A squad moves at the deadline and on call-ups, so a day is generous. */
        private val ROSTER_MAX_AGE = 24.hours
        /** A finished game's play-by-play is finished too; only a live one needs the live floor. */
        private val FINISHED_EVENTS_MAX_AGE = 1.hours
        /**
         * How long [live] lets the timeline go unread while the game document stands still: a
         * penalty shows within this. The site itself asks every 5 s; 24 KB every tick would be
         * ~9 MB an hour a game, and a goal or a period change is read at once regardless.
         */
        private val EVENTS_REFRESH = 30.seconds
        /** The trailing numbers are the CMS block's instance id, so the name is matched by prefix. */
        private const val PLAYER_COMPONENT = "\"team.player-profile"
        private const val PLAY_BY_PLAY_COMPONENT = "\"games.play-by-play"
        /** The play-by-play route's game key where the game page carries it. */
        private val GAME_NUMBER = Regex("\"statNetGameNumber\":\"([0-9]+)\"")
        private const val COUNTRY = "SE"
    }
}
