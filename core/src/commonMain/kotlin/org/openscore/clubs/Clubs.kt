package org.openscore.clubs

import org.openscore.model.TeamRef

/**
 * The crosswalk from a league's native team id to an OpenScore [Club]. [TeamRef.clubId] is
 * filled from here, so the same club resolves to one id whether it comes from the Premier
 * League or Champions League feed, or from Allsvenskan or the Svenska Cupen feed.
 *
 * The table is hand-curated ([ClubTable]); no feed shares an id system with another, so
 * name matching would be wrong exactly where it matters (Hammarby IF vs Hammarby TFF, the two
 * Athletics, Inter vs Inter Miami). What the feeds do share is verified, not derived:
 * `ClubsCoverageLiveTest` checks every team in every league's standings has an entry and that
 * bridges a feed exposes (Sportomedia's `fogisId`) agree with the table.
 */
public object Clubs {

    public val all: List<Club> get() = ClubTable.clubs

    private val byId: Map<String, Club> = ClubTable.clubs.associateBy { it.id }

    private val byNativeId: Map<Pair<String, String>, Club> = buildMap {
        for (club in ClubTable.clubs) for ((namespace, id) in club.ids) put(namespace to id, club)
    }

    /** Leagues whose feed keys teams in another league's id space. */
    private val sharedNamespace: Map<String, String> = mapOf(
        "uel" to "ucl",
        "uecl" to "ucl",
        // The Swedish leagues are served from the Fogis feed and carry Fogis team ids throughout —
        // games, tables and team pages alike; `sportomedia` (allsvenskan.se's abbreviations) is
        // the one namespace that is not a league id.
        "allsvenskan" to "fogis",
        "superettan" to "fogis",
        "svenska-cupen" to "fogis",
        "hockeyallsvenskan" to "shl",
    )

    /** The team-id namespace [leagueId] uses in [Club.ids]. */
    public fun namespace(leagueId: String): String = sharedNamespace[leagueId] ?: leagueId

    /** Namespace of the abbreviations Sportomedia (allsvenskan.se) keys teams by, e.g. `HAM`. */
    public const val SPORTOMEDIA: String = "sportomedia"

    public fun byId(clubId: String): Club? = byId[clubId]

    public fun club(leagueId: String, nativeId: String): Club? = byNativeId[namespace(leagueId) to nativeId]

    public fun clubId(leagueId: String, nativeId: String): String? = club(leagueId, nativeId)?.id
}
