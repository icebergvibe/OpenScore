package org.openscore.providers.sportality

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.openscore.cache.NoopSeasonScheduleStore
import org.openscore.cache.SeasonScheduleStore
import org.openscore.cache.SeasonSnapshot
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Period
import org.openscore.model.PeriodScore
import org.openscore.model.PeriodType
import org.openscore.model.Score
import org.openscore.model.Sport
import org.openscore.model.StageKind
import org.openscore.model.TeamRef
import org.openscore.net.Fetcher
import org.openscore.net.OpenScoreJson
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
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
        val now = clock.now()
        val onDate = season.games.values.filter { it.localDate() == date }.sortedWith(compareBy({ it.startTime }, { it.id }))
        val refreshed = coroutineScope {
            onDate.filter { it.needsRefresh(now) }
                .map { g -> async { runCatchingUnlessCancelled { fetchGame(g.id) }.getOrNull() } }
                .awaitAll().filterNotNull()
        }
        if (refreshed.isEmpty()) return onDate
        merge(refreshed)
        val byId = refreshed.associateBy { it.id }
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

    private fun decodeGamesArray(body: String): List<HaGame> {
        val marker = "\"games\":"
        val markerAt = body.indexOf(marker)
        if (markerAt < 0) throw ProviderException("${league.name} match page contained no games payload", leagueId = league.id)
        val start = body.indexOf('[', markerAt + marker.length)
        if (start < 0) throw ProviderException("${league.name} games payload was malformed", leagueId = league.id)
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
                    '[' -> depth++
                    ']' -> if (--depth == 0) {
                        val json = body.substring(start, index + 1)
                        return runCatching { OpenScoreJson.decodeFromString(ListSerializer(HaGame.serializer()), json) }
                            .getOrElse { throw ProviderException("${league.name} games payload could not be decoded", it, league.id) }
                    }
                }
            }
        }
        throw ProviderException("${league.name} games payload was truncated", leagueId = league.id)
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
            competition = gameType,
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
