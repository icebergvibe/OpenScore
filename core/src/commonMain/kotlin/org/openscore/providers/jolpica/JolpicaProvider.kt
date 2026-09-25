package org.openscore.providers.jolpica

import kotlinx.datetime.TimeZone
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.openscore.model.League
import org.openscore.model.Sport
import org.openscore.model.motorsport.RacingClassification
import org.openscore.model.motorsport.RacingResult
import org.openscore.model.motorsport.RacingRound
import org.openscore.model.motorsport.RacingSeason
import org.openscore.model.motorsport.RacingSession
import org.openscore.model.motorsport.RacingSessionKind
import org.openscore.model.motorsport.RacingStanding
import org.openscore.model.motorsport.RacingStandings
import org.openscore.net.Fetcher
import org.openscore.provider.RacingProvider
import org.openscore.provider.getJson
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Jolpica F1 (https://github.com/jolpica/jolpica-f1, Apache-2.0): the open-source, community-run,
 * Ergast-compatible F1 API. It publishes schedules, classifications and tables, not live timing.
 * Published limits are 4 requests/s burst and 500/hour unauthenticated; every read here is cached a day.
 */
public class JolpicaProvider(private val fetcher: Fetcher, private val baseUrl: String = DEFAULT_BASE_URL) : RacingProvider {
    override val league: League = LEAGUE

    override suspend fun season(year: Int): RacingSeason {
        val races = get("/$year.json?limit=100").mrData.raceTable?.races.orEmpty()
        return RacingSeason(year, races.mapNotNull { it.round()?.let { round -> it.toRound(year, round) } })
    }

    override suspend fun classification(year: Int, round: Int, kind: RacingSessionKind): RacingClassification {
        val path = when (kind) { RacingSessionKind.RACE -> "results"; RacingSessionKind.QUALIFYING -> "qualifying"; RacingSessionKind.SPRINT -> "sprint"; RacingSessionKind.PRACTICE, RacingSessionKind.SPRINT_QUALIFYING -> return RacingClassification(sessionId(year, round, kind), emptyList()) }
        val race = get("/$year/$round/$path.json?limit=100").mrData.raceTable?.races?.firstOrNull()
        val results = when (kind) { RacingSessionKind.RACE -> race?.results; RacingSessionKind.QUALIFYING -> race?.qualifyingResults; RacingSessionKind.SPRINT -> race?.sprintResults; RacingSessionKind.PRACTICE, RacingSessionKind.SPRINT_QUALIFYING -> emptyList() }.orEmpty()
        return RacingClassification(sessionId(year, round, kind), results.map(::result))
    }

    override suspend fun standings(year: Int): RacingStandings = coroutineScope {
        val driverCall = async { get("/$year/driverstandings.json?limit=100") }
        val constructorCall = async { get("/$year/constructorstandings.json?limit=100") }
        val driver = driverCall.await().mrData.standingsTable?.lists?.firstOrNull()
        val constructor = constructorCall.await().mrData.standingsTable?.lists?.firstOrNull()
        RacingStandings(year, driver?.drivers.orEmpty().map { RacingStanding(it.position.toIntOrNull() ?: 0, it.driver.name(), it.constructors.firstOrNull()?.name, it.points.orEmpty(), it.wins) }, constructor?.constructors.orEmpty().map { RacingStanding(it.position.toIntOrNull() ?: 0, it.constructor.name.orEmpty(), null, it.points.orEmpty(), it.wins) })
    }

    private suspend fun get(path: String): JolpicaResponse = fetcher.getJson(baseUrl + path, JolpicaResponse.serializer(), 1.days, LEAGUE.id)

    public companion object {
        public const val DEFAULT_BASE_URL: String = "https://api.jolpi.ca/ergast/f1"
        public val LEAGUE: League = League("f1", Sport.MOTORSPORT, "Formula 1", "INT", TimeZone.UTC, "https://jolpi.ca")
        public fun sessionId(year: Int, round: Int, kind: RacingSessionKind): String = "f1-$year-$round-${kind.name.lowercase()}"
    }
}

private fun JolpicaRace.round(): Int? = round.toIntOrNull()
private fun JolpicaRace.toRound(year: Int, round: Int): RacingRound = RacingRound("$year-$round", round, name.orEmpty(), circuit?.name, circuit?.location?.locality, circuit?.location?.country, listOfNotNull(session("Practice 1", RacingSessionKind.PRACTICE, firstPractice, year, round, "-1"), session("Practice 2", RacingSessionKind.PRACTICE, secondPractice, year, round, "-2"), session("Practice 3", RacingSessionKind.PRACTICE, thirdPractice, year, round, "-3"), session("Sprint qualifying", RacingSessionKind.SPRINT_QUALIFYING, sprintQualifying, year, round), session("Sprint", RacingSessionKind.SPRINT, sprint, year, round), session("Qualifying", RacingSessionKind.QUALIFYING, qualifying, year, round), session("Race", RacingSessionKind.RACE, JolpicaTime(date, time), year, round)))

/** The three practice sessions share a kind, so their ids carry an ordinal to stay distinct. */
private fun session(name: String, kind: RacingSessionKind, time: JolpicaTime?, year: Int, round: Int, suffix: String = ""): RacingSession? = time?.instant()?.let { RacingSession(JolpicaProvider.sessionId(year, round, kind) + suffix, "$year-$round", name, kind, it) }
private fun JolpicaTime.instant(): Instant? = date?.let { d -> runCatching { Instant.parse("${d}T${time?.ifBlank { "00:00:00Z" } ?: "00:00:00Z"}") }.getOrNull() }
private fun result(value: JolpicaResult): RacingResult = RacingResult(value.position?.toIntOrNull(), value.positionText, value.driver.name(), value.driver.code, value.constructor?.name, value.points, value.grid?.toIntOrNull(), value.time?.time ?: value.status, value.fastestLap?.rank == "1", listOfNotNull(value.q1, value.q2, value.q3))
private fun JolpicaDriver?.name(): String = listOfNotNull(this?.givenName, this?.familyName).joinToString(" ").ifBlank { this?.code.orEmpty() }

@Serializable private data class JolpicaResponse(@SerialName("MRData") val mrData: JolpicaData)
@Serializable private data class JolpicaData(@SerialName("RaceTable") val raceTable: JolpicaRaceTable? = null, @SerialName("StandingsTable") val standingsTable: JolpicaStandingsTable? = null)
@Serializable private data class JolpicaRaceTable(@SerialName("Races") val races: List<JolpicaRace> = emptyList())
@Serializable private data class JolpicaRace(val round: String = "", @SerialName("raceName") val name: String? = null, @SerialName("Circuit") val circuit: JolpicaCircuit? = null, val date: String? = null, val time: String? = null, @SerialName("FirstPractice") val firstPractice: JolpicaTime? = null, @SerialName("SecondPractice") val secondPractice: JolpicaTime? = null, @SerialName("ThirdPractice") val thirdPractice: JolpicaTime? = null, @SerialName("SprintQualifying") val sprintQualifying: JolpicaTime? = null, @SerialName("Sprint") val sprint: JolpicaTime? = null, @SerialName("Qualifying") val qualifying: JolpicaTime? = null, @SerialName("Results") val results: List<JolpicaResult>? = null, @SerialName("QualifyingResults") val qualifyingResults: List<JolpicaResult>? = null, @SerialName("SprintResults") val sprintResults: List<JolpicaResult>? = null)
@Serializable private data class JolpicaCircuit(@SerialName("circuitName") val name: String? = null, @SerialName("Location") val location: JolpicaLocation? = null)
@Serializable private data class JolpicaLocation(val locality: String? = null, val country: String? = null)
@Serializable private data class JolpicaTime(val date: String? = null, val time: String? = null)
@Serializable private data class JolpicaResult(val position: String? = null, @SerialName("positionText") val positionText: String? = null, val points: String? = null, @SerialName("Driver") val driver: JolpicaDriver = JolpicaDriver(), @SerialName("Constructor") val constructor: JolpicaConstructor? = null, val grid: String? = null, val status: String? = null, @SerialName("Time") val time: JolpicaResultTime? = null, @SerialName("FastestLap") val fastestLap: JolpicaFastestLap? = null, @SerialName("Q1") val q1: String? = null, @SerialName("Q2") val q2: String? = null, @SerialName("Q3") val q3: String? = null)
@Serializable private data class JolpicaResultTime(val time: String? = null)
@Serializable private data class JolpicaFastestLap(val rank: String? = null)
@Serializable private data class JolpicaDriver(@SerialName("givenName") val givenName: String? = null, @SerialName("familyName") val familyName: String? = null, val code: String? = null)
@Serializable private data class JolpicaConstructor(val name: String? = null)
@Serializable private data class JolpicaStandingsTable(@SerialName("StandingsLists") val lists: List<JolpicaStandingsList> = emptyList())
@Serializable private data class JolpicaStandingsList(@SerialName("DriverStandings") val drivers: List<JolpicaDriverStanding> = emptyList(), @SerialName("ConstructorStandings") val constructors: List<JolpicaConstructorStanding> = emptyList())
@Serializable private data class JolpicaDriverStanding(val position: String = "", val points: String? = null, val wins: String? = null, @SerialName("Driver") val driver: JolpicaDriver = JolpicaDriver(), @SerialName("Constructors") val constructors: List<JolpicaConstructor> = emptyList())
@Serializable private data class JolpicaConstructorStanding(val position: String = "", val points: String? = null, val wins: String? = null, @SerialName("Constructor") val constructor: JolpicaConstructor = JolpicaConstructor())
