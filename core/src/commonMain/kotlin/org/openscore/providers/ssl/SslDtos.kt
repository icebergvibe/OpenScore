@file:Suppress("PropertyName")

package org.openscore.providers.ssl

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The SSL-only response shapes. ssl.se runs on the same Sportality platform as shl.se, so the
 * bootstrap, scoreboard, schedule, game, team and athlete routes decode with the shared
 * `Spt*` DTOs in `providers/sportality`; only the two below differ per site, because they
 * come from the statistics provider rather than the platform (`provider: "ibis"` here,
 * Statnet on the hockey sites). See apis/floorball/ssl/README.md.
 */

// ---- /api/statistics-v2/league-standings ----------------------------------------------

/** Floorball's table columns: a game tied after regulation is `RegT`, of which `OTW` were then won. */
@Serializable
public data class SslStandings(
    val leagueStandings: List<SslStandingsRow> = emptyList(),
    val groupings: List<SslGrouping> = emptyList(),
    val provider: String? = null,
)

@Serializable
public data class SslGrouping(val description: String, val first: Int, val last: Int)

@Serializable
public data class SslStandingsRow(
    @SerialName("Rank") val rank: Int = 0,
    @SerialName("GP") val gp: Int = 0,
    /** Won in regulation. */
    @SerialName("RegW") val regW: Int = 0,
    /** Tied after regulation - the sum of overtime/shootout wins and losses, not a final draw. */
    @SerialName("RegT") val regT: Int = 0,
    /** Lost in regulation. */
    @SerialName("RegL") val regL: Int = 0,
    /** Won after regulation; `RegT - OTW` is therefore the overtime/shootout losses. */
    @SerialName("OTW") val otw: Int = 0,
    @SerialName("G") val g: Int = 0,
    @SerialName("GA") val ga: Int = 0,
    @SerialName("Diff") val diff: Int = 0,
    @SerialName("Points") val points: Int = 0,
    val info: SslStandingsInfo,
)

@Serializable
public data class SslStandingsInfo(
    /** Display code the table shows (`Falun`). */
    val code: String? = null,
    /** Innebandy.se numeric id, not the platform UUID. */
    val teamId: Int? = null,
    val teamInfo: SslStandingsTeamInfo,
)

@Serializable
public data class SslStandingsTeamInfo(
    val teamUuid: String,
    val teamMedia: String? = null,
    val teamNames: SslTeamNames = SslTeamNames(),
)

/** The standings' own name object: snake_case site variants, unlike the platform's camelCase ones. */
@Serializable
public data class SslTeamNames(
    val code: String? = null,
    val short: String? = null,
    val long: String? = null,
    val full: String? = null,
)

// ---- /api/gameday/post-game-data/promo-bar-stats/{game}/{home}/{away} ------------------

/** The smallest clean team totals a finished game has: goals, shots on goal, saves and penalty minutes. */
@Serializable
public data class SslPromoStats(
    val statistics: List<SslPromoStat> = emptyList(),
    val statisticsProvider: String? = null,
)

@Serializable
public data class SslPromoStat(
    /** `G`, `SOG`, `SVS`, `PIM`. */
    val caption: String,
    val homeTeamValue: Double? = null,
    val awayTeamValue: Double? = null,
)
