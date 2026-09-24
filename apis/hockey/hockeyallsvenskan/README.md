# HockeyAllsvenskan API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | Sweden (second tier) |
| **Official site / base URL** | `https://hockeyallsvenskan.se` |
| **Auth** | None |
| **Formats** | Next.js React Server Components + JSON |
| **Last verification** | 2026-09-18 (game routes, opening night with six games in play); 2026-09-23 (table, squads, player profiles) |
| **Provider status** | Schedule, game detail, lineups, league table, club pages and player profiles; season snapshot refreshed per game; live state from the game document (period and score, no clock). Mapped and not wired: squads and the per-club stat routes (POST), play-by-play (POST) and the MQTT push |

## 2026 site migration

The former `www.hockeyallsvenskan.se/api/sports-v2` and `/gameday` Sportality
mapping stopped serving the site. HockeyAllsvenskan now uses a Next.js frontend and
Strapi-shaped API responses. SHL still uses the old mapping, so the providers no
longer share an implementation.

## Provider routes

| Capability | Request | Mapping |
|---|---|---|
| Complete current-season schedule | `GET /pages/matcher?_rsc=openscore` with `RSC: 1` | The single embedded `games` array is decoded and mapped to `Game`. The response contains all 364 regular-season fixtures (1.58 MB decoded, 127 KB gzipped, `Cache-Control: no-store`; measured 2026-09-18); a page `date` parameter is only a UI filter. Played games carry their score, period scores and `decidedIn` here too. |
| One game | `GET /api/game?slug=20260918-aik-modo` | `data[0]` contains stable slug, season, StatNet game/team IDs, UTC time, teams/logos, venue, score, period scores and completion/decision fields. About 700 bytes (6 KB with the team objects), no cache directive. In play it carries `currentPeriod` (`P1`, `P2`, …), `currentPeriodStart` (a UTC stamp), `playedDateTime`, the running `homeScore`/`awayScore` and the period columns filled so far ([`game.new.live.json`](samples/game.new.live.json)). No day list form: `?date=`, `?season=` and `/api/games` without ids answer `400`. |
| Several games at once | `GET /api/games?documentIds=sftwwgo7jg0avrwgpgt79ny9,…` | The same documents for a comma list of Strapi `documentId`s (the season page carries them), one 4 KB row each, `no-store`. What the site's match carousel polls; a candidate for refreshing a night's due games in one read instead of one per game ([`games.by-document-ids.live.json`](samples/games.by-document-ids.live.json)). Not used yet: the durable snapshot keeps OpenScore's model, which has no field for the document id. |
| League table | `GET /pages/tabell?_rsc=openscore` with `RSC: 1` | The standings component of the table page: all 14 rows with the 3-2-1-0 columns, goals and both special-team percentages, and every club's name, crest and colours beside them. 15 KB gzipped, `no-store`, kept 5 minutes. The current regular season is the only one it serves ([below](#get-pagestabell_rscopenscore-with-rsc-1)). |
| Club page | none of its own | A club's identity, home rink and whole season come out of the season snapshot, which is keyed by the same StatNet id the table uses; its table row and rank come from the table read. `team()`, `teamSchedule()` and `teamStats()` cost no request of their own ([below](#what-a-club-page-costs)). |
| Player profile | `GET /players/{slug}?_rsc=openscore` with `RSC: 1` | The profile component: the CMS record, the current season's totals and the career season by season. Keyed by the page slug, which lineup entries carry ([below](#get-playersslug_rscopenscore-with-rsc-1)). |
| Lineups | `GET /games/{slug}/view?_rsc=openscore` with `RSC: 1` | The game page's server-rendered lineup component (`games.game-lineup-2-0` in the RSC stream) carries `homeLineups[]` / `awayLineups[]`: every dressed player with `positionToday` (`GK`, `LD`, `RD`, `LW`, `CE`, `RW`), `line` (`1`–`4`), `jerseyToday`, `isCaptain` / `isAssistant`, `isStarting` (the starting goalie), `isExtraPlayer`, `playerStatNetId` and a nested `player` profile with headshots and slug; the four officials are in the same arrays with `isReferee` / `isLinePerson`. Published about two hours before the puck drop (the 18:00Z game had its sheets at 17:33Z). 148 KB decoded, ~21 KB gzipped, `no-store`; the page is rendered without the component until the sheets exist. The provider reads it on demand and keeps it 10 minutes ([`game-view.lineups.rsc.txt`](samples/game-view.lineups.rsc.txt), the one RSC line). |

The site has no day listing, so the provider keeps the season as a snapshot — in memory
and, on Android, in Room with a completion marker — and answers every date from it:

- the page is read again only when the snapshot is older than six hours (kick-off changes,
  results the game route was never asked for);
- a game on the requested date that is due, under way, or over within the last 24 hours
  without a result in the snapshot is read from `/api/game` at the 10 s live floor and
  merged back; a final record is also written to the durable snapshot;
- a game the snapshot has as over, cancelled or postponed is never re-read, and a page
  import never regresses such a game (the page can lag the game route);
- when the page cannot be read the old snapshot is served instead of an error and the
  page is not retried for five minutes; `game()` falls back to the snapshot only for a
  finished game, whose stored record is complete.

Raw RSC is never stored: what is kept is OpenScore's own normalized record of fixtures and
results (identity, teams, venue, time, stage, scores, period scores, decision), replaced whole
by the next import and discarded by the next build — the same narrow exception to the page's
`no-store` that every league's day listing store makes.

The provider advertises `GAMES_BY_DATE`, `GAME`, `PERIOD_SCORES`, `LINEUPS`, `LINE_GROUPS`
(goalies with the starter first, then `Line n` / `Pairing n` as the sheet has them; officials
left out), `STANDINGS`, `TEAM`, `TEAM_SCHEDULE`, `TEAM_STATS` and `PLAYER`. `LIVE_UPDATES` is
not claimed: the game document says which period is on and the score, but has no clock, and on
the opening night it lagged the ice by tens of minutes (see Live). `ROSTER` is not claimed
either: squads are behind a POST route, and the `Fetcher` is read-only.

## Live (2026-09-18, opening night)

Six games polled through the provider from the first period: the document goes
`currentPeriod: null` → `"P1"` with `currentPeriodStart` and `playedDateTime` set at the
puck drop, the `P1` score columns appear, `isCompleted: false`. The site's own game page does
not use this document for its ticker; it POSTs to `/api/play-by-play` every 60 s and
subscribes to an MQTT broker:

| Route | Request | Notes |
|---|---|---|
| Play-by-play | `POST /api/play-by-play` with `{"statNetGameNumber":"23405","homeStatNetId":"OSIK","awayStatNetId":"MIK","playedDateTime":"…","scheduledDateTime":"…"}` (the first three are required; the payload is the `pollPayload` prop of the game page) | The server proxies StatNet and answers `{game_info: {game_finished, game_finished_at, decided_in, game_time}, game_events: [{Period, period_label, StartTime, EndTime, Finisehd (sic), Events[]}]}`. Events carry `type` (`GoalkeeperEvent`, `Shot`, `Goal`, `Penalty`, `Timeout`, `ShootoutPenaltyShot`, per the site's filters also `Save`, `BlockedShot`, `Sent off`, `Expulsion`), `time` in seconds of the period, `eventDescription` (`EQ`, `save`, `outside`, `covered by player`), `runningScore`, `Assist1`/`Assist2` with season tallies, `team {name, code, statNetId}` and `player {statNetId, firstName, familyName, jerseyNumber}` ([`play-by-play.new.live.json`](samples/play-by-play.new.live.json)). Under the opening-night load StatNet answered one call in three (`{"error":"StatNet API error: 500"}` / `503`, up to 38 s). A POST, so outside the read-only `Fetcher`; unmapped. |
| Push | `GET /api/hivemq-config` → `{host, port, username, password, …}` | The page opens an MQTT (HiveMQ) session with these and subscribes per game (`pollStartBeforeGameMinutes: 45`). The credentials are handed to every browser, but they are credentials: out of scope under docs/principles.md, noted so nobody re-tries. |

The game documents themselves stopped updating at 17:12–17:17Z on the opening night while
the games ran on (no new goals or period changes reached `/api/game` or `/api/games` for
half an hour) and `/api/play-by-play` answered 500 throughout, so the site's StatNet sync,
not the polling, is the limit. Period transitions and the finished shape are recorded as
they arrive (`build/capture/hockeyallsvenskan/`).

## Team and player identifiers

The site spells a club two ways and both spellings appear in the same document. Which one a
route wants is not negotiable, and the wrong one answers `200` with an empty list rather than
an error, so the distinction is worth keeping straight:

- the **StatNet id** is the join key for games and stats. It is `teamId` on a standings row,
  `homeStatNetId` / `awayStatNetId` on a game, `teamStatNetId` on a player profile, and the
  `team` field of `/api/player-leaderboard`.
- the **CMS short name** is the key for squads. It is `statNetClubLabel` on a standings row,
  `shortName` on the team object in the season page and the game document, and the
  `teamShortName` field of `/api/all-players`.
- `teamCode` on a standings row is for display only. It matches the CMS short name for
  thirteen clubs but not for MoDo (`MODO` there, `MoDo` in the CMS), so it is not a safe key
  for either route.

| StatNet id | CMS short name | Club |
|---|---|---|
| `AIK` | `AIK` | AIK |
| `AIS` | `AIS` | Almtuna |
| `BIK` | `BIK` | BIK Karlskoga |
| `IKO` | `IKO` | IK Oskarshamn |
| `KHC` | `KHC` | Kalmar |
| `LIF` | `LIF` | Leksand |
| `MIK` | `MORA` | Mora |
| `MODO` | `MoDo` | MoDo |
| `NYB` | `NVIF` | Nybro Vikings |
| `OSIK` | `ÖIK` | Östersund |
| `SSK` | `SSK` | Södertälje |
| `VHC` | `VHC` | Vimmerby |
| `VIK` | `VIK` | Västerås |
| `VIS` | `VIS` | Visby Roma |

Both spellings are reachable from data the provider already holds: the season page's game rows
carry `homeStatNetId` next to `homeTeam.shortName`, and every standings row carries `teamId`
next to `statNetClubLabel`. The table page's `teamDisplayMap` is keyed by *both* spellings
(19 keys for the 14 clubs, the extra ones being IFB and TRO from last season and the duplicate
spellings), each value carrying the logo, full name and colours, so a lookup by either spelling
resolves.

Players are joined by `slug`: lineup entries carry it as `player.slug`, squad rows and
leaderboard rows as `slug`. The StatNet player id is on lineups (`playerStatNetId`), on
leaderboard rows (`playerStatNetId`) and on the profile (`statNetId`), but **not** on
`/api/all-players` rows, where it only appears inside the headshot file name
(`6608_LIF_HA_Altoern_medium_…`).

## Standings, squads and players

The two GET routes are what the provider reads for the table and for a player's profile. The
three POST routes are reachable but outside the read-only `Fetcher`, which has only `get`
(docs/principles.md), so squads and the per-club stat leaderboards stay unwired; they are
described here because they are the only way to those numbers. `tools/api-health` is GET-only
for the same reason, so the two GET routes carry health checks and the POST routes are
verified by hand.

### GET `/pages/tabell?_rsc=openscore` with `RSC: 1`

**Purpose**: the league table, and the league's club directory with it.
**Parameters**: none that work. `?season=`, `?phase=` and `?location=` are accepted and
ignored: the response always reports `appliedSeason: 2026`, `appliedPhase: "HA"`,
`appliedLocation: "all"`. The page's own season, phase and home/away pickers POST to
`/api/league-standings-all-time`, so only the current regular-season table is reachable by GET.
**Sample**: [`tabell.standings.rsc.txt`](samples/tabell.standings.rsc.txt), the one RSC line.
**Last verified**: 2026-09-23.
**Cache**: 15 KB gzipped, 54 KB decoded, `text/x-component`, `no-store`, no `ETag` or
`Last-Modified`, no `Access-Control-Allow-Origin`. A table moves only as games finish, so the
5 min the other leagues' tables are kept is right.

The `stats.league-standings-262-0` component line carries `standings` (one row per club, in
rank order), `teamDisplayMap`, `highlightTeamCode`, `leagueShort` (`"HA"`), `allTimeMode`,
`seasonYearOptions` (2005-06 to 2026-27, for the POST route), `appliedSeason`, `appliedPhase`,
`appliedLocation` and `compactMode`. Every row value is a string:

`rank`, `teamCode`, `teamId`, `games_played`, `wins`, `losses`, `ties`, `overtime_wins`,
`overtime_losses`, `shootouts_wins`, `shootouts_losses`, `goals`, `goals_against`,
`goal_difference`, `total_points`, `power_play_perc`, `penalty_kill_perc`, `statNetClubLabel`.

The trailing numbers in `stats.league-standings-262-0` are the CMS block's instance id, as in
`games.game-lineup-2-0` on the game page, so a reader should match on the `stats.league-standings`
prefix and not on the whole name: re-editing the page in Strapi would renumber it.

Notes taken against all 14 rows on 2026-09-23:

- points are the Swedish 3-2-1-0 scheme: 3 for a win in regulation, 2 for a win past it
  (`overtime_wins` + `shootouts_wins`), 1 for a loss past it. `total_points` reconciles with
  the columns for every row.
- `ties` is not a draw count - Swedish hockey has no draws. It equals
  `overtime_losses + shootouts_losses` on every row, so it is the point-earning-loss column
  under another name and adds nothing.
- `wins` and `losses` are regulation only, so `wins + losses + ties = games_played`.
- `power_play_perc` and `penalty_kill_perc` are percentages as strings with two decimals
  (`"18.18"`), so a club page gets its two special-teams numbers without a stats call.

### GET `/players/{slug}?_rsc=openscore` with `RSC: 1`

**Purpose**: one player's profile and career.
**Parameters**: the slug, from a squad row, a leaderboard row or a lineup entry.
**Sample**: [`player-profile.rsc.txt`](samples/player-profile.rsc.txt) (Patrik Zackrisson).
**Last verified**: 2026-09-23.
**Cache**: 16 KB gzipped, 55 KB decoded, `no-store`, no validators. A profile is static within
a game day apart from the season totals.

The `team.player-profile-2-0` line carries two objects. `playerData` is the CMS record:
`statNetId`, `teamStatNetId`, `slug`, `firstName`, `familyName`, `jerseyNumber`,
`positionCode` / `positionName`, `birthDate`, `country`, `height`, `weight`, `shoots`,
`youthTeam`, `isRookie`, `headshots` and a nested `team`. `careerStats` is the StatNet record:
`player_info` (birthdate, height, weight, nationality, position), `total_season_stats` (37
keys for the current season, including `TOI_GP`, `PPTOI_GP`, `SHTOI_GP`, `FO_perc`, `Hits`,
`BkS`, `GWG`) and `player_season_by_season_stats`, one row per season and competition with
`Season`, `GameType`, `TeamCode`, `GP`, `G`, `A`, `TP`, `PIM`, `PlusMinus`, `SOG`, `TOI_GP`.

The career is not limited to this league: `GameType` was observed as `HA`, `HAPlayoff`,
`KvalTillSHL`, `Elitserien` and `SMSlutspel` (may be incomplete), with the other league's team
codes in `TeamCode`.

An unknown slug answers **`200`** with the site's not-found page (5.5 KB, carrying
`NEXT_HTTP_ERROR_FALLBACK;404` and no profile component), so a caller must decide on the
component's presence, not on the status.

### POST `/api/all-players`

**Purpose**: a club's whole squad, which is the one thing no GET route gives.
**Body**: `{"scope":"team","orderBy":"name","teamShortName":"MoDo","leagueShortName":"HA","searchQuery":"","page":1,"pageSize":60}`.
`scope` is `team` or `league`; with `league` the team field is ignored and the whole league is
paged (378 players on 2026-09-23, matching the 14 squads summed).
**Sample**: [`all-players.team.json`](samples/all-players.team.json) (Leksand, 26 players),
[`all-players.unknown-team.json`](samples/all-players.unknown-team.json).
**Last verified**: 2026-09-23.
**Cache**: 27 KB for a squad, `application/json`, **no cache directive at all**, no validators.
A squad changes at the transfer deadline and on call-ups, so a day is generous.

Rows carry `firstName`, `familyName`, `id`, `documentId`, `headshots` (`thumb`, `small`,
`medium`, `large`), `positionCode`, `positionName`, `jerseyNumber`, `birthDate`, `country`,
`height`, `weight`, `shoots`, `youthTeam`, `isRookie`, `teamPlayer`, `slug` and a nested `team`
(`shortName`, `name`, `mainColorHex`, `logo`). `positionCode` was observed as `GK`, `LD`, `RD`,
`LW`, `CE`, `RW` - the same set the lineup component uses. `pagination` is
`{page, pageSize, pageCount, total}`.

Squads ran 23 to 30 players. `GET` on this path answers `405`. An unknown or wrongly spelled
team answers `200` with `{"players":[],"pagination":{…,"total":0}}`.

### POST `/api/player-leaderboard`

**Purpose**: a club's players ranked by one stat, which is a squad's season stat line by
another name (only players with appearances, so 22 to 25 per club against a 23 to 30 squad).
**Body**: `{"league":"HA","team":"OSIK","phase":"HA","metric":"TP","isGoalkeeper":false,"page":1,"pageSize":60}`.
`team` is the **StatNet id**; omit `phase` and the answer is empty with no error. Leaving
`team` out gives the league leaderboard the `/pages/spelare` page shows.
**Samples**: [`player-leaderboard.team.json`](samples/player-leaderboard.team.json),
[`player-leaderboard.goalkeepers.json`](samples/player-leaderboard.goalkeepers.json).
**Last verified**: 2026-09-23.
**Cache**: 16 KB per metric for a club, no cache directive.

Skater metrics from `/pages/spelare`: `TP`, `G`, `A`, `PlusMinus`, `Hits`, `FOPerc`, `TOI/GP`
(the last answered no rows on 2026-09-23). Goalkeeper metrics from `/pages/malvaktsstatistik`,
with `isGoalkeeper: true`: `SVSPerc`, `GAA`, `SO`. The lists may be incomplete.

Rows carry `rank`, `player`, `teamCode`, `teamID`, `jerseyNumber`, `position`, `metric`,
`metricValue` (a string), `playerStatNetId`, `firstName`, `familyName`, `slug`, `headshot` /
`headshotSmall` / `headshotMedium`, `teamLogo`, `teamName` and `teamMainColor`, with
`totalCount`, `totalPages` and `page` beside them. One metric per call, so a full stat line
costs one call per column.

### POST `/api/team-leaderboard`

**Purpose**: the 14 clubs ranked by one team stat.
**Body**: `{"league":"HA","phase":"HA","metric":"PPPerc","page":1,"pageSize":20}`.
**Sample**: [`team-leaderboard.json`](samples/team-leaderboard.json).
**Last verified**: 2026-09-23.
**Cache**: 3 KB, no cache directive.

Metrics from `/pages/stats-lag`: `PPPerc`, `PKPerc`, `W`, `G`, `GA`, `SOG`, `SVSPerc` (may be
incomplete). Rows are `rank`, `teamCode`, `teamID`, `metric`, `metricValue`, `teamName`,
`teamLogo`. `PPPerc` and `PKPerc` duplicate columns the standings row already carries, so the
route only earns a call for `SOG` and `SVSPerc`.

## What a club page costs

The season page the provider already imports every six hours carries the full club directory
inline: all 14 teams with `name`, `shortName`, `teamArena`, `arenaAddress`, `arenaLatitude` /
`arenaLongitude`, `arenaTelephone`, `logo`, `mainColorHex` / `alternateColorHex` and the Strapi
`id` / `documentId`, keyed by the StatNet id on every game row. So a club's identity, arena and
whole season of fixtures and results cost nothing beyond the snapshot that already exists, its
table row and rank cost the one table read shared by the league, and only the squad needs a
route of its own.

That is what `team()`, `teamSchedule()` and `teamStats()` do. `team()` reports the rink the club
plays most of its home games in rather than the venue of the first one, so a game moved to a
bigger arena (AIK's opening night at the Avicii Arena, not Hovet) is not taken for home.
`teamSchedule()` answers from the snapshot and re-reads only the games in play, exactly as a day
listing does. `teamStats()` returns the club's table row in three groups: `record` (GP, W, L,
OTW, OTL, SOW, SOL, PTS), `goals` (GF, GA, Diff) and `specialTeams` (PP%, PK%).

The core reports all wins as `wins`, as the NHL, DEL and SSL tables do, with the 3-2-1-0 split
kept in `extra`: `regulationWins`, `overtimeWins`, `shootoutWins`, `overtimeLosses`,
`shootoutLosses`, and `powerPlay` / `penaltyKill` as the strings the site prints.

## Captured samples

- [`matcher.rsc.txt`](samples/matcher.rsc.txt) — reduced two-date RSC fixture proving
  full-season extraction and local date filtering.
- [`game.new.final.json`](samples/game.new.final.json) — completed game with regulation
  score and three period scores.
- [`game.new.live.json`](samples/game.new.live.json) — AIK v MoDo in the first period,
  2026-09-18 17:35Z (`currentPeriod: "P1"`, `currentPeriodStart`, `isCompleted: false`).
- [`games.by-document-ids.live.json`](samples/games.by-document-ids.live.json) — three of
  the night's games from `/api/games?documentIds=`, one scheduled and two in play.
- [`game-view.lineups.rsc.txt`](samples/game-view.lineups.rsc.txt) — the lineup line of
  the AIK v MoDo game page: 22 home and 26 away rows (four officials among the away ones).
- [`play-by-play.new.live.json`](samples/play-by-play.new.live.json) — Östersund v Mora,
  first period in progress: goalie entries, shots and a goal with two assists.
- [`tabell.standings.rsc.txt`](samples/tabell.standings.rsc.txt) - the standings line of the
  table page: 14 rows after three rounds, and the 19-key `teamDisplayMap`.
- [`player-profile.rsc.txt`](samples/player-profile.rsc.txt) - Patrik Zackrisson's profile
  line: CMS record, current-season totals and 29 season rows across five competitions.
- [`all-players.team.json`](samples/all-players.team.json) - Leksand's 26-player squad from
  `/api/all-players`, with birth dates, heights, shooting hands and headshots.
- [`all-players.unknown-team.json`](samples/all-players.unknown-team.json) - the empty `200`
  a misspelled team code answers with.
- [`player-leaderboard.team.json`](samples/player-leaderboard.team.json) - Leksand's 22
  scoring skaters, and [`player-leaderboard.goalkeepers.json`](samples/player-leaderboard.goalkeepers.json)
  its two goalkeepers by save percentage.
- [`team-leaderboard.json`](samples/team-leaderboard.json) - the 14 clubs by power-play
  percentage.
- The older Sportality captures remain in `samples/` as migration history; the current
  provider does not consume them.

## Core model mapping

| Core type | Source | Fields | Gaps |
|---|---|---|---|
| `Game` | season page `games[]`; `/api/game?slug=` for a due, live or just-finished one | `slug` is the id, `scheduledDateTime`, `venue`, `round`, `attendance`, `homeStatNetId` / `awayStatNetId` are the team ids, `homeScore` / `awayScore` | none |
| `GameState` | `/api/game` | `currentPeriod` (`""`, `P1`, `P2`, `P3`), `currentPeriodStart`, `playedDateTime`, `isCompleted` | no intermission marker, and the documents froze under opening-night load, so `LIVE_UPDATES` is not claimed |
| `GameEnding` | `decidedIn` | `FT`, `OT`, `SO` | |
| `Clock` | nowhere | | the site's own ticker has one; it comes from the play-by-play POST |
| `PeriodScore` | `homeScoreP1`-`P3`, `homeOtScore`, `homeSoScore` and the away pairs | | |
| `GameEvent` | `POST /api/play-by-play` | goals with assists and running score, shots, penalties, goalkeeper changes, timeouts, shoot-out attempts | POST, so not wired; StatNet answered one call in three under load |
| `Lineup` | game page RSC (`games.game-lineup-2-0`) | `positionToday`, `line`, `jerseyToday`, `isCaptain` / `isAssistant`, `isStarting`, `isExtraPlayer` | officials are in the same arrays and are dropped |
| `StandingsTable` | table page RSC (`stats.league-standings`) | rank, played, wins, losses, `otherLosses` from `overtime_losses + shootouts_losses`, points, goals for and against, difference; `overtime_wins`, `shootouts_wins`, `shootouts_losses`, `power_play_perc`, `penalty_kill_perc` as `extra` | one table, no groups; current regular season only, and no home/away split without the POST route |
| `Team` | season page team objects, keyed by StatNet id | `name`, `shortName`, `teamArena`, `logo`, `mainColorHex` / `alternateColorHex`, `arenaAddress`, coordinates | no conference or division (a single table), country is always SWE |
| `TeamSeasonStats` | standings row | record, goals for and against, power play, penalty kill | `SOG` and `SVSPerc` are a POST away, one call per metric |
| `Player` as a roster row | `POST /api/all-players` | number, position, birth date, country, height, weight, shooting hand, youth club, headshots | POST; the row has no StatNet id, only a `slug` and the id inside the headshot file name |
| `Player` as a profile | `/players/{slug}` RSC (`team.player-profile`) | bio, club, headshot; the model has no field for the season or career stat lines | keyed by the page slug, while lineups and events key players by StatNet id: a lineup entry carries both, nothing else bridges them |

## Changelog

| Date | Change |
|---|---|
| 2026-09-24 | Wired the mapping: `STANDINGS` from the table page, `TEAM` / `TEAM_SCHEDULE` / `TEAM_STATS` from the season snapshot and that table row, `PLAYER` from the profile page. The crosswalk now keys HockeyAllsvenskan by StatNet code in its own namespace, so its clubs carry a `clubId` again (they had none since the migration). Squads stay unwired: POST. |
| 2026-09-23 | Mapped the league table (`/pages/tabell` RSC), squads (`POST /api/all-players`), per-club player and team stats (`POST /api/player-leaderboard`, `POST /api/team-leaderboard`) and player profiles (`/players/{slug}` RSC). Recorded the two club-code systems and which route wants which, and that the season page already carries the full club directory. Docs, samples and health checks only: no provider change. |
| 2026-09-18 (evening) | Opening night observed through the provider: live document shape sampled, lineups mapped from the game page (`LINEUPS` + `LINE_GROUPS`), `/api/games?documentIds=` found, play-by-play POST and MQTT push documented. The `HA` game type is no longer shown as a competition label. |
| 2026-09-18 | The season snapshot was frozen at first import (no scores, no live or final states ever). It is now re-imported after six hours and due/live/recently-finished games are refreshed through `/api/game` and merged back; stale snapshot served when the page is unreachable. Payload sizes and the absent list route recorded. |
| 2026-09-15 | Remapped schedule and game detail after the Next.js/Strapi migration; added one-time normalized Room season storage. |
| 2026-09-11 | Captured the former Sportality API before the migration. |
