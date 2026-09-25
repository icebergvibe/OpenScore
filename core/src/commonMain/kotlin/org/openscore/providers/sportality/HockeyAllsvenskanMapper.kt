package org.openscore.providers.sportality

import kotlinx.datetime.toLocalDateTime
import org.openscore.model.EventDetails
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.GameTime
import org.openscore.model.League
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
import org.openscore.model.StandingsRow
import org.openscore.model.TeamRef
import org.openscore.model.TeamStat
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.ShotDetails
import org.openscore.model.hockey.ShotOutcome
import org.openscore.model.hockey.StoppageDetails
import org.openscore.model.hockey.Strength
import org.openscore.provider.Dates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * HockeyAllsvenskan's site shapes (HockeyAllsvenskanDtos.kt) to the core model: game
 * documents and season rows, the play-by-play, the game page's lineups, the table rows, and
 * the player and squad pages. What to read and when is [HockeyAllsvenskanProvider]'s business;
 * nothing here fetches.
 */
internal class HockeyAllsvenskanMapper(private val league: League) {

    // ---- games ------------------------------------------------------------------------------

    fun game(row: HaGame): Game = with(row) {
        val start = Instant.parse(scheduledDateTime)
        val completed = isCompleted == true || endedDateTime != null
        val started = playedDateTime != null || currentPeriod != null
        val homeTotal = homeScore?.toIntOrNull()
        val awayTotal = awayScore?.toIntOrNull()
        Game(
            leagueId = league.id,
            id = slug,
            seasonId = season,
            stage = if (playOffGame != null || playOffGameLevel != null) StageKind.PLAYOFF else StageKind.REGULAR,
            // `HA` is the regular season, every game this season; only another type is worth a label.
            competition = gameType?.takeUnless { it == "HA" },
            startTime = start,
            scheduleDate = start.toLocalDateTime(league.zone).date,
            venue = venue ?: homeTeam?.teamArena,
            home = homeTeam.toRef(homeStatNetId),
            away = awayTeam.toRef(awayStatNetId),
            state = when {
                completed -> GameState.FINAL
                started -> GameState.LIVE
                else -> GameState.SCHEDULED
            },
            score = if (homeTotal != null && awayTotal != null) Score(homeTotal, awayTotal) else null,
            periodScores = periodScores(),
            ending = if (completed) when (decidedIn?.uppercase()) {
                "OT", "OVERTIME" -> GameEnding.OVERTIME
                "SO", "SHOOTOUT" -> GameEnding.SHOOTOUT
                else -> GameEnding.REGULATION
            } else null,
            rawState = when {
                completed -> decidedIn ?: "completed"
                started -> currentPeriod ?: "started"
                else -> "scheduled"
            },
        )
    }

    private fun HaGame.periodScores(): List<PeriodScore> = buildList {
        addScore(1, PeriodType.REGULATION, "1", homeScoreP1, awayScoreP1)
        addScore(2, PeriodType.REGULATION, "2", homeScoreP2, awayScoreP2)
        addScore(3, PeriodType.REGULATION, "3", homeScoreP3, awayScoreP3)
        addScore(4, PeriodType.OVERTIME, "OT", homeOtScore, awayOtScore)
        addScore(5, PeriodType.SHOOTOUT, "SO", homeSoScore, awaySoScore)
    }

    private fun MutableList<PeriodScore>.addScore(number: Int, type: PeriodType, label: String, home: String?, away: String?) {
        val h = home?.toIntOrNull()
        val a = away?.toIntOrNull()
        if (h != null && a != null) add(PeriodScore(Period(number, type, label), h, a))
    }

    /**
     * The play-by-play document as core events. `time` is seconds inside the period, and the
     * period number is the block's, so a timeline can be built without a clock (the feed has
     * none: `game_info.game_time` was `{}` in every body seen, finished games included).
     */
    fun events(document: HaPlayByPlay, game: Game): List<GameEvent> = document.gameEvents.flatMapIndexed { block, period ->
        val number = period.period?.toIntOrNull() ?: (block + 1)
        val type = when {
            // No captured game has gone to a shoot-out yet, so how its block is numbered is
            // unknown; its attempts are what say it is one, not its position after overtime.
            period.events.any { it.type == SHOOTOUT_ATTEMPT } -> PeriodType.SHOOTOUT
            number > 3 -> PeriodType.OVERTIME
            else -> PeriodType.REGULATION
        }
        val model = Period(number, type, period.periodLabel ?: if (type == PeriodType.SHOOTOUT) "SO" else number.toString())
        period.events.mapIndexed { index, event -> event.toEvent(game, model, "$number-$index") }
    }

    private fun HaPbpEvent.toEvent(game: Game, period: Period, id: String): GameEvent {
        val side = game.sideOf(team?.statNetId)
        val actor = player?.toRef()
        val elapsed = time?.toIntOrNull()?.seconds
        return GameEvent(
            id = id,
            type = eventType(),
            rawType = type ?: "",
            time = GameTime(period, elapsed = elapsed, label = elapsed?.mmss()),
            team = side,
            players = listOfNotNull(actor),
            score = runningScore.toScore(),
            description = description(actor),
            details = details(actor),
            sortOrder = elapsed?.inWholeSeconds?.toInt(),
        )
    }

    /** A goalie change says which way the goalie went, as SHL's feed on the same platform family does. */
    private fun HaPbpEvent.description(actor: PlayerRef?): String? = when {
        type == "GoalkeeperEvent" && isEntering != null -> (actor?.name ?: "Goalie") + if (isEntering) " in" else " out"
        else -> eventDescription
    }

    private fun HaPbpEvent.eventType(): HockeyEventType = when (type) {
        "Goal" -> HockeyEventType.GOAL
        "Penalty", "Sent off", "Expulsion" -> HockeyEventType.PENALTY
        "Shot" -> if (eventDescription in MISSED_SHOTS) HockeyEventType.MISSED_SHOT else HockeyEventType.SHOT
        "Save" -> HockeyEventType.SHOT
        "BlockedShot" -> HockeyEventType.BLOCKED_SHOT
        "GoalkeeperEvent" -> HockeyEventType.GOALIE_CHANGE
        "Timeout" -> HockeyEventType.STOPPAGE
        SHOOTOUT_ATTEMPT -> HockeyEventType.SHOOTOUT_ATTEMPT
        else -> HockeyEventType.OTHER
    }

    private fun HaPbpEvent.details(actor: PlayerRef?): EventDetails? = when (eventType()) {
        HockeyEventType.GOAL -> GoalDetails(
            scorer = actor,
            assists = listOfNotNull(assist1?.toRef(), assist2?.toRef()),
            strength = if (isPenaltyShot == "true") Strength.PS else STRENGTHS[eventDescription],
            scorerSeasonTotal = scorerSeasonGoals?.toIntOrNull(),
        )
        HockeyEventType.PENALTY -> {
            // `2 min, Tripping`, or `Team Penalty 2 min, Too many players on the ice` when no
            // one player serves it - which is why a penalty can arrive with no player at all.
            val minutes = PENALTY_MINUTES.find(eventDescription.orEmpty())?.groupValues?.get(1)?.toIntOrNull()
            PenaltyDetails(
                player = actor,
                minutes = minutes,
                infraction = eventDescription?.substringAfter(", ", "")?.takeIf { it.isNotBlank() },
                severity = if (eventDescription?.startsWith("Team Penalty") == true) "TEAM" else null,
            )
        }
        HockeyEventType.SHOT, HockeyEventType.MISSED_SHOT -> ShotDetails(
            shooter = actor,
            outcome = if (eventDescription in MISSED_SHOTS) ShotOutcome.MISSED else ShotOutcome.ON_GOAL,
            reason = eventDescription,
        )
        HockeyEventType.STOPPAGE -> StoppageDetails(eventDescription)
        else -> null
    }

    /** `1-0`, home first, as the feed writes it beside a goal. */
    private fun String?.toScore(): Score? {
        val home = this?.substringBefore('-')?.trim()?.toIntOrNull() ?: return null
        val away = substringAfter('-', "").trim().toIntOrNull() ?: return null
        return Score(home, away)
    }

    private fun Game.sideOf(statNetId: String?): TeamRef? = when {
        statNetId == null -> null
        home.id.equals(statNetId, ignoreCase = true) -> home
        away.id.equals(statNetId, ignoreCase = true) -> away
        else -> null
    }

    private fun HaPbpPlayer.toRef(): PlayerRef = PlayerRef(
        leagueId = league.id,
        id = statNetId ?: PlayerNames.fromParts(firstName, familyName),
        name = PlayerNames.fromParts(firstName, familyName),
        jerseyNumber = jerseyNumber?.toIntOrNull(),
    )

    /** An assist carries a name and today's jersey, never an id: the feed does not join them. */
    private fun HaPbpAssist.toRef(): PlayerRef? {
        val who = name?.takeIf { it.isNotBlank() } ?: return null
        return PlayerRef(leagueId = league.id, id = who, name = who, jerseyNumber = jerseyToday?.toIntOrNull())
    }

    fun squadPlayer(row: HaSquadPlayer, teamId: String): Player = with(row) {
        Player(
            ref = PlayerRef(
                leagueId = league.id,
                // The squad row has no StatNet id (it is only inside the headshot file name); the
                // slug is what `player()` is keyed by, so a roster row links straight to a profile.
                id = slug ?: PlayerNames.fromParts(firstName, familyName),
                name = PlayerNames.fromParts(firstName, familyName),
                jerseyNumber = jerseyNumber?.toIntOrNull(),
                position = positionCode,
                headshotUrl = headshots?.small ?: headshots?.medium,
            ),
            firstName = firstName,
            lastName = familyName,
            birthDate = Dates.localDateOrNull(birthDate),
            nationality = country,
            heightCm = height?.toIntOrNull(),
            weightKg = weight?.toIntOrNull(),
            handedness = shoots,
            teamId = teamId,
        )
    }

    private fun Duration.mmss(): String = "${inWholeMinutes}:${(inWholeSeconds % 60).toString().padStart(2, '0')}"

    private fun HaTeam?.toRef(fallbackId: String): TeamRef = TeamRef(
        leagueId = league.id,
        id = fallbackId,
        name = this?.name ?: fallbackId,
        abbreviation = this?.shortName ?: fallbackId,
        logoUrl = this?.logo?.url,
    )

    // ---- lineups ----------------------------------------------------------------------------

    fun lineup(gameId: String, team: TeamRef, entries: List<HaLineupEntry>): Lineup? {
        val dressed = entries.filter { !it.isReferee && !it.isLinePerson && it.positionToday != null }
        if (dressed.isEmpty()) return null
        fun ref(e: HaLineupEntry): PlayerRef = PlayerRef(
            leagueId = league.id,
            id = e.playerStatNetId ?: e.player?.statNetId ?: e.documentId ?: "?",
            name = PlayerNames.fromParts(e.firstName, e.familyName, fallback = e.playerStatNetId ?: "?"),
            jerseyNumber = e.jerseyToday?.toIntOrNull() ?: e.player?.jerseyNumber?.toIntOrNull(),
            position = e.positionToday,
            headshotUrl = e.player?.headshots?.small ?: e.player?.headshots?.medium,
        )
        val groups = ArrayList<LineupGroup>()
        val goalies = dressed.filter { it.positionToday == "GK" }.sortedWith(compareBy({ !it.isStarting }, { it.line?.toIntOrNull() ?: 99 }))
        if (goalies.isNotEmpty()) groups += LineupGroup(LineupGroupKind.GOALIES, "Goalies", goalies.map(::ref))
        // Grouped in one pass, with each entry's line number read once rather than on every
        // comparison; `SKATER_POSITIONS` is built once rather than per skater in the predicate.
        val skaters = dressed.filter { it.positionToday != "GK" }
        val byLine = skaters.groupBy { it.line?.toIntOrNull() }
        for ((line, onLine) in byLine.entries.filter { it.key != null }.sortedBy { it.key }) {
            val forwards = onLine.filter { it.positionToday in FORWARD_POSITIONS }.sortedBy { FORWARD_POSITIONS.indexOf(it.positionToday) }
            val defence = onLine.filter { it.positionToday in DEFENCE_POSITIONS }.sortedBy { DEFENCE_POSITIONS.indexOf(it.positionToday) }
            if (forwards.isNotEmpty()) groups += LineupGroup(LineupGroupKind.LINE, "Line $line", forwards.map(::ref))
            if (defence.isNotEmpty()) groups += LineupGroup(LineupGroupKind.PAIRING, "Pairing $line", defence.map(::ref))
        }
        val rest = skaters.filter { it.line?.toIntOrNull() == null || it.positionToday !in SKATER_POSITIONS }
        if (rest.isNotEmpty()) groups += LineupGroup(LineupGroupKind.OTHER, "Other", rest.map(::ref))
        return Lineup(gameId, team, groups)
    }

    // ---- table, players, stats --------------------------------------------------------------

    /** Wins are all wins, as the other hockey tables report them; the 3-2-1-0 split is in `extra`. */
    fun standingsRow(row: HaStandingsRow, index: Int, clubs: Map<String, TeamRef>, special: Map<String, HaSpecialTeams>): StandingsRow = with(row) {
        val label = statNetClubLabel ?: teamCode
        // A club of a past season may not be in this season's snapshot (relegated, promoted), so
        // the display code stands in for an id the feed no longer gives at all.
        val known = label?.let { clubs[it.lowercase()] }
        val id = known?.id ?: teamCode ?: label ?: "?"
        val regulationWins = wins.orZero()
        val otWins = overtimeWins.orZero()
        val soWins = shootoutWins.orZero()
        val otLosses = overtimeLosses.orZero()
        val soLosses = shootoutLosses.orZero()
        StandingsRow(
            team = known ?: TeamRef(
                leagueId = league.id,
                id = id,
                name = label ?: id,
                // The CMS short name, which is also how the site's squad route spells this club.
                abbreviation = label,
            ),
            rank = rank?.toIntOrNull() ?: (index + 1),
            played = gamesPlayed.orZero(),
            wins = regulationWins + otWins + soWins,
            losses = losses.orZero(),
            otherLosses = otLosses + soLosses,
            points = totalPoints.orZero(),
            goalsFor = goals?.toIntOrNull(),
            goalsAgainst = goalsAgainst?.toIntOrNull(),
            goalDifference = goalDifference?.toIntOrNull(),
            extra = buildMap {
                put("regulationWins", regulationWins.toString())
                put("overtimeWins", otWins.toString())
                put("shootoutWins", soWins.toString())
                put("overtimeLosses", otLosses.toString())
                put("shootoutLosses", soLosses.toString())
                // The row's own two columns have been empty in every season observed; the team
                // leaderboard is where these now come from, and only for the running season.
                (powerPlayPerc?.takeIf { it.isNotBlank() } ?: special[id.lowercase()]?.powerPlay)?.let { put("powerPlay", it) }
                (penaltyKillPerc?.takeIf { it.isNotBlank() } ?: special[id.lowercase()]?.penaltyKill)?.let { put("penaltyKill", it) }
            },
        )
    }

    fun player(data: HaPlayerData, asked: String, info: HaPlayerInfo?): Player = with(data) {
        Player(
            ref = PlayerRef(
                leagueId = league.id,
                id = slug ?: asked,
                name = PlayerNames.fromParts(firstName, familyName, fallback = asked),
                jerseyNumber = jerseyNumber?.toIntOrNull(),
                position = positionCode ?: info?.position,
                headshotUrl = headshots?.small ?: headshots?.medium,
            ),
            firstName = firstName,
            lastName = familyName,
            birthDate = Dates.localDateOrNull(birthDate ?: info?.birthdate),
            nationality = country ?: info?.nationality,
            heightCm = height?.toIntOrNull() ?: info?.height,
            weightKg = weight?.toIntOrNull() ?: info?.weight,
            handedness = shoots,
            teamId = teamStatNetId,
        )
    }

    fun stat(key: String, label: String, value: String?): TeamStat? =
        value?.takeIf { it.isNotBlank() }?.let { TeamStat(key, label, it) }

    private companion object {
        val PENALTY_MINUTES = Regex("([0-9]+) min")
        const val SHOOTOUT_ATTEMPT = "ShootoutPenaltyShot"
        /** `outside` is wide, `frame hit` is the post; everything else reached the goalie. */
        val MISSED_SHOTS = setOf("outside", "frame hit")
        val STRENGTHS = mapOf(
            "EQ" to Strength.EV,
            "PP" to Strength.PP,
            "SH" to Strength.SH,
            "EN" to Strength.EN,
            "PS" to Strength.PS,
        )
        val FORWARD_POSITIONS = listOf("LW", "CE", "RW")
        val DEFENCE_POSITIONS = listOf("LD", "RD")
        /** Built once; this used to be `FORWARD_POSITIONS + DEFENCE_POSITIONS` inside a filter. */
        val SKATER_POSITIONS = FORWARD_POSITIONS + DEFENCE_POSITIONS
    }
}

/** A club's power play and penalty kill from the team leaderboard, as the site prints them. */
internal class HaSpecialTeams(val powerPlay: String?, val penaltyKill: String?)

/** The table's counting columns are strings, and a missing one means none. */
private fun String?.orZero(): Int = this?.toIntOrNull() ?: 0
