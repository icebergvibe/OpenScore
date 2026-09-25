package org.openscore.health

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `apis/<sport>/<league>/health.json` — the machine-readable list of every documented endpoint
 * for one league, kept next to the README it mirrors (docs/principles.md: "keep its
 * expectations in sync with the docs").
 */
@Serializable
public data class HealthFile(
    /** Core league id (`nhl`, `shl`, …) or a feed id when one file serves several leagues. */
    val league: String,
    val name: String,
    /** Prefix for every relative [Check.path]. */
    val baseUrl: String,
    /** Headers sent with every check; values may use placeholders (see [Placeholders]). */
    val headers: Map<String, String> = emptyMap(),
    /** Poll floor documented for the league; the tool never runs faster than this per host. */
    val minDelayMs: Long = 250,
    val checks: List<Check>,
) {
    /** Where this file lives; set by [load], not stored in JSON. */
    @kotlinx.serialization.Transient
    var dir: File = File(".")
        internal set

    val readme: File get() = File(dir, "README.md")
    val samplesDir: File get() = File(dir, "samples")
}

@Serializable
public data class Check(
    /** Short label shown in the report; defaults to the path. */
    val name: String? = null,
    /** Path relative to [HealthFile.baseUrl]. Mutually exclusive with [url] and [query]. */
    val path: String? = null,
    /** Absolute URL for endpoints outside the base URL. */
    val url: String? = null,
    /** GraphQL query sent as `?query=` on the base URL; the response must carry `data` and no `errors`. */
    val query: String? = null,
    /**
     * A JSON request body, which makes this check a `POST` through
     * [org.openscore.net.QueryFetcher] rather than a `GET`. Only for a route that answers a
     * *read* this way and has no `GET` equivalent - the tool never calls anything that mutates,
     * and docs/principles.md says why. Placeholders are resolved in it as they are in [path].
     */
    val body: String? = null,
    val headers: Map<String, String> = emptyMap(),
    /** Accepted HTTP status codes. */
    val status: List<Int> = listOf(200),
    /** `json` (default), `xml`, or `text` (body only checked for being non-empty). */
    val format: String = "json",
    /** Sample file (in `samples/`) whose key structure the live response is compared with. */
    val sample: String? = null,
    /** How many levels of the sample to compare; 0 disables the comparison. */
    val depth: Int = 2,
    /** The response is an id-keyed map: compare the structure of its first value instead of its keys. */
    val mapOfObjects: Boolean = false,
    /** Key paths (`games[].id`, `data.match`) that must be present — a hard failure otherwise. */
    val keys: List<String> = emptyList(),
    /** Free text carried into the report (why this check exists, what it exercises). */
    val note: String? = null,
) {
    init {
        require(listOfNotNull(path, url, query).size == 1) { "check '${name ?: path ?: url ?: query}' needs exactly one of path, url, query" }
        require(body == null || query == null) { "check '${name ?: path ?: url}' cannot be both a GraphQL query and a POST body" }
        require(format in setOf("json", "xml", "text")) { "format must be json, xml or text" }
    }

    val label: String get() = name ?: path ?: url ?: "graphql"
}

public object CheckFiles {
    private val json = Json { ignoreUnknownKeys = false; isLenient = true }

    public const val FILE_NAME: String = "health.json"

    public fun load(file: File): HealthFile =
        json.decodeFromString(HealthFile.serializer(), file.readText()).also { it.dir = file.absoluteFile.parentFile }

    /** Every `apis/<sport>/<league>/health.json`, sorted by sport then league folder. */
    public fun discover(apisDir: File): List<HealthFile> =
        apisDir.walkTopDown()
            .filter { it.isFile && it.name == FILE_NAME && !it.path.contains("_unverified") }
            .sortedBy { it.path }
            .map { load(it) }
            .toList()
}
