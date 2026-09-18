package org.openscore.providers.mls

import kotlinx.datetime.LocalDate
import org.openscore.model.Clock
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameEvent
import org.openscore.model.GameState
import org.openscore.model.GameTime
import org.openscore.model.Lineup
import org.openscore.model.LineupGroup
import org.openscore.model.LineupGroupKind
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
import org.openscore.model.football.CardDetails
import org.openscore.model.football.CardKind
import org.openscore.model.football.FootballEventType
import org.openscore.model.football.FootballGoalDetails
import org.openscore.model.football.GoalKind
import org.openscore.model.football.SubstitutionDetails
import org.openscore.model.football.footballFormation
import org.openscore.provider.Dates
import org.openscore.providers.football.FootballPeriods
import kotlin.time.Instant

public class MlsMapper(private val leagueId: String) {
    public fun gameState(status: String?): GameState = when (status) {
        "scheduled", "notStarted" -> GameState.SCHEDULED
        "preMatch", "lineup" -> GameState.PRE_GAME
        "firstHalf", "secondHalf", "extraTimeFirstHalf", "extraTimeSecondHalf", "penalties" -> GameState.LIVE
        "halfTime", "extraTimeHalfTime" -> GameState.INTERMISSION
        "finalWhistle" -> GameState.FINAL
        "postponed" -> GameState.POSTPONED
        "suspended", "abandoned" -> GameState.SUSPENDED
        "cancelled", "canceled" -> GameState.CANCELLED
        else -> GameState.UNKNOWN
    }

    public fun team(id: String?, name: String?, code: String?, logo: String? = null): TeamRef =
        TeamRef(leagueId, id.orEmpty(), name ?: id.orEmpty(), code, logo ?: MlsClubLogos.url(id))

    public fun team(c: MlsClub): Team = Team(
        ref = team(c.club_id, c.club_name, c.three_letter_code),
        placeName = c.city, commonName = c.short_name, arena = c.stadium_name, country = c.country,
    )

    private fun score(home: Int?, away: Int?, state: GameState): Score? =
        if (state == GameState.SCHEDULED || state == GameState.PRE_GAME || home == null || away == null) null else Score(home, away)

    public fun schedule(m: MlsScheduleMatch, date: LocalDate, metadata: MlsMetadataMatch? = null): Game {
        val state = gameState(m.match_status)
        return Game(leagueId, m.match_id, m.season_id, StageKind.REGULAR, m.competition_name,
            Instant.parse(m.planned_kickoff_time ?: error("MLS match has no planned kick-off")), date, m.stadium_name,
            team(m.home_team_id, m.home_team_name, m.home_team_three_letter_code, logo(metadata?.home?.logoColorUrl)),
            team(m.away_team_id, m.away_team_name, m.away_team_three_letter_code, logo(metadata?.away?.logoColorUrl)), state,
            score(m.home_team_goals, m.away_team_goals, state), rawState = m.match_status)
    }

    public fun game(m: MlsMatch, events: MlsEvents?, stats: MlsMatchStats?): Game {
        val info = m.match_information ?: error("MLS match has no match_information")
        val state = gameState(info.match_status)
        val home = m.home ?: error("MLS match has no home team")
        val away = m.away ?: error("MLS match has no away team")
        val h = team(home.team_id, home.team_name, home.team_three_letter_code)
        val a = team(away.team_id, away.team_name, away.team_three_letter_code)
        return Game(leagueId, info.match_id.orEmpty(), info.season_id, StageKind.REGULAR, info.competition_name,
            Instant.parse(info.planned_kickoff_time ?: error("MLS match has no planned kick-off")), venue = m.environment?.stadium_name,
            home = h, away = a, state = state, score = score(info.home_team_goals, info.away_team_goals, state),
            clock = clock(state, info.minute_of_play), ending = if (state.isFinished) GameEnding.REGULATION else null,
            events = events?.let { events(it, h, a) }, stats = stats(stats), rawState = info.match_status)
    }

    public fun scheduledMetadata(m: MlsMetadataMatch): Game {
        val home = m.home ?: error("MLS metadata has no home team")
        val away = m.away ?: error("MLS metadata has no away team")
        return Game(leagueId, m.sportecId.orEmpty(), m.season?.sportecId, StageKind.REGULAR, m.competition?.name,
            Instant.parse(m.matchDate ?: error("MLS metadata has no match date")), venue = m.venue?.name,
            home = team(home.sportecId, home.fullName, home.abbreviation, logo(home.logoColorUrl)),
            away = team(away.sportecId, away.fullName, away.abbreviation, logo(away.logoColorUrl)),
            state = GameState.SCHEDULED, rawState = "scheduled (sportapi metadata)")
    }

    private fun logo(url: String?): String? =
        url?.replace("{formatInstructions}", "w_250,h_250,c_fit,q_auto,f_png")

    private fun clock(state: GameState, minute: String?): Clock? {
        if (!state.isLive) return null
        val (value, added) = FootballPeriods.parseMinute(minute) ?: return null
        val period = FootballPeriods.periodForMinute(value)
        return Clock(FootballPeriods.timeAtMinute(period, value, added), state == GameState.LIVE)
    }

    private fun period(section: String?, minute: String?): org.openscore.model.Period = when (section) {
        "firstHalf" -> FootballPeriods.FIRST_HALF
        "secondHalf" -> FootballPeriods.SECOND_HALF
        "extraTimeFirstHalf" -> FootballPeriods.EXTRA_FIRST
        "extraTimeSecondHalf" -> FootballPeriods.EXTRA_SECOND
        "penalties" -> FootballPeriods.PENALTIES
        else -> FootballPeriods.parseMinute(minute)?.first?.let(FootballPeriods::periodForMinute) ?: FootballPeriods.FIRST_HALF
    }

    private fun time(section: String?, minute: String?): GameTime {
        val p = period(section, minute)
        val parsed = FootballPeriods.parseMinute(minute)
        return if (parsed == null) GameTime(p) else FootballPeriods.timeAtMinute(p, parsed.first, parsed.second)
    }

    private fun player(id: String?, first: String?, last: String?): PlayerRef? {
        val name = listOfNotNull(first?.takeIf { it.isNotBlank() }, last?.takeIf { it.isNotBlank() }).joinToString(" ")
        return if (id.isNullOrBlank() && name.isBlank()) null else PlayerRef(leagueId, id ?: name, name.ifBlank { id!! })
    }

    private fun eventScore(value: String?): Score? {
        val p = value?.split(":") ?: return null
        return if (p.size == 2) p[0].toIntOrNull()?.let { h -> p[1].toIntOrNull()?.let { Score(h, it) } } else null
    }

    public fun events(feed: MlsEvents, home: TeamRef, away: TeamRef): List<GameEvent> = feed.events.asReversed().mapIndexed { index, row ->
        val e = row.event ?: MlsEventData()
        val id = e.event_id?.toString() ?: "${row.type}-$index"
        val team = when (e.team_id) { home.id -> home; away.id -> away; else -> e.team_id?.let { team(it, e.team_name, e.team_three_letter_code) } }
        val player = player(e.player_id, e.player_first_name, e.player_last_name)
        val score = eventScore(e.result)
        val time = time(e.game_section, e.minute_of_play)
        when {
            row.type == "shot_at_goals" && row.sub_type == "goals" -> {
                val assist = player(e.assist_player_id, e.assist_player_first_name, e.assist_player_last_name)
                GameEvent(id, FootballEventType.GOAL, "shot_at_goals:goals", time, team, listOfNotNull(player, assist), score,
                    description = "Goal — ${player?.name ?: "?"}", details = FootballGoalDetails(player, assist), sortOrder = index)
            }
            // `team_id` on an own goal is the scorer's club (Godoy, San Diego, `result 0:4` for Philadelphia); credit the other side.
            row.type == "own_goals" -> GameEvent(id, FootballEventType.OWN_GOAL, row.type, time, when (team?.id) { home.id -> away; away.id -> home; else -> null }, listOfNotNull(player), score,
                description = "Own goal — ${player?.name ?: "?"}", details = FootballGoalDetails(player, kind = GoalKind.OWN_GOAL), sortOrder = index)
            row.type == "cards" -> {
                val card = when (e.card_color?.lowercase()) { "red" -> CardKind.RED; "yellowred", "yellow_red" -> CardKind.SECOND_YELLOW; else -> CardKind.YELLOW }
                val type = when (card) { CardKind.RED -> FootballEventType.RED_CARD; CardKind.SECOND_YELLOW -> FootballEventType.SECOND_YELLOW; CardKind.YELLOW -> FootballEventType.YELLOW_CARD }
                GameEvent(id, type, "cards:${e.card_color ?: "unknown"}", time, team, listOfNotNull(player), score, details = CardDetails(player, card), sortOrder = index)
            }
            row.type == "substitutions" -> {
                val on = player(e.player_in_id, e.player_in_first_name, e.player_in_last_name)
                val off = player(e.player_out_id, e.player_out_first_name, e.player_out_last_name)
                GameEvent(id, FootballEventType.SUBSTITUTION, row.type, time, team, listOfNotNull(on, off), score,
                    description = "Sub — ${on?.name ?: "?"} for ${off?.name ?: "?"}", details = SubstitutionDetails(on, off), sortOrder = index)
            }
            row.type == "final_whistle" -> GameEvent(id, FootballEventType.GAME_END, row.type, time, team, score = score, description = "Full time", sortOrder = index)
            else -> GameEvent(id, FootballEventType.OTHER, "${row.type}:${row.sub_type.orEmpty()}", time, team, listOfNotNull(player), score, sortOrder = index)
        }
    }

    public fun lineups(gameId: String, m: MlsMatch): List<Lineup> {
        fun side(t: MlsMatchTeam?): Lineup? {
            if (t == null) return null
            fun ref(p: MlsLineupPlayer) = PlayerRef(leagueId, p.person_id ?: p.short_name.orEmpty(), PlayerNames.fromParts(p.first_name, p.last_name, p.short_name.orEmpty()), p.shirt_number, p.playing_position?.takeIf { it.isNotBlank() })
            val starters = t.players.filter { it.starting == "true" }.map(::ref)
            if (starters.isEmpty()) return null
            val bench = t.players.filter { it.starting != "true" }.map(::ref)
            return Lineup(gameId, team(t.team_id, t.team_name, t.team_three_letter_code), listOf(
                LineupGroup(LineupGroupKind.STARTERS, "Starting XI", starters), LineupGroup(LineupGroupKind.BENCH, "Bench", bench)),
                t.trainer_staff.firstOrNull { it.role == "headcoach" }?.short_name, footballFormation(t.latest_line_up))
        }
        return listOfNotNull(side(m.home), side(m.away))
    }

    public fun stats(s: MlsMatchStats?): Map<String, StatPair> {
        val teams = s?.match_statistics_list?.firstOrNull()?.match_statistics?.team_statistics ?: return emptyMap()
        val home = teams.firstOrNull { it.team_role == "home" } ?: return emptyMap()
        val away = teams.firstOrNull { it.team_role == "away" || it.team_role == "guest" } ?: return emptyMap()
        fun pair(h: Any?, a: Any?): StatPair? = if (h == null || a == null) null else StatPair(h.toString(), a.toString())
        return listOfNotNull(
            pair(home.possession_ratio, away.possession_ratio)?.let { "possession" to it },
            pair(home.shots_at_goal_sum, away.shots_at_goal_sum)?.let { "shots" to it },
            pair(home.shots_at_goal_successfull, away.shots_at_goal_successfull)?.let { "shotsOnTarget" to it },
            pair(home.corner_kicks_sum, away.corner_kicks_sum)?.let { "corners" to it },
            pair(home.fouls_sum, away.fouls_sum)?.let { "fouls" to it },
            pair(home.offsides, away.offsides)?.let { "offsides" to it },
            pair(home.cards_yellow, away.cards_yellow)?.let { "yellowCards" to it },
            pair(home.cards_red, away.cards_red)?.let { "redCards" to it },
            pair(home.xG, away.xG)?.let { "xg" to it },
        ).toMap()
    }

    public fun standings(s: MlsStandings, seasonId: String?, label: String): StandingsTable {
        val table = s.tables.firstOrNull()
        val rows = table?.entries.orEmpty().map { e -> StandingsRow(team(e.team_id, e.team, e.team_three_letter_code), e.position ?: 0,
            e.games_played ?: 0, e.wins ?: 0, e.losses ?: 0, e.draws, points = e.points ?: 0,
            goalsFor = e.goals_scored, goalsAgainst = e.goals_against, goalDifference = e.goals_difference) }.sortedBy { it.rank }
        return StandingsTable(leagueId, seasonId ?: table?.season_id, StageKind.REGULAR, listOf(StandingsGroup(label, rows)), "league")
    }

    public fun roster(s: MlsRoster, teamId: String): List<Player> = s.players.mapNotNull { p ->
        val id = p.player_id ?: return@mapNotNull null
        Player(PlayerRef(leagueId, id, PlayerNames.fromParts(p.first_name, p.last_name, p.name.orEmpty()), p.shirt_number, p.playing_position_english),
            p.first_name, p.last_name, Dates.localDateOrNull(p.birth_date),
            nationality = p.nationality_english, teamId = teamId)
    }
}
