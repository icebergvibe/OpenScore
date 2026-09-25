package org.openscore.providers.fliiga

import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import org.openscore.model.Clock
import org.openscore.model.Coordinates
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.GameTime
import org.openscore.model.Lineup
import org.openscore.model.LineupGroup
import org.openscore.model.LineupGroupKind
import org.openscore.model.Period
import org.openscore.model.PeriodScore
import org.openscore.model.PeriodType
import org.openscore.model.Player
import org.openscore.model.PlayerNames
import org.openscore.model.PlayerRef
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.StatPair
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.floorball.FaceoffDetails
import org.openscore.model.floorball.FloorballEventType
import org.openscore.model.floorball.GoalDetails
import org.openscore.model.floorball.PenaltyDetails
import org.openscore.model.floorball.ShotDetails
import org.openscore.model.floorball.ShotOutcome
import org.openscore.provider.Dates
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One team as the site knows it, from the table row joined to its WordPress record. The club
 * id is the identity OpenScore keeps: a season-team id changes with each year's registration.
 */
public data class FlTeamRecord(
    val clubId: String,
    val name: String,
    val wpId: Int? = null,
    val seasonTeamId: String? = null,
    val logoUrl: String? = null,
    val logoDarkUrl: String? = null,
    val venue: String? = null,
)

/**
 * The season's teams under every key the feeds hand out: the table gives club ids, names,
 * crests and WordPress ids, and `joukkueet` adds the season-team id the match documents use.
 * A lookup that misses returns null and the caller keeps the feed's own name, so a missing
 * bridge degrades a team's identity rather than failing the read.
 */
public class FlTeamIndex(public val teams: List<FlTeamRecord>) {
    private val byClub = teams.associateBy { it.clubId }
    private val byWp = teams.mapNotNull { t -> t.wpId?.let { it to t } }.toMap()
    private val bySeasonTeam = teams.mapNotNull { t -> t.seasonTeamId?.let { it to t } }.toMap()
    private val byDisplayName = teams.associateBy { it.name.lowercase() }

    public fun byWpId(id: Int?): FlTeamRecord? = id?.let { byWp[it] }
    public fun bySeasonTeamId(id: String?): FlTeamRecord? = id?.let { bySeasonTeam[it] }
    public fun byName(name: String?): FlTeamRecord? = name?.let { byDisplayName[it.lowercase()] }

    /** Resolves the public team id, and the two feed-local ids a caller may still be holding. */
    public fun byId(id: String): FlTeamRecord? = byClub[id] ?: bySeasonTeam[id] ?: byWp[id.toIntOrNull() ?: -1]

    public companion object {
        public fun from(table: List<FlStandingsRow>, wpTeams: List<FlWpTeam>): FlTeamIndex {
            val seasonTeamByWp = wpTeams.mapNotNull { t -> t.meta.seasonTeamId?.let { t.id to it } }.toMap()
            return FlTeamIndex(
                table.mapNotNull { row ->
                    val clubId = row.club?.id?.ifBlank { null } ?: return@mapNotNull null
                    FlTeamRecord(
                        clubId = clubId,
                        name = row.clubName?.ifBlank { null } ?: row.club.name,
                        wpId = row.wpId,
                        seasonTeamId = seasonTeamByWp[row.wpId],
                        logoUrl = row.logos?.default?.wp ?: row.logos?.default?.source,
                        logoDarkUrl = row.logos?.darkBg?.wp ?: row.logos?.darkBg?.source,
                        venue = row.venue?.ifBlank { null },
                    )
                },
            )
        }
    }
}

/**
 * F-Liiga's TorneoPal-shaped documents to the core model.
 *
 * Time: a row's `time` is cumulative match time as the scoreboard shows it and `time_sec` is
 * the elapsed seconds inside the period, so both are kept. Periods are 1–3 plus 4 for
 * overtime; the match's own start and end rows carry pseudo-periods and are pinned to the
 * first and last real one instead.
 */
public class FliigaMapper(private val leagueId: String) {

    // ---- time --------------------------------------------------------------------------------

    public fun period(raw: Int): Period = when {
        raw in 1..3 -> Period(raw, PeriodType.REGULATION, raw.toString())
        raw >= 4 -> Period(raw, PeriodType.OVERTIME, if (raw == 4) "OT" else "${raw - 3}OT")
        else -> Period(1, PeriodType.REGULATION, "1")
    }

    private fun periodLength(p: Period) = if (p.type == PeriodType.REGULATION) PERIOD_LENGTH else null

    // ---- teams -------------------------------------------------------------------------------

    public fun teamRef(t: FlTeamRecord): TeamRef = TeamRef(
        leagueId = leagueId,
        id = t.clubId,
        name = t.name,
        abbreviation = null,
        logoUrl = t.logoUrl,
    )

    /**
     * A team the index could not place: the feed's own season-team id becomes the id, which
     * keeps the game readable but will not match the club id elsewhere.
     */
    private fun teamRef(id: String, name: String, logo: String? = null): TeamRef =
        TeamRef(leagueId = leagueId, id = id, name = name, logoUrl = logo)

    public fun team(t: FlTeamRecord): Team = Team(
        ref = teamRef(t),
        arena = t.venue,
        logoDarkUrl = t.logoDarkUrl,
        country = "FI",
    )

    // ---- games -------------------------------------------------------------------------------

    /**
     * A schedule row. The WordPress record's goals are written after the match, sometimes hours
     * after it ends, so they are read as a result only once they say something; a started match
     * that still shows nothing is resolved from [game] with the compact live card.
     */
    public fun game(m: FlWpMatch, index: FlTeamIndex, now: Instant): Game? {
        val meta = m.meta
        val id = meta.torneopalId?.ifBlank { null } ?: return null
        val start = Dates.instantOrNull(meta.startTime) ?: return null
        val settled = (meta.homeGoals ?: 0) > 0 || (meta.awayGoals ?: 0) > 0 || (meta.attendance ?: 0) > 0
        val started = start <= now
        return Game(
            leagueId = leagueId,
            id = id,
            seasonId = meta.season,
            stage = stage(meta.group),
            startTime = start,
            scheduleDate = start.toLocalDateTime(HELSINKI).date,
            home = index.byWpId(meta.homeWpId)?.let(::teamRef)
                ?: teamRef(meta.homeSeasonTeamId.orEmpty(), meta.homeSeasonTeamId.orEmpty()),
            away = index.byWpId(meta.awayWpId)?.let(::teamRef)
                ?: teamRef(meta.awaySeasonTeamId.orEmpty(), meta.awaySeasonTeamId.orEmpty()),
            state = if (started && settled) GameState.FINAL else GameState.SCHEDULED,
            score = if (started && settled) Score(meta.homeGoals ?: 0, meta.awayGoals ?: 0) else null,
            // Nothing in the record says whether a win came in overtime; `game()` reads that off the periods.
            ending = null,
            rawState = if (settled) "recorded" else "unrecorded",
        )
    }

    /** The compact live card, laid over the schedule row it belongs to. */
    public fun merge(row: Game, s: FlSummary): Game {
        val state = state(s.status) ?: return row
        val home = (s.homeGoals as? JsonPrimitive)?.intOrNull
        val away = (s.awayGoals as? JsonPrimitive)?.intOrNull
        return row.copy(
            state = state,
            score = if (state.hasStarted && home != null && away != null) Score(home, away) else row.score,
            clock = liveClock(state, s.liveMinutes),
            venue = s.venue?.ifBlank { null } ?: row.venue,
            rawState = s.status,
        )
    }

    /** The full match document: result, period scores, how it ended, and the events when asked for. */
    public fun game(m: FlMatch, index: FlTeamIndex, events: List<GameEvent>? = null): Game {
        val home = side(m.homeTeam, index)
        val away = side(m.awayTeam, index)
        val state = state(m.status) ?: GameState.UNKNOWN
        val score = m.result?.let { r ->
            if (state.hasStarted && r.homeGoals != null && r.awayGoals != null) Score(r.homeGoals, r.awayGoals) else null
        }
        val periods = periodScores(m.result, score)
        return Game(
            leagueId = leagueId,
            id = m.id,
            seasonId = m.season?.name ?: m.season?.id,
            stage = stage(m.groupName),
            startTime = Dates.instantOrNull(m.startTime)
                ?: Dates.localDateOrNull(m.date)?.atStartOfDayIn(HELSINKI)
                ?: Instant.DISTANT_PAST,
            scheduleDate = Dates.localDateOrNull(m.date),
            venue = m.venue?.ifBlank { null },
            home = home,
            away = away,
            state = state,
            score = score,
            clock = liveClock(state, m.liveMinutes),
            periodScores = periods,
            ending = if (state.isFinished) ending(m, periods) else null,
            events = events,
            stats = if (state.hasStarted) stats(m) else emptyMap(),
            rawState = m.status,
        )
    }

    private fun side(t: FlMatchTeam?, index: FlTeamIndex): TeamRef {
        val team = t ?: return teamRef("", "")
        val record = index.bySeasonTeamId(team.id) ?: index.byName(team.name)
        return record?.let(::teamRef) ?: teamRef(team.id, team.name, team.logo?.source)
    }

    /**
     * `Fixture`/`Played` are what a captured response has ever shown; `Live`, `Break` and
     * `Penalties` are the values the site's own script switches on, mapped here so a live game
     * is not mistaken for a scheduled one. They have not been seen on the wire yet, which is
     * why the provider claims no live capability.
     */
    public fun state(raw: String?): GameState? = when (raw?.lowercase()) {
        "fixture" -> GameState.SCHEDULED
        "live" -> GameState.LIVE
        "break" -> GameState.INTERMISSION
        "penalties" -> GameState.LIVE
        "played" -> GameState.FINAL
        else -> null
    }

    private fun stage(group: String?): StageKind = when {
        group == null -> StageKind.REGULAR
        group.contains("runkosarja", ignoreCase = true) -> StageKind.REGULAR
        group.contains("playoff", ignoreCase = true) || group.contains("pudotuspel", ignoreCase = true) -> StageKind.PLAYOFF
        else -> StageKind.OTHER
    }

    /** Minutes played across the match; the period it falls in and the elapsed time inside it. */
    private fun liveClock(state: GameState, liveMinutes: Int?): Clock? {
        if (!state.isLive || liveMinutes == null) return null
        val raw = (liveMinutes / PERIOD_MINUTES) + 1
        val p = period(raw.coerceAtMost(4))
        val elapsed = (liveMinutes - (p.number - 1) * PERIOD_MINUTES).coerceAtLeast(0).minutes
        val len = periodLength(p)
        return Clock(
            GameTime(p, elapsed = elapsed, remaining = len?.let { if (elapsed <= it) it - elapsed else null }, label = "$liveMinutes'"),
            running = null,
        )
    }

    /**
     * `p1`–`p3` are the periods as played. The `overtime` entry is the score *entering*
     * overtime, not the overtime period's own, so the extra period is the final score minus
     * what regulation produced.
     */
    public fun periodScores(result: FlResult?, score: Score?): List<PeriodScore> {
        val raw = result?.periodScores ?: return emptyList()
        val regulation = (1..3).mapNotNull { n -> raw["p$n"]?.let { PeriodScore(period(n), it.home, it.away) } }
        if (regulation.isEmpty()) return emptyList()
        if (!raw.containsKey(OVERTIME_KEY) || score == null) return regulation
        val extra = PeriodScore(
            period = period(4),
            home = score.home - regulation.sumOf { it.home },
            away = score.away - regulation.sumOf { it.away },
        )
        return if (extra.home < 0 || extra.away < 0) regulation else regulation + extra
    }

    /**
     * A shoot-out is the only thing that writes a running `penalty_shootout_score_after`, so a
     * match with one was decided there; otherwise an `overtime` period score says overtime.
     */
    private fun ending(m: FlMatch, periods: List<PeriodScore>): GameEnding = when {
        m.events.any { (it.shootoutScoreAfter?.home ?: 0) > 0 || (it.shootoutScoreAfter?.away ?: 0) > 0 } -> GameEnding.SHOOTOUT
        m.result?.periodScores?.containsKey(OVERTIME_KEY) == true || periods.any { it.period.type == PeriodType.OVERTIME } -> GameEnding.OVERTIME
        else -> GameEnding.REGULATION
    }

    /** Team totals summed from the two lineups; the feed publishes no team-level object. */
    public fun stats(m: FlMatch): Map<String, StatPair> {
        val home = m.lineups?.home?.players ?: return emptyMap()
        val away = m.lineups.away?.players ?: return emptyMap()
        if (home.isEmpty() || away.isEmpty()) return emptyMap()
        fun pair(of: (FlLineupPlayer) -> Int) = StatPair(home.sumOf(of).toString(), away.sumOf(of).toString())
        return mapOf(
            "shots" to pair { it.shots },
            "blocks" to pair { it.blocks },
            "saves" to pair { it.saves },
            "penaltyMinutes" to pair { it.penaltyMinutes },
        )
    }

    // ---- events ------------------------------------------------------------------------------

    /**
     * The main events, with the low-level rows they own folded in: a goal takes its assists
     * and the shot that scored it, a shot on goal takes the save, a blocked shot takes the
     * blocker and a faceoff takes both takers. The rows that register the twenty-man squad at
     * `0:00` are not game events and are dropped, as are the possession and plus/minus rows
     * the goals already account for.
     */
    public fun events(m: FlMatch, home: TeamRef, away: TeamRef): List<GameEvent> {
        val rows = m.events
        val children = rows.filter { (it.connectedEventId ?: "0") != "0" }.groupBy { it.connectedEventId }
        val lastPeriod = rows.mapNotNull { it.period?.toIntOrNull() }.filter { it in 1..8 }.maxOrNull() ?: 1
        var order = 0
        return rows.mapNotNull { e ->
            val kind = kind(e) ?: return@mapNotNull null
            val own = children[e.id].orEmpty()
            val team = when (e.side) { HOME -> home; AWAY -> away; else -> null }
            val actor = playerRef(e)
            val period = period(
                when (kind) {
                    FloorballEventType.GAME_START -> 1
                    FloorballEventType.GAME_END -> lastPeriod
                    else -> e.period?.toIntOrNull() ?: 1
                },
            )
            GameEvent(
                id = e.id.ifBlank { "${e.code}-${e.period}-${e.timeSec}" },
                type = kind,
                rawType = e.code,
                time = GameTime(period, elapsed = e.timeSec?.seconds, label = e.time?.ifBlank { null }),
                team = team,
                players = listOfNotNull(actor) + own.filter { it.code in RELATED_PLAYERS }.mapNotNull(::playerRef),
                score = e.scoreAfter?.takeIf { kind == FloorballEventType.GOAL }?.let { Score(it.home, it.away) },
                coordinates = coordinates(e) ?: rows.shotFor(e)?.let(::coordinates),
                description = e.descriptionFi?.ifBlank { null } ?: e.codeEn?.ifBlank { null },
                details = details(kind, e, own, rows),
                sortOrder = order++,
            )
        }
    }

    /** The `laukausmaali` row a goal was scored from: same side, same period, same second. */
    private fun List<FlEvent>.shotFor(goal: FlEvent): FlEvent? {
        if (goal.code != GOAL) return null
        return firstOrNull { it.code == GOAL_SHOT && it.side == goal.side && it.period == goal.period && it.timeSec == goal.timeSec }
    }

    private fun kind(e: FlEvent): FloorballEventType? = when (e.code) {
        GOAL -> FloorballEventType.GOAL
        SHOT_ON_GOAL -> FloorballEventType.SHOT
        SHOT_WIDE -> FloorballEventType.MISSED_SHOT
        SHOT_BLOCKED -> FloorballEventType.BLOCKED_SHOT
        FACEOFF -> FloorballEventType.FACEOFF
        GOALIE_CHANGE -> FloorballEventType.GOALIE_CHANGE
        PERIOD_START -> FloorballEventType.PERIOD_START
        PERIOD_END -> FloorballEventType.PERIOD_END
        MATCH_START -> FloorballEventType.GAME_START
        MATCH_END -> FloorballEventType.GAME_END
        TIMEOUT -> FloorballEventType.TIMEOUT
        // A penalty's code names its length (`2min`, `5min`, `10min`), so the row's own class decides.
        else -> if (e.type == PENALTY_TYPE) FloorballEventType.PENALTY else null
    }

    private fun details(kind: FloorballEventType, e: FlEvent, own: List<FlEvent>, rows: List<FlEvent>) = when (kind) {
        FloorballEventType.GOAL -> GoalDetails(
            scorer = playerRef(e),
            assists = own.filter { it.code == ASSIST }.mapNotNull(::playerRef),
            goalie = rows.shotFor(e)?.let { shot -> rows.firstOrNull { it.code == CONCEDED && it.connectedEventId == shot.id } }?.let(::playerRef),
        )
        FloorballEventType.PENALTY -> PenaltyDetails(
            player = playerRef(e),
            minutes = e.code.takeWhile { it.isDigit() }.toIntOrNull(),
            infraction = e.eventDescriptionFi?.ifBlank { null } ?: e.descriptionRaw?.ifBlank { null },
            severity = e.code,
        )
        FloorballEventType.SHOT -> ShotDetails(
            shooter = playerRef(e),
            outcome = ShotOutcome.ON_GOAL,
            goalie = own.firstOrNull { it.code == SAVE }?.let(::playerRef),
        )
        FloorballEventType.MISSED_SHOT -> ShotDetails(playerRef(e), ShotOutcome.MISSED)
        FloorballEventType.BLOCKED_SHOT -> ShotDetails(
            shooter = playerRef(e),
            outcome = ShotOutcome.BLOCKED,
            blockedBy = own.firstOrNull { it.code == BLOCK }?.let(::playerRef),
        )
        FloorballEventType.FACEOFF -> FaceoffDetails(
            winner = own.firstOrNull { it.code == FACEOFF_WON }?.let(::playerRef),
            loser = own.firstOrNull { it.code == FACEOFF_LOST }?.let(::playerRef),
        )
        else -> null
    }

    /** `y,x` in TorneoPal rink units; a faceoff spot appends its zone and is not a shot location. */
    private fun coordinates(e: FlEvent): Coordinates? {
        val raw = e.location?.takeIf { it.isNotBlank() && '/' !in it } ?: return null
        val parts = raw.split(',')
        if (parts.size != 2) return null
        val y = parts[0].trim().toDoubleOrNull() ?: return null
        val x = parts[1].trim().toDoubleOrNull() ?: return null
        return Coordinates(x = x, y = y)
    }

    private fun playerRef(e: FlEvent): PlayerRef? {
        val id = e.playerId?.ifBlank { null } ?: return null
        val name = e.playerName?.ifBlank { null } ?: return null
        return PlayerRef(leagueId, id, name, jerseyNumber = e.shirtNumber?.toIntOrNull())
    }

    // ---- lineups -----------------------------------------------------------------------------

    /**
     * Floorball changes a whole five at a time, so a lineup is grouped by the line number the
     * position code carries (`KH/2` is the second line's centre) and ordered left wing, centre,
     * right wing, left back, right back. Goalkeepers form their own group.
     */
    public fun lineups(m: FlMatch, home: TeamRef, away: TeamRef): List<Lineup> = listOfNotNull(
        m.lineups?.home?.let { lineup(m.id, home, it) },
        m.lineups?.away?.let { lineup(m.id, away, it) },
    )

    private fun lineup(gameId: String, team: TeamRef, side: FlLineupSide): Lineup? {
        if (side.players.isEmpty()) return null
        val goalies = side.players.filter { positionCode(it) == GOALIE }
        val skaters = side.players.filter { positionCode(it) != GOALIE }
        val lines = skaters.groupBy { lineNumber(it) }.toSortedMap(compareBy { it ?: Int.MAX_VALUE })
        return Lineup(
            gameId = gameId,
            team = team,
            groups = buildList {
                if (goalies.isNotEmpty()) add(LineupGroup(LineupGroupKind.GOALIES, "Goalkeepers", goalies.sortedBy { lineNumber(it) }.map(::playerRef)))
                for ((line, players) in lines) {
                    val label = line?.let { "Line $it" } ?: "Other"
                    add(LineupGroup(LineupGroupKind.LINE, label, players.sortedBy { SKATER_ORDER.indexOf(positionCode(it)) }.map(::playerRef)))
                }
            },
            headCoach = side.coaches.firstOrNull { it.role?.equals(HEAD_COACH, ignoreCase = true) == true }?.name?.let(PlayerNames::display),
        )
    }

    private fun positionCode(p: FlLineupPlayer): String = p.position?.substringBefore('/')?.trim().orEmpty()

    private fun lineNumber(p: FlLineupPlayer): Int? = p.position?.substringAfter('/', "")?.trim()?.toIntOrNull()

    private fun playerRef(p: FlLineupPlayer): PlayerRef = PlayerRef(
        leagueId = leagueId,
        id = p.id,
        name = p.name,
        jerseyNumber = p.shirtNumber?.toIntOrNull(),
        position = positionCode(p).ifEmpty { null },
        headshotUrl = p.imageUrl?.ifBlank { null },
    )

    // ---- standings / players -------------------------------------------------------------------

    /**
     * The one regular-season table. Floorball pays three points for a regulation win, two for
     * one after regulation and one for a loss after regulation, so the raw split stays in
     * [StandingsRow.extra] beside the shutouts and points-per-game the site shows.
     */
    public fun standings(rows: List<FlStandingsRow>, seasonId: String): StandingsTable {
        val mapped = rows.sortedBy { it.standing }.map { r ->
            StandingsRow(
                team = TeamRef(
                    leagueId = leagueId,
                    id = r.club?.id?.ifBlank { null } ?: r.wpId.toString(),
                    name = r.clubName?.ifBlank { null } ?: r.club?.name.orEmpty(),
                    logoUrl = r.logos?.default?.wp ?: r.logos?.default?.source,
                ),
                rank = r.standing,
                played = r.games,
                wins = r.wins + r.overtimeWins,
                losses = r.loses,
                otherLosses = r.overtimeLosses,
                points = r.points,
                goalsFor = r.goalsFor,
                goalsAgainst = r.goalsAgainst,
                goalDifference = r.goalsDiff,
                extra = buildMap {
                    put("regulationWins", r.wins.toString())
                    put("overtimeWins", r.overtimeWins.toString())
                    put("shutouts", r.shutouts.toString())
                    r.pointsPerGame?.let { put("pointsPerGame", it.toString()) }
                },
            )
        }
        // The feed shouts its group name (`RUNKOSARJA`); a table heading should not.
        val label = rows.firstOrNull()?.groupName?.ifBlank { null }?.let(::titleCase) ?: "League"
        return StandingsTable(leagueId, seasonId, StageKind.REGULAR, listOf(StandingsGroup(label, mapped)), grouping = "league")
    }

    /**
     * A leaderboard row. The board names the team but carries no team id, so the club id comes
     * from [index] by the display name both responses share; a name the table does not have
     * leaves [Player.teamId] null rather than guessing.
     */
    public fun player(row: FlPlayerRow, index: FlTeamIndex): Player = Player(
        ref = PlayerRef(
            leagueId = leagueId,
            id = row.id,
            name = PlayerNames.fromParts(row.firstName, row.lastName),
            jerseyNumber = row.shirtNumber?.toIntOrNull(),
            position = row.role?.let { if (it.equals(GOALKEEPER_ROLE, ignoreCase = true)) "MV" else "KP" },
            headshotUrl = row.imageUrl?.ifBlank { null },
        ),
        firstName = row.firstName.ifBlank { null },
        lastName = row.lastName.ifBlank { null },
        birthDate = Dates.localDateOrNull(row.birthDate),
        nationality = null,
        teamId = index.byName(row.teamName)?.clubId,
        active = true,
    )

    private fun titleCase(value: String): String =
        value.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    private companion object {
        val HELSINKI: TimeZone = FliigaProvider.LEAGUE.zone
        const val PERIOD_MINUTES = 20
        val PERIOD_LENGTH = PERIOD_MINUTES.minutes

        const val HOME = "koti"
        const val AWAY = "vieras"
        const val OVERTIME_KEY = "overtime"
        const val PENALTY_TYPE = "PENALTY"
        const val HEAD_COACH = "Päävalmentaja"
        const val GOALKEEPER_ROLE = "Maalivahti"
        const val GOALIE = "MV"

        // Event codes, in the feed's Finnish.
        const val GOAL = "maali"
        const val ASSIST = "syotto"
        const val GOAL_SHOT = "laukausmaali"
        const val CONCEDED = "paastetty"
        const val SHOT_ON_GOAL = "laukaus"
        const val SAVE = "torjunta"
        const val SHOT_WIDE = "laukausohi"
        const val SHOT_BLOCKED = "laukausblokattu"
        const val BLOCK = "blokki"
        const val FACEOFF = "aloitus"
        const val FACEOFF_WON = "aloitusvoitto"
        const val FACEOFF_LOST = "aloitushavio"
        const val GOALIE_CHANGE = "mvvaihto"
        const val PERIOD_START = "jaksoalkoi"
        const val PERIOD_END = "jaksoloppui"
        const val MATCH_START = "ottelualkoi"
        const val MATCH_END = "otteluloppui"
        const val TIMEOUT = "aikalisa"

        /** The rows that name a second person in the main event: an assist, a save, a block, both faceoff takers. */
        val RELATED_PLAYERS = setOf(ASSIST, SAVE, BLOCK, FACEOFF_WON, FACEOFF_LOST)

        /** Left wing, centre, right wing, left back, right back. */
        val SKATER_ORDER = listOf("VL", "KH", "OL", "VP", "OP")
    }
}
