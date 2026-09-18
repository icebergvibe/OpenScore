package org.openscore.server

import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import org.openscore.OpenScore
import org.openscore.UnknownLeagueException
import org.openscore.feed.FEED_VERSION
import org.openscore.feed.FeedError
import org.openscore.feed.FeedErrorResponse
import org.openscore.feed.FeedGame
import org.openscore.feed.FeedGamesResponse
import org.openscore.feed.FeedJson
import org.openscore.feed.FeedMapper
import org.openscore.net.HttpException
import org.openscore.provider.NotFoundException
import org.openscore.provider.ProviderException
import org.openscore.provider.UnsupportedCapabilityException
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * OpenScore Feed v1 over HTTP. See docs/feed-v1.md for the routes and payloads.
 * Read-only, CORS-open, JSON everywhere; live games stream as Server-Sent Events.
 */
fun Application.feedModule(openScore: OpenScore, clock: Clock = Clock.System) {
    install(DefaultHeaders) { header("X-OpenScore-Feed", FEED_VERSION) }
    install(CallLogging)
    install(ContentNegotiation) { json(FeedJson) }
    install(CORS) {
        anyHost()
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Options)
    }
    install(SSE)
    install(StatusPages) {
        exception<Throwable> { call, cause -> call.respondError(cause) }
    }

    routing {
        route("/v1") {
            get("/leagues") {
                call.respond(openScore.providers.map(FeedMapper::league))
            }

            get("/games") {
                val date = call.request.queryParameters["date"]?.let(::parseDate)
                    ?: clock.todayIn(TimeZone.UTC)
                val leagues = call.request.queryParameters["league"]
                    ?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }
                val result = openScore.gamesOn(date, leagues)
                call.respond(
                    FeedGamesResponse(
                        date = date.toString(),
                        leagues = result.leagues,
                        games = result.games.map(FeedMapper::game),
                        errors = result.errors.map { toFeedError(it.cause, it.leagueId) },
                    ),
                )
            }

            route("/games/{league}/{id}") {
                get {
                    val p = openScore.provider(call.league)
                    call.respond(FeedMapper.game(p.game(call.id)))
                }
                get("/events") {
                    val p = openScore.provider(call.league)
                    call.respond(p.events(call.id).map(FeedMapper::event))
                }
                get("/lineups") {
                    val p = openScore.provider(call.league)
                    call.respond(p.lineups(call.id).map(FeedMapper::lineup))
                }
                sse("/live") {
                    val p = openScore.provider(call.league)
                    val interval = call.request.queryParameters["interval"]?.toIntOrNull()?.seconds ?: 10.seconds
                    try {
                        p.live(call.id, interval).collect { game ->
                            send(ServerSentEvent(data = FeedJson.encodeToString(FeedGame.serializer(), FeedMapper.game(game)), event = "game", id = game.state.name))
                        }
                        send(ServerSentEvent(data = "", event = "end"))
                    } catch (e: CancellationException) {
                        throw e // the client went away; there is nobody left to tell
                    } catch (e: Exception) {
                        val err = toFeedError(e, call.league)
                        send(ServerSentEvent(data = FeedJson.encodeToString(FeedError.serializer(), err), event = "error"))
                    }
                }
            }

            get("/standings/{league}") {
                val p = openScore.provider(call.league)
                call.respond(FeedMapper.standings(p.standings(call.request.queryParameters["season"])))
            }

            route("/teams/{league}/{id}") {
                get {
                    val p = openScore.provider(call.league)
                    call.respond(FeedMapper.team(p.team(call.id)))
                }
                get("/schedule") {
                    val start = parseDate(call.request.queryParameters["startDate"] ?: throw BadRequestException("startDate is required"))
                    val end = parseDate(call.request.queryParameters["endDate"] ?: throw BadRequestException("endDate is required"))
                    if (end < start || end.toEpochDays() - start.toEpochDays() > 366) throw BadRequestException("Use an ordered date range of at most 366 days")
                    val p = openScore.provider(call.league)
                    call.respond(p.teamSchedule(call.id, start, end).map(FeedMapper::game))
                }
                get("/stats") {
                    val season = call.request.queryParameters["season"]?.takeIf { it.matches(SEASON_YEAR) }
                        ?: throw BadRequestException("season must be a four-digit year")
                    val p = openScore.provider(call.league)
                    call.respond(FeedMapper.teamStats(p.teamStats(call.id, season)))
                }
                get("/roster") {
                    val p = openScore.provider(call.league)
                    call.respond(p.roster(call.id).map(FeedMapper::player))
                }
            }

            get("/players/{league}/{id}") {
                val p = openScore.provider(call.league)
                call.respond(FeedMapper.player(p.player(call.id)))
            }
        }
    }
}

private val SEASON_YEAR = Regex("[0-9]{4}")

private val ApplicationCall.league: String get() = parameters["league"]!!.lowercase()
private val ApplicationCall.id: String get() = parameters["id"]!!

class BadRequestException(message: String) : IllegalArgumentException(message)

private fun parseDate(text: String): LocalDate =
    runCatching { LocalDate.parse(text) }.getOrElse { throw BadRequestException("date must be YYYY-MM-DD, got '$text'") }

private suspend fun ApplicationCall.respondError(cause: Throwable) {
    val error = toFeedError(cause, parameters["league"])
    val status = when (error.code) {
        "not_found", "unknown_league" -> HttpStatusCode.NotFound
        "unsupported" -> HttpStatusCode.NotImplemented
        "bad_request" -> HttpStatusCode.BadRequest
        "upstream" -> HttpStatusCode.BadGateway
        else -> HttpStatusCode.InternalServerError
    }
    if (status == HttpStatusCode.InternalServerError) application.environment.log.error("Unhandled error", cause)
    respond(status, FeedErrorResponse(error))
}

internal fun toFeedError(cause: Throwable, league: String?): FeedError = when (cause) {
    is UnknownLeagueException -> FeedError(cause.leagueId, "unknown_league", cause.message ?: "unknown league")
    is NotFoundException -> FeedError(cause.leagueId ?: league, "not_found", cause.message ?: "not found")
    is UnsupportedCapabilityException -> FeedError(cause.leagueId, "unsupported", cause.message ?: "unsupported")
    is BadRequestException -> FeedError(league, "bad_request", cause.message ?: "bad request")
    is HttpException -> FeedError(league, "upstream", "upstream ${cause.status} for ${cause.url}")
    is ProviderException -> FeedError(cause.leagueId ?: league, "upstream", cause.message ?: "provider error")
    else -> FeedError(league, "internal", cause.message ?: cause::class.simpleName ?: "error")
}
