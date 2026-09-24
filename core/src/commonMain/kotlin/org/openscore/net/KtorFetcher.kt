package org.openscore.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.get
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readLine
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * Ktor-backed [Fetcher] that enforces docs/principles.md:
 *
 * - sends a descriptive `User-Agent`,
 * - keeps an in-memory cache per request identity (URL plus caller-supplied headers) and serves
 *   it while younger than the caller's and upstream's freshness limits,
 * - honours upstream `Cache-Control: no-store`, `no-cache`, and `max-age`, and revalidates with
 *   `ETag` and `Last-Modified` validators when supplied,
 * - never issues anything but GET.
 *
 * Requests with different identities run concurrently; matching identities are single-flight,
 * so overlapping callers of one endpoint (three Swedish leagues reading the same Fogis overview)
 * produce one request and share its result. Caller-supplied headers are part of the identity
 * because they can change the response.
 *
 * @param maxEntries how many request identities the cache keeps.
 * @param maxBytes the total UTF-8 body bytes the cache may retain. Both limits are enforced, so
 *   one unusually large response cannot turn a 512-entry cache into a large memory allocation.
 * @param maxConcurrentPerHost caps bursts such as Malta's one-result-per-game listing without
 *   serialising independent providers on different hosts.
 * @param requestTimeoutMillis bounds an ordinary GET from request start through response body.
 *   The manual SSE transport overrides it while retaining a bounded connect/socket timeout.
 */
public class KtorFetcher(
    engine: HttpClientEngine? = null,
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val clock: Clock = Clock.System,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxConcurrentPerHost: Int = DEFAULT_MAX_CONCURRENT_PER_HOST,
    private val observer: FetchObserver? = null,
    private val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    private val connectTimeoutMillis: Long = DEFAULT_CONNECT_TIMEOUT_MILLIS,
    private val socketTimeoutMillis: Long = DEFAULT_SOCKET_TIMEOUT_MILLIS,
) : EventStreamFetcher {

    init {
        require(maxConcurrentPerHost > 0) { "maxConcurrentPerHost must be positive" }
        require(requestTimeoutMillis > 0 && connectTimeoutMillis > 0 && socketTimeoutMillis > 0) { "timeouts must be positive" }
    }

    private val client: HttpClient = (engine?.let { HttpClient(it) } ?: HttpClient()).config {
        followRedirects = true
        expectSuccess = false
        install(HttpTimeout) {
            this.requestTimeoutMillis = this@KtorFetcher.requestTimeoutMillis
            this.connectTimeoutMillis = this@KtorFetcher.connectTimeoutMillis
            this.socketTimeoutMillis = this@KtorFetcher.socketTimeoutMillis
        }
    }

    private class Entry(val response: FetchResponse, val fetchedAt: Instant, val bytes: Long)

    /** URL plus a canonical, case-insensitive representation of caller-supplied headers. */
    private data class RequestKey(val url: String, val headers: List<Pair<String, String>>) {
        companion object {
            fun of(url: String, headers: Map<String, String>): RequestKey =
                RequestKey(
                    url,
                    headers.entries
                        .map { it.key.lowercase() to it.value }
                        .sortedWith(compareBy({ it.first }, { it.second })),
                )
        }
    }

    /** A per-request lock, counted so it is dropped once nobody is waiting on that request. */
    private class UrlLock {
        val mutex = Mutex()
        var waiters = 0
        /**
         * Kept only for the lifetime of the overlapping callers. Unlike [cache], this is also
         * populated for `no-store` and error responses so a burst still costs one request.
         */
        var completed: FetchResponse? = null
    }

    private val cache = LinkedHashMap<RequestKey, Entry>()
    private var cacheBytes: Long = 0
    private val locks = HashMap<RequestKey, UrlLock>()
    private val hostLimits = HashMap<String, Semaphore>()

    /** Guards [cache], [locks] and [hostLimits] only; never held across a request. */
    private val state = Mutex()

    override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse {
        val started = TimeSource.Monotonic.markNow()
        val key = RequestKey.of(url, headers)
        state.withLock { fresh(key, maxAge) }?.let { return report(url, it, started.elapsedNow()) }
        val lock = state.withLock {
            locks.getOrPut(key) { UrlLock() }.also { it.waiters++ }
        }
        try {
            val response = lock.mutex.withLock {
                // A caller that joined this request before it completed gets exactly that
                // result. Preserve whether a 304 supplied a cached body; a no-store response
                // remains non-cached and disappears with this group of callers.
                lock.completed?.let { return@withLock it.copy(source = FetchSource.COALESCED) }
                // Whoever held the lock before us may have just fetched this exact request.
                val already = state.withLock { fresh(key, maxAge) }
                if (already != null) {
                    already
                } else {
                    val cached = state.withLock { cache[key] }
                    val response = request(url, headers, cached)
                    state.withLock { remember(key, response) }.also { lock.completed = it }
                }
            }
            return report(url, response, started.elapsedNow())
        } finally {
            // Runs on cancellation too, where a cancellable lock() would throw before the count came down.
            withContext(NonCancellable) { state.withLock { if (--lock.waiters == 0) locks.remove(key) } }
        }
    }

    /**
     * Opens one upstream SSE connection. Unlike GET bodies this is intentionally not cached;
     * callers must close/reconnect it according to the upstream's event protocol.
     */
    override fun events(url: String, headers: Map<String, String>): Flow<ServerSentEvent> = flow {
        val host = hostOf(url)
        val limit = state.withLock { hostLimits.getOrPut(host) { Semaphore(maxConcurrentPerHost) } }
        limit.withPermit {
            client.prepareGet(url) {
                defaultHeader(headers, HttpHeaders.UserAgent, userAgent)
                defaultHeader(headers, HttpHeaders.Accept, "text/event-stream")
                headers.forEach { (key, value) -> header(key, value) }
                setCapability(
                    HttpTimeoutCapability,
                    HttpTimeoutConfig(
                        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS,
                        connectTimeoutMillis = this@KtorFetcher.connectTimeoutMillis,
                        socketTimeoutMillis = maxOf(this@KtorFetcher.socketTimeoutMillis, SSE_SOCKET_TIMEOUT_MILLIS),
                    ),
                )
            }.execute { response ->
                if (response.status.value !in 200..299) {
                    throw HttpException(url, response.status.value, response.bodyAsText())
                }

                var event: String? = null
                val data = mutableListOf<String>()
                val channel = response.bodyAsChannel()
                while (true) {
                    val line = channel.readLine() ?: break
                    if (line.isEmpty()) {
                        if (event != null || data.isNotEmpty()) emit(ServerSentEvent(event, data.joinToString("\n")))
                        event = null
                        data.clear()
                        continue
                    }
                    if (line.startsWith(':')) continue // keep-alive comment
                    val field = line.substringBefore(':')
                    val value = line.substringAfter(':', "").removePrefix(" ")
                    when (field) {
                        "event" -> event = value
                        "data" -> data += value
                    }
                }
                if (event != null || data.isNotEmpty()) emit(ServerSentEvent(event, data.joinToString("\n")))
            }
        }
    }

    /** The cached copy while it is younger than its caller and upstream freshness limits. Call under [state]. */
    private fun fresh(key: RequestKey, maxAge: Duration): FetchResponse? {
        val cached = cache[key] ?: return null
        val freshness = cached.response.cacheMaxAge()?.let { minOf(maxAge, it) } ?: maxAge
        if (clock.now() - cached.fetchedAt >= freshness) return null
        // LinkedHashMap is insertion ordered. Reinsert fresh hits to make eviction true LRU.
        cache.remove(key)
        cache[key] = cached
        return cached.response.copy(fromCache = true, source = FetchSource.MEMORY)
    }

    private suspend fun request(url: String, headers: Map<String, String>, cached: Entry?): FetchResponse {
        val host = hostOf(url)
        val limit = state.withLock { hostLimits.getOrPut(host) { Semaphore(maxConcurrentPerHost) } }
        return limit.withPermit {
            val http = client.get(url) {
                defaultHeader(headers, HttpHeaders.UserAgent, userAgent)
                defaultHeader(headers, HttpHeaders.Accept, "application/json, */*;q=0.5")
                cached?.response?.etag?.let { header(HttpHeaders.IfNoneMatch, it) }
                cached?.response?.lastModified?.let { header(HttpHeaders.IfModifiedSince, it) }
                headers.forEach { (k, v) -> header(k, v) }
            }
            if (http.status == HttpStatusCode.NotModified && cached != null) {
                return@withPermit cached.response.copy(
                    etag = http.headers[HttpHeaders.ETag] ?: cached.response.etag,
                    lastModified = http.headers[HttpHeaders.LastModified] ?: cached.response.lastModified,
                    cacheControl = http.headers[HttpHeaders.CacheControl] ?: cached.response.cacheControl,
                    fromCache = true,
                    source = FetchSource.REVALIDATED,
                )
            }
            FetchResponse(
                url = url,
                status = http.status.value,
                contentType = http.headers[HttpHeaders.ContentType],
                body = http.bodyAsText(),
                etag = http.headers[HttpHeaders.ETag],
                lastModified = http.headers[HttpHeaders.LastModified],
                cacheControl = http.headers[HttpHeaders.CacheControl],
            )
        }
    }

    private suspend fun report(url: String, response: FetchResponse, duration: Duration): FetchResponse {
        observer?.onFetch(FetchMetric(hostOf(url), response.source, response.status, response.body.utf8Length(), duration))
        return response
    }

    /** Stores a success (a 304 re-dates the old body) and trims least-recently-used entries. Call under [state]. */
    private fun remember(key: RequestKey, response: FetchResponse): FetchResponse {
        // Preserve a stale successful response after a failed revalidation. It still carries
        // the validator for the next attempt; errors themselves are never cached.
        if (!response.isSuccess) return response
        cache.remove(key)?.let { cacheBytes -= it.bytes }
        val bytes = response.body.utf8Length()
        if (!response.isNoStore() && maxEntries > 0 && maxBytes > 0 && bytes <= maxBytes) {
            cache[key] = Entry(response, clock.now(), bytes)
            cacheBytes += bytes
            trimCache()
        }
        return response
    }

    private fun trimCache() {
        while (cache.size > maxEntries || cacheBytes > maxBytes) {
            val oldest = cache.keys.first()
            cache.remove(oldest)?.let { cacheBytes -= it.bytes }
        }
    }

    public fun close(): Unit = client.close()

    public companion object {
        public const val DEFAULT_USER_AGENT: String = "OpenScore/0.2 (+https://github.com/icebergvibe/OpenScore)"

        /** Enough for a season of days across every league. The byte limit remains authoritative. */
        public const val DEFAULT_MAX_ENTRIES: Int = 512
        /** Keeps the default cache near a few dozen MiB even when a feed returns a very large body. */
        public const val DEFAULT_MAX_BYTES: Long = 24L * 1024 * 1024
        /** Avoids per-match fan-out overwhelming one upstream host while keeping the UI concurrent. */
        public const val DEFAULT_MAX_CONCURRENT_PER_HOST: Int = 4
        public const val DEFAULT_REQUEST_TIMEOUT_MILLIS: Long = 20_000
        public const val DEFAULT_CONNECT_TIMEOUT_MILLIS: Long = 10_000
        public const val DEFAULT_SOCKET_TIMEOUT_MILLIS: Long = 20_000
        /** Longer than the provider-level quiet watchdog, so it owns reconnect semantics. */
        private const val SSE_SOCKET_TIMEOUT_MILLIS: Long = 70_000
    }
}

/**
 * Sets a header the fetcher supplies itself, unless the caller asked for that header: a
 * provider's own `Accept` replaces the default rather than being appended to it, which Ktor
 * would otherwise send as one combined value.
 */
private fun HttpRequestBuilder.defaultHeader(callerHeaders: Map<String, String>, name: String, value: String) {
    if (callerHeaders.keys.none { it.equals(name, ignoreCase = true) }) header(name, value)
}

private fun hostOf(url: String): String = url.substringAfter("://", url).substringBefore('/').substringBefore('?').lowercase()

private fun FetchResponse.isNoStore(): Boolean = cacheControl
    ?.split(',')
    ?.any { it.trim().equals("no-store", ignoreCase = true) }
    ?: false

private fun FetchResponse.cacheMaxAge(): Duration? {
    val directives = cacheControl?.split(',')?.map(String::trim).orEmpty()
    if (directives.any { it.substringBefore('=').trim().equals("no-cache", ignoreCase = true) }) return Duration.ZERO
    return directives
        .firstOrNull { it.substringBefore('=').trim().equals("max-age", ignoreCase = true) }
        ?.substringAfter('=', missingDelimiterValue = "")
        ?.trim()
        ?.removeSurrounding("\"")
        ?.toLongOrNull()
        ?.coerceAtLeast(0)
        ?.let { it.seconds }
}
