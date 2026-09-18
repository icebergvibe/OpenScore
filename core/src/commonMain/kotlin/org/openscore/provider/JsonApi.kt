package org.openscore.provider

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import org.openscore.net.Fetcher
import org.openscore.net.FetchResponse
import org.openscore.net.OpenScoreJson
import kotlin.time.Duration

/**
 * Shared "GET this URL and decode it" logic so every provider fails the same way:
 * 404 → [NotFoundException], other non-2xx → [org.openscore.net.HttpException],
 * non-JSON body → [ProviderException], undecodable JSON → [ProviderException].
 */
public suspend fun <T> Fetcher.getJson(
    url: String,
    strategy: DeserializationStrategy<T>,
    maxAge: Duration,
    leagueId: String,
    headers: Map<String, String> = emptyMap(),
): T {
    val response = get(url, headers, maxAge)
    if (response.status == 404) throw NotFoundException("$leagueId: $url → 404", leagueId)
    response.requireSuccess()
    return decodeJson(response, strategy, leagueId)
}

public fun <T> decodeJson(response: FetchResponse, strategy: DeserializationStrategy<T>, leagueId: String): T {
    val body = response.body.trimStart()
    if (!response.isJson && !(body.startsWith("{") || body.startsWith("["))) {
        throw ProviderException("$leagueId: ${response.url} returned ${response.contentType}, expected JSON", leagueId = leagueId)
    }
    return try {
        OpenScoreJson.decodeFromString(strategy, response.body)
    } catch (e: SerializationException) {
        throw ProviderException("$leagueId: cannot parse ${response.url}: ${e.message}", e, leagueId)
    } catch (e: IllegalArgumentException) {
        throw ProviderException("$leagueId: cannot parse ${response.url}: ${e.message}", e, leagueId)
    }
}

/**
 * Like [getJson] but returns null for a 2xx response with an empty body — some platforms
 * (Sportality) answer "nothing yet" that way instead of with `[]` or `{}`.
 */
public suspend fun <T> Fetcher.getJsonOrNull(
    url: String,
    strategy: DeserializationStrategy<T>,
    maxAge: Duration,
    leagueId: String,
): T? {
    val response = get(url, emptyMap(), maxAge)
    if (response.status == 404) throw NotFoundException("$leagueId: $url → 404", leagueId)
    response.requireSuccess()
    if (response.body.isBlank()) return null
    return decodeJson(response, strategy, leagueId)
}
