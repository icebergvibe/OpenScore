package org.openscore.app.alerts

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** How long a delivered alert is remembered, so a rebuild cannot queue it a second time. */
private const val NOTIFIED_RETENTION_MS = 48L * 60 * 60 * 1000

enum class AlertKind { KICKOFF, STARTED, RESULT, LIVE, BREAK }

/**
 * One notification waiting to be delivered, or one watch on a running game.
 *
 * A kick-off reminder carries everything it needs to render, so firing one costs no network at
 * all. Every other kind re-reads the league's listing for [date] when it falls due, to learn
 * whether the game has started, paused, scored or finished — which is why the league's own day
 * is stored rather than worked out again from the kick-off.
 */
@Serializable
data class PendingAlert(
    val leagueId: String,
    val gameId: String,
    /** The league's day the game is filed under (ISO date), so a re-read asks the same listing. */
    val date: String,
    val kind: AlertKind,
    val dueAt: Long,
    val kickoffAt: Long,
    val home: String,
    val away: String,
    val competition: String,
    /**
     * LIVE and BREAK rows only. False until one poll has recorded what had already happened
     * without announcing it, which is what stops switching alerts on at half time from
     * delivering every goal of the first half at once — or that half time itself.
     */
    val seeded: Boolean = false,
    /** LIVE rows: the score as last seen (`2-1`), so the next poll can tell a goal from nothing. */
    val seenScore: String? = null,
    /** LIVE rows: card ids already announced or seeded; BREAK rows: breaks already announced. */
    val seen: Set<String> = emptySet(),
) {
    /** The game, shared by every kind of row on it. */
    val key: String get() = "$leagueId/$gameId"

    val id: String get() = "$key|${kind.name}"

    val title: String get() = "$home v $away"
}

/**
 * The scheduled queue, persisted so it survives a reboot or the process being killed.
 *
 * Held as one JSON blob rather than a database: it is a handful of rows that are rewritten
 * wholesale on every refresh.
 */
class AlertQueue(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("alert_queue", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingSerializer = ListSerializer(PendingAlert.serializer())
    private val notifiedSerializer = MapSerializer(String.serializer(), Long.serializer())

    fun pending(): List<PendingAlert> {
        val raw = prefs.getString(PENDING, null) ?: return emptyList()
        return runCatching { json.decodeFromString(pendingSerializer, raw) }.getOrDefault(emptyList())
    }

    fun savePending(alerts: List<PendingAlert>) {
        prefs.edit().putString(PENDING, json.encodeToString(pendingSerializer, alerts.sortedBy { it.dueAt })).apply()
    }

    private fun notified(): Map<String, Long> {
        val raw = prefs.getString(NOTIFIED, null) ?: return emptyMap()
        return runCatching { json.decodeFromString(notifiedSerializer, raw) }.getOrDefault(emptyMap())
    }

    /** Everything delivered recently, read in one go: a pass checks every row it holds against this. */
    fun notifiedIds(): Set<String> = notified().keys

    fun markNotified(ids: Collection<String>, now: Long = System.currentTimeMillis()) {
        if (ids.isEmpty()) return
        val fresh = (notified() + ids.associateWith { now }).filterValues { now - it < NOTIFIED_RETENTION_MS }
        prefs.edit().putString(NOTIFIED, json.encodeToString(notifiedSerializer, fresh)).apply()
    }

    /** What the schedule was last built from, so an unchanged app launch costs no request. */
    fun signature(): String? = prefs.getString(SIGNATURE, null)

    fun lastRefresh(): Long = prefs.getLong(LAST_REFRESH, 0L)

    fun stamp(signature: String, at: Long) {
        prefs.edit().putString(SIGNATURE, signature).putLong(LAST_REFRESH, at).apply()
    }

    private companion object {
        const val PENDING = "pending"
        const val NOTIFIED = "notified"
        const val SIGNATURE = "signature"
        const val LAST_REFRESH = "last_refresh"
    }
}
