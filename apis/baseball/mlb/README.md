# MLB API (MLB Stats API)

| | |
|---|---|
| **Sport** | Baseball |
| **Country / region** | USA / Canada |
| **Official site** | https://www.mlb.com |
| **Base URL** | `https://statsapi.mlb.com/api/` (`v1/` for almost everything, `v1.1/` for the live game feed) |
| **Auth** | None — public endpoints used by mlb.com, MLB Gameday and the MLB app |
| **Format** | JSON (UTF-8). Errors are JSON too (`{"messageNumber", "message", …}`) |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *` on every GET. (`OPTIONS` preflight returns `403`, so do not send custom request headers from a browser.) |
| **WAF / UA requirement** | None. Fastly + Google front-end; requests with no `User-Agent` succeed. |
| **Last full verification** | 2026-09-12 |
| **Status** | ✅ verified (scheduled, pre-game, in-progress, inning break, final, postponed, doubleheader, extra innings) · ✅ mapped in `core` (`MlbProvider`, league id `mlb`) |

## Overview

`statsapi.mlb.com` is MLB Advanced Media's stats API. It powers mlb.com, the Gameday
live view, the MLB app, and MiLB.com, and is by far the most complete API in this
repo: every pitch has Statcast tracking data, every play has runner movement and
fielding credits, and the API publishes its own enum tables (game statuses, event
types, positions, pitch types …) as endpoints. It has been stable for many years
(the `v1` paths date from 2017; `v1.1/game/{pk}/feed/live` from 2018) and has a
large community around it (e.g. the `MLB-StatsAPI` Python package, `mlb-api` docs
on GitHub). It also covers the minor leagues, KBO, NPB and more via `sportId`, but
this document covers **MLB only** (`sportId=1`).

Two features matter a lot for a tracker:

- **`fields=`** — every endpoint accepts a `fields` parameter that whitelists
  response keys. A 760 KB live feed becomes a 365 B scoreboard poll
  ([sample](samples/feed-live.fields.json)).
- **`hydrate=`** — most endpoints can inline related resources (`linescore`,
  `probablePitcher`, `team`, `stats(…)`) so a scoreboard needs one call, not 15.

Baseball has **no clock**. Game progress is inning + half + outs + count + runners;
see [Game states](#game-states) and the core-model notes at the end.

All samples in [`samples/`](samples/) were captured on **2026-09-11** and **2026-09-12** (regular season,
ends 2026-09-27; postseason 2026-09-28 → 2026-10-31). See
[`samples/_meta.md`](samples/_meta.md) for which game each sample uses and what was
truncated.

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Sport | int | `1` = MLB | [`GET /v1/sports`](#metadata-endpoints). Required on `/schedule`, `/teams`, … |
| Season | 4-digit year, emitted as a **string** | `"2026"` | [`GET /v1/seasons?sportId=1`](#get-v1seasons) |
| League | int | `103` AL, `104` NL | `GET /v1/leagues?sportId=1` |
| Division | int | `200`–`205` | `GET /v1/divisions?sportId=1` |
| Game | `gamePk`, int, ~6 digits, **not** structured | `823088` | `/schedule` → `dates[].games[].gamePk` |
| Game (alt) | `gameGuid` UUID; `game.id` string `YYYY/MM/DD/awaymlb-homemlb-N` | `2026/09/10/texmlb-seamlb-1` | in the live feed; informational only |
| Game type | 1 letter | `R` regular, `S` spring, `F` wild card, `D` DS, `L` LCS, `W` WS, `A` All-Star, `E` exhibition, `I` intrasquad, `P`/`C` postseason/championship (other sports) | `gameType` field; `GET /v1/gameTypes` |
| Team | int (stable for the franchise) | `147` NYY, `136` SEA | `GET /v1/teams?sportId=1`. URLs use the **numeric** id, never the abbreviation |
| Player / person | int (stable for a career; also used for umpires, coaches, scorers) | `592450` Aaron Judge | Rosters, boxscore, plays |
| Venue | int | `3313` Yankee Stadium | `/teams`, game feeds |
| Date | `YYYY-MM-DD` (the game's **official/local** date) | `2026-09-11` | — |
| Feed timecode | `YYYYMMDD_HHMMSS` (UTC) | `20260910_224035` | `metaData.timeStamp` in the live feed |

**Team abbreviations (2026):** ATH ATL AZ BAL BOS CHC CIN CLE COL CWS DET HOU KC LAA
LAD MIA MIL MIN NYM NYY PHI PIT SD SEA SF STL TB TEX TOR WSH. Note they are 2–3
letters and differ from other sources' (e.g. `AZ` not `ARI`, `CWS` not `CHW`).
`teamCode`/`fileCode` (`ath`, `sea`) are a second, lowercase scheme used in
`game.id` and media URLs.

## Discovery path

1. **Today's games:** `GET /v1/schedule?sportId=1` (no `date` = today in US Eastern)
   or `…&date=YYYY-MM-DD`. Add `&hydrate=linescore,probablePitcher,decisions` to get
   score, inning, outs and pitchers in the same call. Read `dates[].games[]` →
   `gamePk`, `status`, `gameDate` (UTC), `teams.home/away`.
2. **A specific game, live:** `GET /v1.1/game/{gamePk}/feed/live` — everything:
   status, linescore, current play, every pitch, both boxscores, rosters, venue,
   weather. Poll it every **10 s** (`metaData.wait` says so). Use `fields=` to trim,
   or `feed/live/diffPatch?startTimecode=` to fetch only JSON-Patch diffs since the
   last `metaData.timeStamp`.
3. **Lineups / per-player stats:** inside the feed (`liveData.boxscore`) or
   standalone `GET /v1/game/{gamePk}/boxscore`. Batting order, bench, bullpen,
   substitutions and umpires are all there.
4. **Standings:** `GET /v1/standings?leagueId=103,104&season=2026` (+
   `standingsTypes=wildCard`). **Rosters:** `GET /v1/teams/{id}/roster`.
   **Player:** `GET /v1/people/{id}?hydrate=currentTeam,stats(…)`.

Recommended poll interval for live games: **10 s** for the live feed (server
`Cache-Control: max-age=10`; `metaData.wait: 10`), **20 s** for the schedule
(`max-age=20`). Do not poll games whose `abstractGameState` is `Final`, and stop
polling a `Preview` game more than once a minute until ~30 min before `gameDate`.

## Endpoints

### `GET /v1/schedule`

| | |
|---|---|
| **Purpose** | Games for a date, date range, team or single `gamePk`. The scoreboard feed. |
| **Parameters** | `sportId=1` **required** (or `gamePk=`); `date=YYYY-MM-DD` or `startDate=…&endDate=…`; `teamId=`; `gameType=`; `season=`; `hydrate=`; `fields=` |
| **Samples** | [`schedule.pre.json`](samples/schedule.pre.json) (2026-09-11, 15 games, all `Scheduled`) · [`schedule.final.json`](samples/schedule.final.json) (2026-09-10) · [`schedule.hydrated.json`](samples/schedule.hydrated.json) (2026-09-10 with `hydrate=linescore,probablePitcher,decisions,team,venue,broadcasts(all),seriesStatus`) · [`schedule.hydrated.pre-game.json`](samples/schedule.hydrated.pre-game.json) (2026-09-12 ~80 min before the first game, `hydrate=team,linescore,probablePitcher,decisions` — what the core provider requests; four games `P` Pre-Game with a `linescore` of `0`s) · [`schedule.no-games.json`](samples/schedule.no-games.json) (2026-12-25) · [`schedule.postponed.json`](samples/schedule.postponed.json) (2026-04-03) · [`schedule.doubleheader.json`](samples/schedule.doubleheader.json) (2026-04-04) · [`schedule.team-range.json`](samples/schedule.team-range.json) (NYY, 3 days) · [`schedule.fields.json`](samples/schedule.fields.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=20` (single date), `max-age=120` (date range) |

**Response shape**

```
totalItems, totalEvents, totalGames, totalGamesInProgress
dates[]
  date "YYYY-MM-DD", totalGames, totalGamesInProgress
  games[]
    gamePk, gameGuid, link "/api/v1.1/game/{pk}/feed/live", gameType, season "2026"
    gameDate "2026-09-10T20:10:00Z"  (UTC), officialDate "2026-09-10" (local game day)
    status { abstractGameState, codedGameState, detailedState, statusCode, startTimeTBD, abstractGameCode, reason? }
    teams.away / teams.home { team{id, name, link}, leagueRecord{wins, losses, ties, pct},
                              score?, isWinner?, splitSquad, seriesNumber, probablePitcher? (hydrate) }
    venue{id, name}, content{link}
    isTie, gameNumber, doubleHeader "N"|"Y"|"S", gamedayType, tiebreaker, dayNight
    scheduledInnings (9), inningBreakLength, gamesInSeries, seriesGameNumber, seriesDescription
    rescheduleDate?, rescheduleGameDate?, rescheduledFrom?, rescheduledFromDate?, resumeDate?, resumedFrom?, description?
    ifNecessary "N"|"Y", ifNecessaryDescription
    linescore{…}, decisions{winner, loser, save}, broadcasts[], seriesStatus{…}   – only with hydrate
```

**Notes**

- `date` filters on the game's **official (local) date**, not UTC: a 02:15Z game in
  San Francisco is listed under the previous day. With no `date`, "today" is US
  Eastern.
- No games → `200` with `dates: []` and `totalGames: 0`.
- **A postponed game is listed twice.** The original date shows the same `gamePk`
  with `status.codedGameState: "D"` (Postponed) and `rescheduleDate`; the makeup date
  lists it again with the real result and `rescheduledFrom`. `?gamePk=` also returns
  both rows. **De-duplicate by `gamePk` and keep the row whose `date == officialDate`.**
- Doubleheaders: `doubleHeader` = `Y` (traditional, one ticket, game 2 starts ~30 min
  after game 1) or `S` (split, separate start times); `gameNumber` 1/2. Both games
  have distinct `gamePk`s.
- `hydrate=linescore` adds the live inning/outs/count/score and the current
  defense/offense, making this a complete scoreboard in one call.
- `GET /v1/schedule/postseason?season=2025` ([sample](samples/schedule-postseason.json))
  returns the bracket games with `description` ("AL Wild Card 'A' Game 1"),
  `seriesGameNumber`, `ifNecessary`. `GET /v1/schedule/postseason/series` groups the
  same games by series (`series.id` like `W_1`).

### `GET /v1.1/game/{gamePk}/feed/live`

| | |
|---|---|
| **Purpose** | **The** live-game endpoint. Complete game state plus every pitch and play. |
| **Parameters** | `gamePk`; optional `timecode=YYYYMMDD_HHMMSS` (state as of that moment), `fields=`, `hydrate=` |
| **Samples** | [`feed-live.pre.json`](samples/feed-live.pre.json) (823498 NYM@NYY, `Scheduled`; player maps trimmed) · [`feed-live.pre-game.json`](samples/feed-live.pre-game.json) (824224 COL@DET, `P` Pre-Game ~80 min before first pitch: lineups posted, `linescore` at `Top 1` with no runs, one `game_advisory`; player maps trimmed) · [`feed-live.live.json`](samples/feed-live.live.json) (823088 replayed with `timecode=20260910_220000`: `I` In Progress, bottom 7, runner on second, 1–2 count, a pitching change in the current inning; `allPlays` cut to the last 3 of 48, player maps trimmed) · [`feed-live.final.json`](samples/feed-live.final.json) (823088 TEX@SEA, `Final`; `allPlays` truncated to 8 of 67, player maps trimmed) · [`feed-live.fields.json`](samples/feed-live.fields.json) (with `fields=`) |
| **Last verified** | 2026-09-12 |
| **Cache** | `max-age=10, public, stale-while-revalidate=30` |

**Response shape**

```
gamePk, link
metaData { wait (10), timeStamp "YYYYMMDD_HHMMSS", gameEvents[], logicalEvents[] }
gameData
  game     { pk, type, doubleHeader, id, gamedayType, tiebreaker, gameNumber, season, seasonDisplay }
  datetime { dateTime (UTC), originalDate, officialDate, dayNight, time "1:10", ampm "PM" (venue local) }
  status   { abstractGameState, codedGameState, detailedState, statusCode, startTimeTBD, abstractGameCode, reason? }
  teams.away / teams.home   – full team object incl. record{wins, losses, winningPercentage, divisionLeader, …}
  players  { "ID592450": {full person bio …}, … }    – everyone on both rosters, keyed "ID{id}"
  venue    { id, name, location{city, state, defaultCoordinates{lat, lng}, elevation}, timeZone{id, offset, offsetAtGameTime}, fieldInfo{capacity, turfType, roofType, leftLine…rightLine} }
  weather  { condition, temp "68", wind "3 mph, L To R" }        – {} before the game
  gameInfo { attendance, firstPitch (UTC), gameDurationMinutes }  – {} before the game
  review   { hasChallenges, away/home{used, remaining} }          – manager replay challenges
  absChallenges { hasChallenges, away/home{usedSuccessful, usedFailed, remaining} }  – ABS (robot-ump) challenges, 2026
  moundVisits   { away/home{used, remaining} }
  flags    { noHitter, perfectGame, awayTeamNoHitter, … }
  alerts[], probablePitchers{away, home}, officialScorer, primaryDatacaster
liveData
  plays    { allPlays[], currentPlay{}, scoringPlays[int], playsByInning[] }   – see below
  linescore{ currentInning, currentInningOrdinal, inningState, inningHalf, isTopInning, scheduledInnings,
             innings[] { num, ordinalNum, home{runs, hits, errors, leftOnBase}, away{…} },
             teams{home, away}{runs, hits, errors, leftOnBase, isWinner?},
             defense{pitcher, catcher, first, second, third, shortstop, left, center, right, batter, onDeck, inHole, battingOrder, team},
             offense{batter, onDeck, inHole, pitcher, battingOrder, team, first?, second?, third?},
             balls, strikes, outs }
  boxscore { teams{home, away}{…}, officials[], info[], pitchingNotes[], topPerformers[] }   – same as /v1/game/{pk}/boxscore
  decisions{ winner, loser, save? }                – only when final
  leaders  { hitDistance, hitSpeed, pitchSpeed }   – {} in captured samples
```

Before first pitch: `plays.allPlays` is `[]`, `linescore.innings` is `[]`,
`linescore.teams.home/away` are `{}`, `linescore.defense/offense` hold only `team`,
`weather`/`gameInfo` are `{}`, and `decisions` is absent. `probablePitchers` and full
`players` / `boxscore` rosters (bench + bullpen, empty `battingOrder`) are already
present.

**`plays.allPlays[]` — one entry per plate appearance**

```
result   { type "atBat", event "Home Run", eventType "home_run", description, rbi, awayScore, homeScore, isOut }
about    { atBatIndex, halfInning "top"|"bottom", isTopInning, inning, startTime, endTime (UTC),
           isComplete, isScoringPlay, hasReview, hasOut, captivatingIndex }
count    { balls, strikes, outs }                 – state *after* the play
matchup  { batter{id, fullName}, batSide{code L|R}, pitcher{id, fullName}, pitchHand{code},
           postOnFirst?/postOnSecond?/postOnThird? {id, fullName}, splits{batter, pitcher, menOnBase} }
pitchIndex[], actionIndex[], runnerIndex[]        – indexes into playEvents / runners
runners[]  { movement{originBase, start, end ("1B"|"2B"|"3B"|"score"|null), outBase, isOut, outNumber},
             details{event, eventType, movementReason, runner{id, fullName}, responsiblePitcher, isScoringEvent, rbi, earned, teamUnearned, playIndex},
             credits[] { player{id}, position{code, abbreviation}, credit "f_putout"|"f_assist"|"f_fielded_ball"|"f_throwing_error"… } }
playEvents[]
  type "pitch" | "action" | "pickoff" | "stepoff" | "no_pitch"
  index, playId (UUID), startTime, endTime, isPitch, pitchNumber?
  count{balls, strikes, outs}
  details { call{code, description}, description, code, isInPlay, isStrike, isBall, type{code, description} (pitch type),
            isOut, hasReview, event?, eventType? (for actions), awayScore?, homeScore?, isScoringPlay?, fromCatcher?, runnerGoing? }
  pitchData { startSpeed, endSpeed, strikeZoneTop/Bottom, coordinates{pX, pZ, x, y, x0, y0, z0, vX0…, aX…, pfxX, pfxZ},
              breaks{breakAngle, breakLength, breakVertical, breakVerticalInduced, breakHorizontal, spinRate, spinDirection},
              zone (1–14), typeConfidence, plateTime, extension }
  hitData   { launchSpeed, launchAngle, totalDistance, trajectory, hardness, location, coordinates{coordX, coordY} }   – when isInPlay
  reviewDetails? { isOverturned, inProgress, reviewType, challengeTeamId, player }
```

`plays.currentPlay` is the last element of `allPlays` (possibly incomplete while
live: `about.isComplete: false`, `result` partially filled). `scoringPlays` lists
`atBatIndex`es. `playsByInning[]` gives `{startIndex, endIndex, top[], bottom[],
hits{away[], home[]}}` per inning with spray-chart coordinates.

**Observed `result.eventType`** *(this game; the full list is
[`GET /v1/eventTypes`](samples/eventTypes.json), which also flags `plateAppearance`,
`hit` and `baseRunningEvent` per code)*: `strikeout`, `field_out`, `single`, `walk`,
`home_run`, `hit_by_pitch`, `sac_bunt`, `other_out`, `fielders_choice_out`,
`intent_walk`, `force_out`.

**Observed `playEvents[].details.eventType` for `action` events:** `batter_timeout`,
`mound_visit`, `game_advisory` ("Status Change - Pre-Game/Warmup/In Progress",
"Injury Delay."), `stolen_base_2b`, `wild_pitch`, `pitching_substitution`,
`offensive_substitution`, `defensive_substitution`, `defensive_switch`.

**Observed pitch `call.code`:** `B` Ball, `*B` Ball In Dirt, `C` Called Strike, `S`
Swinging Strike, `W` Swinging Strike (Blocked), `F` Foul, `T` Foul Tip, `H` Hit By
Pitch, `X` In play, out(s), `D` In play, no out, `E` In play, run(s).
Community-documented: `L` Foul Bunt, `M` Missed Bunt, `I` Intentional Ball, `P`
Pitchout, `V` Automatic Ball, `Q`/`R` Swinging/Foul Pitchout, `A` Automatic Strike.

**Pitch `type.code`:** see [`samples/pitchTypes.json`](samples/pitchTypes.json) (`FF`,
`SI`, `SL`, `ST`, `CU`, `CH`, `FC`, `FS`, `KC`, …).

**`metaData.timeStamp` / `timecode`** — `GET …/feed/live?timecode=20260910_223956`
returns the feed as it was at that moment (`status` was `In Progress`), and
`GET /v1.1/game/{pk}/feed/live/timestamps` ([sample](samples/feed-live-timestamps.final.json))
lists every timestamp at which the feed changed (454 for this game). This is how
to build live-state test fixtures from a finished game.

### `GET /v1.1/game/{gamePk}/feed/live/diffPatch`

| | |
|---|---|
| **Purpose** | Only what changed since a timecode, as [RFC 6902 JSON Patch](https://www.rfc-editor.org/rfc/rfc6902). |
| **Parameters** | `startTimecode=YYYYMMDD_HHMMSS` (the `metaData.timeStamp` you last saw); optional `endTimecode` |
| **Sample** | [`feed-live-diffPatch.final.json`](samples/feed-live-diffPatch.final.json) (5 patch sets, 28 KB, vs 760 KB full feed) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=10` |

Response is `[{ diff: [ {op "add"|"replace"|"remove", path "/liveData/plays/allPlays/66/playEvents/4", value} … ] }, …]`,
one object per feed update. If the timecode is too old / unknown the endpoint
returns the **full feed object** instead (`{gamePk, metaData, …}`) — check whether
the response is an array. Patches are relative to the *untrimmed* feed, so apply
them only to a full feed fetched without `fields=`.

### `GET /v1/game/{gamePk}/boxscore`

| | |
|---|---|
| **Purpose** | Lineups, substitutions, per-player and team game stats, umpires, game notes. Identical to `liveData.boxscore` in the feed. |
| **Samples** | [`boxscore.pre.json`](samples/boxscore.pre.json) · [`boxscore.final.json`](samples/boxscore.final.json) (complete) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=10` |

**Response shape**

```
teams.home / teams.away
  team { full team object incl. record }
  teamStats { batting{…}, pitching{…}, fielding{…} }
  players { "ID641487": { person{id, fullName, boxscoreName}, jerseyNumber, position{code, abbreviation}, status{code, description},
                          parentTeamId, battingOrder? "100".."903", stats{batting, pitching, fielding}, seasonStats{…},
                          gameStatus{isCurrentBatter, isCurrentPitcher, isOnBench, isSubstitute}, allPositions[] } }
  batters[]       – player ids in order of appearance (starters, then subs, then pitchers that batted)
  pitchers[]      – in order of appearance
  bench[], bullpen[]
  battingOrder[]  – 9 ids: the *current* lineup slot 1–9 (empty before the game)
  info[]  { title "BATTING"|"FIELDING"|…, fieldList[] {label, value} }   – narrative notes (HR, RBI, LOB, E …)
  note[]  { label "a"|"1", value "Walked for Rodden in the 8th." }       – substitution footnotes
officials[] { official{id, fullName}, officialType "Home Plate"|"First Base"|… }
info[] { label, value }   – WP, HBP, ABS Challenge, Pitches-strikes, Umpires, Weather, Wind, First pitch, T (duration), Att, Venue
pitchingNotes[], topPerformers[]
```

**`battingOrder` on a player** is a 3-digit string: hundreds digit = lineup slot
(1–9), last two digits = substitution sequence in that slot (`100` starter, `101`
first replacement, `902` second replacement of the 9-hole). `allPositions[]` lists
every position the player took (`PH`, `PR`, `DH`, …). Pitchers' `stats.pitching.note`
carries the decision: `"(W, 12-9)"`, `"(L, 1-3)(BS, 2)"`, `"(S, 27)"`, `"(H, 20)"`.

### `GET /v1/game/{gamePk}/linescore`

| | |
|---|---|
| **Purpose** | Just the linescore block: inning-by-inning R/H/E, current inning/half/outs/count, who is up. The lightest "game state" poll. |
| **Samples** | [`linescore.pre.json`](samples/linescore.pre.json) · [`linescore.live.json`](samples/linescore.live.json) (823088 at `timecode=20260910_220000`: `Bottom` 7, `offense.second` set) · [`linescore.live-break.json`](samples/linescore.live-break.json) (`timecode=20260910_214500`: `inningState: "End"` of the 6th, 3 outs — the between-innings state) · [`linescore.final.json`](samples/linescore.final.json) · [`linescore.final-extra.json`](samples/linescore.final-extra.json) (11 innings) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=10` |

Shape as `liveData.linescore` above. `innings[]` grows past `scheduledInnings`
in extra innings (`currentInning: 11`, 11 entries). `inningState` observed:
`Top`, `Bottom`, `End` (via `timecode=`); `Middle` is the other break state. Both
endpoints honour `timecode=`. Once final, `teams.home/away.isWinner` appears
(standalone endpoint only).

### `GET /v1/game/{gamePk}/playByPlay`

Same as `liveData.plays` (`allPlays`, `currentPlay`, `scoringPlays`,
`playsByInning`). Samples: [`playByPlay.pre.json`](samples/playByPlay.pre.json)
(empty arrays), [`playByPlay.final.json`](samples/playByPlay.final.json)
(truncated to 8 plays), [`playByPlay.final.fields.json`](samples/playByPlay.final.fields.json)
(**all 67 plays** of 823088 with `fields=` dropping pitch tracking, timestamps and
Statcast coordinates — the complete play list the core event mapper is tested on).
Verified 2026-09-11, `max-age=10`. Use the full feed instead unless you only want plays.

### `GET /v1/teams` &nbsp;·&nbsp; `GET /v1/teams/{id}`

| | |
|---|---|
| **Purpose** | All 30 clubs, or one. |
| **Parameters** | `sportId=1` (else you get every affiliated team); `season=`; `hydrate=venue,division,league` |
| **Samples** | [`teams.json`](samples/teams.json) · [`team.json`](samples/team.json) (147 NYY, hydrated) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=60` |

Team object: `id, name ("New York Yankees"), teamName ("Yankees"), locationName,
shortName, franchiseName, clubName, abbreviation, teamCode, fileCode, season,
venue{id, name}, springVenue, springLeague, league{id, name}, division{id, name},
sport, firstYearOfPlay, allStarStatus, active`. No logo URL in the API — mlb.com uses
`https://www.mlbstatic.com/team-logos/{teamId}.svg` (light) and
`https://www.mlbstatic.com/team-logos/team-cap-on-dark/{teamId}.svg` (dark); both
verified `200 image/svg+xml` on 2026-09-12 without headers. Player headshots:
`https://img.mlbstatic.com/mlb-photos/image/upload/w_213,q_auto:best/v1/people/{personId}/headshot/67/current`
(`200 image/jpeg`). None of these are part of the Stats API contract.

### `GET /v1/teams/{id}/roster`

| | |
|---|---|
| **Purpose** | Roster. |
| **Parameters** | `rosterType` — `active` (default, 26-man), `40Man`, `depthChart`, `fullSeason`, `fullRoster`, `nonRosterInvitees`, `gameday`, `allTime`, `coach`; `season=`; `date=`; `hydrate=person` |
| **Samples** | [`roster.active.json`](samples/roster.active.json) · [`roster.40man.json`](samples/roster.40man.json) · [`roster.depthChart.json`](samples/roster.depthChart.json) · [`coaches.json`](samples/coaches.json) (`GET /v1/teams/{id}/coaches`) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=60` |

`roster[]` = `{person{id, fullName}, jerseyNumber, position{code, name, type,
abbreviation}, status{code "A", description "Active"}, parentTeamId}`. Coaches:
`{person, jerseyNumber, job "Manager", jobId "MNGR", title}`. `rosterType` values
are listed by `GET /v1/rosterTypes` ([sample](samples/rosterTypes.json)).

### `GET /v1/people/{id}` &nbsp;·&nbsp; `GET /v1/people?personIds=a,b`

| | |
|---|---|
| **Purpose** | Player / person bio, optionally with team and stats inlined. |
| **Parameters** | `hydrate=currentTeam,stats(group=[hitting,pitching],type=[season,career])` — brackets are literal (curl needs `-g`) |
| **Sample** | [`people.json`](samples/people.json) (592450 Aaron Judge, hydrated) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=300` |

Person: `id, fullName, firstName, lastName, middleName, useName, boxscoreName,
nickName, primaryNumber, birthDate, currentAge, birthCity, birthStateProvince,
birthCountry, height "6' 7\"", weight (lb), active, primaryPosition{code, name, type,
abbreviation}, batSide{code}, pitchHand{code}, draftYear, mlbDebutDate, gender,
isPlayer, isVerified, nameSlug, strikeZoneTop/Bottom` (+ `currentTeam`, `stats[]`
with hydrate). Height is an imperial string, weight an int in pounds.

`GET /v1/people/{id}/stats?stats=season,career&group=hitting&season=2026`
([sample](samples/people-stats.season-career.json)) and
`…?stats=gameLog&group=hitting&season=2026`
([sample](samples/people-stats.gameLog.json), truncated to the last 5 games)
return `stats[] { type{displayName}, group{displayName}, splits[] { season, stat{…}, team, player, game? } }`.
Stat keys are the usual box-score vocabulary (`avg`, `obp`, `ops`, `homeRuns`, `rbi`,
`era`, `inningsPitched`, `whip` …); rate stats are **strings** (`".249"`, `"3.21"`),
counting stats ints. Valid `stats=` values: `GET /v1/statTypes`
([sample](samples/statTypes.json)).

### `GET /v1/standings`

| | |
|---|---|
| **Purpose** | Standings by division, and wild-card tables. |
| **Parameters** | `leagueId=103,104` **required**; `season=2026`; `standingsTypes=regularSeason` (default) `,wildCard,divisionLeaders,springTraining,postseason,…`; `date=`; `hydrate=team,division` (default `team` is just `{id, name "Rays"}`) |
| **Samples** | [`standings.json`](samples/standings.json) (6 division records, unhydrated: `team` is `{id, name "Rays"}`, `division` is `{id}`) · [`standings.hydrated.json`](samples/standings.hydrated.json) (same with `hydrate=team,division` — full team objects with `abbreviation`, and division names; what the core provider requests) · [`standings.wildCard.json`](samples/standings.wildCard.json) (2 league-wide records, 12 teams each) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=60` |

```
records[]
  standingsType, league{id}, division{id}, sport, lastUpdated
  teamRecords[]
    team{id, name}, season, streak{streakCode "W3", streakType, streakNumber}
    divisionRank, leagueRank, sportRank, wildCardRank?, gamesPlayed
    gamesBack, wildCardGamesBack, leagueGamesBack, sportGamesBack, divisionGamesBack   – strings: "-", "3.5"
    leagueRecord{wins, losses, ties, pct}, wins, losses, winningPercentage, runsScored, runsAllowed, runDifferential
    records{ splitRecords[] {type "home"|"away"|"lastTen"|"extraInning"|"oneRun"|"day"|"night"|"grass"|"turf"|"left"|"right"…},
             divisionRecords[], overallRecords[], leagueRecords[], expectedRecords[] }
    divisionChamp, divisionLeader, hasWildcard, clinched, eliminationNumber, wildCardEliminationNumber, magicNumber?
```

Ranks and games-back are **strings**. `standingsTypes` values: `GET /v1/standingsTypes`
([sample](samples/standingsTypes.json)).

### `GET /v1/stats/leaders`

`?leaderCategories=homeRuns,battingAverage&season=2026&sportId=1&limit=5`
([sample](samples/stats-leaders.json)) → `leagueLeaders[] { leaderCategory, statGroup
"hitting"|"pitching"|"catching", season, leaders[] {rank, value (string), person, team,
league} }`. Note a category is returned once **per stat group**, so `battingAverage`
yields hitting *and* pitching (BA against) tables — filter on `statGroup`. Verified
2026-09-11, `max-age=60`. `GET /v1/stats?stats=season&group=hitting&season=2026&sportId=1`
is the bulk-table variant (same `splits[]` shape as player stats).

### `GET /v1/seasons`

`?sportId=1` ([sample](samples/seasons.json)) → the current season with every
boundary date: `preSeasonStartDate, springStartDate/EndDate, regularSeasonStartDate,
lastDate1stHalf, allStarDate, firstDate2ndHalf, regularSeasonEndDate,
postSeasonStartDate/EndDate, seasonEndDate, offseasonStartDate`. This is the
"what phase are we in" call. `GET /v1/seasons/all?sportId=1` lists every season
since 1876. Verified 2026-09-11, `max-age=900`.

### Metadata endpoints

All verified 2026-09-11, `max-age=300–3600`, no parameters. They **define** the
enums used elsewhere — prefer them to hard-coded lists.

| Endpoint | Sample | Contents |
|---|---|---|
| `GET /v1/sports` | [sports.json](samples/sports.json) | `sportId` list (1 MLB, 11 AAA, 12 AA, 13 A+, 14 A, 16 Rookie, 17 Winter, 31 NPB, 32 KBO, 22 College, …) |
| `GET /v1/leagues?sportId=1` | [leagues.json](samples/leagues.json) | AL/NL + spring leagues + historical; `seasonState: "inseason"\|"offseason"` |
| `GET /v1/divisions?sportId=1` | [divisions.json](samples/divisions.json) | 6 divisions, `league.id` |
| `GET /v1/venues/{id}?hydrate=location,fieldInfo,timezone` | [venue.json](samples/venue.json) | address, coordinates, elevation, tz, capacity, roof, fence distances |
| `GET /v1/gameTypes` | [gameTypes.json](samples/gameTypes.json) | 11 codes |
| `GET /v1/gameStatus` | [gameStatus.json](samples/gameStatus.json) | **all 210** status rows (see Game states) |
| `GET /v1/eventTypes` | [eventTypes.json](samples/eventTypes.json) | play/runner event codes with `plateAppearance`, `hit`, `baseRunningEvent` flags |
| `GET /v1/positions` | [positions.json](samples/positions.json) | position codes `1`–`9`, `DH`, `PH`, `PR`, `TWP`, … with `pitcher`/`fielder`/`outfield` flags |
| `GET /v1/pitchTypes` | [pitchTypes.json](samples/pitchTypes.json) | 24 pitch type codes |
| `GET /v1/hitTrajectories` | [hitTrajectories.json](samples/hitTrajectories.json) | `ground_ball`, `line_drive`, `fly_ball`, `popup`, `bunt_*` |
| `GET /v1/rosterTypes`, `/statTypes`, `/standingsTypes` | linked above | parameter vocabularies |

### Other verified endpoints (lower priority)

| Endpoint | Notes |
|---|---|
| `GET /v1/game/{pk}/contextMetrics` | [sample](samples/contextMetrics.final.json). Game header + `homeWinProbability`/`awayWinProbability` (current) and sac-fly probabilities. 1.6 KB, `max-age=10`. |
| `GET /v1/game/{pk}/winProbability` | Per-play win probability, **900 KB** for a full game. Not sampled. |
| `GET /v1/game/{pk}/content` | Media: highlights, recaps, image cuts, EPG. **600 KB**. Not sampled; link out to `mlb.com/gameday/{pk}` instead. |
| `GET /v1/sports/1/players?season=2026` | Every MLB player of the season (1,449 people, 1.5 MB). Not sampled. |
| `GET /v1/teams/{id}/stats?stats=season&group=hitting,pitching&season=2026` | Regular-season team totals. [Sample](samples/team-stats.season.json), team 147, verified 2026-09-14. `stats[].group.displayName` is `hitting` / `pitching`; `splits[]` carries `season`, `team` and `stat`. Counts are numbers; rates and innings are strings. |
| `GET /v1/teams/{id}/leaders?leaderCategories=homeRuns&season=2026` | Team leaders. |
| `GET /v1/people?personIds=592450,660271` | Batch person lookup. |
| `GET /v1/gamePace`, `/attendance?teamId=`, `/awards`, `/draft/{year}` | Verified `200`; not needed for tracking. `/draft/2026` is 1.4 MB. |

## Game states

The API publishes the full table at `GET /v1/gameStatus`
([sample](samples/gameStatus.json), 210 rows). Each status has four fields; use them
in this order of coarseness:

| Field | Values | Use for |
|---|---|---|
| `abstractGameState` | `Preview` · `Live` · `Final` · `Other` | Core `GameState` bucket |
| `codedGameState` | `S` scheduled · `P` pre-game/warmup/delayed start · `I` in progress · `M` manager challenge · `N` umpire review · `T`/`U` suspended · `O` game over · `F` final · `D` postponed · `C` cancelled · `Q`/`R` forfeit · `W` writing · `X` unknown | Finer state without parsing text |
| `statusCode` | `codedGameState` + optional reason letter, e.g. `DR` (Postponed: Rain), `IR` (Delayed: Rain), `FR` (Completed Early: Rain), `FT` (Final: Tied), `PW` (Warmup), `IH` (Instant Replay), `MJ` (Player challenge: Pitch Result) | Exact reason |
| `detailedState` | Human text: `Scheduled`, `Pre-Game`, `Warmup`, `In Progress`, `Delayed Start: Rain`, `Manager challenge: Tag play`, `Suspended: Rain`, `Game Over`, `Final`, `Postponed`, `Cancelled`, `Completed Early: Rain` … | Display |

Mapping to the core model:

| `codedGameState` | Core `GameState` | Notes |
|---|---|---|
| `S` | `SCHEDULED` | `startTimeTBD` may be true |
| `P` | `PRE_GAME` | `PW` Warmup has `abstractGameState: Live`; delayed starts (`PR`…) are `Preview` |
| `I`, `M`, `N` | `LIVE` | plus `linescore.inningState` `Middle`/`End` → `INTERMISSION` if the app wants inning breaks; `IR`/`II`… = rain delay (surface via `reason`) |
| `T`, `U` | `LIVE` (suspended) | no core state yet; resumes on `resumeDate` |
| `O` | `FINAL` | "Game Over" — stats not yet official |
| `F` | `FINAL` | includes `FR`/`FM` shortened games and `FT` ties |
| `D` | `POSTPONED` | `rescheduleDate` gives the makeup |
| `C` | `CANCELLED` | |
| `Q`, `R` | `FINAL` (forfeit) | |
| `W`, `X` | `UNKNOWN` | `X` is what `feed/live` returns for a nonexistent `gamePk` (with `200`, see quirks) |

Observed in samples: `S`, `P` (`feed-live.pre-game.json`, `schedule.hydrated.pre-game.json`),
`I` (`feed-live.live.json`, via `timecode=`), `F`, `DR`, `DI`, `FR`; `PW`, `P`, `I` as
`game_advisory` play events. **`PW`, `M`/`N`, `O` live feeds not yet captured** — see TODO.

What changes between states (observed pre → final; live inferred from `timecode`
replay):

- **S → P/PW → I:** `game_advisory` actions appear in `allPlays[0].playEvents`;
  `linescore.innings` gets entry 1; `boxscore.battingOrder` fills;
  `gameData.weather` fills. During the game `linescore.balls/strikes/outs`,
  `offense.first/second/third` (runners), `defense.*` and `plays.currentPlay` are the
  live state; `metaData.timeStamp` advances every few seconds.
- **I → O/F:** `decisions` appears; `linescore.teams.*.isWinner` (standalone);
  `gameInfo.gameDurationMinutes`, `attendance`; `boxscore.info` gains `T`/`Att`;
  `metaData.gameEvents` contains `game_finished`.

## Quirks & gotchas

- **`sportId=1` is required** on `/schedule`, `/teams`, `/standings` (`leagueId`),
  etc. Without it `/schedule` returns `400 {"message": "Missing required parameter
  sportId or gamePk"}` and `/teams` returns every affiliated minor-league team.
- **Postponed games appear twice** in the schedule (original date + makeup date,
  same `gamePk`). De-dupe by `gamePk`; prefer the row where `date == officialDate`.
- **Dates:** `gameDate` is UTC ISO-8601 with `Z`; `officialDate` is the local game
  day and what `date=` filters on; `datetime.time`/`ampm` are venue-local strings;
  `venue.timeZone.id` is an IANA zone. Doubleheader game 2 and late West-coast games
  therefore have a `gameDate` on the "next" UTC day.
- **Nonexistent `gamePk` on `feed/live` returns `200`** with a stub (`gamePk: 0`,
  `status.codedGameState: "X"`, empty `liveData`). Other endpoints return a proper
  `404 {"messageNumber": 10, "message": "Object not found"}`. Check `gamePk != 0`.
- **`/v1/game/{pk}/feed/live` does not exist** (`404`) — the live feed is on
  `v1.1` only. `boxscore`, `linescore`, `playByPlay` stay on `v1`.
- **Numbers as strings:** season (`"2026"`), rate stats (`".249"`, `"3.21"`),
  standings ranks and games-back (`"1"`, `"3.5"`, `"-"`), `battingOrder` (`"100"`),
  jersey numbers, `weather.temp`. Parse defensively.
- **Player maps are keyed `"ID{id}"`** (`gameData.players`, `boxscore.teams.*.players`).
- **Caching:** `Cache-Control: max-age=10/20/60/…, public, stale-while-revalidate=30,
  stale-if-error=86400` via Fastly; `Age`/`X-Cache: HIT` show edge hits. No `ETag`
  / `Last-Modified`, so conditional requests are not possible. gzip is honoured
  (760 KB feed → 123 KB).
- **CORS:** `Access-Control-Allow-Origin: *` on GETs, so a web app can call it
  directly. An `OPTIONS` preflight returns `403`, so from a browser send only
  simple headers (no custom `X-*`, no `Authorization`) or the preflight will fail.
- **`hydrate` syntax** uses parentheses and brackets:
  `hydrate=stats(group=[hitting,pitching],type=[season,career])`. Shell-escape or
  URL-encode; curl needs `-g`.
- **`fields=` is a flat whitelist of key names** at any depth — listing `teams,home,
  away,score` keeps those keys wherever they occur. Very effective for polling;
  see [samples/feed-live.fields.json](samples/feed-live.fields.json).
- **Invalid parameters** return `400 {"messageNumber": 11, "message": "Invalid
  Request with value: …"}`.
- **Rate limiting:** none observed across ~80 requests in a minute. Be polite anyway;
  the community convention is ≤ 1 request/s sustained.
- **Off-season:** not observed yet (season runs to 2026-10-31). Community reports:
  `/schedule` for a date with no games returns `dates: []`; `/standings` without
  `season` returns the current calendar year, which in January is the *upcoming*
  season with zero games.
- **Minor leagues, KBO, NPB** etc. use the same API with a different `sportId`; the
  live feed shape is the same but pitch/hit tracking data is mostly absent.

## Out of scope

- `GET /v1/game/{pk}/feed/color` → `404` (legacy Gameday colour feed is gone).
- `GET /v1/game/{pk}/content` — media only (highlights, EPG, images). Link to
  `https://www.mlb.com/gameday/{gamePk}` instead.
- `winProbability`, `draft`, `awards`, `homeRunDerby`, `/sports/1/players` — work,
  but too big or irrelevant for a live tracker.
- Streaming/MLB.TV endpoints (`mediaId`, `mvpdAuthRequired` in `broadcasts`) require
  an MLB account — ignored.
- The undocumented "Gameday" XML/`gd2.mlb.com` feeds were retired; do not look for them.

## Core model mapping

Implemented by `org.openscore.providers.mlb.MlbProvider` (league id `mlb`). Capabilities:
`GAMES_BY_DATE GAME EVENTS LINEUPS STANDINGS TEAM TEAM_SCHEDULE TEAM_STATS ROSTER PLAYER LIVE_UPDATES
INTERMISSION_STATE PERIOD_SCORES EVENT_COORDINATES` — no `CLOCK` (there is none),
no `LIVE_PUSH` (the client polls at the 10 s floor, but after its initial full feed it uses
`diffPatch` deltas rather than downloading the whole feed again).

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| Game list | `/v1/schedule?sportId=1&date=…&hydrate=team,linescore,probablePitcher,decisions` | `dates[].games[]` | `gamesOn(date)` takes the **official (local) game date**. One row per `gamePk`; a postponed game is `POSTPONED` on its original date and a normal game on the makeup date |
| Game ids | any | `gamePk` as string | Team ids are the numeric Stats API ids (`147`), player ids the person ids |
| GameState | `status.codedGameState` | `S`→`SCHEDULED`, `P`→`PRE_GAME`, `I`/`M`/`N`→`LIVE` (or `INTERMISSION` when `linescore.inningState` is `Middle`/`End`), `T`/`U`→`SUSPENDED`, `O`/`F`/`Q`/`R`→`FINAL`, `D`→`POSTPONED`, `C`→`CANCELLED`, else `UNKNOWN` | `rawState` = `statusCode/detailedState[/reason]`, e.g. `DR/Postponed/Rain` |
| Score | `teams.home/away.score` (schedule) or `linescore.teams.*.runs` (feed) | | `null` while `SCHEDULED`/`PRE_GAME` even though the feed already says 0–0 |
| Periods / period scores | `linescore.innings[]` | `num`, `home.runs`, `away.runs` | Innings are periods (`label` = inning number); past `scheduledInnings` they are `OVERTIME`. A half-inning without `runs` (not played yet, or the home ninth of a home win) reads **0** |
| Ending | `linescore.innings.size` vs `scheduledInnings` | | `OVERTIME` for extra innings, else `REGULATION` |
| Clock | `linescore.currentInning`, `inningState` | | Period only, no time: `Clock.time.label` is `Top 7` / `Mid 7` / `Bot 7` / `End 7`, `running` null |
| **Situation** | `linescore` | `outs`, `balls`, `strikes`, `offense.first/second/third/batter/onDeck`, `defense.pitcher` | `Game.situation` = `BaseballSituation` (core `model.baseball`) while live; player refs resolved through `gameData.players` (number, position, headshot) |
| Stats | `linescore.teams.*` | `hits`, `errors`, `leftOnBase` | `Game.stats` keys `hits`, `errors`, `leftOnBase`; empty until there is a score |
| GameEvent | `liveData.plays.allPlays[]` | `result.eventType`, `about`, `matchup`, `runners[]`, `count`, `playEvents[]` | One event per **completed** plate appearance (`id` = `atBatIndex`, `details.kind` `plate-appearance`: batter, pitcher, rbi, out, outsAfter, scoringPlay, pitches, runner movements, batted-ball data), plus `playEvents[].type == "action"` that matter (`id` = `atBatIndex.index`): stolen bases, caught stealing, pickoffs, wild pitches, passed balls, balks, runner outs (`base-running`), substitutions/switches (`baseball-substitution`: incoming player, position, the outgoing name parsed from the description), ejections. Timeouts, mound visits, pitches and `game_advisory` are not events. `score` only on scoring plays. `coordinates` = `hitData.coordinates` (spray chart) |
| Event team | `about.isTopInning` | | Top → away bats; pitching/defensive changes go to the fielding side |
| Lineups | `/v1/game/{pk}/boxscore` | `battingOrder[]`, `pitchers[]`, `bench[]`, `bullpen[]`, `players.*.battingOrder` | Groups `Batting order` (`STARTERS`, current occupants of the nine slots), `Pitchers` (`OTHER`, order of appearance), `Substituted` (`OTHER`, hitters replaced during the game), `Bench`, `Bullpen` (`OTHER`). Empty until the batting order is posted (a few hours before first pitch). No manager (`/teams/{id}/coaches` not called) |
| Team | `/v1/teams/{id}` | `name`, `abbreviation`, `locationName`, `teamName`, `venue`, `league`, `division` | `conference` = AL/NL, `division` = division name; logos from mlbstatic.com (see above) |
| Roster | `/v1/teams/{id}/roster` (active 26-man) | `person`, `jerseyNumber`, `position.abbreviation`, `status` | Bio fields need `player()` |
| Player | `/v1/people/{id}?hydrate=currentTeam` | bio fields | `height` `6' 7"` → cm, `weight` lb → kg, `handedness` = `bats/throws` (`R/R`, `S/R`), `nationality` = `birthCountry` as given (`USA`), `birthPlace` = `city, stateProvince` |
| Standings | `/v1/standings?leagueId=103,104&hydrate=team,division[&season=…]` | `records[].teamRecords[]` | `grouping` = `division`, six groups in API order (AL East/Central/West, NL East/Central/West), rows by `divisionRank`. **`points` = wins** (baseball has none); `goalsFor/Against/Difference` = runs. `extra`: `pct`, `gamesBack`, `wildCardGamesBack`, `wildCardRank`, `leagueRank`, `streak`, `divisionLeader`, `clinched`, `magicNumber`, `eliminationNumber`, `home`, `away`, `last10`. Wild-card tables not mapped |
| Not mapped | `probablePitcher`, `decisions` (W/L/S), `weather`, `attendance`, umpires, pitch-by-pitch, `winProbability` | | Candidates for a later baseball-specific extension |

## TODO

- [x] `P` (Pre-Game), `I` (In Progress, runner on base) and `End` (inning break) samples —
      captured 2026-09-12 (`feed-live.pre-game.json`, `feed-live.live.json`,
      `linescore.live*.json`); `I` and `End` via `timecode=` replay of a finished game.
- [ ] Capture `PW` (Warmup), `M`/`N` (replay review) and `O` (Game Over) states from a
      live game (any evening until 2026-09-27).
- [ ] Capture a rain-delay (`IR`) and a suspended (`T`/`U`) game if one occurs.
- [ ] Capture postseason (`F`/`D`/`L`/`W`) samples from 2026-09-28.
- [ ] Verify off-season behaviour of `/schedule` and `/standings` in November.
- [ ] Map probable pitchers and decisions into a baseball-specific presentation field.

## Changelog

| Date | Change |
|---|---|
| 2026-09-11 | Initial mapping. 40+ endpoints verified against the live API; 48 samples captured (pre-game, final, extra innings, postponed, doubleheader, postseason schedule). |
| 2026-09-12 | Live-state samples (`P`, `I` with runners, `End` inning break) via a live pre-game feed and `timecode=` replay; full 67-play list; hydrated standings and scoreboard schedule; logo/headshot URLs verified. `MlbProvider` added to `core`; "Core model mapping" now describes the implementation. |

### Team pages (2026-09-14)

`teamSchedule(teamId, startDate, endDate)` uses
`GET /v1/schedule?sportId=1&teamId={id}&startDate={startDate}&endDate={endDate}&hydrate=team,linescore,probablePitcher,decisions`.
The range is inclusive and may cover a whole season, including spring training and postseason.
The captured [2026 Yankees schedule](samples/schedule.team-season.json) uses `teamId=147`,
`startDate=2026-01-01`, `endDate=2026-12-31`. The response was 1.24 MB; **the sample's
`dates[]` is truncated to September 10–15** (six dates, complete game objects). Envelope
totals are untouched. Game ids preserve doubleheaders and deduplicate rescheduled entries;
`Game.scheduleDate` retains `officialDate` for date headings and day-list refreshes.

`teamStats(teamId, seasonId)` reads the season-totals endpoint above and returns
`TeamSeasonStats` with Batting and Pitching groups. Values retain MLB's notation;
missing values/groups remain absent, and splits must match both team and season.
Both new calls are cached for five minutes. The Android page refreshes small day listings
once a minute while visible and reloads the season sections every five minutes.
