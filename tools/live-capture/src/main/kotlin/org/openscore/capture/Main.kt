package org.openscore.capture

import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.openscore.OpenScore
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.net.Fetcher
import org.openscore.net.KtorFetcher
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private const val USAGE = """
live-capture — polls a game through its core provider and keeps every raw response and mapped state transition.

Usage: ./gradlew :tools:live-capture:run --args="--league <id> [selection] [options]"

Selection (default: list the day's games and exit)
  --league a,b        core league ids (as in OpenScore.default): shl, liiga, premier-league, fogis, …
  --date YYYY-MM-DD   the day to look at, in the league's own date convention (default: today UTC)
  --game id[,id]      capture these game ids (with several leagues, or none, write league/id)
  --next              capture the first game of the day that is not finished yet
  --all               capture every game of the day that is not finished yet
  --live              capture every game of the day that is live right now

Options
  --interval 15s      poll interval while live (floor 10s)            --idle 60s      while more than 15 min before kick-off
  --checkpoint 5m     save a full snapshot at least this often live   --after-final 3  extra polls after FINAL, 60 s apart
  --max 5h            give up after this long                         --out build/capture
  --no-events         do not call provider.events() per poll          --no-lineups    do not call provider.lineups() per poll
  -h, --help

Output: <out>/<league>/<gameId>/ticks.jsonl (the model per poll), NNNN-<state>-<endpoint>.json (raw bodies when
something changed), NNNN-<state>.game.json (Feed v1 rendering), index.jsonl (file ↔ URL). Curate into apis/**/samples by hand.
"""

public fun main(args: Array<String>) {
    val opts = Options.parse(args) ?: run { println(USAGE.trim()); exitProcess(2) }
    val fetcher = captureFetcher()
    val date = opts.date ?: Clock.System.todayIn(TimeZone.UTC)
    try {
        runBlocking {
            val shared: Fetcher = fetcher
            val lookup = OpenScore.default(shared)
            val selected = ArrayList<Pair<String, String>>() // league id → game id
            for (spec in opts.gameIds) {
                val league = spec.substringBefore('/', "").ifEmpty { opts.leagues.singleOrNull() ?: error("'$spec': write league/id, or give exactly one --league") }
                selected += lookup.provider(league).league.id to spec.substringAfter('/')
            }
            for (leagueId in opts.leagues) {
                if (opts.gameIds.isNotEmpty()) break
                val provider = lookup.provider(leagueId)
                val games = provider.gamesOn(date)
                println("${provider.league.name} — ${games.size} games on $date")
                for (g in games) println("  ${g.id.padEnd(40)} ${g.startTime}  ${g.state.name.padEnd(12)} ${g.away.name} @ ${g.home.name}${g.score?.let { "  ${it.home}-${it.away}" } ?: ""}${g.competition?.let { "  ($it)" } ?: ""}")
                val open = games.filter { !it.state.isTerminal }
                val picked: List<Game> = when (opts.mode) {
                    Mode.LIST -> emptyList()
                    Mode.NEXT -> listOfNotNull(open.minByOrNull { it.startTime })
                    Mode.ALL -> open
                    Mode.LIVE -> open.filter { it.state.isLive || it.state == GameState.PRE_GAME }
                }
                picked.forEach { selected += leagueId to it.id }
            }
            if (selected.isEmpty()) {
                if (opts.mode != Mode.LIST) println("nothing to capture")
                return@runBlocking
            }
            println("capturing ${selected.size} game(s): ${selected.joinToString { "${it.first}/${it.second}" }} → ${opts.out}")
            coroutineScope {
                selected.map { (leagueId, gameId) ->
                    async {
                        val recording = RecordingFetcher(shared)
                        val provider = OpenScore.default(recording).provider(leagueId)
                        val dir = File(opts.out, "$leagueId/${gameId.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
                        Capture(provider, recording, gameId, dir, opts.capture, log = { println(it) }).run()
                    }
                }.awaitAll()
            }
        }
    } finally {
        fetcher.close()
    }
}

private enum class Mode { LIST, NEXT, ALL, LIVE }

private class Options(
    val leagues: List<String>,
    val date: LocalDate?,
    val gameIds: List<String>,
    val mode: Mode,
    val out: File,
    val capture: CaptureOptions,
) {
    companion object {
        fun parse(args: Array<String>): Options? {
            var leagues: List<String> = emptyList()
            var date: LocalDate? = null
            var games: List<String> = emptyList()
            var mode = Mode.LIST
            var out = File("build/capture")
            var interval = 15.seconds
            var idle = 60.seconds
            var checkpoint = 5.minutes
            var afterFinal = 3
            var max = 5.hours
            var events = true
            var lineups = true
            var i = 0
            fun next(): String = args.getOrNull(++i) ?: error("missing value for ${args[i - 1]}")
            while (i < args.size) {
                when (args[i]) {
                    "--league" -> leagues = next().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "--date" -> date = LocalDate.parse(next())
                    "--game" -> games = next().split(',').map { it.trim() }.filter { it.isNotEmpty() }
                    "--next" -> mode = Mode.NEXT
                    "--all" -> mode = Mode.ALL
                    "--live" -> mode = Mode.LIVE
                    "--interval" -> interval = Duration.parse(next())
                    "--idle" -> idle = Duration.parse(next())
                    "--checkpoint" -> checkpoint = Duration.parse(next())
                    "--after-final" -> afterFinal = next().toInt()
                    "--max" -> max = Duration.parse(next())
                    "--out" -> out = File(next())
                    "--no-events" -> events = false
                    "--no-lineups" -> lineups = false
                    "-h", "--help" -> return null
                    else -> { System.err.println("unknown option ${args[i]}"); return null }
                }
                i++
            }
            if (leagues.isEmpty() && games.none { '/' in it }) { System.err.println("--league is required"); return null }
            val capture = CaptureOptions(
                interval = interval, idleInterval = idle, checkpoint = checkpoint, afterFinal = afterFinal,
                afterFinalInterval = 60.seconds, maxDuration = max, withEvents = events, withLineups = lineups,
            )
            return Options(leagues, date, games, mode, out, capture)
        }
    }
}

/** The core's polite fetcher, with timeouts so one hung endpoint cannot stall a capture. */
private fun captureFetcher(): KtorFetcher = KtorFetcher(
    engine = OkHttp.create {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
        }
    },
    userAgent = "OpenScore-live-capture/0.1 (+https://github.com/icebergvibe/OpenScore)",
)
