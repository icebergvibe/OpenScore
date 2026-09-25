package org.openscore.providers.ssl

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.Player
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.StatPair
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.providers.sportality.SportalityMapper
import org.openscore.providers.sportality.SptAthleteGroup
import org.openscore.providers.sportality.SptGameInfoResponse
import org.openscore.providers.sportality.SptHeaderGame
import org.openscore.providers.sportality.SptProfilePage
import org.openscore.providers.sportality.SptScheduleGame
import org.openscore.providers.sportality.SptTeam
import kotlin.time.Instant

/**
 * SSL DTOs to the core model. The platform's shared shapes (teams, athletes, the state and
 * ending vocabulary) go through [SportalityMapper]; what is mapped here is floorball's:
 * the table's regulation/overtime columns and the post-game team totals.
 *
 * Nothing here produces a clock, a period score or an event: SSL's game-day routes return
 * no floorball data (see apis/floorball/ssl/README.md), so [Game.events] stays null - "not
 * loaded" rather than "loaded, none" - and every game is the scoreboard alone.
 */
public class SslMapper(private val leagueId: String) {

    private val platform = SportalityMapper(leagueId)

    // ---- teams / players -------------------------------------------------------------------

    public fun teamRef(t: SptTeam): TeamRef = platform.teamRef(t)

    public fun team(t: SptTeam): Team = platform.team(t)

    public fun roster(groups: List<SptAthleteGroup>, teamId: String): List<Player> = platform.roster(groups, teamId)

    public fun player(p: SptProfilePage): Player = platform.player(p)

    // ---- games -----------------------------------------------------------------------------

    /**
     * A scoreboard row. `gameheader` carries no state of its own, only `played`, and an
     * unplayed row's `result` is `0` rather than absent - so a game is scheduled until the
     * flag turns, and the zeroes are never read as a score. A game that should be under way
     * is refined from `game-info` by the provider.
     */
    public fun game(h: SptHeaderGame, teamsByCode: Map<String, SptTeam>): Game {
        val state = if (h.played) GameState.FINAL else GameState.SCHEDULED
        return Game(
            leagueId = leagueId,
            id = h.uuid,
            seasonId = h.ssgtUuid,
            stage = StageKind.REGULAR,
            startTime = Instant.parse(h.startDateTime),
            scheduleDate = Instant.parse(h.startDateTime).toLocalDateTime(SWEDEN).date,
            venue = h.venue?.trim()?.ifEmpty { null },
            home = platform.teamRef(h.homeTeam, teamsByCode),
            away = platform.teamRef(h.awayTeam, teamsByCode),
            state = state,
            score = if (h.played && h.homeTeam.result != null && h.awayTeam.result != null) Score(h.homeTeam.result, h.awayTeam.result) else null,
            ending = if (h.played) ending(h.overtime, h.shootout) else null,
            rawState = "played=${h.played}",
        )
    }

    /** A season-schedule row, used for dates outside the rolling `gameheader` window. */
    public fun game(s: SptScheduleGame): Game {
        val state = platform.scheduleState(s.state)
        val home = (s.homeTeamInfo.score as? JsonPrimitive)?.intOrNull
        val away = (s.awayTeamInfo.score as? JsonPrimitive)?.intOrNull
        return Game(
            leagueId = leagueId,
            id = s.uuid,
            seasonId = s.ssgtUuid,
            stage = StageKind.REGULAR,
            startTime = Instant.parse(s.rawStartDateTime),
            scheduleDate = Instant.parse(s.rawStartDateTime).toLocalDateTime(SWEDEN).date,
            venue = s.venueInfo?.name?.trim()?.ifEmpty { null },
            home = platform.teamRef(s.homeTeamInfo),
            away = platform.teamRef(s.awayTeamInfo),
            state = state,
            score = if (state.hasStarted && home != null && away != null) Score(home, away) else null,
            ending = if (state.isFinished) ending(s.overtime, s.shootout) else null,
            rawState = s.state,
        )
    }

    /**
     * One game from `game-info`, with the post-game totals when they were asked for. This is
     * also how a due game is resolved: the detail route carries the state the scoreboard lacks.
     */
    public fun game(info: SptGameInfoResponse, totals: SslPromoStats? = null): Game {
        val gi = info.gameInfo
        val state = platform.scheduleState(gi.state)
        val home = (info.homeTeam.score as? JsonPrimitive)?.intOrNull
        val away = (info.awayTeam.score as? JsonPrimitive)?.intOrNull
        return Game(
            leagueId = leagueId,
            id = gi.gameUuid,
            seasonId = info.ssgtUuid,
            stage = StageKind.REGULAR,
            startTime = Instant.parse(gi.startDateTime),
            scheduleDate = Instant.parse(gi.startDateTime).toLocalDateTime(SWEDEN).date,
            venue = gi.arenaName?.trim()?.ifEmpty { null },
            home = platform.teamRef(info.homeTeam),
            away = platform.teamRef(info.awayTeam),
            state = state,
            score = if (state.hasStarted && home != null && away != null) Score(home, away) else null,
            ending = if (state.isFinished) ending(gi.overtime, gi.shootout) else null,
            stats = totals?.let(::stats).orEmpty(),
            rawState = gi.state,
        )
    }

    private fun ending(overtime: Boolean, shootout: Boolean): GameEnding = when {
        shootout -> GameEnding.SHOOTOUT
        overtime -> GameEnding.OVERTIME
        else -> GameEnding.REGULATION
    }

    /** `SOG`/`SVS`/`PIM` from the promo bar; `G` is dropped because the score already says it. */
    public fun stats(s: SslPromoStats): Map<String, StatPair> = buildMap {
        for (row in s.statistics) {
            val key = STAT_KEYS[row.caption] ?: continue
            val home = row.homeTeamValue ?: continue
            val away = row.awayTeamValue ?: continue
            put(key, StatPair(number(home), number(away)))
        }
    }

    private fun number(v: Double): String = if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

    // ---- standings ---------------------------------------------------------------------------

    /**
     * The one league table. A tie after regulation is `RegT` and the ones then won are `OTW`,
     * so wins are `RegW + OTW` and the point-earning losses `RegT - OTW`; the raw columns stay
     * in [StandingsRow.extra] as `regulationWins`, `overtimeWins` and `tiedAfterRegulation`,
     * beside the site's own `group` label for the rank's qualification zone.
     */
    public fun standings(s: SslStandings, seasonId: String): StandingsTable {
        val rows = s.leagueStandings.sortedBy { it.rank }.map { r ->
            val t = r.info.teamInfo
            StandingsRow(
                team = TeamRef(
                    leagueId = leagueId,
                    id = t.teamUuid,
                    name = t.teamNames.long ?: t.teamNames.short ?: r.info.code ?: t.teamUuid,
                    abbreviation = t.teamNames.code,
                    logoUrl = t.teamMedia,
                ),
                rank = r.rank,
                played = r.gp,
                wins = r.regW + r.otw,
                losses = r.regL,
                otherLosses = (r.regT - r.otw).coerceAtLeast(0),
                points = r.points,
                goalsFor = r.g,
                goalsAgainst = r.ga,
                goalDifference = r.diff,
                extra = buildMap {
                    put("regulationWins", r.regW.toString())
                    put("overtimeWins", r.otw.toString())
                    put("tiedAfterRegulation", r.regT.toString())
                    s.groupings.firstOrNull { r.rank in it.first..it.last }?.let { put("group", it.description) }
                },
            )
        }
        return StandingsTable(leagueId, seasonId, StageKind.REGULAR, listOf(StandingsGroup("League", rows)), grouping = "league")
    }

    private companion object {
        /** The league files its days by Swedish local dates, whatever zone the reader is in. */
        val SWEDEN: TimeZone = SslProvider.LEAGUE.zone
        val STAT_KEYS = mapOf("SOG" to "shotsOnGoal", "SVS" to "saves", "PIM" to "penaltyMinutes")
    }
}
