package org.openscore.app.ui.team

import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.openscore.app.data.wantsScorePoll
import org.openscore.clubs.Clubs
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.model.League
import org.openscore.model.Player
import org.openscore.model.StageKind
import org.openscore.model.StandingsRow
import org.openscore.model.StandingsTable
import org.openscore.model.TeamRef
import org.openscore.model.leagueDate
import kotlin.time.Instant

enum class TeamTab(val label: String) { OVERVIEW("Overview"), GAMES("Games"), STANDINGS("Standings"), ROSTER("Roster"), STATS("Stats") }
enum class GameFilter(val label: String) { UPCOMING("Upcoming"), RESULTS("Results"), ALL("All games") }

/**
 * Which zone a league keeps its calendar in, as [org.openscore.model.League.zone] states it.
 * Taken as a parameter rather than looked up here so these functions stay pure and a test can
 * hand them a fixed zone; the screens pass [org.openscore.app.data.ScoresRepository.zoneOf].
 */
typealias LeagueZones = (String) -> TimeZone

/** The Malta Premier API labels 2026/27 with its end year, 2027. */
fun teamSeasonYear(leagueId: String, date: LocalDate): Int =
    if (leagueId == "malta-premier") date.year + 1 else date.year

/** Leagues whose season is one calendar year; every other league straddles New Year (July to June). */
private val CALENDAR_YEAR_LEAGUES = setOf("mlb", "mls", "allsvenskan", "superettan", "fogis")

/** The season a team page is about: the dates its schedule spans and how the header names it. */
data class SeasonWindow(val label: String, val start: LocalDate, val end: LocalDate) {
    operator fun contains(date: LocalDate): Boolean = date in start..end
}

fun seasonWindow(leagueId: String, today: LocalDate): SeasonWindow {
    if (leagueId in CALENDAR_YEAR_LEAGUES) return SeasonWindow(today.year.toString(), LocalDate(today.year, 1, 1), LocalDate(today.year, 12, 31))
    val startYear = if (today.month >= Month.JULY) today.year else today.year - 1
    return SeasonWindow("$startYear/${(startYear + 1) % 100}", LocalDate(startYear, 7, 1), LocalDate(startYear + 1, 6, 30))
}

/** A cross-border competition (UEFA, CHL) is never a club's home league. */
val League.isDomestic: Boolean get() = country != "EU"

/**
 * The same club, wherever the app's leagues carry it: the crosswalk names the club's native id
 * in every id namespace, and each app league reads one namespace. Domestic leagues come first,
 * the league the page was opened from ahead of its peers, so profile and squad are read from
 * the club's own league rather than from a cup or a UEFA competition it also plays in.
 */
fun clubSources(team: TeamRef, leagues: List<League>): List<TeamRef> {
    val club = team.clubId?.let(Clubs::byId) ?: return listOf(team)
    val refs = leagues.filter { it.sport == club.sport }.mapNotNull { league ->
        val id = club.ids[Clubs.namespace(league.id)] ?: return@mapNotNull null
        if (league.id == team.leagueId) team else TeamRef(league.id, id, team.name, team.abbreviation, team.logoUrl, clubId = club.id)
    }
    if (refs.none { it.leagueId == team.leagueId }) return listOf(team) + refs
    return refs.sortedWith(compareBy({ if (leagues.first { l -> l.id == it.leagueId }.isDomestic) 0 else 1 }, { if (it.leagueId == team.leagueId) 0 else 1 }))
}

/** One club, however its id is spelled: by club id where both sides have one, else by league and id. */
fun TeamRef.isSameClub(other: TeamRef): Boolean =
    if (clubId != null && other.clubId != null) clubId == other.clubId else leagueId == other.leagueId && id == other.id

fun StandingsTable.rowFor(team: TeamRef): StandingsRow? = rows.firstOrNull { it.team.isSameClub(team) }

/** Today, as [leagueId] counts days. */
private fun Instant.dayIn(zones: LeagueZones, leagueId: String): LocalDate = toLocalDateTime(zones(leagueId)).date

/** The game is one of this club's, in whichever competition. */
fun Game.belongsTo(team: TeamRef): Boolean = home.isSameClub(team) || away.isSameClub(team)

fun Game.resultFor(team: TeamRef): String? {
    if (!belongsTo(team) || !state.isFinished) return null
    val score = score ?: return null
    val atHome = home.isSameClub(team)
    val own = if (atHome) score.home else score.away
    val other = if (atHome) score.away else score.home
    return when { own > other -> "W"; own < other -> "L"; else -> "T" }
}

fun teamGames(games: List<Game>, team: TeamRef, filter: GameFilter, now: Instant, zones: LeagueZones): List<Game> {
    val selected = games.filter { it.belongsTo(team) }.distinctBy { it.leagueId to it.id }.filter {
        val today = now.dayIn(zones, it.leagueId)
        when (filter) {
            GameFilter.ALL -> true
            GameFilter.RESULTS -> it.state.isFinished
            GameFilter.UPCOMING -> it.state.isLive || it.state == GameState.SUSPENDED ||
                (it.state in listOf(GameState.SCHEDULED, GameState.PRE_GAME) &&
                    it.leagueDate(zones(it.leagueId)) >= today)
        }
    }.sortedBy { it.startTime }
    return if (filter == GameFilter.RESULTS) selected.reversed() else selected
}

/** The last five results of the season proper — cup ties and league games alike, but not warm-ups or a play-off run. */
fun recentForm(games: List<Game>, team: TeamRef): List<Game> = games
    .filter { it.stage != StageKind.PRESEASON && it.stage != StageKind.PLAYOFF && it.resultFor(team) != null }
    .distinctBy { it.leagueId to it.id }.sortedBy { it.startTime }.takeLast(5)

/**
 * Wake up near kick-off; off-days and distant/cancelled fixtures need no score poll. Keyed by
 * league, each in its own date convention - these dates are handed straight back to that
 * league's day listing, so they have to be the days it files its games under.
 */
fun scoreRefreshDates(games: List<Game>, now: Instant, zones: LeagueZones): Map<String, Set<LocalDate>> = games
    .filter { it.wantsScorePoll(now) }
    .groupBy({ it.leagueId }, { it.leagueDate(zones(it.leagueId)) })
    .mapValues { (_, dates) -> dates.toSet() }

fun rosterGroup(player: Player): String = when (player.ref.position) {
    "P", "SP", "RP" -> "Pitchers"
    "C" -> "Catchers"
    "1B", "2B", "3B", "SS", "IF" -> "Infielders"
    "LF", "CF", "RF", "OF" -> "Outfielders"
    "DH" -> "Designated hitters"
    "TWP" -> "Two-way players"
    else -> "Other players"
}

/** Football squads read by line: keepers, then defence, midfield, attack, then staff and anything unlabelled. */
fun footballRosterGroup(player: Player): String {
    val position = player.ref.position ?: return "Players"
    return when (position.uppercase()) {
        "GK", "G", "GOALKEEPER" -> "Goalkeepers"
        "DF", "D", "DEF", "DEFENDER", "DEFENSE" -> "Defenders"
        "MF", "M", "MID", "MIDFIELDER", "MIDFIELD" -> "Midfielders"
        "FW", "F", "ST", "ATT", "FORWARD", "OFFENSE", "ATTACKER" -> "Forwards"
        else -> if (position.contains("coach", ignoreCase = true)) "Staff" else "Players"
    }
}

val FOOTBALL_ROSTER_ORDER: List<String> = listOf("Goalkeepers", "Defenders", "Midfielders", "Forwards", "Players", "Staff")
