package org.openscore.app.data

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.openscore.model.motorsport.RacingRound
import org.openscore.model.motorsport.RacingSeason
import org.openscore.model.motorsport.RacingSession
import org.openscore.model.motorsport.RacingSessionKind
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * One session of a racing round as the feeds list it. Racing has no two-team game, so
 * Following and Live show a followed series' sessions (practice, qualifying, sprint, race)
 * on their days instead — an entry to read, not a match to open.
 */
data class RacingSessionEntry(val leagueId: String, val round: RacingRound, val session: RacingSession) {
    val id: String get() = session.id
    val startsAt: Instant get() = session.startsAt

    fun phase(now: Instant): SessionPhase = when {
        now < session.startsAt -> SessionPhase.UPCOMING
        now < session.startsAt + session.kind.window() -> SessionPhase.UNDER_WAY
        else -> SessionPhase.OVER
    }

    fun matches(needle: String): Boolean =
        listOfNotNull(session.name, round.name, round.circuit, round.locality, round.country).any { it.lowercase().contains(needle) }
}

enum class SessionPhase { UPCOMING, UNDER_WAY, OVER }

/**
 * How long a session is taken to run. Jolpica publishes results, not live timing, so what is
 * under way is read off the schedule alone; the windows leave room for delays and red flags
 * rather than calling a session over while it is still on.
 */
fun RacingSessionKind.window(): Duration = when (this) {
    RacingSessionKind.PRACTICE -> 75.minutes
    RacingSessionKind.SPRINT_QUALIFYING -> 60.minutes
    RacingSessionKind.SPRINT -> 60.minutes
    RacingSessionKind.QUALIFYING -> 75.minutes
    RacingSessionKind.RACE -> 150.minutes
}

/** The sessions that start on [date] in [zone] — a race weekend has no home venue's calendar, so the day is the reader's. */
fun RacingSeason.sessionsOn(date: LocalDate, zone: TimeZone, leagueId: String): List<RacingSessionEntry> =
    rounds.flatMap { round ->
        round.sessions
            .filter { it.startsAt.toLocalDateTime(zone).date == date }
            .map { RacingSessionEntry(leagueId, round, it) }
    }.sortedBy { it.startsAt }
