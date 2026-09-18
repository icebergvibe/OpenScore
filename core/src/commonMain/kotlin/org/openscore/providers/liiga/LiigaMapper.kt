package org.openscore.providers.liiga

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

/** Pure functions from Liiga DTOs to the core model. */
public object LiigaMapper {

    private const val LEAGUE_ID = "liiga"

    /** `"626537494:sport"` or `"626537494"` → `"626537494"`. Core team ids are the numeric half. */
    public fun teamId(raw: String): String = raw.substringBefore(':')

    public fun period(index: Int, category: String? = null): Period = when {
        category == "WINNING_SHOT_COMPETITION" || (category == null && index >= 5) -> Period(index, PeriodType.SHOOTOUT, "SO")
        category == "OVERTIME" || (category == null && index == 4) -> Period(index, PeriodType.OVERTIME, "OT")
        else -> Period(index, PeriodType.REGULATION, index.toString())
    }

    public fun stage(serie: String?, playOffPhase: Int): StageKind = when {
        playOffPhase > 0 || serie == "PLAYOFFS" -> StageKind.PLAYOFF
        serie == "RUNKOSARJA" -> StageKind.REGULAR
        serie == "PRACTICE" || serie == "PITSITURNAUS" -> StageKind.PRESEASON
        else -> StageKind.OTHER
    }

    /**
     * Liiga has no state enum. `started`/`ended` are the flags; while live, a clock sitting
     * exactly on the end of the current period is read as an intermission (heuristic from
     * the README — to be confirmed with a live capture).
     */
    public fun gameState(g: LiigaGame): GameState = when {
        g.ended -> GameState.FINAL
        !g.started -> GameState.SCHEDULED
        g.currentPeriod in 1..3 && g.gameTime == g.currentPeriod * 1200 -> GameState.INTERMISSION
        else -> GameState.LIVE
    }

    public fun ending(finishedType: String?): GameEnding? = when (finishedType) {
        "ENDED_DURING_REGULAR_GAME_TIME" -> GameEnding.REGULATION
        "ENDED_DURING_EXTENDED_GAME_TIME" -> GameEnding.OVERTIME
        "ENDED_DURING_WINNING_SHOT_COMPETITION" -> GameEnding.SHOOTOUT
        else -> null
    }

    public fun teamRef(t: LiigaGameTeam): TeamRef = TeamRef(
        leagueId = LEAGUE_ID,
        id = teamId(t.teamId),
        name = t.teamName,
        abbreviation = null,
        logoUrl = t.logos?.lightBg,
    )

    private fun clock(g: LiigaGame, state: GameState): Clock? {
        if (!state.isLive || g.currentPeriod < 1) return null
        val p = g.periods.firstOrNull { it.index == g.currentPeriod }
        val period = period(g.currentPeriod, p?.category)
        if (period.type == PeriodType.SHOOTOUT) return Clock(GameTime(period), running = null)
        val start = p?.startTime ?: ((g.currentPeriod - 1) * 1200)
        val end = p?.endTime?.takeIf { it > start }
        val elapsed = (g.gameTime - start).coerceAtLeast(0)
        return Clock(
            GameTime(period, elapsed = elapsed.seconds, remaining = end?.let { (it - g.gameTime).coerceAtLeast(0).seconds }),
            running = null, // the feed has no running flag
        )
    }

    private fun periodScores(g: LiigaGame): List<PeriodScore> =
        g.periods.filter { it.index in 1..g.currentPeriod }.sortedBy { it.index }.map {
            PeriodScore(period(it.index, it.category), it.homeTeamGoals, it.awayTeamGoals)
        }

    public fun game(g: LiigaGame, players: Map<Long, PlayerRef> = emptyMap(), withEvents: Boolean): Game {
        val state = gameState(g)
        return Game(
            leagueId = LEAGUE_ID,
            id = g.id.toString(),
            seasonId = g.season.toString(),
            stage = stage(g.serie, g.playOffPhase),
            startTime = Instant.parse(g.start),
            venue = g.iceRink?.name,
            home = teamRef(g.homeTeam),
            away = teamRef(g.awayTeam),
            state = state,
            score = if (state == GameState.SCHEDULED) null else Score(g.homeTeam.goals, g.awayTeam.goals),
            clock = clock(g, state),
            periodScores = periodScores(g),
            ending = if (state.isFinished) ending(g.finishedType) else null,
            events = if (withEvents) events(g, players) else null,
            rawState = listOfNotNull(g.finishedType, "started=${g.started}", "ended=${g.ended}").joinToString("/"),
        )
    }

    // ---- events ------------------------------------------------------------------------

    public fun events(g: LiigaGame, players: Map<Long, PlayerRef>): List<GameEvent> {
        val home = teamRef(g.homeTeam)
        val away = teamRef(g.awayTeam)
        fun ref(id: Long?, name: LiigaPlayerName? = null): PlayerRef? = id?.let {
            players[it] ?: PlayerRef(LEAGUE_ID, it.toString(), name?.let { n -> "${n.firstName} ${n.lastName}".trim() } ?: "#$it")
        }
        val out = ArrayList<GameEvent>()

        for ((team, side) in listOf(home to g.homeTeam, away to g.awayTeam)) {
            for (e in side.goalEvents) {
                if (e.period >= 5) continue // the deciding shootout goal; attempts come from winningShotCompetitionEvents
                val scorer = ref(e.scorerPlayerId, e.scorerPlayer)
                val assists = e.assistantPlayerIds.mapIndexedNotNull { i, id -> ref(id, e.assistantPlayers.getOrNull(i)) }
                val strength = strength(e.goalTypes)
                val emptyNet = "TM" in e.goalTypes
                out += GameEvent(
                    id = "goal-${e.eventId}",
                    type = HockeyEventType.GOAL,
                    rawType = "goal" + e.goalTypes.joinToString("") { ":$it" },
                    time = time(g, e.period, e.gameTime),
                    team = team,
                    players = listOfNotNull(scorer) + assists,
                    score = Score(e.homeTeamScore, e.awayTeamScore),
                    description = buildString {
                        append("Goal — ").append(scorer?.name ?: "?")
                        e.goalsSoFarInSeason?.let { append(" (").append(it).append(')') }
                        if (assists.isNotEmpty()) append(" · ").append(assists.joinToString { it.name })
                        strength?.takeIf { it != Strength.EV }?.let { append(" [").append(it).append(']') }
                        if (emptyNet) append(" [EN]")
                    },
                    details = GoalDetails(
                        scorer = scorer,
                        assists = assists,
                        strength = strength,
                        emptyNet = emptyNet,
                        scorerSeasonTotal = e.goalsSoFarInSeason,
                    ),
                    sortOrder = e.eventId,
                )
            }
            for (p in side.penaltyEvents) {
                val player = ref(p.playerId)
                out += GameEvent(
                    id = "penalty-${p.eventId}",
                    type = HockeyEventType.PENALTY,
                    rawType = "penalty" + (p.penaltyFaultType?.let { ":$it" } ?: ""),
                    time = time(g, p.period, p.gameTime),
                    team = team,
                    players = listOfNotNull(player, ref(p.suffererPlayerId)?.takeIf { it.id != player?.id }),
                    description = listOfNotNull(player?.name, p.penaltyMinutes?.let { "$it min" }, p.penaltyFaultName).joinToString(" — "),
                    details = PenaltyDetails(
                        player = player,
                        minutes = p.penaltyMinutes,
                        infraction = p.penaltyFaultName ?: p.penaltyFaultType,
                        severity = p.penaltyFaultType,
                    ),
                    sortOrder = p.eventId,
                )
            }
        }

        val soPeriod = period(5, "WINNING_SHOT_COMPETITION")
        for (a in g.winningShotCompetitionEvents.sortedBy { it.shotNumber }) {
            val shooter = ref(a.shootingPlayerId)
            val goalie = ref(a.blockingPlayerId)
            val team = if (teamId(a.shootingTeamId) == home.id) home else away
            out += GameEvent(
                id = "so-${a.shotNumber}",
                type = HockeyEventType.SHOOTOUT_ATTEMPT,
                rawType = "winning-shot",
                time = GameTime(soPeriod),
                team = team,
                players = listOfNotNull(shooter, goalie),
                description = (if (a.goalScored) "Shootout goal — " else "Shootout miss — ") + (shooter?.name ?: "?"),
                details = ShootoutAttemptDetails(shooter, goalie, scored = a.goalScored),
                sortOrder = 100_000 + a.shotNumber,
            )
        }

        return out.sortedWith(compareBy({ it.period.number }, { it.time.elapsed?.inWholeSeconds ?: 0L }, { it.sortOrder ?: 0 }))
    }

    /** Shots from `/shotmap` as events; merged with [events] by [LiigaProvider.events]. */
    public fun shotEvents(g: LiigaGame, shots: List<LiigaShot>, players: Map<Long, PlayerRef>): List<GameEvent> {
        val home = teamRef(g.homeTeam)
        val away = teamRef(g.awayTeam)
        fun ref(id: Long?): PlayerRef? = id?.let { players[it] ?: PlayerRef(LEAGUE_ID, it.toString(), "#$it") }
        return shots.mapIndexed { i, s ->
            val outcome = when (s.eventType) {
                "GOAL", "GOALIE_BLOCKED" -> ShotOutcome.ON_GOAL
                "PLAYER_BLOCKED" -> ShotOutcome.BLOCKED
                else -> ShotOutcome.MISSED
            }
            val type = when (s.eventType) {
                "GOAL", "GOALIE_BLOCKED" -> HockeyEventType.SHOT
                "PLAYER_BLOCKED" -> HockeyEventType.BLOCKED_SHOT
                else -> HockeyEventType.MISSED_SHOT
            }
            val shooter = ref(s.shooterId)
            GameEvent(
                id = "shot-$i",
                type = type,
                rawType = "shot:${s.eventType}",
                time = time(g, s.period, s.gameTime),
                team = if (s.shootingTeamId.toString() == home.id) home else away,
                players = listOfNotNull(shooter, ref(s.blockerId)),
                coordinates = if (s.shotX != null && s.shotY != null) Coordinates(s.shotX, s.shotY) else null,
                details = ShotDetails(
                    shooter = shooter,
                    outcome = outcome,
                    goalie = if (outcome == ShotOutcome.ON_GOAL) ref(s.blockerId) else null,
                    blockedBy = if (outcome == ShotOutcome.BLOCKED) ref(s.blockerId) else null,
                ),
                sortOrder = 50_000 + i,
            )
        }
    }

    private fun time(g: LiigaGame, periodIndex: Int, gameTime: Int): GameTime {
        val p = g.periods.firstOrNull { it.index == periodIndex }
        val period = period(periodIndex, p?.category)
        val start = p?.startTime ?: ((periodIndex - 1).coerceAtLeast(0) * 1200)
        val end = p?.endTime?.takeIf { it > start }
        return GameTime(
            period,
            elapsed = (gameTime - start).coerceAtLeast(0).seconds,
            remaining = end?.let { (it - gameTime).coerceAtLeast(0).seconds },
        )
    }

    /** Finnish goal-type codes → strength. `YV`/`YV2` power play, `AV` short-handed, `RL` penalty shot. */
    public fun strength(goalTypes: List<String>): Strength? = when {
        "RL" in goalTypes -> Strength.PS
        "YV" in goalTypes || "YV2" in goalTypes -> Strength.PP
        "AV" in goalTypes -> Strength.SH
        else -> Strength.EV
    }

    // ---- lineups ------------------------------------------------------------------------

    public fun playerRef(p: LiigaLineupPlayer): PlayerRef = PlayerRef(
        leagueId = LEAGUE_ID,
        id = p.id.toString(),
        name = "${p.firstName} ${p.lastName}".trim(),
        jerseyNumber = p.jersey,
        position = p.roleCode,
        headshotUrl = p.pictureUrl,
    )

    public fun playerIndex(d: LiigaGameDetail): Map<Long, PlayerRef> =
        (d.homeTeamPlayers + d.awayTeamPlayers).associate { it.id to playerRef(it) }

    private fun isGoalie(p: LiigaLineupPlayer) = p.role == "GOALIE" || p.roleCode == "MV"
    private fun isDefense(p: LiigaLineupPlayer) = p.role?.contains("DEFENSEMAN") == true || p.roleCode?.endsWith("P") == true

    /** Goalies, then per line: forwards as LINE, defence as PAIRING; players without a line as OTHER. */
    public fun lineup(gameId: String, team: TeamRef, players: List<LiigaLineupPlayer>): Lineup {
        val groups = ArrayList<LineupGroup>()
        groups += LineupGroup(LineupGroupKind.GOALIES, "Goalies", players.filter(::isGoalie).sortedBy { it.line ?: 99 }.map(::playerRef))
        val skaters = players.filterNot(::isGoalie)
        for (line in skaters.mapNotNull { it.line }.distinct().sorted()) {
            val onLine = skaters.filter { it.line == line }
            val forwards = onLine.filterNot(::isDefense)
            val defense = onLine.filter(::isDefense)
            if (forwards.isNotEmpty()) groups += LineupGroup(LineupGroupKind.LINE, "Line $line", forwards.map(::playerRef))
            if (defense.isNotEmpty()) groups += LineupGroup(LineupGroupKind.PAIRING, "Pairing $line", defense.map(::playerRef))
        }
        val extras = skaters.filter { it.line == null }
        if (extras.isNotEmpty()) groups += LineupGroup(LineupGroupKind.OTHER, "Extras", extras.map(::playerRef))
        return Lineup(gameId = gameId, team = team, groups = groups)
    }

    // ---- standings / team / player -------------------------------------------------------

    public fun standings(s: LiigaStandings, seasonId: String): StandingsTable {
        fun rows(list: List<LiigaStandingsRow>) = list.sortedBy { it.ranking }.map { r ->
            StandingsRow(
                team = TeamRef(LEAGUE_ID, teamId(r.teamId), r.teamName, null, r.teamLogos?.lightBg),
                rank = r.ranking,
                played = r.games,
                wins = r.wins + r.overtimeWins,
                losses = r.losses,
                otherLosses = r.overtimeLosses,
                points = r.points,
                goalsFor = r.goals,
                goalsAgainst = r.goalsAgainst,
                goalDifference = r.goals - r.goalsAgainst,
                extra = buildMap {
                    put("regulationWins", r.wins.toString())
                    put("overtimeWins", r.overtimeWins.toString())
                    s.playoffsLines.firstOrNull()?.let { put("directToPlayoffs", (r.ranking <= it).toString()) }
                },
            )
        }
        val groups = ArrayList<StandingsGroup>()
        groups += StandingsGroup("Runkosarja", rows(s.season))
        if (s.playoffs.isNotEmpty()) groups += StandingsGroup("Playoffs", rows(s.playoffs))
        return StandingsTable(LEAGUE_ID, seasonId, StageKind.REGULAR, groups, grouping = "league")
    }

    public fun team(t: LiigaTeamInfo): Team = Team(
        ref = TeamRef(LEAGUE_ID, t.id, t.name, t.short_name, t.logo),
        placeName = t.locality,
        commonName = t.name,
    )

    public fun rosterPlayer(p: LiigaLineupPlayer): Player = Player(
        ref = playerRef(p),
        firstName = p.firstName,
        lastName = p.lastName,
        birthDate = Dates.localDateOrNull(p.dateOfBirth),
        birthPlace = listOfNotNull(p.placeOfBirth, p.countryOfBirth).joinToString(", ").ifBlank { null },
        nationality = p.nationality,
        heightCm = p.height,
        weightKg = p.weight,
        handedness = p.handedness?.let { if (it.startsWith("L")) "L" else if (it.startsWith("R")) "R" else it },
        teamId = teamId(p.teamId),
        active = !p.injured,
    )

    public fun player(p: LiigaPlayerInfo): Player {
        val latest = p.teams.values.maxByOrNull { it.season }
        return Player(
            ref = PlayerRef(
                leagueId = LEAGUE_ID,
                id = p.fihaId.toString(),
                name = "${p.firstName} ${p.lastName}".trim(),
                jerseyNumber = latest?.jersey,
                position = latest?.position,
                headshotUrl = latest?.imageUrl,
            ),
            firstName = p.firstName,
            lastName = p.lastName,
            birthDate = Dates.localDateOrNull(p.dateOfBirth),
            birthPlace = listOfNotNull(p.birthLocality?.name, p.birthLocality?.country?.code).joinToString(", ").ifBlank { null },
            nationality = p.nationality?.code,
            heightCm = p.height,
            weightKg = p.weight,
            handedness = p.handedness,
            teamId = latest?.teamId?.toString(),
            active = !p.isRemoved,
        )
    }

}
