# CHL (Champions Hockey League) API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | Europe (24 clubs from ~13 leagues) |
| **Official site** | https://www.chl.hockey |
| **Base URL** | `https://www.chl.hockey/api/s3?q={file}` (static, 60 s cache) · `https://www.chl.hockey/api/s3/live?q={file}` (live, 5 s) |
| **Auth** | None — the site reads pre-rendered JSON files from an S3 bucket through this proxy |
| **Format** | JSON envelope `{ _type, data, errors[] }` (sometimes `includes[]`). Errors are **S3 XML** |
| **CORS** | `Access-Control-Allow-Origin: https://admin.corebine.com` only — browser apps need a proxy |
| **WAF / UA requirement** | None (Cloudflare in front; no `User-Agent` needed) |
| **Last full verification** | 2026-09-11 |
| **Status** | ✅ verified (pre-game + final REG/OT/SO) · 🚧 live-state samples pending |

## Overview

chl.hockey runs on **Corebine**, a CMS whose sport data layer is *not* a REST API but
a set of **pre-rendered JSON files** in an S3 bucket, fetched through
`/api/s3?q=<filename>` (static bucket, `Cache-Control: max-age=60`) or
`/api/s3/live?q=<filename>` (live bucket, `max-age=5, no-store`). The same file
name works on both paths; the site uses `/live` for anything that changes during a
game. Every response is wrapped in `{"_type": "Corebine.Core.Protocol.Response.*",
"data": …, "errors": []}` and every entity carries a `_type` string
(`Corebine.Core.Sport.Match`, `…Team`, `…Player`, …) and a 24-hex `_entityId`.

Both hosts named in the unverified note (`widget.championshockeyleague.com`,
`api.chl.hockey`) **do not resolve**; that note was entirely fictional. The real
file catalogue was recovered from the inline module configs in the page HTML
(`corebine.import('markups/modules/…').then(m => m.XModule('#id', {baseFeedsUrl:
'…/api/s3?q=…'}))`) and the bundle's file-name template
`{feedType}-{competition}-{season}-{event}-{team}-{person}.json` (empty parts and
their dashes are collapsed). Samples captured **2026-09-11** during the 2026–27
regular season (game day 3 of 6 played; games on this date start 16:00–17:00Z).

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Competition | 24-hex, **constant** | `21ec9dad81abe2e0240460d0` | Hard-code; appears in every file name and `source.competition._entityId` |
| Season | 24-hex | `fc954f6d33272fdf4a8b95bb` = 2026/27, `3c5f99fa605394cc65733fc9` = 2025/26 | `corebine.pageSettings.attachments.currentSeason` in any page's HTML; full list below |
| Match | 24-hex `_entityId` | `4f36a358a795d91b2fbb0c9b` | `live-events.json`, `schedule-*.json` → `data[]._entityId` |
| Match ext id | numeric string | `"3012"` | `externalId` (the provider's id; matches the SHL-side `gameExtId` for the same game) |
| Team | 24-hex `_entityId` | `c9840a203093f97cae0a9495` (KooKoo) | `teams-*.json`; `teams.home._entityId` on matches |
| Team ext id / code | numeric string / 3 letters | `"107"` / `KOO` | `externalId`, `shortName`. Logos are on Cloudinary keyed by `externalId` (see quirks) |
| Player | 24-hex `_entityId` | `c0add44e7a2094c45b3e5fe9` | lineups/players files; `source.query` gives the player file name directly |
| Player ext id | numeric string | `"446173"` | `externalId` |

**Season IDs (from the schedule page's season selector):**

| Season | ID | | Season | ID |
|---|---|---|---|---|
| 2026/27 | `fc954f6d33272fdf4a8b95bb` | | 2020/21 | `cb8140fcc60dfdbd9fd298a5` |
| 2025/26 | `3c5f99fa605394cc65733fc9` | | 2019/20 | `8f7d5c9a161f121955e7a148` |
| 2024/25 | `65772c03f5465c804a4fe7de` | | 2018/19 | `3b8d1d7295522f7481d65ded` |
| 2023/24 | `384dfd08cf1b5e6e93cd19ba` | | 2017/18 | `7ff8d796a87a86b861bf9e22` |
| 2022/23 | `42d2f45345814558d4daff38` | | 2016/17 | `741bae588668e5c4a0eeade1` |
| 2021/22 | `f73bbb143cc88c3ebe188d77` | | 2015/16 | `512ec0d6dfe80058c7fed4f6` |
| | | | 2014/15 | `0acaee27317455a72a914215` |

There is no "list seasons" file; to discover a new season, parse
`corebine.pageSettings` from `https://www.chl.hockey/en/schedule` (it is inline JSON
in a `<script>`), or add the ID here when the CHL publishes it.

## Discovery path

1. **Scoreboard / live ticker:** `GET /api/s3/live?q=live-events.json` — every match
   in a window around today (36 matches on 2026-09-11: game days 2–5), with
   `status`, `state`, scores. Poll this for "which games are on".
2. **Full season:** `GET /api/s3?q=schedule-{competition}-{season}.json`.
3. **A match:** `GET /api/s3/live?q=live-event-{match}-scoreboard.json` — state,
   score by period, and the structured event list; `…-lineups.json` for rosters,
   officials and coaches; `…-players-stats.json` / `…-teams.json` for boxscore;
   `…-actions.json` is a text ticker with YouTube highlight links.
4. **Standings:** `GET /api/s3/live?q=standings-groups-{competition}-{season}.json`;
   playoff bracket `…/api/s3?q=standings-playoffs-{competition}-{season}.json`.
5. **Teams / players:** `teams-{competition}-{season}.json`,
   `team-players-info-{competition}-{season}-{team}.json`,
   `player-{competition}-{player}.json`.

Recommended poll interval for live games: **10 s** on `live-event-*-scoreboard.json`
(edge cache is 5 s); `live-events.json` every 30–60 s (the site's own schedule module
uses 60 s–15 min). Send `Accept-Encoding: gzip`.

## Files

All paths below are relative to `https://www.chl.hockey/api/s3` (static) or
`https://www.chl.hockey/api/s3/live` (live); `{C}` = competition id, `{S}` = season
id, `{M}` = match id, `{T}` = team id, `{P}` = player id.

### `GET /api/s3/live?q=live-events.json`

| | |
|---|---|
| **Purpose** | Scoreboard: all matches around the current date, all seasons' stages. |
| **Sample** | [`samples/live-events.json`](samples/live-events.json) captured 2026-09-11 (36 matches) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=5, no-cache, no-store` |

`data[]` of **Match** objects:

```
_entityId, externalId, _modifyDate
startDate            – ISO-8601 UTC ("2026-09-10T15:30:00.000Z"); startDateNotConfirmed bool
status               – not-started | finished  (live value NOT YET CAPTURED — expected "in-progress")
state                – { name, shortName }: "Before game"/"BG", "Fulltime"/"F", "Fulltime/Overtime"/"F/OT",
                       "Fulltime/Shootout"/"F/SO"  (live values not yet captured)
stage                – { group{order, name "Regular Season"|"Round of 16"|…}, round{order, name "Game Day 3"} }
teams.home / .away   – { _entityId, externalId, name, shortName, link{url} }
results.scores       – { home, away }   (0–0 before the game)
venue{_entityId, name}, link{url "/matches/{id}/{slug}"}
```

Note: for **not-started** games `state` still reads `"Fulltime"` in the schedule
file but `"Before game"` in the scoreboard file — use `status`, not `state.name`,
to decide whether a game has been played.

### `GET /api/s3?q=schedule-{C}-{S}.json` &nbsp;·&nbsp; `GET /api/s3?q=team-schedule-{C}-{S}-{T}.json`

| | |
|---|---|
| **Purpose** | Every match of a season (84 in 2026–27 so far — regular season only until the bracket is drawn); one team's matches. |
| **Samples** | [`schedule.json`](samples/schedule.json) (2026–27, full) · [`schedule.2025-26.json`](samples/schedule.2025-26.json) (**truncated**, with OT/SO/playoff examples) · [`team-schedule.json`](samples/team-schedule.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=60` |

Same Match object as above. Playoff rounds appear as `stage.group.name` =
`Round of 16`, `Quarter-Finals`, `Semi-Finals`, `Final` (2025–26: 101 games).

### `GET /api/s3/live?q=live-event-{M}-scoreboard.json`

| | |
|---|---|
| **Purpose** | **The** match endpoint: header, state, score by period, and structured events with player objects. |
| **Samples** | [`live-event-scoreboard.pre.json`](samples/live-event-scoreboard.pre.json) · [`live-event-scoreboard.final.json`](samples/live-event-scoreboard.final.json) (REG) · [`live-event-scoreboard.final-overtime.json`](samples/live-event-scoreboard.final-overtime.json) · [`live-event-scoreboard.final-shootout.json`](samples/live-event-scoreboard.final-shootout.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=5` |

```
data (Match) + audience (attendance) +
results.scores{home, away}
results.periods[]   { name "1st Period"|"2nd Period"|"3rd Period"|"Overtime"|"Shootout", shortName,
                      status not-started|finished (live: presumably "in-progress"),
                      scores{home, away}   (absent before the period starts),
                      actions[] }
```

Periods are listed **newest first** in finished games. There is **no game clock**
field; the current period and its `status` are the only live-state signals found
so far (see TODO).

**Action** (`results.periods[].actions[]`):

```
actionType, externalId, teamType home|away
time{ regularTime }           – SECONDS FROM GAME START (1491 = 24:51 game time, i.e. 04:51 of P2)
message{ title, description } – "Goal, 1:0", "Goal, 2:1, PP", "Penalty", "Goalie In"…
players[]                     – full Player objects (scorer first, then assists)
actions[]                     – sub-actions: for a goal, [goal, assist]; for goalkeeper-change, [substitution-in]…
video{ id (YouTube), thumbnail }  – on goals / event-end when a clip exists
```

**Observed `actionType`:** `event-start`, `event-end`, `period-start`, `period-end`,
`goal`, `goal-penalty` (shootout-deciding / penalty-shot goal), `penalty`,
`goalkeeper-change`, `timeout`, `shot-penalty-miss` (shootout miss). Sub-action types:
`assist`, `substitution-in`. The bundle also treats `goal-own` as a goal. **No shots,
hits, faceoffs, or coordinates.** Strength is only in the goal `message.title`
(`"Goal, 2:1, PP"`); penalty details (infraction, minutes) are only in the
**actions.json** ticker text (`"NIT Dmytro Timashov called for Cross-checking. 2
Minutes."`).

### `GET /api/s3/live?q=live-event-{M}-actions.json`

Verified 2026-09-11 · [`live-event-actions.final.json`](samples/live-event-actions.final.json)
· [`live-event-actions.final-shootout.json`](samples/live-event-actions.final-shootout.json)
· [`live-event-actions.pre.json`](samples/live-event-actions.pre.json). Same period/action
structure but **text only** (no `players[]`), richer `message.description`, and the
YouTube `video` per goal. Use it for penalty text and highlights, not for parsing.

### `GET /api/s3/live?q=live-event-{M}-lineups.json`

| | |
|---|---|
| **Purpose** | Rosters with **line assignments**, officials and coaching staff. |
| **Samples** | [`live-event-lineups.final.json`](samples/live-event-lineups.final.json) · [`live-event-lineups.pre.json`](samples/live-event-lineups.pre.json) (already populated pre-game) |
| **Last verified** | 2026-09-11 |

```
teams.home/.away.athletes[]  { _entityId, externalId, firstName, lastName, number,
                               position{ shortName GK|DE|FW, name, category "First Line"|"Second Line"|
                                         "Third Line"|"Fourth Line"|"Goalkeepers", categoryIndex 1–4|6,
                                         index 11,12 (GK) | 21,22 (D pair) | 31,32,33 (F line) },
                               source{ query "player-{C}-{P}.json" }, actions[] }
teams.home/.away.staff[]     { firstName, lastName, position{ name "Head Coach", category } }
officials[]                  { firstName, lastName, number, position{ shortName RF|LM, name Referee|Linesman } }
```

`position.index` encodes slot: tens digit = unit (1 goalie, 2 defence, 3 forwards),
units digit = slot. Best lineup data of any league mapped so far.

### `GET /api/s3/live?q=live-event-{M}-players-stats.json` &nbsp;·&nbsp; `…-players.json` &nbsp;·&nbsp; `…-teams.json`

Verified 2026-09-11 · [`live-event-players-stats.final.json`](samples/live-event-players-stats.final.json)
· [`live-event-players.final.json`](samples/live-event-players.final.json)
· [`live-event-teams.final.json`](samples/live-event-teams.final.json) / [`.pre.json`](samples/live-event-teams.pre.json).

- `players-stats`: per athlete `stats.properties[] {name, shortName, value, units?}` —
  `G, PPG, SHG, P, A, PIM, +/-, SOG, S%, BS, FOW, FOW%, TOI (sec)`; goalies have
  goalie properties. Boxscore source.
- `players`: same athletes without stats (roster snapshot).
- `teams`: team totals `Sh, S, S%, PP, PP%, PIM, PK%, Sv, Sv%, FOW, SB, Hits`.

All stat values use the generic **property list** pattern
(`{name, shortName, value, units}`) — parse by `shortName`.

### `GET /api/s3/live?q=standings-groups-{C}-{S}.json` &nbsp;·&nbsp; `GET /api/s3?q=standings-playoffs-{C}-{S}.json`

| | |
|---|---|
| **Samples** | [`standings-groups.json`](samples/standings-groups.json) (single 24-team table) · [`standings-playoffs.json`](samples/standings-playoffs.json) (2025–26 bracket) · [`standings-playoffs.empty.json`](samples/standings-playoffs.empty.json) |
| **Last verified** | 2026-09-11 |

`standings-groups`: `data[]` of **Series** `{status in-progress|finished, stage.group,
teams[]{…, stats{ place, points, isLive, placeChange, pointsPercentage,
matches{played.total, won{total, overtimes, shootouts}, lost{…}, drawn},
goals{scored.total, conceded.total} }}}`. One group in the current format.

`standings-playoffs`: nested Series → rounds (`R16`, `QF`, `SF`, `F`) → `events[]`
(pairings) with `teams[]` and their match `events`. Empty `data: []` until the
bracket exists.

### Teams, players, season stats (static bucket, `max-age=60`)

| File | Sample | Notes |
|---|---|---|
| `teams-{C}-{S}.json` | [teams.json](samples/teams.json) | 24 teams: `_entityId, externalId, name, shortName, country{name, code}` |
| `teams-stats-{C}-{S}.json` | [teams-stats.json](samples/teams-stats.json) | Team season stat properties |
| `team-info-{C}-{S}-{T}.json` | [team-info.json](samples/team-info.json) | `info.properties[]` (Founded, Website, Home Venue, …) |
| `team-players-info-{C}-{S}-{T}.json` | [team-players-info.json](samples/team-players-info.json) | Roster with positions, nationality |
| `team-players-stats-{C}-{S}-{T}.json` | [team-players-stats.json](samples/team-players-stats.json) | Roster with season stats |
| `team-stats-{C}-{S}-{T}.json` | [team-stats.json](samples/team-stats.json) | One team's season totals |
| `player-{C}-{P}.json` | [player.json](samples/player.json) | `nationality{name, code}`, `info.properties` (Height cm, Weight kg, Shoots, Born…), `team`, `stats` |
| `statistic-players-{C}-{S}.json` | [statistic-players.json](samples/statistic-players.json) (**truncated**) | All skaters: `GP G A P P/GP xG +/- PIM PPG SHG GWG SOG S% BS HITS FOW% TOI/GP` — **1.5 MB** |
| `statistic-goalkeepers-{C}-{S}.json` | [statistic-goalkeepers.json](samples/statistic-goalkeepers.json) (**truncated**) | |
| `statistic-teams-{C}-{S}.json` | [statistic-teams.json](samples/statistic-teams.json) | `GP W OTW T OTL L P P% xG GF GA GDF GG GAG FOW% PP% PK% AVG.ATT.` |
| `statistic-leaderboard-players-{C}-{S}.json` · `…-goalkeepers-…` | [statistic-leaderboard-players.json](samples/statistic-leaderboard-players.json) · [statistic-leaderboard-goalkeepers.json](samples/statistic-leaderboard-goalkeepers.json) | `data[]` of `{name, shortName, direction, items[]}` groups |
| `live-event-{M}-leaderboard-recap.json` | [live-event-leaderboard-recap.final.json](samples/live-event-leaderboard-recap.final.json) | Post-game top performers |

Also referenced by the site but not sampled: `statistic-players-advanced-{C}-{S}.json`,
`statistic-leaderboard-teams-{C}-{S}.json`, `statistic-goalkeepers-{C}.json`
(all-time, no season). Season-less variants (`{C}.json`) are all-time tables.

## Game states

| Source | Values | Core `GameState` |
|---|---|---|
| `status` | `not-started`, `finished`; live value **not captured** (bundle checks `isMatchLive`; expected `in-progress`) | SCHEDULED / FINAL / LIVE |
| `state.shortName` | `BG` Before game, `F` Fulltime, `F/OT`, `F/SO`; live values not captured | outcome REG/OT/SO |
| `results.periods[].status` | `not-started`, `finished` (+ presumably `in-progress`) | period / INTERMISSION heuristic: all listed periods finished but `status` not finished |

## Quirks & gotchas

- **It's files, not endpoints.** There are no query parameters beyond `q=`. If a file
  doesn't exist you get `403 AccessDenied` **as S3 XML** (`Content-Type:
  application/xml`), not JSON — e.g. a bad match id or a typo in the file name.
- **Two buckets, same names.** `/api/s3?q=` is cached 60 s; `/api/s3/live?q=` 5 s.
  Match files (`live-event-*`) are served from both; use `/live` during games.
- **`state.name` is unreliable pre-game** (`"Fulltime"` in schedule files for games
  that haven't started). Use `status`.
- **Event times are absolute seconds** (`regularTime`) from the start of the game,
  not per-period; period 2 starts at 1200, OT at 3600.
- **Periods newest-first** in finished games' `scoreboard`/`actions`.
- **Everything is `_type`-tagged** and wrapped; nested `actions[]` repeat the parent
  action. Player objects are repeated in full (with `source`, `position`) every
  time they appear — files are verbose but compress well (gzip ≈ 10×).
- **No clock, no shots, no coordinates.** CHL exposes far less live detail than the
  domestic leagues. For Swedish/Finnish clubs' CHL games, SHL's `gameheader` and
  Liiga's game lists also carry the same fixtures (SHL keys them by `gameExtId` =
  CHL `externalId`), sometimes with richer data.
- **Logos** are on Cloudinary, keyed by `externalId` (verified:
  `https://res.cloudinary.com/chl-production/image/upload/chl-prod/assets/teams/107.png`
  → PNG; `.svg` → 400). Player portraits under `assets/players/{externalId}`
  (pattern from `pageSettings.cloudinary` + `getTeamLogo` in the bundle; unverified
  for players).
- **Property lists** instead of fields for all stats: match by `shortName`, and note
  `units` (`percents`, `sec`, `cm`, `kg`).
- **Country codes are 3-letter lowercase** (`fin`, `ger`, `swe`).
- Season history back to 2014/15 is available with the season IDs above.

## Out of scope

- `widget.championshockeyleague.com/*` and `api.chl.hockey/*` — hosts do not exist.
- `/api/sport`, `/api/sport/live`, `/api/sport/live/events` — declared in the
  bootstrap config but return `404 CmsNotFoundError`.
- `/api/cards`, `/api/page/content` — CMS content, not sport data.
- `chl.hokejovyzapis.cz/visualization/?match=…` — third-party shot visualisation
  embed referenced by the bundle; not investigated.

## Core model mapping

| Core concept | Source file | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | constants | competition id, season id table | No season list endpoint |
| Game (id, teams, start time) | `live-events.json` / `schedule-*` | `_entityId`, `startDate`, `teams`, `venue`, `stage` | |
| GameState | `live-event-*-scoreboard` | `status`, `state`, `periods[].status` | Live values unverified |
| Score by period | `scoreboard.results.periods[].scores` | | |
| Clock / period | `periods[]` | period name + status | **Gap:** no clock at all |
| GameEvent | `scoreboard.periods[].actions[]` | goals (scorer + assists), penalties (player only), goalie changes, timeouts, SO attempts | No shots/coords; penalty type only as text in `actions.json` |
| Lineups | `live-event-*-lineups` | `position.category/index` | Full lines + pairs + officials + coaches ✔ |
| Team | `teams-*` | `_entityId`, `shortName`, `country` | |
| Player | `player-*` | `info.properties`, `nationality` | |
| Standings | `standings-groups-*` | `teams[].stats` | Single table; playoff bracket separate |
| Shootout | `scoreboard` `Shootout` period | `shot-penalty-miss`, `goal`, `goal-penalty` | Per-attempt with shooter in `players[]` ✔ |

## TODO

- [ ] Capture live samples: `live-events.json`, `live-event-*-scoreboard.json` mid-game
      — CHL games run most weeks Sep–Nov; e.g. **2026-09-11 16:00Z**
      (`282628c3708fc7d1ce2d537d`) and 17:00Z.
- [ ] Confirm live `status` / `state` / period `status` values.
- [ ] Verify Cloudinary player-portrait URL pattern.
- [ ] Look at `statistic-players-advanced` (xG data).

## Changelog

| Date | Change |
|---|---|
| 2026-09-11 | Initial mapping. Corebine S3 file catalogue recovered from page module configs; 25 file types verified, 33 samples captured. Unverified note's hosts confirmed non-existent. |
