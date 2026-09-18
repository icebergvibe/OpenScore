package org.openscore.providers.mlb

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import org.openscore.net.OpenScoreJson
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every file in apis/baseball/mlb/samples must parse: with its DTO where the provider uses
 * the endpoint, and at least as JSON otherwise. `*.fields.json` samples are deliberately
 * partial responses and only need to be JSON (except the full play list, which is typed).
 */
class MlbDtoTest {

    private val dir = SampleFetcher.samplesDir("baseball", "mlb")

    private val strategies: List<Pair<String, DeserializationStrategy<*>?>> = listOf(
        "playByPlay.final.fields.json" to MlbPlays.serializer(),
        "schedule.fields.json" to null,
        "feed-live.fields.json" to null,
        "schedule." to MlbSchedule.serializer(),
        "schedule-postseason" to MlbSchedule.serializer(),
        "feed-live." to MlbLiveFeed.serializer(),
        "linescore." to MlbLinescore.serializer(),
        "boxscore." to MlbBoxscore.serializer(),
        "playByPlay." to MlbPlays.serializer(),
        "standings." to MlbStandings.serializer(),
        "teams.json" to MlbTeamsResponse.serializer(),
        "team.json" to MlbTeamsResponse.serializer(),
        "team-stats." to MlbTeamStats.serializer(),
        "roster." to MlbRoster.serializer(),
        "people.json" to MlbPeopleResponse.serializer(),
    )

    @Test
    fun everySampleParses() {
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 50, "expected the MLB sample set, found ${files.size} files")
        val failures = mutableListOf<String>()
        var typed = 0
        for (file in files) {
            val text = file.readText()
            val strategy = strategies.firstOrNull { (prefix, _) -> file.name.startsWith(prefix) }?.second
            try {
                if (strategy != null) {
                    OpenScoreJson.decodeFromString(strategy, text)
                    typed++
                } else {
                    OpenScoreJson.decodeFromString(JsonElement.serializer(), text)
                }
            } catch (e: Exception) {
                failures += "${file.name}: ${e.message?.lineSequence()?.first()}"
            }
        }
        if (failures.isNotEmpty()) fail(failures.joinToString("\n"))
        assertTrue(typed >= 25, "expected at least 25 samples to parse through DTOs, got $typed")
    }
}
