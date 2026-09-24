package org.openscore.testing

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import java.io.File
import java.util.Collections
import kotlin.time.Duration

/**
 * Serves captured samples from `apis/<sport>/<league>/samples` instead of the network. Used by the core
 * tests, the feed-server tests, and the server's `--offline` mode so an app can be
 * developed without touching any league API.
 */
public class SampleFetcher : Fetcher {

    private class Route(val file: File?, val contentType: String, val status: Int = 200, val body: String = "")

    private val routes = HashMap<String, Route>()
    /** Every URL asked for, in order. The aggregator asks leagues concurrently, so appends are synchronised. */
    public val requests: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Map full [url] to a sample [file]. */
    public fun route(url: String, file: File, contentType: String = "application/json; charset=utf-8", status: Int = 200): SampleFetcher {
        require(file.isFile) { "Sample not found: ${file.absolutePath}" }
        routes[url] = Route(file, contentType, status)
        return this
    }

    /** Map [url] to an empty 200 body (what Sportality returns before a game starts). */
    public fun emptyRoute(url: String): SampleFetcher {
        routes[url] = Route(null, "application/json")
        return this
    }

    /** Map [url] to a Firebase-style `200 null` body. */
    public fun nullRoute(url: String): SampleFetcher {
        routes[url] = Route(null, "application/json", body = "null")
        return this
    }

    /** Map every entry of [paths] (URL path → file name) under [baseUrl] to files in [dir]. */
    public fun routes(baseUrl: String, dir: File, paths: Map<String, String>): SampleFetcher {
        paths.forEach { (path, name) -> route(baseUrl + path, File(dir, name)) }
        return this
    }

    override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse {
        requests += url
        val route = routes[url]
            ?: return FetchResponse(url, 404, "text/html", "<html><body>404 Not Found</body></html>")
        val file = route.file ?: return FetchResponse(url, route.status, route.contentType, route.body)
        if (route.status != 200) return FetchResponse(url, route.status, route.contentType, file.readText())
        return FetchResponse(url, route.status, route.contentType, unwrapTruncated(file.readText()))
    }

    /**
     * Truncated bare-array samples are stored as `{"_openscore_note": …, "_truncated_array": [...]}`
     * (see CONTRIBUTING.md); serve the array so the DTOs see the real shape.
     */
    private fun unwrapTruncated(text: String): String {
        if (!text.contains("\"_truncated_array\"")) return text
        val obj = Json.parseToJsonElement(text).jsonObject
        return obj["_truncated_array"]?.toString() ?: text
    }

    public companion object {
        /** Locate `apis/` from the current working directory or any parent (Gradle runs tests in the module dir). */
        public fun repoRoot(): File {
            var dir: File? = File(".").absoluteFile
            while (dir != null) {
                if (File(dir, "apis").isDirectory && File(dir, "settings.gradle.kts").isFile) return dir
                dir = dir.parentFile
            }
            error("OpenScore repository root not found from ${File(".").absolutePath}")
        }

        public fun samplesDir(sport: String, league: String, root: File = repoRoot()): File =
            File(root, "apis/$sport/$league/samples").also { require(it.isDirectory) { "No samples at $it" } }

        /** A fetcher preloaded with every league's sample routes. */
        public fun allLeagues(root: File = repoRoot()): SampleFetcher = SampleFetcher().also {
            NhlSamples.register(it, root)
            LiigaSamples.register(it, root)
            SportalitySamples.SHL.register(it, root)
            HockeyAllsvenskanSamples.register(it, root)
            ChlSamples.register(it, root)
            KhlSamples.register(it, root)
            DelSamples.register(it, root)
            SslSamples.register(it, root)
            FliigaSamples.register(it, root)
            Ligue1Samples.register(it, root)
            BundesligaSamples.register(it, root = root)
            PremierLeagueSamples.register(it, root)
            EflSamples.register(it, root)
            SerieASamples.register(it, root)
            LaLigaSamples.register(it, root)
            MlsSamples.register(it, root)
            MaltaSamples.register(it, root)
            AllsvenskanSamples.register(it, root)
            FogisSamples.register(it, root)
            UefaSamples.register(it, root)
            EspnSamples.register(it, root)
            MlbSamples.register(it, root)
            UfcSamples.register(it, root)
        }
    }
}
