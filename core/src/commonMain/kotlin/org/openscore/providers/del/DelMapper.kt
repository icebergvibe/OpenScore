package org.openscore.providers.del

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
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
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.ShootoutAttemptDetails
import org.openscore.model.hockey.ShotDetails
import org.openscore.model.hockey.ShotOutcome
import org.openscore.model.hockey.Strength
import org.openscore.provider.Dates
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pure functions from DEL app-backend DTOs to the core model. */
public object DelMapper {

    public const val LEAGUE_ID: String = "del"

    private const val CREST_BASE = "https://www.penny-del.org/fileadmin/images/teams/2023"
    private val GAME_ID = Regex("""(\d+)t(\d+)""")
    private val ASSIST = Regex("""(\d+)\s+(.+?)\s*(?:,|$)""")

    // ---- ids -------------------------------------------------------------------------------

    /** The feed's own `uniqueID`: `4389t77` = game 4389 of tournament 77. */
    public fun gameId(gameNumber: Int, tournamentId: Int): String = "${gameNumber}t$tournamentId"

    /** `4389t77` → (4389, 77). */
    public fun parseGameId(id: String): Pair<Int, Int> {
        val m = GAME_ID.matchEntire(id.trim()) ?: throw IllegalArgumentException("DEL game id must be '<gameNumber>t<tournamentId>', got '$id'")
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /**
     * penny-del.org's crest per club, keyed by the site's own numeric team id (the API has no
     * crest route). Ingolstadt's SVG sits small on an A4 artboard and renders as a dot, so its
     * PNG next to it is used; Krefeld is the one club the site serves only as a processed PNG
     * at a hashed path.
     */
    public fun crestUrl(noc: String): String? = when (noc) {
        "ING" -> "$CREST_BASE/team_1.png"
        "MAN" -> "$CREST_BASE/team_2.svg"
        "EBB" -> "$CREST_BASE/team_3.svg"
        "KEV" -> "https://www.penny-del.org/fileadmin/_processed_/0/c/csm_team_5_a685a11e1e.png"
        "STR" -> "$CREST_BASE/team_6.svg"
        "IEC" -> "$CREST_BASE/team_7.svg"
        "WOB" -> "$CREST_BASE/team_8.svg"
        "BHV" -> "$CREST_BASE/team_9.svg"
        "KEC" -> "$CREST_BASE/team_11.svg"
        "RBM" -> "$CREST_BASE/team_12.svg"
        "AEV" -> "$CREST_BASE/team_13.svg"
        "NIT" -> "$CREST_BASE/team_14.svg"
        "SWW" -> "$CREST_BASE/team_15.svg"
        "FRA" -> "$CREST_BASE/team_44.svg"
        else -> null
    }

    /** Team codes are the ids; the name comes from `teamList` and falls back to the code. */
    public fun teamRef(noc: String, names: Map<String, String>): TeamRef =
        TeamRef(LEAGUE_ID, noc, names[noc] ?: noc, abbreviation = noc, logoUrl = crestUrl(noc))

    public fun team(t: DelTeam): Team = Team(ref = teamRef(t.noc, mapOf(t.noc to t.name)), commonName = t.name)

    // ---- periods and time --------------------------------------------------------------------

    /** `1`/`2`/`3`, `OT`, `GWS` (the feed's shoot-out key); `TOT` and anything else → null. */
    public fun period(key: String): Period? = when (key) {
        "1", "2", "3" -> Period(key.toInt(), PeriodType.REGULATION, key)
        "OT" -> Period(4, PeriodType.OVERTIME, "OT")
        "GWS" -> Period(5, PeriodType.SHOOTOUT, "SO")
        else -> null
    }

    /** Where a period starts on the feed's game-elapsed clock; playoff overtimes all count from 60:00. */
    private fun periodStart(period: Period): Int = when (period.type) {
        PeriodType.REGULATION -> (period.number - 1) * 1200
        PeriodType.OVERTIME -> 3600
        else -> 3600
    }

    /** `"21:47"` → 1307 seconds. */
    public fun gameSeconds(mmss: String): Int? {
        val parts = mmss.trim().split(':')
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val s = parts[1].toIntOrNull() ?: return null
        return m * 60 + s
    }

    /** Game-elapsed seconds → time within [period]; a shoot-out has no clock. */
    public fun gameTime(period: Period, gameSeconds: Int?): GameTime {
        if (period.type == PeriodType.SHOOTOUT || gameSeconds == null) return GameTime(period)
        return GameTime(period, elapsed = (gameSeconds - periodStart(period)).coerceAtLeast(0).seconds)
    }

    // ---- game ----------------------------------------------------------------------------------

    public fun stage(gamePhase: String?): StageKind = when (gamePhase) {
        "TAG", null, "" -> StageKind.REGULAR
        "PR", "QF", "SF", "GMG" -> StageKind.PLAYOFF
        else -> StageKind.OTHER
    }

    /** The playoff round and game, shown as the competition line; null in the regular season. */
    public fun competition(g: DelGame): String? {
        val round = when (g.gamePhase) {
            "PR" -> "Pre-playoffs"
            "QF" -> "Quarter-final"
            "SF" -> "Semi-final"
            "GMG" -> "Final"
            else -> return null
        }
        return if (g.seriesNumber > 0) "$round, game ${g.seriesNumber}" else round
    }

    /** A playoff series game that was never needed: "completed" with no period ever played. */
    public fun isUnplayed(g: DelGame): Boolean =
        g.progressPerc >= 100 && g.scoreByPeriod?.split('|')?.all { it.trim() == "-:-" } == true

    /**
     * `progressPerc` is the app's own rule (0 / 1-99 / 100); the code says where inside a live
     * game we are. The live codes (`Period 1`, `Period 1 Ended`, `Overtime`, `Game Winning
     * Shots` …) come from the app's string table and are not yet confirmed by a sample.
     */
    public fun gameState(g: DelGame): GameState {
        val code = g.progressCode.orEmpty()
        return when {
            g.deleted != 0 -> GameState.CANCELLED
            code == "Forfeit" -> GameState.FINAL
            g.progressPerc >= 100 || code == "Game Completed" -> if (isUnplayed(g)) GameState.CANCELLED else GameState.FINAL
            code.startsWith("Pre Game") -> GameState.PRE_GAME
            g.progressPerc <= 0 || code == "Scheduled" -> GameState.SCHEDULED
            code.endsWith("Ended") -> GameState.INTERMISSION
            else -> GameState.LIVE
        }
    }

    public fun ending(g: DelGame): GameEnding? = when (g.progressCodeName) {
        "OT" -> GameEnding.OVERTIME
        "GWS" -> GameEnding.SHOOTOUT
        else -> if (gameState(g) == GameState.FINAL) GameEnding.REGULATION else null
    }

    /** `"1:0|2:0|1:2|-:-|-:-"` → the periods that were played, in order. */
    public fun periodScores(scoreByPeriod: String?): List<PeriodScore> {
        if (scoreByPeriod.isNullOrBlank()) return emptyList()
        val keys = listOf("1", "2", "3", "OT", "GWS")
        return scoreByPeriod.split('|').mapIndexedNotNull { i, slot ->
            val (h, a) = slot.trim().split(':').takeIf { it.size == 2 } ?: return@mapIndexedNotNull null
            val home = h.toIntOrNull() ?: return@mapIndexedNotNull null
            val away = a.toIntOrNull() ?: return@mapIndexedNotNull null
            PeriodScore(period(keys.getOrElse(i) { return@mapIndexedNotNull null })!!, home, away)
        }
    }

    /** The period a live code names; null before the game and once it is over. */
    public fun livePeriod(progressCode: String?): Period? {
        val code = progressCode.orEmpty()
        return when {
            code.startsWith("Period ") -> code.removePrefix("Period ").take(1).let { period(it) }
            code.startsWith("Overtime") -> period("OT")
            code.startsWith("Game Winning Shots") -> period("GWS")
            else -> null
        }
    }

    private fun clock(g: DelGame, state: GameState): Clock? {
        if (!state.isLive) return null
        val period = livePeriod(g.progressCode) ?: return null
        val seconds = g.gameTime?.trim()?.toIntOrNull()
        return Clock(gameTime(period, seconds), running = if (state == GameState.INTERMISSION) false else null)
    }

    public fun game(
        g: DelGame,
        names: Map<String, String>,
        events: List<GameEvent>? = null,
        stats: Map<String, StatPair> = emptyMap(),
    ): Game {
        val state = gameState(g)
        return Game(
            leagueId = LEAGUE_ID,
            id = gameId(g.gameNumber, g.tournamentID),
            seasonId = g.tournamentID.toString(),
            stage = stage(g.gamePhase),
            competition = competition(g),
            startTime = Instant.fromEpochSeconds(g.dateTime),
            venue = g.venueName?.takeIf { it.isNotBlank() },
            home = teamRef(g.homeTeam, names),
            away = teamRef(g.guestTeam, names),
            state = state,
            score = if (state == GameState.SCHEDULED || state == GameState.PRE_GAME || state == GameState.CANCELLED) null else Score(g.homeTeamScore, g.guestTeamScore),
            clock = clock(g, state),
            periodScores = periodScores(g.scoreByPeriod),
            ending = if (state == GameState.FINAL) ending(g) else null,
            events = events,
            stats = stats,
            rawState = listOfNotNull(g.progressCode, g.progressCodeName?.takeIf { it != g.progressCode }, "${g.progressPerc}%", g.gameTime?.let { "t=$it" }).joinToString("/"),
        )
    }

    /** The `TOT` row of `gameResults` as two-sided match statistics. */
    public fun stats(results: List<DelPeriodResult>): Map<String, StatPair> {
        val tot = results.firstOrNull { it.period == "TOT" } ?: return emptyMap()
        return buildMap {
            put("shotsOnGoal", StatPair(tot.homeSog.toString(), tot.guestSog.toString()))
            put("shotAttempts", StatPair(tot.homeShotAttempts.toString(), tot.guestShotAttempts.toString()))
            put("saves", StatPair(tot.homeSsg.toString(), tot.guestSsg.toString()))
            put("faceoffsWon", StatPair(tot.homeBully.toString(), tot.guestBully.toString()))
            put("powerPlays", StatPair("${tot.homePpg}/${tot.homePpCount}", "${tot.guestPpg}/${tot.guestPpCount}"))
            if (!tot.homeTpp.isNullOrBlank() && !tot.guestTpp.isNullOrBlank()) put("powerPlayTime", StatPair(tot.homeTpp, tot.guestTpp))
            put("penaltyMinutes", StatPair(tot.homePim.toString(), tot.guestPim.toString()))
        }
    }

    // ---- players ----------------------------------------------------------------------------

    public fun playerRef(m: DelMember): PlayerRef = PlayerRef(
        leagueId = LEAGUE_ID,
        id = m.memberID.toString(),
        name = PlayerNames.fromParts(m.givenName, m.familyName, fallback = "#${m.memberID}"),
        jerseyNumber = m.jerseyNumber?.takeIf { it > 0 },
        position = m.position,
        headshotUrl = m.smallImageUrl ?: m.imageUrl,
    )

    public fun isCoach(m: DelMember): Boolean = m.position?.endsWith("COA") == true

    public fun player(m: DelMember): Player = Player(
        ref = playerRef(m),
        firstName = m.givenName.takeIf { it.isNotBlank() },
        lastName = m.familyName.takeIf { it.isNotBlank() },
        birthDate = birthDate(m),
        birthPlace = m.birthCountry?.takeIf { it.isNotBlank() },
        nationality = m.nationality?.takeIf { it.isNotBlank() },
        heightCm = m.height?.removeSuffix("m")?.toDoubleOrNull()?.let { (it * 100).toInt() },
        weightKg = m.weight?.removeSuffix("kg")?.toIntOrNull(),
        handedness = m.shoots?.takeIf { it == "L" || it == "R" },
        teamId = m.noc.takeIf { it.isNotBlank() },
    )

    /** `teamMembers` writes an ISO string, `statistics` epoch milliseconds; both mean a date. */
    public fun birthDate(m: DelMember): LocalDate? {
        val raw = m.birthday ?: return null
        raw.longOrNull?.let { millis ->
            if (millis <= 0) return null
            return Instant.fromEpochMilliseconds(millis).toString().let(Dates::localDateOrNull)
        }
        return Dates.localDateOrNull(raw.contentOrNull)
    }

    /**
     * Both rosters of a game, for turning the feed's ids, jersey numbers and family names into
     * player refs. Assists come as `"44 Davidson B"` (number, family name, initial), so they are
     * resolved by team and number; a player not on the roster keeps a synthetic id.
     */
    public class Roster(members: List<DelMember>) {
        private val byId: Map<Long, PlayerRef> = members.associate { it.memberID to playerRef(it) }
        private val byTeamAndNumber: Map<Pair<String, Int>, PlayerRef> =
            members.filter { it.jerseyNumber != null && !isCoach(it) }.associate { (it.noc to it.jerseyNumber!!) to playerRef(it) }

        public fun byId(id: Long?): PlayerRef? = id?.let { byId[it] }

        public fun ref(noc: String, id: Long?, number: Int?, name: String): PlayerRef =
            byId(id)
                ?: (if (number != null) byTeamAndNumber[noc to number] else null)
                ?: PlayerRef(LEAGUE_ID, id?.toString() ?: "$noc-${number ?: name}", name.ifBlank { "#${number ?: "?"}" }, number)

        public fun byNumber(noc: String, number: Int, name: String): PlayerRef =
            byTeamAndNumber[noc to number] ?: PlayerRef(LEAGUE_ID, "$noc-$number", name, number)

        public companion object {
            public val EMPTY: Roster = Roster(emptyList())
        }
    }

    // ---- events ----------------------------------------------------------------------------

    /** `"Assist: 44 Davidson B, 5 Reinke M"` → the two assist refs; empty when unassisted. */
    public fun assists(noc: String, actionCode3: String, roster: Roster): List<PlayerRef> {
        val text = actionCode3.substringAfter("Assist:", "").trim()
        if (text.isEmpty()) return emptyList()
        return ASSIST.findAll(text).map { m -> roster.byNumber(noc, m.groupValues[1].toInt(), m.groupValues[2].trim()) }.toList()
    }

    /** Goal strength from the comma-joined `actionCode2` (`EQ`, `PP1`, `SH2`, `EN`, `PS`, `GWS`). */
    public fun strength(codes: Set<String>): Strength? = when {
        "PS" in codes -> Strength.PS
        codes.any { it.startsWith("PP") } -> Strength.PP
        codes.any { it.startsWith("SH") } -> Strength.SH
        "EQ" in codes -> Strength.EV
        else -> null
    }

    /**
     * Goals, penalties, goalkeeper changes and shoot-out attempts from `gameSituations`; with the
     * extended rows also every shot, with rink coordinates. The deciding shoot-out `GOL` (a
     * `GWS` code at 65:00) is the shoot-out's result, not an event: the attempts follow it.
     */
    public fun events(g: DelGame, rows: List<DelSituation>, roster: Roster, names: Map<String, String> = emptyMap()): List<GameEvent> {
        val home = teamRef(g.homeTeam, names)
        val away = teamRef(g.guestTeam, names)
        fun team(noc: String): TeamRef? = when (noc) { g.homeTeam -> home; g.guestTeam -> away; else -> null }
        val out = ArrayList<GameEvent>(rows.size)
        var shootoutIndex = 0

        for (r in rows) {
            val period = period(r.period) ?: continue
            val time = gameTime(period, gameSeconds(r.time))
            val team = team(r.noc)
            val player = roster.ref(r.noc, r.playerId, r.jerseyNumber, r.playerName)
            val codes = r.actionCode2.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            val coordinates = if (r.x != null && r.y != null && (r.x != 0.0 || r.y != 0.0)) Coordinates(r.x, r.y) else null
            val id = r.uniqueID.toString()

            when {
                r.type == "G" && r.actionCode1 == "GOL" -> {
                    if ("GWS" in codes) continue
                    val assists = assists(r.noc, r.actionCode3, roster)
                    val strength = strength(codes)
                    val emptyNet = "EN" in codes
                    out += GameEvent(
                        id = "goal-$id",
                        type = HockeyEventType.GOAL,
                        rawType = "GOL" + codes.joinToString("") { ":$it" },
                        time = time,
                        team = team,
                        players = listOf(player) + assists,
                        score = if (r.homeScore != null && r.guestScore != null) Score(r.homeScore, r.guestScore) else null,
                        coordinates = coordinates,
                        description = buildString {
                            append("Goal - ").append(player.name)
                            if (assists.isNotEmpty()) append(" · ").append(assists.joinToString { it.name })
                            strength?.takeIf { it != Strength.EV }?.let { append(" [").append(it).append(']') }
                            if (emptyNet) append(" [EN]")
                        },
                        details = GoalDetails(scorer = player, assists = assists, strength = strength, emptyNet = emptyNet),
                        sortOrder = r.uniqueID.toInt(),
                    )
                }
                r.type == "P" && r.actionCode1 == "PTY" -> {
                    val minutes = gameSeconds(r.actionCode2)?.let { it / 60 }
                    out += GameEvent(
                        id = "penalty-$id",
                        type = HockeyEventType.PENALTY,
                        rawType = "PTY:${r.actionCode2}",
                        time = time,
                        team = team,
                        players = listOf(player),
                        description = listOfNotNull(player.name, minutes?.let { "$it min" }, r.actionCode3.takeIf { it.isNotBlank() }).joinToString(" - "),
                        details = PenaltyDetails(player = player, minutes = minutes, infraction = r.actionCode3.takeIf { it.isNotBlank() }),
                        sortOrder = r.uniqueID.toInt(),
                    )
                }
                r.type == "I" && r.actionCode1.startsWith("GOL_KPR_") -> {
                    val incoming = r.actionCode1 == "GOL_KPR_IN"
                    out += GameEvent(
                        id = "goalie-$id",
                        type = HockeyEventType.GOALIE_CHANGE,
                        rawType = r.actionCode1,
                        time = time,
                        team = team,
                        players = listOf(player),
                        description = (if (incoming) "Goalkeeper in - " else "Goalkeeper out - ") + player.name,
                        sortOrder = r.uniqueID.toInt(),
                    )
                }
                period.type == PeriodType.SHOOTOUT && ("SCRD" in codes || "SVD_GOL" in codes || "GOLPSMISS" in codes) -> {
                    val scored = "SCRD" in codes
                    shootoutIndex++
                    out += GameEvent(
                        id = "so-$id",
                        type = HockeyEventType.SHOOTOUT_ATTEMPT,
                        rawType = "GWS:${codes.joinToString(",")}",
                        time = GameTime(period),
                        team = team,
                        players = listOf(player),
                        description = (if (scored) "Shootout goal - " else "Shootout miss - ") + player.name,
                        details = ShootoutAttemptDetails(shooter = player, goalie = null, scored = scored),
                        sortOrder = 100_000 + shootoutIndex,
                    )
                }
                r.type == "S" && r.actionCode1 == "SHOT" -> {
                    val outcome = when (r.actionCode2) {
                        "SSG", "G" -> ShotOutcome.ON_GOAL
                        "SSP" -> ShotOutcome.BLOCKED
                        else -> ShotOutcome.MISSED
                    }
                    out += GameEvent(
                        id = "shot-$id",
                        type = when (outcome) {
                            ShotOutcome.ON_GOAL -> HockeyEventType.SHOT
                            ShotOutcome.BLOCKED -> HockeyEventType.BLOCKED_SHOT
                            ShotOutcome.MISSED -> HockeyEventType.MISSED_SHOT
                        },
                        rawType = "SHOT:${r.actionCode2}",
                        time = time,
                        team = team,
                        players = listOf(player),
                        coordinates = coordinates,
                        details = ShotDetails(shooter = player, outcome = outcome),
                        sortOrder = r.uniqueID.toInt(),
                    )
                }
                else -> out += GameEvent(
                    id = "other-$id",
                    type = HockeyEventType.OTHER,
                    rawType = listOf(r.type, r.actionCode1, r.actionCode2).filter { it.isNotBlank() }.joinToString(":"),
                    time = time,
                    team = team,
                    players = listOf(player),
                    coordinates = coordinates,
                    description = r.actionCode3.takeIf { it.isNotBlank() },
                    sortOrder = r.uniqueID.toInt(),
                )
            }
        }
        return out.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L }, { it.sortOrder ?: 0 }))
    }

    // ---- lineups -----------------------------------------------------------------------------

    /**
     * The 22 slots as goalkeepers, defence pairings and forward lines. The slot says the
     * position (1-2 defenders, 3-5 forwards); the roster supplies given names and codes.
     */
    public fun lineups(g: DelGame, slots: List<DelLineupSlot>, roster: Roster, names: Map<String, String>): List<Lineup> {
        val gameId = gameId(g.gameNumber, g.tournamentID)
        fun side(noc: String, id: (DelLineupSlot) -> Long, name: (DelLineupSlot) -> String, number: (DelLineupSlot) -> Int, image: (DelLineupSlot) -> String?): Lineup {
            fun ref(s: DelLineupSlot, position: String): PlayerRef? {
                if (id(s) == 0L && name(s).isBlank()) return null
                val fromRoster = roster.byId(id(s))
                if (fromRoster != null) return fromRoster
                // Family name first in this feed and no separator: keep the order it came in.
                return PlayerRef(LEAGUE_ID, id(s).takeIf { it != 0L }?.toString() ?: "$noc-${number(s)}", name(s), number(s).takeIf { it > 0 }, position, image(s))
            }
            val groups = ArrayList<LineupGroup>()
            val goalies = slots.filter { it.lineNumber == 0 }.sortedBy { it.linePosition }.mapNotNull { ref(it, "GK") }
            if (goalies.isNotEmpty()) groups += LineupGroup(LineupGroupKind.GOALIES, "Goalies", goalies)
            for (line in slots.map { it.lineNumber }.filter { it > 0 }.distinct().sorted()) {
                val onLine = slots.filter { it.lineNumber == line }.sortedBy { it.linePosition }
                val defense = onLine.filter { it.linePosition <= 2 }.mapNotNull { ref(it, "D") }
                val forwards = onLine.filter { it.linePosition >= 3 }.mapNotNull { ref(it, "F") }
                if (forwards.isNotEmpty()) groups += LineupGroup(LineupGroupKind.LINE, "Line $line", forwards)
                if (defense.isNotEmpty()) groups += LineupGroup(LineupGroupKind.PAIRING, "Pairing $line", defense)
            }
            return Lineup(gameId = gameId, team = teamRef(noc, names), groups = groups)
        }
        return listOf(
            side(g.homeTeam, { it.homePlayerMemberId }, { it.homePlayerName }, { it.homePlayerNumber }, { it.homePlayerSmallImageUrl }),
            side(g.guestTeam, { it.guestPlayerMemberId }, { it.guestPlayerName }, { it.guestPlayerNumber }, { it.guestPlayerSmallImageUrl }),
        )
    }

    // ---- standings -------------------------------------------------------------------------

    /** Three points for a regulation win, two after overtime or the shoot-out, one for losing there. */
    public fun standings(rows: List<DelStandingsRow>, names: Map<String, String>, seasonId: String): StandingsTable {
        val sorted = rows.sortedWith(compareBy({ it.rank }, { -it.points }))
        val out = sorted.mapIndexed { i, r ->
            StandingsRow(
                team = teamRef(r.noc, names),
                rank = if (r.rank > 0) r.rank else i + 1,
                played = r.gamesPlayed,
                wins = r.gamesWon + r.otw,
                losses = r.gamesLost,
                otherLosses = r.otl,
                points = r.points,
                goalsFor = r.goalsFor,
                goalsAgainst = r.goalsAgainst,
                goalDifference = r.goalsFor - r.goalsAgainst,
                extra = buildMap {
                    put("regulationWins", r.gamesWon.toString())
                    put("overtimeWins", r.otw.toString())
                    r.pointsPerGame?.let { put("pointsPerGame", it.toString()) }
                },
            )
        }
        return StandingsTable(LEAGUE_ID, seasonId, StageKind.REGULAR, listOf(StandingsGroup("PENNY DEL", out)), grouping = "league")
    }
}
