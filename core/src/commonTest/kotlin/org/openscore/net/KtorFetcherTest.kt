package org.openscore.net

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class KtorFetcherTest {

    private class FakeClock(var now: Instant = Instant.fromEpochSeconds(1_800_000_000)) : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun sendsUserAgentAndCachesWithinMaxAge() = runTest {
        var hits = 0
        val engine = MockEngine { request ->
            hits++
            assertTrue(request.headers[HttpHeaders.UserAgent]!!.startsWith("OpenScore/"), "descriptive User-Agent")
            respond("""{"ok":$hits}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val clock = FakeClock()
        val fetcher = KtorFetcher(engine, clock = clock)

        val first = fetcher.get("https://example.test/x", maxAge = 10.seconds)
        val second = fetcher.get("https://example.test/x", maxAge = 10.seconds)
        assertEquals(1, hits, "second call within maxAge is served from cache")
        assertEquals(first.body, second.body)
        assertTrue(second.fromCache)

        clock.now += 11.seconds
        val third = fetcher.get("https://example.test/x", maxAge = 10.seconds)
        assertEquals(2, hits)
        assertEquals("""{"ok":2}""", third.body)
        assertTrue(!third.fromCache)
    }

    /**
     * A phone correcting its clock moves it backwards. The cached entry then reads as younger
     * than any limit, and a live score would be served from it until the clock caught up.
     */
    @Test
    fun aClockThatMovesBackwardsDoesNotMakeTheCacheEternallyFresh() = runTest {
        var hits = 0
        val engine = MockEngine { respond("""{"ok":${++hits}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }
        val clock = FakeClock()
        val fetcher = KtorFetcher(engine, clock = clock)

        fetcher.get("https://example.test/x", maxAge = 10.seconds)
        assertEquals(1, hits)

        clock.now -= 1.hours
        val afterJump = fetcher.get("https://example.test/x", maxAge = 10.seconds)
        assertEquals(2, hits, "the entry is re-read rather than held for the length of the jump")
        assertTrue(!afterJump.fromCache)
    }

    /** Ktor appends a repeated header, so a provider asking for one must get its value and not a joined pair. */
    @Test
    fun aCallersHeaderReplacesTheDefaultRatherThanJoiningIt() = runTest {
        val accepts = mutableListOf<String?>()
        val agents = mutableListOf<String?>()
        val engine = MockEngine { request ->
            accepts += request.headers[HttpHeaders.Accept]
            agents += request.headers[HttpHeaders.UserAgent]
            respond("{}", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock())

        fetcher.get("https://example.test/default", maxAge = Duration.ZERO)
        fetcher.get("https://example.test/own", mapOf("Accept" to "*/*"), maxAge = Duration.ZERO)
        fetcher.get("https://example.test/case", mapOf("accept" to "text/csv"), maxAge = Duration.ZERO)

        assertEquals(listOf<String?>("application/json, */*;q=0.5", "*/*", "text/csv"), accepts.toList())
        assertTrue(agents.all { it != null && it.startsWith("OpenScore/") }, "the User-Agent default still applies")
    }

    @Test
    fun revalidatesWithEtag() = runTest {
        var ifNoneMatch: String? = null
        var hits = 0
        val engine = MockEngine { request ->
            hits++
            ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
            if (ifNoneMatch == "\"v1\"") {
                respond("", HttpStatusCode.NotModified)
            } else {
                respond("""{"v":1}""", HttpStatusCode.OK, headersOf(HttpHeaders.ETag to listOf("\"v1\""), HttpHeaders.ContentType to listOf("application/json")))
            }
        }
        val clock = FakeClock()
        val fetcher = KtorFetcher(engine, clock = clock)

        val first = fetcher.get("https://example.test/y", maxAge = 1.seconds)
        clock.now += 5.seconds
        val second = fetcher.get("https://example.test/y", maxAge = 1.seconds)
        assertEquals(2, hits)
        assertEquals("\"v1\"", ifNoneMatch, "ETag from first response is sent back")
        assertEquals(first.body, second.body, "304 keeps the cached body")
        assertTrue(second.fromCache)
    }

    @Test
    fun honoursUpstreamMaxAgeAndLastModified() = runTest {
        var hits = 0
        var ifModifiedSince: String? = null
        val modified = "Sun, 14 Sep 2026 00:00:00 GMT"
        val engine = MockEngine { request ->
            hits++
            ifModifiedSince = request.headers[HttpHeaders.IfModifiedSince]
            if (ifModifiedSince == modified) {
                respond("", HttpStatusCode.NotModified, headersOf(HttpHeaders.CacheControl, "max-age=2"))
            } else {
                respond(
                    "{\"v\":1}",
                    HttpStatusCode.OK,
                    headersOf(
                        HttpHeaders.ContentType to listOf("application/json"),
                        HttpHeaders.LastModified to listOf(modified),
                        HttpHeaders.CacheControl to listOf("public, max-age=2"),
                    ),
                )
            }
        }
        val clock = FakeClock()
        val fetcher = KtorFetcher(engine, clock = clock)

        fetcher.get("https://example.test/modified", maxAge = 10.seconds)
        clock.now += 1.seconds
        fetcher.get("https://example.test/modified", maxAge = 10.seconds)
        assertEquals(1, hits, "the upstream max-age permits the first cache hit")

        clock.now += 2.seconds
        val revalidated = fetcher.get("https://example.test/modified", maxAge = 10.seconds)
        assertEquals(2, hits, "the shorter upstream max-age wins")
        assertEquals(modified, ifModifiedSince)
        assertTrue(revalidated.fromCache)
    }

    @Test
    fun doesNotStoreNoStoreResponses() = runTest {
        var hits = 0
        val engine = MockEngine {
            hits++
            respond("{\"hit\":$hits}", HttpStatusCode.OK, headersOf(HttpHeaders.CacheControl, "no-store"))
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock())

        fetcher.get("https://example.test/no-store", maxAge = 1.seconds)
        fetcher.get("https://example.test/no-store", maxAge = 1.seconds)

        assertEquals(2, hits)
    }

    @Test
    fun noCacheResponsesAreAlwaysRevalidated() = runTest {
        var hits = 0
        var ifNoneMatch: String? = null
        val engine = MockEngine { request ->
            hits++
            ifNoneMatch = request.headers[HttpHeaders.IfNoneMatch]
            if (hits == 1) {
                respond(
                    "{\"v\":1}",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ETag to listOf("v1"), HttpHeaders.CacheControl to listOf("no-cache")),
                )
            } else {
                respond("", HttpStatusCode.NotModified)
            }
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock())

        fetcher.get("https://example.test/no-cache", maxAge = 1.hours)
        val revalidated = fetcher.get("https://example.test/no-cache", maxAge = 1.hours)

        assertEquals(2, hits)
        assertEquals("v1", ifNoneMatch)
        assertTrue(revalidated.fromCache)
    }

    @Test
    fun errorsAreNotCached() = runTest {
        var hits = 0
        val engine = MockEngine {
            hits++
            respond("<html>404</html>", HttpStatusCode.NotFound, headersOf(HttpHeaders.ContentType, "text/html"))
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock())
        val r = fetcher.get("https://example.test/z")
        assertEquals(404, r.status)
        assertTrue(!r.isJson)
        fetcher.get("https://example.test/z")
        assertEquals(2, hits)
    }

    @Test
    fun cacheIsSeparatedByCallerSuppliedHeaders() = runTest {
        var hits = 0
        val engine = MockEngine { request ->
            hits++
            respond(request.headers["X-View"]!!, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock())
        val url = "https://example.test/variant"

        val compact = fetcher.get(url, headers = mapOf("X-View" to "compact"))
        val detailed = fetcher.get(url, headers = mapOf("X-View" to "detailed"))
        val compactAgain = fetcher.get(url, headers = mapOf("x-view" to "compact"))

        assertEquals("compact", compact.body)
        assertEquals("detailed", detailed.body)
        assertEquals("compact", compactAgain.body)
        assertTrue(compactAgain.fromCache, "header names are case-insensitive")
        assertEquals(2, hits, "different header values must not share a cached response")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class KtorFetcherConcurrencyTest {

    private class FakeClock(var now: Instant = Instant.fromEpochSeconds(1_800_000_000)) : Clock {
        override fun now(): Instant = now
    }

    @Test
    fun differentUrlsAreFetchedConcurrently() = runTest {
        // Every request parks until all three are in flight; a fetcher that serialises requests never gets there.
        val arrived = kotlinx.coroutines.CompletableDeferred<Unit>()
        var inFlight = 0
        val engine = MockEngine { request ->
            if (++inFlight == 3) arrived.complete(Unit)
            arrived.await()
            respond("""{"u":"${request.url}"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock())
        val bodies = listOf("a", "b", "c").map { async { fetcher.get("https://example.test/$it").body } }.awaitAll()
        assertEquals(3, bodies.distinct().size)
    }

    @Test
    fun sameUrlIsRequestedOnceForConcurrentCallers() = runTest {
        var hits = 0
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = MockEngine {
            hits++
            release.await()
            respond("""{"ok":true}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock())
        val calls = (1..3).map { async { fetcher.get("https://example.test/shared", maxAge = 10.seconds) } }
        testScheduler.runCurrent()
        release.complete(Unit)
        val results = calls.awaitAll()
        assertEquals(1, hits, "concurrent callers of one URL share one request")
        assertEquals(1, results.count { it.source == FetchSource.NETWORK })
        assertEquals(2, results.count { it.source == FetchSource.COALESCED })
    }

    @Test
    fun noStoreResponsesAreStillCoalescedWhileInFlight() = runTest {
        var hits = 0
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val engine = MockEngine {
            hits++
            release.await()
            respond(
                """{"ok":true}""",
                HttpStatusCode.OK,
                headersOf(HttpHeaders.ContentType to listOf("application/json"), HttpHeaders.CacheControl to listOf("no-store")),
            )
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock())
        val calls = (1..3).map { async { fetcher.get("https://example.test/no-store-shared") } }
        testScheduler.runCurrent()
        release.complete(Unit)

        val results = calls.awaitAll()
        assertEquals(1, hits, "overlapping no-store reads use one transfer")
        assertEquals(2, results.count { it.source == FetchSource.COALESCED })
        assertTrue(results.none { it.fromCache }, "coalescing must not claim no-store was cached")

        fetcher.get("https://example.test/no-store-shared")
        assertEquals(2, hits, "a later no-store read goes back to the network")
    }

    @Test
    fun cacheIsBounded() = runTest {
        var hits = 0
        val engine = MockEngine {
            hits++
            respond("""{}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock(), maxEntries = 2)
        fetcher.get("https://example.test/1")
        fetcher.get("https://example.test/2")
        fetcher.get("https://example.test/3")
        fetcher.get("https://example.test/3")
        assertEquals(3, hits, "the newest entries are still served from cache")
        fetcher.get("https://example.test/1")
        assertEquals(4, hits, "the oldest entry was dropped")
    }

    @Test
    fun cacheEvictionUsesRecentReadsAndByteBudget() = runTest {
        var hits = 0
        val engine = MockEngine { request ->
            hits++
            respond(request.url.encodedPath.removePrefix("/"), HttpStatusCode.OK)
        }
        val fetcher = KtorFetcher(engine, clock = FakeClock(), maxEntries = 2, maxBytes = 3)

        fetcher.get("https://example.test/a") // 1 byte
        fetcher.get("https://example.test/b") // 1 byte
        fetcher.get("https://example.test/a") // touch a, so b becomes the LRU entry
        fetcher.get("https://example.test/c") // 1 byte; capacity is now full
        fetcher.get("https://example.test/b") // b was evicted despite a being inserted first
        assertEquals(4, hits)

        fetcher.get("https://example.test/large") // 5 bytes; too large to retain
        fetcher.get("https://example.test/large")
        assertEquals(6, hits, "a body larger than the byte budget is not retained")
    }
}
