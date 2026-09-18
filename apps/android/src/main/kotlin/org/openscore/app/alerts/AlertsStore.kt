package org.openscore.app.alerts

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.openscore.app.data.Favorite

/** How long before kick-off a reminder is due. Offered in the settings screen. */
val LEAD_TIME_CHOICES = listOf(5, 10, 15, 30, 60)

/**
 * Which favourites notify, and what they notify about.
 *
 * Following something and being notified about it are deliberately separate: a league can sit in
 * the Favorites feed for browsing while only a couple of its teams are allowed to buzz the
 * phone. [keys] holds [Favorite.key] values, so a renamed team keeps its alert.
 *
 * [started], [goals], [cards] and [breaks] default off where the other two default on. They are
 * the settings that cost anything while a match is around — each turns a followed fixture into
 * a poll every few minutes — so they are opted into. [cards] is the one that costs a request per
 * match on top of the shared listing, because bookings are only in a provider's events.
 */
data class AlertSettings(
    val kickoff: Boolean = true,
    val results: Boolean = true,
    val started: Boolean = false,
    val goals: Boolean = false,
    val cards: Boolean = false,
    val breaks: Boolean = false,
    val leadMinutes: Int = 10,
    val keys: Set<String> = emptySet(),
) {
    fun notifies(favorite: Favorite): Boolean = favorite.key in keys

    /** True when a running match has to be read for what happened in it. */
    val incidents: Boolean get() = goals || cards

    /** True when something has to be watched while the match runs, rather than around it. */
    val live: Boolean get() = started || incidents || breaks

    /** True when there is anything at all worth scheduling. */
    val active: Boolean get() = keys.isNotEmpty() && (kickoff || results || live)

    /** So switching a kind off clears the rows already queued for it, not only future ones. */
    fun wants(kind: AlertKind): Boolean = when (kind) {
        AlertKind.KICKOFF -> kickoff
        AlertKind.STARTED -> started
        AlertKind.RESULT -> results
        AlertKind.LIVE -> incidents
        AlertKind.BREAK -> breaks
    }

    /** What the schedule is built from; an unchanged one means an app launch costs no request. */
    val signature: String
        get() = listOf(kickoff, results, started, goals, cards, breaks, leadMinutes).joinToString("|") +
            "|" + keys.sorted().joinToString(",")
}

/**
 * One instance per process, on [org.openscore.app.OpenScoreApp]. The mutators are synchronised
 * because two threads write it: the settings screen on the main thread, and the scheduler's
 * [retain] on the I/O dispatcher. Each is a read-modify-write of the whole value, so without the
 * lock a toggle landing during a rebuild could put back a key the rebuild had just retired.
 */
class AlertsStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("alerts", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AlertSettings> = _settings

    private fun load() = AlertSettings(
        kickoff = prefs.getBoolean(KICKOFF, true),
        results = prefs.getBoolean(RESULTS, true),
        started = prefs.getBoolean(STARTED, false),
        goals = prefs.getBoolean(GOALS, false),
        cards = prefs.getBoolean(CARDS, false),
        breaks = prefs.getBoolean(BREAKS, false),
        leadMinutes = prefs.getInt(LEAD, 10),
        keys = prefs.getStringSet(KEYS, emptySet()).orEmpty().toSet(),
    )

    private fun persist(settings: AlertSettings) {
        prefs.edit()
            .putBoolean(KICKOFF, settings.kickoff)
            .putBoolean(RESULTS, settings.results)
            .putBoolean(STARTED, settings.started)
            .putBoolean(GOALS, settings.goals)
            .putBoolean(CARDS, settings.cards)
            .putBoolean(BREAKS, settings.breaks)
            .putInt(LEAD, settings.leadMinutes)
            .putStringSet(KEYS, settings.keys.toSet())
            .apply()
        _settings.value = settings
    }

    @Synchronized
    fun toggle(favorite: Favorite) {
        val current = _settings.value
        persist(current.copy(keys = if (favorite.key in current.keys) current.keys - favorite.key else current.keys + favorite.key))
    }

    @Synchronized
    fun update(change: (AlertSettings) -> AlertSettings) = persist(change(_settings.value))

    /** Forgets alerts whose favourite has since been removed, so the set cannot grow stale. */
    @Synchronized
    fun retain(validKeys: Set<String>) {
        val current = _settings.value
        val kept = current.keys intersect validKeys
        if (kept.size != current.keys.size) persist(current.copy(keys = kept))
    }

    private companion object {
        const val KICKOFF = "kickoff"
        const val RESULTS = "results"
        const val STARTED = "started"
        const val GOALS = "goals"
        const val CARDS = "cards"
        const val BREAKS = "breaks"
        const val LEAD = "lead_minutes"
        const val KEYS = "keys"
    }
}
