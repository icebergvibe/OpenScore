# Fogis livescore XML (SvFF / svenskfotboll.se — `c01.fogis.se`)

| | |
|---|---|
| **Sport** | Football (association); the same feed carries futsal (`football-type-id`) |
| **Country / region** | Sweden — **every** SvFF competition: Allsvenskan, Superettan, Ettan, Div 1–3 (+ district Div 4+), Damallsvenskan, Elitettan, women's Div 1, **Svenska Cupen** (men's and women's), youth (P/F16–19) and district competitions |
| **Official site** | https://www.svenskfotboll.se/livescore/ (Svenska Fotbollförbundet, the FA) |
| **Base URL** | `https://c01.fogis.se/fogistemplates.se/livescore/xml/` — six file types addressed by name (see Endpoints) |
| **Auth** | None. No key, no cookie, no `Origin`/`Referer` check |
| **Format** | **XML** (`application/xml; charset=utf-8`), gzip on request (115 KB → 19 KB). Microsoft IIS 10 behind an Azure-style front cache (`age`, `request-context` headers) |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *` on every response. No preflight support (`OPTIONS` → 405 `Allow: GET`), which is fine for a plain `GET` without custom headers |
| **WAF / UA requirement** | A **non-empty `User-Agent` is required** — empty or missing UA → `403`. Any value works (`OpenScore/0.1` used throughout) |
| **Conditional requests** | None: no `ETag`, no `Last-Modified`, `If-Modified-Since` → `200`. `Cache-Control: public, max-age=45` on everything; `HEAD` → 405 |
| **Last full verification** | 2026-09-12 |
| **Status** | ✅ verified pre-match, **live** (first and second half, day overview and game document — Hammarby–Brommapojkarna and AIK–Västerås, 2026-09-13, polled at the 10 s floor without a missed tick) and full-time (league and cup incl. extra time + shootout). The core's primary source for Swedish football games |

## Overview

Fogis is the Swedish FA's competition-administration system: every match in Sweden is
created, refereed and reported in it, and the match ids, team ids and player ids it
issues are the ones you see on svenskfotboll.se *and* in the SEF GraphQL API
([Allsvenskan](../allsvenskan/README.md) — `fogisId` there **is** the id here). This
feed is the read-side of Fogis's live reporting: a small IIS service that renders
Fogis data as XML "files" for the livescore page of svenskfotboll.se and the 23
district sites. It was found by reading `<html data-hego-xml-basepath=…>` on
`/livescore/` and the `datasources/hego-xml` module of
`/ui/dist/js/livescore.min.js`, which also documents the status codes, event types
and the 45 s refresh cycle below.

What it is good for: one source for **all Swedish football**, including the
competitions no league body publishes (Svenska Cupen — the 2026/27 edition is absent
from the SEF API but here; Ettan; Div 1–3; women's leagues). Events carry the game
clock to the **second** plus the wall-clock time they were entered, half-time scores
are explicit, and full-match lineups come with per-player stats.

What it is not: there is no table/standings file and no player or team resource —
use the SEF API for those where it applies. It is also a rendering of referee/team
reports, so event richness depends on who reports (`livescorereporttype`): the
elite tiers get shots, corners, free-kicks and substitutions with names; lower tiers
get goals, cards and subs.

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Association (`forbundId`) | integer; **1 = SvFF (national)**, others are the district FAs (`association-id` on games: 11 Medelpad, 12, 20, 23 … seen) | `1` | Constant. `overview-{forbundId}-…` for a district returns only that district's competitions (Div 4+, district youth); the national `1` does **not** include those |
| Tournament (competition) | integer, season-specific; also `competition-id` on games | `133348` Allsvenskan 2026 · `133340` Superettan 2026 · `133338`/`133339` Ettan Norra/Södra · `133439` Elitettan · `133440` OBOS Damallsvenskan · `137810` Svenska Cupen 2026/27 omg. 1-2 (men) · `137816` Svenska Cupen 2026/27 omg. 1-3 (women) | `tournaments-{forbundId}.xml` (161 entries for `1`) or any game's `<tournament id>` |
| Competition category | integer, stable across seasons (the "kind" of competition) | `720` Allsvenskan · `740` Superettan · `724` Ettan · `727` Div 1 dam · `728` Div 2 · `729` Div 3 · `721` Damallsvenskan · `16471` Elitettan · `742` Svenska Cupen herr · `726` Div 2 dam | `competition-category-id` on `<game>`, `competitionCategoryId` on `<tournament>` |
| Game | integer — **the Fogis match id** (= SEF `id`/`fogisId`) | `6536131` GIF Sundsvall–Örebro SK, Superettan round 23 | `overview-…`, `schedule-{tournamentId}.xml`, `changes-…` |
| Team | integer — Fogis team id (= SEF `fogisId`/`teamId`) | `25513` GIF Sundsvall · `66036` AIK · `25507` IK Brage | `<team id>` anywhere; `participationId` is the team-in-this-competition id. Logo URLs use a *different* club id (`…/img/teams/9441.png`) |
| Player | integer Fogis player id (= SEF player `id`/`fogisId`) + `player-guid` (UUID used for photos `staticcdn.svenskfotboll.se/img/players/{guid}.jpg` and the `/spelarfakta/` pages) | `680829` Rasmus Wiedesheim-Paul | `lineup-…` `<player id>`, `<participant id>` on events |
| Event | integer, global | `12131360` | `<event id>` |
| Date | `YYYYMMDD` in file names; `YYYY-MM-DD` + `HH:MM:SS` in bodies — **all Europe/Stockholm local time** (`created="2026-09-12 09:22:25"` was sent with `Date: 07:22:24 GMT`) | `20260912` | — |

## Discovery path

1. **What exists:** `tournaments-1.xml` → every national-level tournament of the
   current season with its teams (ids + names). Districts: `tournaments-{id}.xml`.
2. **Fixtures for one competition:** `schedule-{tournamentId}.xml` → rounds → games
   with date/time, stadium, status and score. 240 games for Superettan 2026; the cup
   file only holds the current stage (30 round-2 games).
3. **What is on today:** `overview-1-{YYYYMMDD}.xml` → every national game of that
   day (90 on a September Saturday) with tournament, teams, status, score, referee
   and stadium. Works for any date of the **current season** (2026-02-21 cup group
   stage → 2026-11-29 last round); earlier dates return an empty `status="403"`,
   future dates an empty `status="200"`.
4. **One match:** `game-info-{gameId}.xml` (status, score incl. half-time, referee
   crew, attendance, team stats, **events**) and `lineup-{gameId}.xml` (formation,
   XI + bench, per-player stats). Both exist from the moment the match is in Fogis;
   pre-match they carry empty `<events/>`/`<lineup/>`.
5. **Live:** the site fetches `changes-1.xml` every **45.25 s** (a manifest of
   `game-info-*`/`lineup-*` files with their last-change time) and re-fetches only
   the files that changed. Do the same — it is one small request instead of one per
   match, and the 45 s `max-age` means polling faster returns cached bodies anyway.

Recommended poll interval: **45 s** for `changes-{forbundId}.xml` (matches the server
cache), then fetch the changed `game-info`/`lineup` files; re-read `overview` once a
minute if you need the day view. Cache `tournaments` and `schedule` for hours.

## Endpoints

All are `GET https://c01.fogis.se/fogistemplates.se/livescore/xml/{file}` with a
`User-Agent`. The service is dynamic — unknown ids and dates return **`200`** with an
empty body and an inner `status="403"` attribute, not HTTP 404 (a truly unknown
file *name* is an IIS HTML 404). `http://` redirects `301` to `https://`. The JS
also knows hosts `c02…c06.fogis.se` for `http://c01` sharding; they do not resolve
any more — use `c01` only.

### `overview-{forbundId}-{YYYYMMDD}.xml`

| | |
|---|---|
| **Purpose** | All games of one association on one day, with live status/score |
| **Parameters** | `forbundId` — 1 national, district ids otherwise; `YYYYMMDD` — local date |
| **Samples** | [`samples/overview.today.xml`](samples/overview.today.xml) (2026-09-12, 90 games, all `NOT_STARTED` at 07:10Z) · [`samples/overview.district.xml`](samples/overview.district.xml) (`forbundId` 11 Medelpad, 5 games) · [`samples/overview.future-empty.xml`](samples/overview.future-empty.xml) · [`samples/overview.past-403.xml`](samples/overview.past-403.xml) |
| **Last verified** | 2026-09-12 |
| **Cache** | `public, max-age=45` |

**Response shape**

```
<todays-games created status="200|403">
  <game-info created supplier="FOGIS" livescorereporttype="1|2" status="200">
    <game id association-id football-type-id competition-id competition-category-id date start>
      <tournament id name number competitionCategoryId classname supplier/>
      <teams><team id short-name long-name home-team="true|false" participationId teamImageUrl teamImageSmUrl/> ×2</teams>
      <status id desc/>
      <score home-team away-team home-team-half-time away-team-half-time/>
      <referees name/>
      <stadium name spectators/>
    </game>
  </game-info> …
```

`livescorereporttype` `1` = full live reporting (Allsvenskan, Superettan, Ettan,
Damallsvenskan, Elitettan), `2` = basic (Div 1 dam, Div 2, Div 3). The root
`created` is the render time; each `game-info created` is the last Fogis edit of
that match. No events here — one `game-info-{id}.xml` per match for those.

### `game-info-{gameId}.xml`

| | |
|---|---|
| **Purpose** | One match: header, status, score, officials, attendance, team stats, event log |
| **Samples** | [`samples/game-info.pre.xml`](samples/game-info.pre.xml) (AIK–Västerås SK, 7.5 h before KO) · [`samples/game-info.final.xml`](samples/game-info.final.xml) (GIF Sundsvall 0–3 Örebro SK, Superettan, 68 events) · [`samples/game-info.final.cup-shootout.xml`](samples/game-info.final.cup-shootout.xml) (Athletic Eskilstuna–IK Oddevold, Svenska Cupen 2026/27 round 2, 4–4 → 10–11 after extra time and penalties, 46 events) · [`samples/game-info.unknown-id.xml`](samples/game-info.unknown-id.xml) (`game-info-1.xml`: the `status="403"` stub) |
| **Last verified** | 2026-09-12 |
| **Cache** | `public, max-age=45` |

**Response shape**

```
<game-info created supplier livescorereporttype status>
  <game …same as overview…>
    <tournament/> <teams/> <status id desc/> <score …/>
    <referees name place referee-role-description assistant1-name … assistant2-… fourth-name …/>
    <stadium name spectators/>
    <stats home-corners home-ball-possession home-offsides home-yellow-cards home-red-cards
           home-red-cards-from-bookings home-freekicks-awarded home-finishes home-shots-on-goal
           home-shots-post home-shots-missed home-normal-goals home-penalty-goals
           home-freekick-goals home-cornor-goals home-assists  away-… (same 16)/>
    <ball-possession/>                       ← always empty
  </game>
  <events>
    <event id phase game-time="mm:ss" game-minute-for-web game-time-for-web day-time="HH:MM:SS"
           event-type-id type type-desc home-team="true|false" home-score away-score
           x-position y-position goal-type-ix goal-type-desc>
      <participants><participant id player-guid number given-name surname event-type-id type type-desc is-staff-member/>…</participants>
    </event> …                               ← newest first
  </events>
</game-info>
```

- `game-time` is the running clock of the phase in `mm:ss` (`96:05`, `134:00` in a
  shootout); `game-minute-for-web` is the display minute (`90+7`, `45+1`, `135`);
  `day-time` is the local wall-clock when the event was entered — the gap between two
  `HALFSTARTED`/`HALFENDED` stamps is the real period length.
- `phase`: `1` first half, `2` second half, `3`/`4` extra-time halves, `5` penalty
  shootout. `HALFSTARTED`(31)/`HALFENDED`(32) events bracket every phase.
- `home-score`/`away-score` on each event = score *after* it; on shootout events they
  count shootout goals, and the top-level `<score>` **includes shootout goals** (the
  cup sample's 10–11 is 4–4 plus 6–7 on penalties) — `home-team-half-time` is the
  only untouched intermediate score.
- `x-position`/`y-position` are `0` (elite) or `-1` (not recorded); no real pitch
  coordinates were seen.
- `stats` possession is always `0` (Fogis has no possession feed).

**Observed event types** (`event-type-id` → `type` `type-desc`; the JS enum adds more)

| id | type | desc | participants |
|---|---|---|---|
| 4 | `T` | Freekick | `F` Freekick for |
| 5 | `C` | Corner | (unknown) |
| 6 | `G` | Goal | `S` Scorer (+ second participant with `event-type-id` 11 = assist) |
| 39 | `G` | Goal (seen once, cup) | `S` Scorer |
| 14 | `g` | Penalty goal | `S` Scorer; `goal-type-ix` 5 |
| 12 / 13 | `F` | Finish — 12 off target (`O` Shot missed), 13 on target (`S` Shot saved) | |
| 16 | `S` | Substitution | `O` Substitutes Out (16) + `I` Substitutes In (17) |
| 20 | `P` | Card | `Y` Yellow Card; JS: `R` Red, `YR` Yellow-red; ids 2/8/9 = sending-off |
| 21 / 22 | `pg` / `pm` | Penalty Shooting, goal / miss | `S` Scorer / `O` Shot missed |
| 31 / 32 | `HALFSTARTED` / `HALFENDED` | Half started / ended | dummy participant "Okänd spelare" |

JS-only (`models/event-model`): `O` Offside, `f` Penalty (awarded), `g10` 10 m
penalty goal (futsal), `TIMEOUT`, `HALFTIME`; event-type ids 18/19/26 penalty
missed (outside/saved/post), 25 shot hit post, 23 match ended, 28/29 set-piece and
free-kick goals, 40/41 10 m penalty goal/miss. `goal-type-ix`: 1 Normal, 2 Freekick,
3 Corner, 4 Own, 5 Penalty, 6 Set piece, 11 Normal (header), 12 Penalty 10 m,
16 Set piece (header). Observed: 1, 5, 11.

### `lineup-{gameId}.xml`

| | |
|---|---|
| **Purpose** | Formation, starting XI and bench per team with per-player match stats |
| **Samples** | [`samples/lineup.pre.xml`](samples/lineup.pre.xml) (empty `<lineup/>`, `formation-id="0"`) · [`samples/lineup.final.xml`](samples/lineup.final.xml) (20 players per side) |
| **Last verified** | 2026-09-12 |
| **Cache** | `public, max-age=45` |

```
<game id created supplier>
  <teams>
    <team id home-team name formation-id formation-desc="4-4-2">
      <lineup>
        <player id player-guid number given-name surname position="1…11|Sub" formation-group
                first-in-formation-group is-captain is-goalkeeper substitution="0|1"
                goals assists shots shots-on-goal shots-post shots-missed goals-normal goals-penalty
                goals-corner goals-freekick offsides freekicks-awarded freekicks-against
                penalties-awarded penalties-against bookings red-card red-card-from-bookings image/>
```

`position` is the formation slot (`1` = GK … `11`) or `Sub`; `formation-group`
1 GK, 2 defence, 3 midfield, 4 attack, 12 bench; `substitution="1"` marks players
who came on **or** went off. `formation-id` 1 = 4-4-2, 8 = 5-3-2 (full table in the
JS: 2 4-4-2 diamond, 3 4-5-1, 4 4-3-3, 5 4-2-3-1, 6 3-5-2, 7 3-4-3, 9 4-4-1-1,
10 4-3-1-2, 11 5-4-1, 12 5-2-3, 13 4-1-4-1, 14 4-2-4, 15 4-1-3-2). No coach/staff
in the samples (`coach-name` is read by the JS but absent).

### `changes-{forbundId}.xml`

| | |
|---|---|
| **Purpose** | Manifest of recently changed `game-info`/`lineup` files — the live poll target |
| **Sample** | [`samples/changes.xml`](samples/changes.xml) (`forbundId` 1, 2026-09-12 07:26Z) |
| **Last verified** | 2026-09-12 |
| **Cache** | `public, max-age=45` |

`<change-files><file file-name="game-info-6547937.xml" date-time="2026-09-11 20:24:40" supplier="FOGIS"/>…`
— pairs of `game-info-`/`lineup-` entries, ordered by the overview's game order, not
by time; entries go back days (a January edit was listed). Compare `date-time` with
the last one you fetched (the site adds a 5 s slack) and re-fetch only those files.

### `tournaments-{forbundId}.xml`

| | |
|---|---|
| **Purpose** | All tournaments of the season for one association, with their teams |
| **Samples** | [`samples/tournaments.xml`](samples/tournaments.xml) (`1`: 161 tournaments incl. youth, futsal, friendlies) · [`samples/tournaments.district.xml`](samples/tournaments.district.xml) (`11`: 7 Medelpad tournaments) |
| **Last verified** | 2026-09-12 |

`<tournaments><tournament id name><teams><team id name/>…` — ids and names only;
the list is not filtered to livescore-reported competitions (`tournaments-1-2026.xml`
→ empty `<tournaments/>`; `tournaments.xml` → 404).

### `schedule-{tournamentId}.xml`

| | |
|---|---|
| **Purpose** | Full fixture list of one tournament grouped by round, with status and score |
| **Samples** | [`samples/schedule.superettan.xml`](samples/schedule.superettan.xml) (30 rounds, 240 games) · [`samples/schedule.svenska-cupen.xml`](samples/schedule.svenska-cupen.xml) (one round, 30 games) |
| **Last verified** | 2026-09-12 |

```
<schedule created supplier>
  <tournament tournament-id tournament-name>
    <round id="1" desc="Omgång 1 Superettan">
      <game id date start stadium status="FINISHED|NOT_STARTED|…" home-score away-score association-id>
        <home-team id long-name short-name/> <away-team …/>
```

`schedule-1.xml` (a *tournament* id of 1) returns a 2017 stub — the first path
segment is the tournament id, not the association. `schedule-1-20260912.xml` → `400`.
`status` is the textual state (`NOT_STARTED`, `FINISHED`, `POSTPONED`, …); the core reads
this file for a team's season (`SwedishLeagueProvider.teamSchedule`), keeping it 5 min.

### Related, on svenskfotboll.se (Cloudflare)

`GET https://www.svenskfotboll.se/api/livescore-ticker/` — JSON
([`samples/livescore-ticker.json`](samples/livescore-ticker.json)): today's games with
id, local time, `isLive`/`isFinished`, scores, team abbreviations and logos;
`Cache-Control: public, max-age=30`, CORS not set. The site's ticker polls it every
15 s. Useful only as a compact "today" list; everything else on that host is HTML.

## Game states

`<status id desc>` (`models/game-model.status_codes` in the JS; observed values in bold):

| id | desc | meaning |
|---|---|---|
| **7** | **`NOT_STARTED`** | pre-match (also how unstarted games appear in `schedule`) |
| 1 | `FIRST_HALF_IN_PROGRESS` | live |
| 3 | `HALFTIME` | interval |
| 2 | `SECOND_HALF_IN_PROGRESS` | live |
| 8 / 9 | `EXTRA_TIME_ONE/TWO_IN_PROGRESS` | cup extra time |
| 13 | `EXTRA_TIME_OR_PENALTIES_IN_PROGRESS` | |
| 10 | `GOLDENGOAL_IN_PROGRESS` | legacy |
| 11 | `PENALTIES` | shootout |
| **6** | **`FINISHED`** | final — the only terminal state the JS treats as "ended" |
| 4 | `POSTPONED` | |
| 5 | `CANCELED` | shown as finished by the site |
| 12 | `RESCHEDULED` | treated as not started |
| 80–84 | `THIRD…SEVENTH_HALF_IN_PROGRESS` | futsal/youth formats |

The JS derives the display clock from the latest `HALFSTARTED` event's `day-time`
plus elapsed wall-clock (`game-timer-model`), not from a clock field — there is none.
Live transitions and the cadence of `created`/event updates were observed on 2026-09-13
(see "Live, verified" under Core model mapping): `HALFSTARTED` appeared 1–3 min after the
real kick-off, goals within a tick of the site's ticker.

## Quirks & gotchas

- **Local time everywhere.** `date`/`start`/`created`/`day-time`/`date-time` are
  Europe/Stockholm (CET/CEST) without offset; convert before comparing with the SEF
  API's UTC `startDate`.
- **Season-scoped history.** The overview only serves the current season
  (2026-02-21 → 2026-11-29 verified; 2025-12-01 and earlier → `status="403"`).
  `game-info-{id}` for older ids was not probed.
- **Soft errors.** HTTP `200` with `status="403"` (unknown id/old date) or an empty
  document; check the inner attribute, not the HTTP code.
- **No conditional requests**, 45 s server cache. `age` tells you how stale the
  cached copy is.
- **Empty `User-Agent` → 403.**
- `<score>` includes shootout goals; `<ball-possession/>` and `*-ball-possession`
  are always empty/`0`; `x/y-position` carry no coordinates.
- Events are newest-first; the `HALFENDED` of the last phase is effectively the
  full-time marker (no separate "match ended" event was observed, though id 23 exists
  in the JS).
- `changes-*.xml` lists files by overview order, not by change time — scan the whole
  file (16 KB for 90 games).
- District overviews (`forbundId` ≠ 1) contain only district-run competitions; games
  run nationally by SvFF (`association-id="1"`) appear only in `overview-1-…`, even
  when played in that district.
- Team logo ids in `teamImageUrl` are a third club id (not Fogis team id, not SEF
  `everySportId`).

## Out of scope

- `https://www.svenskfotboll.se/api/livescore/game-info/?gameId=…` and
  `/api/livescore/form/?gameId=…` — return **HTML fragments** for the match page.
- `/api/comp-find/*`, `/api/personalization/*` — site search and "my teams"
  (personalization is cookie-based).
- The Episerver pages (`/serier-cuper/spelprogram/…`) — server-rendered HTML behind a
  Cloudflare challenge.
- Standings: not in this feed. Use the SEF API for Allsvenskan/Superettan/
  Damallsvenskan; other tiers have no key-less table source found yet.
- `c02…c06.fogis.se` — dead hosts from the JS sharding logic.

## Core model mapping

This feed is the core's **primary source for Swedish football**: `SwedishLeagueProvider`
serves `allsvenskan`, `superettan` and `svenska-cupen` from it (tables from allsvenskan.se's
API, which has no reliable live path — see that README), and `FogisProvider` (`fogis`) is the
umbrella over every other tier. The three competitions are found in `tournaments-1` by name
(`Allsvenskan 2026`, `Superettan 2026`) and, for the cup — whose round groups are named
identically for men and women (`Svenska Cupen 2026/27 omg. 1-2` / `omg. 1-3`) — by sharing
teams with the men's leagues (55 of 96 first-round teams on 2026-09-13; the women's group
shares none).

**Live, verified 2026-09-13** (Hammarby–Brommapojkarna and AIK–Västerås, polled at the 10 s
floor for the whole first half alongside allsvenskan.se): every tick answered, `HALFSTARTED`
appeared 1–3 min after the real kick-off, the derived minute advanced every tick, goals
appeared within a tick of the ticker. The day `overview` carries each live game's
`HALFSTARTED` (with `day-time`) and goals, so the minute is available on the day view without
opening the game; it carries no `HALFENDED`, so a second half's first-half score comes from the
`…-half-time` attributes ([`samples/overview.live.xml`](samples/overview.live.xml)).

| Core concept | Source | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `tournaments-1` | `tournament id`/`name`; `competition-category-id` for the stable "kind" | Tournament ids are per season; the cup is split into per-stage tournaments |
| Game (id, teams, start time) | `schedule-{t}`, `overview-{f}-{d}` | `game id`, `date` + `start` (local), `home-team`/`away-team` or `teams/team[@home-team]` | Convert local → UTC |
| GameState | `overview`, `game-info` | `status id`/`desc` | Enum above; `FIRST_HALF_IN_PROGRESS` / `SECOND_HALF_IN_PROGRESS` observed live 2026-09-13 |
| Score by period | `game-info` | `score home-team/away-team`, `…-half-time`; per-event `home-score`/`away-score` at `HALFENDED` | Total includes shootout goals |
| Clock / period | `game-info` events | `phase`, `game-time` (mm:ss), `day-time` of `HALFSTARTED` → derive running clock | No clock field; derive from last `HALFSTARTED` wall-clock |
| GameEvent | `game-info` events | `event-type-id`/`type`, `game-time`, `home-team`, participants (`id`, names, `number`, `type`) | Assist = second participant of a goal |
| Lineups | `lineup-{id}` | `formation-desc`, `player position`/`Sub`, `is-captain`, `is-goalkeeper`, per-player stats | Empty before publication |
| Team stats | `game-info` `stats` | corners, shots (finishes/on goal/post/missed), cards, free-kicks, goal types | No possession |
| Team | any `<team>` | `id`, `short-name`/`long-name`, `participationId`, logo URLs | No team resource |
| Player | `lineup`, events | `id`, `player-guid`, names, `number` | No player resource; photo by guid |
| Standings | — | — | Not available |

## Changelog

| Date | Change |
|---|---|
| 2026-09-12 | Initial mapping: 6 file types, 18 samples (pre/final/cup-shootout/stub, overview/changes/tournaments/schedule); live capture in progress |
| 2026-09-13 | Live verified through two Allsvenskan games polled at the 10 s floor (no missed tick, minute-by-minute clock); `overview.live.xml`, `overview.live.second-half.xml` and `game-info.live.second-half.xml` added. Chosen over the allsvenskan.se GraphQL feed for games, live state, events and lineups |
