package org.openscore.capture

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import org.openscore.net.QueryFetcher
import kotlin.time.Duration

/**
 * Sits between a provider and the real [Fetcher] and keeps every response that actually came
 * from the network (cache hits are the same bytes again, so they are not recorded). One
 * instance per captured game, so concurrent captures never see each other's responses.
 *
 * Also records the request even when it throws, so a provider that blows up on an
 * unexpected body still leaves the body behind for the post-mortem.
 *
 * Made with [of], which answers a [QueryFetcher] exactly when the delegate is one: a provider
 * decides what it claims from what its fetcher can do (HockeyAllsvenskan's squads, table and
 * play-by-play need a POST), so a recorder must not promise a POST it would then fail at.
 */
internal open class RecordingFetcher protected constructor(private val delegate: Fetcher) : Fetcher {

    class Recorded(
        val url: String,
        val headers: Map<String, String>,
        val response: FetchResponse?,
        val error: Throwable?,
        /** The POST body, when this was a [QueryFetcher.query]; null for a GET. */
        val body: String? = null,
    )

    private val lock = Mutex()
    private val recorded = ArrayList<Recorded>()

    override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse =
        recording(url, headers, body = null) { delegate.get(url, headers, maxAge) }

    /** Runs [request] and keeps what it brought back from the network, or the failure. */
    protected suspend fun recording(url: String, headers: Map<String, String>, body: String?, request: suspend () -> FetchResponse): FetchResponse {
        val response = try {
            request()
        } catch (e: Exception) {
            lock.withLock { recorded += Recorded(url, headers, null, e, body) }
            throw e
        }
        if (!response.fromCache) lock.withLock { recorded += Recorded(url, headers, response, null, body) }
        return response
    }

    /** Everything recorded since the last call, in request order. */
    suspend fun drain(): List<Recorded> = lock.withLock { ArrayList(recorded).also { recorded.clear() } }

    private class Querying(private val queryable: QueryFetcher) : RecordingFetcher(queryable), QueryFetcher {
        override suspend fun query(
            url: String,
            body: String,
            contentType: String,
            headers: Map<String, String>,
            maxAge: Duration,
        ): FetchResponse = recording(url, headers, body) { queryable.query(url, body, contentType, headers, maxAge) }
    }

    companion object {
        /** A recorder in front of [delegate] that can POST exactly when [delegate] can. */
        fun of(delegate: Fetcher): RecordingFetcher = if (delegate is QueryFetcher) Querying(delegate) else RecordingFetcher(delegate)
    }
}
