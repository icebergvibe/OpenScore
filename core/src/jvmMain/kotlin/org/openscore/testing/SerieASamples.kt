package org.openscore.testing

import org.openscore.providers.seriea.SerieAProvider
import java.io.File

/** URL → sample routing for the Serie A samples captured on 2026-09-11 (2026/27, between MD 3 and 4). */
public object SerieASamples {
    public const val SEASON: String = "serie-a::Football_Season::ed7fdc2a3e7b408b942ec177b7b956b5"
    public const val MATCHDAY_1: String = "serie-a::Football_MatchDay::59bb8da36eca4ccc9296d6296c2d4654"
    public const val DAY: String = "2026-08-22"
    public const val FINAL_MATCH_ID: String = "serie-a::Football_Match::8f81947dbf6149b7b2801dbf1fa8d68c" // Udinese 1–2 Lazio
    public const val PRE_MATCH_ID: String = "serie-a::Football_Match::07028ce41c0d4c43b0c207a2561310f3" // Inter–Sassuolo
    public const val TEAM_ID: String = "serie-a::Football_Team::b7421caff23448c49134fa4f9095ee09" // Inter
    private const val C = SerieAProvider.SERIE_A

    public val paths: Map<String, String> = mapOf(
        "/competitions/$C/seasons" to "competition-seasons.json",
        "/seasons/$SEASON/matchdays" to "matchdays.json",
        "/seasons/$SEASON/matches?matchDayId=$MATCHDAY_1" to "matches-matchday.json",
        "/seasons/$SEASON/matches/$FINAL_MATCH_ID/header" to "match-header.final.json",
        "/seasons/$SEASON/match/$FINAL_MATCH_ID/summary" to "match-summary.final.json",
        "/seasons/$SEASON/match/$FINAL_MATCH_ID/teamstats" to "match-teamstats.final.json",
        "/seasons/$SEASON/matches/$FINAL_MATCH_ID/lineups" to "match-lineups.final.json",
        "/seasons/$SEASON/matches/$PRE_MATCH_ID/header" to "match-header.pre.json",
        "/seasons/$SEASON/match/$PRE_MATCH_ID/summary" to "match-summary.pre.json",
        "/seasons/$SEASON/match/$PRE_MATCH_ID/teamstats" to "match-teamstats.pre.json",
        "/seasons/$SEASON/matches/$PRE_MATCH_ID/lineups" to "match-lineups.pre.json",
        "/seasons/$SEASON/standings" to "standings.json",
        "/teams/$TEAM_ID/roster" to "team-roster.json",
        "/teams/$TEAM_ID/roster?seasonId=$SEASON" to "team-roster.season.json",
    )

    public fun register(fetcher: SampleFetcher, root: File = SampleFetcher.repoRoot()): SampleFetcher =
        fetcher.routes(SerieAProvider.DEFAULT_BASE_URL, SampleFetcher.samplesDir("football", "serie-a", root), paths)
}
