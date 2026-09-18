# Serie A API (Lega Serie A / Deltatre SDP)

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | Italy |
| **Official site** | https://www.legaseriea.it (Lega Nazionale Professionisti Serie A) |
| **Base URL** | `https://api-sdp.legaseriea.it/v1/serie-a/football/` (alias of `https://seriea-api.prd.sdp.deltatre.digital/v1/…`, which also answers) |
| **Auth** | None — public endpoints used by legaseriea.it's own match-centre widgets |
| **Format** | JSON (UTF-8). Every response carries `apiCallRequestTime` (origin timestamp, UTC) |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *`; `OPTIONS` preflight `204`, `Access-Control-Max-Age: 86400`. Only the `X-Expose-Header` request header is allowed, so keep requests "simple" (no custom headers) |
| **WAF / UA requirement** | None. Akamai in front; works with no `User-Agent` and no `Accept` header |
| **Last full verification** | 2026-09-11 |
| **Status** | ✅ verified (pre-match + finished states) · 🚧 live-state samples not yet captured |

## Overview

legaseriea.it is a Next.js site whose match centre is a set of "Football Widgets"
from **Deltatre's Sports Data Platform (SDP)**. The widgets talk to a small REST API
whose data is Opta/Stats Perform (`providerId: "opta:…"`) merged with Lega Serie A's own
competition system ("kama": matchdays, referees, some players). Not related to the
Pulselive "SDP" behind the Premier League despite the name.

What it gives us: fixtures with UTC kick-offs, a live match header (score, minute,
added time, phase), an event feed (goals with assists, cards, substitutions, shots,
saves, corners, VAR-style labels), full line-ups with formation and pitch
coordinates, referees, team/player stats down to tracking metrics, a win-probability
"momentum" series, standings with home/away splits, and 41 seasons of history back to
1986/87. There is **no seconds-level clock** (`time` is whole minutes) and no
WebSocket/SSE — the site polls every 60 s.

All samples in [`samples/`](samples/) were captured on **2026-09-11** (season 2026/27,
between Matchday 3 and 4). See [`samples/_meta.md`](samples/_meta.md).

## Identifiers

Every id is a namespaced string of the form `serie-a::Football_<Type>::<32 hex>`. The
**full string is required** in URLs — passing only the hex part returns `200` with an
all-`null` body, not `404`. Ids are stable across seasons (Inter has the same
`Football_Team` id in every season). `providerId` fields carry the upstream ids
(`opta:Team:…`, `kama:Match:38326`) for cross-referencing.

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Project / sport | fixed path segments | `serie-a` / `football` | constants (`SDP_PROJECT` in the site config) |
| Competition | `serie-a::Football_Competition::<hex>` | `…::ec93b94f74294dc98ab5bcfd67fc0d88` (Serie A) | `matchdays.competition.competitionId`, any match `matchSet.competitionId` |
| Season | `serie-a::Football_Season::<hex>` | `…::ed7fdc2a3e7b408b942ec177b7b956b5` (2026/27) | `GET /competitions/{compId}/seasons` → `seasons[]` (newest first) |
| Matchday ("matchSet") | `serie-a::Football_MatchDay::<hex>` | `…::59bb8da36eca4ccc9296d6296c2d4654` (Matchday 1) | `GET /seasons/{id}/matchdays` |
| Match | `serie-a::Football_Match::<hex>` | `…::8f81947dbf6149b7b2801dbf1fa8d68c` (Udinese–Lazio, MD 3) | match lists, field `matchId`. Same hex appears in site URLs `/serie-a/match/<hex>/<slug>` |
| Team | `serie-a::Football_Team::<hex>` | `…::b7421caff23448c49134fa4f9095ee09` (Inter) | standings `teams[].teamId`, match `home.teamId` |
| Player | `serie-a::Football_Player::<hex>` | `…::75e8a5f9f7c54239aaf5c7620816ca7a` | line-ups, events, roster |
| Official (coach) | `serie-a::Football_Official::<hex>` | | `lineups.home.staff[]` |
| Referee | `serie-a::Football_Referee::<hex>` | | `matchfacts.referees[]` |
| Stadium | `serie-a::Football_Stadium::<hex>` | | match `stadiumId`, `matchfacts.stadium` |
| Event | `serie-a::opta::Football_<Kind>::<opta id>` | `serie-a::opta::Football_Goal::ejzx…-43-90ff…` | `feed.events[].eventId` |

Other competitions run by the Lega (Coppa Italia, Supercoppa, Primavera) are
different `Football_Competition` ids under the same project; the site's CMS
(`dapi.legaseriea.it/v2/content/en-gb/competitions/<slug>`) is where the site
resolves them. Not verified here.

**Season ids 2026/27 → 2024/25:** `ed7fdc2a…` / see [`competition-seasons.json`](samples/competition-seasons.json)
for all 41 (`seasonName` is `"2026/2027"` style; `startDateUtc`/`endDateUtc` are `null`).

## Discovery path

1. **Season:** `GET /competitions/serie-a::Football_Competition::ec93b94f74294dc98ab5bcfd67fc0d88/seasons`
   → `seasons[0].seasonId` is the current season (list is newest first). Cache it.
2. **Matchdays:** `GET /seasons/{seasonId}/matchdays` → 38 entries with
   `matchdayStatus` (`Fixture` / `Playing` / `Played`) and `startDateUtc`/`endDateUtc`.
   The one with `Playing` is the current round.
3. **Fixtures:** `GET /seasons/{seasonId}/matches?matchDayId={matchSetId}` (10 matches)
   or the whole season `GET /seasons/{seasonId}/matches` (380 matches, ~1.2 MB —
   avoid polling this). Each match has `matchDateUtc`, `status`, `phase`, `time`,
   `additionalTime`, scores, teams, stadium, broadcasters.
4. **Live score/clock:** `GET /seasons/{seasonId}/matches/{matchId}/header` (4 KB).
5. **Events:** `GET /seasons/{seasonId}/match/{matchId}/summary` (key events only) or
   `…/matches/{matchId}/feed` (every shot/corner/save too). Note `match` vs `matches`
   in the path — they are different resource trees.
6. **Line-ups:** `GET /seasons/{seasonId}/matches/{matchId}/lineups` (empty arrays
   until published, ~1 h before kick-off).
7. **Standings:** `GET /seasons/{seasonId}/standings` (overall) or
   `…/standings/overall` (overall + home + away).
8. **Squad:** `GET /teams/{teamId}/roster`. **Season stats:** `GET /seasons/{seasonId}/stats/players?…`.

Recommended poll interval for live matches: **40–60 s** on `header` + `summary`.
Akamai serves `private, max-age=40` responses from its edge cache for the full 40 s
(`server-timing: cdn-cache; desc=HIT`, identical `apiCallRequestTime`), so polling
faster than 40 s returns the same body. The site's widgets default to 60 s. No
`ETag`/`Last-Modified`, so conditional requests are not possible.

## Endpoints

All paths are relative to `https://api-sdp.legaseriea.it/v1/serie-a/football/`.
Optional `?locale=it-IT` (default `en-US`; `en-GB` also accepted) translates labels
(`roleLabel`, `label`, `description`, stat labels) — ids and structure are unchanged.

The site's client is a URL-builder proxy: every method name becomes a path segment
verbatim, which is why the path casing is inconsistent (`matchpreview` and
`matchPreview` are both used by the site and both work; `advancedMatchEvents` is
camel-case).

### `GET /competitions/{competitionId}/seasons`

| | |
|---|---|
| **Purpose** | All seasons of a competition, newest first |
| **Sample** | [`samples/competition-seasons.json`](samples/competition-seasons.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

**Response shape:** `{ seasons: [ { seasonId, seasonName, competitionId, providerId
("kama:Season:278"), name, officialName, shortName, acronymName, startDateUtc: null,
endDateUtc: null, imagery } ], apiCallRequestTime }`. 41 seasons, 2026/2027 back to 1986/1987.

### `GET /seasons/{seasonId}`

| | |
|---|---|
| **Purpose** | One season object (same shape as above under `season`) |
| **Sample** | [`samples/season.json`](samples/season.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

### `GET /seasons/{seasonId}/matchdays`

| | |
|---|---|
| **Purpose** | The 38 rounds with their date window and status |
| **Sample** | [`samples/matchdays.json`](samples/matchdays.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=900` |

**Response shape:** `{ competition, matchdays: [ { matchSetId, providerId
("kama:MatchDay:4903"), name ("Matchday 1"), shortName, seasonId, competitionId,
stageId, roundId: null, index: null, startDateUtc, endDateUtc, matchdayStatus } ] }`.
`startDateUtc`/`endDateUtc` are day-granular (`…T00:00:00Z`) and the window is
exclusive of the last day (MD 1 = 22–24 Aug is `2026-08-22` → `2026-08-24`).

**Observed enum values** — `matchdayStatus`: `Fixture`, `Playing`, `Played`
*(observed, may be incomplete)*.

### `GET /seasons/{seasonId}/matches`

| | |
|---|---|
| **Purpose** | Match list. Unfiltered = all 380 matches of the season |
| **Parameters** | `matchDayId` — one round · `relevantTeamIds` — comma-separated team ids · `matchStatus` — `All` (default), `Upcoming`, `Live`, `Finished`, and per the client enum also `Postponed`, `Canceled`, `Lineup`, `Tactical`, `Suspended`, `Abandoned` (unverified) |
| **Sample** | [`samples/matches.json`](samples/matches.json) (season list, **truncated to the first 12 of 380**) · [`samples/matches-matchday.json`](samples/matches-matchday.json) (MD 1, complete) · [`samples/matches-team.json`](samples/matches-team.json) (Inter, `matchStatus=All`, **truncated to 6 of 38**) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=60` |

**Response shape:** `{ competition, matches: [ … ], apiCallRequestTime }`. Each match:

- ids: `matchId`, `providerId` (`kama:Match:38326`), `seasonId`, `matchSet` (the
  matchday object), `stageId`/`roundId`/`groupId` (`null` for the league)
- time: `matchDateUtc` (`2026-09-07T18:45:00Z`), `matchDateLocal`
  (`2026-09-07T20:45:00`, Europe/Rome), `localTimeUtcOffset` (`+02:00`),
  `isUnknownKickOffTime`
- state: `status`, `providerStatus`, `phase`, `scheduleStatus`, `time` (minute),
  `additionalTime`
- score: `homeScorePush`/`awayScorePush` (live push score) and
  `providerHomeScore`/`providerAwayScore` (Opta) — identical in every finished
  sample; `providerPenaltyScoreHome/Away`, `aggregate`, `winReason`, `winTeamId`,
  `previousLegId`/`previousLegsResult` (cups)
- `home`/`away`: team object (`teamId`, `providerId`, `shortName`, `officialName`,
  `acronymName`, `mediaName`, `mediaShortName`, `imagery.teamLogo` …) plus
  `scores[]` (goal scorers with player object + `events[]`), `redCards[]`,
  `officialRedCards[]`, and season form counters `wins/draws/losses/homeWins/…`
- venue: `stadiumId`, `stadiumName`, `cityName`
- `editorial`: broadcasters (`broadcastLive: "DAZN"`, `broadcasterNational1..3`),
  `highlightsUrl`, `ticketsUrl`, TV director/delegates

Matches within a response are in kick-off order.

### `GET /seasons/multipleSeasonMatches`

| | |
|---|---|
| **Purpose** | Matches across several seasons, filtered by teams — the site uses it for head-to-head/form |
| **Parameters** | `seasonIds` (comma-separated), `relevantTeamIds` (comma-separated), `matchStatus` |
| **Sample** | [`samples/multiple-season-matches.json`](samples/multiple-season-matches.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=59` (not `private`) |

Same match objects as above under `matches[]`. With one season and two teams it
returns each team's finished matches (union, not only mutual meetings).

### `GET /seasons/{seasonId}/matches/{matchId}/header`

| | |
|---|---|
| **Purpose** | **The live-score object.** Score, minute, phase, scorers, red cards, venue, matchday, competition |
| **Sample** | [`samples/match-header.pre.json`](samples/match-header.pre.json) · [`samples/match-header.final.json`](samples/match-header.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

**Response shape:** the match object above minus `editorial`, plus `stadiumImagery`,
`attendance` (`null` so far), `groupName`/`roundName`, and `competition` (with
`seasonProviderId`). `home.scores[]` lists scorers with `events[] { type ("goal"),
time, additionalTime, relatedPlayerId (assist), phase }`.

**Pre-match:** `status: "UPCOMING"`, `phase: "PRE_MATCH"`, `time: 0`, scores `null`,
`winReason: ""`. **Final:** `status: "FINISHED"`, `phase: "FULL_TIME"`, `time: 90`,
`additionalTime: 6`, `winReason: "RegularTime"` / `"Draw"`, `winTeamId` set (or `null`
for a draw).

### `GET /seasons/{seasonId}/match/{matchId}/summary`

| | |
|---|---|
| **Purpose** | Key events timeline: goals, cards, substitutions, period starts/ends |
| **Sample** | [`samples/match-summary.pre.json`](samples/match-summary.pre.json) · [`samples/match-summary.final.json`](samples/match-summary.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

**Response shape:** `{ matchId, name ("Inter vs Udinese"), officialName, providerId,
status, providerStatus, phase, time, additionalTime, group, subLeague, events: [ … ] }`.
Events are **newest first**. Each event: `type`, `label`, `description` (English
sentence), `eventId`, `timeStamp` (UTC, wall-clock), running score
(`homeScorePush`, `awayScorePush`, `providerHomeScore`, `providerAwayScore`,
penalty scores), and either `home` or `away` (the other is `null`) holding
`{ team, time, additionalTime, phase, player }`. `player` includes the acting player
and — for goals — `assistPlayerId`/`assist*` fields; for substitutions the
`related*` fields are the player going off. Goals also carry `xPosition`/`yPosition`
(pitch %), `xg`, `shotOrigin` (`null` so far). Period events (`first-half`,
`half-time-break`, …) have no side.

### `GET /seasons/{seasonId}/matches/{matchId}/feed`

| | |
|---|---|
| **Purpose** | Full event feed (summary events + shots, saves, corners, man of the match) |
| **Parameters** | `page` — 30 events per page, newest first. **Omit `page` to get everything on one page** (68 events, `totalPages: 1`). `pageNumElement` is ignored |
| **Sample** | [`samples/match-feed.pre.json`](samples/match-feed.pre.json) · [`samples/match-feed.final.json`](samples/match-feed.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

**Response shape:** as `summary` plus `pagination { totalPages, currentPage, isLastPage }`.
Shot events add `distance`, `isPenaltyBox`, `isFromOpenPlay`, `playOrigin`,
`evaluation`, `goalPrevented` (on saves) — mostly empty/`false` in the sample.

**Observed event `type` values** (`summary` + `feed`): `first-half`,
`half-time-break`, `second-half`, `end-second-half`, `goal`, `yellow-card`,
`substitution`, `attempt-missed`, `attempt-saved`, `corner`, `man-of-the-match`.
The site's translation table also lists: `own-goal`, `penalty`, `penalty-missed`,
`red-card`, `yellow-red-card`, `var`, `offside`, `foul`, `hand-ball`,
`free-kick-won`, `free-kick-lost`, `team-news`, `kick-off`, `start`, `delay`,
`half-time-summary`, `post-match-summary`, `extra-time-half-time`,
`end-extra-time-1/2`, `end-1` … `end-5`, `second-half-start`
*(observed, may be incomplete; the extra names are from the client bundle and unverified)*.
Goal sub-types (`G` goal / `PG` penalty / `OG` own goal) are only exposed in
`matchpreview.lastMatches[].scores[].type` (with `periodId` and `timeMin`); the
header's `scores[].events[].type` is plain `goal`. Whether the summary uses distinct
`own-goal`/`penalty` types is unverified.

### `GET /seasons/{seasonId}/matches/{matchId}/lineups`

| | |
|---|---|
| **Purpose** | Starting XI, bench, staff, formation, kit colours, pitch positions |
| **Sample** | [`samples/match-lineups.pre.json`](samples/match-lineups.pre.json) · [`samples/match-lineups.final.json`](samples/match-lineups.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

**Response shape:** `{ matchId, providerId, pitchSizeX, pitchSizeY, home, away }`. Each
side: team fields, `tacticalFormation` (`"3-5-2"`, `"Unknown"` before publication),
`playerShirtMainColor`/`SecondaryColor`/`NumberColor` (hex), `fielded[]`,
`benched[]`, `staff[]`. Player: `playerId`, `providerId`, `bibNumber` (string),
`role` (1 GK, 2 DF, 3 MF, 4 FW) + `roleLabel`, names (`mediaFirstName`,
`mediaLastName`, `shirtName`, `shortName`, `displayName`), `nationality` +
`nationalityIsoCode`, `isCaptain`, `isGoalkeeper`, `isOneBookingAway`,
`isSuspended`, `events[]` (goals/cards/subs for that player), `tacticalXPosition`/
`tacticalYPosition` (0–1 strings, formation slot), `averageXPosition`/`averageYPosition`
(`null`). Staff: `staffId`, `role` 101 Head Coach / 102 Assistant Coach.

Before publication the arrays are empty (`fielded: []`, `staff: []`), not `null`.

### `GET /seasons/{seasonId}/match/{matchId}/matchfacts`

| | |
|---|---|
| **Purpose** | Referees (incl. VAR), stadium, city, weather/environment |
| **Sample** | [`samples/match-matchfacts.pre.json`](samples/match-matchfacts.pre.json) · [`samples/match-matchfacts.final.json`](samples/match-matchfacts.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=300` |

`referees[]` roles: `Referee`, `Assistant Referee 1/2`, `Fourth Official`, `VAR`,
`Assistant VAR Official`, each with `section` (the referee's home association, e.g.
`"Monza"`). `enviroment` (sic) is all `null` in our samples except `soldOut`/`country`.
**Quirk:** `status`/`phase` here lag the header — the finished match still reports
`status: "LIVE"`, `phase: "END_SECOND_HALF"` (5-min cache). Do not use this endpoint
for state.

### `GET /seasons/{seasonId}/match/{matchId}/teamstats`

| | |
|---|---|
| **Purpose** | Match team statistics, home vs away |
| **Sample** | [`samples/match-teamstats.pre.json`](samples/match-teamstats.pre.json) · [`samples/match-teamstats.final.json`](samples/match-teamstats.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

`stats[] { statsId, statsLabel, statsLabelAbbreviation, statsUnit, statsValueHome,
statsValueAway }` — 407 ids in a finished match, from `possession-perc`, `shots`,
`shots-on-goal`, `corners`, `fouls`, `yellow-cards`, `expected-goals` through Opta
`attIboxGoal`-style keys to tracking metrics (`distance-covered-sprinting`,
`maximum-speed`, `team_pitch_control__avg_minute`). Pre-match: `stats: []`,
`home`/`away` `null`.

### `GET /seasons/{seasonId}/match/{matchId}/ranking`

| | |
|---|---|
| **Purpose** | Per-player match statistics for both teams (270 stat ids per player) |
| **Sample** | [`samples/match-ranking.pre.json`](samples/match-ranking.pre.json) · [`samples/match-ranking.final.json`](samples/match-ranking.final.json) (**players truncated to 3 per side**; full response is 1.9 MB) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

`home.players[] { player, team, rankName, rankLabel, stats[] }`. Heavy; fetch once
after full time, not while polling.

### `GET /seasons/{seasonId}/match/{matchId}/momentum`

| | |
|---|---|
| **Purpose** | Per-minute win-probability series |
| **Sample** | [`samples/match-momentum.pre.json`](samples/match-momentum.pre.json) · [`samples/match-momentum.final.json`](samples/match-momentum.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

`predictions[] { periodId (1/2), matchMinute, matchMinuteSecond, home, away, combined }`,
newest first (99 points for a full match). Pre-match: `predictions: []`. Note the
`periodId`/`matchMinute` pair — the only place the API exposes period numbers.

### `GET /seasons/{seasonId}/match/{matchId}/advancedMatchEvents`

| | |
|---|---|
| **Purpose** | Every on-ball event (passes, crosses, set pieces) with coordinates and `xp` |
| **Sample** | [`samples/match-advancedmatchevents.pre.json`](samples/match-advancedmatchevents.pre.json) · [`samples/match-advancedmatchevents.final.json`](samples/match-advancedmatchevents.final.json) (**truncated to 15 of 1460 events**; full response is 860 KB) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

`advancedEvents[] { eventId, eventType, eventTime, minuteOfPlay, gameSection, teamId,
playerId, receiverId, xPosition, yPosition, xEndPosition, yEndPosition, isPass,
isCross, isCorner, isFreeKick, … }`. Not needed for a score app; documented so
nobody polls it.

### `GET /seasons/{seasonId}/match/{matchId}/matchpreview`

| | |
|---|---|
| **Purpose** | Pre-match pack: form (last 5), head-to-head record, last meetings, recent results, broadcasters |
| **Sample** | [`samples/match-matchpreview.pre.json`](samples/match-matchpreview.pre.json) · [`samples/match-matchpreview.final.json`](samples/match-matchpreview.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=3600` |

`{ matchId, status, phase, time, additionalTime, home { form[], … }, away, headToHead
{ winsHome, draws, winsAway, …Label }, headToHeadAnyComp, lastMatches[],
lastMatchesAnyComp[], homeRecentResults[], awayRecentResults[], editorial }`.
`matchPreview` (camel-case) is the same resource.

### `GET /seasons/{seasonId}/standings`

| | |
|---|---|
| **Purpose** | League table |
| **Sample** | [`samples/standings.json`](samples/standings.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

**Response shape:** `{ competition, type: "table", teams: [ … ], legenda: [],
editorials: [], apiCallRequestTime }`. Teams are in rank order; each has team fields,
`qualification` (`null` this early — zone label/colour later), `achievementStatuses`,
`note` (deductions), and `stats[] { statsId, statsLabel, statsLabelAbbreviation,
statsValue }` with ids `rank`, `team`, `points`, `matches-played`, `win`, `draw`,
`lose`, `goals-for`, `goals-against`, `goal-difference`, `movement`,
`shootout-wins`, `shootout-losses`, `form` (array of 6 `{ formType: W/D/L/- }`,
oldest first, padded with `-`). Historical seasons work (2025/26 → Inter, 87 pts).

### `GET /seasons/{seasonId}/standings/overall`

| | |
|---|---|
| **Purpose** | Three tables in one: `type` `table` (overall), `home`, `away` |
| **Sample** | [`samples/standings-overall.json`](samples/standings-overall.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=40` |

`{ standings: [ <standings object>, <home>, <away> ] }` — 180 KB, so prefer plain
`standings` for polling.

### `GET /teams/{teamId}/roster`

| | |
|---|---|
| **Purpose** | Squad (players + coaching staff). Bare, it returns every player ever registered for the club in the platform (387 for Inter; `playerStatus`/`leaveDate` `"Active"`/`""` for all of them). **`?seasonId={seasonId}` scopes it to the season's registrations** (45 for Inter 2026/27, numberless youth players included; `competition` then names the season) — verified 2026-09-16, `private, max-age=3600` |
| **Sample** | [`samples/team-roster.json`](samples/team-roster.json) (**truncated to 25 of 387 players**) captured 2026-09-11 · [`samples/team-roster.season.json`](samples/team-roster.season.json) (`?seasonId=`, all 45) captured 2026-09-16 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=3600` |

Player: `info[]` (player/jersey/position/age), `dateOfBirth`, `height`, `weight`
(strings, cm/kg), `nationality`, `role`/`roleLabel`, names, `editorial`.
`competition` is all `null`. `GET /teams/{teamId}/rosters` (plural) returns an empty
shell (`rosters: null`) — see [`samples/team-rosters.json`](samples/team-rosters.json).

### `GET /seasons/{seasonId}/stats/players` and `…/stats/teams`

| | |
|---|---|
| **Purpose** | Season leaderboards. Players: 311 stat ids each; teams: 407 |
| **Parameters** | `category` (`all`), `orderBy` (a `statsLabel`, e.g. `Goals`, `Total Shots`), `direction` (`desc`), `role` (`All`, `Goalkeeper`, …), `page`, `pageNumElement` (players, default 10), `teamId`/`playerId` (single entity) |
| **Sample** | [`samples/stats-players.json`](samples/stats-players.json) (**truncated to 3 of 10** on page 1 of 41) · [`samples/stats-teams.json`](samples/stats-teams.json) (**truncated to 3 of 20**) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | `private, max-age=60` |

Both return `{ competition, players|teams: [ { stats[], rankLabel, … } ], pagination }`.
Each item is 40–70 KB, so page small. The site also calls `stats/players/compare?homePlayerId=&awayPlayerId=`
and `stats/teams/compare?homeTeamId=&awayTeamId=` (not captured).

## Game states

`status` is the API's normalised state, `providerStatus` the Opta one, `phase` the
period. The header, match list, summary, feed and preview all carry the trio; use
`header` as the source of truth.

| `status` | `providerStatus` | `phase` | Meaning |
|---|---|---|---|
| `UPCOMING` | `Upcoming` | `PRE_MATCH` | scheduled; `time: 0`, scores `null` |
| `UNKNOWN` | `null` | `PRE_MATCH` | `feed`/`summary` before kick-off report this instead of `UPCOMING` |
| `LIVE` | *(unverified)* | `FIRST_HALF` / `SECOND_HALF` / `END_SECOND_HALF` observed on `matchfacts`; a half-time phase presumably `HALF_TIME_BREAK` (event spelling `HalfTimeBreak`) | in play; `time` = minute, `additionalTime` = stoppage minute once past 45/90 |
| `FINISHED` | `Finished` | `FULL_TIME` | final; `winReason` `RegularTime` or `Draw` |

Phase names appear in two spellings: `SCREAMING_SNAKE` on match/header objects
(`PRE_MATCH`, `FULL_TIME` on match objects; `FIRST_HALF`, `SECOND_HALF`, `END_SECOND_HALF` seen on `matchfacts`/`ranking`) and
`PascalCase` on events (`FirstHalf`, `HalfTimeBreak`, `SecondHalf`, `EndSecondHalf`).
The client bundle's status enum lists `Unknown, Upcoming, Postponed, Canceled, Lineup,
Tactical, Live, Suspended, Abandoned, Finished` — the site treats `Lineup`/`Tactical`
as "upcoming" and `Suspended` as "live". `LIVE` values, extra-time/penalty phases and
the postponed/abandoned representations are **not yet observed**.

`scheduleStatus` is `UNKNOWN` on all 380 matches.

## Quirks & gotchas

- **Ids must be the full `serie-a::Football_X::hex` string.** A bare hex id, or an
  id that does not exist, returns **`200` with every field `null`** (`matchId: null`).
  Treat a `null` `matchId` as not-found. A bogus *season* id on the match list returns
  `500`. The `action` sub-resource the site references also returns `500`.
- **Two resource trees:** `/matches/{id}/{header,feed,lineups}` vs
  `/match/{id}/{summary,ranking,teamstats,matchfacts,momentum,matchpreview,advancedMatchEvents}`.
  Mixing them up yields `404`/empty bodies.
- **Time zones:** `matchDateUtc` is proper UTC (`Z`). `matchDateLocal` is Europe/Rome
  wall-clock without offset; `localTimeUtcOffset` gives it. Event `timeStamp` is UTC.
- **Clock is whole minutes only** (`time` + `additionalTime`); no seconds anywhere.
  Derive a running clock from the last `timeStamp` of a period-start event in `summary`
  (`first-half`, `second-half`) plus wall time, as for the Premier League.
- **Edge cache:** Akamai caches `max-age=40` bodies (despite `private`) — polling
  faster than 40 s is pointless. `apiCallRequestTime` in the body tells you when the
  origin actually generated the response; use it to de-duplicate.
- **Events newest-first** in `summary`/`feed`; `feed` without `page` returns all events
  on one page, with `page=N` it paginates 30 per page.
- **`matchfacts` state lags** (5-min cache and apparently a different pipeline).
- **`roster` is not season-scoped** (all-time list).
- **Large responses:** season match list 1.2 MB, `ranking` 1.9 MB,
  `advancedMatchEvents` 860 KB, `stats/teams` 1.4 MB, `standings/overall` 180 KB.
  Don't poll them.
- **No ETag/304**, no `Vary`. `Cache-Control` is `private` (except
  `multipleSeasonMatches`).
- **Imagery:** `imagery.teamLogo` etc. are paths under
  `https://media-sdp.legaseriea.it/` (e.g. `clubLogos/<teamhex>.webp`, `_light`
  variant, `teamImages/…`, `stadiums/…`). Verified `200 image/webp`.
- The retry policy in the site's client is 3 retries with 5/10/20 s back-off on any
  non-2xx — hints that the origin occasionally errors; a `500` is worth one retry.
- Localisation: `?locale=it-IT` changes labels only. The site passes `Accept:
  text/plain; x-api-version=1.0`; `api-supported-versions: 1.0` comes back regardless.

## Out of scope

- `https://www.legaseriea.it/api/season/{id}/championship/A/matchday` and the other
  legacy `www.legaseriea.it/api/...` endpoints from older community scrapers → `404`
  HTML. The old API is gone.
- `https://dapi.legaseriea.it/v2/content/…` — the site's CMS (news, videos, awards,
  competition pages). Not needed for scores; unverified.
- LiveLike (`cf-blast.livelikecdn.com`, `serie-a-api-prod.livelikeapp.com`) — fan
  engagement widgets requiring a profile token.
- `GET /seasons/{id}/match/{matchId}/action` → `500`.
- `GET /teams/{id}/rosters` → empty shell.
- The Deltatre origin host `seriea-api.prd.sdp.deltatre.digital` works identically but
  is an implementation detail; use the `api-sdp.legaseriea.it` alias.

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `/competitions/{id}/seasons` | `seasonId`, `seasonName`, `competitionId` | Competition id is a constant; season dates are `null` (use `matchdays[0].startDateUtc` / `[37].endDateUtc`) |
| Game (id, teams, start time) | `/seasons/{s}/matches?matchDayId=` | `matchId`, `home.teamId`, `away.teamId`, `matchDateUtc`, `stadiumName`, `matchSet.name` | UTC directly available |
| GameState | `header` | `status`, `phase` | Live values unverified (see table) |
| Score by period | `header` / `summary` | `homeScorePush`/`awayScorePush`; per-half from `summary` goal events' `phase` | No explicit half-time score field |
| Clock / period | `header` | `time`, `additionalTime`, `phase` | **Whole minutes only**; period start wall-clock from `summary` period events |
| GameEvent | `summary` (key) / `feed` (all) | `type`, `home|away.time/additionalTime/phase`, `player.playerId`, `player.assistPlayerId`, `timeStamp` | Newest first; own goal/penalty types not yet observed |
| Lineups | `lineups` | `fielded[]`, `benched[]`, `staff[]`, `tacticalFormation`, `tacticalX/YPosition` | Empty arrays until ~1 h before kick-off |
| Team | any match / `standings` | `teamId`, `shortName`, `officialName`, `acronymName`, `imagery.teamLogo` | No dedicated `/teams/{id}` verified; logos on `media-sdp` |
| Player | `lineups` / `roster` | `playerId`, names, `bibNumber`, `role`, `nationality`; `roster` adds DOB/height/weight | `roster` is all-time, not current squad |
| Standings | `standings` (`/overall` for splits) | `teams[].stats[]` by `statsId` | Rank order; form array; `note` for deductions |
| Officials | `matchfacts` | `referees[]` | Names + role + section; state fields on this endpoint lag |

## TODO

- [ ] Capture live samples during Matchday 4 (2026-09-11 18:45Z Venezia–Fiorentina,
      then 12–14 Sep): `header` (`status`/`phase`/`time`/`additionalTime` during
      `FIRST_HALF`, `HALF_TIME_BREAK`, `SECOND_HALF`), `summary`, `feed`, `lineups`
      (`Lineup`/`Tactical` status?), `matches?matchStatus=Live`, `momentum`.
- [ ] Observe `red-card`, `own-goal`, `penalty`, `var` event types and how
      `providerPenaltyScore*` is used (Coppa Italia).
- [ ] Observe a postponed/suspended match.
- [ ] Check whether Coppa Italia / Supercoppa use the same project with other
      competition ids (`dapi.legaseriea.it/v2/content/en-gb/competitions/coppa-italia`).

## Changelog

| Date | Change |
|---|---|
| 2026-09-11 | Initial mapping: 22 endpoints, 33 samples (pre-match + finished). Endpoint list extracted from the legaseriea.it widget bundle (Deltatre SDP client proxy). |
