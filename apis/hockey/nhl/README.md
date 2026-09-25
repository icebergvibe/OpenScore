# NHL API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | USA / Canada |
| **Official site** | https://www.nhl.com |
| **Base URL** | `https://api-web.nhle.com/v1/` |
| **Auth** | None — public endpoints used by nhl.com and the NHL app |
| **Format** | JSON (UTF-8). Errors are **HTML**, not JSON (see quirks) |
| **CORS** | **No** `Access-Control-Allow-Origin` header. Browser apps need a proxy. |
| **WAF / UA requirement** | None. Cloudflare in front, but requests with any or no `User-Agent` succeed. |
| **Last full verification** | 2026-09-11 |
| **Status** | ✅ verified (pre-game + final states) · 🚧 live-state samples pending: no capture has been taken, and the 2026-09-19 preseason opener named here as the first chance was not recorded |

## Overview

`api-web.nhle.com` is the JSON backend behind nhl.com's schedule, scores, game center,
standings and player pages. It replaced the older `statsapi.web.nhl.com` in late 2023
and has been stable since. It is fast, sits behind Cloudflare, needs no key, and
returns short `Cache-Control` max-ages (1–20 s) on almost everything — so "live" is
genuinely live.

There is a second host, `https://api.nhle.com/stats/rest/en/…`, that serves bulk
stats tables (skaters, goalies, teams, shift charts). It also needs no key and is
noted under [Secondary: stats REST API](#secondary-stats-rest-api) but is not the
primary source for OpenScore.

All samples in [`samples/`](samples/) were captured on **2026-09-11** (NHL off-season;
2026–27 preseason starts 2026-09-19, regular season 2026-09-29).

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Season | 8-digit int `YYYYYYYY` (start year + end year) | `20262027` | [`GET /season`](#get-season) lists all; `club-schedule-season` returns `currentSeason` |
| Game | 10-digit int `YYYY TT GGGG` | `2026020001` | Any schedule/score endpoint, field `id` |
| Game type | int | `1` preseason, `2` regular season, `3` playoffs | Digits 5–6 of the game ID, or `gameType` field |
| Team | 3-letter abbreviation (`abbrev`) **or** numeric `id` | `TOR` / `10` | `score`/`schedule` responses carry both. URL paths use the abbreviation. |
| Player | int (stable for a career) | `8478403` | Rosters, play-by-play `rosterSpots`, boxscore |
| Date | `YYYY-MM-DD` | `2026-09-29` | — |

**Game ID structure:** `2026020001` = season start year `2026`, type `02` (regular
season), sequence `0001`. Playoff IDs encode round/series/game in the last four
digits: `2025030416` = 2025–26, playoffs, round `4`, series `1`, game `6`.

**Team abbreviations (2026–27):** ANA BOS BUF CAR CBJ CGY CHI COL DAL DET EDM FLA LAK
MIN MTL NJD NSH NYI NYR OTT PHI PIT SEA SJS STL TBL TOR UTA VAN VGK WPG WSH.

## Discovery path

1. **Today's games:** `GET /score/now` → follows a 307 to `/score/{date}`. Read
   `games[]` — each has `id`, `gameState`, `startTimeUTC`, `homeTeam`, `awayTeam`,
   scores, and `periodDescriptor` + `clock` once live.
2. **A specific game:** take `games[].id` →
   `GET /gamecenter/{id}/play-by-play` for full live state + every event, or
   `GET /gamecenter/{id}/landing` for a lighter scoring/penalty summary.
3. **Lineups / per-player stats:** `GET /gamecenter/{id}/boxscore`. Scratches, coaches
   and officials are in `GET /gamecenter/{id}/right-rail` → `gameInfo`.
4. **Standings:** `GET /standings/now`. **Rosters:** `GET /roster/{abbrev}/current`.
   **Player:** `GET /player/{id}/landing`.

Recommended poll interval for live games: **10 s** for `play-by-play` (server cache is
9 s), **15–20 s** for `score/{date}`. Do not poll games whose `gameState` is `FUT`,
`OFF` or `FINAL`.

## Endpoints

### `GET /score/{date}` &nbsp;·&nbsp; `GET /score/now`

| | |
|---|---|
| **Purpose** | All games on one date with live scores. The primary "scoreboard" feed. |
| **Parameters** | `date` — `YYYY-MM-DD`, or `now` |
| **Samples** | [`samples/score.json`](samples/score.json) (2026-09-29, opening night, all `FUT`) · [`samples/score.final.json`](samples/score.final.json) (2026-06-14, Cup Final G6, `OFF`) · [`samples/score.no-games.json`](samples/score.no-games.json) (2026-09-11, empty day) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=19` |

**Response shape**

```
prevDate, currentDate, nextDate   – navigation; on an empty day these skip to the
                                    nearest dates *with* games (2026-06-14 / 2026-09-19)
gameWeek[]                        – 7 days of {date, dayAbbrev, numberOfGames}
games[]
  id, season, gameType, gameDate, startTimeUTC, easternUTCOffset, venueUTCOffset, venueTimezone
  venue{default}, neutralSite
  gameState, gameScheduleState
  awayTeam / homeTeam { id, abbrev, name{default}, logo, score, sog }   – slimmer than the gamecenter team objects
  periodDescriptor{number, periodType, maxRegulationPeriods}   – present once started
  clock{timeRemaining, secondsRemaining, running, inIntermission} – present once started
  gameOutcome{lastPeriodType}                                   – present when finished
  goals[]                                                       – scoring summary once started
  tvBroadcasts[], gameCenterLink, ticketsLink, teamLeaders[]
oddsPartners[]                    – ignore
```

**Notes**

- `/score/now` returns **307** to the NHL's idea of the current date: during the season
  that is today; in the off-season it points at the next game day (on 2026-09-11 it
  redirected to 2026-09-29, opening night — *not* the first preseason date). Follow
  redirects.
- A date with no games returns `200` with `games: []`, not an error.
- Preseason games (`gameType: 1`) do appear here.

### `GET /schedule/{date}` &nbsp;·&nbsp; `GET /schedule/now`

| | |
|---|---|
| **Purpose** | A week of games starting at `date`, plus season boundary dates. |
| **Parameters** | `date` — `YYYY-MM-DD` or `now` (307 → concrete date) |
| **Sample** | [`samples/schedule.json`](samples/schedule.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=14` |

**Response shape**

```
preSeasonStartDate, regularSeasonStartDate, regularSeasonEndDate, playoffEndDate
nextStartDate, previousStartDate, numberOfGames
gameWeek[] { date, dayAbbrev, numberOfGames, games[] }   – games[] same shape as /score
```

**Notes** — the season-boundary dates make this the best single call for "what phase
of the season are we in". `/schedule-calendar/{date}` ([sample](samples/schedule-calendar.json))
returns the same boundaries plus the team list, without games.

### `GET /club-schedule-season/{team}/{season}` &nbsp;·&nbsp; `GET /club-schedule/{team}/{week|month}/{date|now}`

| | |
|---|---|
| **Purpose** | One team's full-season, week or month schedule with results. |
| **Parameters** | `team` — abbreviation; `season` — `YYYYYYYY` or `now`; `date` — `YYYY-MM-DD` or `now` |
| **Samples** | [`samples/club-schedule-season.json`](samples/club-schedule-season.json) (TOR 2025–26, **truncated** to 16 of 88 games) · [`samples/club-schedule-week.json`](samples/club-schedule-week.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=6` |

**Response shape** — `previousSeason, currentSeason, nextSeason, clubTimezone,
clubUTCOffset, games[]`. Each game is the `/score` shape plus `gameOutcome`,
`winningGoalie`, `winningGoalScorer`, `threeMinRecap`.

### `GET /gamecenter/{id}/play-by-play`

| | |
|---|---|
| **Purpose** | **The** live-game endpoint: state, clock, score, both rosters, and every event with rink coordinates. |
| **Parameters** | `id` — game ID |
| **Samples** | [`gamecenter-play-by-play.pre.json`](samples/gamecenter-play-by-play.pre.json) (future game) · [`gamecenter-play-by-play.final.json`](samples/gamecenter-play-by-play.final.json) (regulation, 343 plays) · [`gamecenter-play-by-play.final-shootout.json`](samples/gamecenter-play-by-play.final-shootout.json) (OT + SO) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=9` |

**Response shape**

```
id, season, gameType, gameDate, startTimeUTC, venue, venueLocation, tvBroadcasts
gameState, gameScheduleState
periodDescriptor{number, periodType, maxRegulationPeriods}
clock{timeRemaining "MM:SS", secondsRemaining, running, inIntermission}
displayPeriod, regPeriods (3), otInUse, shootoutInUse, limitedScoring
gameOutcome{lastPeriodType}            – only when finished
situation{...}                         – only while live (power play / empty net state) – NOT YET CAPTURED
awayTeam / homeTeam { id, abbrev, commonName{default}, placeName{default}, score, sog, logo, darkLogo }
rosterSpots[] { teamId, playerId, firstName{default}, lastName{default}, sweaterNumber, positionCode, headshot }
plays[]
  eventId, sortOrder, typeCode, typeDescKey
  periodDescriptor{number, periodType}
  timeInPeriod "MM:SS", timeRemaining "MM:SS"
  situationCode "HGHSAGAS"  (see below), homeTeamDefendingSide "left"|"right"
  details{...}                         – varies by type; absent on period-start/-end, shootout-complete, game-end
summary{}                              – empty in captured samples
```

Before the game starts `plays` and `rosterSpots` are `[]`, `clock` is `20:00`, and
`periodDescriptor`/`gameOutcome` are absent.

**Observed `typeDescKey` / `typeCode`** *(observed, may be incomplete)*

| typeCode | typeDescKey | `details` fields of interest |
|---|---|---|
| 502 | `faceoff` | `winningPlayerId`, `losingPlayerId`, `xCoord`, `yCoord`, `zoneCode`, `eventOwnerTeamId` |
| 503 | `hit` | `hittingPlayerId`, `hitteePlayerId`, coords |
| 504 | `giveaway` | `playerId`, coords |
| 505 | `goal` | `scoringPlayerId`, `assist1PlayerId`, `assist2PlayerId`, `*PlayerTotal`, `goalieInNetId`, `shotType`, `awayScore`, `homeScore`, `eventOwnerTeamId`, coords, `highlightClip*` |
| 506 | `shot-on-goal` | `shootingPlayerId`, `goalieInNetId`, `shotType`, `awaySOG`, `homeSOG`, coords |
| 507 | `missed-shot` | `shootingPlayerId`, `goalieInNetId`, `reason` (`wide-left`, `wide-right`, `high-and-wide-*`, `above-crossbar`, `hit-crossbar`, `short`, `failed-bank-attempt`), `shotType`, coords |
| 508 | `blocked-shot` | `shootingPlayerId`, `blockingPlayerId`, `reason`, coords |
| 509 | `penalty` | `typeCode` (observed: `MIN`, `BEN`; community-documented: `MAJ`, `MIS`, `GAM`, `MAT`, `PS`), `descKey` (`tripping`, `hooking`, `high-sticking`, `interference`, `too-many-men-on-the-ice`, …), `duration`, `committedByPlayerId`, `drawnByPlayerId`, `servedByPlayerId` |
| 516 | `stoppage` | `reason` (`icing`, `offside`, `puck-frozen`, `goalie-stopped-after-sog`, `puck-in-netting`, `puck-in-crowd`, `tv-timeout`, …), `secondaryReason?` |
| 520 | `period-start` | — |
| 521 | `period-end` | — |
| 524 | `game-end` | — |
| 525 | `takeaway` | `playerId`, coords |
| 535 | `delayed-penalty` | — |
| — | `shootout-complete` | — |

Other type codes exist (e.g. `failed-shot-attempt` in shootouts, `goalie-change`,
`emergency-goaltender`); treat unknown keys as `OTHER`.

**`periodDescriptor.periodType`:** `REG`, `OT`, `SO` *(observed)*. Regular-season OT is
period 4, shootout period 5. Playoff OT periods continue as `OT` with number 4, 5, 6…

**`situationCode`** is a 4-char string `[away goalie][away skaters][home skaters][home goalie]`,
e.g. `1551` = 5-on-5 both goalies in, `1451` = away shorthanded (4 skaters), `0651` =
away goalie pulled, 6 skaters, `1010` = shootout / penalty shot. `shotType` values
observed: `wrist`, `slap`, `snap`, `backhand`, `tip-in`, `deflected`, `wrap-around`,
`bat`; community-documented: `poke`, `between-legs`, `cradle`.

**Coordinates:** `xCoord` ∈ [−99, 99] observed (feet from center ice along the
length; ±89 is the goal line), `yCoord` ∈ [−42, 42]. `zoneCode` ∈ `O` / `D` / `N` from the event owner's perspective.
`homeTeamDefendingSide` tells you which end the home net is in that period.

### `GET /gamecenter/{id}/boxscore`

| | |
|---|---|
| **Purpose** | Per-player game stats, grouped by team and position. The lineup source. |
| **Samples** | [`gamecenter-boxscore.pre.json`](samples/gamecenter-boxscore.pre.json) · [`gamecenter-boxscore.final.json`](samples/gamecenter-boxscore.final.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=15` |

**Response shape** — game header (same fields as play-by-play, minus plays) plus:

```
playerByGameStats
  awayTeam / homeTeam
    forwards[]  { playerId, sweaterNumber, name{default}, position, goals, assists, points,
                  plusMinus, pim, hits, powerPlayGoals, sog, faceoffWinningPctg, toi "MM:SS",
                  blockedShots, shifts, giveaways, takeaways }
    defense[]   – same fields
    goalies[]   { playerId, sweaterNumber, name, position, starter, toi, shotsAgainst, saves,
                  goalsAgainst, savePctg?, evenStrengthShotsAgainst "saves/shots", … }
```

`playerByGameStats` is absent before the game starts (the pre sample has only the
header). Lines/pairings are **not** exposed — only position groups.

### `GET /gamecenter/{id}/landing`

| | |
|---|---|
| **Purpose** | Game summary page data: scoring by period, penalties, three stars, shootout; for future games, a `matchup` block with season stats. |
| **Samples** | [`gamecenter-landing.pre.json`](samples/gamecenter-landing.pre.json) · [`gamecenter-landing.final.json`](samples/gamecenter-landing.final.json) · [`gamecenter-landing.final-shootout.json`](samples/gamecenter-landing.final-shootout.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=8` |

**Response shape**

```
(game header) + tiesInUse, maxPeriods
summary
  scoring[]   { periodDescriptor, goals[] { eventId, situationCode, strength "ev"|"pp"|"sh",
                playerId, name, teamAbbrev, timeInPeriod, shotType, goalModifier, assists[],
                awayScore, homeScore, highlightClip* } }
  penalties[] { periodDescriptor, penalties[] { timeInPeriod, type, duration, descKey,
                committedByPlayer, drawnBy, teamAbbrev } }
  shootout    { liveScore{home, away}, events[] { sequence, playerId, teamAbbrev, shotType,
                result "goal"|"save"|"miss", gameWinner } }          – only when a SO happened
  threeStars[]{ star, playerId, teamAbbrev, name, position, goals/assists/points or GAA/sv% }
matchup       – pre-game only: skaterComparison, goalieComparison, skaterSeasonStats, goalieSeasonStats
```

Note the assist objects here carry player **names**, while play-by-play only has IDs —
use `rosterSpots` from play-by-play to resolve IDs.

### `GET /gamecenter/{id}/right-rail`

| | |
|---|---|
| **Purpose** | Side-panel data: officials, coaches, scratches, linescore, shots by period, team stat comparison, season series, game report PDFs. |
| **Samples** | [`gamecenter-right-rail.pre.json`](samples/gamecenter-right-rail.pre.json) · [`gamecenter-right-rail.final.json`](samples/gamecenter-right-rail.final.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=8` |

**Response shape**

```
gameInfo { referees[], linesmen[], awayTeam/homeTeam { headCoach{default}, scratches[] } }
linescore { byPeriod[] { periodDescriptor, away, home }, totals{away, home} }
shotsByPeriod[] { periodDescriptor, away, home }
teamGameStats[] { category, awayValue, homeValue }   – sog, faceoffWinningPctg, powerPlay "x/y", pim, hits, blockedShots, giveaways, takeaways
seasonSeries[], seasonSeriesWins{}, gameReports{}, gameVideo{}
```

### `GET /wsc/play-by-play/{id}` &nbsp;·&nbsp; `GET /wsc/game-story/{id}`

Verified 2026-09-11 (`200`, cache 10–11 s). `wsc/play-by-play` is an alternative
event feed with a different, more verbose shape; `wsc/game-story`
([sample](samples/wsc-game-story.final.json)) is a narrative summary with
`summary.scoring`, `threeStars` and `gameOutcome`. Neither adds anything the
`gamecenter/*` endpoints lack; documented so nobody re-discovers them.

### `GET /roster/{team}/current` &nbsp;·&nbsp; `GET /roster/{team}/{season}`

| | |
|---|---|
| **Purpose** | Team roster grouped by position with player bios. |
| **Parameters** | `team` — abbreviation; `season` — `YYYYYYYY` |
| **Samples** | [`samples/roster-current.json`](samples/roster-current.json) (TOR) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=4` |

**Response shape** — `forwards[]`, `defensemen[]`, `goalies[]`, each entry:
`id, headshot, firstName{default,…}, lastName{default,…}, sweaterNumber, positionCode
(C|L|R|D|G), shootsCatches (L|R), heightInInches, heightInCentimeters, weightInPounds,
weightInKilograms, birthDate, birthCity{default}, birthStateProvince?, birthCountry (ISO-3)`.

`/roster/{team}/current` 307-redirects to `/roster/{team}/{currentSeason}`. An unknown
team code redirects and then 404s. `GET /roster-season/{team}`
([sample](samples/roster-season.json)) lists the seasons a team has rosters for.

### `GET /player/{id}/landing`

| | |
|---|---|
| **Purpose** | Full player profile: bio, current team, featured/career/season-by-season stats, last 5 games, awards, draft. |
| **Sample** | [`samples/player-landing.json`](samples/player-landing.json) (8478403, Jack Eichel) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=8` |

Key fields: `playerId, isActive, currentTeamId, currentTeamAbbrev, firstName, lastName,
sweaterNumber, position, headshot, heroImage, height*/weight*, birthDate, birthCity,
birthCountry, shootsCatches, draftDetails{year, round, pickInRound, overallPick},
featuredStats{season, regularSeason{subSeason, career}, playoffs}, careerTotals,
seasonTotals[] (one row per season × league × gameTypeId), last5Games[], awards[]`.

`GET /player/{id}/game-log/{season}/{gameType}`
([sample](samples/player-game-log.json)) returns `gameLog[]` with per-game stat lines.

### `GET /standings/{date}` &nbsp;·&nbsp; `GET /standings/now`

| | |
|---|---|
| **Purpose** | League standings as of a date. One flat array; group client-side by conference/division. |
| **Parameters** | `date` — `YYYY-MM-DD` or `now` (307 → last standings date; in the off-season that is last season's final day) |
| **Sample** | [`samples/standings.json`](samples/standings.json) (2026-04-17, final 2025–26) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=14` |

**Response shape** — `wildCardIndicator, standingsDateTimeUtc, standings[]` (32 rows).
Each row: `teamAbbrev{default}, teamName{default}, teamCommonName, placeName, teamLogo,
conferenceAbbrev/Name, divisionAbbrev/Name, conferenceSequence, divisionSequence,
leagueSequence, wildcardSequence, clinchIndicator ("p","x","y","z","e" or absent),
gamesPlayed, wins, losses, otLosses, ties, points, pointPctg, regulationWins,
regulationPlusOtWins, goalFor, goalAgainst, goalDifferential, streakCode, streakCount,
l10*, home*, road*, gameTypeId, date`.

`GET /standings-season` ([sample](samples/standings-season.json)) lists every season
with flags like `conferencesInUse`, `wildcardInUse`, `tiesInUse`, `standingsStart/End`.

### `GET /skater-stats-leaders/{season}/{gameType}` &nbsp;·&nbsp; `GET /goalie-stats-leaders/…`

| | |
|---|---|
| **Purpose** | Top-N leaders per stat category. |
| **Parameters** | path: `current` or `{season}/{gameType}`; query: `categories` (comma list), `limit` |
| **Samples** | [`samples/skater-stats-leaders.json`](samples/skater-stats-leaders.json) · [`samples/goalie-stats-leaders.json`](samples/goalie-stats-leaders.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=1–2` |

Response is an object keyed by category (`goals`, `points`, `assists`, `plusMinus`,
`penaltyMins`, `toi`, `faceoffLeaders`; goalies: `wins`, `savePctg`, `goalsAgainstAverage`,
`shutouts`), each an array of `{id, firstName, lastName, sweaterNumber, headshot,
teamAbbrev, teamName, teamLogo, position, value}`. `current` in the off-season returns
last season.

### Other verified endpoints (lower priority)

| Endpoint | Sample | Notes |
|---|---|---|
| `GET /season` | [season.json](samples/season.json) | Array of all season IDs, oldest first |
| `GET /club-stats/{team}/now` · `/{team}/{season}/{gameType}` | [club-stats.json](samples/club-stats.json) | Season stats for every skater/goalie on a team |
| `GET /schedule-calendar/{date}` | [schedule-calendar.json](samples/schedule-calendar.json) | Season boundaries + team list |
| `GET /playoff-bracket/{year}` | [playoff-bracket.json](samples/playoff-bracket.json) | `series[]` with seeds, wins, `seriesUrl` |
| `GET /playoff-series/carousel/{season}` | [playoff-series-carousel.json](samples/playoff-series-carousel.json) | Round-by-round series summaries |
| `GET /location` | — | Returns `{"country": "XX"}` for the caller's IP; `Cache-Control: private`. Used by nhl.com for broadcast blackouts. |

### Secondary: stats REST API

`https://api.nhle.com/stats/rest/en/{table}?cayenneExp=…` (verified 2026-09-11,
`200`, no key). Tables include `team`, `skater/summary`, `goalie/summary`,
`shiftcharts` (`?cayenneExp=gameId=2025030416` → 300 KB of shift data). Uses a
different query syntax (Apache Cayenne expressions) and returns `{data[], total}`.
Useful for bulk/historical stats; not needed for live tracking. Not sampled.

## Game states

`gameState` values *(observed + documented by community; `PRE`/`LIVE`/`CRIT` not yet
captured in samples)*:

| `gameState` | Meaning | Core `GameState` |
|---|---|---|
| `FUT` | Scheduled, not started | `SCHEDULED` |
| `PRE` | Pre-game (warm-ups, lineups posted) | `PRE_GAME` |
| `LIVE` | In progress | `LIVE` (or `INTERMISSION` if `clock.inIntermission`) |
| `CRIT` | In progress, late in a close game / OT | `LIVE` |
| `OVER` | Game ended, stats not yet final | `FINAL` |
| `FINAL` | Final, but boxscore may still be edited | `FINAL` |
| `OFF` | Official final | `FINAL` |

`gameScheduleState`: `OK` (observed); community-documented: `PPD` (postponed), `SUSP`
(suspended), `CNCL` (cancelled), `TBD`. Map to `POSTPONED`/`CANCELLED` accordingly.

What changes between states:

- **FUT → PRE/LIVE:** `periodDescriptor`, `clock`, `situation` appear; `rosterSpots`
  and `plays` fill; boxscore gains `playerByGameStats`.
- **LIVE → OFF:** `gameOutcome` appears; `clock.running` false; `summary.threeStars`
  appears in landing.
- Interestingly, 2025–26 preseason games in `club-schedule-season` are `FINAL` while
  regular-season games are `OFF` — preseason games may never reach `OFF`.

## Quirks & gotchas

- **`/now` is a redirect, not a resource.** Every `…/now` path returns `307 Location:`
  a concrete date/season. Follow redirects; better, resolve the date once and call the
  concrete URL, so you also learn what the NHL considers "now".
- **Timestamps are UTC ISO-8601 with `Z`** (`startTimeUTC`). `gameDate` is the date in
  **Eastern time**, and `venueUTCOffset` / `easternUTCOffset` are provided. `/score/{date}`
  and `/schedule/{date}` interpret `date` in Eastern time, so a late west-coast game
  belongs to the Eastern date.
- **Errors are HTML.** Unknown game ID or malformed date → `404` with an HTML body
  (`Content-Type: text/html`). Check `Content-Type` before parsing.
- **Unknown team abbreviation** on `/roster/{team}/current` → `307` then `404`.
- **No CORS headers.** `Access-Control-Allow-Origin` is never sent; a browser-based web
  app must go through a proxy. Android/desktop are unaffected.
- **Conditional requests don't work.** Weak `ETag`s are sent but `If-None-Match` still
  returns `200`, not `304`.
- **Cache TTLs are tiny** (1–20 s, `cf-cache-status` usually `MISS`/`DYNAMIC`), so
  the poll interval is bounded by politeness, not staleness.
- **Localised strings** are objects: `{"default": "Toronto", "fr": "Toronto", "cs": …}`.
  Always read `.default`. Not every string field is localised (e.g. `abbrev` is plain).
- **Boxscore names are abbreviated** (`"J. Eichel"`). Full names come from
  `roster`, `play-by-play.rosterSpots`, or `player/{id}/landing`.
- **Off-season behaviour:** `/score/now` → opening night; `/standings/now` → last
  standings date of the previous season; `skater-stats-leaders/current` → last season.
- **Images:** logos are SVG (`logo` light, `darkLogo` dark) on `assets.nhle.com`;
  headshots are PNG. Do not commit them to this repo.
- Season IDs appear both as int (`20262027`) and, in some community docs, as strings —
  the API always emits ints.

## Out of scope

- `GET /gamecenter/{id}/shift-charts` → `404`. Shift data lives on the stats host
  (`api.nhle.com/stats/rest/en/shiftcharts?cayenneExp=gameId=…`).
- Video/highlight URLs (`highlightClip*`, `threeMinRecap`, `gameVideo`) point at
  nhl.com video pages, not media files. Link out; do not scrape.
- `oddsPartners` (betting) — ignored on purpose.

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `/season`, `/schedule/{date}` | season IDs, `*StartDate`/`*EndDate` | Stage = `gameType` 1/2/3 |
| Game (id, teams, start time) | `/score/{date}` | `games[].id`, `homeTeam`, `awayTeam`, `startTimeUTC`, `venue` | Team has both `id` and `abbrev`; use `abbrev` as core team ID |
| GameState | any game object | `gameState`, `gameScheduleState`, `clock.inIntermission` | see table above |
| Score by period | `/gamecenter/{id}/right-rail` | `linescore.byPeriod[]` | or derive from `landing.summary.scoring` |
| Clock / period | `/gamecenter/{id}/play-by-play` | `clock`, `periodDescriptor` | `clock.timeRemaining` counts down |
| GameEvent | `/gamecenter/{id}/play-by-play` | `plays[]` | Rich: type, time, coords, players; `situationCode` → strength |
| Lineups | `/gamecenter/{id}/boxscore` | `playerByGameStats.*` | **Gap:** no line/pairing grouping, only F/D/G. Scratches in `right-rail.gameInfo` |
| Team | `/score` team objects, `/roster/{team}/current` | `id`, `abbrev`, `commonName`, `placeName`, `logo` | No dedicated team-info endpoint; `/standings` has conference/division |
| Player | `/player/{id}/landing`, `/roster/{team}/{season}` | bio fields | Full |
| Standings | `/standings/{date}` | `standings[]` | Flat; group by `conferenceName`/`divisionName`; wildcard via `wildcardSequence` |
| Shootout | `/gamecenter/{id}/landing` | `summary.shootout` | Also as `SO` period plays in play-by-play |

## TODO

- [ ] Capture `PRE`, `LIVE`, `CRIT` samples (play-by-play with `situation`, score with
      running `clock`) during the first preseason game on **2026-09-19**.
- [ ] Capture a `PPD`/`SUSP` `gameScheduleState` if one occurs.
- [ ] Confirm `OVER` state exists and its ordering vs `FINAL`.

## Changelog

| Date | Change |
|---|---|
| 2026-09-11 | Initial mapping. 24 endpoints verified against live API; 30 samples captured. |
