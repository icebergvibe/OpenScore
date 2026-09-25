package org.openscore.feed

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/*
 * OpenScore Feed v1 — the JSON contract apps consume. Documented in docs/feed-v1.md.
 *
 * This is deliberately a separate set of classes from org.openscore.model: the model can
 * evolve with the Kotlin API, the feed changes only with a version bump. Times are ISO-8601
 * UTC strings, durations are whole seconds, enums are their upper-case names, and
 * sport-specific event details are a JSON object with a `kind` discriminator.
 */

public const val FEED_VERSION: String = "1"

/** JSON configuration for the feed: nulls are explicit, defaults are written. */
public val FeedJson: Json = Json {
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = true
    prettyPrint = false
}

@Serializable
public data class FeedLeague(
    val id: String,
    val sport: String,
    val name: String,
    val country: String?,
    /**
     * IANA zone this league keeps its calendar in: the one `date` on `/v1/games` is read in,
     * and the one a game's `startTime` has to be converted to to get the day it is filed under.
     * A client that uses its own zone instead asks for days the league has no such game on.
     */
    val zone: String,
    val websiteUrl: String?,
    val capabilities: List<String>,
)

@Serializable
public data class FeedTeamRef(
    val league: String,
    val id: String,
    val name: String,
    val abbreviation: String?,
    val logoUrl: String?,
    /** OpenScore club id, the same across leagues; null when the club is not in the crosswalk. */
    val clubId: String? = null,
)

@Serializable
public data class FeedPlayerRef(
    val league: String,
    val id: String,
    val name: String,
    val jerseyNumber: Int?,
    val position: String?,
    val headshotUrl: String?,
)

@Serializable
public data class FeedPeriod(
    val number: Int,
    /** `REGULATION`, `OVERTIME`, `SHOOTOUT`, `UNKNOWN`. */
    val type: String,
    val label: String,
)

@Serializable
public data class FeedScore(val home: Int, val away: Int)

@Serializable
public data class FeedClock(
    val period: FeedPeriod,
    val elapsedSeconds: Int?,
    val remainingSeconds: Int?,
    val running: Boolean?,
    /** Display form in the sport's convention (`12:34`, `67'`, `90'+4`), or null. */
    val label: String? = null,
)

/** One statistic for both sides; values are strings (counts, percentages, decimals). */
@Serializable
public data class FeedStatPair(val home: String, val away: String)

@Serializable
public data class FeedPeriodScore(
    val period: FeedPeriod,
    val home: Int,
    val away: Int,
)

@Serializable
public data class FeedCoordinates(val x: Double, val y: Double)

@Serializable
public data class FeedEvent(
    val id: String,
    /** Sport event key, e.g. `goal`, `penalty`, `shootout-attempt` (see docs/feed-v1.md). */
    val type: String,
    val rawType: String,
    val period: FeedPeriod,
    val elapsedSeconds: Int?,
    val remainingSeconds: Int?,
    /** Display form (`67'`, `90'+4`, `12:34`), or null. */
    val timeLabel: String? = null,
    val team: FeedTeamRef?,
    val players: List<FeedPlayerRef>,
    val score: FeedScore?,
    val coordinates: FeedCoordinates?,
    val description: String?,
    /** Sport-specific payload with a `kind` field, or null. */
    val details: JsonObject?,
    val sortOrder: Int?,
)

@Serializable
public data class FeedGame(
    val league: String,
    val id: String,
    val seasonId: String?,
    /** `PRESEASON`, `REGULAR`, `PLAYOFF`, `OTHER`, or null. */
    val stage: String?,
    /** Competition name for multi-competition providers, or null. */
    val competition: String? = null,
    /** ISO-8601 UTC, e.g. `2026-09-29T21:00:00Z`. */
    val startTime: String,
    val scheduleDate: String? = null,
    /** True when only the day is fixed; `startTime` is then midnight of that day in the league's zone. */
    val startTimeTbd: Boolean = false,
    val venue: String?,
    val home: FeedTeamRef,
    val away: FeedTeamRef,
    /** `SCHEDULED`, `PRE_GAME`, `LIVE`, `INTERMISSION`, `FINAL`, `POSTPONED`, `SUSPENDED`, `CANCELLED`, `UNKNOWN`. */
    val state: String,
    val score: FeedScore?,
    val clock: FeedClock?,
    /** Sport-specific live state with a `kind` field (baseball: inning half, outs, count, runners), or null. */
    val situation: JsonObject? = null,
    val periodScores: List<FeedPeriodScore>,
    /** `REGULATION`, `OVERTIME`, `SHOOTOUT`, or null. */
    val ending: String?,
    /** null = not included in this response; [] = included and none yet. */
    val events: List<FeedEvent>?,
    /** Per-team match statistics keyed by stat name (`possession`, `shots`, `xg` …); see docs/feed-v1.md. */
    val stats: Map<String, FeedStatPair> = emptyMap(),
    val rawState: String?,
)

@Serializable
public data class FeedError(
    val league: String?,
    /** `not_found`, `unsupported`, `upstream`, `unknown_league`, `bad_request`, `internal`. */
    val code: String,
    val message: String,
)

@Serializable
public data class FeedGamesResponse(
    val date: String,
    val leagues: List<String>,
    val games: List<FeedGame>,
    /** Leagues that failed are reported here instead of failing the whole response. */
    val errors: List<FeedError>,
)

@Serializable
public data class FeedErrorResponse(val error: FeedError)

@Serializable
public data class FeedLineupGroup(
    val kind: String,
    val label: String,
    val players: List<FeedPlayerRef>,
)

@Serializable
public data class FeedLineup(
    val gameId: String,
    val team: FeedTeamRef,
    val groups: List<FeedLineupGroup>,
    val headCoach: String?,
    /** Football formation digits (`433`), or null. */
    val formation: String? = null,
)

@Serializable
public data class FeedStandingsRow(
    val team: FeedTeamRef,
    val rank: Int,
    val played: Int,
    val wins: Int,
    val losses: Int,
    val draws: Int?,
    val otherLosses: Int?,
    val points: Int,
    val goalsFor: Int?,
    val goalsAgainst: Int?,
    val goalDifference: Int?,
    val extra: Map<String, String>,
)

@Serializable
public data class FeedStandingsGroup(val label: String, val rows: List<FeedStandingsRow>)

@Serializable
public data class FeedStandings(
    val league: String,
    val seasonId: String?,
    val stage: String?,
    val grouping: String,
    val groups: List<FeedStandingsGroup>,
)

@Serializable
public data class FeedTeam(
    val league: String,
    val id: String,
    val name: String,
    val abbreviation: String?,
    val logoUrl: String?,
    val logoDarkUrl: String?,
    val placeName: String?,
    val commonName: String?,
    val arena: String?,
    val conference: String?,
    val division: String?,
    val country: String? = null,
    /** OpenScore club id, the same across leagues; null when the club is not in the crosswalk. */
    val clubId: String? = null,
)

@Serializable
public data class FeedPlayer(
    val league: String,
    val id: String,
    val name: String,
    val firstName: String?,
    val lastName: String?,
    val jerseyNumber: Int?,
    val position: String?,
    val headshotUrl: String?,
    /** ISO date. */
    val birthDate: String?,
    val birthPlace: String?,
    val nationality: String?,
    val heightCm: Int?,
    val weightKg: Int?,
    val handedness: String?,
    val teamId: String?,
    val active: Boolean?,
)

/** Team season totals; values retain the sport's decimal/innings notation. */
@Serializable
public data class FeedTeamSeasonStats(
    val leagueId: String,
    val teamId: String,
    val seasonId: String,
    val groups: List<FeedTeamStatGroup>,
)

@Serializable
public data class FeedTeamStatGroup(val key: String, val label: String, val stats: List<FeedTeamStat>)

@Serializable
public data class FeedTeamStat(val key: String, val label: String, val value: String)
