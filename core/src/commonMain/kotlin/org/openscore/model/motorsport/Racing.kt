package org.openscore.model.motorsport

import kotlin.time.Instant

/** A Formula 1 championship season. Racing is deliberately not represented as a two-team game. */
public data class RacingSeason(val year: Int, val rounds: List<RacingRound>)

public data class RacingRound(
    val id: String,
    val number: Int,
    val name: String,
    val circuit: String?,
    val locality: String?,
    val country: String?,
    val sessions: List<RacingSession>,
)

public enum class RacingSessionKind { PRACTICE, SPRINT_QUALIFYING, SPRINT, QUALIFYING, RACE }

public data class RacingSession(
    val id: String,
    val roundId: String,
    val name: String,
    val kind: RacingSessionKind,
    val startsAt: Instant,
)

public data class RacingClassification(
    val sessionId: String,
    val entries: List<RacingResult>,
)

public data class RacingResult(
    val position: Int?,
    val positionText: String?,
    val driver: String,
    val driverCode: String?,
    val constructor: String?,
    val points: String?,
    val grid: Int?,
    val timeOrStatus: String?,
    val fastestLap: Boolean,
    val segments: List<String> = emptyList(),
)

public data class RacingStandings(
    val season: Int,
    val drivers: List<RacingStanding>,
    val constructors: List<RacingStanding>,
)

public data class RacingStanding(
    val position: Int,
    val name: String,
    val detail: String?,
    val points: String,
    val wins: String?,
)
