# Sample provenance

All samples captured **2026-09-11 ~17:12–17:20 UTC** with
`curl -A "OpenScore/0.1" -H "Accept: application/json"` and no other headers
(none are required), pretty-printed with `json.dump(indent=2, ensure_ascii=False)`.
The exact URL for each is in the README next to the sample link. Base URL:
`https://api-sdp.legaseriea.it/v1/serie-a/football/`, season
`serie-a::Football_Season::ed7fdc2a3e7b408b942ec177b7b956b5` (2026/27).

Matches used:

| matchId | Match | State | Used by |
|---|---|---|---|
| `serie-a::Football_Match::8f81947dbf6149b7b2801dbf1fa8d68c` | Udinese 1–2 Lazio, Matchday 3, 2026-09-07 18:45Z | `FINISHED` / `FULL_TIME` | `*.final.json` |
| `serie-a::Football_Match::07028ce41c0d4c43b0c207a2561310f3` | Inter v Udinese, Matchday 4, 2026-09-14 18:45Z | `UPCOMING` / `PRE_MATCH` (captured ~3 days before kick-off) | `*.pre.json` |

Team samples use Inter (`serie-a::Football_Team::b7421caff23448c49134fa4f9095ee09`);
`matches-matchday.json` is Matchday 1 (`serie-a::Football_MatchDay::59bb8da36eca4ccc9296d6296c2d4654`).

## Truncations

Arrays only, per CONTRIBUTING; objects are intact.

| File | Truncation |
|---|---|
| `matches.json` | `matches[]` first 12 of 380 (full response 1.17 MB) |
| `matches-team.json` | `matches[]` first 6 of 38 |
| `match-ranking.final.json` | `home.players[]` / `away.players[]` first 3 each (full response 1.97 MB) |
| `match-advancedmatchevents.final.json` | `advancedEvents[]` first 15 of 1460 (full response 860 KB) |
| `team-roster.json` | `players[]` first 25 of 387 |
| `team-roster.season.json` | complete (45 players, `?seasonId=` 2026/27, captured 2026-09-16) |
| `stats-teams.json` | `teams[]` first 3 of 20 (full response 1.41 MB) |
| `stats-players.json` | `players[]` first 3 of 10 on page 1 of 41 |

Everything else is complete as returned.
