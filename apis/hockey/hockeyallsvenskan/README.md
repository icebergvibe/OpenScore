# HockeyAllsvenskan API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | Sweden (second tier) |
| **Official site / base URL** | `https://hockeyallsvenskan.se` |
| **Auth** | None |
| **Formats** | Next.js React Server Components + JSON |
| **Last verification** | 2026-09-18 |
| **Provider status** | Schedule and basic game detail remapped; season snapshot refreshed per game; live detail pending |

## 2026 site migration

The former `www.hockeyallsvenskan.se/api/sports-v2` and `/gameday` Sportality
mapping stopped serving the site. HockeyAllsvenskan now uses a Next.js frontend and
Strapi-shaped API responses. SHL still uses the old mapping, so the providers no
longer share an implementation.

## Provider routes

| Capability | Request | Mapping |
|---|---|---|
| Complete current-season schedule | `GET /pages/matcher?_rsc=openscore` with `RSC: 1` | The single embedded `games` array is decoded and mapped to `Game`. The response contains all 364 regular-season fixtures (1.58 MB decoded, 127 KB gzipped, `Cache-Control: no-store`; measured 2026-09-18); a page `date` parameter is only a UI filter. Played games carry their score, period scores and `decidedIn` here too. |
| One game | `GET /api/game?slug=20260918-aik-modo` | `data[0]` contains stable slug, season, StatNet game/team IDs, UTC time, teams/logos, venue, score, period scores and completion/decision fields. About 700 bytes, no cache directive. There is no list form: `?date=`, `/api/games` and `?season=` all answer `400` (2026-09-18). |

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

The provider deliberately advertises only `GAMES_BY_DATE`, `GAME`, and
`PERIOD_SCORES`. The new site also exposes play-by-play and lineups through POST and
GraphQL routes, but those remain disabled until live-state samples and the required
read-only POST transport are implemented and replay-tested.

## Captured samples

- [`matcher.rsc.txt`](samples/matcher.rsc.txt) — reduced two-date RSC fixture proving
  full-season extraction and local date filtering.
- [`game.new.final.json`](samples/game.new.final.json) — completed game with regulation
  score and three period scores.
- The older Sportality captures remain in `samples/` as migration history; the current
  provider does not consume them.

## Changelog

| Date | Change |
|---|---|
| 2026-09-18 | The season snapshot was frozen at first import (no scores, no live or final states ever). It is now re-imported after six hours and due/live/recently-finished games are refreshed through `/api/game` and merged back; stale snapshot served when the page is unreachable. Payload sizes and the absent list route recorded. |
| 2026-09-15 | Remapped schedule and game detail after the Next.js/Strapi migration; added one-time normalized Room season storage. |
| 2026-09-11 | Captured the former Sportality API before the migration. |
