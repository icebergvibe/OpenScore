package org.openscore.capture

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openscore.net.FetchResponse
import org.openscore.net.Fetcher
import kotlin.time.Duration

/**
 * Sits between a provider and the real [Fetcher] and keeps every response that actually came
 * from the network (cache hits are the same bytes again, so they are not recorded). One
 * instance per captured game, so concurrent captures never see each other's responses.
 *
 * Also records the request even when it throws, so a provider that blows up on an
 * unexpected body still leaves the body behind for the post-mortem.
 */
internal class RecordingFetcher(private val delegate: Fetcher) : Fetcher {

    class Recorded(val url: String, val headers: Map<String, String>, val response: FetchResponse?, val error: Throwable?)

    private val lock = Mutex()
    private val recorded = ArrayList<Recorded>()

    override suspend fun get(url: String, headers: Map<String, String>, maxAge: Duration): FetchResponse {
        val response = try {
            delegate.get(url, headers, maxAge)
        } catch (e: Exception) {
            lock.withLock { recorded += Recorded(url, headers, null, e) }
            throw e
        }
        if (!response.fromCache) lock.withLock { recorded += Recorded(url, headers, response, null) }
        return response
    }

    /** Everything recorded since the last call, in request order. */
    suspend fun drain(): List<Recorded> = lock.withLock { ArrayList(recorded).also { recorded.clear() } }
}
