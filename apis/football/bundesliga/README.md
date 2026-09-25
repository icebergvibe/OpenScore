# Bundesliga API (DFL / bundesliga.com Firebase Realtime Database)

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | Germany — Bundesliga (`DFL-COM-000001`) and 2. Bundesliga (`DFL-COM-000002`); Supercup and DFB-Pokal share the tree (see below) |
| **Official site** | https://www.bundesliga.com (DFL Deutsche Fußball Liga) |
| **Base URL** | `https://bundesliga-web-prod.europe-west1.firebasedatabase.app/` — a **Firebase Realtime Database** read through its REST interface (`<path>.json`). `https://wapp.bapi.bundesliga.com/` (CloudFront) proxies the same `/all/…` paths |
| **Auth** | None. The database rules allow anonymous reads from the season node downwards |
| **Format** | JSON (UTF-8). Firebase returns the raw node: objects keyed by id, a bare scalar (`"PRE_MATCH"`) or `null` for a missing path |
| **CORS** | **Yes** — `Access-Control-Allow-Origin` echoes the request origin (`*` on SSE); preflight `200` with `GET,POST,PUT,DELETE,PATCH`. Proxy: `*` |
| **Live push** | **Yes** — `Accept: text/event-stream` on any path streams `put`/`patch` events (Firebase REST streaming). Verified key-less |
| **WAF / UA requirement** | None. Works with no `User-Agent` |
| **Last full verification** | 2026-09-11 |
| **Status** | ✅ verified — pre-match, first half, half-time, second half and final states all captured from one match (Union Berlin 1–3 Schalke, 2026-09-11), plus the SSE streams during it |

## Overview

bundesliga.com is an Angular app. Its fixtures, live scores, live ticker, line-ups,
match stats and tables come from a **Firebase Realtime Database** (`bundesliga-web-prod`)
that the browser subscribes to with the Firebase SDK (WebSocket). The same database
is exposed over Firebase's standard REST interface, and its security rules allow
anonymous reads of everything under `/all/{competition}/seasons/{season}/…` and the
language-prefixed match nodes — no key, no cookie, no Origin check.

What it gives us: fixtures with UTC kick-offs, live score (`live`/`halftime`/
`fulltime`), match minute + injury time, a rich live-ticker (goals with xG, shot speed
and distance, cards, substitutions, period markers, editorial text/images/videos), full
line-ups with formation and pitch coordinates, team stats (possession, xG, passes,
sprints, distance, win-probability per minute), per-player match rankings, average
positions, league tables (overall / home / away / form) and season club/player
rankings. Ten seasons of history (2016/17 →). **No seconds-level clock**
(`minuteOfPlay.minute` + `injuryTime`) — same limitation as the other football leagues.

The site also has a REST API at `https://wapp.bapi.bundesliga.com` (club/person/player
profiles, editorial, broadcasters) that is gated by `Origin` **and** a static
`x-api-key` — that part is out of scope (see [Out of scope](#out-of-scope)).

All samples in [`samples/`](samples/) were captured on **2026-09-11** (season 2026/27,
Matchday 3) — the `pre`/`live`/`halftime`/`live2`/`final` files are one match followed
through its whole life. See [`samples/_meta.md`](samples/_meta.md).

## Identifiers

All ids are DFL "Datalibrary" ids: `DFL-<TYPE>-<6 chars>` (upper-case, base-36-ish,
sequential). They are stable across seasons and shared with the DFL's official data
feeds, so they are the same ids other DFL-backed products use.

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Competition | `DFL-COM-xxxxxx` | `DFL-COM-000001` Bundesliga · `DFL-COM-000002` 2. Bundesliga · `DFL-COM-000003` Supercup · `DFL-COM-000N5D` DFB-Pokal · `DFL-COM-J00020` UEFA Champions League (mirrored) | constants; all listed in `configNode.json` |
| Season | `DFL-SEA-xxxxxx` | `DFL-SEA-0001KA` = 2026/27, `…K9` = 2025/26, … `…K0` = 2016/17 (last char counts up each season) | `configNode.json` → `<comp>.season.dflDatalibrarySeasonId`; table `season.id` |
| Matchday | `DFL-DAY-xxxxxx` | `DFL-DAY-004CBT` = MD 1 2026/27, `…CBU` = MD 2, `…CBV` = MD 3 … (34 consecutive ids) | `GET …/seasons/{season}/matchdays.json?shallow=true`, or `dflDatalibraryMatchdayId` on any match. **Matchday ids are shared across competitions**: MD 5 is `DFL-DAY-004CBX` for both Bundesliga and 2. Bundesliga |
| Match | `DFL-MAT-xxxxxx` | `DFL-MAT-J043GZ` (Union Berlin–Schalke, MD 3) | match lists; also in the site's live-ticker URL |
| Club | `DFL-CLU-xxxxxx` | `DFL-CLU-00000A` Freiburg, `DFL-CLU-00000G` Bayern, `DFL-CLU-000010` Augsburg | `teams.home.dflDatalibraryClubId`, table `club.id` |
| Player / person | `DFL-OBJ-xxxxxx` | `DFL-OBJ-00258B` Maximilian Eggestein, `DFL-OBJ-J00TI2` (coach) | line-ups, ticker entries, rankings |

Season *names* are `2026-2027` (config, tables) or `2026/2027` (site). There is no
seasons list endpoint — the site hard-codes the id → name map in its bundle
(`DFL-SEA-0001K0` … `0001KA`); `configNode.json` gives the current one.

## Discovery path

1. **Current season + matchday:** `GET https://wapp.bapi.bundesliga.com/config/configNode.json`
   → `["DFL-COM-000001"].season.dflDatalibrarySeasonId` (`DFL-SEA-0001KA`) and
   `.matchday.dflDatalibraryMatchdayId` / `matchdayNumber` / `matchdayRange`.
2. **Matchday ids:** `GET /all/{comp}/seasons/{season}/matchdays.json?shallow=true`
   → 34 keys in order (Firebase returns them insertion-ordered; sort by id to be safe).
3. **Fixtures + live scores:** `GET /all/{comp}/seasons/{season}/matches.json?orderBy="matchday"&equalTo=3`
   → 9 matches keyed by id with `matchStatus`, `plannedKickOff`, `teams`, and once
   started `kickOff`, `score`, `minuteOfPlay`. This one call is enough for a scoreboard.
4. **Live ticker / events:** `GET /en/{comp}/seasons/{season}/matchdays/{matchdayId}/{matchId}.json`
   (`de`/`es` also) → same score/minute fields plus `liveBlogEntries`.
5. **Line-ups / stats / rankings:** `GET /all/{comp}/seasons/{season}/matchdays/{matchdayId}/{matchId}/{lineup|stats|rankings|averagePositions}.json`.
6. **Table:** `GET /all/{comp}/seasons/{season}/liveTable.json` (`homeTable`, `awayTable`, `formTable`).
7. **Live updates:** subscribe with `Accept: text/event-stream` to the path you would
   otherwise poll (e.g. the `matches.json?orderBy="matchday"&equalTo=N` query or one
   match node) and apply `put`/`patch` events. Or poll with `X-Firebase-ETag: true` +
   `If-None-Match` (verified `304`).

Recommended cadence if polling: **≥ 10 s** on the matchday `matches` query (small, ~10 KB)
or the single match node; but prefer SSE — that is what the site itself does (via
the SDK), so pushed data is as fresh as the site.

## Firebase REST conventions

These apply to every path below. Reference: Firebase Realtime Database REST API.

- Append `.json` to the node path. Nested paths work at any depth
  (`…/{matchId}/matchStatus.json` → `"PRE_MATCH"`), so you can fetch single fields.
- `?shallow=true` → `{ key: true, … }` for the node's children (cheap listing).
- Filters: `?orderBy="<childKey>"&equalTo=<value>` (string values quoted:
  `equalTo="DFL-CLU-00000G"`, `orderBy` may be a deep path like
  `"teams/home/dflDatalibraryClubId"`), `startAt`/`endAt`, `limitToFirst`/`limitToLast`.
  Filtering only works on keys the rules index; the ones the site uses (listed per
  endpoint) are verified. An unindexed key returns `400` `Index not defined …`
  — verified for `plannedKickOff` and `matchStatus` on `matches`, so there is **no
  "all live matches" query**; select by `matchday` and read `matchStatus` client-side.
- `?print=pretty` pretty-prints; `?timeout=…` limits server time.
- Missing path → `200` with body `null`. Path above the readable level → `401`
  `{"error":"Permission denied"}` ([`samples/denied-competition.json`](samples/denied-competition.json)).
- `Cache-Control: no-cache` on everything; `X-Firebase-ETag: true` request header
  makes the server return `ETag`, and `If-None-Match` then yields `304`.
- Streaming: `Accept: text/event-stream` → `event: put` with `{"path":"/","data":…}`
  for the initial state, then `patch` events with a **path relative to the
  subscribed node** and an object of changed keys (deep keys as `a/b/c`), `keep-alive`
  every 30 s while idle, `cancel`/`auth_revoked` on rule changes. See
  [SSE during a match](#sse-during-a-match) for what actually arrives.
- Responses are objects keyed by id, **not arrays** — key order is Firebase's, sort
  client-side (`plannedKickOff`, `seasonOrder`, `rank`, `order`).

## Endpoints

Paths are relative to `https://bundesliga-web-prod.europe-west1.firebasedatabase.app/`.
`{comp}` = `DFL-COM-000001` (Bundesliga) unless stated; everything was also verified
for `DFL-COM-000002` (2. Bundesliga), which has the identical tree.

### `GET https://wapp.bapi.bundesliga.com/config/configNode.json`

| | |
|---|---|
| **Purpose** | The "what is now" pointer: current season, matchday and stage per competition |
| **Sample** | [`samples/config.json`](samples/config.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |
| **Cache** | S3 via CloudFront, `ETag` + `Last-Modified`; regenerated every few minutes (`lastUpdateDateTime`) |

**Response shape:** `{ "<competitionId>": { lastUpdateDateTime, matchday { dflDatalibraryMatchdayId, matchdayNumber, matchdayRange { start, end } }, season { dflDatalibrarySeasonId, seasonId, name ("2026-2027"), firstMatchdayStart }, seasonState, stage, pre? } }`.

**Observed values** — `seasonState`: `running`. `stage`: `matchDayFirstHalf`,
`singleMatch`, `Final`, `SPIELTAG 1` *(observed, may be incomplete; the site uses it
for the match bar only)*. `pre: ["DFL-COM-000003"]` on the Bundesliga entry links the
Supercup as the pre-season competition. The Firebase copy at
`https://bundesliga-web-prod.europe-west1.firebasedatabase.app/config.json` is the
fallback the site uses if this file fails.

### `GET /all/{comp}/seasons/{season}.json?shallow=true`

| | |
|---|---|
| **Purpose** | Lists the season's child nodes (the lowest publicly readable level) |
| **Sample** | [`samples/season-shallow.json`](samples/season-shallow.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

`{ matches, matchdays, liveTable, homeTable, awayTable, formTable, stats }`. Cups
(`DFL-COM-000003`, `DFL-COM-000N5D`) have only `matches`, `matchdays`, `stats`;
`groupTables` exists as a path but is `null` for the league.

### `GET /all/{comp}/seasons/{season}/matches.json`

| | |
|---|---|
| **Purpose** | **Fixtures and live scores** for the whole season, keyed by match id. This is the scoreboard endpoint |
| **Parameters** | `orderBy="matchday"&equalTo=N` (one round, verified) · `orderBy="teams/home/dflDatalibraryClubId"&equalTo="DFL-CLU-…"` and `…/away/…` (a club's home / away games, verified) · `orderBy="matchType"&equalTo="…"` (cups/UCL, used by the site) · `limitToFirst` |
| **Sample** | [`samples/matches.json`](samples/matches.json) (whole season, **306 matches, 330 KB, complete**) · [`samples/matches-matchday.json`](samples/matches-matchday.json) (MD 3, all pre-match) · [`samples/matches-matchday.live.json`](samples/matches-matchday.live.json) / [`.halftime`](samples/matches-matchday.halftime.json) / [`.live2`](samples/matches-matchday.live2.json) / [`.final`](samples/matches-matchday.final.json) (MD 3 with one match in play) · [`samples/matches-club-home.json`](samples/matches-club-home.json) (Bayern home) · [`samples/matches-matchday.2bl.json`](samples/matches-matchday.2bl.json) (2. Bundesliga MD 5) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

**Response shape:** `{ "DFL-MAT-…": { … } }`. Each match:

- ids: `matchId` (= `dflDatalibraryMatchId`), `dflDatalibraryCompetitionId`,
  `dflDatalibrarySeasonId`, `dflDatalibraryMatchdayId`, `matchday` (number),
  `matchdayRange { start, end }` (day-granular, `+0000`), `seasonOrder` (1–306, the
  fixture's ordinal in the season), `slugs { slugLong }`, `matchType` (cups/UCL
  only: `"SPIELTAG 1"`, `"Play-Offs"`, `"Round Of 16"`, `"Quarter-finals"`,
  `"Semi-finals"`, `"Final"`; absent for the league)
- time: `plannedKickOff` (`2026-09-05T13:30:00+0000`, **UTC**), `matchDateFixed`
  (`false` while the DFL has only set the weekend), `kickOff` (actual, appears once
  started)
- state: `matchStatus`, `minuteOfPlay { minute, injuryTime }` (once started)
- `score { home { live, halftime, fulltime }, away { … } }` (once started; `halftime`
  is filled at the break, `fulltime` at the end)
- `teams { home, away }`: `dflDatalibraryClubId`, `nameFull`, `nameShort`,
  `threeLetterCode`, `logoUrl` (season-specific SVG on `assets.bundesliga.com`),
  `gradientStartColor`/`gradientEndColor`/`textColor`
- `en { highlightVideo { videoId, duration } }` / `de { … }` after the match (JW Player ids)

Pre-match objects lack `kickOff`, `score`, `minuteOfPlay` — compare
[`match-basic.pre.json`](samples/match-basic.pre.json) with
[`match-basic.final.json`](samples/match-basic.final.json).

### `GET /all/{comp}/seasons/{season}/matches/{matchId}.json`

| | |
|---|---|
| **Purpose** | One match's basic object (same shape as above). ~1 KB — ideal for SSE per match |
| **Sample** | [`samples/match-basic.pre.json`](samples/match-basic.pre.json) · [`samples/match-basic.live.json`](samples/match-basic.live.json) · [`samples/match-basic.halftime.json`](samples/match-basic.halftime.json) · [`samples/match-basic.live2.json`](samples/match-basic.live2.json) · [`samples/match-basic.final.json`](samples/match-basic.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

Unknown id → `null` ([`samples/match-basic.unknown.json`](samples/match-basic.unknown.json)).

### `GET /all/{comp}/seasons/{season}/matchdays.json?shallow=true`

| | |
|---|---|
| **Purpose** | The 34 matchday ids of the season |
| **Sample** | [`samples/matchdays-shallow.json`](samples/matchdays-shallow.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

**Do not fetch this node without `shallow=true`:** the full node embeds
`lineup`/`stats`/`rankings`/`averagePositions` for every match — 2.0 MB for the
Bundesliga and 3.5 MB for 2. Bundesliga after two rounds, growing all season. Nothing
about matchdays themselves (dates, numbers) lives here; use `configNode.json` and
`matchdayRange` on matches.

### `GET /all/{comp}/seasons/{season}/matchdays/{matchdayId}/{matchId}.json`

| | |
|---|---|
| **Purpose** | The match's data bundle: `lineup`, `stats`, `rankings`, `averagePositions`, `lastUpdateDateTime` |
| **Sample** | [`samples/match-data.pre.json`](samples/match-data.pre.json) · [`samples/match-data.final.json`](samples/match-data.final.json) (80 KB) · [`samples/match-all-node-shallow.json`](samples/match-all-node-shallow.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

Each child is fetchable on its own (below); the site fetches `lineup` and `stats`
separately.

#### `…/{matchId}/lineup.json`

| | |
|---|---|
| **Sample** | [`samples/match-lineup.pre.json`](samples/match-lineup.pre.json) (published 78 min before kick-off) · [`samples/match-lineup.final.json`](samples/match-lineup.final.json) captured 2026-09-11 |

`{ home, away, lastUpdateDateTime }`; each side `{ startingEleven { isTactical,
tacticalFormationName ("4-2-3-1", "3-4-2-1"), persons[] }, bench { persons[] }, coaches
{ persons[] } }`. Person: `dflDatalibraryObjectId`, `name`, `tacticalName` (short),
`slugifiedShortName`, `shirtNumber`, `role`, `imageUrl` (circle PNG), and for starters
`position { x, y }` (pitch %, own goal at the bottom: GK `y: 90.9`).

**Observed `role` values:** `GOALKEEPER`, `DEFENSE`, `MIDFIELD`, `ATTACK`,
`HEADCOACH` *(observed, may be incomplete)*. Every state has 11 starters + 9 bench;
the pre-match line-up is the official one (`isTactical: true`), not a prediction, and
the node did not change during the match (substitutions are only in the ticker).
Live copies: [`match-lineup.live.json`](samples/match-lineup.live.json) ·
[`match-lineup.halftime.json`](samples/match-lineup.halftime.json) ·
[`match-lineup.live2.json`](samples/match-lineup.live2.json).

#### `…/{matchId}/stats.json`

| | |
|---|---|
| **Sample** | [`samples/match-stats.pre.json`](samples/match-stats.pre.json) · [`samples/match-stats.live.json`](samples/match-stats.live.json) (20') · [`samples/match-stats.halftime.json`](samples/match-stats.halftime.json) · [`samples/match-stats.live2.json`](samples/match-stats.live2.json) (46') · [`samples/match-stats.final.json`](samples/match-stats.final.json) captured 2026-09-11 |

Flat object of team stats, each `{ homeValue, awayValue, lastUpdateDateTime }`:
`ballPossessionRatio`, `cornerKicks`, `distanceCovered` (km), `fouls`, `offsides`,
`passAccuracy`, `passEfficiency`, `passes`, `shotsOffTarget`, `shotsOnTarget`,
`sprints`, `tacklesWon`, `XGoals` (capital X — the populated one; `xGoals`/`xgoals`
are empty placeholders). Plus `winProbability.probability[]`
(`{ minuteOfPlay, homeTeamWinProbability, drawProbability, guestTeamWinProbability }`
per minute incl. injury-time minutes — the only per-minute timeline in the API;
21 points at 20', 51 at half-time, 101 at full time),
`playerRankings { challengesWon, maximumSpeed, shots, totalDistanceCovered }` (top
players in the match) and `fantasyManagerRanking { home[], away[] }` (fantasy points).
Pre-match: zeros / `50`–`50` possession, keys present.

#### `…/{matchId}/rankings.json` and `…/{matchId}/averagePositions.json`

Included in [`match-data.final.json`](samples/match-data.final.json). `rankings.playerRankings.<metric>`
= `{ "DFL-OBJ-…": { rank, index, value, name, slug, imageUrl, club { … } } }` for
`challengesWon`, `maximumSpeed` (km/h), `shotsAtGoal`, `totalDistanceCovered`.
`averagePositions.scopes.matchScope.{home,away}."DFL-OBJ-…" { position { x, y }, shirtNumber, imageUrl }`.

### `GET /{lang}/{comp}/seasons/{season}/matchdays/{matchdayId}/{matchId}.json`

| | |
|---|---|
| **Purpose** | **The live-ticker match object**: score, minute, status, referee, stadium, environment, highlight video and every ticker entry. Localised |
| **Parameters** | `{lang}` = `en`, `de`, `es` (the site maps `jp`, `fr`, `pt`, `ar` → `en`). Sub-paths work: `…/{matchId}/matchStatus.json`, `…/liveBlogInfos.json` |
| **Sample** | [`samples/match.pre.json`](samples/match.pre.json) (16 KB, 19 entries) · [`samples/match.live.json`](samples/match.live.json) (20', 31 entries) · [`samples/match.halftime.json`](samples/match.halftime.json) (47) · [`samples/match.live2.json`](samples/match.live2.json) (46', 52) · [`samples/match.final.json`](samples/match.final.json) (52 KB, 94 entries) · [`samples/match.de.pre.json`](samples/match.de.pre.json) · [`samples/match-matchStatus.pre.json`](samples/match-matchStatus.pre.json) · [`samples/match-liveBlogInfos.de.final.json`](samples/match-liveBlogInfos.de.final.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

**Response shape:** the basic match fields (ids, `matchStatus`, `plannedKickOff`,
`kickOff`, `score`, `minuteOfPlay`, `teams`, `slugs { slugLong, slugShort }`,
`matchdayLabel`) plus:

- `referee { displayName, firstName, lastName }`, `stadiumName`,
  `stadiumIconUrlBlack/White`, `environment { numberOfSpectators, soldOut,
  temperature, precipitation, lastUpdateDateTime }`
- `highlight { video { videoId, duration } }` (added some time after full time — not
  yet present 2 min after the final whistle; present on a match from the previous
  week), `liveBlogUrl`
  (webview URL), `liveBlogInfos { isTyping, homeIsTyping, awayIsTyping }` (typing
  indicator for the ticker UI)
- `liveBlogEntries`: object keyed by a 14-digit timestamp-ish id, each
  `{ entryType, entryDate (local, `+0200`), matchSection, order, playtime { minute,
  injuryTime }, side ("home" | "away" | "none"), detail { … }, hidden?, pinned?,
  conference?, enableHtml? }`. **Sort by `order`** (ascending = chronological).
  In-play `order` encodes the clock: `MMII SS 0000` → minute 45+2 = `4502010000`,
  90+5 = `9005010000`; pre-match editorial entries are small numbers (`-10000`,
  `10000` …). Entries with `hidden: true` must be filtered out (the site does).

**Observed `entryType` values and their `detail`:**

| `entryType` | `detail` |
|---|---|
| `start_firstHalf`, `end_firstHalf`, `start_secondHalf`, `end_secondHalf`, `finalWhistle` | `{ score { home, away } }` — period markers, `side: "none"` |
| `goal` | `{ scorer { dflDatalibraryObjectId, name, imageUrl }, assist { … }?, score, penalty (bool), xG, distanceToGoal (m), shotSpeed (km/h)? }` — `assist` and `shotSpeed` are present only when known; own goals not yet observed |
| `yellowCard` | `{ person { … }, score }` — `redCard` / `yellowRedCard` not yet observed |
| `videoAssistant` | `{ review ("Offside?", "Previous foul?"), situation ("No goal", "Goal"), decision ("Goal"), score }` — VAR check, `side: "none"` |
| `sub` | `{ in { … }, out { … }, score }` |
| `lineup` | `{ formation ("4-2-3-1"), lineup[] { dflDatalibraryObjectId, name, shirtNumber, position, imageUrl } }` per side, pre-match. `position` is a German tactical slot code: `TW`, `IVL`/`IVZ`/`IVR`, `LV`/`RV`, `DML`/`DMZ`/`DMR`, `DLM`/`DRM`, `OLM`/`ORM`, `ZO`, `HL`/`HR`, `STL`/`STZ`/`STR` *(observed)* |
| `stats` | `{ type ("pie"), title, headline, text, home { value }, away { value }, matchFact }` |
| `playerOfTheMatch` | `{ playerOfTheMatch { … }, percentageOfVotes }` |
| `freetext`, `image`, `video`, `embed` | editorial: `{ headline, text }`, `{ copyright, headline, text, url? }`, `{ videoId, duration, aspectRatio, headline, text }`, `{ embedPlatform, eventId, projectId, channelsEnabled }` |

*(observed, may be incomplete — from two finished Bundesliga matches, one of them
followed live)*. During play new entries arrive within seconds of the event; the
`goal` entry for a 25' goal was present in the 45' snapshot and the `score` patch on
the basic node arrived on the SSE stream before the ticker entry.

**Observed `matchSection` values:** `PRE_MATCH`, `FIRST_HALF`, `HALF`, `SECOND_HALF`,
`FINAL_WHISTLE`.

### `GET /{lang}/{comp}/seasons/{season}/matchdays/{matchdayId}.json`

All nine ticker match objects of the round in one object (~460 KB for a finished
matchday). Only [`samples/matchday-detail-shallow.json`](samples/matchday-detail-shallow.json)
is captured; use `?shallow=true` to list match ids, fetch matches individually.
The site filters it with `orderBy="slugs/slugLong"&equalTo="<slug>"` to resolve URLs.

### `GET /all/{comp}/seasons/{season}/liveTable.json`

| | |
|---|---|
| **Purpose** | League table, updated live during matches |
| **Variants** | `homeTable`, `awayTable`, `formTable` (same shape; `formTable` entries add `form[] { matchDay, matchId, result }`) · `groupTables` (`null` for the league) |
| **Sample** | [`samples/liveTable.json`](samples/liveTable.json) · [`samples/homeTable.json`](samples/homeTable.json) · [`samples/awayTable.json`](samples/awayTable.json) · [`samples/formTable.json`](samples/formTable.json) · [`samples/liveTable.2bl.json`](samples/liveTable.2bl.json) · [`samples/liveTable.2025-26.json`](samples/liveTable.2025-26.json) (final 2025/26 table) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

**Response shape:** `{ competition { id, name }, season { id, name }, matchday { id,
name ("2") }, creationDateTime, qualifications[] { id, title, color }, entries[] }`.
`entries[]` is a real array in rank order: `{ rank, subRank, tendency, club { id,
dflDatalibraryClubId, nameFull, nameShort, threeLetterCode, slugifiedFull,
slugifiedSmall, logoUrl }, gamesPlayed, wins, draws, losses, goalsScored,
goalsAgainst, goalDifference, points, qualification }`.

**The table is live:** it is regenerated during matches (`creationDateTime` moved
18:34 → 19:17 → 20:36 UTC; Schalke went 12th → 7th → 7th, `gamesPlayed` already
counts the match in progress). Live copies: [`liveTable.live.json`](samples/liveTable.live.json) ·
[`liveTable.halftime.json`](samples/liveTable.halftime.json) · [`liveTable.final.json`](samples/liveTable.final.json).

**Observed enums** — `qualification`: `UEFA_CHAMPIONS_LEAGUE`, `UEFA_EUROPA_LEAGUE`,
`UEFA_EUROPA_LEAGUE_QUALIFICATION` (titled "Conference League"), `PLAY_OFF`
(relegation play-off), `RELEGATION`, `NONE`. `tendency`: `UP`, `DOWN`, `STABLE`.
`form[].result`: `WIN`, `DRAW`?, `LOSS`? *(only `WIN` observed)*.
Historical seasons work (`DFL-SEA-0001K9` → Bayern 89 pts after MD 34).

### `GET /all/{comp}/seasons/{season}/stats/…`

| | |
|---|---|
| **Purpose** | Season rankings for clubs and players, and per-player season totals |
| **Paths** | `stats/clubRankings/{metric}.json` · `stats/playerRankings/{metric}.json` · `stats/playerPage/{playerId}/seasonStats.json` · `stats/fantasyManagerRanking/{clubId}.json` |
| **Parameters** | rankings: `?orderBy="index"&limitToFirst=N` (verified; the site uses 10) |
| **Sample** | [`samples/stats-shallow.json`](samples/stats-shallow.json) · [`samples/stats-clubRankings-shallow.json`](samples/stats-clubRankings-shallow.json) · [`samples/stats-clubRankings-goals.json`](samples/stats-clubRankings-goals.json) · [`samples/stats-playerRankings-shallow.json`](samples/stats-playerRankings-shallow.json) · [`samples/stats-playerRankings-shotsAtGoalSuccessful.json`](samples/stats-playerRankings-shotsAtGoalSuccessful.json) · [`samples/stats-playerPage-shallow.json`](samples/stats-playerPage-shallow.json) (336 player ids) · [`samples/stats-playerPage-seasonStats.json`](samples/stats-playerPage-seasonStats.json) · [`samples/stats-fantasyManagerRanking-shallow.json`](samples/stats-fantasyManagerRanking-shallow.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

Ranking nodes are `{ "DFL-CLU-…" | "DFL-OBJ-…": { rank, index (0-based order),
value, name, logoUrl | imageUrl, color, textColor, slug?, club? } }`.
**Club metrics:** `ballPossessionRatio`, `cards`, `cardsYellow`, `crossesFromPlay`,
`distanceCovered`, `foulsAgainstOpponent`, `goals`, `intensiveRuns`, `ownGoals`,
`passesFromPlayRatio`, `penalties`, `shotsAtGoal`, `shotsAtGoalWoodWork`, `sprints`,
`tacklingGamesAirWon`, `tacklingGamesWon`. **Player metrics:** the same minus
`ballPossessionRatio`/`goals`, plus `assists`, `goalkeeperSaves`, `maximumSpeed`,
`shotsAtGoalSuccessful` (= goals). There is **no `playerRankings/goals`** (→ `null`).
`playerPage/{id}/seasonStats` is `{ <metric>: { value } }` (`matchesPlayed`,
`ballActions`, `distanceCovered`, `maximumSpeed`, `shotsAtGoal`, `shotsOnTarget`,
`passesFromPlayRatio`, `tacklingGames*`, …) — the only per-player profile data
available key-less (no names/DOB; those are on the gated `person` API).

### Other competitions in the same database

| Competition | Season node children | Notes |
|---|---|---|
| `DFL-COM-000002` 2. Bundesliga | full set | identical tree; verified `matches`, `matchdays`, `liveTable` |
| `DFL-COM-000003` Supercup | `matches`, `matchdays`, `stats` | 1 match (Dortmund–Bayern 2026-08-22), matchday `DFL-DAY-004CBT`; ticker under `/{lang}/DFL-COM-000003/…` |
| `DFL-COM-000N5D` DFB-Pokal | `matches`, `matchdays`, `stats` | 48 matches so far (round 1 = matchday 1, round 2 = matchday 2 on 2026-10-27/28); no tables |
| `DFL-COM-J00020` UEFA Champions League | full set incl. `liveTable` | mirrored for the site's UCL section; 189 matches with `matchType` (`SPIELTAG 1…8`, `Play-Offs`, …). Third-party data — not something OpenScore should rely on |
| `DFL-COM-000004` / `000005` | `null` | the site's relegation play-off ids (BL↔2BL, 2BL↔3. Liga); populated in May/June only (unverified) |
| `DFL-COM-J00028`, `J0002E` | in `configNode.json` only | World Cup 2026 / Euro 2024 |

## Game states

`matchStatus` is the single state field, identical on the basic match, the ticker
match and the `matches` list. All five values below were observed on one match
(Union Berlin–Schalke, 2026-09-11) via polling and SSE.

| `matchStatus` | Meaning | What changes |
|---|---|---|
| `PRE_MATCH` | scheduled | no `kickOff`/`score`/`minuteOfPlay` |
| `FIRST_HALF` | in play | at kick-off one patch sets `kickOff` (actual, to the second), `matchStatus` and `score { home/away { live: 0, halftime: 0 } }`; then `minuteOfPlay` advances once a minute, `injuryTime` counting up while `minute` stays `45` |
| `HALF` | half-time | `matchStatus` only; `minuteOfPlay` stays at `45+3`; `score.*.halftime` already holds the break score (it tracks `live` during the first half) |
| `SECOND_HALF` | in play | one patch sets `matchStatus`, `minuteOfPlay {46, 0}` and adds `score.*.fulltime` (tracks `live` from now on) |
| `FINAL_WHISTLE` | finished | one patch sets `matchStatus` together with the final `score`; `minuteOfPlay` ends at e.g. `{ minute: 90, injuryTime: 14 }`; `highlight` follows later |

So `halftime`/`fulltime` are **running copies**, not "set once at the break": read
`halftime` only from `HALF` onwards and `fulltime` only at `FINAL_WHISTLE`.
Extra-time/penalty states (cups) and postponed/abandoned representations are **not
yet observed**. `matchDateFixed: false` marks fixtures whose kick-off is still
provisional.

## SSE during a match

Three streams were recorded for the whole match (kick-off to 2 min after full
time); the raw captures are summarised here rather than committed.

**Single match, `/all/…/matches/{matchId}.json`** — the stream to use for a
scoreboard. 1 `put` (initial object), then ~110 `patch` events, all with
`"path":"/"` and an object of top-level keys:

```
event: patch
data: {"path":"/","data":{"kickOff":"2026-09-11T18:30:21+0000","score":{"away":{"halftime":0,"live":0},"home":{"halftime":0,"live":0}},"dateQuality":null,"matchStatus":"FIRST_HALF"}}
event: patch
data: {"path":"/","data":{"minuteOfPlay":{"injuryTime":0,"minute":1}}}
event: patch
data: {"path":"/","data":{"score":{"away":{"halftime":1,"live":1},"home":{"halftime":0,"live":0}},"dateQuality":null}}
event: patch
data: {"path":"/","data":{"dateQuality":null,"matchStatus":"HALF"}}
event: patch
data: {"path":"/","data":{"score":{"away":{"fulltime":1,"halftime":1,"live":1},"home":{…}},"dateQuality":null,"minuteOfPlay":{"injuryTime":0,"minute":46},"matchStatus":"SECOND_HALF"}}
event: patch
data: {"path":"/","data":{"score":{…"live":3…},"dateQuality":null,"matchStatus":"FINAL_WHISTLE"}}
```

- `minuteOfPlay` is patched **once per minute** (106 patches over the match).
- `"dateQuality":null` appears in most patches and alone about once a minute — it is
  a no-op (deleting a key that does not exist). Ignore it; do not treat it as a change.
- Sub-objects arrive **whole** (`score` with both sides, `minuteOfPlay` with both
  fields), so a shallow merge of `data` into your object is enough.
- `kickOff` was patched twice (`…:21` then `…:23`).

**Matchday query, `/all/…/matches.json?orderBy="matchday"&equalTo=3`** — same
patches, but with the match id prefixed in the keys:
`{"path":"/","data":{"DFL-MAT-J043GZ/minuteOfPlay":{"injuryTime":0,"minute":12}}}`,
`"DFL-MAT-J043GZ/score":{…}`, `"DFL-MAT-J043GZ/matchStatus":"HALF"`. Nine matches on
one connection; the noise is the same `…/dateQuality` key.

**Ticker match, `/en/…/matchdays/{md}/{matchId}.json`** — patches are keyed
`liveBlogEntries/<entryId>` (a whole new entry object), `liveBlogInfos/isTyping`
(true/false as the editor types), `environment` (whole object). **This stream died
silently** at ~18:12 UTC, before kick-off: no `cancel`, no error, the connection
stayed open but sent nothing further, not even `keep-alive`. The other two streams
ran the full 2 h. Implement a watchdog — no event (incl. `keep-alive`) for 60 s →
reconnect — and re-read the node on reconnect; do not rely on a single connection.

Polling with `X-Firebase-ETag: true` + `If-None-Match` remains a fine fallback
(`304` verified, and again on a ticker node on 2026-09-25) at ≥ 10 s.

**What the provider does with this.** The single-match node is streamed for the
scoreboard; the ticker and stats nodes, which carry the timeline and the stats the
stream never does, are read over REST: at each connect, whenever the streamed score or
status gets ahead of the ticker's (a goal, a break, the whistle - the ticker can trail
the stream), and otherwise at most once a minute, so bookings and substitutions reach
the timeline too. Every node read asks for an `ETag`, so an unchanged ticker costs a
`304`. A stream that fails or sends nothing for 60 s is reconnected from a fresh
snapshot; at the final whistle a ticker still behind is re-read up to six times at the
10 s floor before the live flow ends.

## Quirks & gotchas

- **Objects, not arrays.** Match lists, ticker entries and rankings are id-keyed
  objects. Firebase orders keys by its own rules; sort by `plannedKickOff`/`seasonOrder`
  (matches), `order` (ticker), `rank`/`index` (rankings). Only `liveTable.entries`
  and `winProbability.probability` are real arrays.
- **Permission boundary.** `/`, `/all`, `/all/{comp}` and `/all/{comp}/seasons` → `401`
  `Permission denied`. Readable from `/all/{comp}/seasons/{season}` down and from
  `/{lang}/{comp}/seasons/{season}/matchdays/{md}/{match}` down. Missing → `null`, never `404`.
- **Don't fetch `matchdays.json` or `/{lang}/…/matchdays/{md}.json` whole** (2–3.5 MB
  and ~460 KB respectively). `matches.json` for the whole season is 330 KB — fetch
  once, then filter by `matchday`.
- **Clock is whole minutes** (`minuteOfPlay.minute` + `injuryTime`, `45+3` style).
  Period start wall-clock is not exposed directly; derive it from the
  `start_firstHalf` / `start_secondHalf` ticker entries' `entryDate` (editorial
  timestamp, `+0200`, second precision) or from `kickOff`.
- **Time zones:** `plannedKickOff`/`kickOff`/`matchdayRange` are UTC with `+0000`
  suffix (not `Z`); `entryDate` on ticker entries and all `lastUpdateDateTime`
  values are ISO with offset/`Z`. Parse offsets, don't assume `Z`.
- **`halftime`/`fulltime` score keys** are running copies of `live` for the current
  half (`halftime` appears at kick-off, `fulltime` at the start of the second half);
  they are only "final" once `matchStatus` has moved past that half.
- **The minute can regress.** During a 14-minute stoppage-time period the stream
  went `90+7` → `46`, `47` … `52` → `90+14` (a feed reset), while `matchStatus` stayed
  `SECOND_HALF`. Treat `minuteOfPlay` as advisory: never go backwards in the UI
  unless `matchStatus` changed, and prefer `kickOff`/`start_secondHalf` + wall clock
  for a running clock.
- **`environment` is not reliable live** (`numberOfSpectators: 0`, `soldOut: false`
  at full time for a sold-out ground); it is corrected days later.
- **`XGoals` vs `xGoals` vs `xgoals`** in `stats`: only the capital-X one has values.
- **Language nodes are separate data** — `de` and `en` ticker entries differ
  (different editorial content and counts), but the match-level fields are the same.
- **Ticker `hidden: true`** entries exist and must be dropped; `conference: true` marks
  entries that belong to the multi-match "Konferenz" ticker.
- **CloudFront proxy** (`wapp.bapi.bundesliga.com/all/…`) returns the same JSON with
  `Access-Control-Allow-Origin: *` but is edge-cached (`x-cache: Hit from cloudfront`,
  no `Cache-Control`/`Age`, TTL unknown) and does **not** stream SSE (returns JSON).
  Use the Firebase host for live data.
- **SSE connections are one per path.** Firebase's REST stream sends `keep-alive` every
  30 s and may send `cancel` if rules change; reconnect with back-off. The site itself
  uses the SDK's WebSocket, so REST streaming is a supported but distinct channel.
- **Imagery:** `logoUrl` (season-specific `clublogos/{season}/{club}.svg`) and player
  `imageUrl` (`player/dfl-obj-…-dfl-clu-…-dfl-sea-….png`, `-circle` variant) on
  `https://assets.bundesliga.com`, verified `200`. Player images are club+season
  specific.
- The whole-season `matches` list (which the core keeps warm for the day view) carries
  `score { home/away { halftime, fulltime, live } }` and `matchStatus` per row, so a club's
  season with results is a filter over it (`teams/home|away/dflDatalibraryClubId`).
- **No club/player directories key-less.** Club metadata comes with matches/tables;
  player names come with line-ups/ticker/rankings. Profiles (DOB, nationality,
  height) live on the gated REST API.

## Out of scope

- `https://wapp.bapi.bundesliga.com/{club|season/{id}/club|person|persons|player|broadcaster(s)|editorial|editorial/wiki|broadcasts|web-api/locale}` — the site's
  REST API. Requests need `x-api-key: 60ETUJ4j5YagIHdu-PROD` (a static key shipped in
  the public JS bundle) **and** an `Origin` of `www.bundesliga.com`; otherwise `403`
  `"Forbidden for non bundesliga top level usage. Please contact service or technical
  support."`. Key + spoofed Origin gets past the gate. A key plus an explicit
  "non-Bundesliga usage forbidden" message is a clear signal — not used.
- `https://wdh.bundesliga.com` — second keyed API (`x-api-key: aiV5ERJNE4wwz-PROD`), not probed.
- `/{lang}/custom/matches/{slug}` — hand-made ticker pages for friendlies, Club World
  Cup, UCL nights (610 keys, 100 KB+ each, no ids). Editorial, not fixtures.
- `stats/fantasyManagerRanking` — fantasy-game points per club, not needed.
- `DFL-COM-J00020` (Champions League mirror) — third-party competition data; policy
  on such sources is still open (see NFL/ESPN note in the top-level README).
- The Firebase web SDK config in the bundle (`apiKey`, `bundesliga-web-official`
  project) is the standard public web-app identifier, but it is not needed for REST
  reads and we do not use it.
- Okta/`fan.bundesliga.com` login, Firebase auth, OneTrust, Dynatrace — site plumbing.

## Core model mapping

| Core concept | Source | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `configNode.json` | `season.dflDatalibrarySeasonId`, `season.name`, `firstMatchdayStart` | No seasons list; ids increment by one per season |
| Game (id, teams, start time) | `matches` (per matchday) | `matchId`, `teams.home/away.dflDatalibraryClubId`, `plannedKickOff`, `matchday` | UTC; `matchDateFixed` flags provisional dates |
| GameState | `matchStatus` | `PRE_MATCH` / `FIRST_HALF` / `HALF` / `SECOND_HALF` / `FINAL_WHISTLE` | All observed; cup extra time/penalties not yet |
| Score by period | `score` | `home/away.live`, `.halftime`, `.fulltime` | Explicit half-time score — better than PL/Serie A |
| Clock / period | `minuteOfPlay` + `matchStatus` | `minute`, `injuryTime` | Whole minutes; period start from ticker `start_*` entries |
| GameEvent | ticker `liveBlogEntries` | `entryType`, `playtime`, `side`, `detail.scorer/person/in/out`, `detail.score` | Filter `hidden`; sort by `order`; red cards/own goals not yet observed — the core credits a goal to whichever side `detail.score` grew for, so `side`'s meaning on an own goal does not matter |
| Lineups | `lineup` | `startingEleven.persons[]`, `bench`, `coaches`, `tacticalFormationName`, `position{x,y}` | Published ≥ 1 h before kick-off |
| Team | `teams.*` / table `club` | `dflDatalibraryClubId`, `nameFull`, `nameShort`, `threeLetterCode`, `logoUrl`, colours | No standalone club endpoint key-less |
| Player | `lineup` / ticker / rankings | `dflDatalibraryObjectId`, `name`, `shirtNumber`, `role`, `imageUrl` | No DOB/nationality key-less |
| Standings | `liveTable` (+ `home/away/formTable`) | `entries[]` | Rank order array; zones via `qualification` + `qualifications[]` colours |
| Officials | ticker match | `referee` | Main referee only |
| Live push | SSE on any node | `put`/`patch` events | Native push — no polling needed |

## TODO

- [ ] Observe `redCard` / `yellowRedCard`, own-goal and penalty representations in
      the ticker, and `form[].result` values other than `WIN`.
- [ ] Observe a postponed match and the relegation play-off nodes (`DFL-COM-000004/5`, May 2027).
- [ ] Check DFB-Pokal extra time / penalties in `score` and `matchStatus`.
- [ ] Find out when `highlight` and the corrected `environment` land after full time.
- [ ] Characterise the silent SSE drop on the `/{lang}/…` match node (reproduce, timing).
- [ ] Measure the CloudFront proxy TTL in case the Firebase host is ever restricted.

## Changelog

| Date | Change |
|---|---|
| 2026-09-25 | Provider behaviour under the stream documented ("What the provider does with this"): the ticker is re-read when the scoreboard gets ahead of it and at most once a minute otherwise, every node read carries `X-Firebase-ETag: true` (the `304` re-verified on a ticker node), and a failed stream is reconnected rather than ending the live view. |
| 2026-09-11 (evening) | Live verification: Union Berlin–Schalke followed from `PRE_MATCH` to `FINAL_WHISTLE` (15 live samples), all five `matchStatus` values, SSE patch shapes on three streams, `videoAssistant` entry type, live table updates, clock-regression and running-copy score quirks. |
| 2026-09-11 | Initial mapping: Firebase RTDB tree (config, matches, match, ticker, lineup, stats, rankings, 4 tables, season stats), pre-match + finished samples for Bundesliga, table samples for 2. Bundesliga and 2025/26. Paths extracted from the bundesliga.com Angular bundle (`["","all",comp,"seasons",season,…].join("/")` builders). |
