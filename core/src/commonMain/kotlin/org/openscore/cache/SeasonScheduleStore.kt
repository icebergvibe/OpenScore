package org.openscore.cache

import org.openscore.model.Game
import kotlin.time.Instant

/** One complete, normalized league season as a provider last imported it. */
public data class SeasonSnapshot(
    val seasonId: String,
    /** When [games] were imported from the upstream season listing; the provider decides how old is too old. */
    val savedAt: Instant,
    val games: List<Game>,
)

/**
 * Durable storage for one complete, normalized league season.
 *
 * Providers never put upstream response bodies here. A non-null [load] means [save] completed for
 * the whole season, so an interrupted import cannot masquerade as a complete offline schedule.
 * The snapshot is a fallback and a bootstrap, not the truth: the provider that owns it decides
 * when it is too old and refreshes the games that can still change from the network.
 */
public interface SeasonScheduleStore {
    /** The season stored for [leagueId], whichever season that is, or null when none was saved completely. */
    public suspend fun load(leagueId: String): SeasonSnapshot?

    /** Replaces whatever was stored for [leagueId] with [snapshot], atomically. */
    public suspend fun save(leagueId: String, snapshot: SeasonSnapshot)

    /**
     * Rewrites some games of the stored [seasonId] in place — a game's final record, read from a
     * game route between two season imports. A no-op when that season is not stored, so an update
     * can never stand in for a season; [SeasonSnapshot.savedAt] is left alone, because only a
     * whole import says how old the schedule is.
     */
    public suspend fun update(leagueId: String, seasonId: String, games: List<Game>)
}

public object NoopSeasonScheduleStore : SeasonScheduleStore {
    override suspend fun load(leagueId: String): SeasonSnapshot? = null
    override suspend fun save(leagueId: String, snapshot: SeasonSnapshot): Unit = Unit
    override suspend fun update(leagueId: String, seasonId: String, games: List<Game>): Unit = Unit
}
