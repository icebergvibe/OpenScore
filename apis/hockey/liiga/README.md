# Liiga API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | Finland |
| **Official site** | https://liiga.fi |
| **Base URL** | `https://liiga.fi/api/v2/` |
| **Auth** | None — public endpoints used by the liiga.fi single-page app |
| **Format** | JSON (UTF-8). Errors are JSON (`{"message": …}` / `{"error": …}` / bare string) |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *` on every v2 response |
| **WAF / UA requirement** | None. CloudFront in front; requests with no `User-Agent` succeed. |
| **Last full verification** | 2026-09-11 |
| **Status** | ✅ verified (pre-game + final states) · 🚧 live-state samples not yet captured |

## Overview

`liiga.fi/api/v2` is the backend of the liiga.fi web app (a Vite/React SPA). It is an
AWS API Gateway + CloudFront deployment: fast, compressed (`gzip` cuts the 1.5 MB
season list to 70 KB — always send `Accept-Encoding: gzip`), with short cache TTLs
on live data (2–5 s) and long ones on reference data (5–60 min).

**`/api/v1/` is dead.** Every v1 path now returns the SPA's `index.html` with `200
text/html`. The unverified notes in this repo described v1 routes; ignore them.

The route list below was recovered from the site's own JS bundles
(`liiga.fi/assets/*.js`, searching for `I.get("/…")` calls), then each route was
called for real. Samples captured **2026-09-11**, 10 days into the 2026–27 regular
season (`season=2027`), so finished games are plentiful but no game was live.

History goes back to 1975 (`?season=1976` works), which makes this API unusually
good for historical data.

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Season | int, **the ending year** | `2027` = 2026–27 | `game.season`; the current one is whatever `/games?tournament=runkosarja` returns without `season` |
| Tournament | lowercase slug | `runkosarja`, `playoffs`, `valmistavat_ottelut`, `playout`, `qualifications`, `chl`, `all` | fixed list (see below) |
| Game | int, 7 digits for current seasons (`27` prefix = season 2027) | `2701274`; older seasons use short ints (`55080`) | `games[].id` |
| Team | string `"<numericId>:<slug>"` | `362185137:tappara` | `homeTeam.teamId`; `/teams/info` keys. Some endpoints use only the numeric half (`shootingTeamId: 55786244`) |
| Player | int (`fihaId`, Finnish federation ID, stable for a career) | `60860218` | `homeTeamPlayers[].id`, `goalEvents[].scorerPlayerId` |
| Official | int `officialID` | `28795517` | `game.referees[]` |
| Date | `YYYY-MM-DD` | `2026-09-01` | — |

**Tournament / `serie` values.** Query parameter → `serie` value in responses:

| `tournament=` | `serie` | Meaning |
|---|---|---|
| `runkosarja` | `RUNKOSARJA` | Regular season |
| `playoffs` | `PLAYOFFS` | Playoffs (`playOffPhase` 1–5, `playOffReqWins` 1/3/4) |
| `valmistavat_ottelut` | `PRACTICE`, `PITSITURNAUS` | Pre-season friendlies and the Pitsiturnaus tournament |
| `playout` | — | Relegation round (empty in 2026–27) |
| `qualifications` | — | Qualification games (empty in 2026–27) |
| `chl` | — | Champions Hockey League games (in player game logs) |
| `all` | any | Accepted by `/games?…&date=` and returns all series |

**Team IDs (2026–27, 16 teams):** `168761288:hifk`, `219244634:jyp`, `238306801:jokerit`,
`292293444:jukurit`, `362185137:tappara`, `461765763:kookoo`, `495643563:kärpät`,
`55786244:hpk`, `624554857:lukko`, `626537494:sport`, `651304385:tps`, `679171680:ässät`,
`859884935:kalpa`, `875886777:pelicans`, `933686567:saipa`, `951626834:ilves`
(`1368624751:k-espoo` appears in pre-season games only). Note the non-ASCII slugs —
URL-encode them (`%C3%A4`).

## Discovery path

1. **Today's games:** `GET /games?tournament=all&date=YYYY-MM-DD` →
   `{games[], previousGameDate, nextGameDate}`. Each game has `id`, `season`, `start`
   (UTC), `started`, `ended`, `finishedType`, `currentPeriod`, `gameTime`, both teams'
   `goals` and `goalEvents`, and `periods[]`. For a scoreboard this is enough.
2. **A specific game:** `GET /games/{season}/{id}` → `{game, awards, homeTeamPlayers,
   awayTeamPlayers}`. `game` adds `penaltyEvents`, `goalKeeperEvents`, `referees`,
   `expectedGoals`; the player arrays are the **lineups** (with `line` numbers).
3. **Shots:** `GET /shotmap/{season}/{id}` → array of every shot attempt with rink
   coordinates and strength.
4. **Standings:** `GET /standings?season={season}`. **Rosters:**
   `GET /players/info?tournament=runkosarja&fromSeason=…&toSeason=…`.
   **Player:** `GET /players/info/{id}`.

Recommended poll interval for live games: **10 s** on `/games/{season}/{id}` and
`/shotmap/…` (edge cache is 2–5 s on live data; the game-detail cache jumps to 300 s
once `ended` is true). Do not poll games where `started` is false or `ended` is true.

## Endpoints

### `GET /games?tournament={t}&date={YYYY-MM-DD}`

| | |
|---|---|
| **Purpose** | All games on one date. The scoreboard feed. |
| **Parameters** | `tournament` — required (`all` accepted); `date` — required |
| **Samples** | [`samples/games-by-date.json`](samples/games-by-date.json) (2026-09-01, 7 finished games) · [`samples/games-by-date.empty.json`](samples/games-by-date.empty.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=5, s-maxage=5, stale-while-revalidate=60` |

**Response shape**

```
games[]                       – game objects, see below
previousGameDate, nextGameDate – nearest dates with games (skips gaps; useful for navigation)
```

**Game object** (shared by every `/games` list endpoint):

```
id, season, serie, gameWeek
start                – ISO-8601 UTC ("2026-09-01T15:30:00Z")
end                  – UNRELIABLE, see quirks
started, ended       – booleans; the primary state flags
finishedType         – ACTIVE_OR_NOT_STARTED | ENDED_DURING_REGULAR_GAME_TIME |
                       ENDED_DURING_EXTENDED_GAME_TIME | ENDED_DURING_WINNING_SHOT_COMPETITION
currentPeriod        – 0 before start; 1–5
gameTime             – seconds of game clock elapsed (3600 = full regulation, 3900 = through OT)
periods[]            – { index, category NORMAL|OVERTIME|WINNING_SHOT_COMPETITION,
                         homeTeamGoals, awayTeamGoals, startTime, endTime (seconds) }
                       ALWAYS 5 entries, even before the game starts and for games that
                       ended in regulation (OT/SO entries are placeholders with 0 goals)
homeTeam / awayTeam  – { teamId, teamName, goals, timeOut (seconds or null), ranking,
                         goalEvents[], powerplayInstances, powerplayGoals,
                         shortHandedInstances, shortHandedGoals, gameStartDateTime (local, +03:00),
                         logos{darkBg, lightBg}, teamPlaceholder }
spectators, iceRink{ id, name, city, latitude, longitude, streetAddress, zip }
playOffPair, playOffPhase, playOffReqWins   – 0 outside playoffs
stale                – always false in samples
```

**Goal event** (`homeTeam.goalEvents[]` / `awayTeam.goalEvents[]`):

```
eventId, period, gameTime (seconds), logTime (UTC wall clock)
scorerPlayerId, scorerPlayer{playerId, firstName, lastName}
assistantPlayerIds[], assistantPlayers[]{playerId, firstName, lastName}
homeTeamScore, awayTeamScore          – score AFTER this goal
goalTypes[]                           – see enum below; [] = even strength
winningGoal                           – true on the game-winning goal
plusPlayerIds, minusPlayerIds         – SPACE-SEPARATED STRING of jersey numbers, e.g. "3 8 55 17 21"
goalsSoFarInSeason, assistsSoFarInSeason{playerId: n}
videoClipUrl, videoClipPlaylistUrl (HLS .m3u8), videoThumbnailUrl
```

**Observed `goalTypes`** (Finnish abbreviations, 2025–27 seasons; *decodes marked ? are
best guesses*):

| Code | Meaning |
|---|---|
| `YV` | Ylivoima — power-play goal |
| `YV2` | Two-man-advantage power-play goal |
| `AV` | Alivoima — shorthanded goal |
| `TM` | Tyhjä maali — empty-net goal |
| `IM` | Ilman maalivahtia — scored while own goalie pulled |
| `VT` | Voittomaali — game-winning goal |
| `VL` | Voittolaukaus — deciding shootout goal |
| `RL` | Rangaistuslaukaus — penalty-shot goal |
| `VT0`, `RL0`, `SR`, `TV` | unknown? (`VT0`/`RL0` possibly "…in regulation"/"…missed"; `SR`, `TV` unresolved) |

Shootout goals appear in `goalEvents` with `period: 5`, `gameTime: 3900` and only the
deciding one is listed (as `VL`); the team `goals` total includes it.

### `GET /games?tournament={t}&season={season}` &nbsp;·&nbsp; `…&week={n}&season={season}`

| | |
|---|---|
| **Purpose** | Every game of a season (or one game week) for a tournament. |
| **Parameters** | `tournament` — required; `season` — optional (defaults to current); `week` — optional game-week number |
| **Samples** | [`samples/games-season.json`](samples/games-season.json) (**truncated**, 12 of 595) · [`samples/games-by-week.json`](samples/games-by-week.json) (**truncated**, 6 of 16) · [`samples/games-playoffs.json`](samples/games-playoffs.json) (2025–26 playoffs, **truncated**, 6 of 60) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=5` |

Bare array of game objects. Without `season` it returns the current season. Without
`tournament` it returns `{games, previousGameDate, nextGameDate}` for regular season.
`start`/`end` **query params are ignored** (the unverified notes were wrong).
**Big:** 1.5 MB uncompressed for a full season — use gzip and cache it.

`tournament=runkosarja&season=2027` returns *only* regular-season games; omitting
`tournament` (or `season=` alone) returns regular season **plus** pre-season
(`PRACTICE`, `PITSITURNAUS`).

### `GET /games/{season}/{id}`

| | |
|---|---|
| **Purpose** | Full game detail: state, events, lineups, officials, awards. **The live-game endpoint.** |
| **Parameters** | `season` — must match the game's season; `id` — game ID |
| **Samples** | [`game.pre.json`](samples/game.pre.json) (2701298, 2026-09-15) · [`game.final.json`](samples/game.final.json) (2701274, regulation) · [`game.final-shootout.json`](samples/game.final-shootout.json) (2701280, OT + SO) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=60` while not ended → `max-age=300, s-maxage=600` once ended |

**Response shape**

```
game                 – game object (above) plus:
  homeTeam/awayTeam.penaltyEvents[]      { eventId, period, gameTime, logTime, playerId, suffererPlayerId,
                                           penaltyMinutes, penaltyFaultType (code), penaltyFaultName (Finnish),
                                           penaltyBegintime, penaltyEndtime (seconds), penaltyInfo }
  homeTeam/awayTeam.goalKeeperEvents[], goalKeeperChanges[]   – [] in all samples; shape unknown
  homeTeam/awayTeam.expectedGoals        – xG, float
  referees[]           { officialID, firstName, lastName, roleAbbrv PT|LT, roleName, pictureUrl }
  buyTicketsUrl, gamblingEvent{…}        – ignore
awards[]             { id, awardCategory, awardName, awardPoint, playerId, teamId }   – "three stars"-style
homeTeamPlayers[] / awayTeamPlayers[]   – THE LINEUP:
  id, teamId, firstName, lastName, jersey, role, roleCode, line (1–4 or null),
  captain, alternateCaptain, rookie, injured, suspended, removed,
  dateOfBirth, placeOfBirth, countryOfBirth, nationality, handedness LEFT|RIGHT, height (cm), weight (kg),
  pictureUrl
```

Before the game: `started: false`, `currentPeriod: 0`, `gameTime: 0`, empty event
arrays, and `referees`, `spectators`, `end`, `gamblingEvent` are **absent**. The player
arrays are already populated (28 players/side in the sample) — the projected lineup.

**`role` / `roleCode`** *(observed)*: `GOALIE`/`MV`, `LEFT_DEFENSEMAN`/`VP`,
`RIGHT_DEFENSEMAN`/`OP`, `DEFENSEMAN`/`P`, `SEVENTH_DEFENSEMAN`/`7. P`,
`EIGHTH_DEFENSEMAN`/`8. P`, `CENTER`/`KH`, `LEFT_WING`/`VL`, `RIGHT_WING`/`OL`,
`STRIKER`/`H` (generic forward), `THIRTEENTH_STRIKER`/`13. H`. Players with
`line: null` are extras/scratches.

**`penaltyFaultType`** *(observed)*: `KOR` Korkea maila (high-sticking), `KAM` Kampitus
(tripping), `KII` Kiinnipitäminen (holding), `M-KII` Mailasta kiinnipitäminen (holding
the stick), `VKV` Väkivaltaisuus (roughing), `POL` Polvitaklaus (kneeing), `JR` Liian
monta pelaajaa jäällä (too many men), `VII` Pelin viivyttäminen (delay of game).
`penaltyFaultName` is always the Finnish name — use it as the display string.

**Unknown game ID** → `200` with `{"homeTeamPlayers": [], "awayTeamPlayers": []}` and no
`game` key. Treat a missing `game` as not-found.

**`?dataType=` variants** of the same URL:

| Variant | Sample | Returns |
|---|---|---|
| `?dataType=playersWithStats` | [`game.players-with-stats.json`](samples/game.players-with-stats.json) | `{homeTeamPlayers[], awayTeamPlayers[]}` where each is `{playerDTO, allTimeStats, seasonStats, seasonStatsInTeam, vsTeamStats}` |
| `?dataType=pastAndFutureGames` | [`game.past-and-future-games.json`](samples/game.past-and-future-games.json) | `{pastGamesBetweenTeams[], futureGamesHomeTeam[], futureGamesAwayTeam[]}` |

### `GET /games/preview/{season}/{id}/?gameDate=…&homeTeam=…&awayTeam=…`

Verified 2026-09-11 · [`samples/game-preview.json`](samples/game-preview.json) ·
`max-age` not set (SWR 60). Returns `{teamComparison, playersToWatch, goaliesToWatch,
homePreviousGames, awayPreviousGames}`. All four query parameters are required
(team IDs in `numeric:slug` form). Pre-game "matchup" data only.

### `GET /shotmap/{season}/{id}`

| | |
|---|---|
| **Purpose** | Every shot attempt with coordinates, outcome and strength. |
| **Samples** | [`shotmap.final.json`](samples/shotmap.final.json) (87 shots) · [`shotmap.final-shootout.json`](samples/shotmap.final-shootout.json) · [`shotmap.pre.json`](samples/shotmap.pre.json) (`[]`) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=2, s-maxage=2` |

Bare array, sorted by `gameTime`:

```
type            – EvenStrengthShot | PowerplayShot | ShorthandedShot
eventType       – GOAL | GOALIE_BLOCKED (save) | PLAYER_BLOCKED | MISSED
period, gameTime (seconds)
shootingTeamId  – NUMERIC team id only (55786244, not "55786244:hpk")
leftTeam, rightTeam – which numeric team defends which end in this period
shooterId, blockerId – player IDs; blockerId is the goalie on GOALIE_BLOCKED/GOAL, the
                  blocking skater on PLAYER_BLOCKED, and (oddly) still set on most MISSED shots
shotX, shotY    – rink coordinates; observed ranges x ∈ [76, 964], y ∈ [9, 502]
ownTeamPlayersOnIce, otherTeamPlayersOnIce – 4/5/6 (goalie counted)
```

Coordinate system: looks like a ~1000 × 500 pixel rink image (origin top-left, x along
the length). Not verified against a real rink; calibrate before drawing.
**No penalty-shot / shootout attempts** in the shotmap — only periods 1–4 observed.

### `GET /standings?season={season}` &nbsp;·&nbsp; `…&tournament={t}`

| | |
|---|---|
| **Purpose** | Standings for all tournaments of a season in one response. |
| **Parameters** | `season` — required; `tournament` — accepted but the response is identical (all tables always returned) |
| **Samples** | [`samples/standings.json`](samples/standings.json) (2027, 3 games in) · [`samples/standings.playoffs.json`](samples/standings.playoffs.json) (2026, with playoff table) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=15` |

```
season[]              – regular-season table, sorted by ranking
playoffs[]            – playoff table (empty until playoffs)
valmistavat_ottelut[] – pre-season table
playout[], qualifications[]
playoffsLines         – e.g. [4, 12, 14]: draw lines after these ranks (direct to playoffs / play-in / playout)
sortByPointsPerGameSeason – bool
```

Row: `teamId, teamName, teamLogos, ranking, games, wins, overtimeWins, losses,
overtimeLosses, ties, points, goals, goalsAgainst, pointsPerGame, winPercentage,
powerPlay*/shortHanded* (instances, time, goals, percentage), penaltyMinutes,
twoMinutePenalties … twentyFiveMinutePenalties, distance, distancePerGame, serie` plus
a `live*` twin of each counting stat (`liveRanking`, `livePoints`, `liveGoals` …)
that includes in-progress games.

### `GET /schedule?tournament={t}&season={season}`

Verified 2026-09-11 · [`samples/schedule.json`](samples/schedule.json) (**truncated**, 8
of 544) · `max-age=30`. A **flat, lightweight** alternative to `/games`: one object per
game with `homeTeamName/Id`, `awayTeamName/Id`, `homeTeamGoals`, `awayTeamGoals`,
`expected*Goals`, `started`, `ended`, `homeTeamWinner`, `finishedType`, `start`,
`iceRink`, `spectators`, rankings — but **no events**. 420 KB vs 1.5 MB for the same
season; prefer this for schedule screens. `serie` here is a numeric ID (`1188`), not
the string.

### `GET /players/info?tournament={t}&fromSeason={s}&toSeason={s}`

| | |
|---|---|
| **Purpose** | Every player registered in the tournament for the season range — the league-wide roster list. |
| **Parameters** | `tournament`, `fromSeason`, `toSeason` — all required (`400 "missing parameter"` otherwise). Optional `nationality`, `team` are sent by the site but **`team` had no effect** in testing (identical response). |
| **Sample** | [`samples/players-info.json`](samples/players-info.json) (**truncated**, 25 of 505) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=1800, s-maxage=3600` |

Bare array of `{id, teamId, teamName, firstName, lastName, jersey, role, roleCode,
captain, alternateCaptain, rookie, injured, suspended, removed, dateOfBirth,
placeOfBirth, countryOfBirth, nationality, handedness, height, weight, pictureUrl}`.
To build a team roster, filter client-side by `teamId`.

### `GET /players/info/{id}` &nbsp;·&nbsp; `GET /players/info/{id}/games/{season}`

| | |
|---|---|
| **Purpose** | Player profile with full career history; per-game log for a season. |
| **Samples** | [`samples/player-info.json`](samples/player-info.json) · [`samples/player-games.json`](samples/player-games.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | profile `max-age=1800`; game log SWR only |

Profile: `fihaId, firstName, lastName, dateOfBirth, birthLocality, nationality,
handedness (L|R here, unlike LEFT|RIGHT elsewhere), height, weight, isSuspended,
isRemoved, activeSeasons[], teams{season: {season, jersey, teamId, slug, teamName,
imageUrl, position}}, teamSeasons{teamId: [seasons]}, teamList{name: id},
historical{regular[], playoffs[], …}` — one stat row per season × tournament, including
non-Liiga leagues (`leagueName`).

Game log: `{qualifications[], practice[], chl[], pitsiturnaus[], regular[], playout[],
playoffs[]}`, each row `{gameId, date, homeTeamName, awayTeamName, goals, assists,
totalPoints, plusMinus, shots, timeOnIce, faceoffs*, penaltyMinutes, saves,
savePercentage, goalsAgainst, goalkeeper, won, tied, lost, …}`.

Percentages are **strings**, not numbers, in both: `"50.00"` in the game log, and in
`historical` rows a mix of `"9.09"` and Finnish-formatted `"0,00"` / `"5,000.00"` —
parse defensively.

### `GET /players/stats/summed/{fromSeason}/{toSeason}/{tournament}/{current}?dataType={type}`

| | |
|---|---|
| **Purpose** | League-wide player stat tables (the site's "Tilastot" pages). |
| **Parameters** | `fromSeason`, `toSeason`; `tournament`; `current` — `true`/`false` (no observed difference); `dataType` — `basicStats`, `goalStats` verified; the bundle also uses `advancedStats`, `faceOffStats`, `penaltyStats`, `playerStats`, `powerplayPenaltykillStats`, `shotStats`, `skatingStats` (unverified). `goalieStats` → `400 Invalid dataType`. |
| **Samples** | [`players-stats-summed.basicStats.json`](samples/players-stats-summed.basicStats.json) · [`players-stats-summed.goalStats.json`](samples/players-stats-summed.goalStats.json) (both **truncated** to 20 of 403 rows) |
| **Last verified** | 2026-09-11 |
| **Cache** | SWR only |

Bare array, one row per player with identity fields (`playerId, teamId, teamName,
teamShortName, role (H/P/MV…), jersey, goalkeeper, rookie, isU20 …`) plus the stat
columns for the `dataType`. Goalies are included (`goalkeeper: true`) with goalie
columns in `basicStats`. 350 KB — gzip.

### `GET /teams/info` &nbsp;·&nbsp; `GET /teams/info?team={teamId}`

Verified 2026-09-11 · [`samples/teams-info.json`](samples/teams-info.json) (**truncated**
to 2 of 16 teams) · [`samples/team-info.json`](samples/team-info.json) (**truncated**, 12
of 596 rows) · `max-age=300`.

- Without `team`: `{teams{ "id:slug": {id, name, short_name, slug, locality, country,
  logo, url, current_venue_capacity, contact_info (HTML), general_info (HTML),
  externals[], teamtournamentstats} }, logos[]}`. The **only source of `short_name`**
  (`HIFK`, `SaiPa`… — not always 3 letters; `teamShortName` in stats rows is the 3-letter form, e.g. `JUK`). 1.5 MB because of embedded HTML and stats — cache for a day.
- With `team`: a bare array of per-season record rows (`season, serieId, games, wins,
  losses, ties, winsInExtraTime, points, regularSeasonRank, playoffRank, home*/away*`)
  back to 1975.

### Broken / unstable

| Endpoint | Observed | Note |
|---|---|---|
| `GET /teams/stats?seasonFrom=…&seasonTo=…&tournament=…[&dataType=…]` | `500 "Remote server error"` / `502` | Used by the site's team-stats page; backend was down during mapping. Re-test. |

## Game states

Liiga has no single state enum; derive it:

| Condition | Core `GameState` |
|---|---|
| `started == false` | `SCHEDULED` (no distinct pre-game state; lineups appear in `homeTeamPlayers` whenever published) |
| `started && !ended` | `LIVE`; `INTERMISSION` if `gameTime` equals a period boundary (`1200`, `2400`, `3600`) — *heuristic, unverified live* |
| `ended == true` | `FINAL`; `finishedType` tells REG / OT / SO |
| — | No observed representation of postponed/cancelled games; a game that never happens presumably stays `started: false`. |

`finishedType` → `gameOutcome`: `ENDED_DURING_REGULAR_GAME_TIME` = REG,
`ENDED_DURING_EXTENDED_GAME_TIME` = OT, `ENDED_DURING_WINNING_SHOT_COMPETITION` = SO.
Pre-season games sometimes end at `gameTime: 1800` (two-period friendlies).

## Quirks & gotchas

- **`end` is wrong.** `end` is frequently *earlier* than `start` (`start
  2026-09-01T15:30Z, end 2026-09-01T12:28Z`) — it appears to be a local time mislabelled
  as UTC, or a different clock entirely. Do not use it; use `ended` + `gameTime`.
- **Two time formats for the same instant.** `start` is UTC (`Z`); `homeTeam.gameStartDateTime`
  is local Finnish time with offset (`+03:00`). Prefer `start`.
- **`periods` always has 5 entries**, including placeholder OT/SO rows for games that
  never went there. Use `currentPeriod`/`gameTime`/`finishedType`, not `periods.length`.
- **`plusPlayerIds` / `minusPlayerIds` are strings of jersey numbers**, space-separated,
  not player IDs — despite the name.
- **Team ID has two forms.** `"55786244:hpk"` in most places, bare `55786244` in
  `/shotmap`, `/players/stats/summed`, `/teams/info?team=` rows and `teamSeasons`. Split
  on `:` and key the core model by the numeric part.
- **Player ID field names vary:** `id` in lineups, `playerId` in stats, `fihaId` in the
  profile — all the same number.
- **Not-found is `200`.** Unknown game → `200` with an empty players object; unknown
  routes → `403 {"message":"Missing Authentication Token"}` (AWS API Gateway's default
  for no matching route — **not** an auth requirement); bad params → `400` with a bare
  JSON string like `"missing parameter"` / `"unsupported parameter"`.
- **No `ETag` / conditional requests.** Rely on `Cache-Control` only.
- **Non-ASCII in URLs** (`kärpät`, `ässät`) must be percent-encoded.
- **Finnish everywhere:** penalty names, award names, standings keys
  (`valmistavat_ottelut`), stat strings with decimal commas. Plan for a translation
  table in the core.
- **Video** (`videoClipUrl`, HLS) and **odds** (`gamblingEvent`) are present on goal
  events/games; link out, don't embed, and ignore odds.
- **CORS is open** (`*`), so a browser web app can call this API directly — the only
  hockey API so far where that is true.

## Out of scope

- All `/api/v1/*` paths — return the SPA HTML.
- `/games/{season}/{id}/lineups`, `/events`, `/pbp`, `/teams/{season}/{slug}`,
  `/players/{id}`, `/players/stats/{season}/{tournament}` — do not exist (`403 Missing
  Authentication Token`). The unverified notes invented them.
- `/gameweeks` → `400 "unsupported parameter"`.

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `/games?tournament=…` | `season` (end year), `serie` | Stage = `serie`; season boundaries not published — infer from first/last `start` |
| Game (id, teams, start time) | `/games?…&date=` | `id`, `season`, `start`, `homeTeam`, `awayTeam`, `iceRink` | `season` is needed alongside `id` for detail calls |
| GameState | game object | `started`, `ended`, `finishedType`, `gameTime` | Derived; no PRE_GAME, no postponed state |
| Score by period | game object | `periods[].homeTeamGoals/awayTeamGoals` | Ignore placeholder periods beyond `currentPeriod` |
| Clock / period | game object | `currentPeriod`, `gameTime` (elapsed seconds) | Counts **up**; no `running` flag — **gap**: cannot tell a stopped clock from a running one |
| GameEvent — goals | game object | `homeTeam/awayTeam.goalEvents[]` | Full: scorer, assists, time, strength via `goalTypes`, video |
| GameEvent — penalties | `/games/{season}/{id}` | `penaltyEvents[]` | Finnish names; start/end times |
| GameEvent — shots | `/shotmap/{season}/{id}` | all rows | Coordinates + strength + outcome; no hits/faceoffs/giveaways |
| Lineups | `/games/{season}/{id}` | `homeTeamPlayers[]`, `awayTeamPlayers[]` | **Better than NHL**: `line` numbers and role codes present |
| Team | `/teams/info` | `teams{}.name/short_name/logo/locality` | Cache heavily (1.5 MB) |
| Player | `/players/info/{id}` | profile | Full career, multi-league |
| Standings | `/standings?season=` | `season[]`, `playoffs[]`, `playoffsLines` | Also `live*` columns |
| Shootout | `/games/{season}/{id}` | `game.winningShotCompetitionEvents[]` (`shotNumber`, `shootingPlayerId`, `blockingPlayerId`, `shootingTeamId`, `goalScored`, `winningGoal`) | Per-attempt ✔ (found while building the core provider; the by-date list only has the deciding goal in `goalEvents` with `period: 5`) |

## TODO

- [ ] Capture a live game (`started && !ended`) on **2026-09-15** — game detail, by-date
      list and shotmap — and verify the intermission heuristic and whether
      `goalKeeperEvents`/`goalKeeperChanges` populate.
- [ ] Decode `goalTypes` `VT0`, `RL0`, `SR`, `TV`.
- [ ] Re-test `/teams/stats` (was 500/502).
- [ ] Verify the remaining `dataType` values on `/players/stats/summed`.
- [ ] Calibrate shotmap coordinates against rink dimensions.

## Changelog

| Date | Change |
|---|---|
| 2026-09-11 | Initial mapping. Routes recovered from the site bundle; 15 endpoints verified, 24 samples captured. v1 confirmed dead. |
| 2026-09-12 | Core provider (`org.openscore.providers.liiga`) built on this doc. Correction: shootout attempts *are* exposed (`game.winningShotCompetitionEvents[]`). 2026–27 has **17** teams (K-Espoo). |
