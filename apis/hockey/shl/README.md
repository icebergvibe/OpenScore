# SHL API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | Sweden |
| **Official site** | https://www.shl.se |
| **Base URL** | `https://www.shl.se/api/` (REST) · `https://game-broadcaster.s8y.se/live/game` (live SSE) |
| **Auth** | None — public endpoints used by the shl.se web app |
| **Format** | JSON. Errors are JSON (`{"message","error","statusCode"}`) |
| **CORS** | **No** usable CORS on `www.shl.se/api` (only `Access-Control-Allow-Credentials`, never `Allow-Origin`). The SSE host sends `Access-Control-Allow-Origin: *`. |
| **WAF / UA requirement** | None. Cloudflare + Varnish in front; no `User-Agent` needed. |
| **Last full verification** | 2026-09-11 |
| **Status** | ✅ verified (pre-game + final states) · 🚧 live-state / SSE samples not yet captured (SHL opens 2026-09-19) |

## Overview

shl.se runs on **Sportality** (s8y.se), a white-label sports-site platform. The same
platform powers HockeyAllsvenskan and the individual SHL club sites; this document is
the reference for both — [HockeyAllsvenskan](../hockeyallsvenskan/README.md) is
documented as a delta (verified 2026-09-11: identical response shapes). Stats are provided by **Statnet** (`provider: "statnet"` in responses).

The API has three families:

| Family | Prefix | Purpose |
|---|---|---|
| Sports | `/api/sports-v2/…` | Schedule, game info, teams, athletes — the "reference" data |
| Game day | `/api/gameday/…` | Boxscore, play-by-play, team/player stats for one game |
| Statistics | `/api/statistics-v2/…` | Standings, leaderboards, stat tables, player profiles |

plus a **Server-Sent Events** stream for live games (see [Live updates](#live-updates)).

The route list was recovered from the site bundle (`www.shl.se/assets/index-*.js`, a
`ROUTES` constant map plus its call sites); every route below was then called for
real. Samples captured **2026-09-11**, eight days before the 2026–27 SHL season, so
finished games come from 2025–26 and pre-game samples from 2026–27. shl.se also
tracks the Swedish clubs' **CHL** games, which appear in the same feeds under
`seriesCode: "CHL"`.

The unverified notes in this repo were wrong about the routes but right about the
IDs: `qa98unlbd6` is the SHL 2026–27 **ssgtUuid** and `p2qoh7wot5` a **game** UUID.

## Identifiers

Everything is keyed by short opaque UUIDs (10-char base-36 or `xxxx-xxxxXXXXX` forms).

| ID | Format | Example | How to obtain |
|---|---|---|---|
| **ssgtUuid** (season + series + game type) | 10-char | `qa98unlbd6` (SHL 2026–27), `iuzqg7dqk9` (SHL 2025–26), `aigy31hpwc` (CHL 2026–27) | `season-series-game-types-filter` → `ssgtUuid`; also on every game object. **The key you need for schedules, standings and stats.** |
| Season | 10-char / `qXX-…` | `ndcf81nlb3` = 2026/2027 | `season-series-game-types-filter` → `season[]` (has `code: "2026"`) |
| Series | `qQ9-…` | `qQ9-bb0bzEWUk` = SHL, `qQ9-9397drVEh` = CHL | same → `series[]` |
| Game type | `qQ9-…` | `qQ9-af37Ti40B` (regular season) | same → `defaultSsgtFilter.gameType`; `gameType[]` list was empty |
| Game | 10-char or `qQ1-…` | `p2qoh7wot5`, `bdhvuc5tex`, `qQ1-5b5lU7Fqa` | `gameheader`, `game-schedule` → `uuid` |
| Team | `xxxx-xxxxXXXXX` | `50e6-50e6DYeWM` (Skellefteå) | `all-teams`, `game-schedule.teamList`, team objects in games |
| Team code | 3–4 letters | `SKE`, `FHC`, `HV71`, `ÖRE` | team `names.code`. Note `SAIK` vs `SKE`, `OHK` vs `ÖRE` — two code systems (see quirks) |
| Instance id | `xxx1_xxx` | `saik1_saik` | `ownerInstanceId`; identifies the club's Sportality site; used in logo URLs |
| Athlete | 10-char or `qQ9-…` | `acnem5beey` | `athletes/by-team-uuid` → `players[].uuid` |
| Statnet player id | numeric string | `"6433"` | `play-by-play` `player.playerId`, `boxscore` keys — **different from athlete UUID** |
| Game ext id | numeric string | `"22003"` | `game-info.gameInfo.extId`, `play-by-play.gameId` |

**SHL teams 2026–27 (14), `all-teams.teamCode` / `teamNames.code`:** BIF Brynäs,
DIF Djurgården, FBK Färjestad, FHC Frölunda, HV71, IFB Björklöven, LHC Linköping,
LHF Luleå, MIF Malmö, OHK/ÖRE Örebro, RBK Rögle, SAIK/SKE Skellefteå, TIK Timrå,
VLH Växjö ([`samples/all-teams.json`](samples/all-teams.json)).

## Discovery path

1. **Bootstrap once per season:** `GET /api/sports-v2/season-series-game-types-filter?series=shl`
   → `ssgtUuid` (current), `defaultSsgtFilter{season, series, gameType}`, and the lists
   of seasons/series to build other ssgtUuids.
2. **Today / this week:** `GET /api/gameday/gameheader` (no parameters) → games grouped
   by date, ~10 days around today, all series (SHL + CHL). This is the scoreboard feed.
3. **Full schedule:** `GET /api/sports-v2/game-schedule?seasonUuid=…&seriesUuid=…&gameTypeUuid=…&gamePlace=all&played=all`.
4. **A game:** `GET /api/sports-v2/game-info/{gameUuid}` for header/state, then
   `GET /api/gameday/play-by-play/{gameUuid}` for events and
   `GET /api/gameday/boxscore/{gameUuid}` for lineups + per-player stats.
5. **Live:** open the SSE stream (below) or poll `game-overview` / `play-by-play`.
6. **Standings:** `GET /api/statistics-v2/league-standings?ssgtUuid=…`.
   **Rosters:** `GET /api/sports-v2/athletes/by-team-uuid/{teamUuid}`.

Recommended poll interval: **10 s** on `gameday/game-overview` (tiny). The direct client
keeps its initial `play-by-play` (100+ KB; send `Accept-Encoding: gzip`) and reloads it only
after a score or state transition; use SSE instead once its payload schema is captured.
Responses carry weak `ETag`s and honour `If-None-Match` (→ `304`), so conditional
requests are worthwhile here.

## Endpoints

### `GET /api/gameday/gameheader`

| | |
|---|---|
| **Purpose** | Scoreboard: games from the last couple of days through the next ~10, all series. |
| **Parameters** | none |
| **Sample** | [`samples/gameheader.json`](samples/gameheader.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | no `Cache-Control`; Cloudflare `cf-cache-status: HIT` observed |

Object keyed by date (`"2026-09-19": [...]`). Each game: `uuid, startDateTime (UTC),
date, played, overtime, shootout, ssgtUuid, seriesCode, seriesNames[], venue,
homeTeam{name, code, result, logo}, awayTeam{…}, roundNumber, roundLabel`. `result` is
the score once played, otherwise absent/empty. **No live clock/state here** — use
`game-overview` for that.

### `GET /api/sports-v2/season-series-game-types-filter?series={code}[&season={seasonUuid}]`

| | |
|---|---|
| **Purpose** | The ID bootstrap: seasons, series, game types, current ssgtUuid. |
| **Parameters** | `series` — `shl` (case-insensitive); `season` — optional season UUID (response was identical with or without it) |
| **Sample** | [`samples/season-series-game-types-filter.json`](samples/season-series-game-types-filter.json) |
| **Last verified** | 2026-09-11 |

```
series[]            { uuid, code, names[{language, translation}] }        – CHL, SHL
season[]            { uuid, code "2026", names[…"2026/2027"] }            – back to 1975
gameType[]          []  (empty; the regular-season game type UUID only appears in defaultSsgtFilter)
defaultSsgtFilter   { season, series, gameType }                           – UUIDs for "now"
ssgtUuid            "qa98unlbd6"
```

To get a **previous season's** ssgtUuid, call `game-schedule` with that season's
UUID and read `ssgtUuid` from the response (`xs4m9qupsi` → `iuzqg7dqk9`).

### `GET /api/sports-v2/game-schedule?seasonUuid=…&seriesUuid=…&gameTypeUuid=…&gamePlace=all&played=all`

| | |
|---|---|
| **Purpose** | Every game of a season/series/game-type. |
| **Parameters** | all three UUIDs required (missing → `500`); `gamePlace` — `all` / `home` / `away` (with `teams`); `teams` — optional team UUID filter; `played` — `all` / `played` / `unplayed` (values from the bundle; only `all` verified) |
| **Samples** | [`game-schedule.json`](samples/game-schedule.json) (2026–27, **truncated** 8 of 364) · [`game-schedule.played.json`](samples/game-schedule.played.json) (2025–26, **truncated**, incl. OT + SO games) |
| **Last verified** | 2026-09-11 |

```
gameInfo[]   { uuid, rawStartDateTime (UTC), startDateTime ("2026-09-19 15:15:00" LOCAL, no zone),
               state pre-game|live|post-game, overtime, shootout, ssgtUuid,
               homeTeamInfo/awayTeamInfo { status N/A|WIN|LOSE, uuid, ownerInstanceId, code, names{…}, score (int or "N/A"), icon },
               venueInfo{uuid, name}, seriesInfo{uuid, code, displayName}, roundNumber, roundLabel }
teamList[]   full team objects (same as all-teams)
ssgtUuid
```

430 KB per season — gzip and cache.

### `GET /api/sports-v2/game-info/{gameUuid}`

| | |
|---|---|
| **Purpose** | Game header: teams, venue, state, series; the canonical "what game is this". |
| **Samples** | [`game-info.pre.json`](samples/game-info.pre.json) · [`game-info.final.json`](samples/game-info.final.json) · [`game-info.chl-final.json`](samples/game-info.chl-final.json) · [`game-info.not-found.json`](samples/game-info.not-found.json) |
| **Last verified** | 2026-09-11 |

```
gameInfo   { gameUuid, extId, startDateTime (UTC), arenaName, state pre_game|post_game (underscore here!),
             overtime, shootout, seriesCode, seriesName, roundNumber, roundLabel, seriesDisplayName }
homeTeam / awayTeam { names{code, short, long, full, codeSite…}, uuid, instanceId, foundedOn, address, email, icon, score ("" before game) }
ssgtUuid, seriesUuid, instanceContext{…}   – ignore instanceContext (site-relative flags)
```

**Unknown UUID → `200` with all-empty strings** (`gameUuid: ""`). Treat empty
`gameInfo.gameUuid` as not-found.

### `GET /api/gameday/play-by-play/{gameUuid}`

| | |
|---|---|
| **Purpose** | Every event of a game, newest first, with rink coordinates. |
| **Samples** | [`play-by-play.final.json`](samples/play-by-play.final.json) (131 events) · [`play-by-play.final-shootout.json`](samples/play-by-play.final-shootout.json) (OT + SO) |
| **Last verified** | 2026-09-11 |
| **Cache** | `ETag` honoured |

Bare array, **sorted by `eventId` descending** (latest event first). Empty array (`0`
bytes body, not `[]`!) for games that have not started. Every event repeats a big
header (`gameSourceId "20250913-FHC-LHC", gameId, round, gameType "Elitserien",
arena, attendance, startDateAndTime (LOCAL, no zone), gameState, revision,
realWorldTime, updatedTime, homeTeam{teamId, teamName, teamCode, score},
awayTeam{…}`) — the score in the header is the **score at the time of the event**.

Per-event fields:

| `type` | Fields |
|---|---|
| `goal` | `period, time "MM:SS" (elapsed), eventTeam{teamId, place home/away, teamCode, teamName}, player{playerId, firstName, familyName, jerseyToday, statistics[G,A]}, assists{first, second}, locationX, locationY, goalSection, homeGoals, awayGoals, goalStatus EQ/PP1/PP2/SH1…, isPenaltyShot, isEmptyNetGoal, pop[] (players on ice, plus), nep[] (minus)` |
| `shot` | `period, time, eventTeam, player, locationX, locationY, goalSection, isPenaltyShot` — shots on goal only (blocked/missed not present) |
| `penalty` | `period, time, eventTeam, player, offence (code), variant{shortName Minor/Bench/Major…, description "2 min", minorTime…}, didRenderInPenaltyShot` |
| `goalkeeper` | `period, time, eventTeam, player, isEntering` — goalie in/out |
| `timeout` | `period, time, eventTeam` |
| `period` | `period, started, startedAt (UTC), finished, finishedAt (UTC)` — **no eventId/gameState**; use these to detect intermissions |
| `shootout-penalty-shot` | `period 99, time "00:00", eventTeam, player, isGoal, isPenaltyShot` |

**Periods:** 1–3 regulation, 4 = OT, **99 = shootout** (not 5). **`time` counts up**
within the period (`"19:59"` is late in the period).

**Observed `offence` codes:** `TRIP`, `HOOK`, `HOLD`, `INTRF`, `CROSS`, `ROUGH`, `BOARD`,
`TOO-M` (too many men), `P-SHOT` (penalty shot awarded). **`goalStatus`:** `EQ`, `PP1`
observed; `PP2`, `SH1`, `SH2`, `EN`, `PS` presumed.

**Coordinates:** `locationX` ∈ [−29, 481], `locationY` ∈ [−140, 135] observed for shots.
Looks like x along the length in some unit where ~0 is the defending goal line and
~480 the far end; not calibrated. `goalSection` is 1–9 for the net grid (−1/−3 = wide?).

**`gameState`** on events (from the bundle enum `NotStarted | Ongoing | PeriodBreak |
GameEnded`); only `GameEnded` observed so far.

### `GET /api/gameday/boxscore/{gameUuid}` &nbsp;·&nbsp; `GET /api/gameday/player-stats/{gameUuid}`

| | |
|---|---|
| **Purpose** | Per-player game stats with line numbers and positions — the **lineup** source. `player-stats` is the same data with more (hidden) columns. |
| **Samples** | [`boxscore.final.json`](samples/boxscore.final.json) · [`player-stats.final.json`](samples/player-stats.final.json) |
| **Last verified** | 2026-09-11 |

```
dataColumns[]    { name, type, highlighted, group }     – NR, Player, LINE, POS, G, A, SOG, SW, PIM, PPG, PPSOG, +/-, FOW, FOL, FOPerc, TOI, Hits
players          { homeTeamValue: { "<playerId>": {fullName, firstName, lastName, extIds[]} }, awayTeamValue }
stats            { homeTeamValue: [ { NR, POS, LINE, G, A, …, TOI "MM:SS", info{playerId, teamId, team} } ], awayTeamValue }
goalkeepers, gkStats, gkDataColumns   – same pattern for goalies (GA, SOGA, SPGA, SVS, SVS%)
provider         "statnet"
```

`POS` values: `LW`, `CE`, `RW`, `LD`, `RD`, `GK`. `LINE` 1–4 (goalies 1–2). Players
are keyed by the Statnet numeric id (string), not the athlete UUID. **Empty body (0
bytes)** before the game.

### `GET /api/gameday/game-overview/{gameUuid}`

Verified 2026-09-11 · [`samples/game-overview.final.json`](samples/game-overview.final.json).
The lightest live-state call: `{gameUuid, homeTeam{teamCode…}, awayTeam, homeGoals,
awayGoals, state NotStarted|Ongoing|PeriodBreak|GameEnded, time{period, periodTime
"MM:SS"}}`. Empty body before the game. **Poll this**, not play-by-play, to detect
changes.

### `GET /api/gameday/team-stats/{gameUuid}` &nbsp;·&nbsp; `GET /api/gameday/game-info/{gameUuid}` &nbsp;·&nbsp; `GET /api/gameday/post-game-data/team-stats/{gameUuid}`

Verified 2026-09-11 · samples [`team-stats.final.json`](samples/team-stats.final.json),
[`gameday-game-info.final.json`](samples/gameday-game-info.final.json),
[`post-game-team-stats.final.json`](samples/post-game-team-stats.final.json).

- `team-stats`: `{home, away}` each with `statistics[]` per `period` (0 = total) of
  `parsedTotalStatistics[{key, value}]` — keys `G, PIM, FOW, SOG, SPG, PPSOG, Saves,
  GA, PP_perc, SH_perc, PPG, SHG, PPGA, SHGA, NumPP, NumSH, Hits, BkS, SiBk`. This is
  the **score-by-period** source (`G` per period).
- `gameday/game-info`: `teamHead2Head[]` comparison rows + `bestScorers[]`. Empty
  lists before the game.
- `post-game-data/team-stats`: same comparison, different wrapper.

### `GET /api/statistics-v2/league-standings?ssgtUuid={ssgt}[&teamUuid=…]`

| | |
|---|---|
| **Purpose** | Standings table. |
| **Samples** | [`league-standings.json`](samples/league-standings.json) (2025–26 final) · [`league-standings.empty.json`](samples/league-standings.empty.json) (2026–27 before opening) |
| **Last verified** | 2026-09-11 |

`{dataColumn[], groupings[], leagueStandings[], provider}`. Row: `Rank, GP, W, OTW,
L, OTL, G, GA, Diff, Points, info{code, teamId (Statnet code e.g. SAIK), teamInfo{
teamUuid, teamOwnerInstanceId, teamMedia, teamNames{code SKE, short, long, full…},
clubPageLink}}`. `Team: 0` is a placeholder — the team is in `info`. Before the
season starts: `{"leagueStandings": []}`.

### `GET /api/sports-v2/all-teams/{ssgtUuid}` &nbsp;·&nbsp; `GET /api/sports-v2/all-sites/{ssgtUuid}` &nbsp;·&nbsp; `GET /api/sports-v2/teams/{teamUuid}`

Verified 2026-09-11 · [`all-teams.json`](samples/all-teams.json) ·
[`all-sites.json`](samples/all-sites.json) · [`team.json`](samples/team.json).

- `all-teams`: array of `{uuid, teamCode, ownerInstanceId, teamNames{code, short,
  long, full, …}, clubName, displayName, icon, logo, teamMedia, socialMedia, series[],
  sport[], extIds[]}` — 14 SHL teams.
- `all-sites`: `[{id, instanceId, publicUrl, name, icon}]` — club websites.
- `teams/{uuid}`: `{names, info{founded, golds, finals, address, phone, email,
  chairman, manager, facebook, instagram, …}}`.

### `GET /api/sports-v2/athletes/by-team-uuid/{teamUuid}`

| | |
|---|---|
| **Purpose** | Current roster grouped by position. |
| **Sample** | [`samples/athletes-by-team.json`](samples/athletes-by-team.json) (**truncated**: 2 players per group, portrait `srcset`s removed — the real response is 400 KB, mostly imgix `srcset` strings) |
| **Last verified** | 2026-09-11 |

`[{position "Målvakter"|"Backar"|"Forwards", positionCode GK|D|F, players[{uuid,
firstName, lastName, fullName, nationality "FI", jerseyNumber, gender, portraitList[],
renderedLatestPortrait{url, srcset…}}]}]`. No bio data — use the profile endpoints.
Query-param form (`?teamUuid=`) → `404`; path form only.

### `GET /api/sports-v2/athlete-details/{athleteUuid}` &nbsp;·&nbsp; `GET /api/statistics-v2/athlete/profile-page?playerUuid={uuid}&masterSiteInstanceId=` &nbsp;·&nbsp; `…/athlete/profile?ssgtUuid=…&playerUuid=…&statisticsSubset=` &nbsp;·&nbsp; `…/athlete/seasonList?playerUuid=…` &nbsp;·&nbsp; `…/athlete/playerProfile_gameLog?playerUuid=…`

Verified 2026-09-11 · samples [`athlete-details.json`](samples/athlete-details.json),
[`athlete-profile-page.json`](samples/athlete-profile-page.json),
[`athlete-profile.json`](samples/athlete-profile.json),
[`athlete-season-list.json`](samples/athlete-season-list.json),
[`athlete-game-log.json`](samples/athlete-game-log.json).

- `athlete-details`: raw platform record — `athleteData{uuid, firstName, lastName,
  dateOfBirth, height, weight, nationality, playerExtIds[{extId "6607", extIdType.code
  "isa"…}]}`. **`playerExtIds` is the bridge between athlete UUID and the Statnet
  numeric id** used in play-by-play/boxscore.
- `profile-page`: bio + `team{}` + season/career stat blocks (`age{value,format}`,
  `weight{value,format}`, `height{…}`, `position "Målvakt"`, `positionCode GK`,
  `shoots`). `masterSiteInstanceId` may be empty.
- `profile`: shorter version keyed to one `ssgtUuid`.
- `playerProfile_gameLog`: `[{dataColumns[], stats[]…}]` season-by-season table.

The query-param forms are required; path forms (`/profile/{uuid}`) → `404`.

### `GET /api/statistics-v2/stats-info/{module}?ssgtUuid=…&count=50[&teamUuid=…]` &nbsp;·&nbsp; `GET /api/statistics-v2/featured/athlete/leaderboard?ssgtUuid=…&module=…&count=N`

Verified 2026-09-11 · samples [`stats-info.players_summary.json`](samples/stats-info.players_summary.json),
[`stats-info.goalkeepers_summary.json`](samples/stats-info.goalkeepers_summary.json),
[`leaderboard.json`](samples/leaderboard.json).

`module` = `players_summary`, `goalkeepers_summary` verified; `teams_summary` → `500`.
`stats-info` returns `[{dataColumns[], defaultSortKey, stats[{Rank, GP, TP, G, A, PIM,
GWG, PPG, SOG, Hits, BkS, Plus, Minus, PlusMinus, TOI_GP, TPPG, info{uuid, fullName,
team…}}], players{}, teams{}, totalCount}]`. `leaderboard` returns full athlete
profile objects with `seasonStats`/`careerStats` for the top N.

### Other verified endpoints

| Endpoint | Sample | Notes |
|---|---|---|
| `GET /api/sports-v2/today-games?seasonSeriesGameType={ssgt}&pageSize=10` | [today-games.json](samples/today-games.json) | Today's games for one ssgt; empty body if none |
| `GET /api/sports-v2/upcoming-games/{teamUuid}?gamePlace=` | [upcoming-games.json](samples/upcoming-games.json) | `{upcomingGames[]}` for a team |
| `GET /api/sports-v2/upcoming-games?pageSize=10&series[]={seriesUuid}` | [upcoming-games-by-series.json](samples/upcoming-games-by-series.json) | league-wide |
| `GET /api/sports-v2/played-games/{teamUuid}` | [played-games.json](samples/played-games.json) | `{playedGames[]}` recent results (has a localised `date "10 sep 26"`) |
| `GET /api/sports-v2/staffs?teamUuid={uuid}` | [staffs.json](samples/staffs.json) (truncated) | Coaches/staff; query-param form only |
| `GET /api/statistics-v2/game-center/team-h2h?ssgtUuid=…&homeTeamUuid=…&awayTeamUuid=…` | [team-h2h.json](samples/team-h2h.json) | Season head-to-head comparison |
| `GET /api/statistics-v2/team-page/stats-header?teamUuid=…` | [team-stats-header.json](samples/team-stats-header.json) | small |
| `GET /api/sports-v2/ssgt/{ssgt}/provider` | — | Returns the bare string `statnet` (`text/html`) |
| `GET /api/sports-v2/upcoming-live-games` | — | `[{gameUuid, gameExtId}]` of games live/soon |

### Live updates

The site does **not poll** for live games; it opens an `EventSource` to

```
https://game-broadcaster.s8y.se/live/game?gameUuid={gameUuid}
```

Verified 2026-09-11: responds `200 text/event-stream` with
`Access-Control-Allow-Origin: *` and `Cache-Control: no-store`; stays open and
**silent for games that are not live** (no initial snapshot). From the bundle, the
client state it feeds is `gameData{gameId, gameTime, gameState, period,
homeTeamScore, awayTeamScore, statusString}`, `dynamicPeriods[]`, `playByPlay`,
`teamStats`, `goalScorers`, `insights` — so expect events carrying those. Without
`gameUuid` the stream is a firehose for all games. **Event names and payload shapes
are not yet captured** (see TODO). The bundle also references an internal
`site-service-cached.frontend.svc.cluster.local` variant — ignore.

### Broken / unstable

| Endpoint | Observed |
|---|---|
| `GET /api/sports-v2/latest-ssgt/{series}` | `500` for `shl` |
| `GET /api/gameday/periodstats/{uuid}` | `500` for every game tried |
| `GET /api/gameday/play-by-play/initial-events/{uuid}` | `500` |
| `GET /api/gameday/team-standings/{uuid}` | empty body always |
| `GET /api/sports-v2/game-series/by-ssgt?ssgtUuid=…&round=1&ownGames=false` | `[]` (season not started; re-test) |
| `GET /api/statistics-v2/stats-info/teams_summary?…` | `500` |
| `GET /api/gameday/post-game-data/promo-bar-stats/{uuid}` | `404` |

## Game states

Three different vocabularies, depending on the endpoint:

| Endpoint | Values | Core `GameState` |
|---|---|---|
| `game-schedule`, `gameheader`-adjacent, `today-games`, `upcoming-games` (`state`) | `pre-game`, `live`, `post-game`; bundle also has `canceled` | SCHEDULED / LIVE / FINAL / CANCELLED |
| `sports-v2/game-info` (`gameInfo.state`) | `pre_game`, `post_game` (underscores; live value presumably `live`) | same |
| `game-overview.state`, play-by-play `gameState` | `NotStarted`, `Ongoing`, `PeriodBreak`, `GameEnded` | SCHEDULED / LIVE / INTERMISSION / FINAL |

`overtime` / `shootout` booleans on the game object give REG/OT/SO. `gameheader` uses
`played: true/false` only.

Before the game: `play-by-play`, `boxscore`, `game-overview`, `team-stats` all return
an **empty body** (`Content-Length: 0`, `200`), not JSON. Handle that explicitly.

## Quirks & gotchas

- **Empty body ≠ empty JSON.** Several gameday endpoints return `200` with zero bytes
  for games that have not started. A strict JSON parser will throw.
- **Two time formats.** `rawStartDateTime`/`startDateTime` on `game-info`, `gameheader`
  are UTC ISO-8601 (`Z`). `startDateTime` on `game-schedule` and `startDateAndTime` on
  play-by-play events are **local Swedish time with no zone** (`"2026-09-19 15:15:00"`).
  Prefer the `raw*`/UTC fields.
- **Two team-code systems.** Display codes (`SKE`, `ÖRE`) in `teamNames.code` /
  `names.code`; Statnet codes (`SAIK`, `OHK`) in `all-teams.teamCode`, play-by-play
  `teamId`, standings `info.teamId` and `played-games` `code`. Map via team UUID, not code.
- **Two player-id systems.** Athlete UUID (`acnem5beey`) in sports/statistics
  endpoints; Statnet numeric id (`"6433"`) in gameday endpoints. Bridge:
  `athlete-details.athleteData.playerExtIds[].extId`.
- **Play-by-play is newest-first** and every event repeats the game header — 100+ KB
  per game. Use gzip; use `game-overview` to decide when to refetch.
- **Shootout is period 99.** OT is 4.
- **Not-found is `200`** with empty strings (`game-info`) or empty body (gameday).
  Unknown *routes* are a proper `404 {"message":"Cannot GET …"}`.
- **No CORS on the REST API**; the SSE host allows `*`. A web app needs a proxy for
  REST but can consume SSE directly.
- **`ETag` + `If-None-Match` work** (`304`). No `Cache-Control` at all on most
  responses; Varnish (`x-varnish`) and Cloudflare cache in front.
- **Swedish strings** in positions (`Målvakter`, `Backar`), penalty descriptions and
  localized dates (`"10 sep 26"`). Team `names` come in 8 variants (`code`, `short`,
  `long`, `full`, and `*Site` versions) — use `short` for display, `code` for badges.
- **Multi-series feeds.** `gameheader`, `upcoming-live-games` etc. mix SHL and CHL
  games; filter on `seriesCode` / `ssgtUuid`.
- **UUID formats vary** (`qa98unlbd6`, `qQ1-5b5lU7Fqa`, `50e6-50e6DYeWM`); treat all as
  opaque strings.

## Out of scope

- The `/api/v1/…` paths from the unverified notes — `404 Cannot GET`.
- Ticketing (`game-info/{uuid}/ticket`), articles, feeds, shop, `oauth-agent/*`
  (that's the site's own login, not needed for data).
- `playerProfile_seasonGameLog`, `stats-and-upcoming-game`, `game-form`,
  `single-game-form`, `overview-team-stats`, `player-h2h(-v2)`, `layout-info` (1.8 MB
  UI config), `game-metadata/*` — exist but not needed; unsampled.

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `season-series-game-types-filter` | `season[]`, `series[]`, `ssgtUuid` | Stage = game type UUID; only regular season seen so far |
| Game (id, teams, start time) | `gameheader` / `game-schedule` / `game-info` | `uuid`, `rawStartDateTime`, `homeTeamInfo`, `awayTeamInfo`, `venueInfo` | |
| GameState | `game-overview.state` (live) / `game-schedule.state` | see table | Intermission is explicit (`PeriodBreak`) |
| Score by period | `gameday/team-stats` | `statistics[period].parsedTotalStatistics[G]` | or derive from goal events |
| Clock / period | `game-overview.time` | `period`, `periodTime` (elapsed) | No `running` flag — **gap**; SSE may provide |
| GameEvent | `play-by-play` | `goal`, `shot`, `penalty`, `goalkeeper`, `timeout`, `period`, `shootout-penalty-shot` | Coordinates on goals/shots; no blocked/missed shots, hits or faceoffs |
| Lineups | `boxscore.stats` | `LINE`, `POS`, `NR` | Good: lines + positions. Scratches not exposed |
| Team | `all-teams` | `uuid`, `teamCode`, `teamNames`, `icon` | |
| Player | `athletes/by-team-uuid` + `athlete/profile-page` | | Two id systems (see quirks) |
| Standings | `league-standings` | rows | Flat single table |
| Shootout | `play-by-play` | `shootout-penalty-shot` events, period 99 | Full attempt list ✔ |

## TODO

- [ ] Capture live samples: `game-overview` (Ongoing/PeriodBreak), `play-by-play`
      mid-game, and **SSE events** (`curl -N …/live/game?gameUuid=…`) — first chance
      **2026-09-11 17:00Z** (CHL: TAP@VLH `qQ1-5b5lthEyb`, RBS@RBK `qQ1-5b5lM018m`),
      then SHL opening day **2026-09-19**.
- [ ] Confirm `live` value of `game-info.gameInfo.state` and `game-schedule.state`.
- [ ] Get the playoff / play-in game type UUIDs once those schedules exist.
- [ ] Re-test `latest-ssgt`, `periodstats`, `initial-events`, `game-series/by-ssgt`.
- [ ] Calibrate shot coordinates.
- [x] Verify this document against HockeyAllsvenskan (same platform) — done 2026-09-11, no shape differences.

## Changelog

| Date | Change |
|---|---|
| 2026-09-11 | Initial mapping. Routes recovered from the shl.se bundle; 30+ endpoints verified, 38 samples captured. SSE live channel identified. |
