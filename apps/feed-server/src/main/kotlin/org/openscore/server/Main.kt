package org.openscore.server

import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import org.openscore.OpenScore
import org.openscore.net.KtorFetcher
import org.openscore.testing.SampleFetcher

/**
 * `feed-server [--port N] [--offline]`
 *
 * `--offline` serves the captured samples under `apis/` instead of calling any league
 * (run from the repository root), so an app can be developed without network access.
 */
fun main(args: Array<String>) {
    val port = args.indexOf("--port").takeIf { it >= 0 }?.let { args.getOrNull(it + 1)?.toIntOrNull() }
        ?: System.getenv("PORT")?.toIntOrNull()
        ?: 8080
    val offline = "--offline" in args

    val openScore = if (offline) {
        OpenScore.default(SampleFetcher.allLeagues())
    } else {
        OpenScore.default(KtorFetcher(userAgent = System.getenv("OPENSCORE_USER_AGENT") ?: KtorFetcher.DEFAULT_USER_AGENT))
    }

    println("OpenScore feed v1 on http://localhost:$port/v1  (${openScore.leagues.joinToString { it.id }}${if (offline) ", offline samples" else ""})")
    embeddedServer(CIO, port = port, host = "0.0.0.0") { feedModule(openScore) }.start(wait = true)
}
