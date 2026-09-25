package org.openscore.capture

import io.ktor.http.decodeURLQueryComponent
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openscore.model.Game
import org.openscore.model.GameState
import org.openscore.provider.Capability
import org.openscore.provider.LeagueProvider
import org.openscore.provider.MIN_LIVE_POLL_INTERVAL
import java.io.File
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal class CaptureOptions(
    /** Poll interval while the game is live or about to start; never below [MIN_LIVE_POLL_INTERVAL]. */
    val interval: Duration,
    /** Poll interval while the game is still more than [preWindow] away. */
    val idleInterval: Duration,
    val preWindow: Duration = 15.minutes,
    /** While live, save a full snapshot at least this often even when nothing "significant" changed. */
    val checkpoint: Duration,
    /** How many more polls to take after the game went final (stats settle, endings get set). */
    val afterFinal: Int,
    val afterFinalInterval: Duration,
    val maxDuration: Duration,
    val withEvents: Boolean,
    val withLineups: Boolean,
)

/** One line of `index.jsonl`: which raw body was saved when, and for which URL. */
@Serializable
internal data class IndexEntry(
    val seq: Int,
    val at: String,
    val state: String,
    val url: String,
    val file: String,
    val status: Int?,
    val contentType: String?,
    val bytes: Int,
    val headers: Map<String, String> = emptyMap(),
    val error: String? = null,
)

/**
 * Polls one game through its provider and writes, under [dir]:
 *
 * - `ticks.jsonl` — the mapped model at every poll (state, clock, score, …), one line each
 * - `NNNN-<state>-<endpoint>.json|xml|txt` — every raw body the provider read at a tick that
 *   changed something, or at a checkpoint; a body identical to the last one saved for that
 *   URL is skipped
 * - `NNNN-<state>.game.json` — the Feed v1 rendering of the mapped game at those ticks
 * - `index.jsonl` — file ↔ URL ↔ tick
 */
internal class Capture(
    private val provider: LeagueProvider,
    private val recording: RecordingFetcher,
    private val gameId: String,
    private val dir: File,
    private val opts: CaptureOptions,
    private val log: (String) -> Unit,
) {
    private val json = Json { encodeDefaults = false; explicitNulls = false }
    private val ticksFile = File(dir, "ticks.jsonl")
    private val indexFile = File(dir, "index.jsonl")
    private val lastBodyHash = HashMap<String, String>()
    private var seq = 0

    suspend fun run(): Game? {
        dir.mkdirs()
        val started = Clock.System.now()
        var last: Signature? = null
        var lastSaved: Instant? = null
        var lastError: String? = null
        var finalPolls = 0
        var lastGame: Game? = null
        val tag = "${provider.league.id}/$gameId"

        while (true) {
            seq++
            val now = Clock.System.now()
            val at = now.toString().substringBefore('.').let { if (it.endsWith("Z")) it else "${it}Z" }
            recording.drain()

            var game: Game? = null
            var lineups: Int? = null
            var error: String? = null
            try {
                var g = provider.game(gameId)
                if (opts.withEvents && g.events == null && provider.supports(Capability.EVENTS)) {
                    g = g.copy(events = provider.events(gameId))
                }
                if (opts.withLineups && provider.supports(Capability.LINEUPS)) {
                    lineups = provider.lineups(gameId).size
                }
                game = g
            } catch (e: Exception) {
                error = "${e::class.simpleName}: ${e.message?.lineSequence()?.firstOrNull()?.take(300)}"
            }
            val recorded = recording.drain()

            val sig = game?.let { Signature.of(it, lineups) }
            val changed = when {
                game != null -> sig!!.diff(last)
                error != lastError -> listOf("error")
                else -> emptyList()
            }
            val live = game?.state?.isLive == true || (game != null && game.state == GameState.PRE_GAME)
            val checkpoint = live && lastSaved != null && now - lastSaved >= opts.checkpoint
            val saveNow = changed.isNotEmpty() || checkpoint || lastSaved == null

            val saved = ArrayList<String>()
            val stateTag = game?.state?.name?.lowercase() ?: "error"
            if (saveNow) {
                for (r in recorded) saved += saveRaw(r, at, stateTag) ?: continue
                if (game != null) {
                    val name = "${seq.pad()}-$stateTag.game.json"
                    File(dir, name).writeText(game.toFeedJson())
                    saved += name
                }
                lastSaved = now
            }

            val tick = if (game != null) {
                game.tick(seq, at, lineups, sig!!, changed, saved, recorded.size)
            } else {
                Tick(seq = seq, at = at, changed = changed, saved = saved, error = error, requests = recorded.size)
            }
            ticksFile.appendText(json.encodeToString(Tick.serializer(), tick) + "\n")
            log("$tag #${seq.pad()} $at ${describe(tick)}${if (changed.isNotEmpty()) "  [${changed.joinToString(",")}]" else ""}${if (saved.isNotEmpty()) "  saved ${saved.size}" else ""}")

            if (game != null) { last = sig; lastGame = game }
            lastError = error

            val state = game?.state
            val done = state?.isTerminal == true
            if (done) {
                finalPolls++
                if (finalPolls > opts.afterFinal) {
                    log("$tag: ${state.name.lowercase()} - stopping after ${seq} polls")
                    return game
                }
            }
            if (now - started > opts.maxDuration) { log("$tag: max duration reached — stopping"); return lastGame }

            val wait = when {
                done -> opts.afterFinalInterval
                game != null && state == GameState.SCHEDULED && game.startTime - now > opts.preWindow -> opts.idleInterval
                else -> maxOf(opts.interval, MIN_LIVE_POLL_INTERVAL)
            }
            delay(wait)
        }
    }

    private fun saveRaw(r: RecordingFetcher.Recorded, at: String, stateTag: String): String? {
        val body = r.response?.body
        val hash = body?.let { sha1(it) } ?: "error"
        if (lastBodyHash[r.url] == hash) return null
        lastBodyHash[r.url] = hash
        val ext = when {
            body == null -> "txt"
            r.response.isJson || body.trimStart().let { it.startsWith("{") || it.startsWith("[") } -> "json"
            body.trimStart().startsWith("<") -> "xml"
            else -> "txt"
        }
        val name = "${seq.pad()}-$stateTag-${slug(r.url)}.$ext"
        File(dir, name).writeText(body ?: (r.error?.toString() ?: ""))
        val entry = IndexEntry(
            seq, at, stateTag, r.url, name, r.response?.status, r.response?.contentType, body?.length ?: 0,
            headers = r.headers.filterKeys { !it.equals("Ocp-Apim-Subscription-Key", ignoreCase = true) },
            error = r.error?.let { "${it::class.simpleName}: ${it.message}" },
        )
        indexFile.appendText(json.encodeToString(IndexEntry.serializer(), entry) + "\n")
        return name
    }

    private fun describe(t: Tick): String {
        if (t.error != null) return "ERROR ${t.error}"
        val sb = StringBuilder()
        sb.append(t.state)
        t.rawState?.let { sb.append(" (").append(it).append(')') }
        t.score?.let { sb.append("  ").append(it) }
        if (t.period != null || t.clock != null) {
            sb.append("  ").append(t.period ?: "?")
            t.clock?.let { sb.append(' ').append(it) }
            t.running?.let { sb.append(if (it) " ▶" else " ⏸") }
        }
        t.periodScores?.let { sb.append("  [").append(it).append(']') }
        t.situation?.let { sb.append("  ").append(it.toString().take(80)) }
        t.events?.let { sb.append("  ev=").append(it) }
        t.lineups?.let { sb.append(" lu=").append(it) }
        t.ending?.let { sb.append("  end=").append(it) }
        return sb.toString()
    }

    private fun Int.pad(): String = toString().padStart(4, '0')

    companion object {
        /** Longest path kept verbatim in a file name; beyond it the slug carries a hash (see [slug]). */
        private const val MAX_SLUG = 90

        private fun sha1(s: String): String =
            MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

        /**
         * A short, stable, filesystem-safe name for an endpoint URL: its path, or the GraphQL root
         * field.
         *
         * A long path keeps its head *and* its tail with a hash of the whole between them, because
         * the tail is what names the endpoint. Truncating the head alone silently overwrote bodies:
         * Serie A's `header`, `summary`, `lineups` and `teamstats` all hang off the same
         * 90-character season-and-match prefix, so one poll wrote four bodies to one file and only
         * the last survived (2026-09-13 capture, three of four endpoints lost per poll).
         */
        fun slug(url: String): String {
            val noScheme = url.substringAfter("://")
            val path = noScheme.substringBefore('?').substringAfter('/', "")
            val query = noScheme.substringAfter('?', "")
            if (query.startsWith("query=")) {
                val q = runCatching { query.removePrefix("query=").substringBefore('&').decodeURLQueryComponent() }.getOrDefault(query)
                val root = Regex("\\{\\s*([A-Za-z_][A-Za-z0-9_]*)").find(q)?.groupValues?.get(1) ?: "query"
                return "graphql-$root"
            }
            val cleaned = path.ifEmpty { noScheme.substringBefore('?') }
                .replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_')
            val base = if (cleaned.length <= MAX_SLUG) cleaned else {
                val head = cleaned.take(MAX_SLUG - 40).trim('_')
                val tail = cleaned.takeLast(24).trim('_')
                "${head}_p${sha1(cleaned).take(6)}_$tail"
            }
            return if (query.isEmpty()) base else base + "_q" + sha1(query).take(6)
        }
    }
}
