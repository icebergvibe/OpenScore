package org.openscore.health

import io.ktor.http.encodeURLParameter
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import org.openscore.net.QueryFetcher
import java.io.File
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

public enum class Status { PASS, WARN, FAIL, SKIP }

@Serializable
public data class CheckResult(
    val league: String,
    val name: String,
    val url: String?,
    val status: Status,
    val httpStatus: Int? = null,
    val millis: Long? = null,
    val message: String = "",
    /** Sample paths absent from the live response (drift), capped for readability. */
    val missing: List<String> = emptyList(),
    val newPaths: Int = 0,
    val note: String? = null,
)

/**
 * Runs one league's `health.json` against the live API, sequentially and no faster than the
 * file's `minDelayMs`, and compares each response with its captured sample.
 */
public class Runner(
    private val fetcher: Fetcher,
    private val placeholders: Placeholders,
    private val maxMissingListed: Int = 12,
    private val log: (String) -> Unit = {},
) {
    public suspend fun run(file: HealthFile): List<CheckResult> {
        val results = ArrayList<CheckResult>(file.checks.size)
        for ((i, check) in file.checks.withIndex()) {
            if (i > 0) delay(file.minDelayMs)
            val result = runOne(file, check)
            log(Report.line(result))
            results += result
        }
        return results
    }

    public suspend fun runOne(file: HealthFile, check: Check): CheckResult {
        val base = CheckResult(file.league, check.label, null, Status.SKIP, note = check.note)
        val url: String
        val headers: Map<String, String>
        val body: String?
        try {
            url = when {
                check.url != null -> placeholders.resolve(check.url)
                check.query != null -> file.baseUrl + "?query=" + placeholders.resolve(check.query).encodeURLParameter()
                else -> file.baseUrl + placeholders.resolve(check.path!!)
            }
            headers = (file.headers + check.headers).mapValues { placeholders.resolve(it.value) }
            body = check.body?.let { placeholders.resolve(it) }
        } catch (e: UnresolvedPlaceholder) {
            return base.copy(message = "skipped: ${e.message}")
        }

        val mark = TimeSource.Monotonic.markNow()
        val response = try {
            // maxAge 0: the whole point is a fresh request.
            when {
                body == null -> fetcher.get(url, headers, maxAge = 0.seconds)
                fetcher is QueryFetcher -> fetcher.query(url, body, headers = headers, maxAge = 0.seconds)
                else -> return base.copy(url = url, message = "skipped: this fetcher cannot POST")
            }
        } catch (e: Exception) {
            return base.copy(url = url, status = Status.FAIL, millis = mark.elapsedNow().inWholeMilliseconds, message = "request failed: ${e::class.simpleName}: ${e.message}")
        }
        val millis = mark.elapsedNow().inWholeMilliseconds
        val got = base.copy(url = url, httpStatus = response.status, millis = millis)

        if (response.status !in check.status) {
            return got.copy(status = Status.FAIL, message = "HTTP ${response.status}, expected ${check.status.joinToString("/")}: ${response.body.snippet()}")
        }
        // A documented error status (404 for an unknown id, 401 for a gated node) is the whole check.
        if (!response.isSuccess) return got.copy(status = Status.PASS, message = "HTTP ${response.status} as documented")
        return when (check.format) {
            "json" -> checkJson(check, file, response, got)
            "xml" -> checkXml(check, file, response, got)
            else -> if (response.body.isBlank()) got.copy(status = Status.FAIL, message = "empty body") else got.copy(status = Status.PASS, message = "${response.body.length} chars")
        }
    }

    private fun checkJson(check: Check, file: HealthFile, response: FetchResponse, got: CheckResult): CheckResult {
        val body = response.body.trimStart()
        if (response.status == 204 || body.isEmpty()) {
            return if (check.sample == null && check.keys.isEmpty()) got.copy(status = Status.PASS, message = "empty body (accepted)")
            else got.copy(status = Status.FAIL, message = "empty body, expected JSON")
        }
        if (!response.isJson && !(body.startsWith("{") || body.startsWith("["))) {
            return got.copy(status = Status.FAIL, message = "not JSON (${response.contentType}): ${body.snippet()}")
        }
        val live = try {
            Shape.parseJson(body)
        } catch (e: Exception) {
            return got.copy(status = Status.FAIL, message = "unparseable JSON: ${e.message?.lineSequence()?.first()}")
        }

        if (check.query != null) {
            val obj = live as? JsonObject
            val errors = obj?.get("errors") as? JsonArray
            if (errors != null && errors.isNotEmpty()) return got.copy(status = Status.FAIL, message = "GraphQL errors: ${errors.toString().snippet()}")
            if (obj?.get("data") == null || obj["data"] is JsonNull) return got.copy(status = Status.FAIL, message = "GraphQL response without data")
        }

        val absent = check.keys.filterNot { Shape.hasJsonPath(live, it) }
        if (absent.isNotEmpty()) return got.copy(status = Status.FAIL, message = "required keys missing", missing = absent)

        if (check.sample == null || check.depth <= 0) return got.copy(status = Status.PASS, message = describe(live))

        val sampleFile = File(file.samplesDir, check.sample)
        if (!sampleFile.isFile) return got.copy(status = Status.WARN, message = "sample not found: ${check.sample}")
        val sample = Shape.unwrapSample(Shape.parseJson(sampleFile.readText()))
        if (kind(sample) != kind(live)) {
            return got.copy(status = Status.FAIL, message = "top-level ${kind(sample)} in sample, ${kind(live)} live")
        }
        val expected = Shape.jsonPaths(sample, check.depth, check.mapOfObjects)
        val actual = Shape.jsonShape(live, check.depth, check.mapOfObjects)
        val missing = actual.missing(expected)
        val added = (actual.paths - expected).size
        return if (missing.isEmpty()) got.copy(status = Status.PASS, message = describe(live), newPaths = added)
        else got.copy(status = Status.WARN, message = "${missing.size} of ${expected.size} sample paths missing", missing = missing.take(maxMissingListed), newPaths = added)
    }

    private fun checkXml(check: Check, file: HealthFile, response: FetchResponse, got: CheckResult): CheckResult {
        val body = response.body.trimStart()
        if (!body.startsWith("<")) return got.copy(status = Status.FAIL, message = "not XML (${response.contentType}): ${body.snippet()}")
        val live = try {
            Shape.parseXml(body)
        } catch (e: Exception) {
            return got.copy(status = Status.FAIL, message = "unparseable XML: ${e.message}")
        }
        val absent = check.keys.filterNot { Shape.hasXmlPath(live, it) }
        if (absent.isNotEmpty()) return got.copy(status = Status.FAIL, message = "required elements missing", missing = absent)
        if (check.sample == null || check.depth <= 0) return got.copy(status = Status.PASS, message = "<${live.name}> ${live.children.size} children")

        val sampleFile = File(file.samplesDir, check.sample)
        if (!sampleFile.isFile) return got.copy(status = Status.WARN, message = "sample not found: ${check.sample}")
        val sample = Shape.parseXml(sampleFile.readText())
        if (sample.name != live.name) return got.copy(status = Status.FAIL, message = "root <${sample.name}> in sample, <${live.name}> live")
        val expected = Shape.xmlPaths(sample, check.depth)
        val actual = Shape.xmlPaths(live, check.depth)
        val missing = Shape.missingXml(expected, actual)
        // An empty live document (off-day) cannot be expected to carry the sample's children.
        val excused = live.children.isEmpty()
        return if (missing.isEmpty() || excused) got.copy(status = Status.PASS, message = "<${live.name}> ${live.children.size} children", newPaths = (actual - expected).size)
        else got.copy(status = Status.WARN, message = "${missing.size} of ${expected.size} sample paths missing", missing = missing.take(maxMissingListed), newPaths = (actual - expected).size)
    }

    private fun kind(e: kotlinx.serialization.json.JsonElement): String = when (e) {
        is JsonObject -> "object"
        is JsonArray -> "array"
        is JsonNull -> "null"
        else -> "scalar"
    }

    private fun describe(e: kotlinx.serialization.json.JsonElement): String = when (e) {
        is JsonObject -> "object, ${e.size} keys"
        is JsonArray -> "array, ${e.size} items"
        is JsonNull -> "null"
        else -> "scalar"
    }

    private fun String.snippet(): String = lineSequence().firstOrNull().orEmpty().take(120)
}
