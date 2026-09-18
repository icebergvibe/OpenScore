package org.openscore.feed

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.openscore.model.Clock
import org.openscore.model.EventDetails
import org.openscore.model.Game
import org.openscore.model.GameEvent
import org.openscore.model.GameSituation
import org.openscore.model.League
import org.openscore.model.Lineup
import org.openscore.model.Period
import org.openscore.model.PeriodScore
import org.openscore.model.Player
import org.openscore.model.PlayerRef
import org.openscore.model.Score
import org.openscore.model.ShootoutAttemptDetails
import org.openscore.model.StandingsTable
import org.openscore.model.Team
import org.openscore.model.TeamRef
import org.openscore.model.TeamSeasonStats
import org.openscore.model.baseball.BaseRunningDetails
import org.openscore.model.baseball.BaseballSituation
import org.openscore.model.baseball.BaseballSubstitutionDetails
import org.openscore.model.baseball.PlateAppearanceDetails
import org.openscore.model.football.CardDetails
import org.openscore.model.football.DisallowedGoalDetails
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.PenaltyMissDetails
import org.openscore.model.football.SubstitutionDetails
import org.openscore.model.hockey.FaceoffDetails
import org.openscore.model.hockey.GoalDetails
import org.openscore.model.hockey.HitDetails
import org.openscore.model.hockey.PenaltyDetails
import org.openscore.model.hockey.ShotDetails
import org.openscore.model.hockey.StoppageDetails
import org.openscore.provider.LeagueProvider
import kotlin.time.Duration

/** Model → Feed v1. Pure functions; one entry point per feed type. */
public object FeedMapper {

    public fun league(p: LeagueProvider): FeedLeague = league(p.league, p.capabilities.map { it.name })

    public fun league(l: League, capabilities: List<String>): FeedLeague = FeedLeague(
        id = l.id,
        sport = l.sport.name,
        name = l.name,
        country = l.country,
        websiteUrl = l.websiteUrl,
        capabilities = capabilities.sorted(),
    )

    public fun teamStats(s: TeamSeasonStats): FeedTeamSeasonStats = FeedTeamSeasonStats(
        s.leagueId, s.teamId, s.seasonId,
        s.groups.map { group -> FeedTeamStatGroup(group.key, group.label, group.stats.map { FeedTeamStat(it.key, it.label, it.value) }) },
    )

    public fun teamRef(t: TeamRef): FeedTeamRef = FeedTeamRef(t.leagueId, t.id, t.name, t.abbreviation, t.logoUrl, t.clubId)

    public fun playerRef(p: PlayerRef): FeedPlayerRef =
        FeedPlayerRef(p.leagueId, p.id, p.name, p.jerseyNumber, p.position, p.headshotUrl)

    public fun period(p: Period): FeedPeriod = FeedPeriod(p.number, p.type.name, p.label)

    public fun score(s: Score): FeedScore = FeedScore(s.home, s.away)

    private fun seconds(d: Duration?): Int? = d?.inWholeSeconds?.toInt()

    public fun clock(c: Clock): FeedClock =
        FeedClock(period(c.period), seconds(c.time.elapsed), seconds(c.time.remaining), c.running, c.time.label)

    public fun periodScore(p: PeriodScore): FeedPeriodScore = FeedPeriodScore(period(p.period), p.home, p.away)

    public fun game(g: Game): FeedGame = FeedGame(
        league = g.leagueId,
        id = g.id,
        seasonId = g.seasonId,
        stage = g.stage?.name,
        competition = g.competition,
        startTime = g.startTime.toString(),
        scheduleDate = g.scheduleDate?.toString(),
        startTimeTbd = g.startTimeTbd,
        venue = g.venue,
        home = teamRef(g.home),
        away = teamRef(g.away),
        state = g.state.name,
        score = g.score?.let(::score),
        clock = g.clock?.let(::clock),
        situation = g.situation?.let(::situation),
        periodScores = g.periodScores.map(::periodScore),
        ending = g.ending?.name,
        events = g.events?.map(::event),
        stats = g.stats.mapValues { (_, v) -> FeedStatPair(v.home, v.away) },
        rawState = g.rawState,
    )

    public fun event(e: GameEvent): FeedEvent = FeedEvent(
        id = e.id,
        type = e.type.key,
        rawType = e.rawType,
        period = period(e.period),
        elapsedSeconds = seconds(e.time.elapsed),
        remainingSeconds = seconds(e.time.remaining),
        timeLabel = e.time.label,
        team = e.team?.let(::teamRef),
        players = e.players.map(::playerRef),
        score = e.score?.let(::score),
        coordinates = e.coordinates?.let { FeedCoordinates(it.x, it.y) },
        description = e.description,
        details = e.details?.let(::details),
        sortOrder = e.sortOrder,
    )

    private fun refJson(p: PlayerRef): JsonElement = FeedJson.encodeToJsonElement(FeedPlayerRef.serializer(), playerRef(p))

    /** A player reference as a feed object, or an explicit null. */
    private fun JsonObjectBuilder.putRef(key: String, p: PlayerRef?) {
        put(key, if (p == null) JsonNull else refJson(p))
    }

    /** Sport-specific live state → JSON object with a `kind`. */
    public fun situation(s: GameSituation): JsonObject = buildJsonObject {
        when (s) {
            is BaseballSituation -> {
                put("kind", "baseball")
                put("inning", s.inning)
                put("half", s.half.name)
                put("outs", s.outs)
                put("balls", s.balls)
                put("strikes", s.strikes)
                putRef("onFirst", s.onFirst)
                putRef("onSecond", s.onSecond)
                putRef("onThird", s.onThird)
                putRef("batter", s.batter)
                putRef("pitcher", s.pitcher)
                putRef("onDeck", s.onDeck)
            }
            else -> put("kind", s::class.simpleName ?: "unknown")
        }
    }

    /** Sport-specific details → JSON object with a `kind`. Unknown detail types yield `{"kind": "<class>"}`. */
    public fun details(d: EventDetails): JsonObject = buildJsonObject {
        when (d) {
            is GoalDetails -> {
                put("kind", "goal")
                putRef("scorer", d.scorer)
                putJsonArray("assists") { d.assists.forEach { add(refJson(it)) } }
                put("strength", d.strength?.name)
                put("emptyNet", d.emptyNet)
                put("shotType", d.shotType)
                putRef("goalie", d.goalie)
                put("scorerSeasonTotal", d.scorerSeasonTotal)
            }
            is PenaltyDetails -> {
                put("kind", "penalty")
                putRef("player", d.player)
                putRef("drawnBy", d.drawnBy)
                putRef("servedBy", d.servedBy)
                put("minutes", d.minutes)
                put("infraction", d.infraction)
                put("severity", d.severity)
            }
            is ShotDetails -> {
                put("kind", "shot")
                putRef("shooter", d.shooter)
                put("outcome", d.outcome.name)
                putRef("goalie", d.goalie)
                putRef("blockedBy", d.blockedBy)
                put("shotType", d.shotType)
                put("reason", d.reason)
            }
            is FaceoffDetails -> {
                put("kind", "faceoff")
                putRef("winner", d.winner)
                putRef("loser", d.loser)
            }
            is HitDetails -> {
                put("kind", "hit")
                putRef("hitter", d.hitter)
                putRef("hittee", d.hittee)
            }
            is StoppageDetails -> {
                put("kind", "stoppage")
                put("reason", d.reason)
            }
            is ShootoutAttemptDetails -> {
                put("kind", "shootout-attempt")
                putRef("shooter", d.shooter)
                putRef("goalie", d.goalie)
                put("scored", d.scored)
                put("shotType", d.shotType)
            }
            is FootballGoalDetails -> {
                put("kind", "football-goal")
                putRef("scorer", d.scorer)
                putRef("assist", d.assist)
                put("goalKind", d.kind.name)
                put("varDecision", d.varDecision?.name)
                put("scorerSeasonTotal", d.scorerSeasonTotal)
            }
            is CardDetails -> {
                put("kind", "card")
                putRef("player", d.player)
                put("card", d.card.name)
                put("reason", d.reason)
            }
            is SubstitutionDetails -> {
                put("kind", "substitution")
                putRef("playerOn", d.playerOn)
                putRef("playerOff", d.playerOff)
                put("reason", d.reason)
            }
            is PenaltyMissDetails -> {
                put("kind", "penalty-miss")
                putRef("player", d.player)
                putRef("savedBy", d.savedBy)
                put("outcome", d.outcome)
            }
            is DisallowedGoalDetails -> {
                put("kind", "goal-disallowed")
                putRef("player", d.player)
                put("reason", d.reason)
            }
            is PlateAppearanceDetails -> {
                put("kind", "plate-appearance")
                putRef("batter", d.batter)
                putRef("pitcher", d.pitcher)
                put("rbi", d.rbi)
                put("out", d.out)
                put("outsAfter", d.outsAfter)
                put("scoringPlay", d.scoringPlay)
                put("pitches", d.pitches)
                putJsonArray("runners") {
                    d.runners.forEach { r ->
                        add(
                            buildJsonObject {
                                putRef("runner", r.runner)
                                put("from", r.from)
                                put("to", r.to)
                                put("out", r.out)
                                put("scored", r.scored)
                            },
                        )
                    }
                }
                d.battedBall?.let { b ->
                    put(
                        "battedBall",
                        buildJsonObject {
                            put("launchSpeedMph", b.launchSpeedMph)
                            put("launchAngle", b.launchAngle)
                            put("distanceFt", b.distanceFt)
                            put("trajectory", b.trajectory)
                        },
                    )
                } ?: put("battedBall", JsonNull)
            }
            is BaseRunningDetails -> {
                put("kind", "base-running")
                putRef("runner", d.runner)
                put("from", d.from)
                put("to", d.to)
                put("out", d.out)
            }
            is BaseballSubstitutionDetails -> {
                put("kind", "baseball-substitution")
                putRef("playerIn", d.playerIn)
                put("replaces", d.replaces)
                put("position", d.position)
                put("substitutionKind", d.kind)
            }
            else -> put("kind", d::class.simpleName ?: "unknown")
        }
    }

    public fun lineup(l: Lineup): FeedLineup = FeedLineup(
        gameId = l.gameId,
        team = teamRef(l.team),
        groups = l.groups.map { FeedLineupGroup(it.kind.name, it.label, it.players.map(::playerRef)) },
        headCoach = l.headCoach,
        formation = l.formation,
    )

    public fun standings(t: StandingsTable): FeedStandings = FeedStandings(
        league = t.leagueId,
        seasonId = t.seasonId,
        stage = t.stage?.name,
        grouping = t.grouping,
        groups = t.groups.map { g ->
            FeedStandingsGroup(
                label = g.label,
                rows = g.rows.map { r ->
                    FeedStandingsRow(
                        team = teamRef(r.team),
                        rank = r.rank,
                        played = r.played,
                        wins = r.wins,
                        losses = r.losses,
                        draws = r.draws,
                        otherLosses = r.otherLosses,
                        points = r.points,
                        goalsFor = r.goalsFor,
                        goalsAgainst = r.goalsAgainst,
                        goalDifference = r.goalDifference,
                        extra = r.extra,
                    )
                },
            )
        },
    )

    public fun team(t: Team): FeedTeam = FeedTeam(
        league = t.leagueId,
        id = t.id,
        name = t.name,
        abbreviation = t.ref.abbreviation,
        logoUrl = t.ref.logoUrl,
        logoDarkUrl = t.logoDarkUrl,
        placeName = t.placeName,
        commonName = t.commonName,
        arena = t.arena,
        conference = t.conference,
        division = t.division,
        country = t.country,
        clubId = t.ref.clubId,
    )

    public fun player(p: Player): FeedPlayer = FeedPlayer(
        league = p.leagueId,
        id = p.id,
        name = p.name,
        firstName = p.firstName,
        lastName = p.lastName,
        jerseyNumber = p.ref.jerseyNumber,
        position = p.ref.position,
        headshotUrl = p.ref.headshotUrl,
        birthDate = p.birthDate?.toString(),
        birthPlace = p.birthPlace,
        nationality = p.nationality,
        heightCm = p.heightCm,
        weightKg = p.weightKg,
        handedness = p.handedness,
        teamId = p.teamId,
        active = p.active,
    )
}
