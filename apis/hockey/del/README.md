# DEL (PENNY DEL) API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | Germany |
| **Official site** | https://www.penny-del.org |
| **Base URL** | `https://del-services.appticore.com/2026/query.php` |
| **Auth** | None. The backend of the official *Deutsche Eishockey Liga* app (`org.del.android` 2.2); the app sends no key, only `os`, `requestName`, `lastUpdate` and an `appVersion` header that is not required |
| **Format** | JSON (UTF-8). Every response is `200`; errors are `{"error": {"message": "REST Error"}}`, unknown ids give `[]` or `[null]` |
| **CORS** | **No** `Access-Control-Allow-Origin` header (an `OPTIONS` request answers `Allow: GET, HEAD, POST, PUT, DELETE, OPTIONS` and nothing else). Browser apps need a proxy |
| **WAF / UA requirement** | None. Plain nginx, no edge cache; requests with any or no `User-Agent` succeed |
| **Last full verification** | 2026-09-19 |
| **Status** | ✅ verified (scheduled + final states, regular season and playoffs) · 🚧 live-state samples not yet captured (first chance: game day 2, 2026-09-20) |

## Overview

penny-del.org itself is a server-rendered TYPO3 site (`/spiele`, `/statistik/spieldetails/…`,
`/tabelle`): every page is HTML, `cache-control: private, no-store`, and nothing on it polls.
The old `del.org/live-ticker/*.json` files that community scrapers used are gone
(`301` to penny-del.org, then `404`). The JSON source is the backend of the league's mobile
app, found by grepping the APK: `https://del-services.appticore.com/2026/query.php`, an
Appticore installation of the framework that also powers the IIHF World Championship apps
(response header `X-IIHF-LastUpdate`, `noc` for team codes).

One URL serves everything. The app POSTs `application/x-www-form-urlencoded` fields, but the
same fields work as a **GET query string** (PHP `$_REQUEST`), which is what OpenScore uses:

```
GET /2026/query.php?os=android&lastUpdate=0&requestName={name}&{parameters}
```

`os=android` and `lastUpdate=0` go on every request; `requestName` selects the dataset. Bodies
are compact (a game 1.2 KB, its events 5 KB, the whole 364-game season 425 KB raw / 11 KB
gzipped; send `Accept-Encoding: gzip`). There are no cache validators and no `Cache-Control`;
change detection is the `lastUpdate` watermark described under [Quirks](#quirks--gotchas).

Samples in [`samples/`](samples/) were captured on **2026-09-19**, two days into the 2026-27
regular season (7 games played, none live), plus the 2026 playoffs for the series shape.

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Tournament | int; odd = regular season, the following even = that season's playoffs | `77` = 2026-27 regular season, `76` = 2026 playoffs, `75` = 2025-26 | [`tournamentList`](#get-requestnametournamentlist); older ids answer on any year path |
| Season year in the path | `/2026/` | | Fixed per app release; only changes what `tournamentList` lists (see quirks) |
| Game | `uniqueID` = `"{gameNumber}t{tournamentID}"`; every per-game read needs both halves (`tournamentId` + `gameNumber`, and the tournament must match). `gameNumber` alone is unique across tournaments and ends the website's URL slug | `4389t77` (`…/spieldetails/17092026_eisbaeren-berlin_gg_straubing-tigers_4389`) | `games[].uniqueID` |
| Team | 3-letter `noc` code | `EBB`, `RBM`, `MAN` | `teamList`; `homeTeam` / `guestTeam` on games |
| Player | int `memberID` (stable across seasons, shared by lineups, events and stats) | `2209` | `teamMembers`, `gameLineup.*PlayerMemberId`, `gameSituations.playerId` |
| Game day | `tournamentPhase` 1-52 in the regular season; playoffs use `gamePhase` `PR`/`QF`/`SF`/`GMG` + `seriesNumber` | `1` | `games[]` |
| Date/time | Unix seconds, UTC | `1789666200` = 2026-09-17T17:30Z (19:30 Berlin) | `games[].dateTime` |
| Date filter | `YYYY-MM-DD HH:MM:SS`, compared in **UTC** | `dateFrom=2026-09-18 00:00:00` | `games` without `tournamentId` |

**Teams 2026-27 (14):** AEV Augsburger Panther · BHV Pinguins Bremerhaven · EBB Eisbären
Berlin · FRA Löwen Frankfurt · IEC Iserlohn Roosters · ING ERC Ingolstadt · KEC Kölner Haie ·
KEV Krefeld Pinguine · MAN Adler Mannheim · NIT Nürnberg Ice Tigers · RBM EHC Red Bull München ·
STR Straubing Tigers · SWW Schwenninger Wild Wings · WOB Grizzlys Wolfsburg. `DRE` (Dresdner
Eislöwen) and `DEG` appear in older tournaments.

**Crests** are not served by the API (the app bundles them). penny-del.org has one SVG per
club at `https://www.penny-del.org/fileadmin/images/teams/2023/team_{siteId}.svg`
(`cache-control: max-age=2592000`), keyed by the website's own numeric team id:

| noc | siteId | noc | siteId | noc | siteId | noc | siteId |
|---|---|---|---|---|---|---|---|
| ING | 1 (use `team_1.png`, see quirks) | MAN | 2 | EBB | 3 | KEV | 5 (PNG only, see quirks) |
| STR | 6 | IEC | 7 | WOB | 8 | BHV | 9 |
| KEC | 11 | RBM | 12 | AEV | 13 | NIT | 14 |
| SWW | 15 | FRA | 44 | | | | |

## Discovery path

1. **Season / current tournament:** `requestName=tournamentList` → one row per tournament of
   the path's year; `tournamentID` 77 is the 2026-27 regular season (the playoff tournament
   is added around February). Cache for a day.
2. **Games:** `requestName=games&tournamentId=77` → the whole season (364 rows, 11 KB gzipped),
   or `requestName=games&dateFrom=… &dateTo=…` → any UTC window across tournaments. Each row
   has teams, `dateTime`, `progressCode`, `progressPerc`, both scores and `scoreByPeriod`,
   which is enough for a scoreboard.
3. **One game:** `requestName=games&tournamentId=77&gameNumber=4389` → the same row alone
   (1.2 KB). Add `requestName=gameSituations` (goals, penalties, goalie changes, shoot-out
   attempts), `gameResults` (per-period score and team stats), `gameLineup` (lines) and
   `gameOfficials`.
4. **Clock:** `requestName=gameTime` → `{progressCode, progressPerc, gameTime}` in 130 B.
5. **Standings:** `requestName=teamStandings&tournamentId=77`. **Rosters:**
   `requestName=teamMembers&tournamentId=77&noc=EBB`. **Leaders:**
   `requestName=statistics&tournamentId=77&type=points`.

Recommended poll interval for a live game: **15 s** on `games` + `gameSituations` with the
`lastUpdate` watermark (22 B when nothing changed), `gameTime` in between if a running clock is
wanted. The app itself polls `gameTime` every 30 s and the full game data every 90 s.
Regular-season games start at 19:30 Berlin on weekdays (17:30Z in September, 18:30Z in
winter), 14:00/16:30/19:00 at weekends.

## Endpoints

All endpoints are `GET …/query.php?os=android&lastUpdate=0&requestName=…` on the base URL;
only the extra parameters are listed. "Watermark" is the `X-IIHF-LastUpdate` response header
(`T` + Unix milliseconds); `T0`/`T1`/absent means the dataset does not participate.

### `GET ?requestName=tournamentList`

| | |
|---|---|
| **Purpose** | The tournaments the app shows for the path's year: the regular season and, once created, the playoffs |
| **Parameters** | none |
| **Sample** | [`samples/tournament-list.json`](samples/tournament-list.json) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark |

**Response shape** - array of `{tournamentID, name ("DEL 2026 - Regular season"), longName,
shortName ("HAUPTRUNDE"), displayName ("2026/2027"), category (11 regular season, 12 playoff),
categoryName, startDate, endDate (ISO, midnight `Z`), type (2), gamePhases (";"-joined game days
`1;2;…;52`, or `9;3;2;1` for playoffs), icerink, featured, defaultSection, sort, showInMenu,
logoUrl (an old telekomeishockey.de PNG that now redirects to an HTML page), …}`.

### `GET ?requestName=games`

| | |
|---|---|
| **Purpose** | The schedule/scoreboard rows: one tournament, a UTC window, or one game |
| **Parameters** | `tournamentId` - whole tournament; `dateFrom` + `dateTo` (`YYYY-MM-DD HH:MM:SS`, UTC, both required) - window across all tournaments, may be combined with `tournamentId`; `gameNumber` (with `tournamentId`) - one game |
| **Samples** | [`samples/games.season.json`](samples/games.season.json) (tournament 77, truncated to the first 21 of 364 rows) · [`samples/games.range.json`](samples/games.range.json) (2026-09-17 to 09-19, 7 finished) · [`samples/games.day.json`](samples/games.day.json) / [`samples/games.day.scheduled.json`](samples/games.day.scheduled.json) / [`samples/games.day.empty.json`](samples/games.day.empty.json) (the German days 2026-09-18, 09-20 and 09-19 as UTC windows: six finals, seven fixtures, nothing) · [`samples/games.playoffs.json`](samples/games.playoffs.json) (tournament 76, 54 rows with series fields) · [`samples/games.final.json`](samples/games.final.json) (4389, regulation) · [`samples/games.final-shootout.json`](samples/games.final-shootout.json) (4394, `progressCodeName` `GWS`) · [`samples/games.scheduled.json`](samples/games.scheduled.json) (4396) · [`samples/games.unknown.json`](samples/games.unknown.json) (`[null]`) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark `T1789798212000` on 2026-09-19 (advanced after the last final of the evening) |

**Response shape** - array of game rows:

```
dateTime                 Unix seconds, UTC
gameNumber, tournamentID, uniqueID ("4389t77")
tournamentPhase          game day 1-52 (regular season); 9 = pre-playoffs, 3/2/1 = QF/SF/final
gamePhase                "TAG" (regular season) | "PR" | "QF" | "SF" | "GMG" (final)
group                    "A" always
venue "", venueName      "Uber Arena"; venueLat/venueLong/venueFoursquareId mostly 0/""
homeTeam, guestTeam      noc codes
homeTeamScore, guestTeamScore
scoreByPeriod            "1:0|2:0|1:2|-:-|-:-"  P1|P2|P3|OT|GWS, "-:-" = not played
progressCode             see Game states; progressCodeName repeats it, except "OT" / "GWS" on
                         finals decided in overtime / the shoot-out
progressPerc             0 scheduled, 1-99 in play, 100 finished
gameTime, gameTimeElapsed   null on every finished/scheduled row seen; the clock lives in gameTime
spectators               attendance once final (13422)
playoff, seriesNumber, seriesHomeTeamScore, seriesGuestTeamScore, playoffReverseTeam
                         playoff series fields, 0 in the regular season (see below)
hot, liveCommentaryAvailable, guessAvailable, notificationsCount, *Guess*, checkInsCount,
homeBestPlayer, guestBestPlayer, highlights*, notif*, userGuess, userCheckIn, *Cramo, showTime,
homeBench "L", guestBench "R", *Colour, deleted, points, rank, videoActionsCount
                         app features; ignore
```

**Notes**

- `dateFrom`/`dateTo` need the full `YYYY-MM-DD HH:MM:SS` form; a bare date is ignored and the
  whole database comes back (364 rows). `dateFrom` alone returns everything from that instant.
- Rows are ordered by `dateTime`. A `gameNumber` that does not exist, or one under the wrong
  `tournamentId`, returns `[null]`, so guard the element, not just the array; the per-game
  routes without `tournamentId` are a `REST Error`.
- **Playoff series** (tournament 76): `seriesNumber` is the game's number within its series,
  `seriesHomeTeamScore`/`seriesGuestTeamScore` the series standing *after* the game from this
  row's home/guest perspective, `playoffReverseTeam` 1 when the home side is the series'
  second-named team. Series games that were never needed keep `progressCode` "Game Completed"
  with `-:-` in every period slot, 0:0 and `gameTime` 0 (4386, 4387 in the 2026 final): treat
  "completed with no period scores" as not played.
- `homeBestPlayer`/`guestBestPlayer` are `memberID`s of the players of the game once awarded.

### `GET ?requestName=gameTime`

| | |
|---|---|
| **Purpose** | The clock: 130 bytes with the progress code and elapsed seconds |
| **Parameters** | `tournamentId`, `gameNumber` |
| **Samples** | [`samples/game-time.final.json`](samples/game-time.final.json) (`3600`) · [`samples/game-time.final-shootout.json`](samples/game-time.final-shootout.json) (`3906`) · [`samples/game-time.scheduled.json`](samples/game-time.scheduled.json) (`0`) |
| **Last verified** | 2026-09-19 |
| **Cache** | none, no watermark header |

`{gameNumber, progressPerc, progressCode, gameTime, gameTimeElapsed, uniqueID}`. `gameTime`
is a **string of seconds elapsed in the game**: `3600` after regulation, `3906` for 5 minutes of
overtime plus six shoot-out attempts (each attempt advances the clock one second, see
`gameSituations`), `5025` = 83:45 for a playoff game ended in the second overtime. The app
shows `(60 - round(gameTime / 60)) % 20` as the minute left in the period. `gameTimeElapsed`
has been `null` in every state seen. Whether the value moves between polls while a period runs
or only at events is not yet known (no live sample).

### `GET ?requestName=gameSituations`

| | |
|---|---|
| **Purpose** | Game events: goals, penalties, goalkeeper changes, shoot-out attempts. The `Ereignisse` list of the website |
| **Parameters** | `tournamentId`, `gameNumber` |
| **Samples** | [`samples/game-situations.final.json`](samples/game-situations.final.json) (4389, 19 rows) · [`samples/game-situations.final-shootout.json`](samples/game-situations.final-shootout.json) (4394, `GWS` period) · [`samples/game-situations.final-playoff-ot.json`](samples/game-situations.final-playoff-ot.json) (4384, `OT` goal at 83:45) · [`samples/game-situations.scheduled.json`](samples/game-situations.scheduled.json) (`[]`) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark |

**Response shape** - array in game order:

```
uniqueID                 event id (27141468), increasing
noc                      team of the player
period                   "1" | "2" | "3" | "OT" | "GWS"
time                     "mm:ss" elapsed in the GAME, not the period ("21:47" is 01:47 of P2;
                         "65:00" opens the shoot-out; "83:45" is the second playoff overtime)
playerId, playerName (family name only), jerseyNumber
type                     "G" goal | "P" penalty | "I" info (goalkeeper in/out, saved shoot-out attempt)
actionCode1              "GOL" | "PTY" | "GOL_KPR_IN" | "GOL_KPR_OUT" | "" (shoot-out attempts)
actionCode2              goal: strength "EQ" | "PP1" | "PP2" | "SH1" | "SH2" | "EN" | "PS" | "GWS",
                         comma-joined when several apply ("EQ,EN");
                         penalty: minutes "02:00" | "05:00" | "10:00" | "20:00";
                         shoot-out attempt: "SCRD" (scored) | "SVD_GOL" (saved)
actionCode3              goal: "Assist: 44 Davidson B, 5 Reinke M" (number + family name +
                         initial; "" when unassisted); penalty: the offence in English
                         ("Too Many Players on the Ice", "Slashing", "Cross-Checking")
homeScore, guestScore    on goals only: the score after the goal
x, y                     0.0 here; coordinates come with gameSituationsExtended
```

**Notes**

- Both starting goalkeepers appear as `GOL_KPR_IN` at `00:00`; an empty net is `GOL_KPR_OUT`
  followed by `GOL_KPR_IN` when the goalie returns.
- A shoot-out is one `GOL` with `actionCode2` `GWS` credited to the deciding shooter at
  `65:00` (that is the goal in the score), then one row per attempt at 65:01, 65:02, … with
  `actionCode1` empty: `type` `G` + `SCRD` for a conversion, `type` `I` + `SVD_GOL` for a save.
  Only the `GOL` row changes the score.
- Playoff overtime is 20-minute periods until a goal, all labelled `OT`.
- The app's string table lists more codes not yet seen in a sample (may be incomplete):
  `GOLPSMISS` (penalty-shot miss), `GWG`, `AG`, `EA`, `ENG`, `SHG`, `SCRD`, `MISS_*`,
  `LOST_PCK`, and penalty offences up to `Match Penalty` / `Game Misconduct`.

### `GET ?requestName=gameSituationsExtended`

| | |
|---|---|
| **Purpose** | The same rows plus every shot with rink coordinates (the app's ice-rink view) |
| **Parameters** | `tournamentId`, `gameNumber` |
| **Sample** | [`samples/game-situations-extended.final.json`](samples/game-situations-extended.final.json) (127 rows: 108 shots, 9 penalties, 6 goals, 4 goalie changes) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark |

Shot rows: `type` `S`, `actionCode1` `SHOT`, `actionCode2` `SSP` (shot saved), `MISS_*`
(missed, per the string table), `actionCode3` the shooter's photo URL, `x`/`y` rink
coordinates in 0..1. Eight times the size of `gameSituations`; load on demand only.

### `GET ?requestName=gameResults`

| | |
|---|---|
| **Purpose** | Per-period score and team statistics |
| **Parameters** | `tournamentId`, `gameNumber` |
| **Samples** | [`samples/game-results.final.json`](samples/game-results.final.json) (P1-P3 + `TOT`) · [`samples/game-results.final-shootout.json`](samples/game-results.final-shootout.json) (`GWS` and `OT` rows too) · [`samples/game-results.final-playoff-ot.json`](samples/game-results.final-playoff-ot.json) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark (this one advanced overnight, `T1789812006000`, after the games one) |

**Response shape** - array of `{period ("1" | "2" | "3" | "OT" | "GWS" | "TOT"), homeScore,
guestScore, homeSog, guestSog, homeShotAttempts, guestShotAttempts, homeShotEfficiency,
guestShotEfficiency, homePpCount, guestPpCount, homePpg, guestPpg, homePpEfficiency,
guestPpEfficiency, homeShg, guestShg, homePim, guestPim, homeBully, guestBully (face-off
wins), homeSsg, guestSsg (saves), homeTpp, guestTpp ("05:11", power-play time), uniqueID}`.

**Notes**

- Only the `TOT` row carries the statistics; per-period rows have the score and zeros.
- Rows are **not** in period order (`GWS` came before `OT`); sort by period key.
- Empty for a scheduled game.

### `GET ?requestName=scoreboard`

| | |
|---|---|
| **Purpose** | Score, progress and the `TOT` stats of one game plus both teams' season ranks in five categories |
| **Parameters** | `tournamentId`, `gameNumber` |
| **Samples** | [`samples/scoreboard.final.json`](samples/scoreboard.final.json) · [`samples/scoreboard.final-shootout.json`](samples/scoreboard.final-shootout.json) (`progressCodeName` `GWS`) · [`samples/scoreboard.scheduled.json`](samples/scoreboard.scheduled.json) (`gamestats` null) |
| **Last verified** | 2026-09-19 |
| **Cache** | none, watermark `T0` |

`{gameNumber, homeTeam, guestTeam, homeTeamScore, guestTeamScore, progressCode,
progressCodeName, progressPerc, gamestats (the gameResults TOT row, null before the game),
penalties [] (empty on all finals; presumably the penalties currently being served while live),
stats_gk[], stats_pen[], stats_se[], stats_pp[], stats_pk[] (one row per team, season totals
and rank)}`. `gameDetailTeamStats` returns the same five season rows keyed `homeTeam`/`guestTeam`.

### `GET ?requestName=gameLineup`

| | |
|---|---|
| **Purpose** | Both teams' lines, side by side |
| **Parameters** | `tournamentId`, `gameNumber` |
| **Sample** | [`samples/game-lineup.final.json`](samples/game-lineup.final.json) (22 rows) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark |

**Response shape** - array of slots, each with a home and a guest player:

```
lineNumber       0 = goalkeepers, 1-4 = lines
linePosition     goalkeepers: 1 starter, 2 backup
                 lines: 1-2 defenders (left, right), 3-5 forwards (left wing, centre, right wing)
homePlayerMemberId, homePlayerName ("LaFontaine Jack", family name first), homePlayerNumber,
homePlayerImageUrl, homePlayerSmallImageUrl
guestPlayerMemberId, guestPlayerName, guestPlayerNumber, guest…ImageUrl
uniqueID         0
```

An unused slot has `…MemberId` 0, name `""`, number 0 (a team dressing 7 defenders and 11
forwards leaves holes in line 4). Positions are implied by the slot, not stated; `teamMembers`
has the roster position. Empty (`[]`) for a scheduled game; when lineups are published before
face-off is not yet known.

### `GET ?requestName=gameOfficials`

| | |
|---|---|
| **Purpose** | Referees and linesmen |
| **Parameters** | `tournamentId`, `gameNumber` |
| **Sample** | [`samples/game-officials.final.json`](samples/game-officials.final.json) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark |

Array of `{officialFamilyName, officialGivenName, officialPosition ("Ref1" | "Ref2" | "Lin1"
| "Lin2"), officialNationality "", officialImageUrl null, officialSpeed/Distance null}`.
Empty before the game.

### `GET ?requestName=teamList`

| | |
|---|---|
| **Purpose** | The tournament's teams |
| **Parameters** | `tournamentId`; `noc` is accepted but still returns all 14 |
| **Sample** | [`samples/team-list.json`](samples/team-list.json) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark |

Array of `{noc, name ("Eisbären Berlin"), homeJersey "", awayJersey "", tournamentID, uniqueId
("EBBt77"), notif*, cheersCount, userCheer}`. No city, arena or colours; arenas come from
`games[].venueName`.

### `GET ?requestName=teamStandings`

| | |
|---|---|
| **Purpose** | The table |
| **Parameters** | `tournamentId` (regular seasons only; a playoff tournament returns `[]`) |
| **Samples** | [`samples/team-standings.json`](samples/team-standings.json) (2026-27 after one game) · [`samples/team-standings.2025-26.json`](samples/team-standings.2025-26.json) (final 2025-26, 52 games) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark |

Array of `{noc, rank, gamesPlayed, gamesWon, gamesLost, otw, otl, goalsFor, goalsAgainst, gdf
(string, "-3"), points, pointsPerGame, phase (9), group "A", tournamentID, uniqueID}`. Three
points for a regulation win, two for an overtime/shoot-out win (`otw`), one for an
overtime/shoot-out loss (`otl`); `gamesWon`/`gamesLost` count regulation results only.

### `GET ?requestName=teamMembers`

| | |
|---|---|
| **Purpose** | Roster with season stats; or one player |
| **Parameters** | `tournamentId`, `noc`; `memberId` narrows to one player (the head coach row comes along, and `noc` may then be left out); without either the whole league (300 KB). A playoff tournament id has its own rosters |
| **Samples** | [`samples/team-members.team.json`](samples/team-members.team.json) (EBB, 27 rows) · [`samples/team-members.team.STR.json`](samples/team-members.team.STR.json) (STR, the other side of game 4389) · [`samples/team-members.player.json`](samples/team-members.player.json) (2209 + coach) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark (per team, `T1789804817000`) |

Array of `{memberID, noc, familyName, givenName, scoreboardName, jerseyNumber, position ("GK"
| "D" | "F" | "HED_COA"), shoots ("L" | "R"), captain ("" so far), birthday (ISO `Z`), birthCountry
(actually the birthplace, "Mississauga, ON"), nationality ("CAN"), height ("1.91m"), weight
("95kg"), homeClub, imageUrl, smallImageUrl, gamesPlayed, statistics (";"-joined: skaters
`points;goals;assists;pim;shootingPct`, goalkeepers `savePct;goalsAgainst;toi;…;shutouts`),
statisticsOrder, plusMinus, timeOnIceTotal/PP/SH (seconds), rank*, uniqueID, social ids}`.

### `GET ?requestName=statistics`

| | |
|---|---|
| **Purpose** | League leaders (players) and team rankings |
| **Parameters** | `tournamentId`, `type` = `points` \| `goals` (30 players, full stat rows) \| `gk` \| `pp` \| `pk` \| `pen` \| `se` (14 team rows) |
| **Samples** | [`samples/statistics.points.json`](samples/statistics.points.json) · [`samples/statistics.goals.json`](samples/statistics.goals.json) · [`samples/statistics.gk.json`](samples/statistics.gk.json) · [`samples/statistics.pp.json`](samples/statistics.pp.json) · [`samples/statistics.pk.json`](samples/statistics.pk.json) · [`samples/statistics.pen.json`](samples/statistics.pen.json) · [`samples/statistics.se.json`](samples/statistics.se.json) |
| **Last verified** | 2026-09-19 |
| **Cache** | none; watermark |

Player rows are the `teamMembers` row plus `rank, assists, ppg, shg, shots, faceoff*,
plus/minus, pen*, pimAvg, svs/sog/ga` (goalkeeper fields), and `birthday` as **epoch
milliseconds** here (ISO string in `teamMembers`). Team rows: `gk` `{sog, goals, saves,
efficiency}`, `pp` `{advantage, goals, efficiency}`, `pk` `{disadvantage, goals, efficiency}`,
`pen` `{twoMin, fiveMin, tenMin, totalMin, mp, gm}`, `se` `{shots, goals, efficiency}`.

### Other verified request names (lower priority)

| `requestName` | Parameters | Sample | Notes |
|---|---|---|---|
| `gameDetailTeamStats` | `tournamentId`, `gameNumber` | [game-detail-team-stats.final.json](samples/game-detail-team-stats.final.json) | `{homeTeam: {pp, se, gk, pen, pk}, guestTeam: {…}}`, the same season rows as `scoreboard` |
| `gameDetailPlayerComparison` | `tournamentId`, `gameNumber` | [game-detail-player-comparison.final.json](samples/game-detail-player-comparison.final.json) | scoring leader and best goalkeeper per side (full player rows), `*BestPlayerOfTheGame` null on 4389 |
| `gameDetailTeamRankHistory` | `tournamentId`, `gameNumber` | [game-detail-team-rank-history.final.json](samples/game-detail-team-rank-history.final.json) | the two clubs' final ranks in past seasons (`seasonYear` 2016 for tournament 10) |
| `historicalData` | `tournamentId`, `memberID` | [historical-data.json](samples/historical-data.json) | a player's per-season records (`records[]`) |
| `bestPlayerGame` | `tournamentId`, `memberId` | [best-player-game.json](samples/best-player-game.json) | `{mostGoalsGame: {game, …}, …}` |
| `whatsNew` | `tournamentId` | not sampled | app news feed; `text` is a `<script>` redirect to `penny-del.org/appnews/{id}` |
| `playOff`, `calendar`, `actualAppVersion`, `finalRanking`, `historicalGames` | | [error.json](samples/error.json) | `REST Error` / `[]` on 2026-09-19; `historicalGames` needs `homeTeam`+`guestTeam` (unverified) |

Write-type names in the app (`deviceReg`, `notification*`, `bid`, `checkIn`, `cheer`) are not
touched.

## Game states

`progressCode` values. Observed: `Scheduled`, `Game Completed`. The rest come from the app's
string table (`game.progresscode.code.*`) and the IIHF apps built on the same backend; not yet
seen in a DEL sample:

| `progressCode` | `progressPerc` | Meaning | Core `GameState` |
|---|---|---|---|
| `Scheduled` | 0 | not started | `SCHEDULED` |
| `Pre Game`, `Pre Game Ended` | 0 | warm-up | `PRE_GAME` |
| `Period 1` / `Period 2` / `Period 3` | 1-99 | in play | `LIVE` |
| `Period 1 Ended` / `Period 2 Ended` / `Period 3 Ended` | 1-99 | intermission | `INTERMISSION` |
| `Overtime`, `Overtime Ended` | 1-99 | overtime, break before the shoot-out | `LIVE` / `INTERMISSION` |
| `Game Winning Shots`, `Game Winning Shots Ended` | 1-99 | shoot-out | `LIVE` |
| `Game Completed` | 100 | final; `progressCodeName` `OT` / `GWS` says how | `FINAL` |
| `Forfeit` | 100 | awarded | `FINAL` |

`progressPerc` is the app's own rule: `0` scheduled, `1-99` live (the game screen opens on the
ticker tab), `100` finished. Postponements are not represented by a code; expect a moved
`dateTime` (or `deleted` 1, never seen).

## Quirks & gotchas

- **One URL, `requestName` selects the dataset.** GET and POST are equivalent. Unknown or
  incomplete requests do not fail: a wrong `requestName` or a missing required parameter gives
  `{"error": {"message": "REST Error"}}` with `200`, an unknown `gameNumber` gives `[null]`, a
  scheduled game's detail routes give `[]`.
- **`lastUpdate` is a whole-dataset watermark, not a row delta.** Send back the previous
  response's `X-IIHF-LastUpdate` value (with or without the `T`; the app strips it) and the
  server answers `[]` (22 B) when the dataset has not changed since, otherwise the **complete**
  dataset again. Datasets are per request name and parameter set (the season list, one game's
  situations, one team's roster each have their own). `gameTime` and `scoreboard` send no
  usable watermark (`T0`/absent): always poll them with `lastUpdate=0`. Treat `T0` as "no
  watermark", never send it back.
- **No HTTP caching at all**: no `Cache-Control`, `ETag` or `Last-Modified`, nginx straight to
  PHP. Poll politely; nothing upstream absorbs bursts. Bodies are uncompressed unless
  `Accept-Encoding: gzip` is sent (40x smaller on the season list).
- **The `/2026/` path segment is the app release, not a data partition.** `/2023/` … `/2026/`
  all serve every tournament (`games&tournamentId=75` works on `/2026/`); only `tournamentList`
  differs, listing that year's regular season and, once it exists, its playoffs
  (`/2025/` lists 75 and 76). `/2027/` answers `[]` today. Expect a new segment with each
  season's app update; bump the base URL when `tournamentList` on the old one stops adding
  the playoffs.
- **Times are UTC Unix seconds**; the website shows Europe/Berlin. `dateFrom`/`dateTo` compare
  in UTC and need `HH:MM:SS`.
- **Event `time` is game-elapsed** (`21:47` = P2 01:47, `65:00` = shoot-out, `83:45` = second
  playoff overtime), not period time. `gameTime` is the same clock in seconds.
- **Period slots**: `scoreByPeriod` always has five (`P1|P2|P3|OT|GWS`); `gameResults` rows come
  unordered and only `TOT` carries statistics.
- **Team rows have no crest, city or arena**; crests come from the website (table above).
  Ingolstadt's `team_1.svg` is drawn small on an A4 artboard (`viewBox 0 0 841.9 595.3`) and
  renders as a dot; `team_1.png` (1020 × 1130) next to it is the usable one. Krefeld's is the
  one club without an SVG: penny-del.org serves a processed PNG at a hashed
  path (`/fileadmin/_processed_/0/c/csm_team_5_a685a11e1e.png` on 2026-09-19, 200 × 220) that
  will change whenever the site re-renders it.
- **Names**: `playerName` in events and `familyName` in rosters are the family name only;
  assists are `"Assist: 44 Davidson B, 5 Reinke M"` (number, family name, given initial), so
  resolve assists through the roster by jersey number, not by parsing the text. Lineup names
  are `"Family Given"`.
- **Two date formats for one field**: `birthday` is an ISO string in `teamMembers` and epoch
  milliseconds in `statistics`.
- **Player photos** on `del-support-services.appticore.com` are plain `http://` URLs (the
  `crop_640x580/` and `resize_144x144/` prefixes select a size). Link, do not mirror.
- **Off-season**: the playoff tournament disappears from the next year's path; `teamStandings`
  for a playoff id is `[]`, `finalRanking` was empty on 2026-09-19.
- **No live sample yet.** How `gameTime` advances between events, whether `Period N` codes
  and `gameTimeElapsed` appear as the string table suggests, when `gameLineup` fills, and
  what `scoreboard.penalties` holds while a penalty runs are all to be confirmed on
  2026-09-20 (seven games, 12:00Z / 14:30Z / 17:00Z; `venueName` is still empty on them).

## Out of scope

- penny-del.org HTML (schedule, game centre, tables): server-rendered, `no-store`, and
  everything it shows is in the API. Only the crest SVGs are used from the site.
- The site's `/startseite?type=9876` route (the only JSON on penny-del.org): login status, not data.
- Wisehockey's real-time analytics platform (announced by the league) - partner API, not
  public.
- Write routes of the app (device registration, push subscriptions, guesses, check-ins,
  cheers) and the news feed (`whatsNew`).
- Highlight videos (`highlightsVimeoURL`, `videoActionsCount`, `liveTVRedirect` on the site)
  point at MagentaSport; link out at most.

## Core model mapping

| Core concept | Source request | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `tournamentList` | `tournamentID`, `displayName`, `category` 11/12, `startDate`/`endDate` | Regular season and playoffs are separate tournaments (odd/even ids); stage = `gamePhase` |
| Game (id, teams, start time) | `games` | `uniqueID`, `homeTeam`/`guestTeam` (`noc`), `dateTime`, `venueName` | Core id = `uniqueID` (`4389t77`); a day is one UTC window covering the German date; never-needed playoff series games are dropped from listings |
| GameState | `games` / `gameTime` | `progressCode`, `progressPerc`, `progressCodeName` (`OT`/`GWS`) | Table above; live codes unverified |
| Score by period | `games` | `scoreByPeriod` (`P1|P2|P3|OT|GWS`) | Or `gameResults` rows sorted by period |
| Clock / period | `gameTime` | `gameTime` (elapsed seconds), `progressCode` | Period from the code, period time = `gameTime - periodStart`; no running flag |
| GameEvent | `gameSituations` (+ `teamMembers` for names) | `type`, `actionCode1..3`, `period`, `time`, `playerId`, `noc`, `homeScore`/`guestScore` | Goals with strength and assists (assists resolved by jersey number through the roster), penalties with minutes and offence, goalie changes, shoot-out attempts; shots with coordinates from `gameSituationsExtended` in `events()` |
| Lineups | `gameLineup` (+ `teamMembers`) | `lineNumber`, `linePosition`, `*PlayerMemberId`, `*PlayerName`, `*PlayerNumber` | Full lines and pairings (`LINE_GROUPS`); position implied by the slot, given names from the roster (the slot's `"Family Given"` cannot be split, both halves can be several words); empty before the game |
| Team | `teamList` (+ website crest) | `noc`, `name` | No city/arena/colours; arena via `games[].venueName`; crest via the site id table |
| Player | `teamMembers` | bio and season stats | Full; `birthCountry` is the birthplace |
| Standings | `teamStandings` | `rank`, `points`, `otw`/`otl`, `gdf` | Regular season only; three-point system |
| Team stats (game) | `gameResults` `TOT` / `scoreboard.gamestats` | `*Sog`, `*ShotAttempts`, `*Ssg`, `*Pim`, `*Bully`, `*PpCount`, `*Ppg`, `*Tpp` | `Game.stats`: `shotsOnGoal`, `shotAttempts`, `saves`, `faceoffsWon`, `powerPlays` (`0/3`), `powerPlayTime`, `penaltyMinutes`; read once the game has started |
| Officials | `gameOfficials` | names + `Ref1/Ref2/Lin1/Lin2` | |

## TODO

- [ ] Capture `Pre Game`, `Period N`, `Period N Ended`, `Overtime` and `Game Winning Shots`
      samples of `games`, `gameTime`, `gameSituations`, `scoreboard` and `gameLineup` on
      2026-09-20 (game day 2, seven games, first face-off 14:00 Berlin = 12:00Z).
- [ ] Confirm whether `gameTime` ticks between events or only at them, and what
      `gameTimeElapsed` is.
- [ ] Watch `tournamentList` around February 2027 for the 2027 playoff tournament id (78?).

## Changelog

| Date | Change |
|---|---|
| 2026-09-19 | Initial mapping from the app backend (APK bundle-grep). 17 request names verified, 47 samples captured (2026-27 regular season, 2026 playoffs). `DelProvider` built on them the same day: game ids are `uniqueID`. |
