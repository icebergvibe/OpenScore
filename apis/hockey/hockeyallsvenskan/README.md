# HockeyAllsvenskan API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | Sweden (second tier) |
| **Official site / base URL** | `https://hockeyallsvenskan.se` |
| **Auth** | None |
| **Formats** | Next.js React Server Components + JSON |
| **Last verification** | 2026-09-18 (game routes, opening night with six games in play); 2026-09-25 (table moved to a POST route, squads, leaderboards, play-by-play) |
| **Provider status** | Schedule, game detail, lineups, league table, club pages, squads, player profiles and play-by-play; season snapshot refreshed per game; live state from the game document (period and score, no clock) with the timeline polled beside it. Mapped and not wired: the MQTT push |

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
left out), `EVENTS`, `TEAM`, `TEAM_SCHEDULE` and `PLAYER` always, and `STANDINGS`,
`TEAM_STATS`, `ROSTER` and `LIVE_UPDATES` when its fetcher is a `QueryFetcher` - those four are
behind POST routes with no GET anywhere, so with a plain `Fetcher` the provider does without
them rather than failing when asked (docs/principles.md, "Read-only").

`CLOCK` is not claimed: nothing on this site publishes one. The game document has the period
and the score but no clock, and the play-by-play's `game_info.game_time` has been `{}` in every
body observed, finished games included. `EVENTS` carries its own times instead - seconds
elapsed inside each period, counted from zero again every period.

## Live (2026-09-18, opening night)

Six games polled through the provider from the first period: the document goes
`currentPeriod: null` → `"P1"` with `currentPeriodStart` and `playedDateTime` set at the
puck drop, the `P1` score columns appear, `isCompleted: false`. The site's own game page does
not use this document for its ticker; it POSTs to `/api/play-by-play` every 60 s and
subscribes to an MQTT broker. The page's own config is `pollIntervalMs: 5000` and
`pollStartBeforeGameMinutes: 45`, so the site polls every 5 s from 45 minutes before the
scheduled start; we keep the 10 s floor.

| Route | Request | Notes |
|---|---|---|
| Play-by-play | `POST /api/play-by-play` - see its own section below. Under the opening-night load StatNet answered one call in three (`{"error":"StatNet API error: 500"}` / `503`, up to 38 s), so a failure here is often upstream rather than drift. | The provider polls this beside the game document while a game is live; it is 24 KB against the game page's 235 KB. |
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

One GET route is left here - the player profile. Everything else on this page answers only a
`POST`, and answers `405` to a `GET`. They are reads: each returns exactly what an anonymous
visitor's browser is shown, with no side effect and nothing in the body but the question. The
provider reaches them through `QueryFetcher` (docs/principles.md, "Read-only"), and
`tools/api-health` does too, so every one of them now carries a health check rather than being
verified by hand.

### POST `/api/league-standings-all-time`

**Purpose**: the league table. **This replaced a GET on 2026-09-25**: `/pages/tabell?_rsc=`
used to server-render a `stats.league-standings` component carrying all fourteen rows, and now
returns a 16.8 KB client-rendered shell with no standings in it - the plain HTML has none
either. The page's own season, phase and home/away pickers always called this route; now the
first render does too.
**Body**: `{"league":"HA","phase":"HA","season":2026,"location":"all"}`. `season` is a year.
`location` is `all`, `home` or `away` (a home-only table omits a club with no home game yet, so
it can be short of fourteen). `phase` may be left out. `{"league":"HA"}` alone answers
`400 {"error":"Missing league or season"}`.
**Samples**: [`league-standings-all-time.json`](samples/league-standings-all-time.json) (2026-27
after three rounds), [`league-standings-all-time.past-season.json`](samples/league-standings-all-time.past-season.json)
(2025-26, finished).
**Last verified**: 2026-09-25.
**Cache**: 4.4 KB, `application/json`, no cache directive, no validators. A table moves only as
games finish, so the 5 min the other leagues' tables are kept is right.

`{standings: [...]}` and nothing else. Every row value is a string:

`rank`, `teamCode`, `statNetClubLabel`, `games_played`, `wins`, `losses`, `ties`,
`overtime_wins`, `overtime_losses`, `shootouts_wins`, `shootouts_losses`, `goals`,
`goals_against`, `goal_difference`, `total_points`, `power_play_perc`, `penalty_kill_perc`.

**Two things the old component had that this has not**, both of which cost a join:

- **no StatNet id.** The old row carried `teamId` (`OSIK`, `MIK`, `NYB`) beside the display
  `teamCode` (`ÖIK`, `MORA`, `NVIF`). Only the display code survives, and the core keys clubs
  by the StatNet id the games use, so the season snapshot supplies it: it carries both
  spellings for all fourteen clubs (see "Two club-code systems"). A club the snapshot has no
  game for - a past season's relegated side - keeps the display code, which is the best that
  can be done.
- **no crests, names or colours.** `teamDisplayMap` is gone with the component. The season
  snapshot has all of it, keyed by StatNet id, so this costs no request.

And `power_play_perc` / `penalty_kill_perc` are empty strings in every season observed,
current or finished, so those two columns come from `POST /api/team-leaderboard` instead.

**What it costs.** The rows are 4.4 KB, but the join makes the table depend on the season
snapshot, which is a 127 KB gzipped page when nothing has imported it yet. In the app that is
always already loaded - the feed reads it to show any day at all - so the table is 4.4 KB plus
two shared leaderboard calls. A caller that wants only the table on a cold start pays the
season page once and then never again for six hours.

What it gained: **a season that is not the running one.** The old page accepted `?season=` and
ignored it, always reporting `appliedSeason: 2026`; this answers 2025 with the finished
2025-26 table (52 games, IF Björklöven on 119 points). The picker offers 2005-06 onwards.

Notes that still hold, taken against all 14 rows:

- points are the Swedish 3-2-1-0 scheme: 3 for a win in regulation, 2 for a win past it
  (`overtime_wins` + `shootouts_wins`), 1 for a loss past it. `total_points` reconciles with
  the columns for every row.
- `ties` is not a draw count - Swedish hockey has no draws. It equals
  `overtime_losses + shootouts_losses` on every row, so it is the point-earning-loss column
  under another name and adds nothing.
- `wins` and `losses` are regulation only, so `wins + losses + ties = games_played`.

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
paged (378 players on 2026-09-23, matching the 14 squads summed). Every field is required in
practice - the defaults are not optional, they are simply what the site always sends.
**Sample**: [`all-players.team.json`](samples/all-players.team.json) (Leksand, 26 players),
[`all-players.team-modo.json`](samples/all-players.team-modo.json) (MoDo, 29 - the club whose
two codes differ), [`all-players.unknown-team.json`](samples/all-players.unknown-team.json).
**Last verified**: 2026-09-25.
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

**The spelling is the whole difficulty.** `teamShortName` is the CMS short name, and asking
with the StatNet id the core keys clubs by is the wrongly-spelled case above: verified
2026-09-25, `MoDo` → 29 players and `MODO` → 0, `ÖIK` → 26 and `OSIK` → 0, `MORA` → 29 and
`MIK` → 0, `NVIF` → 26 and `NYB` → 0. Nothing in the response says the key was wrong. The
provider takes the CMS spelling from the season snapshot (`TeamRef.abbreviation`), so the join
costs no request and does not depend on the table; and it treats an empty squad as an error
rather than an answer, because an empty squad is the only symptom this mistake ever produces.

### POST `/api/player-leaderboard`

**Purpose**: a club's players ranked by one stat, which is a squad's season stat line by
another name (only players with appearances, so 22 to 25 per club against a 23 to 30 squad).
**Body**: `{"league":"HA","team":"OSIK","phase":"HA","metric":"TP","isGoalkeeper":false,"page":1,"pageSize":60}`.
`team` is the **StatNet id** - the opposite of the squad route, which wants the CMS name. Omit
`phase` and the answer is empty with no error. Leaving `team` out gives the league leaderboard
the `/pages/spelare` page shows.
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
**Samples**: [`team-leaderboard.json`](samples/team-leaderboard.json) (`PPPerc`),
[`team-leaderboard.pp.json`](samples/team-leaderboard.pp.json),
[`team-leaderboard.pk.json`](samples/team-leaderboard.pk.json),
[`team-leaderboard.sog.json`](samples/team-leaderboard.sog.json),
[`team-leaderboard.svs.json`](samples/team-leaderboard.svs.json).
**Last verified**: 2026-09-25.
**Cache**: 3 KB, no cache directive.

Metrics from `/pages/stats-lag`: `PPPerc`, `PKPerc`, `W`, `G`, `GA`, `SOG`, `SVSPerc` (may be
incomplete). Rows are `rank`, `teamCode`, `teamID` (the StatNet id), `metric`, `metricValue`,
`teamName`, `teamLogo`. There is **no season parameter**, so this is the running season only.

`PPPerc` and `PKPerc` used to duplicate two standings columns and so earned no call; since
2026-09-25 those columns are empty strings and this route is the only source of them. The
table costs two calls here, shared by the whole league, and a club page's `Shooting` group
costs `SOG` and `SVSPerc` on top.

### POST `/api/play-by-play`

**Purpose**: the game's events - goals with both assists and the running score, penalties with
the infraction and its minutes, shots by outcome, goalie changes, timeouts. The site calls this
its `pollPayload`; the server proxies StatNet and passes the answer through.
**Body**: `{"statNetGameNumber":"23401","homeStatNetId":"AIK","awayStatNetId":"MODO","scheduledDateTime":"2026-09-18T17:00:00Z"}`.
**All four fields are required** - any one missing is
`400 {"error":"Missing required fields: statNetGameNumber, homeStatNetId, awayStatNetId, scheduledDateTime"}`.
The README previously said the three ids were enough; they are not. `playedDateTime`, which the
site also sends, makes no difference. But **only `statNetGameNumber` selects the game**: a wrong
`scheduledDateTime` or a wrong side id still answers the right game, so the other three are
validated and then ignored. An unknown number answers `404 {"error":"StatNet API error: 404"}`.
**Samples**: [`play-by-play.new.final.json`](samples/play-by-play.new.final.json) (AIK 1-3 MoDo,
all three periods, 113 events), [`play-by-play.new.live.json`](samples/play-by-play.new.live.json)
(Östersund v Mora, first period in play),
[`play-by-play.new.not-started.json`](samples/play-by-play.new.not-started.json) (the 404 body).
**Last verified**: 2026-09-25.
**Cache**: 24 KB for a finished game, no cache directive, no validators. While a game is live
the provider reads it whenever the game document moved (a goal, a period, the end) and otherwise
every 30 s, since the document does not move on a penalty or a shot; the site polls every 5 s.

**`statNetGameNumber` is not the slug.** It is on every season page row (364 of them), on the
game page, and in this season's game documents: `/api/game?slug=` carries it
([`game.new.live.json`](samples/game.new.live.json): `23401`), as does
`/api/games?documentIds=`. The finished 2025-26 document in the samples has it `null`. The
provider takes it from the season import and from every game read and keeps it beside the
snapshot; the core model has no field for a provider-private id, so a snapshot restored from the
durable store does not have it, and only a game nobody has read since the cold start sends its
first `events()` to the game page.

**The game page carries the same document inline.** `/games/{slug}/view?_rsc=` has a
`games.play-by-play-2-0` component whose `initialData` is byte-for-byte this response, so the
events are reachable without a POST at all - at 235 KB against 24 KB. That is the fallback when
the game number is not known, and it leaves the number behind for every call after it. The
component is absent from a page for a game that has not started, as are the lineups.

Shape: `{game_info: {game_finished, game_finished_at, decided_in, game_time}, game_events: [...]}`.
Each `game_events` entry is a period: `Period` (`"1"`), `period_label` (`"Period 1"`),
`StartTime`, `EndTime`, `Finisehd` (sic, upstream's spelling) and `Events`.

`game_info.game_time` has been **`{}` in every body observed**, finished games included, so
there is no clock here; `decided_in` matches the game document's (`FT`, `OT`, `SO`).

Events carry `type`, `time` (seconds inside the period, from zero again each period),
`eventDescription`, `team {name, code, statNetId}` and usually `player {statNetId, firstName,
familyName, jerseyNumber}`. Observed `type` values, with the counts from the 113-event sample:
`Shot` (95), `Penalty` (7), `GoalkeeperEvent` (6, with `isEntering`), `Goal` (4), `Timeout` (1).
The site's own filters also name `Save`, `BlockedShot`, `Sent off`, `Expulsion` and
`ShootoutPenaltyShot`, none of which a captured game has produced. May be incomplete.
The provider writes a `GoalkeeperEvent` as "*name* in" / "*name* out" from `isEntering` (the
sample: both starters in at 0, AIK's goalie out and back in the third, both out at the end),
and types a period block holding `ShootoutPenaltyShot` events as the shoot-out whatever its
`Period` number, since how the site numbers that block is not yet known.

`eventDescription` means something different per type, which is the field to be careful with:

| On a | It is | Observed |
|---|---|---|
| `Goal` | the manpower | `EQ`. `PP`, `SH`, `EN` unobserved; `isPenaltyShot` is a **string** `"true"`/`"false"` |
| `Penalty` | minutes and infraction | `2 min, Tripping`, `2 min, Holding the stick`, `Team Penalty 2 min, Too many players on the ice` |
| `Shot` | the outcome | `save` (52), `outside` (27), `covered by player` (13), `frame hit` (2), `on target` (1) |
| `Timeout` | the reason | `Requested timeout` |

So a shot that reached the goalie is `save`, `covered by player` or `on target`, and one that
did not is `outside` (wide) or `frame hit` (the post). A **team penalty has no `player`**, only
a team, so a penalty event can arrive with nobody to attribute it to.

A goal carries `runningScore` (`"1-0"`, home first), `scorerSeasonGoals`, and `Assist1` /
`Assist2` as `{name, jerseyToday, seasonAssists}` - **a name and a shirt number, never an id**,
so an assisting player cannot be joined to a profile the way the scorer can.

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

- [`matcher.rsc.txt`](samples/matcher.rsc.txt) - reduced three-game RSC fixture proving
  full-season extraction and local date filtering. Two of its rows are real (AIK v MoDo on the
  opening night, carrying the real `statNetGameNumber`, and Leksand v Oskarshamn in October);
  the 2026-09-25 return fixture is synthetic and deliberately has **no** game number, which is
  what exercises the play-by-play fallback.
- [`game.new.final.json`](samples/game.new.final.json) — completed game with regulation
  score and three period scores.
- [`game.new.live.json`](samples/game.new.live.json) — AIK v MoDo in the first period,
  2026-09-18 17:35Z (`currentPeriod: "P1"`, `currentPeriodStart`, `isCompleted: false`).
- [`games.by-document-ids.live.json`](samples/games.by-document-ids.live.json) — three of
  the night's games from `/api/games?documentIds=`, one scheduled and two in play.
- [`game-view.lineups.rsc.txt`](samples/game-view.lineups.rsc.txt) — the lineup line of
  the AIK v MoDo game page: 22 home and 26 away rows (four officials among the away ones).
- [`game-view.play-by-play.rsc.txt`](samples/game-view.play-by-play.rsc.txt) - two RSC lines of
  the same page: the one carrying `statNetGameNumber` and the `games.play-by-play-2-0` component
  whose `initialData` is the whole play-by-play. This is the route taken when the game number is
  not known.
- [`play-by-play.new.final.json`](samples/play-by-play.new.final.json) - AIK 1-3 MoDo finished:
  three periods, 113 events, four goals with assists and running scores, seven penalties
  including a team penalty with no player, and shots across all five outcomes.
- [`play-by-play.new.live.json`](samples/play-by-play.new.live.json) — Östersund v Mora,
  first period in progress: goalie entries, shots and a goal with two assists.
- [`play-by-play.new.not-started.json`](samples/play-by-play.new.not-started.json) - the `404`
  body for a game StatNet has no sheet for, which is every game before its opening face-off.
- [`league-standings-all-time.json`](samples/league-standings-all-time.json) - the table after
  three rounds, and [`league-standings-all-time.past-season.json`](samples/league-standings-all-time.past-season.json)
  the finished 2025-26 one.
- [`tabell.standings.rsc.txt`](samples/tabell.standings.rsc.txt) - the standings line the table
  page carried until 2026-09-25, with its 19-key `teamDisplayMap`. **Historical**: the page no
  longer renders it. Kept because it is the only record of the `teamId` column the current route
  does not have.
- [`player-profile.rsc.txt`](samples/player-profile.rsc.txt) - Patrik Zackrisson's profile
  line: CMS record, current-season totals and 29 season rows across five competitions.
- [`all-players.team.json`](samples/all-players.team.json) - Leksand's 26-player squad from
  `/api/all-players`, with birth dates, heights, shooting hands and headshots.
- [`all-players.team-modo.json`](samples/all-players.team-modo.json) - MoDo's 29, asked for as
  `MoDo`; the club whose CMS name and StatNet id differ.
- [`all-players.unknown-team.json`](samples/all-players.unknown-team.json) - the empty `200`
  a misspelled team code answers with, which is also what the StatNet id answers.
- [`player-leaderboard.team.json`](samples/player-leaderboard.team.json) - Leksand's 22
  scoring skaters, and [`player-leaderboard.goalkeepers.json`](samples/player-leaderboard.goalkeepers.json)
  its two goalkeepers by save percentage.
- [`team-leaderboard.json`](samples/team-leaderboard.json),
  [`.pp`](samples/team-leaderboard.pp.json), [`.pk`](samples/team-leaderboard.pk.json),
  [`.sog`](samples/team-leaderboard.sog.json) and [`.svs`](samples/team-leaderboard.svs.json) -
  the 14 clubs by power play, penalty kill, shots on goal and save percentage.
- The older Sportality captures remain in `samples/` as migration history; the current
  provider does not consume them.

## Core model mapping

| Core type | Source | Fields | Gaps |
|---|---|---|---|
| `Game` | season page `games[]`; `/api/game?slug=` for a due, live or just-finished one | `slug` is the id, `scheduledDateTime`, `venue`, `round`, `attendance`, `homeStatNetId` / `awayStatNetId` are the team ids, `homeScore` / `awayScore` | none |
| `GameState` | `/api/game` | `currentPeriod` (`""`, `P1`, `P2`, `P3`), `currentPeriodStart`, `playedDateTime`, `isCompleted` | no intermission marker, and the documents froze under opening-night load, so `LIVE_UPDATES` is not claimed |
| `GameEnding` | `decidedIn` | `FT`, `OT`, `SO` | |
| `Clock` | nowhere | | not published anywhere on this site: the game document has none and the play-by-play's `game_info.game_time` has been `{}` in every body seen, finished games included. Events carry seconds-in-period instead |
| `PeriodScore` | `homeScoreP1`-`P3`, `homeOtScore`, `homeSoScore` and the away pairs | | |
| `GameEvent` | `POST /api/play-by-play`, or the game page's `games.play-by-play-2-0` `initialData` when the game number is unknown | goals with assists and running score, shots by outcome, penalties with minutes and infraction, goalie changes, timeouts | assists carry a name and shirt number but no id, so only the scorer joins to a profile; StatNet answered one call in three under opening-night load |
| `Lineup` | game page RSC (`games.game-lineup-2-0`) | `positionToday`, `line`, `jerseyToday`, `isCaptain` / `isAssistant`, `isStarting`, `isExtraPlayer` | officials are in the same arrays and are dropped |
| `StandingsTable` | `POST /api/league-standings-all-time`, joined to the season snapshot for ids and crests; `POST /api/team-leaderboard` for the two percentages | rank, played, wins, losses, `otherLosses` from `overtime_losses + shootouts_losses`, points, goals for and against, difference; `overtime_wins`, `shootouts_wins`, `shootouts_losses`, `powerPlay`, `penaltyKill` as `extra` | one table, no groups. Past seasons are served, but their special teams are not (the leaderboard takes no season), and a club not in this season's snapshot keeps the table's display code instead of a StatNet id |
| `Team` | season page team objects, keyed by StatNet id | `name`, `shortName`, `teamArena`, `logo`, `mainColorHex` / `alternateColorHex`, `arenaAddress`, coordinates | no conference or division (a single table), country is always SWE |
| `TeamSeasonStats` | standings row, plus `POST /api/team-leaderboard` (`PPPerc`, `PKPerc`, `SOG`, `SVSPerc`) and `POST /api/player-leaderboard` (`TP`, and `SVSPerc` for goalies) | record, goals, special teams, shooting, and the club's leading scorer and goaltender | one call per metric, all four asked for together and none allowed to fail the screen; current season only |
| `Player` as a roster row | `POST /api/all-players`, keyed by the CMS name the season snapshot supplies | number, position, birth date, country, height, weight, shooting hand, youth club, headshots | the row has no StatNet id, only a `slug` and the id inside the headshot file name, so a roster row opens a profile but does not join to a lineup |
| `Player` as a profile | `/players/{slug}` RSC (`team.player-profile`) | bio, club, headshot; the model has no field for the season or career stat lines | keyed by the page slug, while lineups and events key players by StatNet id: a lineup entry carries both, nothing else bridges them |

## Changelog

| Date | Change |
|---|---|
| 2026-09-25 | **The game document carries `statNetGameNumber` after all** (this season's; `game.new.live.json` has `23401`), so the provider takes it from every game read and a cold start's first timeline is the POST rather than the game page; the section that said otherwise is corrected. The live flow now carries the play-by-play itself, read when the document moved and otherwise every 30 s, because the document does not move on a penalty. |
| 2026-09-25 | **The league table stopped being a `GET`.** `/pages/tabell` went client-rendered and its `stats.league-standings` component is gone, which broke `standings()` and `teamStats()`; the rows moved to `POST /api/league-standings-all-time`, which drops the StatNet id and the display map (both now joined from the season snapshot) and leaves the two special-teams columns empty (now from the team leaderboard), and in exchange serves past seasons the old page refused. Wired the four POST routes through the new read-only `QueryFetcher`: `ROSTER`, `EVENTS` (also reachable from the game page's `initialData`), `LIVE_UPDATES`, and the leaderboard columns on `TEAM_STATS`. Corrected the play-by-play body - all four fields are required, not three, and only `statNetGameNumber` selects the game - and recorded that `game_time` is always empty, so no `CLOCK`. `tools/api-health` gained a `body` field, so all seven POST routes now carry checks instead of being verified by hand. |
| 2026-09-24 | Wired the mapping: `STANDINGS` from the table page, `TEAM` / `TEAM_SCHEDULE` / `TEAM_STATS` from the season snapshot and that table row, `PLAYER` from the profile page. The crosswalk now keys HockeyAllsvenskan by StatNet code in its own namespace, so its clubs carry a `clubId` again (they had none since the migration). Squads stay unwired: POST. |
| 2026-09-23 | Mapped the league table (`/pages/tabell` RSC), squads (`POST /api/all-players`), per-club player and team stats (`POST /api/player-leaderboard`, `POST /api/team-leaderboard`) and player profiles (`/players/{slug}` RSC). Recorded the two club-code systems and which route wants which, and that the season page already carries the full club directory. Docs, samples and health checks only: no provider change. |
| 2026-09-18 (evening) | Opening night observed through the provider: live document shape sampled, lineups mapped from the game page (`LINEUPS` + `LINE_GROUPS`), `/api/games?documentIds=` found, play-by-play POST and MQTT push documented. The `HA` game type is no longer shown as a competition label. |
| 2026-09-18 | The season snapshot was frozen at first import (no scores, no live or final states ever). It is now re-imported after six hours and due/live/recently-finished games are refreshed through `/api/game` and merged back; stale snapshot served when the page is unreachable. Payload sizes and the absent list route recorded. |
| 2026-09-15 | Remapped schedule and game detail after the Next.js/Strapi migration; added one-time normalized Room season storage. |
| 2026-09-11 | Captured the former Sportality API before the migration. |
