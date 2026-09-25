package org.openscore.app.ui.feed

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import org.openscore.LeagueError
import org.openscore.app.data.RacingSessionEntry
import org.openscore.app.data.ScoresRepository
import org.openscore.app.data.wantsScorePoll
import org.openscore.model.Game
import org.openscore.provider.runCatchingUnlessCancelled
import kotlin.time.Clock
import kotlin.time.Instant

/** One day of one feed. */
sealed class DayState {
    data object Loading : DayState()

    data class Loaded(
        val games: List<Game>,
        /** Leagues that did not answer; the rest of the day is still shown. */
        val failed: List<LeagueError>,
        /** Leagues still being asked; the day is drawn with what has come in so far. */
        val pending: List<String> = emptyList(),
        /** The day's sessions of any racing series asked for (F1): no games, a calendar. */
        val sessions: List<RacingSessionEntry> = emptyList(),
    ) : DayState()

    data class Error(val message: String) : DayState()
}

/**
 * Holds the days each feed has fetched, keyed by the leagues asked for, so that coming back to
 * a feed shows what it showed before without a request. Every fetch goes through the core's
 * aggregator, which asks the leagues concurrently and reports a failing league rather than
 * failing the day.
 *
 * **Main-confined.** [held] and [jobs] are plain maps: every entry point is called from the
 * screen, and [viewModelScope] is `Dispatchers.Main.immediate`, so they are only ever touched
 * from one thread. The requests themselves are concurrent, but they are started and retired
 * here. A caller from another thread, or a test that hands Main a thread pool, breaks that.
 */
class FeedViewModel(private val repository: ScoresRepository) : ViewModel() {

    private val held = LinkedHashMap<List<String>, MutableStateFlow<Map<LocalDate, DayState>>>()
    private val spec = MutableStateFlow<FeedSpec?>(null)
    private val jobs = HashMap<Pair<List<String>, LocalDate>, DayJob>()
    private var hunt: Job? = null

    /**
     * A fetch in flight, with what it was asked for. Two requests for the same feed and day
     * coalesce only when the one already running will deliver what the new one wants: a reader's
     * pull goes to the network where a stored day would have done, and a whole-day fetch covers
     * leagues that a poll of the few due ones does not.
     */
    private class DayJob(val job: Job, val fresh: Boolean, val leagues: Set<String>) {
        fun answers(fresh: Boolean, leagues: List<String>): Boolean =
            (this.fresh || !fresh) && this.leagues.containsAll(leagues)
    }

    /**
     * The days held for [spec]. Handed out per feed rather than switched behind one flow, so a
     * change of feed never shows the outgoing feed's days under the incoming feed's name for a
     * frame — which was enough to anchor the list on them and then lose the anchor.
     */
    fun days(spec: FeedSpec): StateFlow<Map<LocalDate, DayState>> = flowFor(spec.fetchKey)

    private val _inFlight = MutableStateFlow(0)
    val inFlight: StateFlow<Int> = _inFlight

    private fun flowFor(key: List<String>): MutableStateFlow<Map<LocalDate, DayState>> {
        // Reinsert on a hit: LinkedHashMap is insertion ordered, so without this the eviction
        // below would drop the feed opened longest ago rather than the one left alone longest -
        // and the feed someone keeps coming back to is the first one opened.
        held.remove(key)?.let { held[key] = it; return it }
        // A handful of feeds is plenty; the rail has three sports and Favorites is one more.
        if (held.size >= 6) {
            val evicted = held.keys.first()
            held.remove(evicted)
            // An evicted feed is no longer visible. Do not keep its network work or completed
            // Job objects for the rest of this ViewModel's lifetime.
            jobs.entries.removeAll { (jobKey, running) ->
                if (jobKey.first != evicted) false else {
                    running.job.cancel()
                    true
                }
            }
        }
        return MutableStateFlow<Map<LocalDate, DayState>>(emptyMap()).also { held[key] = it }
    }

    /**
     * Switch feeds and resolve today. Looking ahead is deliberately user-driven: automatically
     * hunting a quiet week multiplied one screen open by eight dates and every selected league.
     * The timeline's explicit "Load later days" affordance still fetches future dates in small
     * batches when the reader asks for them.
     */
    fun openFeed(next: FeedSpec, today: LocalDate) {
        if (spec.value == next) return
        spec.value = next
        hunt?.cancel()
        hunt = viewModelScope.launch { loadDayAndWait(next, today) }
    }

    /** Fetches a day the feed has not held yet; a held or in-flight day is left alone. */
    fun loadDay(date: LocalDate) {
        val s = spec.value ?: return
        val flow = flowFor(s.fetchKey)
        if (flow.value[date] != null) return
        fetch(s, date, flow)
    }

    /** Re-fetches a day the feed already holds — the reader's pull, so the store is bypassed. */
    fun refreshDay(date: LocalDate) {
        val s = spec.value ?: return
        val flow = flowFor(s.fetchKey)
        if (flow.value[date] !is DayState.Loaded && flow.value[date] !is DayState.Error) return
        fetch(s, date, flow, fresh = true)
    }

    /**
     * The poll: only leagues with a game in play or on the doorstep of kick-off
     * ([wantsScorePoll]) are asked again, and the answer replaces just their games in the held
     * day. The Live feed also asks when nothing is due, because an empty live listing is the
     * state before the next kick-off as much as after the last.
     */
    fun refreshLive(date: LocalDate, isToday: Boolean, now: Instant = Clock.System.now()) {
        val s = spec.value ?: return
        val flow = flowFor(s.fetchKey)
        val state = flow.value[date] as? DayState.Loaded ?: return
        val due = state.games.filter { it.wantsScorePoll(now) }.map { it.leagueId }.distinct()
        val leagues = when {
            due.isNotEmpty() -> due
            // Only today can still produce a kick-off; a league's yesterday with nothing running is over.
            s.kind == FeedKind.LIVE && isToday -> s.leagueIds
            else -> return
        }
        fetch(s, date, flow, leagues)
    }

    /** Asks [leagues] (the feed's, or the few a poll cares about) for [date]; other leagues' games in the held day stay. */
    private fun fetch(
        s: FeedSpec,
        date: LocalDate,
        flow: MutableStateFlow<Map<LocalDate, DayState>>,
        leagues: List<String> = s.leagueIds,
        fresh: Boolean = false,
    ) {
        val jobKey = s.fetchKey to date
        val running = jobs[jobKey]
        if (running != null && running.job.isActive) {
            if (running.answers(fresh, leagues)) return
            // It does not. The reader pulled while a poll was running, or a whole day was asked
            // for while only the due leagues were: let the weaker request go.
            running.job.cancel()
        }
        val initial = flow.value[date] as? DayState.Loaded
        if (initial == null) flow.value = flow.value + (date to DayState.Loading)
        val job = viewModelScope.launch {
            // Counted inside the coroutine, so a job cancelled before its body ever ran cannot
            // leave the count standing at one for the rest of this ViewModel's life. `update`
            // rather than `++` because this is a read-modify-write on shared state.
            _inFlight.update { it + 1 }
            val previous: DayState.Loaded? = initial
            // Each writer below merges into whatever the day holds by then, so the games and the
            // sessions can land in either order without one blanking the other.
            fun current(map: Map<LocalDate, DayState>): DayState.Loaded? = map[date] as? DayState.Loaded ?: previous
            try {
                coroutineScope {
                    // A racing series (F1) has no day listing: its sessions come from the season
                    // calendar, one cached read, spliced in beside the games.
                    val racing = leagues.filter(repository::isRacing)
                    val gameLeagues = leagues - racing.toSet()
                    if (racing.isNotEmpty()) launch {
                        val failed = ArrayList<LeagueError>()
                        val sessions = racing.flatMap { id ->
                            runCatchingUnlessCancelled { repository.racingSessionsOn(date, id) }
                                .getOrElse { failed += LeagueError(id, it); emptyList() }
                        }
                        flow.update { map ->
                            val cur = current(map)
                            map + (date to DayState.Loaded(
                                games = cur?.games.orEmpty(),
                                failed = cur?.failed.orEmpty().filterNot { it.leagueId in racing } + failed,
                                // The games are still on their way while the day holds nothing but the calendar.
                                pending = cur?.pending ?: gameLeagues,
                                sessions = sessions,
                            ))
                        }
                    }
                    val started = System.currentTimeMillis()
                    var first = true
                    repository.gamesOn(date, gameLeagues, fresh).collect { result ->
                        if (result.pending.isEmpty()) {
                            Log.d(TAG, "gamesOn $date ${gameLeagues.size} leagues: ${result.games.size} games in ${System.currentTimeMillis() - started} ms" +
                                (if (result.errors.isEmpty()) "" else ", failed ${result.errors.map { "${it.leagueId}: ${it.cause.message?.take(120)}" }}"))
                        } else if (first) {
                            Log.d(TAG, "gamesOn $date first of ${gameLeagues.size} leagues in ${System.currentTimeMillis() - started} ms")
                        }
                        first = false
                        // A league keeps what it showed until its new answer is in, so neither a poll nor a
                        // refresh blanks the slow leagues while the quick ones land. On a whole-day fetch the
                        // last emission has replaced every league; a poll of some leagues leaves the rest alone.
                        val replaced = gameLeagues.toSet() - result.pending.toSet()
                        flow.update { map ->
                            val cur = current(map)
                            val games = cur?.games.orEmpty().filterNot { it.leagueId in replaced } + result.games
                            val failed = cur?.failed.orEmpty().filterNot { it.leagueId in replaced } + result.errors
                            map + (date to DayState.Loaded(
                                games.sortedWith(compareBy({ it.startTime }, { it.leagueId }, { it.id })),
                                failed,
                                result.pending,
                                sessions = cur?.sessions.orEmpty(),
                            ))
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                flow.value = flow.value + (date to (previous ?: DayState.Error(e.message ?: "Could not load this day")))
            } finally {
                _inFlight.update { it - 1 }
                // A cancelled, evicted or superseded request can finish after this feed/day has
                // been asked for again. It must not erase that replacement from the map.
                if (jobs[jobKey]?.job === coroutineContext[Job]) jobs.remove(jobKey)
            }
        }
        jobs[jobKey] = DayJob(job, fresh, leagues.toSet())
    }

    private suspend fun loadDayAndWait(s: FeedSpec, date: LocalDate): DayState? {
        val flow = flowFor(s.fetchKey)
        if (flow.value[date] == null) fetch(s, date, flow)
        jobs[s.fetchKey to date]?.job?.join()
        return flow.value[date]
    }

    companion object {
        private const val TAG = "OpenScore"

        /** What a feed keeps of a day: the Live feed only what is in play. */
        fun filterForKind(spec: FeedSpec, games: List<Game>): List<Game> =
            if (spec.kind == FeedKind.LIVE) games.filter { it.state.isLive } else games

        /** A game that kicked off on the league's yesterday can still be running in a local morning. */
        fun liveDatesFor(today: LocalDate, localHour: Int): List<LocalDate> =
            if (localHour < 12) listOf(today.minus(1, DateTimeUnit.DAY), today) else listOf(today)
    }
}
