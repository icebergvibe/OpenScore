package org.openscore.providers.mlb

import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.contentOrNull
import org.openscore.model.Clock
import org.openscore.model.Coordinates
import org.openscore.model.Game
import org.openscore.model.GameCredit
import org.openscore.model.GameEnding
import org.openscore.model.GameEvent
import org.openscore.model.GamePreview
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
import org.openscore.model.StatPair
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.TeamSeasonStats
import org.openscore.model.TeamStat
import org.openscore.model.TeamStatGroup
import org.openscore.model.baseball.BaseRunningDetails
import org.openscore.model.baseball.BaseballEventType
import org.openscore.model.baseball.BaseballSituation
import org.openscore.model.baseball.BaseballSubstitutionDetails
import org.openscore.model.baseball.BattedBall
import org.openscore.model.baseball.InningHalf
import org.openscore.model.baseball.PlateAppearanceDetails
import org.openscore.model.baseball.RunnerMovement
import org.openscore.provider.Dates
import kotlin.math.roundToInt

/** Pure functions from MLB Stats API DTOs to the core model. */
public object MlbMapper {

    public const val LEAGUE_ID: String = "mlb"
    private const val DEFAULT_INNINGS = 9

    // ---- ids, images -------------------------------------------------------------------

    /** mlbstatic.com team logos; verified 2026-09-12, not part of the Stats API itself. */
    public fun logoUrl(teamId: Int): String = "https://www.mlbstatic.com/team-logos/$teamId.svg"
    public fun logoDarkUrl(teamId: Int): String = "https://www.mlbstatic.com/team-logos/team-cap-on-dark/$teamId.svg"
    public fun headshotUrl(personId: Long): String =
        "https://img.mlbstatic.com/mlb-photos/image/upload/w_213,q_auto:best/v1/people/$personId/headshot/67/current"

    /** Division names for unhydrated standings/team objects that carry only the id. */
    private val DIVISIONS = mapOf(
        200 to "American League West", 201 to "American League East", 202 to "American League Central",
        203 to "National League West", 204 to "National League East", 205 to "National League Central",
    )

    public fun teamRef(t: MlbTeam): TeamRef = TeamRef(
        leagueId = LEAGUE_ID,
        id = t.id.toString(),
        name = t.name.ifBlank { listOfNotNull(t.locationName, t.teamName).joinToString(" ") },
        abbreviation = t.abbreviation,
        logoUrl = logoUrl(t.id),
    )

    private fun playerRef(p: MlbPersonRef?, players: Map<Long, PlayerRef>): PlayerRef? {
        if (p == null) return null
        return players[p.id] ?: PlayerRef(LEAGUE_ID, p.id.toString(), p.fullName ?: "#${p.id}", headshotUrl = headshotUrl(p.id))
    }

    private fun playerRef(p: MlbPersonRef?): PlayerRef? = playerRef(p, emptyMap())

    private fun preview(teams: MlbScheduleTeams): GamePreview? {
        val home = playerRef(teams.home.probablePitcher)
        val away = playerRef(teams.away.probablePitcher)
        return if (home == null && away == null) null else GamePreview("Probable pitchers", home, away)
    }

    private fun credits(decisions: MlbDecisions?): List<GameCredit> = listOfNotNull(
        decisions?.winner?.let { playerRef(it)?.let { p -> GameCredit("Winning pitcher", p) } },
        decisions?.loser?.let { playerRef(it)?.let { p -> GameCredit("Losing pitcher", p) } },
        decisions?.save?.let { playerRef(it)?.let { p -> GameCredit("Save", p) } },
    )

    /** `gameData.players` (keyed `ID{id}`) → refs with number, position and headshot. */
    public fun playerMap(people: Map<String, MlbPerson>): Map<Long, PlayerRef> =
        people.values.associate { p ->
            p.id to PlayerRef(
                leagueId = LEAGUE_ID,
                id = p.id.toString(),
                name = p.fullName,
                jerseyNumber = p.primaryNumber?.toIntOrNull(),
                position = p.primaryPosition?.abbreviation,
                headshotUrl = headshotUrl(p.id),
            )
        }

    // ---- state, innings ------------------------------------------------------------------

    public fun stage(gameType: String): StageKind = when (gameType) {
        "S" -> StageKind.PRESEASON
        "R" -> StageKind.REGULAR
        "F", "D", "L", "W" -> StageKind.PLAYOFF
        else -> StageKind.OTHER
    }

    private fun inningHalf(state: String?): InningHalf? = when (state) {
        "Top" -> InningHalf.TOP
        "Middle" -> InningHalf.MIDDLE
        "Bottom" -> InningHalf.BOTTOM
        "End" -> InningHalf.END
        else -> null
    }

    public fun gameState(status: MlbStatus, linescore: MlbLinescore?): GameState = when (status.codedGameState) {
        "S" -> GameState.SCHEDULED
        "P" -> GameState.PRE_GAME
        "I", "M", "N" -> when (inningHalf(linescore?.inningState)) {
            InningHalf.MIDDLE, InningHalf.END -> GameState.INTERMISSION
            else -> GameState.LIVE
        }
        "T", "U" -> GameState.SUSPENDED
        "O", "F", "Q", "R" -> GameState.FINAL
        "D" -> GameState.POSTPONED
        "C" -> GameState.CANCELLED
        else -> GameState.UNKNOWN
    }

    /** Innings are the periods; anything past the scheduled nine is extra innings. */
    public fun period(inning: Int, scheduledInnings: Int?): Period {
        val regulation = scheduledInnings ?: DEFAULT_INNINGS
        return Period(inning, if (inning <= regulation) PeriodType.REGULATION else PeriodType.OVERTIME, inning.toString())
    }

    private fun halfLabel(half: InningHalf, inning: Int): String = when (half) {
        InningHalf.TOP -> "Top $inning"
        InningHalf.MIDDLE -> "Mid $inning"
        InningHalf.BOTTOM -> "Bot $inning"
        InningHalf.END -> "End $inning"
    }

    /**
     * Runs per inning. A half-inning not (yet) played has no `runs` in the feed (the home
     * ninth of a home win, the current half); it is reported as 0 here.
     */
    public fun periodScores(ls: MlbLinescore?): List<PeriodScore> {
        if (ls == null) return emptyList()
        return ls.innings.map { PeriodScore(period(it.num, ls.scheduledInnings), it.home.runs ?: 0, it.away.runs ?: 0) }
    }

    private fun ending(state: GameState, ls: MlbLinescore?): GameEnding? {
        if (state != GameState.FINAL || ls == null || ls.innings.isEmpty()) return null
        val regulation = ls.scheduledInnings ?: DEFAULT_INNINGS
        return if (ls.innings.size > regulation) GameEnding.OVERTIME else GameEnding.REGULATION
    }

    /** No time clock in baseball: the "clock" is the inning and its half, nothing else. */
    private fun clock(state: GameState, ls: MlbLinescore?): Clock? {
        if (!state.isLive || ls == null) return null
        val inning = ls.currentInning ?: return null
        val half = inningHalf(ls.inningState) ?: return null
        return Clock(GameTime(period(inning, ls.scheduledInnings), label = halfLabel(half, inning)), running = null)
    }

    public fun situation(state: GameState, ls: MlbLinescore?, players: Map<Long, PlayerRef> = emptyMap()): BaseballSituation? {
        if (!state.isLive || ls == null) return null
        val inning = ls.currentInning ?: return null
        val half = inningHalf(ls.inningState) ?: return null
        return BaseballSituation(
            inning = inning,
            half = half,
            outs = ls.outs ?: 0,
            balls = ls.balls ?: 0,
            strikes = ls.strikes ?: 0,
            onFirst = playerRef(ls.offense?.first, players),
            onSecond = playerRef(ls.offense?.second, players),
            onThird = playerRef(ls.offense?.third, players),
            batter = playerRef(ls.offense?.batter, players),
            pitcher = playerRef(ls.defense?.pitcher, players),
            onDeck = playerRef(ls.offense?.onDeck, players),
        )
    }

    private fun score(state: GameState, home: Int?, away: Int?): Score? = when {
        state == GameState.SCHEDULED || state == GameState.PRE_GAME || state == GameState.POSTPONED || state == GameState.CANCELLED -> null
        home == null && away == null -> null
        else -> Score(home ?: 0, away ?: 0)
    }

    /** Hits, errors and runners left on base — the H and E of an R/H/E line. Empty until there is a score. */
    private fun stats(score: Score?, ls: MlbLinescore?): Map<String, StatPair> {
        if (score == null) return emptyMap()
        val home = ls?.teams?.home ?: return emptyMap()
        val away = ls.teams.away
        return buildMap {
            if (home.hits != null && away.hits != null) put("hits", StatPair(home.hits.toString(), away.hits.toString()))
            if (home.errors != null && away.errors != null) put("errors", StatPair(home.errors.toString(), away.errors.toString()))
            if (home.leftOnBase != null && away.leftOnBase != null) put("leftOnBase", StatPair(home.leftOnBase.toString(), away.leftOnBase.toString()))
        }
    }

    private fun rawState(s: MlbStatus): String = listOfNotNull(s.statusCode, s.detailedState, s.reason).joinToString("/")

    // ---- schedule ------------------------------------------------------------------------

    /** All games of a schedule response, de-duplicated by gamePk (postponed games are listed on both dates). */
    public fun games(s: MlbSchedule): List<Game> {
        val byPk = LinkedHashMap<Long, Pair<MlbScheduleDate, MlbScheduleGame>>()
        for (date in s.dates) for (g in date.games) {
            val existing = byPk[g.gamePk]
            if (existing == null || (g.officialDate == date.date && existing.second.officialDate != existing.first.date)) byPk[g.gamePk] = date to g
        }
        return byPk.values.map { (_, g) -> game(g) }
    }

    public fun game(g: MlbScheduleGame): Game {
        val state = gameState(g.status, g.linescore)
        val ls = g.linescore
        val score = score(state, g.teams.home.score ?: ls?.teams?.home?.runs, g.teams.away.score ?: ls?.teams?.away?.runs)
        return Game(
            leagueId = LEAGUE_ID,
            id = g.gamePk.toString(),
            seasonId = g.season,
            stage = stage(g.gameType),
            startTime = Dates.instant(g.gameDate),
            scheduleDate = g.officialDate?.let { LocalDate.parse(it) },
            venue = g.venue?.name,
            home = teamRef(g.teams.home.team),
            away = teamRef(g.teams.away.team),
            state = state,
            score = score,
            clock = clock(state, ls),
            situation = situation(state, ls),
            periodScores = periodScores(ls),
            ending = ending(state, ls),
            preview = preview(g.teams),
            credits = credits(g.decisions),
            stats = stats(score, ls),
            rawState = rawState(g.status),
        )
    }

    // ---- live feed -----------------------------------------------------------------------

    public fun game(f: MlbLiveFeed): Game {
        val gd = f.gameData
        val ls = f.liveData.linescore
        val state = gameState(gd.status, ls)
        val teams = requireNotNull(gd.teams) { "feed ${f.gamePk} has no teams" }
        val home = teamRef(teams.home)
        val away = teamRef(teams.away)
        val players = playerMap(gd.players)
        val score = score(state, ls.teams.home.runs, ls.teams.away.runs)
        return Game(
            leagueId = LEAGUE_ID,
            id = f.gamePk.toString(),
            seasonId = gd.game.season,
            stage = stage(gd.game.type),
            startTime = Dates.instant(requireNotNull(gd.datetime.dateTime) { "feed ${f.gamePk} has no dateTime" }),
            venue = gd.venue?.name,
            home = home,
            away = away,
            state = state,
            score = score,
            clock = clock(state, ls),
            situation = situation(state, ls, players),
            periodScores = periodScores(ls),
            ending = ending(state, ls),
            events = events(f.liveData.plays, home, away, players, ls.scheduledInnings),
            stats = stats(score, ls),
            rawState = rawState(gd.status),
        )
    }

    public fun events(f: MlbLiveFeed): List<GameEvent> {
        val teams = requireNotNull(f.gameData.teams) { "feed ${f.gamePk} has no teams" }
        return events(f.liveData.plays, teamRef(teams.home), teamRef(teams.away), playerMap(f.gameData.players), f.liveData.linescore.scheduledInnings)
    }

    /**
     * One event per completed plate appearance, plus base-running plays, substitutions and
     * ejections that happen in between. Pitches, timeouts, mound visits and status
     * advisories are not events.
     */
    public fun events(
        plays: MlbPlays,
        home: TeamRef,
        away: TeamRef,
        players: Map<Long, PlayerRef> = emptyMap(),
        scheduledInnings: Int? = null,
    ): List<GameEvent> {
        val out = ArrayList<GameEvent>()
        // Names seen anywhere in the plays, for feeds without a player map (actions only carry the id).
        val names = HashMap<Long, String>()
        for (play in plays.allPlays) {
            play.matchup.batter?.let { p -> p.fullName?.let { names[p.id] = it } }
            play.matchup.pitcher?.let { p -> p.fullName?.let { names[p.id] = it } }
            play.runners.forEach { r -> r.details.runner?.let { p -> p.fullName?.let { names[p.id] = it } } }
        }
        fun ref(p: MlbPersonRef?, fallbackName: String? = null): PlayerRef? {
            if (p == null) return null
            return players[p.id] ?: PlayerRef(LEAGUE_ID, p.id.toString(), p.fullName ?: names[p.id] ?: fallbackName ?: "#${p.id}", headshotUrl = headshotUrl(p.id))
        }
        for (play in plays.allPlays) {
            val top = play.about.isTopInning
            val batting = if (top) away else home
            val fielding = if (top) home else away
            val period = period(play.about.inning, scheduledInnings)
            val time = GameTime(period, label = halfLabel(if (top) InningHalf.TOP else InningHalf.BOTTOM, play.about.inning))
            val base = play.atBatIndex * 100

            for (pe in play.playEvents) {
                if (pe.type != "action") continue
                val raw = pe.details.eventType ?: continue
                val type = actionType(raw) ?: continue
                val description = pe.details.description ?: ""
                val substitution = if (type == BaseballEventType.SUBSTITUTION) SUBSTITUTION.find(description) else null
                val actor = ref(pe.player, substitution?.groupValues?.get(1))
                val scoring = pe.details.isScoringPlay == true
                val details = when (type) {
                    BaseballEventType.SUBSTITUTION -> BaseballSubstitutionDetails(
                        playerIn = actor,
                        replaces = substitution?.groupValues?.get(2),
                        position = pe.position?.abbreviation,
                        kind = if (raw.endsWith("_switch")) "switch" else raw.removeSuffix("_substitution"),
                    )
                    BaseballEventType.EJECTION, BaseballEventType.OTHER -> null
                    else -> BaseRunningDetails(runner = actor, out = pe.details.isOut == true)
                }
                out += GameEvent(
                    id = "${play.atBatIndex}.${pe.index}",
                    type = type,
                    rawType = raw,
                    time = time,
                    team = if (raw.startsWith("pitching_") || raw.startsWith("defensive_") || raw == "pitcher_switch") fielding else batting,
                    players = listOfNotNull(actor),
                    score = if (scoring && pe.details.homeScore != null && pe.details.awayScore != null) Score(pe.details.homeScore, pe.details.awayScore) else null,
                    description = pe.details.description,
                    details = details,
                    sortOrder = base + pe.index,
                )
            }

            if (!play.about.isComplete || play.result.type != "atBat") continue
            val raw = play.result.eventType ?: continue
            val batter = ref(play.matchup.batter)
            val pitcher = ref(play.matchup.pitcher)
            val hit = play.playEvents.lastOrNull { it.hitData != null }?.hitData
            val scoring = play.about.isScoringPlay
            out += GameEvent(
                id = play.atBatIndex.toString(),
                type = resultType(raw),
                rawType = raw,
                time = time,
                team = batting,
                players = listOfNotNull(batter, pitcher),
                score = if (scoring && play.result.homeScore != null && play.result.awayScore != null) Score(play.result.homeScore, play.result.awayScore) else null,
                coordinates = hit?.coordinates?.let { c -> if (c.coordX != null && c.coordY != null) Coordinates(c.coordX, c.coordY) else null },
                description = play.result.description,
                details = PlateAppearanceDetails(
                    batter = batter,
                    pitcher = pitcher,
                    rbi = play.result.rbi ?: 0,
                    out = play.result.isOut == true,
                    outsAfter = play.count.outs,
                    scoringPlay = scoring,
                    pitches = play.playEvents.count { it.type == "pitch" },
                    runners = play.runners.map { r ->
                        RunnerMovement(
                            runner = ref(r.details.runner),
                            from = r.movement.start,
                            to = r.movement.end,
                            out = r.movement.isOut == true,
                        )
                    },
                    battedBall = hit?.let { BattedBall(it.launchSpeed, it.launchAngle, it.totalDistance, it.trajectory) },
                ),
                sortOrder = base + 99,
            )
        }
        return out
    }

    /** `Pitching Change: Jakob Junis replaces Jacob deGrom.` / `Defensive Substitution: Elias Díaz replaces Cam Cauley, batting 9th, …` */
    private val SUBSTITUTION = Regex("""(?:Change|Substitution|Switch): (?:Pinch[- ]hitter |Pinch[- ]runner )?(.+?) replaces (.+?)(?:,|\.$|$)""")

    /** `result.eventType` of a plate appearance → type. Full list: `GET /v1/eventTypes`. */
    public fun resultType(raw: String): BaseballEventType = when {
        raw.endsWith("triple_play") -> BaseballEventType.TRIPLE_PLAY
        raw.endsWith("double_play") -> BaseballEventType.DOUBLE_PLAY
        raw == "single" -> BaseballEventType.SINGLE
        raw == "double" -> BaseballEventType.DOUBLE
        raw == "triple" -> BaseballEventType.TRIPLE
        raw == "home_run" -> BaseballEventType.HOME_RUN
        raw == "walk" -> BaseballEventType.WALK
        raw == "intent_walk" -> BaseballEventType.INTENTIONAL_WALK
        raw == "hit_by_pitch" -> BaseballEventType.HIT_BY_PITCH
        raw == "strikeout" || raw == "strike_out" -> BaseballEventType.STRIKEOUT
        raw == "field_out" -> BaseballEventType.FIELD_OUT
        raw == "force_out" -> BaseballEventType.FORCE_OUT
        raw.startsWith("fielders_choice") -> BaseballEventType.FIELDERS_CHOICE
        raw == "sac_fly" -> BaseballEventType.SACRIFICE_FLY
        raw == "sac_bunt" -> BaseballEventType.SACRIFICE_BUNT
        raw == "field_error" || raw == "error" -> BaseballEventType.ERROR
        raw.contains("interf") -> BaseballEventType.INTERFERENCE
        raw == "other_out" -> BaseballEventType.RUNNER_OUT
        else -> BaseballEventType.OTHER
    }

    /** `playEvents[].details.eventType` of an action → type, or null for actions that are not events (timeouts, mound visits …). */
    public fun actionType(raw: String): BaseballEventType? = when {
        raw.startsWith("stolen_base") -> BaseballEventType.STOLEN_BASE
        raw.startsWith("caught_stealing") || raw.startsWith("pickoff_caught_stealing") || raw == "cs_double_play" -> BaseballEventType.CAUGHT_STEALING
        raw.startsWith("pickoff_") -> BaseballEventType.PICKOFF
        raw == "wild_pitch" -> BaseballEventType.WILD_PITCH
        raw == "passed_ball" -> BaseballEventType.PASSED_BALL
        raw == "balk" || raw == "forced_balk" -> BaseballEventType.BALK
        raw == "other_out" || raw == "runner_double_play" -> BaseballEventType.RUNNER_OUT
        raw.endsWith("_substitution") || raw.endsWith("_switch") -> BaseballEventType.SUBSTITUTION
        raw == "ejection" -> BaseballEventType.EJECTION
        raw == "injury" || raw == "runner_placed" || raw == "defensive_indiff" || raw == "other_advance" -> BaseballEventType.OTHER
        else -> null
    }

    // ---- boxscore → lineups --------------------------------------------------------------

    public fun lineups(gameId: String, b: MlbBoxscore): List<Lineup> {
        val teams = b.teams ?: return emptyList()
        if (teams.home.battingOrder.isEmpty() && teams.away.battingOrder.isEmpty()) return emptyList()
        return listOf(lineup(gameId, teams.home), lineup(gameId, teams.away))
    }

    private fun lineup(gameId: String, t: MlbBoxscoreTeam): Lineup {
        val byId = t.players.values.associateBy { it.person.id }
        fun ref(id: Long): PlayerRef {
            val p = byId[id] ?: return PlayerRef(LEAGUE_ID, id.toString(), "#$id", headshotUrl = headshotUrl(id))
            return PlayerRef(
                leagueId = LEAGUE_ID,
                id = id.toString(),
                name = p.person.fullName ?: "#$id",
                jerseyNumber = p.jerseyNumber?.toIntOrNull(),
                position = p.position?.abbreviation,
                headshotUrl = headshotUrl(id),
            )
        }
        val inOrder = t.battingOrder.toSet()
        val replaced = t.players.values
            .filter { it.battingOrder != null && it.person.id !in inOrder && it.person.id !in t.pitchers }
            .sortedBy { it.battingOrder }
            .map { it.person.id }
        return Lineup(
            gameId = gameId,
            team = teamRef(t.team),
            groups = listOfNotNull(
                LineupGroup(LineupGroupKind.STARTERS, "Batting order", t.battingOrder.map(::ref)),
                LineupGroup(LineupGroupKind.OTHER, "Pitchers", t.pitchers.map(::ref)).takeIf { it.players.isNotEmpty() },
                LineupGroup(LineupGroupKind.OTHER, "Substituted", replaced.map(::ref)).takeIf { it.players.isNotEmpty() },
                LineupGroup(LineupGroupKind.BENCH, "Bench", t.bench.map(::ref)),
                LineupGroup(LineupGroupKind.OTHER, "Bullpen", t.bullpen.map(::ref)),
            ),
        )
    }

    // ---- standings -----------------------------------------------------------------------

    public fun standings(s: MlbStandings): StandingsTable {
        val records = s.records.filter { it.standingsType == null || it.standingsType == "regularSeason" }.ifEmpty { s.records }
        val groups = records.map { r ->
            val label = r.division?.name ?: r.division?.id?.let(DIVISIONS::get) ?: r.league?.name ?: "League"
            StandingsGroup(
                label = label,
                rows = r.teamRecords.sortedBy { it.divisionRank?.toIntOrNull() ?: Int.MAX_VALUE }.mapIndexed { i, t ->
                    StandingsRow(
                        team = teamRef(t.team),
                        rank = t.divisionRank?.toIntOrNull() ?: (i + 1),
                        played = t.gamesPlayed,
                        wins = t.wins,
                        losses = t.losses,
                        // Baseball has no points; wins are the ranking key.
                        points = t.wins,
                        goalsFor = t.runsScored,
                        goalsAgainst = t.runsAllowed,
                        goalDifference = t.runDifferential,
                        extra = buildMap {
                            t.winningPercentage?.let { put("pct", it) }
                            t.gamesBack?.let { put("gamesBack", it) }
                            t.wildCardGamesBack?.let { put("wildCardGamesBack", it) }
                            t.wildCardRank?.let { put("wildCardRank", it) }
                            t.leagueRank?.let { put("leagueRank", it) }
                            t.streak?.streakCode?.let { put("streak", it) }
                            t.divisionLeader?.let { put("divisionLeader", it.toString()) }
                            t.clinched?.let { put("clinched", it.toString()) }
                            t.magicNumber?.let { put("magicNumber", it) }
                            t.eliminationNumber?.let { put("eliminationNumber", it) }
                            t.records?.splitRecords?.forEach { split ->
                                when (split.type) {
                                    "home" -> put("home", "${split.wins}-${split.losses}")
                                    "away" -> put("away", "${split.wins}-${split.losses}")
                                    "lastTen" -> put("last10", "${split.wins}-${split.losses}")
                                }
                            }
                        },
                    )
                },
            )
        }
        return StandingsTable(
            leagueId = LEAGUE_ID,
            seasonId = records.firstOrNull()?.teamRecords?.firstOrNull()?.season,
            stage = if (records.all { it.standingsType == null || it.standingsType == "regularSeason" }) StageKind.REGULAR else StageKind.OTHER,
            groups = groups,
            grouping = "division",
        )
    }

    // ---- team / roster / player ----------------------------------------------------------

    public fun team(t: MlbTeam): Team = Team(
        ref = teamRef(t),
        placeName = t.locationName,
        commonName = t.teamName,
        arena = t.venue?.name,
        conference = t.league?.name,
        division = t.division?.name ?: t.division?.id?.let(DIVISIONS::get),
        logoDarkUrl = logoDarkUrl(t.id),
    )

    public fun teamStats(response: MlbTeamStats, teamId: String, seasonId: String): TeamSeasonStats = TeamSeasonStats(
        leagueId = LEAGUE_ID,
        teamId = teamId,
        seasonId = seasonId,
        groups = response.stats.mapNotNull { group ->
            val key = group.group.displayName
            val labels = when (key) {
                "hitting" -> listOf(
                    "avg" to "Batting average", "runs" to "Runs", "homeRuns" to "Home runs",
                    "rbi" to "Runs batted in", "hits" to "Hits", "obp" to "On-base percentage",
                    "slg" to "Slugging percentage", "ops" to "On-base + slugging",
                    "stolenBases" to "Stolen bases", "baseOnBalls" to "Walks", "strikeOuts" to "Strikeouts",
                )
                "pitching" -> listOf(
                    "era" to "Earned run average", "whip" to "Walks + hits per inning", "strikeOuts" to "Strikeouts",
                    "wins" to "Wins", "losses" to "Losses", "saves" to "Saves",
                    "inningsPitched" to "Innings pitched", "earnedRuns" to "Earned runs",
                    "baseOnBalls" to "Walks allowed", "homeRuns" to "Home runs allowed",
                    "avg" to "Opponent batting average", "shutouts" to "Shutouts",
                )
                else -> return@mapNotNull null
            }
            val split = group.splits.firstOrNull { it.season == seasonId && it.team?.id?.toString() == teamId }
                ?: return@mapNotNull null
            val stats = labels.mapNotNull { (id, label) ->
                split.stat[id]?.contentOrNull?.let { TeamStat(id, label, it) }
            }
            if (stats.isEmpty()) null else TeamStatGroup(key, if (key == "hitting") "Batting" else "Pitching", stats)
        },
    )

    public fun roster(r: MlbRoster, teamId: String): List<Player> = r.roster.map { e ->
        Player(
            ref = PlayerRef(
                leagueId = LEAGUE_ID,
                id = e.person.id.toString(),
                name = e.person.fullName ?: "#${e.person.id}",
                jerseyNumber = e.jerseyNumber?.toIntOrNull(),
                position = e.position?.abbreviation,
                headshotUrl = headshotUrl(e.person.id),
            ),
            teamId = r.teamId?.toString() ?: teamId,
            active = e.status?.code?.let { it == "A" },
        )
    }

    public fun player(p: MlbPerson): Player = Player(
        ref = PlayerRef(
            leagueId = LEAGUE_ID,
            id = p.id.toString(),
            name = p.fullName,
            jerseyNumber = p.primaryNumber?.toIntOrNull(),
            position = p.primaryPosition?.abbreviation,
            headshotUrl = headshotUrl(p.id),
        ),
        firstName = p.firstName,
        lastName = p.lastName,
        birthDate = Dates.localDateOrNull(p.birthDate),
        birthPlace = listOfNotNull(p.birthCity, p.birthStateProvince).joinToString(", ").ifBlank { null },
        nationality = p.birthCountry,
        heightCm = p.height?.let(::heightCm),
        weightKg = p.weight?.let { (it * 0.45359237).roundToInt() },
        handedness = handedness(p.batSide?.code, p.pitchHand?.code),
        teamId = p.currentTeam?.id?.toString(),
        active = p.active,
    )

    /** Bats/throws as the sport writes it: `R/R`, `L/R`, `S/R` (switch-hitter). */
    public fun handedness(bats: String?, throws: String?): String? =
        if (bats == null && throws == null) null else "${bats ?: "?"}/${throws ?: "?"}"

    private val HEIGHT = Regex("""(\d+)'\s*(\d+)"?""")

    /** `6' 7"` → 201. */
    public fun heightCm(text: String): Int? {
        val m = HEIGHT.find(text) ?: return null
        val inches = m.groupValues[1].toInt() * 12 + m.groupValues[2].toInt()
        return (inches * 2.54).roundToInt()
    }
}
