package org.openscore.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Where the body returned by [Fetcher.get] came from. */
public enum class FetchSource {
    /** A response body transferred by this call. */
    NETWORK,
    /** A fresh response read from the bounded in-memory cache. */
    MEMORY,
    /** A cached body whose validator was accepted by an upstream `304`. */
    REVALIDATED,
    /** A response shared with an overlapping caller of the same request identity. */
    COALESCED,
}

/** One completed fetch, suitable for privacy-safe per-host performance accounting. */
public data class FetchMetric(
    val host: String,
    val source: FetchSource,
    val status: Int,
    /** Decoded UTF-8 bytes retained by the app. This deliberately is not claimed to be wire bytes. */
    val bodyBytes: Long,
    val duration: Duration,
)

/** Receives metrics after a fetch. Keep implementations small: this runs on the caller coroutine. */
public fun interface FetchObserver {
    public suspend fun onFetch(metric: FetchMetric)
}

/** In-process counters an app can inspect without collecting request URLs or identifiers. */
public class FetchMetrics : FetchObserver {
    private val mutex = Mutex()
    private val byHost = LinkedHashMap<String, FetchMetricTotals>()

    override suspend fun onFetch(metric: FetchMetric): Unit = mutex.withLock {
        val old = byHost[metric.host] ?: FetchMetricTotals()
        byHost[metric.host] = old.copy(
            requests = old.requests + 1,
            networkRequests = old.networkRequests + if (metric.source == FetchSource.NETWORK) 1 else 0,
            memoryHits = old.memoryHits + if (metric.source == FetchSource.MEMORY) 1 else 0,
            revalidations = old.revalidations + if (metric.source == FetchSource.REVALIDATED) 1 else 0,
            coalesced = old.coalesced + if (metric.source == FetchSource.COALESCED) 1 else 0,
            decodedBytes = old.decodedBytes + metric.bodyBytes,
            elapsed = old.elapsed + metric.duration,
            rateLimits = old.rateLimits + if (metric.status == 429) 1 else 0,
            failures = old.failures + if (metric.status !in 200..299 && metric.status != 304) 1 else 0,
        )
    }

    public suspend fun snapshot(): Map<String, FetchMetricTotals> = mutex.withLock { byHost.toMap() }
}

public data class FetchMetricTotals(
    val requests: Int = 0,
    val networkRequests: Int = 0,
    val memoryHits: Int = 0,
    val revalidations: Int = 0,
    /** Callers served by an overlapping request, including responses marked `no-store`. */
    val coalesced: Int = 0,
    val decodedBytes: Long = 0,
    val elapsed: Duration = Duration.ZERO,
    val rateLimits: Int = 0,
    val failures: Int = 0,
)

/**
 * The one thing providers use to reach the network. Read-only by construction: there is
 * only [get]. Tests substitute an implementation that serves the captured samples.
 */
public interface Fetcher {
    /**
     * @param maxAge the maximum age a caller accepts for a cached copy of this exact URL before
     *   a new request is made. An upstream cache directive may require an earlier revalidation.
     *   Providers pass long values for schedules/standings and the 10 s floor for live game
     *   endpoints.
     */
    public suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        maxAge: Duration = DEFAULT_MAX_AGE,
    ): FetchResponse

    public companion object {
        public val DEFAULT_MAX_AGE: Duration = 10.seconds
    }
}

/** A decoded event from a direct Server-Sent Events connection. */
public data class ServerSentEvent(
    val event: String? = null,
    val data: String,
)

/**
 * Optional direct-client transport for providers whose upstream exposes Server-Sent Events.
 *
 * It deliberately has no reconnect policy: each provider owns its protocol-specific snapshot,
 * patch and reconnect semantics. Providers fall back to normal [Fetcher.get] polling when this
 * interface is unavailable (for example, deterministic sample fetchers in tests).
 */
public interface EventStreamFetcher : Fetcher {
    public fun events(url: String, headers: Map<String, String> = emptyMap()): Flow<ServerSentEvent>
}

public data class FetchResponse(
    val url: String,
    val status: Int,
    val contentType: String?,
    val body: String,
    val etag: String? = null,
    /** The upstream validator, retained so a stale cache entry can use `If-Modified-Since`. */
    val lastModified: String? = null,
    /** Upstream cache directives, retained so [KtorFetcher] can honour `no-store`, `no-cache`, and `max-age`. */
    val cacheControl: String? = null,
    /** True when served from the fetcher's cache (fresh, or revalidated with 304). */
    val fromCache: Boolean = false,
    val source: FetchSource = FetchSource.NETWORK,
) {
    val isSuccess: Boolean get() = status in 200..299
    val isJson: Boolean get() = contentType?.contains("json", ignoreCase = true) == true

    /** Throws unless 2xx. */
    public fun requireSuccess(): FetchResponse {
        if (!isSuccess) throw HttpException(url, status, body.take(200))
        return this
    }
}

public class HttpException(
    public val url: String,
    public val status: Int,
    public val bodySnippet: String,
) : RuntimeException("HTTP $status for $url: ${bodySnippet.lineSequence().firstOrNull().orEmpty()}")

/**
 * The UTF-8 length of this string without encoding a copy of it. Bodies run to hundreds of
 * kilobytes and are measured on every fetch, so the allocation is worth avoiding.
 */
internal fun String.utf8Length(): Long {
    var bytes = 0L
    var i = 0
    while (i < length) {
        val c = this[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> { i++; 4 }
            // A lone surrogate encodes as the one-byte replacement `?`, as encodeToByteArray() does.
            c.isSurrogate() -> 1
            else -> 3
        }
        i++
    }
    return bytes
}
