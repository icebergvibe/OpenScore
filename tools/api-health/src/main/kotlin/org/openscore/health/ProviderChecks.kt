package org.openscore.health

import kotlinx.coroutines.delay
import kotlinx.datetime.LocalDate
import org.openscore.provider.Capability
import org.openscore.provider.LeagueProvider
import kotlin.time.TimeSource

/**
 * The other half of "still works": drives a core [LeagueProvider] through its discovery path
 * (today's games → one game → standings) so a feed that answers 200 but no longer parses into
 * the model is caught too. Three to four requests per league.
 */
public class ProviderChecks(
    private val today: LocalDate,
    private val delayMs: Long = 250,
    private val log: (String) -> Unit = {},
) {
    public suspend fun run(provider: LeagueProvider): List<CheckResult> {
        val league = provider.league.id
        val results = ArrayList<CheckResult>(3)

        val games = measure(league, "provider.gamesOn($today)") { provider.gamesOn(today) }
        results += games.result.copy(message = games.value?.let { "${it.size} games" } ?: games.result.message)
        log(Report.line(results.last()))

        val first = games.value?.firstOrNull()
        if (first != null && provider.supports(Capability.GAME)) {
            delay(delayMs)
            val game = measure(league, "provider.game(${first.id})") { provider.game(first.id) }
            results += game.result.copy(message = game.value?.let { "${it.away.name} @ ${it.home.name}: ${it.state}${it.score?.let { s -> " $s" } ?: ""}" } ?: game.result.message)
            log(Report.line(results.last()))
        }

        if (provider.supports(Capability.STANDINGS)) {
            delay(delayMs)
            val table = measure(league, "provider.standings()") { provider.standings() }
            results += table.result.copy(message = table.value?.let { "${it.rows.size} rows, season ${it.seasonId}" } ?: table.result.message)
            log(Report.line(results.last()))
        }
        return results
    }

    private class Measured<T>(val result: CheckResult, val value: T?)

    private suspend fun <T> measure(league: String, name: String, block: suspend () -> T): Measured<T> {
        val mark = TimeSource.Monotonic.markNow()
        return try {
            val value = block()
            Measured(CheckResult(league, name, null, Status.PASS, millis = mark.elapsedNow().inWholeMilliseconds), value)
        } catch (e: Exception) {
            Measured(CheckResult(league, name, null, Status.FAIL, millis = mark.elapsedNow().inWholeMilliseconds, message = "${e::class.simpleName}: ${e.message?.lineSequence()?.firstOrNull()?.take(200)}"), null)
        }
    }
}
