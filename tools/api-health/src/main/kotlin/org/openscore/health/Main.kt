package org.openscore.health

import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.openscore.OpenScore
import org.openscore.net.Fetcher
import org.openscore.net.KtorFetcher
import org.openscore.providers.laliga.LaLigaKeys
import java.io.File
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess
import kotlin.time.Clock

private const val USAGE = """
api-health — checks that every endpoint documented in apis/ still answers and still looks like its sample.

Usage: ./gradlew :tools:api-health:run --args="[options]"

  --league a,b,c      only these league ids (as in health.json / core), default all
  --no-providers      skip driving the core providers (today's games, one game, standings)
  --no-endpoints      skip the health.json endpoint checks
  --list              print the checks that would run, without any request
  --json <file>       write the full report as JSON
  --markdown <file>   write the report as Markdown (GitHub step summary friendly)
  --root <dir>        repository root (default: found from the working directory)
  --fail-on-warn      exit 1 on shape warnings too, not only on failures
  --quiet             only print the summary
  -h, --help
"""

public fun main(args: Array<String>) {
    val opts = Options.parse(args) ?: run { println(USAGE.trim()); exitProcess(2) }
    val root = opts.root ?: findRepoRoot()
    val apisDir = File(root, "apis")
    require(apisDir.isDirectory) { "No apis/ under $root" }

    val files = CheckFiles.discover(apisDir).filter { opts.leagues == null || it.league in opts.leagues }
    if (opts.list) {
        for (f in files) {
            println("${f.name} (${f.league}) — ${f.readme.relativeTo(root)}")
            for (c in f.checks) println("  ${c.label}: ${c.url ?: c.query?.take(60)?.let { "graphql $it…" } ?: f.baseUrl + c.path}${c.sample?.let { " ← samples/$it" } ?: ""}")
        }
        return
    }

    val fetcher = healthFetcher()
    val today = Clock.System.todayIn(TimeZone.UTC)
    val placeholders = Placeholders(today, laLigaSecrets(fetcher))
    val print: (String) -> Unit = if (opts.quiet) ({}) else ({ println(it) })
    val runner = Runner(fetcher, placeholders, log = print)
    val providerChecks = ProviderChecks(today, log = print)
    val providers = OpenScore.default(fetcher).providers.associateBy { it.league.id }

    val leagues = ArrayList<LeagueReport>()
    try {
        runBlocking {
            for (f in files) {
                print("${f.name} — ${f.readme.relativeTo(root)}")
                val results = ArrayList<CheckResult>()
                if (opts.endpoints) results += runner.run(f)
                if (opts.providers) {
                    for (id in f.coveredLeagues) {
                        val p = providers[id] ?: continue
                        results += providerChecks.run(p)
                    }
                }
                leagues += LeagueReport(f.league, f.name, f.readme.relativeTo(root).path, results)
            }
        }
    } finally {
        fetcher.close()
    }

    val report = HealthReport(DateTimeFormatter.ISO_INSTANT.format(ZonedDateTime.now(ZoneOffset.UTC).withNano(0)), leagues)
    println(Report.terminalSummary(report))
    opts.jsonOut?.let { it.parentFile?.mkdirs(); it.writeText(Report.toJson(report)) }
    opts.markdownOut?.let { it.parentFile?.mkdirs(); it.writeText(Report.toMarkdown(report)) }

    val bad = report.failed > 0 || (opts.failOnWarn && report.warned > 0)
    exitProcess(if (bad) 1 else 0)
}

/**
 * Which core league ids a health file speaks for (one Sportomedia file covers Allsvenskan and
 * Superettan — whose games now come from Fogis, so driving those providers exercises both feeds;
 * the Fogis file also covers Svenska Cupen; the UEFA file covers the three club competitions; the
 * EFL file covers the Championship and the Carabao Cup).
 */
private val HealthFile.coveredLeagues: List<String>
    get() = when (league) {
        "allsvenskan" -> listOf("allsvenskan", "superettan")
        "fogis" -> listOf("fogis", "svenska-cupen")
        "ucl" -> listOf("ucl", "uel", "uecl")
        "efl" -> listOf("championship", "carabao-cup")
        else -> listOf(league)
    }

private class Options(
    val leagues: Set<String>?,
    val providers: Boolean,
    val endpoints: Boolean,
    val list: Boolean,
    val jsonOut: File?,
    val markdownOut: File?,
    val root: File?,
    val failOnWarn: Boolean,
    val quiet: Boolean,
) {
    companion object {
        fun parse(args: Array<String>): Options? {
            var leagues: Set<String>? = null
            var providers = true
            var endpoints = true
            var list = false
            var json: File? = null
            var md: File? = null
            var root: File? = null
            var failOnWarn = false
            var quiet = false
            var i = 0
            fun next(): String = args.getOrNull(++i) ?: error("missing value for ${args[i - 1]}")
            while (i < args.size) {
                when (args[i]) {
                    "--league" -> leagues = next().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
                    "--no-providers" -> providers = false
                    "--no-endpoints" -> endpoints = false
                    "--list" -> list = true
                    "--json" -> json = File(next())
                    "--markdown" -> md = File(next())
                    "--root" -> root = File(next())
                    "--fail-on-warn" -> failOnWarn = true
                    "--quiet" -> quiet = true
                    "-h", "--help" -> return null
                    else -> { System.err.println("unknown option ${args[i]}"); return null }
                }
                i++
            }
            return Options(leagues, providers, endpoints, list, json, md, root, failOnWarn, quiet)
        }
    }
}

private fun findRepoRoot(): File {
    var dir: File? = File(".").absoluteFile
    while (dir != null) {
        if (File(dir, "apis").isDirectory && File(dir, "settings.gradle.kts").isFile) return dir
        dir = dir.parentFile
    }
    error("OpenScore repository root not found from ${File(".").absolutePath}; pass --root")
}

/** The core's polite fetcher, with timeouts so one hung endpoint cannot stall the whole run. */
private fun healthFetcher(): KtorFetcher = KtorFetcher(
    engine = OkHttp.create {
        config {
            connectTimeout(15, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
        }
    },
    userAgent = "OpenScore-api-health/0.1 (+https://github.com/icebergvibe/OpenScore)",
)

/** `{secret.laliga.publicService}` / `{secret.laliga.webview}` — read from laliga.com once per run. */
private fun laLigaSecrets(fetcher: Fetcher): SecretResolver {
    var keys: LaLigaKeys? = null
    return SecretResolver { name ->
        if (!name.startsWith("laliga.")) return@SecretResolver null
        val k = keys ?: LaLigaKeys.discover(fetcher).also { keys = it }
        when (name.removePrefix("laliga.")) {
            "publicService" -> k.publicService
            "webview" -> k.webview
            else -> null
        }
    }
}
