package org.openscore.health

import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

class RunnerTest {
    private val today = LocalDate(2026, 9, 12)

    /** Serves canned responses per URL; unknown URLs 404. */
    private class FakeFetcher(private val responses: Map<String, FetchResponse>) : Fetcher {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse {
            requests += url to headers
            return responses[url] ?: FetchResponse(url, 404, "text/html", "<html>nope</html>")
        }
    }

    private fun json(url: String, body: String, status: Int = 200) = FetchResponse(url, status, "application/json", body)

    private fun leagueDir(vararg samples: Pair<String, String>): File {
        val dir = Files.createTempDirectory("health").toFile()
        File(dir, "README.md").writeText("# test")
        File(dir, "samples").mkdirs()
        samples.forEach { (name, body) -> File(dir, "samples/$name").writeText(body) }
        return dir
    }

    private fun file(dir: File, vararg checks: Check, headers: Map<String, String> = emptyMap()) =
        HealthFile("test", "Test", "https://api.test/v1", headers = headers, minDelayMs = 0, checks = checks.toList()).also { it.dir = dir }

    @Test
    fun passesWhenLiveMatchesSample() = runBlocking {
        val dir = leagueDir("games.json" to """{"games":[{"id":1,"state":"FUT"}],"date":"2026-09-11"}""")
        val fetcher = FakeFetcher(mapOf("https://api.test/v1/score/2026-09-12" to json("", """{"games":[{"id":9,"state":"LIVE","clock":"1:00"}],"date":"2026-09-12"}""")))
        val results = Runner(fetcher, Placeholders(today)).run(file(dir, Check(path = "/score/{today}", sample = "games.json", keys = listOf("games[].id"))))
        val r = results.single()
        assertEquals(Status.PASS, r.status, r.message)
        assertEquals(1, r.newPaths) // clock is new
        assertEquals("https://api.test/v1/score/2026-09-12", r.url)
    }

    @Test
    fun warnsOnMissingSamplePathsButExcusesEmptyArrays() = runBlocking {
        val dir = leagueDir("games.json" to """{"games":[{"id":1,"state":"FUT"}],"nextDate":"x"}""")
        val fetcher = FakeFetcher(mapOf(
            "https://api.test/v1/renamed" to json("", """{"games":[{"id":1}],"nextDate":"x"}"""),
            "https://api.test/v1/empty" to json("", """{"games":[],"nextDate":"x"}"""),
        ))
        val runner = Runner(fetcher, Placeholders(today))
        val renamed = runner.runOne(file(dir), Check(path = "/renamed", sample = "games.json"))
        assertEquals(Status.WARN, renamed.status)
        assertEquals(listOf("games[].state"), renamed.missing)

        val empty = runner.runOne(file(dir), Check(path = "/empty", sample = "games.json"))
        assertEquals(Status.PASS, empty.status, empty.message)
    }

    @Test
    fun failsOnStatusRequiredKeysAndNonJson() = runBlocking {
        val dir = leagueDir()
        val fetcher = FakeFetcher(mapOf(
            "https://api.test/v1/html" to FetchResponse("", 200, "text/html", "<html>login</html>"),
            "https://api.test/v1/nokey" to json("", """{"other":1}"""),
        ))
        val runner = Runner(fetcher, Placeholders(today))
        assertEquals(Status.FAIL, runner.runOne(file(dir), Check(path = "/missing")).status)
        assertEquals(Status.FAIL, runner.runOne(file(dir), Check(path = "/html")).status)
        val nokey = runner.runOne(file(dir), Check(path = "/nokey", keys = listOf("games[]")))
        assertEquals(Status.FAIL, nokey.status)
        assertEquals(listOf("games[]"), nokey.missing)
        assertEquals(Status.PASS, runner.runOne(file(dir), Check(path = "/missing", status = listOf(404))).status)
    }

    @Test
    fun graphqlChecksEncodeTheQueryAndRejectErrors() = runBlocking {
        val dir = leagueDir()
        val url = "https://api.test/v1?query=%7Bteam%28abbrv%3A%22AIK%22%29%7Babbrv%7D%7D"
        val fetcher = FakeFetcher(mapOf(url to json("", """{"errors":[{"message":"Unexpected error."}],"data":{"team":null}}""")))
        val r = Runner(fetcher, Placeholders(today)).runOne(file(dir), Check(query = "{team(abbrv:\"AIK\"){abbrv}}"))
        assertEquals(url, fetcher.requests.single().first)
        assertEquals(Status.FAIL, r.status)
        assertTrue(r.message.contains("Unexpected error"), r.message)
    }

    @Test
    fun headersAndSecretsAreResolvedAndSkippedWhenUnavailable() = runBlocking {
        val dir = leagueDir()
        val fetcher = FakeFetcher(mapOf("https://api.test/v1/x" to json("", "{}")))
        val secrets = SecretResolver { if (it == "k.public") "abc" else null }
        val f = file(dir, headers = mapOf("X-Key" to "{secret.k.public}"))
        val runner = Runner(fetcher, Placeholders(today, secrets))
        assertEquals(Status.PASS, runner.runOne(f, Check(path = "/x")).status)
        assertEquals("abc", fetcher.requests.single().second["X-Key"])
        val skipped = runner.runOne(f, Check(path = "/x", headers = mapOf("X-Key" to "{secret.k.missing}")))
        assertEquals(Status.SKIP, skipped.status)
    }

    @Test
    fun xmlChecksCompareElementPaths() = runBlocking {
        val dir = leagueDir("g.xml" to """<games created="1"><game id="1"><score home="0"/></game></games>""")
        val fetcher = FakeFetcher(mapOf(
            "https://api.test/v1/g.xml" to FetchResponse("", 200, "application/xml", """<games created="2"><game id="7"><score home="3"/></game></games>"""),
            "https://api.test/v1/empty.xml" to FetchResponse("", 200, "application/xml", """<games created="2"/>"""),
        ))
        val runner = Runner(fetcher, Placeholders(today))
        val ok = runner.runOne(file(dir), Check(path = "/g.xml", format = "xml", sample = "g.xml", keys = listOf("games/game/@id", "games/game/score")))
        assertEquals(Status.PASS, ok.status, ok.message)
        val empty = runner.runOne(file(dir), Check(path = "/empty.xml", format = "xml", sample = "g.xml", keys = listOf("games")))
        assertEquals(Status.PASS, empty.status, empty.message)
    }
}
