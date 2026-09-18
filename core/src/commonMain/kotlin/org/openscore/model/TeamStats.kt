package org.openscore.model

/** Season totals, with provider-defined groups such as batting and pitching. */
public data class TeamSeasonStats(
    val leagueId: String,
    val teamId: String,
    val seasonId: String,
    val groups: List<TeamStatGroup>,
)

public data class TeamStatGroup(val key: String, val label: String, val stats: List<TeamStat>)

/** Display values preserve the sport's notation (e.g. ".235" or "1332.1" innings). */
public data class TeamStat(val key: String, val label: String, val value: String)
