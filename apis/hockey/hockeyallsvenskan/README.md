# HockeyAllsvenskan API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | Sweden (second tier) |
| **Official site / base URL** | `https://hockeyallsvenskan.se` |
| **Auth** | None |
| **Formats** | Next.js React Server Components + JSON |
| **Last verification** | 2026-09-18 (opening night, six games in play) |
| **Provider status** | Schedule, game detail and lineups; season snapshot refreshed per game; live state from the game document (period and score, no clock); play-by-play and push documented, not wired |

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

The provider advertises `GAMES_BY_DATE`, `GAME`, `PERIOD_SCORES`, `LINEUPS` and
`LINE_GROUPS` (goalies with the starter first, then `Line n` / `Pairing n` as the sheet has
them; officials left out). `LIVE_UPDATES` is not claimed: the game document says which period
is on and the score, but has no clock, and on the opening night it lagged the ice by tens of
minutes (see Live).

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
- The older Sportality captures remain in `samples/` as migration history; the current
  provider does not consume them.

## Changelog

| Date | Change |
|---|---|
| 2026-09-18 (evening) | Opening night observed through the provider: live document shape sampled, lineups mapped from the game page (`LINEUPS` + `LINE_GROUPS`), `/api/games?documentIds=` found, play-by-play POST and MQTT push documented. The `HA` game type is no longer shown as a competition label. |
| 2026-09-18 | The season snapshot was frozen at first import (no scores, no live or final states ever). It is now re-imported after six hours and due/live/recently-finished games are refreshed through `/api/game` and merged back; stale snapshot served when the page is unreachable. Payload sizes and the absent list route recorded. |
| 2026-09-15 | Remapped schedule and game detail after the Next.js/Strapi migration; added one-time normalized Room season storage. |
| 2026-09-11 | Captured the former Sportality API before the migration. |
