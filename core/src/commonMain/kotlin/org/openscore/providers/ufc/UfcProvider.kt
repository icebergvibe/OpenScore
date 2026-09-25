package org.openscore.providers.ufc

import kotlinx.datetime.TimeZone
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.LocalDate
import org.openscore.cache.NoopSeasonScheduleStore
import org.openscore.cache.SeasonScheduleStore
import org.openscore.cache.SeasonSnapshot
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.League
import org.openscore.model.Sport
import org.openscore.model.combat.FightSituation
import org.openscore.net.Fetcher
import org.openscore.provider.BaseLeagueProvider
import org.openscore.provider.Capability
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * UFC (with Dana White's Contender Series and Road to UFC) from the live-stats API behind
 * ufc.com — see apis/mma/ufc/README.md.
 *
 * The API has two routes and no listing: `event/live/{id}.json` is a whole card, and ids are
 * dense but not chronological. So the provider keeps the cards it knows as a snapshot — in
 * memory and, through [scheduleStore], across processes — and finds new ones by sweeping the
 * id frontier:
 *
 * - with nothing stored, the frontier is located from [seedEventId] by jumping and bisecting
 *   over the tiny "unknown id" answers, and the [WINDOW] cards below it are read;
 * - every [FRONTIER_MAX_AGE], ids above the frontier are probed until [EMPTY_RUN] in a row are
 *   empty, and cards still open are re-read for changes to their fight card;
 * - a day is answered from the snapshot; a card on it that is due or under way is re-read at
 *   the live floor, and one restored from the store is re-read once (the store keeps a fight's
 *   state, not its result);
 * - [game] always reads the card, and adds the fight route's statistics once a fight has
 *   started.
 *
 * Game ids are `{eventId}-{fightId}`; the red corner is `home`. Dates are the card's own —
 * the calendar date at the venue.
 */
public class UfcProvider(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val clock: Clock = Clock.System,
    private val scheduleStore: SeasonScheduleStore = NoopSeasonScheduleStore,
    /** Where a cold sweep starts: any card from the last year; bump it now and then. */
    private val seedEventId: Int = SEED_EVENT_ID,
) : BaseLeagueProvider() {

    override val league: League = LEAGUE

    override val capabilities: Set<Capability> = setOf(
        Capability.GAMES_BY_DATE,
        Capability.GAME,
        Capability.EVENTS,
        Capability.LIVE_UPDATES,
    )

    /** One card as last read. [complete] is false for a card restored from the store, whose games lack their result. */
    private class Card(
        val eventId: Int,
        val date: LocalDate?,
        val readAt: Instant,
        val complete: Boolean,
        val games: List<Game>,
    ) {
        val terminal: Boolean get() = games.isNotEmpty() && games.all { it.state.isTerminal }
        /** A fight not over whose start is at hand or recent; bounded so a fight the feed never closes stops costing reads. */
        fun isDue(now: Instant): Boolean =
            games.any { !it.state.isTerminal && it.startTime <= now + KICKOFF_LEAD && it.startTime > now - RESULT_WINDOW }
    }

    private class Snapshot(val frontier: Int, val sweptAt: Instant, val cards: Map<Int, Card>)

    private val lock = Mutex()
    @Volatile private var snapshot: Snapshot? = null
    private var sweepFailedAt: Instant? = null

    override suspend fun gamesOn(date: LocalDate): List<Game> {
        val snap = snapshot()
        val now = clock.now()
        val onDate = snap.cards.values.filter { it.date == date }
        val refreshed = coroutineScope {
            onDate.filter { it.needsRefresh(now) }
                .map { c -> async { runCatchingUnlessCancelled { readCard(c.eventId, LIVE_MAX_AGE) }.getOrNull() } }
                .awaitAll().filterNotNull()
        }
        if (refreshed.isNotEmpty()) merge(refreshed)
        val byId = refreshed.associateBy { it.eventId }
        return onDate.map { byId[it.eventId] ?: it }
            .flatMap { it.games }
            .filter { it.scheduleDate == date }
            .sortedWith(compareBy<Game> { it.startTime }.thenByDescending { it.cardPosition() })
    }

    /** Due or under way, restored without results, or open and possibly re-carded since it was read. */
    private fun Card.needsRefresh(now: Instant): Boolean = when {
        terminal -> !complete
        isDue(now) -> true
        !complete -> true
        else -> now - readAt >= CARD_MAX_AGE
    }

    override suspend fun game(id: String): Game {
        val eventId = UfcMapper.eventIdOf(id) ?: throw NotFoundException("${league.name} game '$id' is not {event}-{fight}", league.id)
        val fightId = UfcMapper.fightIdOf(id) ?: throw NotFoundException("${league.name} game '$id' is not {event}-{fight}", league.id)
        val event = readEvent(eventId, LIVE_MAX_AGE) ?: throw NotFoundException("${league.name} event $eventId not found", league.id)
        merge(listOf(card(eventId, event, clock.now())))
        val fight = event.FightCard.firstOrNull { it.FightId == fightId }
            ?: throw NotFoundException("${league.name} fight $fightId is not on card $eventId", league.id)
        val game = UfcMapper.game(event, eventId, fight, withEvents = true)
        if (!game.state.hasStarted) return game
        // The fight route is the only source of strike and takedown totals; a failure there is not a failed game.
        val detail = runCatchingUnlessCancelled {
            fetcher.getJson("$baseUrl/fight/live/$fightId.json", UfcFightResponse.serializer(), LIVE_MAX_AGE, league.id).LiveFightDetail
        }.getOrNull() ?: return game
        return game.copy(stats = UfcMapper.stats(detail, game.home, game.away))
    }

    override suspend fun events(gameId: String): List<GameEvent> = game(gameId).events.orEmpty()

    // ---- snapshot -------------------------------------------------------------------------

    /** The snapshot, swept when there is none or the last sweep is older than [FRONTIER_MAX_AGE]. */
    private suspend fun snapshot(): Snapshot {
        snapshot?.takeIf { !it.isStale(clock.now()) }?.let { return it }
        return lock.withLock {
            val now = clock.now()
            val held = held()
            if (held != null && !held.isStale(now)) return@withLock held
            val failedAt = sweepFailedAt
            if (held != null && failedAt != null && now - failedAt < SWEEP_RETRY) return@withLock held
            val swept = runCatchingUnlessCancelled { sweep(held, now) }
            swept.getOrNull()?.let { fresh ->
                snapshot = fresh
                sweepFailedAt = null
                persist(fresh)
                return@withLock fresh
            }
            if (held == null) throw swept.exceptionOrNull()!!
            sweepFailedAt = now
            held
        }
    }

    private fun Snapshot.isStale(now: Instant): Boolean = now - sweptAt >= FRONTIER_MAX_AGE

    /** In memory or restored from the store; never the network. Call under [lock]. */
    private suspend fun held(): Snapshot? = snapshot ?: scheduleStore.load(league.id)?.let(::restore)?.also { snapshot = it }

    /** Stored games back into cards. Their results are gone with the store's record, so they are re-read on first use. */
    private fun restore(saved: SeasonSnapshot): Snapshot? {
        val cards = saved.games.groupBy { UfcMapper.eventIdOf(it.id) ?: -1 }.filterKeys { it > 0 }
            .map { (eventId, games) -> Card(eventId, games.first().scheduleDate, saved.savedAt, complete = false, games) }
            .associateBy { it.eventId }
        if (cards.isEmpty()) return null
        // Stored before the store's own age is a sweep ago: a restored snapshot is swept on first use.
        return Snapshot(cards.keys.max(), Instant.fromEpochSeconds(0), cards)
    }

    /**
     * Finds new cards above the frontier and re-reads the open ones. With no snapshot at all the
     * frontier is located first, from the seed, and the window below it read.
     */
    private suspend fun sweep(previous: Snapshot?, now: Instant): Snapshot {
        val cards = LinkedHashMap<Int, Card>()
        previous?.cards?.let(cards::putAll)
        var frontier = previous?.frontier ?: locateFrontier(cards, now)
        // New ids above the frontier, a batch at a time, until a whole run of empties says the sequence has ended.
        while (true) {
            val batch = coroutineScope {
                (frontier + 1..frontier + EMPTY_RUN).map { eid -> async { readEvent(eid, FRONTIER_MAX_AGE)?.let { eid to card(eid, it, now) } } }.awaitAll()
            }.filterNotNull()
            if (batch.isEmpty()) break
            batch.forEach { (eid, c) -> cards[eid] = c }
            frontier = batch.maxOf { it.first }
        }
        // Open cards and the window's holes (ids read as empty or cancelled before) may have changed.
        val from = maxOf(frontier - WINDOW + 1, 1)
        val stale = (from..frontier).filter { eid ->
            val c = cards[eid]
            c == null || (!c.terminal && now - c.readAt >= CARD_MAX_AGE)
        }
        coroutineScope {
            stale.map { eid -> async { readEvent(eid, CARD_MAX_AGE)?.let { eid to card(eid, it, now) } } }
                .awaitAll().filterNotNull().forEach { (eid, c) -> cards[eid] = c }
        }
        if (cards.isEmpty()) throw NotFoundException("${league.name}: no cards found from event $seedEventId", league.id)
        return Snapshot(frontier, now, cards)
    }

    /**
     * The highest existing id, found from the seed by jumping over ids until an empty answer,
     * then bisecting: about a dozen tiny requests. Every card met on the way is kept.
     */
    private suspend fun locateFrontier(cards: MutableMap<Int, Card>, now: Instant): Int {
        suspend fun exists(id: Int): Boolean {
            cards[id]?.let { return true }
            val event = readEvent(id, FRONTIER_MAX_AGE) ?: return false
            cards[id] = card(id, event, now)
            return true
        }
        if (!exists(seedEventId)) throw NotFoundException("${league.name}: seed event $seedEventId does not exist", league.id)
        var lo = seedEventId
        var hi = seedEventId + JUMP
        while (exists(hi)) { lo = hi; hi += JUMP }
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (exists(mid)) lo = mid else hi = mid
        }
        // Read the window below what bisection found; the sweep above it confirms the end of the sequence.
        val from = maxOf(lo - WINDOW + 1, 1)
        coroutineScope {
            (from..lo).filterNot { it in cards }
                .map { eid -> async { readEvent(eid, CARD_MAX_AGE)?.let { eid to card(eid, it, now) } } }
                .awaitAll().filterNotNull().forEach { (eid, c) -> cards[eid] = c }
        }
        return lo
    }

    private suspend fun merge(fresh: List<Card>) {
        lock.withLock {
            val held = held() ?: return
            snapshot = Snapshot(maxOf(held.frontier, fresh.maxOf { it.eventId }), held.sweptAt, held.cards + fresh.associateBy { it.eventId })
        }
        // A card that is over is written through, so a restart knows its fights' state without a read.
        val settled = fresh.filter { it.terminal }.flatMap { it.games }
        if (settled.isNotEmpty()) runCatchingUnlessCancelled { scheduleStore.update(league.id, SNAPSHOT_ID, settled) }
    }

    private suspend fun persist(snap: Snapshot) {
        runCatchingUnlessCancelled {
            scheduleStore.save(league.id, SeasonSnapshot(SNAPSHOT_ID, snap.sweptAt, snap.cards.values.flatMap { it.games }))
        }
    }

    // ---- network ---------------------------------------------------------------------------

    /** The event document, or null for an id the API does not know (a 200 with an empty object). */
    private suspend fun readEvent(eventId: Int, maxAge: Duration): UfcEvent? {
        val event = fetcher.getJson("$baseUrl/event/live/$eventId.json", UfcEventResponse.serializer(), maxAge, league.id).LiveEventDetail
        return event.takeUnless { it.isEmpty }
    }

    private suspend fun readCard(eventId: Int, maxAge: Duration): Card? = readEvent(eventId, maxAge)?.let { card(eventId, it, clock.now()) }

    /** The snapshot keeps a card's games without their timelines; [game] maps those on demand. */
    private fun card(eventId: Int, event: UfcEvent, now: Instant): Card =
        Card(eventId, UfcMapper.localDate(event), now, complete = true, UfcMapper.card(event, eventId, withEvents = false))

    private fun Game.cardPosition(): Int = (situation as? FightSituation)?.cardPosition ?: 0

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://d29dxerjsp82wz.cloudfront.net/api/v3"
        public val LEAGUE: League = League(UfcMapper.LEAGUE_ID, Sport.MMA, "UFC", "US", TimeZone.UTC, "https://www.ufc.com")
        /** What the store files the snapshot under; every game's `seasonId`. */
        public const val SNAPSHOT_ID: String = UfcMapper.SEASON_ID
        /** UFC 326 (2026-03-07). */
        public const val SEED_EVENT_ID: Int = 1296
        /** Cards below the frontier worth knowing: about five months of UFC, DWCS and Road to UFC ids. */
        public const val WINDOW: Int = 30
        /** Empty answers in a row that end a sweep. */
        public const val EMPTY_RUN: Int = 8
        private const val JUMP = 32
        private val LIVE_MAX_AGE = 10.seconds
        /** An open card is re-read this often for fights added or moved. */
        private val CARD_MAX_AGE = 1.hours
        /** The frontier is probed this often for new cards. */
        private val FRONTIER_MAX_AGE = 6.hours
        private val SWEEP_RETRY = 10.minutes
        /** The same lead the app's poll uses, so a card is read from the network as its first fight nears. */
        private val KICKOFF_LEAD = 5.minutes
        /** How long after its scheduled start a fight not marked over keeps its card on the live cadence. */
        private val RESULT_WINDOW = 8.hours
    }
}
