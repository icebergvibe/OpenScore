package org.openscore.providers.espn

import org.openscore.clubs.Clubs
import org.openscore.model.Player
import org.openscore.model.PlayerRef
import org.openscore.net.Fetcher
import org.openscore.provider.Dates
import org.openscore.provider.NotFoundException
import org.openscore.provider.getJson
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.hours

/**
 * ESPN's public soccer roster route, used as a **squad supplement** for clubs whose own feed
 * has no key-less squad: the Bundesliga (`wapp.bapi` gates its person/club resources) and
 * the UEFA competitions (`comp.uefa.com` has no squad endpoint). It is never a game or score
 * source — see docs/principles.md for the rules it was adopted under.
 *
 * Ids are ESPN's own (`Clubs` keeps them under the `espn` namespace, hand-verified against
 * ESPN's team directories); the players it returns carry `leagueId = "espn"` because that is
 * the id space their ids live in. `season` is the season's start year; without it the
 * Conference League slug answers 500, and the league-agnostic `all` slug pads the squad with
 * reserve-team players (56 for Bayern against the 25 registered).
 */
public class EspnRosters(
    private val fetcher: Fetcher,
    private val baseUrl: String = DEFAULT_BASE_URL,
) {
    public suspend fun roster(leagueSlug: String, espnTeamId: String, seasonYear: Int): List<Player> {
        val r = fetcher.getJson("$baseUrl/apis/common/v3/sports/soccer/$leagueSlug/teams/$espnTeamId/roster?season=$seasonYear", EspnRoster.serializer(), ROSTER_MAX_AGE, LEAGUE_ID)
        val players = r.positionGroups.flatMap { it.athletes }.map { a -> player(a, r.team?.id ?: espnTeamId) }
        if (players.isEmpty()) throw NotFoundException("ESPN: no roster for $leagueSlug team $espnTeamId ($seasonYear)", LEAGUE_ID)
        return players
    }

    /** The roster of the club behind ([leagueId], [nativeId]) through the crosswalk's `espn` id, or null when the club has none. */
    public suspend fun rosterFor(leagueId: String, nativeId: String, leagueSlug: String, seasonYear: Int): List<Player>? {
        val espnId = Clubs.club(leagueId, nativeId)?.ids?.get(NAMESPACE) ?: return null
        return roster(leagueSlug, espnId, seasonYear)
    }

    private fun player(a: EspnAthlete, teamId: String): Player {
        val name = a.displayName ?: a.fullName ?: listOfNotNull(a.firstName, a.lastName).joinToString(" ").ifBlank { a.id }
        return Player(
            ref = PlayerRef(LEAGUE_ID, a.id, name, a.jersey?.toIntOrNull(), positionCode(a.position?.abbreviation), a.headshot?.href),
            firstName = a.firstName,
            lastName = a.lastName,
            birthDate = Dates.localDateOrNull(a.displayDOB),
            nationality = a.citizenship ?: a.birthPlace?.country,
            heightCm = a.height?.let { (it * 2.54).roundToInt() },
            weightKg = a.weight?.let { (it * 0.45359237).roundToInt() },
            teamId = teamId,
        )
    }

    private fun positionCode(abbreviation: String?): String? = when (abbreviation) {
        "G" -> "GK"; "D" -> "DF"; "M" -> "MF"; "F" -> "FW"; else -> abbreviation
    }

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://site.web.api.espn.com"
        /** The id namespace of the players and clubs this source names; also the crosswalk key. */
        public const val NAMESPACE: String = "espn"
        private const val LEAGUE_ID = NAMESPACE
        /** ESPN says `max-age=300`; a squad changes on transfer days, not by the minute. */
        private val ROSTER_MAX_AGE = 1.hours

        public const val BUNDESLIGA_SLUG: String = "ger.1"
        public const val CHAMPIONS_LEAGUE_SLUG: String = "uefa.champions"
        public const val EUROPA_LEAGUE_SLUG: String = "uefa.europa"
        public const val CONFERENCE_LEAGUE_SLUG: String = "uefa.europa.conf"
    }
}
