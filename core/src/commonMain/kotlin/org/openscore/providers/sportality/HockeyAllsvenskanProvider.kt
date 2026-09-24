package org.openscore.providers.sportality

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.openscore.cache.NoopSeasonScheduleStore
import org.openscore.cache.SeasonScheduleStore
import org.openscore.cache.SeasonSnapshot
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.LineupGroup
import org.openscore.model.LineupGroupKind
import org.openscore.model.Period
import org.openscore.model.PeriodScore
import org.openscore.model.PeriodType
import org.openscore.model.Player
import org.openscore.model.PlayerNames
import org.openscore.model.PlayerRef
import org.openscore.model.Score
import org.openscore.model.Sport
import org.openscore.model.StageKind
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.TeamSeasonStats
import org.openscore.model.TeamStat
import org.openscore.model.TeamStatGroup
import org.openscore.net.Fetcher
import org.openscore.net.OpenScoreJson
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.Dates
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import org.openscore.provider.getJson
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.concurrent.Volatile
import kotlin.time.Clock
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
 * [LINEUP_MAX_AGE] in the fetcher. Play-by-play is a POST route and stays unmapped.
 */
public class HockeyAllsvenskanProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.System,
    private val scheduleStore: SeasonScheduleStore = NoopSeasonScheduleStore,
) : BaseLeagueProvider() {

    override val league: League = LEAGUE
    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.PERIOD_SCORES,
        Capability.LINEUPS,
        Capability.LINE_GROUPS,
        Capability.STANDINGS,
        Capability.TEAM,
        Capability.TEAM_SCHEDULE,
        Capability.TEAM_STATS,
        Capability.PLAYER,
    )

    /** The season as last imported, games keyed by slug in schedule order. */
    private class Snapshot(val seasonId: String, val savedAt: Instant, val games: Map<String, Game>) {
        constructor(saved: SeasonSnapshot) : this(saved.seasonId, saved.savedAt, saved.games.associateBy { it.id })
        fun isStale(now: Instant): Boolean = now - savedAt >= SCHEDULE_MAX_AGE
    }

    /** Guards imports and merges; reads of [snapshot] outside it see the last completed one. */
    private val scheduleLock = Mutex()
    @Volatile private var snapshot: Snapshot? = null
    private var refreshFailedAt: Instant? = null

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val season = season()
        val onDate = season.games.values.filter { it.localDate() == date }.sortedWith(compareBy({ it.startTime }, { it.id }))
        val byId = refreshOpen(onDate)
        if (byId.isEmpty()) return onDate
        // A game route can move a game to another day; the day asked for keeps only what is still on it.
        return onDate.map { byId[it.id] ?: it }.filter { it.localDate() == date }
    }

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
     * The dressed players of both sides from the game page, in the site's line structure:
     * goalies (the starter first), then one LINE per forward line and one PAIRING per defence
     * pair; officials are listed in the same arrays and left out. Empty until the club sheets
     * are published (about two hours before the game).
     */
    override suspend fun lineups(gameId: String): List<Lineup> {
        val game = scheduleLock.withLock { held()?.games?.get(gameId) } ?: game(gameId)
        val response = fetcher.get("$baseUrl/games/$gameId/view?_rsc=openscore", headers = mapOf("RSC" to "1"), maxAge = LINEUP_MAX_AGE)
        if (response.status == 404) throw NotFoundException("${league.name} game '$gameId' has no page", league.id)
        response.requireSuccess()
        // The page is rendered without the lineup component until the sheets exist.
        if (!response.body.contains("\"homeLineups\":")) return emptyList()
        val home = decodeArray(response.body, "\"homeLineups\":", ListSerializer(HaLineupEntry.serializer()))
        val away = decodeArray(response.body, "\"awayLineups\":", ListSerializer(HaLineupEntry.serializer()))
        return listOfNotNull(lineup(gameId, game.home, home), lineup(gameId, game.away, away))
    }

    private fun lineup(gameId: String, team: TeamRef, entries: List<HaLineupEntry>): Lineup? {
        val dressed = entries.filter { !it.isReferee && !it.isLinePerson && it.positionToday != null }
        if (dressed.isEmpty()) return null
        fun ref(e: HaLineupEntry): PlayerRef = PlayerRef(
            leagueId = league.id,
            id = e.playerStatNetId ?: e.player?.statNetId ?: e.documentId ?: "?",
            name = PlayerNames.fromParts(e.firstName, e.familyName, fallback = e.playerStatNetId ?: "?"),
            jerseyNumber = e.jerseyToday?.toIntOrNull() ?: e.player?.jerseyNumber?.toIntOrNull(),
            position = e.positionToday,
            headshotUrl = e.player?.headshots?.small ?: e.player?.headshots?.medium,
        )
        val groups = ArrayList<LineupGroup>()
        val goalies = dressed.filter { it.positionToday == "GK" }.sortedWith(compareBy({ !it.isStarting }, { it.line?.toIntOrNull() ?: 99 }))
        if (goalies.isNotEmpty()) groups += LineupGroup(LineupGroupKind.GOALIES, "Goalies", goalies.map(::ref))
        val skaters = dressed.filter { it.positionToday != "GK" }
        for (line in skaters.mapNotNull { it.line?.toIntOrNull() }.distinct().sorted()) {
            val onLine = skaters.filter { it.line?.toIntOrNull() == line }
            val forwards = onLine.filter { it.positionToday in FORWARD_POSITIONS }.sortedBy { FORWARD_POSITIONS.indexOf(it.positionToday) }
            val defence = onLine.filter { it.positionToday in DEFENCE_POSITIONS }.sortedBy { DEFENCE_POSITIONS.indexOf(it.positionToday) }
            if (forwards.isNotEmpty()) groups += LineupGroup(LineupGroupKind.LINE, "Line $line", forwards.map(::ref))
            if (defence.isNotEmpty()) groups += LineupGroup(LineupGroupKind.PAIRING, "Pairing $line", defence.map(::ref))
        }
        val rest = skaters.filter { it.line?.toIntOrNull() == null || it.positionToday !in FORWARD_POSITIONS + DEFENCE_POSITIONS }
        if (rest.isNotEmpty()) groups += LineupGroup(LineupGroupKind.OTHER, "Other", rest.map(::ref))
        return Lineup(gameId, team, groups)
    }

    /**
     * The league table, from the table page's standings component.
     *
     * The page serves the current regular season only: it accepts `season`, `phase` and
     * `location` parameters and ignores them (its own pickers POST elsewhere), so [seasonId]
     * may name the running season or be left out. The rows carry every club's name, crest and
     * colours with them, so the table is one 15 KB read and never touches the season snapshot.
     */
    override suspend fun standings(seasonId: String?): StandingsTable {
        val props = tableProps()
        val season = props.seasonIdFor(seasonId)
        val rows = props.standings.mapIndexed { index, row -> row.toStandingsRow(index, props.teamDisplayMap) }
        if (rows.isEmpty()) throw ProviderException("${league.name} table was empty", leagueId = league.id)
        return StandingsTable(
            leagueId = league.id,
            seasonId = season,
            stage = StageKind.REGULAR,
            groups = listOf(StandingsGroup(league.name, rows)),
            grouping = "league",
        )
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
     * What the table says about one club: its record, its goals and its two special-team
     * percentages. Everything past those is behind one of the site's POST routes.
     */
    override suspend fun teamStats(teamId: String, seasonId: String): TeamSeasonStats {
        val props = tableProps()
        val season = props.seasonIdFor(seasonId)
        val row = props.standings.firstOrNull { it.teamId.equals(teamId, ignoreCase = true) }
            ?: throw NotFoundException("${league.name} team '$teamId' is not in the table", league.id)
        val groups = listOf(
            TeamStatGroup("record", "Record", listOfNotNull(
                stat("gamesPlayed", "GP", row.gamesPlayed),
                stat("wins", "W", row.wins),
                stat("losses", "L", row.losses),
                stat("overtimeWins", "OTW", row.overtimeWins),
                stat("overtimeLosses", "OTL", row.overtimeLosses),
                stat("shootoutWins", "SOW", row.shootoutWins),
                stat("shootoutLosses", "SOL", row.shootoutLosses),
                stat("points", "PTS", row.totalPoints),
            )),
            TeamStatGroup("goals", "Goals", listOfNotNull(
                stat("goalsFor", "GF", row.goals),
                stat("goalsAgainst", "GA", row.goalsAgainst),
                stat("goalDifference", "Diff", row.goalDifference),
            )),
            TeamStatGroup("specialTeams", "Special teams", listOfNotNull(
                stat("powerPlay", "PP%", row.powerPlayPerc),
                stat("penaltyKill", "PK%", row.penaltyKillPerc),
            )),
        ).filter { it.stats.isNotEmpty() }
        return TeamSeasonStats(league.id, row.teamId ?: teamId, season ?: seasonId, groups)
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
        return data.toPlayer(id, props.careerStats?.playerInfo)
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

    private suspend fun tableProps(): HaStandingsProps {
        val response = fetcher.get(
            "$baseUrl/pages/tabell?_rsc=openscore",
            headers = mapOf("RSC" to "1"),
            maxAge = TABLE_MAX_AGE,
        ).requireSuccess()
        return decodeObject(response.body, STANDINGS_COMPONENT, HaStandingsProps.serializer())
    }

    /** The season the page just served, refusing an ask for any other: no other is reachable. */
    private fun HaStandingsProps.seasonIdFor(asked: String?): String? {
        val season = appliedSeason?.let { "season_$it" }
        if (asked != null && asked != season && asked != appliedSeason?.toString()) {
            throw NotFoundException(
                "${league.name} serves only the current table (${season ?: "season unknown"}); the page ignores a season parameter",
                league.id,
            )
        }
        return season
    }

    /** Wins are all wins, as the other hockey tables report them; the 3-2-1-0 split is in `extra`. */
    private fun HaStandingsRow.toStandingsRow(index: Int, display: Map<String, HaDisplayTeam>): StandingsRow {
        val id = teamId ?: teamCode ?: "?"
        val shown = display[teamCode] ?: display[teamId]
        val regulationWins = wins.orZero()
        val otWins = overtimeWins.orZero()
        val soWins = shootoutWins.orZero()
        val otLosses = overtimeLosses.orZero()
        val soLosses = shootoutLosses.orZero()
        return StandingsRow(
            team = TeamRef(
                leagueId = league.id,
                id = id,
                name = shown?.name ?: statNetClubLabel ?: id,
                // The CMS short name, which is also how the site's squad route spells this club.
                abbreviation = shown?.shortName ?: statNetClubLabel ?: teamCode,
                logoUrl = shown?.logo,
            ),
            rank = rank?.toIntOrNull() ?: (index + 1),
            played = gamesPlayed.orZero(),
            wins = regulationWins + otWins + soWins,
            losses = losses.orZero(),
            otherLosses = otLosses + soLosses,
            points = totalPoints.orZero(),
            goalsFor = goals?.toIntOrNull(),
            goalsAgainst = goalsAgainst?.toIntOrNull(),
            goalDifference = goalDifference?.toIntOrNull(),
            extra = buildMap {
                put("regulationWins", regulationWins.toString())
                put("overtimeWins", otWins.toString())
                put("shootoutWins", soWins.toString())
                put("overtimeLosses", otLosses.toString())
                put("shootoutLosses", soLosses.toString())
                powerPlayPerc?.takeIf { it.isNotBlank() }?.let { put("powerPlay", it) }
                penaltyKillPerc?.takeIf { it.isNotBlank() }?.let { put("penaltyKill", it) }
            },
        )
    }

    private fun HaPlayerData.toPlayer(asked: String, info: HaPlayerInfo?): Player = Player(
        ref = PlayerRef(
            leagueId = league.id,
            id = slug ?: asked,
            name = PlayerNames.fromParts(firstName, familyName, fallback = asked),
            jerseyNumber = jerseyNumber?.toIntOrNull(),
            position = positionCode ?: info?.position,
            headshotUrl = headshots?.small ?: headshots?.medium,
        ),
        firstName = firstName,
        lastName = familyName,
        birthDate = Dates.localDateOrNull(birthDate ?: info?.birthdate),
        nationality = country ?: info?.nationality,
        heightCm = height?.toIntOrNull() ?: info?.height,
        weightKg = weight?.toIntOrNull() ?: info?.weight,
        handedness = shoots,
        teamId = teamStatNetId,
    )

    private fun stat(key: String, label: String, value: String?): TeamStat? =
        value?.takeIf { it.isNotBlank() }?.let { TeamStat(key, label, it) }

    private suspend fun fetchGame(id: String): Game {
        val response = fetcher.getJson(
            "$baseUrl/api/game?slug=$id",
            HaGamesResponse.serializer(),
            LIVE_MAX_AGE,
            league.id,
        )
        return response.data.firstOrNull()?.toGame()
            ?: throw NotFoundException("${league.name} game '$id' not found", league.id)
    }

    /**
     * Still open, and the snapshot may already be behind: kicked off (or due) but not yet marked
     * over. Bounded to [RESULT_WINDOW] after kick-off: anything older is the page's to correct on
     * its next import, so a game the site never closes does not cost a request on every visit.
     */
    private fun Game.needsRefresh(now: Instant): Boolean =
        !state.isTerminal && startTime <= now && startTime > now - RESULT_WINDOW

    private fun Game.localDate(): LocalDate = scheduleDate ?: startTime.toLocalDateTime(SWEDEN).date

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
        val games = decodeGamesArray(response.body).map { it.toGame() }
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

    private fun HaGame.toGame(): Game {
        val start = Instant.parse(scheduledDateTime)
        val completed = isCompleted == true || endedDateTime != null
        val started = playedDateTime != null || currentPeriod != null
        val homeTotal = homeScore?.toIntOrNull()
        val awayTotal = awayScore?.toIntOrNull()
        return Game(
            leagueId = league.id,
            id = slug,
            seasonId = season,
            stage = if (playOffGame != null || playOffGameLevel != null) StageKind.PLAYOFF else StageKind.REGULAR,
            // `HA` is the regular season, every game this season; only another type is worth a label.
            competition = gameType?.takeUnless { it == "HA" },
            startTime = start,
            scheduleDate = start.toLocalDateTime(SWEDEN).date,
            venue = venue ?: homeTeam?.teamArena,
            home = homeTeam.toRef(homeStatNetId),
            away = awayTeam.toRef(awayStatNetId),
            state = when {
                completed -> GameState.FINAL
                started -> GameState.LIVE
                else -> GameState.SCHEDULED
            },
            score = if (homeTotal != null && awayTotal != null) Score(homeTotal, awayTotal) else null,
            periodScores = periodScores(),
            ending = if (completed) when (decidedIn?.uppercase()) {
                "OT", "OVERTIME" -> GameEnding.OVERTIME
                "SO", "SHOOTOUT" -> GameEnding.SHOOTOUT
                else -> GameEnding.REGULATION
            } else null,
            rawState = when {
                completed -> decidedIn ?: "completed"
                started -> currentPeriod ?: "started"
                else -> "scheduled"
            },
        )
    }

    private fun HaGame.periodScores(): List<PeriodScore> = buildList {
        addScore(1, PeriodType.REGULATION, "1", homeScoreP1, awayScoreP1)
        addScore(2, PeriodType.REGULATION, "2", homeScoreP2, awayScoreP2)
        addScore(3, PeriodType.REGULATION, "3", homeScoreP3, awayScoreP3)
        addScore(4, PeriodType.OVERTIME, "OT", homeOtScore, awayOtScore)
        addScore(5, PeriodType.SHOOTOUT, "SO", homeSoScore, awaySoScore)
    }

    private fun MutableList<PeriodScore>.addScore(number: Int, type: PeriodType, label: String, home: String?, away: String?) {
        val h = home?.toIntOrNull()
        val a = away?.toIntOrNull()
        if (h != null && a != null) add(PeriodScore(Period(number, type, label), h, a))
    }

    private fun HaTeam?.toRef(fallbackId: String): TeamRef = TeamRef(
        leagueId = league.id,
        id = fallbackId,
        name = this?.name ?: fallbackId,
        abbreviation = this?.shortName ?: fallbackId,
        logoUrl = this?.logo?.url,
    )

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://hockeyallsvenskan.se"
        public val LEAGUE: League = League("hockeyallsvenskan", Sport.HOCKEY, "HockeyAllsvenskan", "SE", DEFAULT_BASE_URL)
        private val SWEDEN = TimeZone.of("Europe/Stockholm")
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
        /** The trailing numbers are the CMS block's instance id, so the name is matched by prefix. */
        private const val STANDINGS_COMPONENT = "\"stats.league-standings"
        private const val PLAYER_COMPONENT = "\"team.player-profile"
        private const val COUNTRY = "SE"
        private val FORWARD_POSITIONS = listOf("LW", "CE", "RW")
        private val DEFENCE_POSITIONS = listOf("LD", "RD")
    }
}

@Serializable
private data class HaGamesResponse(val data: List<HaGame> = emptyList())

@Serializable
private data class HaGame(
    val season: String? = null,
    val slug: String,
    val scheduledDateTime: String,
    val venue: String? = null,
    val currentPeriod: String? = null,
    val homeScore: String? = null,
    val awayScore: String? = null,
    val endedDateTime: String? = null,
    val decidedIn: String? = null,
    val homeScoreP1: String? = null,
    val awayScoreP1: String? = null,
    val homeScoreP2: String? = null,
    val awayScoreP2: String? = null,
    val homeScoreP3: String? = null,
    val awayScoreP3: String? = null,
    val homeOtScore: String? = null,
    val awayOtScore: String? = null,
    val homeSoScore: String? = null,
    val awaySoScore: String? = null,
    val gameType: String? = null,
    val playOffGame: String? = null,
    val playOffGameLevel: String? = null,
    val playedDateTime: String? = null,
    val isCompleted: Boolean? = null,
    val homeStatNetId: String,
    val awayStatNetId: String,
    val homeTeam: HaTeam? = null,
    val awayTeam: HaTeam? = null,
)

@Serializable
private data class HaTeam(
    val name: String,
    val shortName: String? = null,
    val teamArena: String? = null,
    val logo: HaLogo? = null,
)

@Serializable private data class HaLogo(val url: String? = null)

/** One row of the game page's lineup component: a dressed player, or an official (`isReferee` / `isLinePerson`). */
@Serializable
private data class HaLineupEntry(
    val documentId: String? = null,
    val firstName: String? = null,
    val familyName: String? = null,
    /** GK | LD | RD | LW | CE | RW; null for officials. */
    val positionToday: String? = null,
    /** Forward line or defence pair, `"1"`-`"4"`; the goalies' order. */
    val line: String? = null,
    val jerseyToday: String? = null,
    val isCaptain: Boolean = false,
    val isAssistant: Boolean = false,
    /** The starting goalie. */
    val isStarting: Boolean = false,
    val playerStatNetId: String? = null,
    val isExtraPlayer: Boolean = false,
    val isReferee: Boolean = false,
    val isLinePerson: Boolean = false,
    /** The player's profile, when the CMS has one (null for a few call-ups). */
    val player: HaLineupPlayer? = null,
)

@Serializable
private data class HaLineupPlayer(
    val statNetId: String? = null,
    val jerseyNumber: String? = null,
    val positionCode: String? = null,
    val slug: String? = null,
    val headshots: HaHeadshots? = null,
)

@Serializable
private data class HaHeadshots(val small: String? = null, val medium: String? = null, val large: String? = null)

/** The table page's standings component (`stats.league-standings-<block>-<instance>`). */
@Serializable
private data class HaStandingsProps(
    val standings: List<HaStandingsRow> = emptyList(),
    /** Keyed by *both* club spellings, so a lookup by either resolves. */
    val teamDisplayMap: Map<String, HaDisplayTeam> = emptyMap(),
    val appliedSeason: Int? = null,
)

/** One table row. Every value is a string, the ranks and the percentages included. */
@Serializable
private data class HaStandingsRow(
    val rank: String? = null,
    /** Display spelling (`ÖIK`, `MORA`): the key into `teamDisplayMap`, never a join key. */
    val teamCode: String? = null,
    /** StatNet id (`OSIK`, `MIK`): what the games, and so the core, call this team. */
    val teamId: String? = null,
    @SerialName("games_played") val gamesPlayed: String? = null,
    /** Regulation wins; a win past regulation is in the overtime or shoot-out column. */
    val wins: String? = null,
    val losses: String? = null,
    @SerialName("overtime_wins") val overtimeWins: String? = null,
    @SerialName("overtime_losses") val overtimeLosses: String? = null,
    @SerialName("shootouts_wins") val shootoutWins: String? = null,
    @SerialName("shootouts_losses") val shootoutLosses: String? = null,
    val goals: String? = null,
    @SerialName("goals_against") val goalsAgainst: String? = null,
    @SerialName("goal_difference") val goalDifference: String? = null,
    @SerialName("total_points") val totalPoints: String? = null,
    @SerialName("power_play_perc") val powerPlayPerc: String? = null,
    @SerialName("penalty_kill_perc") val penaltyKillPerc: String? = null,
    /** The CMS short name (`MoDo`), which is how the site's squad route spells this club. */
    val statNetClubLabel: String? = null,
)

@Serializable
private data class HaDisplayTeam(
    val logo: String? = null,
    val shortName: String? = null,
    val name: String? = null,
)

/** The player page's profile component (`team.player-profile-<block>-<instance>`). */
@Serializable
private data class HaPlayerProps(
    val playerData: HaPlayerData? = null,
    val careerStats: HaCareerStats? = null,
)

@Serializable
private data class HaPlayerData(
    val firstName: String? = null,
    val familyName: String? = null,
    val slug: String? = null,
    val statNetId: String? = null,
    /** The club's StatNet id, so a profile says which side the player is on. */
    val teamStatNetId: String? = null,
    val jerseyNumber: String? = null,
    val positionCode: String? = null,
    val birthDate: String? = null,
    val country: String? = null,
    val height: String? = null,
    val weight: String? = null,
    val shoots: String? = null,
    val headshots: HaHeadshots? = null,
)

/** Only the identity half of the career block is mapped; the stat lines are not in the model. */
@Serializable
private data class HaCareerStats(@SerialName("player_info") val playerInfo: HaPlayerInfo? = null)

@Serializable
private data class HaPlayerInfo(
    @SerialName("Birthdate") val birthdate: String? = null,
    @SerialName("Nationality") val nationality: String? = null,
    @SerialName("Height") val height: Int? = null,
    @SerialName("Weight") val weight: Int? = null,
    @SerialName("Position") val position: String? = null,
)

/** The table's counting columns are strings, and a missing one means none. */
private fun String?.orZero(): Int = this?.toIntOrNull() ?: 0
