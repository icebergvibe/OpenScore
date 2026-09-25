# Sample provenance

All samples captured **2026-09-12 05:39–06:20 UTC** with `python3 urllib` (headers
`Ocp-Apim-Subscription-Key: <service key>`, `Content-Language: en`,
`User-Agent: OpenScore/0.1`), pretty-printed with `json.dump(indent=2,
ensure_ascii=False)`. The exact URL for each is in the README next to the sample link.
Base URLs: `https://apim.laliga.com/public-service` (files without prefix) and
`https://apim.laliga.com/webview` (files prefixed `wv-`). Subscription
`laliga-easports-2026` (LaLiga EA Sports 2026/27), matchday 5 in progress
(1 of 10 played).

Matches used:

| id | slug | Match | State | Used by |
|---|---|---|---|---|
| `102297` (Opta `g2650810`) | `temporada-2026-2027-laliga-ea-sports-sevilla-fc-valencia-cf-5` | Sevilla FC 1–0 Valencia CF, MD 5, 2026-09-11 19:00Z | `FullTime` (~9 h after) | `match.final.json`, `wv-match.final.json`, `wv-match-lineups.final.json`, `wv-match-events.final.json`, `wv-match-comments.final.json`, `wv-match-stats.final.json` |
| `102295` (Opta `g2650808`) | `temporada-2026-2027-laliga-ea-sports-real-madrid-rayo-vallecano-5` | Real Madrid v Rayo Vallecano, MD 5, 2026-09-12 19:00Z | `PreMatch` (13 h before) | `match.pre.json`, `wv-match.pre.json`, `wv-match-lineups.pre.json`, `wv-match-events.pre.json`, `wv-match-comments.pre.json`, `wv-match-stats.pre.json`, `wv-matches-tv.json` |

Live pair, captured **2026-09-13 12:47 UTC** (`python3 -m json.tool`) during RC Celta – Málaga
CF (MD 5, kick-off 12:00Z), 44th minute:

| Sample | URL | Shows |
|---|---|---|
| `matches-week.live.json` | `/api/v1/matches?subscriptionSlug=laliga-easports-2026&week=5&limit=100&orderField=date&orderType=asc` | the week listing says only `"status": "FirstHalf"` for the running match — no `match_time`, no `period_started` |
| `wv-match.live.json` | `/api/web/matches/temporada-2026-2027-laliga-ea-sports-rc-celta-malaga-cf-5` | the match resource has `match_time: 44` and `period_started.FirstHalf.start` (second precision) |

Team samples use Real Madrid (`real-madrid`, id `15`, Opta `t186`); player samples use
Jude Bellingham (`jude-bellingham`, id `15165`, Opta `p244855`).
`matches-segunda.json` is `laliga-hypermotion-2026` MD 5, `matches-copa.json`
`copa-del-rey-2026` round 1, `matches-last-season.json` `laliga-easports-2025` MD 38,
`matches-premier-league-mirror.json` `english-premier-league-2026` MD 4.

## Truncations

Arrays only; objects are intact.

| File | Truncation |
|---|---|
| `subscriptions.json` | `subscriptions[]` first 3 of 14 |
| `subscription-teams-stats.json` | `team_stats[]` first 3 of 20 |
| `team-squad.json` | `squads[]` first 5 of 34 |
| `team-squad-manager.json` | `squads[]` first 3 of 34 |

## Not captured

No live-state samples yet (first MD 5 kick-off was 6 h after capture). See the README
TODO list.

## Live states

`wv-match.live.json`, `wv-match.halftime.json` and `wv-match.live-second-half.json` are
three polls of one `tools/live-capture` recording of RC Celta 1-1 Málaga CF
(`temporada-2026-2027-laliga-ea-sports-rc-celta-malaga-cf-5`, MD 5, 2026-09-13), 366 polls
at 5 s from 05:17Z to 14:02Z.

```
./gradlew :tools:live-capture:run --args="--league la-liga --game temporada-2026-2027-laliga-ea-sports-rc-celta-malaga-cf-5 --max 10h"
```

| Sample | Poll | Feed state |
|---|---|---|
| `wv-match.live.json` | 241 (12:46:43Z) | `FirstHalf`, `match_time` 44, 1-0 |
| `wv-match.halftime.json` | 248 (12:51:14Z) | `HalfTime`, `match_time` 48, `FirstHalf.stop` set |
| `wv-match.live-second-half.json` | 279 (13:08:08Z) | `SecondHalf`, `match_time` 45, `SecondHalf.start` set |

The capture polled the match resource 59 times but `…/events` only 11 and `…/lineups` 3,
so it says nothing about how those two behave under load.

Pretty-printed with `jq .`; nothing truncated. The raw recording is under
`build/capture/la-liga/` and is git-ignored.
