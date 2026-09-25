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

## Live states

The live bodies are polls lifted out of a `tools/live-capture` recording of
`serie-a::Football_Match::b7ecdcd497b3445490aa0f0ba58468b5` (Lecce 3-2 Monza, MD 4,
2026-09-13), 385 polls at 5 s from 05:17Z to 15:03Z.

```
./gradlew :tools:live-capture:run --args="--league serie-a --game serie-a::Football_Match::b7ecdcd497b3445490aa0f0ba58468b5 --max 10h"
```

| Sample | Poll | Feed state |
|---|---|---|
| `match-header.live.json` | 144 (13:24:08Z) | `LIVE`/`FIRST_HALF`, `time` 21, 3-1 |
| `match-header.halftime.json` | 207 (14:00:52Z) | `LIVE`/`HALF_TIME_BREAK`, `time` 45, `additionalTime` 0 |
| `match-summary.live.json` | 131 (13:14:24Z) | 2 goals, `time` 11 |
| `match-summary.live-later.json` | 159 (13:36:08Z) | 4 goals, `time` 33 |
| `match-teamstats.live.json` | 250 (14:15:48Z) | `LIVE`/`SECOND_HALF`, 50' |
| `match-lineups.live.json` | 291 (14:29:31Z) | `LIVE`/`SECOND_HALF`, 64' |

**What this capture lost, and why.** `header`, `summary`, `teamstats` and `lineups` all
hang off the same season-and-match path prefix, and the capture tool truncated a long
path to 90 characters before naming the file. All four therefore wrote to **one** file
name per poll and only the last survived, so three of every four bodies are gone. The
slug now keeps the endpoint name off the end of the path
(`Capture.slugKeepsTheEndpointNameOffTheEndOfALongPath` guards it), but that does not
recover these. What is missing as a result: the `header` at `SECOND_HALF` and at
`FULL_TIME` (the 2026-09-11 `match-header.final.json` covers the finished shape), the
`summary` at `HALF_TIME_BREAK` and `SECOND_HALF`, and every `summary` body from the
poll a `header` won.

Because of that, `match-header.live.json` (poll 144) and `match-summary.live.json`
(poll 131) are **not the same poll** - the summary of poll 144 did not survive. The pair
still shows the real failure mode (the header ahead of the summary), one goal deeper
than the run actually showed.

Pretty-printed with `jq .`; nothing truncated. The raw recording is under
`build/capture/serie-a/` and is git-ignored.
