package org.openscore.capture

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.openscore.feed.FeedJson
import org.openscore.feed.FeedMapper
import org.openscore.model.Game

/**
 * One line of `ticks.jsonl`: what the mapped model said at one poll. Compact on purpose — a
 * three-hour capture at 10 s is a thousand of these, and the point is to read the clock and
 * state columns down the file.
 */
@Serializable
internal data class Tick(
    val seq: Int,
    /** Wall clock, ISO UTC. */
    val at: String,
    val state: String? = null,
    val rawState: String? = null,
    val score: String? = null,
    val period: String? = null,
    val clock: String? = null,
    val elapsed: Int? = null,
    val remaining: Int? = null,
    val running: Boolean? = null,
    val periodScores: String? = null,
    val situation: JsonObject? = null,
    val ending: String? = null,
    /** Event count when the call included events; null = not loaded. */
    val events: Int? = null,
    val lineups: Int? = null,
    val stats: Int? = null,
    /** Which [Signature] fields changed since the previous tick — the reason a tick was saved. */
    val changed: List<String> = emptyList(),
    /** Files written at this tick, relative to the game directory. */
    val saved: List<String> = emptyList(),
    val error: String? = null,
    val requests: Int = 0,
)

/**
 * The fields whose change makes a tick worth saving in full (raw bodies + mapped game).
 * The running clock is deliberately excluded: it changes at every tick during play, and the
 * `ticks.jsonl` line already records it; the checkpoint interval catches a mid-period sample.
 */
internal data class Signature(
    val state: String,
    val rawState: String?,
    val score: String?,
    val period: Int?,
    val running: Boolean?,
    val periodScores: String,
    val ending: String?,
    val events: Int?,
    val lineups: Int?,
    val situation: String?,
) {
    fun diff(other: Signature?): List<String> {
        if (other == null) return listOf("first")
        val out = ArrayList<String>()
        if (state != other.state) out += "state"
        if (rawState != other.rawState) out += "rawState"
        if (score != other.score) out += "score"
        if (period != other.period) out += "period"
        if (running != other.running) out += "running"
        if (periodScores != other.periodScores) out += "periodScores"
        if (ending != other.ending) out += "ending"
        if (events != other.events) out += "events"
        if (lineups != other.lineups) out += "lineups"
        if (situation != other.situation) out += "situation"
        return out
    }

    companion object {
        fun of(g: Game, lineups: Int?): Signature = Signature(
            state = g.state.name,
            rawState = g.rawState,
            score = g.score?.let { "${it.home}-${it.away}" },
            period = g.clock?.period?.number,
            running = g.clock?.running,
            periodScores = g.periodScores.joinToString(" ") { "${it.period.label}:${it.home}-${it.away}" },
            ending = g.ending?.name,
            events = g.events?.size,
            lineups = lineups,
            situation = g.situation?.let { FeedMapper.situation(it).toString() },
        )
    }
}

internal fun Game.tick(seq: Int, at: String, lineups: Int?, sig: Signature, changed: List<String>, saved: List<String>, requests: Int): Tick = Tick(
    seq = seq,
    at = at,
    state = state.name,
    rawState = rawState,
    score = sig.score,
    period = clock?.period?.label,
    clock = clock?.time?.label,
    elapsed = clock?.time?.elapsed?.inWholeSeconds?.toInt(),
    remaining = clock?.time?.remaining?.inWholeSeconds?.toInt(),
    running = clock?.running,
    periodScores = sig.periodScores.ifEmpty { null },
    situation = situation?.let { FeedMapper.situation(it) },
    ending = sig.ending,
    events = events?.size,
    lineups = lineups,
    stats = stats.size.takeIf { it > 0 },
    changed = changed,
    saved = saved,
    requests = requests,
)

internal fun Game.toFeedJson(): String = FeedJson.encodeToString(org.openscore.feed.FeedGame.serializer(), FeedMapper.game(this))
