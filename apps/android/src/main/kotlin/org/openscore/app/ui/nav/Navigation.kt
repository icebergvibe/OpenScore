package org.openscore.app.ui.nav

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import org.openscore.app.ui.team.LeagueZones
import org.openscore.model.Game
import org.openscore.model.League
import org.openscore.model.TeamRef
import org.openscore.model.leagueDate

/*
 * The screens, as the keys of one back stack: the tab scaffold at the bottom, then whatever
 * was tapped, in order. Keys carry ids rather than the core's models so the stack survives
 * process death in the saved state; the model a tap already has in hand is passed alongside
 * through [GameSeeds], and a screen restored without it fetches by id.
 */

@Serializable
data object HomeKey : NavKey

/** [date] is the league's own calendar day, the one a day listing would be asked for to find the game again. */
@Serializable
data class MatchKey(val leagueId: String, val gameId: String, val date: String) : NavKey {
    companion object {
        /** [zone] is the league's own, from [org.openscore.app.data.ScoresRepository.zoneOf]. */
        fun of(game: Game, zone: TimeZone): MatchKey =
            MatchKey(game.leagueId, game.id, game.leagueDate(zone).toString())
    }
}

@Serializable
data class TableKey(val leagueId: String) : NavKey

/** Everything a [TeamRef] holds, so the team page is rebuilt exactly after process death. */
@Serializable
data class TeamKey(
    val leagueId: String,
    val teamId: String,
    val name: String,
    val abbreviation: String?,
    val logoUrl: String?,
    val clubId: String?,
) : NavKey {
    fun toRef(): TeamRef = TeamRef(leagueId, teamId, name, abbreviation, logoUrl, clubId)

    companion object {
        fun of(team: TeamRef): TeamKey = TeamKey(team.leagueId, team.id, team.name, team.abbreviation, team.logoUrl, team.clubId)
    }
}

/**
 * The games handed to match screens by the cards that opened them, so the header is drawn
 * from what the reader just saw instead of after a request. Activity-scoped: it outlives a
 * rotation but not the process, which is when the key alone has to do.
 */
class GameSeeds : ViewModel() {
    private val games = HashMap<MatchKey, Game>()

    fun put(key: MatchKey, game: Game) { games[key] = game }

    fun get(key: MatchKey): Game? = games[key]

    /** Nothing above the root can still need a seed. */
    fun clear() = games.clear()
}

/** What the screens can do to the stack. Pushing the key already on top is a no-op, so a double tap opens one screen. */
@Stable
class Navigator(
    private val backStack: NavBackStack<NavKey>,
    private val seeds: GameSeeds,
    /** So a game opened from a card is keyed to its league's day, not to the reader's. */
    private val zones: LeagueZones,
) {

    fun openGame(game: Game) {
        val key = MatchKey.of(game, zones(game.leagueId))
        seeds.put(key, game)
        push(key)
    }

    /** A game known only by id and day (a notification): the screen fetches it. Everything else is popped first. */
    fun openGame(key: MatchKey) {
        backStack.removeAll { it != HomeKey }
        seeds.clear()
        push(key)
    }

    fun openTable(league: League) = push(TableKey(league.id))

    fun openTeam(team: TeamRef) = push(TeamKey.of(team))

    fun back() {
        backStack.removeLastOrNull()
        if (backStack.size <= 1) seeds.clear()
    }

    private fun push(key: NavKey) {
        if (backStack.lastOrNull() != key) backStack.add(key)
    }
}
