package org.openscore.providers.fogis

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Sport
import org.openscore.net.Fetcher
import org.openscore.net.SimpleXml
import org.openscore.net.XmlNode
import org.openscore.net.utf8Length
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every SvFF competition (Allsvenskan → Div 3, women's tiers, Svenska Cupen) from the
 * Fogis livescore XML behind svenskfotboll.se — see apis/football/fogis-livescore/README.md.
 *
 * One provider covers many competitions; [Game.competition] names the tournament. Ids are
 * Fogis match ids (shared with the Allsvenskan provider). Dates are Swedish local dates.
 * No standings, team or player resources exist in this feed. A `User-Agent` is required
 * (the shared [org.openscore.net.KtorFetcher] always sends one).
 */
public class FogisProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    /** 1 = national (SvFF); district FA ids give district competitions. */
    private val associationId: Int = NATIONAL,
    override val league: League = LEAGUE,
    private val clock: Clock = Clock.System,
    private val maxParsedEntries: Int = DEFAULT_MAX_PARSED_ENTRIES,
    private val maxParsedBytes: Long = DEFAULT_MAX_PARSED_BYTES,
) : BaseLeagueProvider() {

    private val mapper = FogisMapper(league.id)

    /**
     * The last parse of each file, keyed on the body the fetcher handed back. The fetcher's
     * cache returns the same body instance until the file is re-fetched, so the three Swedish
     * league providers on top of this one read the ~100 KB overview and tournament files
     * without each parsing them again. Identity, not equality: comparing two 100 KB strings
     * costs about what parsing does.
     */
    private class Parsed(val body: String, val root: XmlNode, val bytes: Long)
    private val parsed = LinkedHashMap<String, Parsed>()
    private var parsedBytes: Long = 0
    private val parsedLock = Mutex()

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LINEUPS,
        Capability.LIVE_UPDATES,
        Capability.CLOCK,
        Capability.CLOCK_RUNNING_FLAG,
        Capability.INTERMISSION_STATE,
        Capability.PERIOD_SCORES,
    )

    override suspend fun gamesOn(date: LocalDate): List<Game> = gamesOn(date, tournamentIds = null)

    /**
     * Games on [date], optionally only those in [tournamentIds] (see [tournamentIds]). The
     * overview's per-game `<events>` (half starts with wall-clock times, goals) drive the clock
     * and period scores; the timeline itself is left unloaded because that list is partial.
     */
    public suspend fun gamesOn(date: LocalDate, tournamentIds: Set<String>?): List<Game> {
        val day = date.toString().replace("-", "")
        val root = xml("overview-$associationId-$day.xml", LIVE_MAX_AGE)
        if (root["status"] == "403") return emptyList() // outside the current season
        return root.childrenNamed("game-info")
            .filter { tournamentIds == null || it.child("game")?.get("competition-id") in tournamentIds }
            .mapNotNull { info -> info.child("game")?.let { mapper.game(it, events = info.child("events"), now = clock.now(), reportType = null, includeEvents = false) } }
            .sortedWith(compareBy({ it.startTime }, { it.competition }))
    }

    /** Every tournament of the season for this association, cached for hours: `(id, name, team ids)`. */
    public suspend fun tournaments(): List<Tournament> =
        xml("tournaments-$associationId.xml", TOURNAMENTS_MAX_AGE).childrenNamed("tournament").map { t ->
            Tournament(t["id"].orEmpty(), t["name"].orEmpty(), t.child("teams")?.childrenNamed("team")?.mapNotNull { it["id"] }?.toSet().orEmpty())
        }

    /**
     * The season's tournament ids behind one of the competitions the app names. Ids are
     * season-specific (`133348` = Allsvenskan 2026), so they are looked up by name each season;
     * the cup is split into round groups (`Svenska Cupen 2026/27 omg. 1-2`, …) whose names do not
     * say men or women, so the men's ones are told apart by sharing teams with the men's leagues
     * (55 of the 96 first-round teams on 2026-09-13; the women's group shares none).
     */
    public suspend fun tournamentIds(competition: FogisCompetition): Set<String> {
        val all = tournaments()
        return when (competition) {
            FogisCompetition.ALLSVENSKAN -> all.filter { ALLSVENSKAN_NAME.matches(it.name) }.map { it.id }.toSet()
            FogisCompetition.SUPERETTAN -> all.filter { SUPERETTAN_NAME.matches(it.name) }.map { it.id }.toSet()
            FogisCompetition.SVENSKA_CUPEN -> {
                val menTeams = all.filter { MENS_LEAGUE_NAME.matches(it.name) }.flatMap { it.teamIds }.toSet()
                all.filter { it.name.startsWith(CUP_PREFIX) && it.teamIds.any { id -> id in menTeams } }.map { it.id }.toSet()
            }
        }
    }

    /**
     * A team's fixtures across the given tournaments (one `schedule-{id}.xml` each, cached like
     * the tournament list), the file the association's own fixture pages read.
     */
    public suspend fun teamSchedule(teamId: String, tournamentIds: Set<String>, startDate: LocalDate, endDate: LocalDate): List<Game> = coroutineScope {
        tournamentIds.map { tid ->
            async {
                val root = xml("schedule-$tid.xml", SCHEDULE_MAX_AGE)
                if (root["status"] == "403") return@async emptyList()
                root.childrenNamed("tournament").flatMap { t ->
                    t.childrenNamed("round").flatMap { r -> r.childrenNamed("game") }
                        .filter { g -> g.child("home-team")?.get("id") == teamId || g.child("away-team")?.get("id") == teamId }
                        .filter { g -> g["date"]?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() }?.let { it in startDate..endDate } == true }
                        .map { mapper.scheduledGame(it, t["tournament-name"]) }
                }
            }
        }.awaitAll().flatten().sortedBy { it.startTime }
    }

    override suspend fun game(id: String): Game {
        val root = gameInfo(id)
        val game = root.child("game") ?: throw NotFoundException("${league.name}: match '$id' not found", league.id)
        return mapper.game(game, events = root.child("events"), now = clock.now(), reportType = root["livescorereporttype"])
    }

    override suspend fun events(gameId: String): List<GameEvent> = game(gameId).events.orEmpty()

    override suspend fun lineups(gameId: String): List<Lineup> {
        val info = gameInfo(gameId).child("game") ?: return emptyList()
        val root = xml("lineup-$gameId.xml", LINEUP_MAX_AGE)
        return mapper.lineups(gameId, root, info)
    }

    private suspend fun gameInfo(id: String): XmlNode {
        val root = xml("game-info-$id.xml", LIVE_MAX_AGE)
        if (root["status"] == "403") throw NotFoundException("${league.name}: match '$id' not found", league.id)
        return root
    }

    private suspend fun xml(file: String, maxAge: Duration): XmlNode {
        val response = fetcher.get(baseUrl + file, maxAge = maxAge)
        if (response.status == 404) throw NotFoundException("${league.name}: $file → 404", league.id)
        response.requireSuccess()
        if (response.body.isBlank()) throw NotFoundException("${league.name}: $file is empty", league.id)
        parsedLock.withLock {
            val hit = parsed.remove(file)
            if (hit?.body === response.body) {
                // A manual access-order LRU: HashMap itself does not promote reads.
                parsed[file] = hit
                return@withLock hit.root
            }
            if (hit != null) parsedBytes -= hit.bytes
            null
        }?.let { return it }
        val root = try {
            SimpleXml.parse(response.body)
        } catch (e: IllegalArgumentException) {
            throw ProviderException("${league.name}: cannot parse $file: ${e.message}", e, league.id)
        }
        rememberParsed(file, response.body, root)
        return root
    }

    private suspend fun rememberParsed(file: String, body: String, root: XmlNode) {
        val bytes = body.utf8Length()
        if (maxParsedEntries <= 0 || maxParsedBytes <= 0 || bytes > maxParsedBytes) return
        parsedLock.withLock {
            parsed.remove(file)?.let { parsedBytes -= it.bytes }
            parsed[file] = Parsed(body, root, bytes)
            parsedBytes += bytes
            while (parsed.size > maxParsedEntries || parsedBytes > maxParsedBytes) {
                parsed.remove(parsed.keys.first())?.let { parsedBytes -= it.bytes }
            }
        }
    }

    public data class Tournament(val id: String, val name: String, val teamIds: Set<String>)

    public companion object {
        private val ALLSVENSKAN_NAME = Regex("""Allsvenskan \d{4}""")
        private val SUPERETTAN_NAME = Regex("""Superettan \d{4}""")
        /** The men's top three tiers, whose teams identify the men's cup groups. */
        private val MENS_LEAGUE_NAME = Regex("""(Allsvenskan|Superettan|Ettan (Norra|Södra)) \d{4}""")
        private const val CUP_PREFIX = "Svenska Cupen"
        private val TOURNAMENTS_MAX_AGE = 6.hours
        /** A tournament's fixture file (240 games for a league) changes with each result; `max-age=45` upstream. */
        private val SCHEDULE_MAX_AGE = 5.minutes

        /** Parsed XML holds a DOM as well as the raw body, so cap it more tightly than HTTP bytes. */
        private const val DEFAULT_MAX_PARSED_ENTRIES: Int = 32
        private const val DEFAULT_MAX_PARSED_BYTES: Long = 4L * 1024 * 1024

        public const val DEFAULT_BASE_URL: String = "https://c01.fogis.se/fogistemplates.se/livescore/xml/"
        public const val NATIONAL: Int = 1
        public val LEAGUE: League = League("fogis", Sport.FOOTBALL, "Swedish football (SvFF livescore)", "SE", "https://www.svenskfotboll.se/livescore/")
        public val SWEDEN: TimeZone = TimeZone.of("Europe/Stockholm")
        private val LIVE_MAX_AGE = 45.seconds
        private val LINEUP_MAX_AGE = 5.minutes

        /** Local `YYYY-MM-DD` + `HH:MM:SS` → instant. */
        public fun localInstant(date: String, time: String): kotlin.time.Instant =
            LocalDateTime(LocalDate.parse(date), LocalTime.parse(time)).toInstant(SWEDEN)
    }
}
