package org.openscore.app.alerts

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import org.openscore.GamesOnDate
import org.openscore.app.OpenScoreApp
import org.openscore.app.data.FavoriteFilter
import org.openscore.app.data.ScoresRepository
import org.openscore.app.ui.common.groupTitle
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.Sport
import org.openscore.provider.Capability
import org.openscore.provider.runCatchingUnlessCancelled
import java.util.Calendar
import java.util.Date
import kotlin.time.Clock

private const val TAG = "OpenScoreAlerts"

/**
 * A receiver pass has a 20 s ceiling. A feed is allowed less than that so one slow host cannot
 * cancel the whole pass just before its own request timeout, leaving every due row untouched.
 */
private const val LISTING_TIMEOUT_MS = 10_000L
private const val EVENTS_TIMEOUT_MS = 5_000L

/** Local hour at which the next day's games are looked up. */
private const val DAILY_REFRESH_HOUR = 3

/** Opening the app will not rebuild the schedule more often than this unless something changed. */
private const val REFRESH_MIN_INTERVAL_MS = 6L * 60 * 60 * 1000

/**
 * How soon a rebuild that could read nothing is tried again. BOOT_COMPLETED routinely arrives
 * before the network does, and the 3 a.m. alarm can land on a phone in flight mode.
 */
private const val REFRESH_RETRY_MS = 15L * 60 * 1000

/**
 * Turns followed games into notifications without ever telling anyone what is followed.
 *
 * The whole design rests on the app already knowing kick-off times: one daily read of the
 * same day listings the feeds use is enough to schedule every reminder, so a kick-off alert
 * costs no network at all when it fires. Everything else re-reads the listing when it falls
 * due, and one request per league covers every followed game in it.
 *
 * Exactly one alert alarm is outstanding at a time. It fires, delivers whatever is due, and sets
 * the next one, so the phone is never woken speculatively. It is near-exact when Android's Alarms
 * & reminders access is granted and remains an inexact, Doze-piercing fallback otherwise.
 */
object AlertScheduler {

    const val ACTION_FIRE = "org.openscore.app.action.ALERT_FIRE"
    const val ACTION_REFRESH = "org.openscore.app.action.ALERT_REFRESH"

    /**
     * One rebuild or delivery at a time. Both read the queue, work on it and write it back whole,
     * and both are started on every resume as well as by their alarms; interleaved, whichever
     * wrote last would put back the copy it had read first.
     */
    private val lock = Mutex()

    /** Rebuilds only if what is followed has changed, or the schedule has gone stale. Cheap to call on every launch. */
    suspend fun ensureScheduled(context: Context) {
        val app = OpenScoreApp.from(context)
        lock.withLock {
            val queue = AlertQueue(app)
            val unchanged = queue.signature() == app.alerts.settings.value.signature
            if (unchanged && System.currentTimeMillis() - queue.lastRefresh() < REFRESH_MIN_INTERVAL_MS) {
                // Nothing to re-read, but the alarms are re-set from the stored queue anyway: a
                // force-stop (or "Force stop" in Android's settings) cancels every alarm an app
                // holds, and without this the chain would stay dead until the schedule went stale.
                val pending = queue.pending()
                if (pending.isNotEmpty()) {
                    scheduleNext(app, pending)
                    scheduleDailyRefresh(app)
                }
                return
            }
            rebuild(app)
        }
    }

    /** Re-reads favourites and rebuilds the queue. Safe to call whenever settings change. */
    suspend fun refresh(context: Context) {
        val app = OpenScoreApp.from(context)
        lock.withLock { rebuild(app) }
    }

    /** Delivers everything due, then re-arms. Called from the alarm. */
    suspend fun fire(context: Context) {
        val app = OpenScoreApp.from(context)
        lock.withLock { deliver(app) }
    }

    /** Arms the alarms for the queue as stored, after a pass that was cut short before it could. */
    fun rearm(context: Context) {
        Log.d(TAG, "pass timed out; re-arming from the stored queue")
        scheduleNext(context, AlertQueue(context).pending())
        scheduleDailyRefresh(context)
    }

    /** Opening the app is the one moment a wake-up is free, so anything the system has been sitting on arrives at once. */
    suspend fun deliverOverdue(context: Context) {
        val now = System.currentTimeMillis()
        if (AlertQueue(context).pending().none { it.dueAt <= now }) return
        Log.d(TAG, "catching up on overdue alerts")
        fire(context)
    }

    private suspend fun rebuild(app: OpenScoreApp) {
        val queue = AlertQueue(app)
        val repository = app.repository
        val favorites = app.favorites.favorites.value
        app.alerts.retain(favorites.map { it.key }.toSet())
        val settings = app.alerts.settings.value
        val alerting = favorites.filter { settings.notifies(it) }.toSet()

        if (alerting.isEmpty() || !settings.active) {
            Log.d(TAG, "nothing to alert on; standing down")
            queue.savePending(emptyList())
            queue.stamp(settings.signature, System.currentTimeMillis())
            cancel(app, ACTION_FIRE)
            cancel(app, ACTION_REFRESH)
            return
        }

        Notifications.ensureChannels(app)
        val filter = FavoriteFilter(alerting)
        val leagues = filter.leaguesToFetch(repository.leagues)
        val dates = upcomingDates()
        Log.d(TAG, "refreshing ${leagues.size} league(s) over ${dates.size} day(s)")
        val now = System.currentTimeMillis()

        val found: List<Pair<LocalDate, GamesOnDate?>> = coroutineScope {
            dates.map { date -> async { date to readListing(repository, date, leagues) } }.awaitAll()
        }

        val fresh = mutableListOf<PendingAlert>()
        // Every game this pass found to be followed, whether or not it produced a row — a finished
        // one produces none but its queued result check is still wanted. Read with [scanned] to
        // tell "no longer followed" from "not looked at".
        val followed = mutableSetOf<String>()
        val scanned = mutableSetOf<Pair<String, String>>()
        // Followed games the league has called off: rows already queued for one are the reminder
        // for a kick-off that is not happening, and are replaced by saying so.
        val calledOff = mutableMapOf<String, Game>()
        found.forEach { (date, result) ->
            result ?: return@forEach
            val failed = result.errors.map { it.leagueId }.toSet()
            // A bounded read may have useful answers from quick leagues while others are still
            // pending. Only call a league scanned when it actually answered; otherwise rows for
            // the slow league would be mistaken for games that are no longer followed.
            scanned += (result.leagues - failed - result.pending.toSet()).map { it to date.toString() }
            result.games.filter(filter::matches).forEach { game ->
                followed += game.key
                if (game.state == GameState.POSTPONED || game.state == GameState.CANCELLED) calledOff[game.key] = game
                val league = repository.league(game.leagueId)
                fresh += AlertRules.alertsFor(game, league?.sport ?: Sport.FOOTBALL, groupTitle(league, game.leagueId), date.toString(), settings, now)
            }
        }

        // Merge rather than replace, and which side wins depends on the kind. A kick-off time can
        // move, so the freshly read reminder is authoritative; a result check may already have
        // backed off after finding a match still running, and a live watch has seeded itself, so
        // those are kept as they are. Either way nothing already delivered comes back.
        val notified = queue.notifiedIds()
        val freshById = fresh.filterNot { it.id in notified }.associateBy { it.id }
        val carried = queue.pending().filterNot { row ->
            row.id in notified ||
                ((row.kind == AlertKind.KICKOFF || row.kind == AlertKind.STARTED) && row.id in freshById) ||
                // Otherwise switching a kind off would only stop future rows.
                !settings.wants(row.kind) ||
                // Nor would unfollowing stop them: a team dropped mid-afternoon would go on
                // announcing its goals and its final score until they had all fired.
                !stillFollowed(row, leagues.toSet(), scanned, followed)
        }
        val (off, kept) = carried.partition { it.key in calledOff }
        announceCalledOff(app, queue, off, calledOff, notified)
        val merged = (kept + freshById.values).distinctBy { it.id }

        queue.savePending(merged)
        Log.d(TAG, "queued ${merged.size} alert(s): ${merged.sortedBy { it.dueAt }.map { "${it.kind} ${it.title} @${Date(it.dueAt)}" }}")
        scheduleNext(app, merged)

        // Do not leave a slow followed league undiscovered until tomorrow merely because another
        // league answered. The useful partial queue is already saved; retry the timed-out part
        // soon and stamp the daily refresh only after a complete pass.
        val incomplete = found.any { (_, result) -> result == null || result.pending.isNotEmpty() }
        if (scanned.isEmpty() || incomplete) {
            Log.d(TAG, "schedule read was incomplete; retrying in ${REFRESH_RETRY_MS / 60_000} minutes")
            setAlarm(app, ACTION_REFRESH, System.currentTimeMillis() + REFRESH_RETRY_MS)
            return
        }
        queue.stamp(settings.signature, System.currentTimeMillis())
        scheduleDailyRefresh(app)
    }

    /**
     * Says once, for each game with a row queued on it, that the league has called it off. The
     * rows themselves are dropped by the caller; every kind of watch would otherwise end on the
     * same finding when it fell due, and the reminder would fire for nothing.
     */
    private fun announceCalledOff(app: OpenScoreApp, queue: AlertQueue, rows: List<PendingAlert>, games: Map<String, Game>, notified: Set<String>) {
        val delivered = mutableListOf<String>()
        rows.distinctBy { it.key }.forEach { row ->
            val post = AlertRules.calledOffPost(row, games.getValue(row.key)) ?: return@forEach
            if (post.id in notified) return@forEach
            Log.d(TAG, "called off: ${row.title} (${games.getValue(row.key).state})")
            Notifications.post(app, post, GameLink.of(row))
            delivered += post.id
        }
        queue.markNotified(delivered)
    }

    /**
     * Whether a row already queued is still about something followed. Absence from this pass is
     * not on its own an answer: the scan covers three days for the followed leagues only, so a
     * row whose league failed to load has simply not been looked at and is left alone.
     */
    private fun stillFollowed(row: PendingAlert, leagues: Set<String>, scanned: Set<Pair<String, String>>, followed: Set<String>): Boolean = when {
        row.leagueId !in leagues -> false
        (row.leagueId to row.date) in scanned -> row.key in followed
        else -> true
    }

    private suspend fun deliver(app: OpenScoreApp) {
        val queue = AlertQueue(app)
        val repository = app.repository
        val settings = app.alerts.settings.value
        val now = System.currentTimeMillis()

        val pending = queue.pending()
        // Never consume a future row merely because another alarm woke the app. That used to
        // pull up to five minutes of reminders and polls into one batch.
        val due = pending.filter { it.dueAt <= now }
        val remaining = pending.filter { it.dueAt > now }.toMutableList()
        if (due.isEmpty()) {
            scheduleNext(app, remaining)
            return
        }
        Log.d(TAG, "firing ${due.size} of ${pending.size}: ${due.map { "${it.kind} ${it.title}" }}")

        Notifications.ensureChannels(app)
        val delivered = mutableListOf<String>()
        // Read once and added to as we go, so a duplicate inside this batch is caught too.
        val notified = queue.notifiedIds().toMutableSet()
        fun show(post: Post, link: GameLink) {
            if (!notified.add(post.id)) return
            Notifications.post(app, post, link)
            delivered += post.id
        }

        due.filter { it.kind == AlertKind.KICKOFF }.forEach { row ->
            // An overdue pre-game reminder is no longer useful once the scheduled start has
            // passed. The start/result watches will still report what the feed actually says.
            AlertRules.kickoffPost(row, now)?.let { show(it, GameLink.of(row)) }
                ?: Log.d(TAG, "dropping stale reminder for ${row.title}")
        }

        val watches = due.filter { it.kind != AlertKind.KICKOFF }
        if (watches.isNotEmpty()) {
            // One listing per league-day covers every followed game in it, whatever kind is asking.
            val listings: Map<String, GamesOnDate?> = coroutineScope {
                watches.groupBy { it.date }.map { (date, rows) ->
                    async { date to readListing(repository, LocalDate.parse(date), rows.map { it.leagueId }.distinct()) }
                }.awaitAll().toMap()
            }
            val games: Map<String, Game> = listings.values.filterNotNull().flatMap { it.games }.associateBy { it.key }
            fun unread(row: PendingAlert): Boolean {
                val listing = listings[row.date] ?: return true
                return row.leagueId in listing.pending || listing.errors.any { it.leagueId == row.leagueId }
            }

            // Events are the one read that cannot be shared between games, so the rows that need
            // them are asked for together rather than one after another.
            val events: Map<String, List<GameEvent>?> = coroutineScope {
                watches.filter { it.kind == AlertKind.LIVE && !unread(it) && AlertRules.needsEvents(it, games[it.key], settings, repository.supports(it.leagueId, Capability.EVENTS)) }
                    .map { row -> async { row.key to withTimeoutOrNull(EVENTS_TIMEOUT_MS) { runCatchingUnlessCancelled { repository.events(row.leagueId, row.gameId) }.getOrNull() } } }
                    .awaitAll().toMap()
            }

            watches.forEach { row ->
                val sport = repository.league(row.leagueId)?.sport ?: Sport.FOOTBALL
                if (unread(row)) {
                    // The league did not answer, which says nothing about the game: look again,
                    // unless this has gone on long enough to stop caring.
                    if (now - row.kickoffAt > AlertRules.GIVE_UP_MS) Log.d(TAG, "giving up on ${row.title}") else remaining += row.copy(dueAt = now + AlertRules.LIVE_POLL_MINUTES * 60_000L)
                    return@forEach
                }
                val game = games[row.key]
                val outcome = when (row.kind) {
                    AlertKind.STARTED -> AlertRules.pollStart(row, sport, game, now)
                    AlertKind.BREAK -> AlertRules.pollBreak(row, sport, game, now)
                    AlertKind.LIVE -> AlertRules.pollLive(row, sport, game, events[row.key], settings, repository.supports(row.leagueId, Capability.EVENTS), now)
                    AlertKind.RESULT -> AlertRules.pollResult(row, sport, game, now)
                    AlertKind.KICKOFF -> Outcome(null)
                }
                outcome.log?.let { Log.d(TAG, it) }
                outcome.posts.forEach { show(it, GameLink.of(row)) }
                outcome.next?.let { remaining += it }
            }
        }

        queue.markNotified(delivered, now)
        queue.savePending(remaining)
        scheduleNext(app, remaining)
        scheduleDailyRefresh(app)
    }

    private val Game.key: String get() = "$leagueId/$id"

    /**
     * Keeps the last progressive snapshot if the slowest league misses the background budget.
     * Quick feeds can still update their alerts, while [GamesOnDate.pending] makes the remaining
     * rows retry instead of being treated as absent.
     */
    private suspend fun readListing(repository: ScoresRepository, date: LocalDate, leagues: Collection<String>): GamesOnDate? {
        var latest: GamesOnDate? = null
        withTimeoutOrNull(LISTING_TIMEOUT_MS) {
            repository.gamesOn(date, leagues).collect { latest = it }
        }
        return latest
    }

    /**
     * The days worth scheduling from. Yesterday as well as tomorrow, because the leagues file
     * games under their own dates: an NHL game at 22:00 Eastern is that day's for the league and
     * four in the morning of the next for a European phone, which the 3 a.m. rebuild would
     * otherwise never see.
     */
    private fun upcomingDates(): List<LocalDate> {
        val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
        return listOf(today.minus(1, DateTimeUnit.DAY), today, today.plus(1, DateTimeUnit.DAY))
    }

    private fun scheduleNext(context: Context, pending: List<PendingAlert>) {
        val next = pending.minByOrNull { it.dueAt }
        if (next == null) {
            cancel(context, ACTION_FIRE)
            return
        }
        setAlarm(context, ACTION_FIRE, next.dueAt)
        Log.d(TAG, "next alert at ${Date(next.dueAt)} (${next.kind} ${next.title})")
    }

    private fun scheduleDailyRefresh(context: Context) {
        val calendar = Calendar.getInstance().apply {
            if (get(Calendar.HOUR_OF_DAY) >= DAILY_REFRESH_HOUR) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, DAILY_REFRESH_HOUR)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        setAlarm(context, ACTION_REFRESH, calendar.timeInMillis)
    }

    /** Whether Android has granted the special access needed for near-exact background wake-ups. */
    fun canSchedulePrecisely(context: Context): Boolean {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || manager.canScheduleExactAlarms()
    }

    /**
     * Use a near-exact, Doze-piercing alarm for the user-facing alert when permission allows it.
     * The 3 a.m. queue refresh has no visible deadline and remains batchable. Fresh Android 13+
     * installs do not receive exact-alarm access automatically, so the inexact form is also the
     * safe fallback until it is enabled (or after it is revoked).
     */
    private fun setAlarm(context: Context, action: String, atMillis: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = maxOf(atMillis, System.currentTimeMillis() + 5_000)
        val operation = intentFor(context, action)
        if (action == ACTION_FIRE && canSchedulePrecisely(context)) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
                return
            } catch (_: SecurityException) {
                // Access can be revoked between the check and this call. Fall through instead
                // of breaking the alarm chain.
            }
        }
        manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, operation)
    }

    private fun cancel(context: Context, action: String) {
        context.getSystemService(AlarmManager::class.java)?.cancel(intentFor(context, action))
    }

    private fun intentFor(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
        context,
        if (action == ACTION_FIRE) 1 else 2,
        Intent(context, AlertReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
