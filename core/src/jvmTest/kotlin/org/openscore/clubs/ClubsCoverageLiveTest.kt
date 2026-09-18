package org.openscore.clubs

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.openscore.OpenScore
import org.openscore.model.Sport
import org.openscore.model.TeamRef
import org.openscore.net.KtorFetcher
import org.openscore.provider.Capability
import org.openscore.provider.LeagueProvider
import org.openscore.providers.espn.EspnRosters
import org.openscore.providers.fogis.SwedishLeagueProvider
import org.openscore.providers.sportomedia.AllsvenskanProvider
import org.openscore.providers.sportomedia.SportomediaProvider
import org.openscore.providers.sportomedia.SuperettanProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours

/**
 * The season check for the club crosswalk, against the real feeds: every team in every
 * league's standings (or, before the first round, its next three weeks of fixtures) must be
 * in [ClubTable], and the bridges a feed exposes must agree with
 * it (Sportomedia's `fogisId` is the Fogis team id). Unmapped teams are printed as
 * ready-to-paste `club(...)` lines — curate the slug, then add them.
 *
 * Off by default; `./gradlew :core:jvmTest -Dopenscore.live=true --tests '*ClubsCoverageLiveTest*'`.
 */
class ClubsCoverageLiveTest {

    @Test
    fun everyStandingsTeamHasAClub() {
        if (System.getProperty("openscore.live") != "true") return
        val fetcher = KtorFetcher()
        val problems = ArrayList<String>()
        try {
            val all = OpenScore.default(fetcher)
            runBlocking {
                for (p in all.providers) {
                    if (!p.supports(Capability.STANDINGS)) continue
                    val table = runCatching { p.standings() }.getOrElse { e -> problems += "${p.league.id}: standings failed: $e"; continue }
                    // Before the first round the table is empty; the next three weeks of fixtures name the teams instead.
                    val teams = table.rows.map { it.team }.ifEmpty { upcomingTeams(p) }.distinctBy { it.id }
                    val unmapped = teams.filter { it.clubId == null }
                    println("${p.league.id}: ${teams.size} teams, ${unmapped.size} unmapped")
                    for (t in unmapped) println("    " + draftLine(fetcher, p, t))
                    if (unmapped.isNotEmpty()) problems += "${p.league.id}: ${unmapped.size} teams without a club: ${unmapped.joinToString { it.name }}"
                    // Squads for these leagues come from ESPN: a club without an `espn` id and without a covered domestic league has no squad.
                    if (p.league.id in ESPN_SQUAD_LEAGUES) {
                        val noSquad = teams.mapNotNull { t -> t.clubId?.let(Clubs::byId) }
                            .filter { c -> c.ids[EspnRosters.NAMESPACE] == null && c.ids.keys.none { it in DOMESTIC_SQUAD_NAMESPACES } }
                        if (noSquad.isNotEmpty()) problems += "${p.league.id}: ${noSquad.size} clubs without an espn id (look them up in ESPN's team directory): ${noSquad.joinToString { it.id }}"
                    }

                }
                // The Swedish leagues carry Fogis ids; Sportomedia's own `fogisId` bridge must agree with
                // both the `fogis` and the `sportomedia` (abbreviation) entries on each club's line.
                for (sm in listOf(AllsvenskanProvider(fetcher), SuperettanProvider(fetcher))) {
                    val listed = runCatching { sm.teams() }.getOrElse { e -> problems += "${sm.league.id}: teamsForLeague failed: $e"; continue }
                    for (t in listed) {
                        val fogisId = t.fogisId?.toString() ?: continue
                        val club = Clubs.club(Clubs.SPORTOMEDIA, t.abbrv)
                            ?: run { problems += "${sm.league.id}: ${t.abbrv} (${t.displayName}) has no club with a sportomedia id; fogis=$fogisId"; continue }
                        val tabled = club.ids["fogis"]
                        if (tabled != fogisId) problems += "${club.id}: table says fogis=${tabled}, ${sm.league.id} feed says fogisId=$fogisId"
                    }
                }
            }
        } finally {
            fetcher.close()
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }

    private suspend fun upcomingTeams(p: LeagueProvider): List<TeamRef> {
        var date = Clock.System.todayIn(TimeZone.UTC)
        val teams = ArrayList<TeamRef>()
        repeat(21) {
            runCatching { p.gamesOn(date) }.getOrElse { emptyList() }.forEach { g -> teams += g.home; teams += g.away }
            date = date.plus(1, DateTimeUnit.DAY)
        }
        return teams
    }

    private suspend fun draftLine(fetcher: KtorFetcher, p: LeagueProvider, t: TeamRef): String {
        val country = if (p.league.id in setOf("ucl", "uel", "uecl", "chl")) runCatching { p.team(t.id).country }.getOrNull() else p.league.country
        val ids = ArrayList<String>()
        ids += "\"${Clubs.namespace(p.league.id)}\" to \"${t.id}\""
        if (p is SportomediaProvider) fogisId(fetcher, t.id)?.let { ids += "\"fogis\" to \"$it\"" }
        if (p is SwedishLeagueProvider) t.abbreviation?.let { ids += "\"${Clubs.SPORTOMEDIA}\" to \"$it\"" }
        val sport = when (p.league.sport) { Sport.HOCKEY -> "HOCKEY"; Sport.FOOTBALL -> "FOOTBALL"; Sport.BASEBALL -> "BASEBALL"; Sport.MOTORSPORT -> "MOTORSPORT" }
        return "club(\"${slug(t.name)}\", \"${t.name}\", $sport, ${country?.let { "\"$it\"" }}, ${ids.joinToString()}),"
    }

    private suspend fun fogisId(fetcher: KtorFetcher, abbrv: String): String? {
        val url = SportomediaProvider.DEFAULT_BASE_URL + "?query=" + SportomediaProvider.Queries.team(abbrv).encodeURLParameter()
        val body = fetcher.get(url, maxAge = 1.hours).requireSuccess().body
        val team = Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject?.get("team") ?: return null
        return team.jsonObject["fogisId"]?.jsonPrimitive?.content?.takeIf { it != "null" }
    }

    private fun slug(name: String): String = name.lowercase()
        .map { FOLD[it] ?: it }.joinToString("")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private companion object {
        val ESPN_SQUAD_LEAGUES = setOf("bundesliga", "ucl", "uel", "uecl")
        val DOMESTIC_SQUAD_NAMESPACES = setOf("premier-league", "la-liga", "serie-a", "ligue1", "mls", "sportomedia", "malta-premier")
        val FOLD = mapOf('å' to 'a', 'ä' to 'a', 'ö' to 'o', 'ø' to 'o', 'æ' to 'a', 'é' to 'e', 'è' to 'e', 'ü' to 'u', 'ñ' to 'n', 'ç' to 'c', 'í' to 'i', 'ó' to 'o', 'á' to 'a', 'ú' to 'u', 'ß' to 's', 'ë' to 'e', 'ı' to 'i', 'ş' to 's', 'ğ' to 'g', 'ć' to 'c', 'č' to 'c', 'š' to 's', 'ž' to 'z', 'ł' to 'l', 'ń' to 'n', 'ő' to 'o', 'ř' to 'r', 'ě' to 'e', 'ý' to 'y')
    }
}
