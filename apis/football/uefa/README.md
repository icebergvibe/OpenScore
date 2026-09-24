# UEFA competitions API (uefa.com — `match` / `comp` / `standings` / `matchstats` `.uefa.com`)

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | Europe (UEFA) — **Champions League**, **Europa League**, **Conference League** and the **Nations League** (one API; also every other UEFA competition, see [Identifiers](#identifiers)) |
| **Official site** | https://www.uefa.com |
| **Base URLs** | `https://match.uefa.com/v5` (matches, events, line-ups, livescore) · `https://comp.uefa.com/v2` (competitions, teams, players) · `https://standings.uefa.com/v1` (tables) · `https://matchstats.uefa.com/v1` (team / player statistics) |
| **Auth** | None. The CORS policy advertises `x-api-key` / `x-api-consumer-id` / `authorization` as *allowed* request headers, but nothing documented here needs any of them |
| **Format** | JSON (UTF-8); Spring Boot behind Akamai (`server-timing: cdn-cache; desc=HIT/MISS`, `ak_p`) |
| **CORS** | **No, for third parties** — `Access-Control-Allow-Origin: https://www.uefa.com` (fixed, `Vary: Origin`, not reflected). A browser app needs a proxy (the feed-server) |
| **WAF / UA requirement** | None on the API hosts — plain `curl` with our descriptive `User-Agent` works. **`www.uefa.com` itself resets non-browser TLS connections** (Akamai Bot Manager), so the site's JS bundles could not be fetched; the endpoint list below comes from the community bindings (see [Discovery](#discovery-path)) and was verified request by request |
| **Conditional requests** | Strong `ETag` on every 200 (exposed via `Access-Control-Expose-Headers`); `Cache-Control: public, max-age=…, s-maxage=…` everywhere — see [Caching](#caching) |
| **Last full verification** | 2026-09-13 (club competitions) · 2026-09-24 (Nations League) |
| **Status** | ✅ verified for pre-match, finished, extra-time and shoot-out matches (2025/26 final Paris–Arsenal, 2025/26 play-off Juventus–Galatasaray) and for all three club competitions of 2026/27 (UCL MD1 played, UEL MD1 2026-09-16/17, UECL MD1 2026-10-15), plus the Nations League 2026/27 (MD1 2026-09-24) and the 2025 final. **Live states not yet observed** — see [Game states](#game-states) |

## Overview

uefa.com's match centre, fixtures pages and tables are fed by a small family of public
JSON micro-services under `*.uefa.com`. They are the same services for every UEFA
competition — men's and women's, club and national team, futsal, youth — selected by a
numeric `competitionId`, and they are key-less: the only thing that distinguishes us from
the site is the missing CORS origin. Four are mapped here: the three club competitions
and the **Nations League**, whose only differences are national teams instead of clubs,
group tiers and a biennial calendar (see [Nations League](#nations-league)).

What it gives us: fixtures for any competition/season/date window with UTC kick-offs,
**one match object** carrying score (regular / after extra time / shoot-out /
aggregate), status, round + matchday + group, stadium, weather, officials, attendance,
the goal-scorers and red cards with **minute:second**, the related leg of a two-legged
tie, and the winner with the reason; a **timeline** endpoint with every match event
(goals with body part and pitch coordinates, cards, substitutions, shoot-out kicks,
VAR checks, **`START_PHASE` / `END_PHASE` markers with wall-clock UTC timestamps**,
injury time announced); line-ups with pitch coordinates, captain and kit colour; 429
team statistics per match (Opta-style names plus tracking data); the standings of any
group/league phase with tiebreak fields; and a 300-byte **livescore** endpoint that
lists the matches within ±1 h with a `hash` that changes whenever a match changed —
the site's cheap change detector.

Every response carries `translations` objects in nine languages (EN FR DE ES PT IT RU
ZH AR) on every team, player, competition, round and stadium, which makes bodies 3–5×
larger than they need to be (a match is ~30 KB, ~7 KB gzipped). There is no field
selection; always send `Accept-Encoding: gzip`.

Samples in [`samples/`](samples/) were captured on **2026-09-13** (season "2027" =
2026/27: UCL league phase MD1 played 2026-09-08..10, UEL MD1 and UECL MD1 upcoming) plus
two finished 2025/26 knock-out matches for extra time and penalties.

## Identifiers

All ids are **strings of digits** (`"2049553"`), `idProvider: "FAME"` (UEFA's Football
Administration & Management Environment). They are global — a team, player or match id
is the same in every competition — and stable across seasons.

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Competition | integer | `1` Champions League · `14` Europa League · `2019` Conference League · `9` Super Cup · `2008` Youth League · `22` Regions' Cup (club, men); `2014` Nations League, `3` EURO (national, men), … | `GET comp.uefa.com/v2/competitions` (all 307) or `?competitionIds=1,14,2019` / `?competitionIds=2014`. **Read the id off the feed, never off a name**: `2016` is the Youth Olympic Girls Futsal Tournament, not the Nations League |
| Season | **end year** as string: `"2027"` = 2026/27 | `2027` | `seasonYear` on every match / standings / team; a season starts with the first qualifying round in early July (2026-07-07 for 2027) |
| Round | integer | `2002239` (UCL 2026/27 league phase), `2002235` (first qualifying round) | match `round.id`; `round.metaData.type` = `FIRST_QUALIFYING` / … / `LEAGUE` / `KNOCK_OUT_PLAY_OFF` / `EIGHTH_FINALS` / … / `FINAL`; `round.mode` `GROUP` / `KNOCK_OUT` / `FINAL`, `modeDetail` `GROUP` / `KNOCK_OUT_ONE_LEG` / `KNOCK_OUT_TWO_LEGS` |
| Matchday | integer | `36598` (UCL MD1) | match `matchday.id`; `matchday.name` `MD1`, `R1L1`, `longName` `Matchday 1`; `dateFrom`/`dateTo` window |
| Group | integer | `2013887` ("League", the 36-team league phase) | match `group.id`, standings `group.id`; groups also exist in qualifying (`groupCount: 14` in a two-leg round = one "group" per tie) |
| Match | integer | `2049553` (Real Madrid–Inter, UCL MD1 2026/27) | any match list |
| Team | integer | `50051` Real Madrid · `50138` Inter · `52747` Paris · `52280` Arsenal; national sides are low numbers, `88` Malta · `43` France · `110` Portugal | match `homeTeam.id`; `teamCode` (`RMA`, `MLT`) is the 3-letter display code, `organizationId` a club-level id, `associationId` the FA. `typeTeam` `CLUB` / `NATIONAL` and `teamTypeDetail` (`NATIONAL_MEN_TEAM_A`) tell them apart |
| Player / person | integer | `250076574` Kylian Mbappé · `63672` José Mourinho (coach) · referees likewise | line-ups, events (`primaryActor.person.id`), `playerEvents` |
| Event | UUID | `2c10b40f-3960-4c3e-8aba-d8c8390c80a6` | events endpoint and match `playerEvents[].id` (same id) |
| Stadium | integer | `250002938` | match `stadium.id` |

Unknown ids: `GET /v5/matches/{id}` → **404** `{"error":{"message":"MATCH with id 999999999","status":404,"title":"Not Found"}}`
([sample](samples/match.not-found.json)); list endpoints with a filter that matches
nothing → `200 []`; a wrong parameter value → 404 with `"[X] is not valid for filter"`,
a missing required parameter → 400 (Spring conversion message).

## Discovery path

1. **Competition metadata (once):** `GET https://comp.uefa.com/v2/competitions?competitionIds=1,14,2019`
   → `id`, `code` (`UCL`/`UEL`/`UECL`), names, logo. Nothing here says which season is
   current; derive it from the date (July → next year's `seasonYear`) or take
   `seasonYear` from the latest match.
2. **Games on a day:** `GET https://match.uefa.com/v5/matches?competitionId=1&fromDate=2026-09-08&toDate=2026-09-08&limit=50&offset=0&order=ASC`
   (`competitionId=2014` for the Nations League)
   → array of full match objects (score, status, teams, round, `playerEvents`). Leave
   `competitionId` out to get every competition on that day (Youth League, Regions' Cup …).
   `offset` is **required** (404 `"null is not valid for offset"` without it).
3. **Live state, score, phase:** `GET https://match.uefa.com/v5/matches/{id}` — or, to
   refresh several at once, `GET /v5/matches?matchId=2049553,2050063`. One club's
   drawn fixtures: `GET /v5/matches?competitionId=1&seasonYear=2027&teamId=52280&offset=0&limit=100`
   (league phase first, later rounds as they are drawn; verified 2026-09-16).
4. **Timeline:** `GET https://match.uefa.com/v5/matches/{id}/events?filter=MAIN&order=ASC&limit=500&offset=0`
   → goals, cards, substitutions, shoot-out kicks, corners, phase start/end,
   injury time, full time. `filter` is **required**; values verified: `ALL`, `MAIN`,
   `LINEUP` (goals + cards + subs only), `GOALS`, `CARDS`, `PHASES`.
5. **Line-ups:** `GET https://match.uefa.com/v5/matches/{id}/lineups` (`{"lineupStatus":"NOT_AVAILABLE","matchId":…}` until published).
6. **Team statistics:** `GET https://matchstats.uefa.com/v1/team-statistics/{id}` (`[]` before the match).
7. **Standings:** `GET https://standings.uefa.com/v1/standings?competitionId=1&seasonYear=2027&phase=TOURNAMENT`.
8. **Teams / players:** `GET https://comp.uefa.com/v2/teams?teamIds=50051` ·
   `GET https://comp.uefa.com/v2/players?playerIds=250076574`.
9. **What is on right now:** `GET https://match.uefa.com/v5/livescore` — every match
   across all competitions from 1 h before kick-off to 1 h after full time, as
   `{ id, status, lineupStatus, hash, [score, minute, phase, …] }`; `max-age=4`. Poll this,
   and re-fetch the match only when its `hash` changed.

Recommended polling for a live match: `livescore` every **10 s** (300 bytes), the match
and `events?filter=MAIN` on hash change and at most every **30 s** otherwise. Edge TTLs
during live play are still to be measured (see [Caching](#caching)).

## Endpoints

No headers are required on any of them. `offset`/`limit` paginate the list endpoints
(`limit` ≤ 500 verified on matches; `1000` → 400).

### Competitions (`comp.uefa.com/v2`)

#### `GET /competitions`

| | |
|---|---|
| **Purpose** | Competition metadata |
| **Parameters** | `competitionIds` (comma list). Without it: all 307 competitions, 546 KB |
| **Sample** | [`samples/competitions.json`](samples/competitions.json) (`?competitionIds=1,14,2019`) captured 2026-09-13 · [`samples/competitions.unl.json`](samples/competitions.unl.json) (`?competitionIds=2014`) captured 2026-09-24 |
| **Last verified** | 2026-09-13 |

`[ { id, code, age (ADULT/YOUTH), region (CONTINENTAL), sex, sportsType, teamCategory
(CLUB/NATIONAL), type (CUP), images.FULL_LOGO, metaData.name, translations.name /
qualifyingName / tournamentName … } ]`. Not every competition has a `code`.

#### `GET /teams`

| | |
|---|---|
| **Purpose** | Teams of a competition season, or by id |
| **Parameters** | `competitionId` + `seasonYear` + `limit` + `offset`, **or** `teamIds` (comma list). Optional `roundIds`, `associationId` (unverified) |
| **Sample** | [`samples/teams.json`](samples/teams.json) (`?competitionId=1&seasonYear=2027&limit=100&offset=0` → 81 teams incl. qualifying) · [`samples/teams.by-id.json`](samples/teams.by-id.json) (`?teamIds=50051,50138`) captured 2026-09-13 · [`samples/teams.unl.json`](samples/teams.unl.json) (`?teamIds=88` → Malta, a national side) captured 2026-09-24 |
| **Last verified** | 2026-09-24 |

Team object (same everywhere): `id`, `internationalName`, `teamCode`, `countryCode`
(alpha-3), `associationId`, `organizationId`, `logoUrl` (70 px) / `mediumLogoUrl` (240) /
`bigLogoUrl` (700), `associationLogoUrl`, `isPlaceHolder`, `typeIsNational`, `typeTeam`
(`DOMESTIC`), `teamTypeDetail`, `translations { displayName, displayOfficialName,
shortName, displayTeamCode, countryName }`. Unknown `teamIds` → `[]`. `typeTeam` is
`CLUB` or `NATIONAL`; a national side carries `teamTypeDetail: NATIONAL_MEN_TEAM_A`,
`typeIsNational: true` and the **country's flag** as `logoUrl` (`/imgml/flags/70x70/MLT.png`)
instead of a club crest.

#### `GET /players`

| | |
|---|---|
| **Purpose** | Player profiles |
| **Parameters** | `competitionId` + `seasonYear` + `limit` + `offset` (every registered player of the season, no team filter — `teamId`/`clubId` are ignored), **or** `playerIds` (comma list) |
| **Sample** | [`samples/players.json`](samples/players.json) (first 50 of UCL 2027) · [`samples/players.by-id.json`](samples/players.by-id.json) (`?playerIds=250076574`) captured 2026-09-13 |
| **Last verified** | 2026-09-13 |

`{ id, internationalName, clubId, clubJerseyNumber, clubShirtName, nationalTeamId,
nationalJerseyNumber, nationalShirtName, fieldPosition (GOALKEEPER/DEFENDER/MIDFIELDER/
FORWARD), detailedFieldPosition (STRIKER/WINGER/CENTRE_BACK/…/UNKNOWN), nationalFieldPosition,
countryCode, countryOfBirthCode, birthDate, age (string), height, weight, gender, imageUrl,
translations { name, firstName, lastName, shortName, fieldPosition, countryName } }`.
**There is no squad endpoint** — a team's current squad is only visible through line-ups.

### Matches (`match.uefa.com/v5`)

#### `GET /matches`

| | |
|---|---|
| **Purpose** | Match list — the fixtures/results page, the "matches on a date" feed, and multi-id refresh |
| **Parameters** | Filters (any combination): `competitionId`, `seasonYear`, `fromDate` / `toDate` (`YYYY-MM-DD`, inclusive; an ISO date-time → 400), `matchId` (comma list), `groupId`, `roundId`, `opponentTeamIds`. Paging: `limit` (default 10, ≤ 500), `offset` (**required** unless `matchId` is given), `order` `ASC`/`DESC` by kick-off. `dateFrom`/`dateTo` and other spellings are silently ignored |
| **Sample** | [`samples/matches.day.json`](samples/matches.day.json) (UCL, 2026-09-08 → 6 finished) · [`samples/matches.day-pre.json`](samples/matches.day-pre.json) (UEL, 2026-09-16 → 9 upcoming) · [`samples/matches.day-uecl.json`](samples/matches.day-uecl.json) (UECL, 2026-10-15 → 18) · [`samples/matches.empty.json`](samples/matches.empty.json) (`[]`) · [`samples/matches.season.json`](samples/matches.season.json) (`?competitionId=1&seasonYear=2027&limit=500&offset=0` → 234 matches from the first qualifying round; **truncated to 5**) · [`samples/matches.by-id.json`](samples/matches.by-id.json) (`?matchId=2049553,2050063`) captured 2026-09-13; [`samples/matches.day-unl.json`](samples/matches.day-unl.json) (Nations League, 2026-09-24 → 8 upcoming) · [`samples/matches.unl-team.json`](samples/matches.unl-team.json) (`?competitionId=2014&seasonYear=2027&teamId=88` → Malta's 4 group matches) captured 2026-09-24 |
| **Last verified** | 2026-09-24 |

Returns full match objects (below). Sorted by kick-off. With `competitionId` +
`fromDate`/`toDate` and no `seasonYear` the window spans seasons. The date filter
compares against `kickOffTime.date` (the **local** calendar date, see quirks).

#### `GET /matches/{matchId}`

| | |
|---|---|
| **Purpose** | **The match object** — schedule, state, score, teams, round, venue, scorers/red cards, related leg, winner |
| **Sample** | [`samples/match.pre.json`](samples/match.pre.json) (`2050063` Omonia–Celta, `UPCOMING`, `lineupStatus NOT_AVAILABLE`) · [`samples/match.final.json`](samples/match.final.json) (`2049553` Real Madrid 2–1 Inter, league phase) · [`samples/match.final-two-legs.json`](samples/match.final-two-legs.json) (`2048635` TNS–Sabah, second leg, `aggregate`, `winner.aggregate`) · [`samples/match.final-extra-time.json`](samples/match.final-extra-time.json) (`2047770` Juventus–Galatasaray 2025/26 play-off: `regular` 3–0, `total` 3–2, `winner.aggregate.reason WIN_ON_EXTRA_TIME`) · [`samples/match.final-penalties.json`](samples/match.final-penalties.json) (`2047742` 2026 final Paris–Arsenal 1–1, `penalty` 4–3, `WIN_ON_PENALTIES`, `playerEvents.penaltyScorers`) · [`samples/match.not-found.json`](samples/match.not-found.json) captured 2026-09-13; [`samples/match.unl-pre.json`](samples/match.unl-pre.json) (`2048009` Andorra–Malta, Nations League League D group D1, `UPCOMING`) · [`samples/match.unl-final-penalties.json`](samples/match.unl-final-penalties.json) (`2044949` 2025 final Portugal 2–2 Spain, `penalty` 5–3) captured 2026-09-24 |
| **Last verified** | 2026-09-24 |

**Shape** (top level): `id`, `seasonYear`, `competition { id, code, … }`,
`competitionPhase` (`QUALIFYING` / `TOURNAMENT`), `round { id, metaData { name, type },
mode, modeDetail, phase, status (UPCOMING/CURRENT/FINISHED), active, dateFrom/dateTo,
teams[], groupCount, substitutionCount, … }`, `matchday { id, name, longName,
sequenceNumber, type (MATCHDAY / FIRST_LEG / SECOND_LEG / SINGLE…), dateFrom/dateTo }`,
`group { id, metaData.groupName, teams[], teamsQualifiedNumber, type }` (league phase /
group stage only), `type` (`GROUP_STAGE` / `FIRST_LEG` / `SECOND_LEG` / `SINGLE`), `leg
{ number, dateTimeFrom, dateTimeTo }` (two-leg ties), `sessionNumber`,
`kickOffTime { date, dateTime (UTC `Z`), utcOffsetInHours }`, `status`, `lineupStatus`
(`NOT_AVAILABLE` / `TACTICAL_AVAILABLE` / `AVAILABLE`), `homeTeam` / `awayTeam` (team
object), `stadium { id, translations.name/officialName, city, countryCode, capacity,
geolocation, pitch, images }`, `matchAttendance`, `condition { weatherCondition,
temperature, pitchCondition }`, `referees[] { person { id, countryCode }, role
(REFEREE, ASSISTANT_REFEREE_ONE/TWO, FOURTH_OFFICIAL, VIDEO_ASSISTANT_REFEREE,
ASSISTANT_VIDEO_ASSISTANT_REFEREE, REFEREE_OBSERVER, UEFA_DELEGATE), images }`,
`behindClosedDoors`, `fullTimeAt` (UTC, once finished), `score { regular, total,
[penalty], [aggregate] }` each `{ home, away }`, `winner { match { reason, team },
[aggregate { reason, team }] }` with `reason` ∈ `WIN_REGULAR`, `WIN_ON_EXTRA_TIME`,
`WIN_ON_PENALTIES`, `WIN_ON_AGGREGATE`, `WIN_ON_AWAY_GOAL`, `WIN_BY_FORFEIT`, `DRAW`,
`playerEvents { scorers[], redCards[], penaltyScorers[], penaltiesMissed[] }`,
`playerOfTheMatch { player, … }`, `relatedMatches[]` (the other leg, same shape minus
its own `relatedMatches`), and while live `phase` and `minute { normal, injury }` (see
[Game states](#game-states)).

`playerEvents.scorers[]`: `{ id (= event id), goalType (SCORED / PENALTY / OWN), phase,
time { minute, second, [injuryMinute] }, teamId, player {…} }`. For an **own goal**
`teamId` is the scorer's own team; the goal counts for the opponent.
`penaltyScorers[]` / `penaltiesMissed[]` are the shoot-out kicks (`penaltyType`
SCORED / MISSED, `phase: PENALTY`, no `time`). Scores: `regular` = after 90 minutes,
`total` = after extra time (equal to `regular` when there was none — the only hint of
extra time in a list is `total ≠ regular`, `winner.*.reason`, or the events),
`penalty` = shoot-out only, `aggregate` = over both legs (present on both legs once the
tie has started; `winner.aggregate` appears on the second leg).

#### `GET /matches/{matchId}/events`

| | |
|---|---|
| **Purpose** | The match timeline |
| **Parameters** | `filter` (**required**; `ALL`, `MAIN`, `LINEUP`, `GOALS`, `CARDS`, `PHASES` verified — anything else → 404 `"[X] is not valid for filter"`), `order` (`ASC`/`DESC`), `limit`, `offset` |
| **Sample** | [`samples/match-events.main.final.json`](samples/match-events.main.final.json) (`filter=MAIN`, Real Madrid–Inter: 38 events) · [`samples/match-events.all.final.json`](samples/match-events.all.final.json) (`ALL`, 152 events, **truncated to 40**) · [`samples/match-events.lineup.final.json`](samples/match-events.lineup.final.json) (`LINEUP`: goals, cards, subs) · [`samples/match-events.phases.final.json`](samples/match-events.phases.final.json) (`PHASES`) · [`samples/match-events.main.final-penalties.json`](samples/match-events.main.final-penalties.json) (final with extra time + shoot-out) · [`samples/match-events.main.own-goal.json`](samples/match-events.main.own-goal.json) (`subType: OWN`, `PENALTY`) · [`samples/match-events.phases.final-extra-time.json`](samples/match-events.phases.final-extra-time.json) · [`samples/match-events.main.pre.json`](samples/match-events.main.pre.json) (`[]`) captured 2026-09-13 · [`samples/match-events.main.unl-final-penalties.json`](samples/match-events.main.unl-final-penalties.json) (Nations League 2025 final: 55 events through extra time and the shoot-out) captured 2026-09-24 |
| **Last verified** | 2026-09-24 |

Event: `{ id (UUID), matchId, type, [subType], [detail], phase, time { minute, second,
[injuryMinute] } (absent in the shoot-out and on FULL_TIME), timestamp (wall clock, UTC,
ms), primaryActor { type: PLAYER, person {…}, team {…} }, [secondaryActor], [bodyPart
(LEFT/RIGHT/HEAD)], [fieldPosition { coordinate { x, y }, distance }] + fspFieldPosition,
[totalScore], [injuryTimeMinutesAdded], [varInfo], [freeText], [relatedEventId] }`.

Types by filter (observed): **`MAIN`** = `START_PHASE`, `END_PHASE`, `FULL_TIME`,
`INJURY_TIME`, `GOAL`, `YELLOW_CARD`, `RED_CARD`, `SUBSTITUTION`, `PENALTY` (shoot-out
kick), `CORNER`; **`ALL`** adds `CHANGE_PHASE`, `ASSIST`, `SHOT_ON_GOAL`, `SHOT_WIDE`,
`SHOT_BLOCKED`, `SAVE`, `FOUL`, `FREE_KICK`, `OFFSIDE`, `VAR`, `ADDITIONAL_INFORMATION`
(and presumably more). Facts the core relies on:

- `time.minute` is the conventional **minute in progress** (a goal 13 min 30 s after
  kick-off has `minute: 14, second: 31`), `second` the second within that minute,
  `injuryMinute` the stoppage minute (`45 +2`). `phase` says which period.
- `START_PHASE` / `END_PHASE` carry the whistle's UTC `timestamp` per phase
  (`FIRST_HALF`, `SECOND_HALF`, `EXTRA_TIME_FIRST_HALF`, `EXTRA_TIME_SECOND_HALF`,
  `PENALTY`) — a second-precision clock source. `CHANGE_PHASE` (only in `ALL`) is the
  feed switching phase and fires **up to 15 min before** the real `START_PHASE`; do not
  use it as kick-off. `INJURY_TIME` at `minute 45/90/105/120` says
  `injuryTimeMinutesAdded`. `FULL_TIME` (no `time`) closes the match.
- `GOAL`: `primaryActor` scorer, `secondaryActor` the **beaten goalkeeper** (not the
  assist — assists are separate `ASSIST` events in `ALL` only), `subType` absent /
  `PENALTY` / `OWN` (own goal: `primaryActor.team` is the scorer's own team),
  `bodyPart`, coordinates. `totalScore` is **the final total score, not a running score**
  (every goal of a finished match shows the same value) — compute the running score.
- `SUBSTITUTION`: `primaryActor` = player **off**, `secondaryActor` = player **on**,
  `detail` e.g. `HALF_TIME`.
- `PENALTY` (shoot-out): `subType` `SCORED` / `MISSED`, `detail` `WIDE` / `SAVED` on
  misses, `secondaryActor` the goalkeeper, `phase: PENALTY`, no `time`.
- Yellow/red cards: no reason field observed. Second yellows not yet observed
  (expected `RED_CARD` with a `subType`).

#### `GET /matches/{matchId}/lineups`

| | |
|---|---|
| **Purpose** | Starting XI, bench, coaches, kit |
| **Sample** | [`samples/match-lineups.final.json`](samples/match-lineups.final.json) · [`samples/match-lineups.pre.json`](samples/match-lineups.pre.json) (`{"lineupStatus":"NOT_AVAILABLE","matchId":"2050063"}`) captured 2026-09-13 · [`samples/match-lineups.unl-final.json`](samples/match-lineups.unl-final.json) (Nations League final, 26 per side) captured 2026-09-24 |
| **Last verified** | 2026-09-24 |

`{ matchId, lineupStatus, homeTeam, awayTeam }`, each side `{ team, field[11],
bench[], coaches[], kitImageUrl, shirtColor }`. Field/bench entries: `{ player {…},
jerseyNumber, type (PLAYER / GOALKEEPER / CAPTAIN), isBooked, isLateUpdate,
fieldCoordinate { x, y } (0–1000 grid, field only), fspFieldCoordinate }`. No formation
string — derive it from `fieldCoordinate.y` rows if needed. Coaches: `{ person { id,
countryCode, translations { name, firstName, lastName } }, role: COACH, imageUrl }`.

#### `GET /livescore`

| | |
|---|---|
| **Purpose** | Cheap "what changed" list: every match (all competitions) from 1 h before kick-off to 1 h after the end |
| **Sample** | [`samples/livescore.json`](samples/livescore.json) (two upcoming Regions' Cup matches) captured 2026-09-13 |
| **Last verified** | 2026-09-13 |

`[ { id, status, lineupStatus, hash, [score, minute, phase, winner, fullTimeAt,
matchAttendance, translations.phaseName] } ]` — `hash` changes whenever any exposed
property changes. `Cache-Control: max-age=4, s-maxage=2`. Live shape pending.

### Statistics (`matchstats.uefa.com/v1`)

#### `GET /team-statistics/{matchId}`

| | |
|---|---|
| **Purpose** | Per-team match statistics |
| **Sample** | [`samples/team-statistics.final.json`](samples/team-statistics.final.json) (429 statistics per team, 516 KB) · [`samples/team-statistics.pre.json`](samples/team-statistics.pre.json) (`[]`) captured 2026-09-13 |
| **Last verified** | 2026-09-13 |

`[ { teamId, idProvider, statistics: [ { name, value (string), translations.name } ] } ]`.
Names the core reads: `goals`, `attempts`, `attempts_on_target`, `attempts_off_target`,
`attempts_blocked`, `ball_possession` (percent), `corners`, `fouls_committed`,
`offsides`, `yellow_cards`, `red_cards`, `saves`, `passes_attempted`,
`passes_completed`, `passes_accuracy`, `distance_covered` (km, decimal). Also
`shot_in_goal_gate_*`, `dribbling*`, `aerialduel`, `cross_attempted`, `recovered_ball`,
`tackles*`, `distance_covered_*` splits, `top_speed`, … ~60 KB gzipped, so the core
fetches it at most once a minute.

`GET /player-statistics/{matchId}` exists (same shape per `playerId`, **5.3 MB**) and is
documented here only so nobody polls it.

### Standings (`standings.uefa.com/v1`)

#### `GET /standings`

| | |
|---|---|
| **Purpose** | Tables of a competition season — one entry per group (the club league phase is a single 36-team "League" group; the Nations League has 14, see [Nations League](#nations-league)) |
| **Parameters** | `competitionId` + `seasonYear` (+ optional `phase` `TOURNAMENT` / `QUALIFYING`, `groupIds`, `roundId`) |
| **Sample** | [`samples/standings.json`](samples/standings.json) (UCL 2027 after MD1) · [`samples/standings.uel.json`](samples/standings.uel.json) (UEL 2027 before MD1: all zeros, ranked by coefficient) · [`samples/standings.previous-season.json`](samples/standings.previous-season.json) (UCL 2026, final table) captured 2026-09-13 · [`samples/standings.unl.json`](samples/standings.unl.json) (Nations League 2027, 14 groups) captured 2026-09-24 |
| **Last verified** | 2026-09-24 |

`[ { group { id, metaData.groupName, roundId, teams[], teamsQualifiedNumber,
league? { id, metaData { leagueName, leagueShortName }, order } }, round
{…}, status (OFFICIAL), qualificationLabels[], items: [ { rank, team, teamId, played,
won, drawn, lost, points, totalPoints, goalsFor, goalsAgainst, goalDifference,
wonHome/wonAway/…, goalsForHome/goalsForAway, rankingCoefficient, isLive, isTied,
isBanned, isOverridden, tieBreakerRuleId, opponentsPoints, opponentsGoalDifference,
groupFairPlayCoefficient } ] } ]`. `Cache-Control: max-age=20` during the season
(`isLive` suggests in-play updates — pending verification).

## Game states

Observed so far (`status` on the match object):

| `status` | Meaning | Also present |
|---|---|---|
| `UPCOMING` | scheduled; `lineupStatus` flips from `NOT_AVAILABLE` to `TACTICAL_AVAILABLE` / `AVAILABLE` about an hour before kick-off | no `score`, `minute`, `phase` |
| `LIVE` | in play (from the community typings; **not yet observed** — also `CURRENT`?) | `phase` ∈ `FIRST_HALF`, `HALF_TIME_BREAK`, `SECOND_HALF`, `EXTRA_TIME_FIRST_HALF`, `EXTRA_TIME_SECOND_HALF`, `PENALTY` (+ an extra-time break value?), `minute { normal, injury }`, `score`, `translations.phaseName` |
| `FINISHED` | full time | `fullTimeAt`, `winner`, `score.total` / `penalty` |
| `ABANDONED`, `CANCELED` | from the typings, not observed | |

Pending: the live shape of `matches/{id}` and `livescore` (how `minute` counts through
stoppage time and extra time, whether `phase` flips at `CHANGE_PHASE` or `START_PHASE`,
what the half-time and extra-time breaks look like, edge TTLs while live). A capture of
the Regions' Cup matches on 2026-09-13 and of UEL MD1 (2026-09-16/17) is scheduled;
this section will be rewritten from it.

## Nations League

Competition **`2014`** (`code UNL`, `teamCategory NATIONAL`, `sex MALE`). It rides on the
same four services with the same shapes; what differs is worth knowing before wiring it.

**The seasons are the odd end years, and only those.** The competition is biennial:
`2025` is 2024/25, `2027` is 2026/27. Every even `seasonYear` answers `[]` on `/matches`
and **404** on `/standings` (verified 2026-09-24 for 2022, 2024, 2026, 2028). An edition
also outlives its name: the 2025 relegation play-offs were played **2026-03-26/31** and
are filed under `seasonYear=2025`, so "the current season" cannot be the plain
July-rolls-over rule the club competitions use:

| Date | Plain rule | Correct |
|---|---|---|
| 2026-09-24 (MD1) | 2027 | 2027 |
| 2027-08-01 | 2028 | **2027** (the edition is still running) |
| 2028-03-26 (play-offs) | 2028 | **2027** |
| 2028-09-01 (next edition) | 2029 | 2029 |

`NationsLeagueProvider` rounds an even year down to the odd one below it.

**Four tiers, fourteen groups.** `round.metaData.name` is `League phase` for all of them
(`type GROUP_STANDINGS`, `mode GROUP`), so the round does not say which table is which.
The group does: `group.metaData.groupName` is `Group A1` … `Group D2`, and, uniquely to
this competition, `group.league { id, metaData { leagueName, leagueShortName }, order }`
names the tier (`League A`…`League D`, order 1-4). `group.order` is global 1-14 and sorts
A1 → D2. Leagues A-C hold four groups of four, League D two groups of three (so those
sides play four matches, not six).

**National teams, not clubs.** `typeTeam: NATIONAL`, `teamTypeDetail:
NATIONAL_MEN_TEAM_A`, ids are low integers (`88` Malta, `43` France), and the "crest" is
the country's flag under `/imgml/flags/`. `countryCode` is the alpha-3. There is no club
crosswalk for them and nothing to join them to: `TeamRef.clubId` stays null, which is
also why a team page is single-league here. Squads are not available either (no squad
endpoint, and the crosswalk's `espn` ids are club ids), so `ROSTER` is unsupported.

**Everything else is the club shape.** Knock-out rounds (`Final`, semi-finals,
relegation play-offs `FIRST_LEG`/`SECOND_LEG`) carry the same `winner.match.reason` /
`winner.aggregate.reason` and `score.penalty`. The 2025 final
([sample](samples/match.unl-final-penalties.json)) is Portugal 2-2 Spain, 5-3 on
penalties, with `WIN_ON_PENALTIES` and a 55-event `MAIN` timeline. `/livescore` is
cross-competition, so the same change detector covers these matches.

## Caching

Every 200 carries `Cache-Control: public, max-age=N, s-maxage=M` with `N` counting down
(the browser TTL) and `M` the Akamai TTL. Observed `s-maxage`: **587 s** on finished
matches, their events and line-ups; **137 s** on upcoming matches and on match lists
of the current round; **597 s** on team statistics; **300 s** on teams / players /
competitions; **10 s** on standings; **2 s** on `livescore`. What a live match gets is
the open question — the site relies on `livescore` (`s-maxage=2`) to know *when* to
re-read the match, which suggests the match object itself keeps a non-trivial TTL.
`server-timing: cdn-cache; desc=HIT|MISS` shows whether the edge answered. `ETag` is
strong and stable across edges; `If-None-Match` → `304` not yet verified.

## Quirks & gotchas

- **`offset` is mandatory** on `/matches` (unless `matchId` is given) and `filter` on
  `/events`; forgetting either is a 404 with a Spring message, not a 400.
- **Date filters are `fromDate`/`toDate`**, plain `YYYY-MM-DD`, inclusive, compared to
  `kickOffTime.date` — which is the **local** date of the venue (`utcOffsetInHours`
  given). No UEFA match kicks off late enough for the UTC date to differ in practice.
  Any other spelling (`dateFrom`, `date`, …) is ignored and you get the whole season.
- **The Nations League is biennial** and its seasons are odd end years only; an even
  `seasonYear` is an empty list (matches) or a 404 (standings). See
  [Nations League](#nations-league).
- **Season = end year** (`2027` for 2026/27) and it begins with July's qualifiers, so a
  season-wide list has 234 matches of which 144 are the league phase. `round.phase` /
  `competitionPhase` (`QUALIFYING` vs `TOURNAMENT`) and `round.mode` (`GROUP` vs
  `KNOCK_OUT`) tell them apart.
- **Translations bloat.** Nine languages on every nested team/player/round; a match
  list of 18 upcoming games is 270 KB (45 KB gzipped). Nothing trims them.
- **`totalScore` on goal events is the final score**, not the score at that moment.
- **`CHANGE_PHASE` precedes `START_PHASE`** by up to 15 minutes (feed operator switches
  the phase, then the whistle goes). Only in `filter=ALL`.
- **`winner.match.reason` is per match, `winner.aggregate.reason` per tie**: the second
  leg of Juventus–Galatasaray shows `match.reason WIN_REGULAR` (Juventus won the match)
  and `aggregate.reason WIN_ON_EXTRA_TIME` (Galatasaray went through), while
  `regular 3–0` / `total 3–2` reveal the extra time.
- **No CORS for anyone but uefa.com**; the site itself blocks non-browser clients, the
  API hosts do not.
- **Two-leg ties** repeat the whole other match in `relatedMatches[]`; `score.aggregate`
  is on both legs.
- **No squad / roster endpoint**; `players?competitionId` is the whole season's pool
  (unpaged team filter absent). Line-ups are the only per-team squad view.
- **`players[].age` and `clubJerseyNumber` are strings**, `jerseyNumber` in line-ups an
  int.

## Out of scope

- `www.uefa.com` pages and its JS bundles (Akamai Bot Manager resets non-browser
  connections; nothing there is needed — the API hosts are open).
- `player-statistics/{matchId}` (5.3 MB per call).
- `fsp-*.uefa.com` / "Football Services Platform" hosts referenced by
  `fspFieldPosition` — not needed, not probed.
- Images (`img.uefa.com`) — link only, never store.

## Core model mapping

Provider: `org.openscore.providers.uefa.UefaProvider` (shared) with
`ChampionsLeagueProvider` (`ucl`, competition 1), `EuropaLeagueProvider` (`uel`, 14),
`ConferenceLeagueProvider` (`uecl`, 2019) and `NationsLeagueProvider` (`unl`, 2014).

| Core concept | Source | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | constants | `competitionId`; `seasonYear` (end year) | `seasonId` = `seasonYear`; current season = year + 1 from July, rounded down to the odd year for the biennial Nations League |
| Game (id, teams, start time, venue) | `/matches`, `/matches/{id}` | `id`, `kickOffTime.dateTime` (UTC), `homeTeam`/`awayTeam`, `stadium.translations.name.EN` | `Game.competition` = `round.metaData.name` (qualifying rounds, league phase, knock-outs share one provider); `stage` = OTHER for qualifying, REGULAR for the league/group phase, PLAYOFF for knock-outs |
| GameState | match | `status`, `lineupStatus`, `phase` | `UPCOMING` → SCHEDULED (PRE_GAME once line-ups are out); `LIVE` → LIVE / INTERMISSION by `phase`; `FINISHED` → FINAL; `ABANDONED` → SUSPENDED; `CANCELED` → CANCELLED |
| Score | match | `score.total` (`regular` when `total` absent); shoot-out in `score.penalty` | `GameEnding`: SHOOTOUT if `penalty`, OVERTIME if `total ≠ regular` or an extra-time phase appears in the events, else REGULATION |
| Clock / period | events `START_PHASE`/`END_PHASE` `timestamp`; list fallback `minute { normal, injury }` + `phase` | second-precision elapsed per phase, `running` = no `END_PHASE` yet | Requires the events call; a `gamesOn` scoreboard gets the minute-level clock only |
| Period scores | events `GOAL` by `phase` (list: `playerEvents.scorers[].phase`) | own goals credited to the opponent | |
| GameEvent | `/events?filter=MAIN` | `GOAL` (+`subType` PENALTY/OWN), `YELLOW_CARD`, `RED_CARD`, `SUBSTITUTION` (off→on), `PENALTY` (shoot-out), `START_PHASE`/`END_PHASE`/`FULL_TIME`, `INJURY_TIME`, `CORNER` → OTHER | No assists in MAIN (only in ALL, 700 KB) → `FootballGoalDetails.assist` null; card reasons absent |
| Lineups | `/lineups` | `field[]` starters, `bench[]`, `type CAPTAIN`, `coaches[]` | No formation string |
| Team stats | `team-statistics/{id}` | 16 named statistics → `possession`, `shots`, `shotsOnTarget`, `shotsBlocked`, `corners`, `fouls`, `offsides`, `yellowCards`, `redCards`, `saves`, `passes`, `passesCompleted`, `passAccuracy`, `distanceKm` | 60 KB gzipped → fetched with a 60 s cache |
| Team | `/teams?teamIds=` | `internationalName`, `teamCode`, `countryCode`, `mediumLogoUrl` | `Team.country` is the alpha-3 code as given; a Nations League side is a country, so `clubId` is null and the logo is its flag |
| Player | `/players?playerIds=` | names, `birthDate`, `countryCode`, `height`, `weight`, `clubId`, `fieldPosition` | `ROSTER` unsupported (no squad endpoint); for the club competitions it comes from ESPN through the crosswalk, for the Nations League not at all |
| Standings | `/standings` | one `StandingsGroup` per entry (`group.metaData.groupName`), `items[]` | `extra`: `coefficient`, `live`, `tied`. Label = the group name, prefixed with `group.league.metaData.leagueName` where there is one (`League A · Group A1`), else the round name |
| Live | polling `/livescore` hash → `/matches/{id}` + events | | No push transport |

## Changelog

| Date | Change |
|---|---|
| 2026-09-13 | Initial mapping: 4 hosts, 10 endpoints, 36 samples (pre / final / extra time / shoot-out / own goal / two legs, all three competitions). Live states pending |
| 2026-09-24 | Nations League (competition **2014**) added: 9 samples, 10 health checks, `NationsLeagueProvider`. Corrected the competition id in [Identifiers](#identifiers) - `2016`, which the first mapping named, is a girls' futsal tournament. Documented the biennial odd-year seasons, the four tiers / fourteen groups (`group.league`) and national sides |
