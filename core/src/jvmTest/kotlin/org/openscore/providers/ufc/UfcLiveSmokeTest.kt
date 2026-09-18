package org.openscore.providers.ufc

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.openscore.model.GameState
import org.openscore.net.KtorFetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Talks to the real API: the cold sweep from the seed, a card day, a finished fight with its
 * timeline and statistics. Off by default; `-Dopenscore.live=true --tests '*UfcLiveSmokeTest*'`.
 */
class UfcLiveSmokeTest {
    @Test
    fun sweepsCardsAndReadsAFight() {
        if (System.getProperty("openscore.live") != "true") return
        runBlocking {
            val fetcher = KtorFetcher()
            val ufc = UfcProvider(fetcher)
            val t0 = System.currentTimeMillis()
            val upcoming = ufc.gamesOn(LocalDate.parse("2026-09-19"))
            println("UFC 331 day: ${upcoming.size} fights in ${System.currentTimeMillis() - t0} ms")
            upcoming.forEach { println("  ${it.id} ${it.startTime} ${it.state} ${it.home.name} v ${it.away.name} · ${it.rawState}") }
            assertTrue(upcoming.isNotEmpty())

            val t1 = System.currentTimeMillis()
            val final = ufc.game("1320-12874")
            println("game: ${final.state} ${final.situation} events=${final.events?.size} stats=${final.stats.keys} in ${System.currentTimeMillis() - t1} ms")
            assertEquals(GameState.FINAL, final.state)
            assertTrue(final.events!!.isNotEmpty())
            assertTrue(final.stats.isNotEmpty())

            val t2 = System.currentTimeMillis()
            val again = ufc.gamesOn(LocalDate.parse("2026-09-19"))
            println("second day read: ${again.size} fights in ${System.currentTimeMillis() - t2} ms (should be instant)")
            fetcher.close()
        }
    }
}
