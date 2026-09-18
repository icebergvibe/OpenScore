package org.openscore.providers.nhl

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonElement
import org.openscore.net.OpenScoreJson
import org.openscore.testing.SampleFetcher
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Every file in apis/hockey/nhl/samples must parse: with its DTO where the provider uses
 * the endpoint, and at least as JSON otherwise (so a broken sample is caught too).
 */
class NhlDtoTest {

    private val dir = SampleFetcher.samplesDir("hockey", "nhl")

    private val strategies: List<Pair<String, DeserializationStrategy<*>>> = listOf(
        "score." to NhlScoreResponse.serializer(),
        "gamecenter-play-by-play." to NhlPlayByPlay.serializer(),
        "gamecenter-boxscore." to NhlBoxscore.serializer(),
        "standings.json" to NhlStandingsResponse.serializer(),
        "standings-season.json" to NhlStandingsSeasons.serializer(),
        "roster-current.json" to NhlRoster.serializer(),
        "player-landing.json" to NhlPlayerLanding.serializer(),
    )

    @Test
    fun everySampleParses() {
        val files = dir.listFiles { f -> f.extension == "json" }!!.sortedBy { it.name }
        assertTrue(files.size >= 30, "expected the NHL sample set, found ${files.size} files")
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
        assertTrue(typed >= 12, "expected at least 12 samples to parse through DTOs, got $typed")
    }
}
