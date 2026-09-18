package org.openscore.providers.nhl

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
import org.openscore.model.PlayerRef
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.StandingsGroup
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.hockey.FaceoffDetails
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HitDetails
import org.openscore.model.hockey.HockeyEventType
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.ShootoutAttemptDetails
import org.openscore.model.hockey.ShotDetails
import org.openscore.model.hockey.ShotOutcome
import org.openscore.model.hockey.StoppageDetails
import org.openscore.model.hockey.Strength
import org.openscore.provider.Dates
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Pure functions from NHL DTOs to the core model. Kept separate so they are trivially testable. */
public object NhlMapper {

    private const val LEAGUE_ID = "nhl"

    // ---- periods / time --------------------------------------------------------------

    public fun period(d: NhlPeriodDescriptor): Period {
        val regulation = d.maxRegulationPeriods ?: 3
        return when (d.periodType) {
            "REG" -> Period(d.number, PeriodType.REGULATION, d.number.toString())
            "OT" -> {
                val nth = d.number - regulation
                Period(d.number, PeriodType.OVERTIME, if (nth <= 1) "OT" else "${nth}OT")
            }
            "SO" -> Period(d.number, PeriodType.SHOOTOUT, "SO")
            else -> Period(d.number, PeriodType.UNKNOWN, d.periodType)
        }
    }

    /** `mm:ss` → duration; null when malformed. */
    public fun clockDuration(text: String?): Duration? {
        if (text == null) return null
        val parts = text.split(':')
        if (parts.size != 2) return null
        val m = parts[0].toIntOrNull() ?: return null
        val s = parts[1].toIntOrNull() ?: return null
        return m.minutes + s.seconds
    }

    /** Regulation periods are 20:00; OT is 5:00 in the regular season and 20:00 in the playoffs. */
    private fun periodLength(period: Period, gameType: Int): Duration? = when (period.type) {
        PeriodType.REGULATION -> 20.minutes
        PeriodType.OVERTIME -> if (gameType == 3) 20.minutes else 5.minutes
        else -> null
    }

    private fun stage(gameType: Int): StageKind = when (gameType) {
        1 -> StageKind.PRESEASON
        2 -> StageKind.REGULAR
        3 -> StageKind.PLAYOFF
        else -> StageKind.OTHER
    }

    public fun gameState(gameState: String, scheduleState: String, clock: NhlClock?): GameState {
        when (scheduleState) {
            "PPD" -> return GameState.POSTPONED
            "SUSP" -> return GameState.SUSPENDED
            "CNCL" -> return GameState.CANCELLED
        }
        return when (gameState) {
            "FUT" -> GameState.SCHEDULED
            "PRE" -> GameState.PRE_GAME
            "LIVE", "CRIT" -> if (clock?.inIntermission == true) GameState.INTERMISSION else GameState.LIVE
            "OVER", "FINAL", "OFF" -> GameState.FINAL
            else -> GameState.UNKNOWN
        }
    }

    private fun ending(outcome: NhlGameOutcome?): GameEnding? = when (outcome?.lastPeriodType) {
        "REG" -> GameEnding.REGULATION
        "OT" -> GameEnding.OVERTIME
        "SO" -> GameEnding.SHOOTOUT
        else -> null
    }

    private fun clock(state: GameState, clock: NhlClock?, descriptor: NhlPeriodDescriptor?, gameType: Int): Clock? {
        if (!state.isLive || clock == null || descriptor == null) return null
        val period = period(descriptor)
        val remaining = clock.secondsRemaining?.seconds ?: clockDuration(clock.timeRemaining)
        val elapsed = periodLength(period, gameType)?.let { len -> remaining?.let { len - it } }
        return Clock(GameTime(period, elapsed = elapsed, remaining = remaining), running = clock.running)
    }

    // ---- teams / players ---------------------------------------------------------------

    public fun teamRef(t: NhlGameTeam): TeamRef = TeamRef(
        leagueId = LEAGUE_ID,
        id = t.abbrev,
        name = t.name?.default?.takeIf { it.isNotBlank() }
            ?: listOfNotNull(t.placeName?.default, t.commonName?.default).joinToString(" ").ifBlank { t.abbrev },
        abbreviation = t.abbrev,
        logoUrl = t.logo,
    )

    private fun playerRef(spot: NhlRosterSpot): PlayerRef = PlayerRef(
        leagueId = LEAGUE_ID,
        id = spot.playerId.toString(),
        name = "${spot.firstName.default} ${spot.lastName.default}".trim(),
        jerseyNumber = spot.sweaterNumber,
        position = spot.positionCode,
        headshotUrl = spot.headshot,
    )

    // ---- score board (/score/{date}) ---------------------------------------------------

    public fun game(g: NhlScoreGame): Game {
        val state = gameState(g.gameState, g.gameScheduleState, g.clock)
        val home = teamRef(g.homeTeam)
        val away = teamRef(g.awayTeam)
        val goals = g.goals.map { GoalTally(period(it.periodDescriptor), it.homeScore, it.awayScore) }
        val score = scoreOf(state, g.homeTeam.score, g.awayTeam.score)
        return Game(
            leagueId = LEAGUE_ID,
            id = g.id.toString(),
            seasonId = g.season.toString(),
            stage = stage(g.gameType),
            startTime = Instant.parse(g.startTimeUTC),
            venue = g.venue?.default,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(state, g.clock, g.periodDescriptor, g.gameType),
            periodScores = periodScores(goals, g.periodDescriptor?.let(::period), score, ending(g.gameOutcome)),
            ending = if (state.isFinished) ending(g.gameOutcome) else null,
            rawState = "${g.gameState}/${g.gameScheduleState}",
        )
    }

    // ---- game centre (/gamecenter/{id}/play-by-play) -----------------------------------

    public fun game(p: NhlPlayByPlay): Game {
        val state = gameState(p.gameState, p.gameScheduleState, p.clock)
        val score = scoreOf(state, p.homeTeam.score, p.awayTeam.score)
        val goals = p.plays
            .filter { it.typeDescKey == "goal" && it.periodDescriptor.periodType != "SO" }
            .mapNotNull { play ->
                val d = play.details ?: return@mapNotNull null
                GoalTally(period(play.periodDescriptor), d.homeScore ?: return@mapNotNull null, d.awayScore ?: return@mapNotNull null)
            }
        return Game(
            leagueId = LEAGUE_ID,
            id = p.id.toString(),
            seasonId = p.season.toString(),
            stage = stage(p.gameType),
            startTime = Instant.parse(p.startTimeUTC),
            venue = p.venue?.default,
            home = teamRef(p.homeTeam),
            away = teamRef(p.awayTeam),
            state = state,
            score = score,
            clock = clock(state, p.clock, p.periodDescriptor, p.gameType),
            periodScores = periodScores(goals, p.periodDescriptor?.let(::period), score, ending(p.gameOutcome)),
            ending = if (state.isFinished) ending(p.gameOutcome) else null,
            events = events(p),
            rawState = "${p.gameState}/${p.gameScheduleState}",
        )
    }

    private fun scoreOf(state: GameState, home: Int?, away: Int?): Score? =
        if (state == GameState.SCHEDULED || home == null || away == null) null else Score(home, away)

    private class GoalTally(val period: Period, val homeAfter: Int, val awayAfter: Int)

    /**
     * Per-period scores derived from the running totals on goals. Periods without goals are
     * listed as 0–0 up to the current period; a shootout is credited 1–0 to the winner.
     */
    private fun periodScores(goals: List<GoalTally>, current: Period?, finalScore: Score?, ending: GameEnding?): List<PeriodScore> {
        if (current == null || finalScore == null) return emptyList()
        val tallies = LinkedHashMap<Int, PeriodScore>()
        var prevHome = 0
        var prevAway = 0
        for (g in goals.sortedWith(compareBy({ it.period.number }, { it.homeAfter + it.awayAfter }))) {
            val existing = tallies[g.period.number] ?: PeriodScore(g.period, 0, 0)
            tallies[g.period.number] = existing.copy(
                home = existing.home + (g.homeAfter - prevHome),
                away = existing.away + (g.awayAfter - prevAway),
            )
            prevHome = g.homeAfter
            prevAway = g.awayAfter
        }
        val lastPlayed = if (current.type == PeriodType.SHOOTOUT) current.number - 1 else current.number
        val result = (1..lastPlayed).map { n ->
            tallies[n] ?: PeriodScore(periodByNumber(n, current), 0, 0)
        }.toMutableList()
        if (current.type == PeriodType.SHOOTOUT && ending == GameEnding.SHOOTOUT) {
            val homeWon = finalScore.home > finalScore.away
            result += PeriodScore(current, if (homeWon) 1 else 0, if (homeWon) 0 else 1)
        }
        return result
    }

    private fun periodByNumber(n: Int, current: Period): Period {
        val regulation = 3
        return when {
            n <= regulation -> Period(n, PeriodType.REGULATION, n.toString())
            current.type == PeriodType.SHOOTOUT && n == current.number -> current
            else -> Period(n, PeriodType.OVERTIME, if (n - regulation <= 1) "OT" else "${n - regulation}OT")
        }
    }

    public fun events(p: NhlPlayByPlay): List<GameEvent> {
        val players = p.rosterSpots.associate { it.playerId to playerRef(it) }
        val teamsById = mapOf(p.homeTeam.id to teamRef(p.homeTeam), p.awayTeam.id to teamRef(p.awayTeam))
        val homeId = p.homeTeam.id
        return p.plays.sortedBy { it.sortOrder }.map { play -> event(play, players, teamsById, homeId) }
    }

    private fun event(
        play: NhlPlay,
        players: Map<Long, PlayerRef>,
        teams: Map<Int, TeamRef>,
        homeTeamId: Int,
    ): GameEvent {
        val d = play.details
        val period = period(play.periodDescriptor)
        val remaining = clockDuration(play.timeRemaining)
        val elapsed = clockDuration(play.timeInPeriod)
        val team = d?.eventOwnerTeamId?.let(teams::get)
        val isHome = d?.eventOwnerTeamId == homeTeamId
        fun ref(id: Long?): PlayerRef? = id?.let { players[it] ?: PlayerRef(LEAGUE_ID, it.toString(), "#$it") }
        val coords = if (d?.xCoord != null && d.yCoord != null) Coordinates(d.xCoord, d.yCoord) else null
        val inShootout = period.type == PeriodType.SHOOTOUT

        var type: HockeyEventType
        var involved: List<PlayerRef?> = emptyList()
        var details: org.openscore.model.EventDetails? = null
        var description: String? = null
        var score: Score? = null

        when (play.typeDescKey) {
            "goal" -> {
                val scorer = ref(d?.scoringPlayerId)
                if (inShootout) {
                    type = HockeyEventType.SHOOTOUT_ATTEMPT
                    involved = listOf(scorer, ref(d?.goalieInNetId))
                    details = ShootoutAttemptDetails(scorer, ref(d?.goalieInNetId), scored = true, shotType = d?.shotType)
                    description = "Shootout goal — ${scorer?.name}"
                } else {
                    type = HockeyEventType.GOAL
                    val assists = listOfNotNull(ref(d?.assist1PlayerId), ref(d?.assist2PlayerId))
                    val (strength, emptyNet) = strength(play.situationCode, isHome)
                    involved = listOf(scorer) + assists
                    details = GoalDetails(
                        scorer = scorer,
                        assists = assists,
                        strength = strength,
                        emptyNet = emptyNet,
                        shotType = d?.shotType,
                        goalie = ref(d?.goalieInNetId),
                        scorerSeasonTotal = d?.scoringPlayerTotal,
                    )
                    if (d?.homeScore != null && d.awayScore != null) score = Score(d.homeScore, d.awayScore)
                    description = buildString {
                        append("Goal — ").append(scorer?.name)
                        d?.scoringPlayerTotal?.let { append(" (").append(it).append(')') }
                        if (assists.isNotEmpty()) append(" · ").append(assists.joinToString { it.name })
                        strength?.takeIf { it != Strength.EV }?.let { append(" [").append(it).append(']') }
                    }
                }
            }
            "shot-on-goal", "missed-shot", "blocked-shot" -> {
                val shooter = ref(d?.shootingPlayerId)
                if (inShootout) {
                    type = HockeyEventType.SHOOTOUT_ATTEMPT
                    involved = listOf(shooter, ref(d?.goalieInNetId))
                    details = ShootoutAttemptDetails(shooter, ref(d?.goalieInNetId), scored = false, shotType = d?.shotType)
                    description = "Shootout miss — ${shooter?.name}"
                } else {
                    val outcome = when (play.typeDescKey) {
                        "shot-on-goal" -> ShotOutcome.ON_GOAL
                        "missed-shot" -> ShotOutcome.MISSED
                        else -> ShotOutcome.BLOCKED
                    }
                    type = when (outcome) {
                        ShotOutcome.ON_GOAL -> HockeyEventType.SHOT
                        ShotOutcome.MISSED -> HockeyEventType.MISSED_SHOT
                        ShotOutcome.BLOCKED -> HockeyEventType.BLOCKED_SHOT
                    }
                    involved = listOf(shooter, ref(d?.goalieInNetId), ref(d?.blockingPlayerId))
                    details = ShotDetails(
                        shooter = shooter,
                        outcome = outcome,
                        goalie = ref(d?.goalieInNetId),
                        blockedBy = ref(d?.blockingPlayerId),
                        shotType = d?.shotType,
                        reason = d?.reason,
                    )
                }
            }
            "penalty" -> {
                type = HockeyEventType.PENALTY
                val player = ref(d?.committedByPlayerId)
                involved = listOf(player, ref(d?.drawnByPlayerId), ref(d?.servedByPlayerId))
                details = PenaltyDetails(
                    player = player,
                    drawnBy = ref(d?.drawnByPlayerId),
                    servedBy = ref(d?.servedByPlayerId),
                    minutes = d?.duration,
                    infraction = d?.descKey,
                    severity = d?.typeCode,
                )
                description = listOfNotNull(player?.name, d?.duration?.let { "$it min" }, d?.descKey).joinToString(" — ")
            }
            "delayed-penalty" -> type = HockeyEventType.DELAYED_PENALTY
            "hit" -> {
                type = HockeyEventType.HIT
                involved = listOf(ref(d?.hittingPlayerId), ref(d?.hitteePlayerId))
                details = HitDetails(ref(d?.hittingPlayerId), ref(d?.hitteePlayerId))
            }
            "faceoff" -> {
                type = HockeyEventType.FACEOFF
                involved = listOf(ref(d?.winningPlayerId), ref(d?.losingPlayerId))
                details = FaceoffDetails(ref(d?.winningPlayerId), ref(d?.losingPlayerId))
            }
            "giveaway" -> { type = HockeyEventType.GIVEAWAY; involved = listOf(ref(d?.playerId)) }
            "takeaway" -> { type = HockeyEventType.TAKEAWAY; involved = listOf(ref(d?.playerId)) }
            "stoppage" -> { type = HockeyEventType.STOPPAGE; details = StoppageDetails(d?.reason); description = d?.reason }
            "period-start" -> type = HockeyEventType.PERIOD_START
            "period-end" -> type = HockeyEventType.PERIOD_END
            "game-end" -> type = HockeyEventType.GAME_END
            else -> type = HockeyEventType.OTHER
        }

        return GameEvent(
            id = play.eventId.toString(),
            type = type,
            rawType = play.typeDescKey,
            time = GameTime(period, elapsed = elapsed, remaining = remaining),
            team = team,
            players = involved.filterNotNull(),
            score = score,
            coordinates = coords,
            description = description,
            details = details,
            sortOrder = play.sortOrder,
        )
    }

    /**
     * `situationCode` is four digits: away goalie in net, away skaters, home skaters, home
     * goalie in net (`1551` = 5-on-5, both goalies in). A pulled goalie's extra attacker is
     * not a power play, so skaters are reduced by one on a side whose net is empty.
     */
    public fun strength(situationCode: String?, scoringTeamIsHome: Boolean): Pair<Strength?, Boolean?> {
        if (situationCode == null || situationCode.length != 4 || !situationCode.all { it.isDigit() }) return null to null
        val awayGoalie = situationCode[0] == '1'
        val awaySkaters = situationCode[1].digitToInt()
        val homeSkaters = situationCode[2].digitToInt()
        val homeGoalie = situationCode[3] == '1'
        val ownGoalie = if (scoringTeamIsHome) homeGoalie else awayGoalie
        val oppGoalie = if (scoringTeamIsHome) awayGoalie else homeGoalie
        val own = (if (scoringTeamIsHome) homeSkaters else awaySkaters) - (if (ownGoalie) 0 else 1)
        val opp = (if (scoringTeamIsHome) awaySkaters else homeSkaters) - (if (oppGoalie) 0 else 1)
        val emptyNet = !oppGoalie
        val strength = when {
            own > opp -> Strength.PP
            own < opp -> Strength.SH
            else -> Strength.EV
        }
        return strength to emptyNet
    }

    // ---- boxscore -> lineups -----------------------------------------------------------

    public fun lineups(b: NhlBoxscore): List<Lineup> {
        val stats = b.playerByGameStats ?: return emptyList()
        fun side(team: NhlGameTeam, t: NhlBoxscoreTeam): Lineup {
            fun refs(list: List<NhlBoxscorePlayer>) = list.map {
                PlayerRef(LEAGUE_ID, it.playerId.toString(), it.name.default, it.sweaterNumber, it.position)
            }
            return Lineup(
                gameId = b.id.toString(),
                team = teamRef(team),
                groups = listOf(
                    LineupGroup(LineupGroupKind.FORWARDS, "Forwards", refs(t.forwards)),
                    LineupGroup(LineupGroupKind.DEFENSE, "Defense", refs(t.defense)),
                    LineupGroup(LineupGroupKind.GOALIES, "Goalies", refs(t.goalies)),
                ),
            )
        }
        return listOf(side(b.homeTeam, stats.homeTeam), side(b.awayTeam, stats.awayTeam))
    }

    // ---- standings ---------------------------------------------------------------------

    public fun team(r: NhlStandingsRow): Team = Team(
        ref = standingsTeamRef(r),
        placeName = r.placeName?.default,
        commonName = r.teamCommonName?.default,
        conference = r.conferenceName,
        division = r.divisionName,
        logoDarkUrl = r.teamLogoDark,
    )

    private fun standingsTeamRef(r: NhlStandingsRow) = TeamRef(
        leagueId = LEAGUE_ID,
        id = r.teamAbbrev.default,
        name = r.teamName.default,
        abbreviation = r.teamAbbrev.default,
        logoUrl = r.teamLogo,
    )

    public fun standings(s: NhlStandingsResponse): StandingsTable {
        val rows = s.standings
        val seasonId = rows.firstOrNull()?.seasonId?.toString()
        val groups = rows
            .groupBy { it.divisionName ?: it.conferenceName ?: "League" }
            .entries
            .sortedWith(compareBy({ it.value.first().conferenceName ?: "" }, { it.key }))
            .map { (division, members) ->
                StandingsGroup(
                    label = division,
                    rows = members.sortedBy { it.divisionSequence ?: it.leagueSequence ?: Int.MAX_VALUE }.map { r ->
                        StandingsRow(
                            team = standingsTeamRef(r),
                            rank = r.divisionSequence ?: r.leagueSequence ?: 0,
                            played = r.gamesPlayed,
                            wins = r.wins,
                            losses = r.losses,
                            draws = r.ties.takeIf { it > 0 },
                            otherLosses = r.otLosses,
                            points = r.points,
                            goalsFor = r.goalFor,
                            goalsAgainst = r.goalAgainst,
                            goalDifference = r.goalDifferential,
                            extra = buildMap {
                                r.conferenceName?.let { put("conference", it) }
                                r.conferenceSequence?.let { put("conferenceRank", it.toString()) }
                                r.leagueSequence?.let { put("leagueRank", it.toString()) }
                                r.wildcardSequence?.takeIf { it > 0 }?.let { put("wildcard", it.toString()) }
                                r.clinchIndicator?.let { put("clinch", it) }
                                r.regulationWins?.let { put("regulationWins", it.toString()) }
                                r.regulationPlusOtWins?.let { put("regulationPlusOtWins", it.toString()) }
                                if (r.streakCode != null && r.streakCount != null) put("streak", "${r.streakCode}${r.streakCount}")
                                if (r.homeWins != null) put("home", "${r.homeWins}-${r.homeLosses}-${r.homeOtLosses}")
                                if (r.roadWins != null) put("road", "${r.roadWins}-${r.roadLosses}-${r.roadOtLosses}")
                                if (r.l10Wins != null) put("last10", "${r.l10Wins}-${r.l10Losses}-${r.l10OtLosses}")
                            },
                        )
                    },
                )
            }
        return StandingsTable(
            leagueId = LEAGUE_ID,
            seasonId = seasonId,
            stage = rows.firstOrNull()?.gameTypeId?.let(::stage),
            groups = groups,
            grouping = "division",
        )
    }

    // ---- roster / player ---------------------------------------------------------------

    public fun roster(r: NhlRoster, teamId: String): List<Player> =
        (r.forwards + r.defensemen + r.goalies).map { p ->
            Player(
                ref = PlayerRef(
                    leagueId = LEAGUE_ID,
                    id = p.id.toString(),
                    name = "${p.firstName.default} ${p.lastName.default}".trim(),
                    jerseyNumber = p.sweaterNumber,
                    position = p.positionCode,
                    headshotUrl = p.headshot,
                ),
                firstName = p.firstName.default,
                lastName = p.lastName.default,
                birthDate = Dates.localDateOrNull(p.birthDate),
                birthPlace = listOfNotNull(p.birthCity?.default, p.birthStateProvince?.default).joinToString(", ").ifBlank { null },
                nationality = p.birthCountry,
                heightCm = p.heightInCentimeters,
                weightKg = p.weightInKilograms,
                handedness = p.shootsCatches,
                teamId = teamId.uppercase(),
            )
        }

    public fun player(p: NhlPlayerLanding): Player = Player(
        ref = PlayerRef(
            leagueId = LEAGUE_ID,
            id = p.playerId.toString(),
            name = "${p.firstName.default} ${p.lastName.default}".trim(),
            jerseyNumber = p.sweaterNumber,
            position = p.position,
            headshotUrl = p.headshot,
        ),
        firstName = p.firstName.default,
        lastName = p.lastName.default,
        birthDate = Dates.localDateOrNull(p.birthDate),
        birthPlace = listOfNotNull(p.birthCity?.default, p.birthStateProvince?.default).joinToString(", ").ifBlank { null },
        nationality = p.birthCountry,
        heightCm = p.heightInCentimeters,
        weightKg = p.weightInKilograms,
        handedness = p.shootsCatches,
        teamId = p.currentTeamAbbrev,
        active = p.isActive,
    )

}
