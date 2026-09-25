# Allsvenskan API (SEF / allsvenskan.se — `gql.sportomedia.se`)

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | Sweden |
| **Official site** | https://allsvenskan.se (Svensk Elitfotboll, the top-two-tier clubs' organisation) |
| **Base URL** | `https://gql.sportomedia.se/graphql` — a single **GraphQL** endpoint (queries + SSE subscriptions) |
| **Auth** | None. No key, no cookie, no `Origin` check. Works with an empty `User-Agent` |
| **Format** | JSON (`application/json; charset=utf-8`), Google App Engine (`server: Google Frontend`) |
| **CORS** | **Yes** — `Access-Control-Allow-Origin` echoes any `Origin`, `Access-Control-Allow-Credentials: true`; `OPTIONS` preflight `204` allowing `POST` + `content-type` |
| **WAF / UA requirement** | None |
| **Conditional requests** | An `etag` (bare SHA-256 of the body) and `last-modified` (= `date`) are returned, but `If-None-Match` never yields `304` (GET or POST). No `Cache-Control`, no CDN |
| **Schema** | Introspection is open — [`schema.graphql`](schema.graphql) is the SDL captured 2026-09-12 (117 types, 51 query roots, 3 subscriptions) |
| **Last full verification** | 2026-09-12 |
| **Status** | ✅ verified pre-match, live (first half, half time, second half) and full-time (league and cup); **not used for games**: 29 of the first 100 ticks were 429s or timeouts and `matchMinute` froze (see Core model mapping). The core reads only tables, teams, squads and players from it; games come from [Fogis](../fogis-livescore/README.md). Live samples kept since 2026-09-25 |

## Overview

allsvenskan.se is a WordPress site (WP Engine) whose custom `sef-leagues` theme
embeds a React app (`/wp-content/themes/sef-leagues/build/main.js`). Every piece of
football data on it — fixtures, scores, live ticker, line-ups, stats, tables, squads,
player pages — comes from one GraphQL endpoint, `https://gql.sportomedia.se/graphql`,
exposed as `window.gqlURI` in the page HTML. Sportomedia is the agency that builds
SEF's web and club apps; the same backend serves **Superettan** (tier 2) and
**Damallsvenskan** (women's top tier), the clubs' own apps, and cup/European fixtures
of the member clubs. The data itself is a merge of several sources visible in the
ids: `fogisId` (the Swedish FA's Fogis system — match ids *are* Fogis ids),
`everySportId`, `smcId` (Sportomedia's own), and a Forzasys/NEP tagging feed
(`matchEvents[].source: "forzasys"`) that produces the live ticker with
**second-precision `gameTime`** and links every shot, card, sub and goal to a
highlight clip.

What it gives us: the season fixture list with UTC kick-offs, status, period and
minute; one `match` resource with score, extended status, `matchMinuteWithStoppageTime`
(`"45+5'"`, `"FT"`), attendance, referees and a full event list (kick-off, shots,
shots on target, goals with assist, cards, subs, offsides, half-time/full-time
markers, each with a Swedish description and a video clip pointer); line-ups with
formation, a formation slot per player and per-player **physical data** (distance,
sprints, max speed) and shot counts; team stats per half and total (possession,
shots, corners, offsides, cards, subs, distance); the league table
(total/home/away) with last-five form; team lists, squads with season stats, staff,
player profiles with per-season history; scorer/assist/yellow-card leaders; and a
season-wide statistics dump (698 players × 27 stats, 16 teams × 34 stats). Live
updates are pushed over **GraphQL subscriptions (SSE)** — the site does no timed
polling of the match page at all.

All samples in [`samples/`](samples/) were captured on **2026-09-12** (season 2026,
round 21). See [`samples/_meta.md`](samples/_meta.md).

## Transport

GraphQL only — there are no REST paths. Both of these work identically:

```bash
# POST (what the site does)
curl -sS -H 'Content-Type: application/json' -A 'OpenScore/0.1 (+https://github.com/<org>/OpenScore)' \
  --data '{"query":"query($l:String!,$y:Int!){ matchesForLeague(configLeagueName:$l, configSeasonStartYear:$y, startDate:\"2026-09-12\", endDate:\"2026-09-14\"){ matches { id startDate homeTeamAbbrv visitingTeamAbbrv status homeTeamScore visitingTeamScore } } }","variables":{"l":"allsvenskan","y":2026}}' \
  https://gql.sportomedia.se/graphql

# GET (query + variables as URL parameters)
curl -sS -G --data-urlencode 'query={ match(id:6529993, configLeagueName:"allsvenskan", configSeasonStartYear:2026){ match { id status homeTeamScore visitingTeamScore } } }' \
  https://gql.sportomedia.se/graphql
```

- Errors come back as `200` with an `errors[]` array and `data.<field>: null`.
  Validation errors are precise (`Cannot query field "x" on type "Y"`,
  `GRAPHQL_VALIDATION_FAILED`); backend errors are opaque
  (`"Unexpected error."`, `INTERNAL_SERVER_ERROR`) and are how "not found" is
  expressed (see Quirks). An invalid POST body gives `400`-style `BAD_REQUEST`.
- Field selections below are the ones the site sends (extracted from the bundle's
  `gql` template literals); the full field lists are in [`schema.graphql`](schema.graphql).
- **Subscriptions** use the [graphql-sse](https://github.com/enisdenjo/graphql-sse)
  "distinct connections" mode: `GET /graphql?query=subscription…&variables=…` with
  `Accept: text/event-stream` → `200 text/event-stream`, `cache-control: no-cache`.
  The server sends `:` comment lines as keep-alives and, on each change,
  `event: next` / `data: {"data":{"matchSubscription":{…}}}` (same shape as the
  query). The site opens one stream each for `matchSubscription`,
  `lineupsSubscription` and `matchStatsSubscription` when a match page is open,
  re-opens them when the tab becomes visible again, and uses the plain queries as
  the initial/fallback data. Verified connecting pre-match; push cadence and payload
  were not measured, since the feed was dropped for live data after the 2026-09-13 poll.

There is a `Mutation` type with a single field (`commentHide`, moderation) — never
call it.

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| League (`configLeagueName`) | lowercase string, **case-insensitive** on input | `allsvenskan` · `superettan` · `damallsvenskan`; cup/European fixtures appear under `svenskacupen`, `championsleague`, `europaleague`, `conferenceleague` | Constant. The bundle also defines `cup`, `friendly`, `european`, `futsal`, `allsvenskanQualification`, `superettanQualification` (unverified). `leagueName` in responses is the display form (`"Allsvenskan"`, `"Conference League Tredje kvalomgången"`) |
| Season (`configSeasonStartYear`) | integer = calendar year (Allsvenskan runs Apr–Nov) | `2026` | `seasons(configLeagueName)` → 102 entries back to 1924 (`id: "allsvenskan-2026"`, `years: "2026"` / `"1924/1925"`); everything else in the entry is `null` |
| Match | integer = **Fogis match id** (`fogisId` echoes it; `everySportId` is `0`) | `6529993` (BK Häcken–Mjällby AIF, round 21) | `matchesForLeague` / `matchesForTeam`. A match is addressed by **id + configLeagueName + season together** — the wrong league or season for a valid id is an error, not a redirect |
| Round | integer 1…30 | `21` | match `round` |
| Team | `abbrv` — short uppercase string, stable across seasons; women's teams are prefixed `DAM` | `AIK`, `BKH`, `DEIF`, `DIF`, `GAIS`, `GBG`, `HAM`, `HBK`, `IFBP`, `IFE`, `IKS`, `KFF`, `MAIF`, `MFF`, `OIS`, `VSK` (2026); `DAMAIK` | `teamsForLeague`; match `homeTeamAbbrv`/`visitingTeamAbbrv`. Also `fogisId` (66036 AIK), `everySportId` (9367), `smcId` (10), and `teamId` in standings (= `fogisId`) |
| Player | integer = Fogis player id (`fogisId` = `id`); `smcId` is a second, Sportomedia id | `1500426` (Andronikos Kakoullis) | `squad`, `lineups`, `leaders`, `statistics.player[].fogisId`. Player images are `https://information-20ca4.kxcdn.com/player/{id}[--{season}].png` |
| Event | `key` — 32-hex hash; `pushHash` — 12–13 char id shared with the highlight clip | `aa52b5b8aaf93d846317d9d9948476ef` | `match.matchEvents[]`. `pushHash` = `latestHighlightsFromMatches.videos[].id` = `video.id` |
| Standings row | `"{type}-{teamId}-{x}"` | `total-133348-108445` | `standingsForLeague` |

## Discovery path

1. **Season:** `configSeasonStartYear` = current year while the season is on
   (Allsvenskan 2026: 2026-04-04 → 2026-11-29). `seasons(configLeagueName:"allsvenskan")`
   confirms the list; the last entry is the current one.
2. **Fixtures:** `matchesForLeague(configLeagueName, configSeasonStartYear)` — the
   whole season (240 matches, 30 rounds, sorted by `startDate`), or a window with
   `startDate`/`endDate` (`YYYY-MM-DD`, end **exclusive**, time part ignored). The
   site asks for −2…+3 months and re-fetches every 60 s. Each entry already carries
   `status`, `extendedStatus`, `period`, `matchMinute` and the score.
3. **Match page:** `match(id, configLeagueName, configSeasonStartYear)` → header +
   `matchEvents[]`; `lineups(…)` → formation and 11+subs per side with per-player
   stats; `matchStats(…)` → team stats per half; `matchConfig(…)` → whether the
   match time/possession/distance should be hidden and any "interrupted" banner.
4. **Live:** open `matchSubscription` / `lineupsSubscription` /
   `matchStatsSubscription` SSE streams for the same arguments, or poll the three
   queries. `status` (`UPCOMING → ONGOING → FINISHED`) and `period` tell you where
   you are; `matchEvents[]` is newest-first.
5. **Table:** `standingsForLeague(…, type:"total"|"home"|"away")`.
6. **Teams and people:** `teamsForLeague` → `team(abbrv)`, `squad(abbrv, season)`,
   `teamStaff(abbrv, season)`, `teamForm(abbrv, limit)`, `matchesForTeam(abbrv, season)`
   (includes cup/European matches); `player(id)`.

Recommended poll interval for live matches: **20 s** for `match` (the site itself
uses push; no edge cache exists, so every request hits the backend — be polite),
60 s for `matchStats`/`lineups`, and use the SSE subscriptions where you can hold a
connection. Cache lists 60 s and everything else for 10 minutes (the site's
`staleTime` values).

## Operations

All examples are `POST` bodies' `query` text with `variables`; `$l`/`$y` are
`configLeagueName`/`configSeasonStartYear` unless noted. Response shape is always
`{ "data": { "<root>": … } }`.

### `seasons(configLeagueName!, sort)`

| | |
|---|---|
| **Purpose** | Season list for a league |
| **Sample** | [`samples/seasons.json`](samples/seasons.json) — `allsvenskan`, `sort:"desc"` |
| **Last verified** | 2026-09-12 |

Returns `[{id, name, years, configLeagueName, configSeasonStartYear, startDate, endDate, everySportId}]`
— 102 Allsvenskan seasons (1924…2026), 27 Superettan (2000…), 27 Damallsvenskan
(1999…). Only `id`/`years`/`configSeasonStartYear` are populated. `sort:"desc"`
still came back ascending.

### `matchesForLeague(configLeagueName!, configSeasonStartYear!, startDate, endDate)`

| | |
|---|---|
| **Purpose** | Fixtures + results + live status for a league season or date window |
| **Parameters** | `startDate`/`endDate` — `YYYY-MM-DD`; start inclusive, **end exclusive**; a `T…Z` time part is ignored. Omit both for the whole season |
| **Samples** | [`samples/matches-for-league.round.json`](samples/matches-for-league.round.json) (2026-09-10…16, 8 matches: one `FINISHED`, seven `UPCOMING`) · [`samples/matches-for-league.season.json`](samples/matches-for-league.season.json) (whole 2026 season, truncated to 12 of 240) · [`samples/matches-for-league.superettan.json`](samples/matches-for-league.superettan.json) · [`samples/matches-for-league.damallsvenskan.json`](samples/matches-for-league.damallsvenskan.json) |
| **Last verified** | 2026-09-12 |

`matches[]` of `MinimizedMatch`: `id`, `startDate` (UTC ISO, `.000Z`),
`homeTeamName`/`visitingTeamName` (+ `…Formatted`), `homeTeamAbbrv`/`visitingTeamAbbrv`,
`homeTeamScore`/`visitingTeamScore` (0–0 before kick-off), `status`, `extendedStatus`,
`period` (`null` unless live), `round`, `matchMinute` (0 unless live),
`configLeagueName`, `leagueName`, `arenaName`, the `fogisId`/`everySportId` cross-ids
per team, `cacheVersion` (`"SHORT"`). Sorted by `startDate` ascending. No
`matchMinuteWithStoppageTime` on this type — use `match` for that.

### `matchesForTeam(abbrv!, configSeasonStartYear!, startDate, endDate)`

| | |
|---|---|
| **Purpose** | All matches of one club in a season, **across competitions** |
| **Sample** | [`samples/matches-for-team.json`](samples/matches-for-team.json) — Hammarby 2026: 41 matches over `allsvenskan`, `svenskacupen`, `europaleague`, `conferenceleague` |
| **Last verified** | 2026-09-12 |

Same `MinimizedMatch` shape. The cup/European entries carry their own
`configLeagueName`, which is what you must pass to `match(...)` to open them.
⚠️ Returns `"Unexpected error."` for some clubs (`AIK`, `GBG`, `VSK`, `HBK` on
2026-09-12) and an empty list for a lowercase `abbrv` — worked for `HAM`, `MAIF`,
`IFBP`, `BKH`. Treat as best-effort; `matchesForLeague` is the reliable list.

### `match(id!, configLeagueName!, configSeasonStartYear!)`

| | |
|---|---|
| **Purpose** | Match header + full event ticker |
| **Samples** | [`samples/match.final.json`](samples/match.final.json) (Häcken 1–1 Mjällby, `FINISHED`/`FINISHED_RECENTLY`, 33 events) · [`samples/match.pre.json`](samples/match.pre.json) (AIK–Västerås, `UPCOMING`, `matchEvents: []`) · [`samples/match.final.cup.json`](samples/match.final.cup.json) (Shamrock Rovers 0–1 Hammarby, `conferenceleague`, `everysport`-sourced events) |
| **Last verified** | 2026-09-12 |

**Response shape** — `match.match` (yes, nested twice):

- Header: everything in `MinimizedMatch` plus `matchMinuteWithStoppageTime`
  (`"FT"` when finished; `"45+5'"` style while live), `homeTeamLogo`/`visitingTeamLogo`
  (svenskfotboll.se CDN), `arenaName`, `spectators` (int, 0 when unknown),
  `referees[]` (names: referee, two assistants), `isAvailablePublicly` (media flag),
  `homeTeamLineup`/`visitingTeamLineup` (**futsal only** — `null` for football; use
  `lineups`).
- `matchEvents[]`, **newest first**: `type`, `typeString` (Swedish label: `Mål`,
  `Skott`, `Byte`, `Varning`, `Offside`), `gameTime` (**seconds** from kick-off of the
  match, e.g. 2700 = start of second half, 5520 = 92:00), `period`,
  `minuteWithStoppageTime` (`"17'"`, `"45+5'"`, `"90+2'"`; `null` on the first
  kick-off), `description` (Swedish ticker text), `teamName`, `byHomeTeam`,
  `playerName`, `assistPlayerName` + `assistPlayerId` (goals), `inPlayerName`/`outPlayerName`
  (subs), running `homeTeamScore`/`visitingTeamScore` and per-period
  `…PeriodScore`, `source` (`forzasys` for the tagged Allsvenskan feed, `everysport`
  / `inferred` for cup matches), `key`, `pushHash`, `isPrivate`, `rating` (1–4
  editorial importance), `video{id, webUrl, streamUrl (HLS), thumbnail, duration (ms
  as string), isAvailablePublicly, isLive}` — link to it, never store it.

**Observed enum values** *(may be incomplete)*

- `status`: `UPCOMING`, `FINISHED` (schema also: `ONGOING`, `INTERRUPTED`, `POSTPONED`)
- `extendedStatus`: `UPCOMING`, `FINISHED_RECENTLY`, `FINISHED` (schema also:
  `UPCOMING_STARTING_SOON`, `UPCOMING_STARTING`, `ONGOING`, `INTERRUPTED`, `POSTPONED`, `CANCELED`)
- `period`: `null` (pre/post) — schema: `PERIOD_FIRST_HALF`, `PERIOD_SECOND_HALF`,
  `PERIOD_FIRST_OVERTIME`, `PERIOD_SECOND_OVERTIME`, `PERIOD_PENALTIES`; events use the same strings
- `matchEvents[].type` (a `String`, not an enum): `START`, `PERIOD_RESULT`, `GOAL`,
  `SHOT`, `SHOT_ON_TARGET`, `SUBSTITUTION`, `WARNING`, `SECOND_WARNING`, `OFFSIDE`.
  The bundle's switch also handles `MISC`, `FREE_KICK`, `PENALTY`, `RED_CARD`,
  `CORNER`, `SAVE`, `VAR` — not yet observed.
- `matchEvents[].source`: `forzasys`, `everysport`, `inferred`

`START` and `PERIOD_RESULT` events mark kick-off (`gameTime` 0 / 2700) and the
period ends (`"Paus."` at `45+5'`, `"Matchen slut…"` at `90+2'`), so the event list
alone gives you every period boundary in seconds.

### `lineups(id!, configLeagueName!, configSeasonStartYear!)`

| | |
|---|---|
| **Purpose** | Starting XI, bench, formation and per-player stats |
| **Samples** | [`samples/lineups.final.json`](samples/lineups.final.json) (Häcken 4-2-3-1, Mjällby 3-4-3) · [`samples/lineups.pre.json`](samples/lineups.pre.json) (not published: `formation: "fallback"`, empty arrays) |
| **Last verified** | 2026-09-12 |

`homeTeam`/`visitingTeam` → `{abbrv, formation ("4-2-3-1" | "fallback"), starting[11], substitutes[]}`.
Each `LineupPlayer`: `id`, names, `shirtNumber`, `position` (**formation slot**
`"1"`…`"11"`, `"Sub"` for the bench), `positionIndexFromBackRight` (0 in the sample),
`positionText` (`Goalkeeper`, `Back`, `Centre-Back`, `Left-Back`, `Midfield`,
`Forward`, or `null`), status flags `hasBeenSubstituted`/`hasScored`/`hasWarning`/`hasRedCard`,
`image`, and tracking/physical data — `distance` (m), `maxSpeed` (km/h), `sprints`,
`sprintDistance`, `high/mid/lowIntensityRuns` + `…Distance`, plus `shotsOnTarget`,
`shotsOffTarget`, `receivedFreeKicks`, `givenFreeKicks`, `savedShots`, `savePercentage`.

### `matchStats(id!, configLeagueName!, configSeasonStartYear!)`

| | |
|---|---|
| **Purpose** | Team stats for first half, second half and total |
| **Samples** | [`samples/match-stats.final.json`](samples/match-stats.final.json) · [`samples/match-stats.pre.json`](samples/match-stats.pre.json) (**`"Unexpected error."`** — no stats object exists before kick-off) |
| **Last verified** | 2026-09-12 |

`{id, firstPeriodStats, secondPeriodStats, totalStats}`, each `PeriodStats` with
`homeTeam…`/`visitingTeam…` × `Possesion` (sic, percent), `Shots`, `ShotsOnTarget`,
`ShotsOffTarget`, `Corners`, `Offsides`, `YellowCards`, `RedCards`, `Substitutions`,
`Distance` (m). ⚠️ `…ShotsOffTarget` equals `…Shots` in the sample (14/14, 7/7) —
it looks like total shots, not off-target; derive off-target as `Shots − ShotsOnTarget`.

### `matchConfig(id!, configLeagueName!, configSeasonStartYear!)`

| | |
|---|---|
| **Purpose** | Presentation flags for a match |
| **Sample** | [`samples/match-config.json`](samples/match-config.json) |
| **Last verified** | 2026-09-12 |

`{showInterruptedMatchInformation, interruptedMatchText, hideMatchTime, hideStats{ballPossesion, distance}}`.
Honour `hideMatchTime` (the league hides the clock when the feed is unreliable).

### `standingsForLeague(configLeagueName, configSeasonStartYear, type)`

| | |
|---|---|
| **Purpose** | League table |
| **Parameters** | `type` — `total`, `home`, `away` (anything else → `standings: []`) |
| **Samples** | [`samples/standings-for-league.total.json`](samples/standings-for-league.total.json) · [`.home.json`](samples/standings-for-league.home.json) · [`.away.json`](samples/standings-for-league.away.json) · [`.superettan.json`](samples/standings-for-league.superettan.json) |
| **Last verified** | 2026-09-12 |

`standings[]` ordered by `position`: `teamName`, `teamAbbrv`, `officialAbbrv`,
`teamId` (Fogis), `previousPosition` (`null` on 2026-09-12), `borderType`
(`noborder` | `borderbottom` — the site's zone separators: after positions 1, 2, 3,
13, 14 → title/UCL/UEL/relegation play-off/relegation lines; Superettan draws them
after 2, 4, 12, 14 → promotion/promotion play-off/relegation play-off/relegation,
plus a trailing one on the last row), `logoImageUrl`,
`stats[]` as `{name, value}` **strings**: `gp`, `w`, `t`, `l`, `gf`, `ga`, `d`, `pts`,
and `form[]` — the last 5 matches with `matchResult` (`W`/`D`/`L`), score, round,
`startDate`. Past seasons work (`2025` → Mjällby AIF 75 pts). Whether the table
updates during play has not been verified.

`standingsForTeam(teamAbbrv, configSeasonStartYear, type)` returned `standings: []`
for `AIK`/`total` ([`samples/standings-for-team.json`](samples/standings-for-team.json)) — use the league table and filter.

### `teamsForLeague(configLeagueName, configSeasonStartYear)` · `team(abbrv)`

| | |
|---|---|
| **Samples** | [`samples/teams-for-league.json`](samples/teams-for-league.json) (16 teams) · [`samples/team.json`](samples/team.json) (AIK) |
| **Last verified** | 2026-09-12 |

Team: `abbrv`, `name`/`displayName`, `gender` (`men`/`women`), `sport` (`FOOTBALL`),
`fogisId`, `everySportId`, `smcId`, `logoImageUrl`, `squadImage`, `ticketType`.
`team(abbrv)` adds `arena{name, built, spectators, originalImageUrl}`, `info[]`
(`{name, value}` Swedish key facts: founded, seasons in Allsvenskan, home arena…) and
`links[]` (club site/social). Verified for `superettan` (16 teams) and
`damallsvenskan` (`DAM…` abbrvs, `gender: women`).

### `squad(abbrv, configSeasonStartYear)` · `teamStaff(abbrv, configSeasonStartYear)` · `teamForm(abbrv!, limit!)`

| | |
|---|---|
| **Samples** | [`samples/squad.json`](samples/squad.json) (AIK, 28 players) · [`samples/team-staff.json`](samples/team-staff.json) (head coach only) · [`samples/team-form.json`](samples/team-form.json) (last 5: `result` `WIN`/`DRAW`/`LOSS`, `type` `HOME`/`AWAY`, `opponentId` = abbrv) |
| **Last verified** | 2026-09-12 |

Squad is grouped `goalkeepers` / `defenders` / `midfields` / `forwards`; each player has
`id`/`fogisId`/`smcId`, names, `shirtNumber`, `position` (`Goalkeeper`, `Back`,
`Left-Back`, `Midfield`, `Forward`), `nationality`, `birthDate`, `height`/`weight`,
socials, `image`, and `currentSeasonStats{matchesPlayed, matchesStarted, goals,
assists, yellowCards, redCards, competitionDisplayName}`. ⚠️ Players without stats
get an all-zero stub with `configSeasonStartYear: 0` and empty strings, not `null`.

### `player(id)` · `playerCurrentSeasonStats(id, season)`

| | |
|---|---|
| **Samples** | [`samples/player.json`](samples/player.json) (Kakoullis, with 2025 history) · [`samples/player-current-season-stats.json`](samples/player-current-season-stats.json) (`null`) |
| **Last verified** | 2026-09-12 |

`player` = profile + `currentSeasonStats` + `stats[]` (one `PlayerStats` per
league-season with `gameStats{goals, assists, yellowCards, redCards, matchesPlayed,
matchesStarted, matchesSubstituted}`). No team link beyond `teamLogo` (often `null`)
— take the club from `squad`/`lineups`. `playerCurrentSeasonStats` returned `null`
with and without `season`.

### `leaders(configLeagueName!, configSeasonStartYear!, type!, abbrv)`

| | |
|---|---|
| **Parameters** | `type` — verified `goals`, `assists`, `yellowCards`. ⚠️ An unknown value (`redCards`) crashed the backend with **HTTP 503** — do not guess |
| **Samples** | [`samples/leaders.goals.json`](samples/leaders.goals.json) · [`.assists.json`](samples/leaders.assists.json) · [`.yellowCards.json`](samples/leaders.yellowCards.json) (each truncated to 20 of 173–250) |
| **Last verified** | 2026-09-12 |

`leaders[]` ordered by `toplistNumber`: player ids/names/image/team, `statsValue`,
`matchesPlayed`. Every player with ≥ 1 is listed. The site does not use this query
(it derives its top-lists from `statistics`); it is probably for the club apps.

### `statistics(configLeagueName!, configSeasonStartYear!)`

| | |
|---|---|
| **Purpose** | Season-wide player and team statistics in one 560 KB document |
| **Sample** | [`samples/statistics.json`](samples/statistics.json) — `player[]` truncated to 5 of 698, `team[]` complete (16) |
| **Last verified** | 2026-09-12 |

`player[]`: profile + `matchesPlayed`, `goals`, `assists`, shot splits
(`shots`, `shotsOnTarget`, `shotsPostOrBar`), goal types (`penaltyGoals`,
`headerGoals`, `freekickGoals`, `cornerHeaderGoals`, `allCornerGoals`, `subinGoals`),
cards, and physical totals (`totalDistance`, `highestSpeed`, `highestDistanceInMatch`,
`averageDistancePerMatch`; `springDistance` sic). Includes players with 0 matches and
`teamAbbrv: null`. `team[]`: goals/shots/set-piece splits, cards, corners, distance
home/away/total, average possession per half as home/away, `averageAttendees`,
`totalAttendees`. Cache for hours.

### Media metadata (link only)

`latestHighlightsFromMatches(configLeagueName, configSeasonStartYear, team, type)`
([sample](samples/latest-highlights-from-matches.json)) and
`officialHighlightsReels(…)` ([sample](samples/official-highlights-reels.json))
return clip lists with `matchId`, `minuteWithStoppageTime`, `type`
(`SHOT`, `GOAL`, …), `webUrl` (highlights.allsvenskan.se), `streamUrl` (HLS on
api.fotbollplay.se), `thumbnail`. `teamLogos(ids:[abbrv])` ([sample](samples/team-logos.json))
maps abbrvs to logo URLs on `data-20ca4.kxcdn.com` (a KeyCDN front for the
`connectedleaguedata.appspot.com` GCS bucket). Do not redistribute any of it.

## Game states

Observed in the kept samples. The live rows come from a 375-poll recording of Hammarby
3-1 IF Brommapojkarna (game `6529991`, 2026-09-13), curated 2026-09-25:

| State | `status` / `extendedStatus` | `period` | `matchMinute` / `matchMinuteWithStoppageTime` | Newest `matchEvents` entry |
|---|---|---|---|---|
| Pre-match | `UPCOMING` / `UPCOMING` | `null` | `0` / `"-"` | `[]` (`spectators: 0`, `referees` already set) |
| About to start | `UPCOMING` / `UPCOMING_STARTING` | `null` | `0` / `"-"` | `START` |
| First half | `ONGOING` / `ONGOING` | `PERIOD_FIRST_HALF` | `11` / `"11'"` | whatever last happened |
| Stoppage time | `ONGOING` / `ONGOING` | `PERIOD_FIRST_HALF` | `45` / `"45+2"` | as above |
| **Half time** | `ONGOING` / `ONGOING` | **`PERIOD_FIRST_HALF`**, not null | `45` / **`"HT"`** | **`PERIOD_RESULT`** (`description: "Paus."`) |
| Second half | `ONGOING` / `ONGOING` | `PERIOD_SECOND_HALF` | `49` / `"49'"` | `START` |
| Just finished | `FINISHED` / `FINISHED_RECENTLY` | `null` | `0` / `"FT"` | `PERIOD_RESULT`, list newest first |
| Finished (older) | `FINISHED` / `FINISHED` | `null` | `0` / `"FT"` | as above |

**Half time does not clear `period`.** It stays `PERIOD_FIRST_HALF` for the whole break
(13 minutes here) and only `matchMinuteWithStoppageTime` becomes `"HT"`, so a rule of the
form "`ONGOING` with no period" never fires and a break reads as in play. The dependable
marker is the **newest `matchEvents` entry being `PERIOD_RESULT`**, because `"HT"` outlives
the restart by a poll: at 13:09:07Z the minute still read `"HT"` and `period` was still
`PERIOD_FIRST_HALF` while the events already showed `START`.

`INTERRUPTED`, `POSTPONED`, `CANCELED`, `UPCOMING_STARTING_SOON`, extra time and penalties
remain unobserved. The score is on every list entry, so a schedule poll doubles as a
scoreboard.

## Quirks & gotchas

- **Times are UTC** (`2026-09-12T13:00:00.000Z`); Allsvenskan kicks off at 15:00/17:30
  Swedish time. Date filters are calendar dates with an exclusive end.
- **`gameTime` is seconds, not minutes**, counted continuously through the match
  (second half starts at 2700 regardless of first-half stoppage). Together with the
  `START`/`PERIOD_RESULT` events this is the most precise event clock of the football
  leagues mapped so far. `matchMinute` (int) and `matchMinuteWithStoppageTime`
  (string) are the display clock; its live cadence is unverified.
- **Errors mean "not found"**: `match(id:1, …)`, a valid id with the wrong
  `configLeagueName` or season, and `matchStats` for a match that has not started
  all return `errors[0].message: "Unexpected error."` with `data.<field>: null`,
  HTTP 200. The only non-200 seen was the **503** from an unknown `leaders.type`.
- **Events are newest-first** and identified by `key`; `pushHash` links to the clip.
  `isPrivate: true` marks editorial/ticker-only events (kick-off, period end);
  `rating` 1–4 is importance (goals 4, cards 3, ticker 2).
- `match.match.homeTeamLineup`/`visitingTeamLineup` are **futsal** types — always
  `null` for football; use `lineups`.
- Lineup `position` is the formation slot (`"1"`…`"11"`), not a role; `positionText`
  is the role and can be `null` for bench players.
- `matchStats.*ShotsOffTarget` = `*Shots` (see above). Possession is an integer percent.
- `squad` stat stubs are all-zero with `configSeasonStartYear: 0` instead of `null`.
- `matchesForTeam` errors for some clubs; `standingsForTeam` and `matchUp` returned
  empty/`null`; `playerCurrentSeasonStats` returned `null`; `seasons(sort:"desc")`
  is ignored. Everything the *site* uses works; the extra roots look app-only or stale.
- `cacheVersion: "SHORT"` on match objects is a hint for the client cache tier, not an ETag.
- No `Cache-Control`, no CDN, no `304` — every request is served by the app. Keep
  polling polite; prefer the subscriptions for live.
- Descriptions, `typeString`, `info[].name` and `leagueName` are **Swedish**.
- The same endpoint serves `superettan` and `damallsvenskan` with identical shapes;
  there is no separate Superettan API. superettan.se is the same `sef-leagues`
  WordPress theme with the same `gqlURI`. Verified 2026-09-12 for `superettan`:
  `matchesForLeague`, `match` (Forzasys events with second-precision `gameTime`,
  `spectators`), `lineups`, `matchStats`, `standingsForLeague`, `teamsForLeague`,
  `seasons` and the `matchSubscription` SSE handshake; for `damallsvenskan`:
  `matchesForLeague`, `teamsForLeague`, `seasons`. Superettan 2026 abbrvs: `NOR`,
  `FFF`, `OFK`, `VAR`, `IKO`, `NFC`, `LAN`, `SANDIF`, `OIF`, `HIF`, `VARN`, `LJUN`,
  `NIF`, `OSK`, `IKB`, `SUN`. Only the standings `borderType` zones differ (see
  `standingsForLeague`). The clubs'
  European matches are reachable through `matchesForTeam` and
  `match(configLeagueName:"conferenceleague"|…)` with `everysport`-sourced events
  (cards and goals only, `spectators: 0`, no referees).
- **Svenska Cupen** is also league-wide: `matchesForLeague(configLeagueName:
  "svenskacupen", configSeasonStartYear: 2026)` returns the whole 2025/26 edition
  (119 matches from the May 2025 first round to the 2026-05-14 final, amateur clubs
  included). The cup's season key is the **end** year, `round` is *not* the stage
  (the final is `round: 1`; infer stages from dates), events are `source: "fogis"`
  (or `"inferred"`) with possession `0`, and `seasons("svenskacupen")` skips 2025.
  The **2026/27 edition was absent** on 2026-09-12 although its round 2 had been
  played (18–26 Aug): nothing under 2026 or 2027, `match(6827326)` →
  `INTERNAL_SERVER_ERROR`. For current cup coverage use the FA's
  [Fogis livescore feed](../fogis-livescore/README.md), which shares this API's ids.

## Out of scope

- `Mutation.commentHide`, `comments`, `contentReactions`, `poll`/`activePolls`/`countedVotes`,
  `quizResult`, `engagement`, `stories`, `onboarding`, `announcements`, `faq`,
  `appPromos`, `appTheme`, `teamsAppConfig`, `ticketConfig`, `affiliateLinks`,
  `article(s)`, `teamNews`/`leagueNews`, `fotbollPlay*`, `valuestats` (Unibet odds) —
  fan-engagement, editorial and betting roots; not called.
- `/auth/idtoken`, `/data-endpoint/vote` on allsvenskan.se — logged-in fan features.
- The WordPress REST API (`/wp-json/`) — editorial only.
- Video streams (`api.fotbollplay.se`, `video.sef.forzify.com`) — media; link, don't fetch.

## Core model mapping

**Used for tables, teams, rosters and players only.** Games, live state, events and lineups
for Allsvenskan and Superettan come from the FA's
[Fogis livescore](../fogis-livescore/README.md) instead. Polling the same match
(AIK–Västerås, 2026-09-13 12:00Z) through both feeds at the same 10–15 s cadence, this API
answered 29 of the first 100 live ticks with **HTTP 429** or a socket timeout, showed the
kick-off four minutes late, updated `matchMinute` in jumps (13' for over three minutes) and
carried a third of the events Fogis had; Fogis missed no tick. The `matchesForLeague` list
type (`MinimizedMatch`) also has no logo fields — logos are on `match`, `teamsForLeague` and
`teamLogos` only. The core reads `teamsForLeague` (abbrv, names, `fogisId`, crest; kept a
day) for both: crests for the `total` table, and the Fogis id ↔ abbreviation bridge that
lets the Swedish league wrappers key everything by Fogis id.

| Core concept | Source | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | constants + `seasons` | `configLeagueName`, `configSeasonStartYear` | Three leagues on one endpoint; season = calendar year |
| Game (id, teams, start time) | `matchesForLeague` | `id`, `startDate` (UTC), `homeTeamAbbrv`/`visitingTeamAbbrv`, `round`, `arenaName` | Id must be paired with league + season on every call |
| GameState | `match` / list | `status`, `extendedStatus`, `period` | Live values pending; `matchConfig.hideMatchTime` |
| Score by period | `match` | `homeTeamScore`/`visitingTeamScore`; `matchEvents[].{home,visiting}TeamPeriodScore` on `PERIOD_RESULT` events | Half-time score = the `PERIOD_RESULT` event with `period: PERIOD_FIRST_HALF` |
| Clock / period | `match` | `matchMinute`, `matchMinuteWithStoppageTime`, `period`; `START`/`PERIOD_RESULT` events' `gameTime` (s) | Real period start wall-clock only via `video.date` of the `START` event (tagging time, ±s) |
| GameEvent | `match.matchEvents[]` | `type`, `gameTime`, `minuteWithStoppageTime`, `playerName`, `assistPlayerName`/`assistPlayerId`, `in/outPlayerName`, `byHomeTeam`, `key` | Player ids only for assists; names otherwise — resolve via `lineups` |
| Lineups | `lineups` | `starting[]`/`substitutes[]`, `formation`, `position` slot, `hasBeenSubstituted` | Published shortly before kick-off (pre-match sample empty) |
| Team stats | `matchStats` | per half + total | `ShotsOffTarget` bug |
| Team | `teamsForLeague`, `team` | `abbrv`, names, `fogisId`, `logoImageUrl`, `arena` | |
| Player | `squad`, `player`, `lineups` | `id` (Fogis), names, `shirtNumber`, `position`, `nationality`, `birthDate`, physical data | |
| Standings | `standingsForLeague` | `position`, `stats[]` (`gp w t l gf ga d pts` as strings), `form[]` | Live update behaviour pending |
| Live push | subscriptions | `matchSubscription`, `lineupsSubscription`, `matchStatsSubscription` (SSE) | Payload cadence pending |

## Changelog

| Date | Change |
|---|---|
| 2026-09-12 | Initial mapping: schema, 33 samples (pre/final/cup + static), SSE handshake verified; live capture of AIK–Västerås SK in progress |
| 2026-09-13 | Live poll of AIK–Västerås SK alongside Fogis: 429s and timeouts on 29 of the first 100 ticks, kick-off shown four minutes late, `matchMinute` frozen for minutes. Dropped for games, live state, events and lineups; kept for standings, teams, squads and players (and the `fogisId` bridge) |
| 2026-09-25 | Live states curated from the 2026-09-13 recording of Hammarby 3-1 Brommapojkarna (375 polls). 5 new samples: first half, stoppage time into half time, the second half, `UPCOMING_STARTING`, and a stale in-play body served *during* the break. Found and fixed: half time keeps `period: "PERIOD_FIRST_HALF"` and only marks `"HT"` in the minute, so the core's break rule could never fire and 13 minutes of half time read as in play. Also measured here: 108 of 375 polls failed (62 `429` on `match`, 29 on `lineups`, 12 timeouts), and the Superettan game recorded the same day failed 149 of 375 - worse than the 29-in-100 that got this feed dropped for games |
