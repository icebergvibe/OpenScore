package org.openscore.model

public data class StandingsRow(
    val team: TeamRef,
    /** 1-based rank within its [StandingsGroup]. */
    val rank: Int,
    val played: Int,
    val wins: Int,
    val losses: Int,
    /** Draws/ties where the sport has them. */
    val draws: Int? = null,
    /** Losses that still earn a point (hockey OT/SO losses). */
    val otherLosses: Int? = null,
    val points: Int,
    val goalsFor: Int? = null,
    val goalsAgainst: Int? = null,
    val goalDifference: Int? = null,
    /**
     * League-specific columns that do not fit above (`regulationWins`, `streak`, `wildcard`,
     * `clinch` …). Keys are documented per provider.
     */
    val extra: Map<String, String> = emptyMap(),
)

public data class StandingsGroup(
    /** `Atlantic`, `Eastern Conference`, `Group A`, or the league name for a single table. */
    val label: String,
    val rows: List<StandingsRow>,
)

public data class StandingsTable(
    val leagueId: String,
    val seasonId: String?,
    val stage: StageKind?,
    val groups: List<StandingsGroup>,
    /** What the groups are (`division`, `conference`, `league`, `group`). */
    val grouping: String,
) {
    val rows: List<StandingsRow> get() = groups.flatMap { it.rows }
}
