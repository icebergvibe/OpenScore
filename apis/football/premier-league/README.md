# Premier League API (Pulselive SDP)

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | England |
| **Official site** | https://www.premierleague.com |
| **Base URL** | `https://sdp-prem-prod.premier-league-prod.pulselive.com/api/` (paths are versioned per endpoint: `v1/…`, `v2/…`, `v3/…`, `v5/…`) |
| **Auth** | None — public endpoints used by premierleague.com |
| **Format** | JSON (UTF-8). Errors are JSON (`{"title", "status", "detail"}`) |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *` (sent when an `Origin` header is present; `Vary: Origin`). `OPTIONS` preflight returns `200`. |
| **WAF / UA requirement** | None. CloudFront in front; requests with no `User-Agent`, no `Origin` and no `Referer` succeed. |
| **Last full verification** | 2026-09-11 (live states 2026-09-25 from the 2026-09-13 capture) |
| **Status** | ✅ verified across every state a league match reaches: `PreMatch`, `FirstHalf`, `HalfTime`, `SecondHalf`, `FullTime` |

## Overview

premierleague.com was rebuilt in 2025 on Pulselive's "SDP" (sports data platform).
The page exposes the API base itself (`window.SDP_API`), the current season
(`ACTIVE_PL_SEASON_ID = '2026'`) and the current matchweek
(`ACTIVE_PL_MATCHWEEK_ID`). The data underneath is Opta's: match, team and player
IDs are Opta IDs, and the `momentum` endpoint is a lightly wrapped Opta feed. No
key, no cookie and no header of any kind is required.

It is a *thin* API compared with MLB or the NHL: match objects are small, there is
no unified "everything" feed, and match detail is spread over ~8 endpoints
(match, lineups, events, timeline, stats, commentary, officials, momentum). It
also covers other competitions the PL site shows (FA Cup, League Cup, UEFA
competitions, PL2, U18) via the competition id, but only competition **8** (Premier
League) is verified here.

The older API the previous site used, `https://footballapi.pulselive.com/football/…`,
is still live and is documented briefly under [Legacy API](#legacy-api-footballapipulselivecom)
because it returns a running clock in seconds, where the new one - now observed live -
gives whole cumulative minutes only.

All samples in [`samples/`](samples/) were captured on **2026-09-11** (between
matchweeks 3 and 4 of 2026/27), except the live states, which come from a 2026-09-13
recording. See [`samples/_meta.md`](samples/_meta.md).

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Competition | Opta competition id, string | `"8"` Premier League (`"1"` FA Cup, `"2"` League Cup, `"5"` UCL, `"6"` UEL, `"898"` PL2, `"568"` U18, `"38"` Community Shield) | Hard-coded in the site bundle; `GET /v2/competitions/{id}/details` |
| Season | 4-digit start year, **string** | `"2026"` = 2026/27 | `competitions/8/details.seasons[]` (ids are **not** years before ~2011: `"1"` = 2000/01, `"11"` = 1992/93) |
| Matchweek | int 1–38 | `4` | `standings.matchweek`, `match.matchWeek` |
| Match | Opta match id, string | `"2645215"` | Matchweek / match list endpoints, field `matchId` |
| Team | Opta team id, string (stable across seasons) | `"3"` Arsenal, `"8"` Chelsea, `"43"` Man City | `GET /v1/competitions/8/seasons/2026/teams` |
| Player | Opta player id, string (stable for a career) | `"154561"` David Raya | Lineups, events, squad |
| Manager / official | string ids | `"51018"` Mikel Arteta | Lineups, officials |
| Opta UUIDs | 25-char strings like `434k88c48bhwdqrkp8muqp1jo` | — | Only inside `momentum` and `player/{id}/career`; every object there also carries the numeric `op…Id` |

**Team abbreviations (2026/27):** ARS AVL BOU BRE BHA CHE COV CRY EVE FUL HUL IPS
LEE LIV MCI MUN NEW NFO SUN TOT. `shortName` is the display name (`"Villa"`,
`"Spurs"`, `"Forest"`).

**Cross-reference to the legacy API:** legacy fixtures carry `altIds.opta: "g2645215"`
(`g` + match id) and teams `altIds.opta: "t3"`.

## Discovery path

1. **Fixtures for a matchweek:** `GET /v1/competitions/8/seasons/2026/matchweeks/{n}/matches`
   (`n` from the standings' `matchweek` + 1, or the site's `ACTIVE_PL_MATCHWEEK_ID`).
   Or by date: `GET /v2/matches?competition=8&season=2026&kickoff>2026-09-12T00:00:00&kickoff<2026-09-12T23:59:59`.
   Each match has `matchId`, `period`, `kickoff` (local), teams with scores.
2. **A specific match:** `GET /v2/matches/{id}` — header with `period`, `clock`,
   scores, half-time scores, red cards.
3. **Events:** `GET /v1/matches/{id}/timeline` (ordered, with UTC timestamps) or
   `GET /v1/matches/{id}/events` (goals/cards/subs grouped per team).
4. **Lineups:** `GET /v3/matches/{id}/lineups` — XI, bench, formation grid, managers.
5. **Standings:** `GET /v5/competitions/8/seasons/2026/standings`. **Squads:**
   `GET /v2/competitions/8/seasons/2026/teams/{teamId}/squad`.
   **Player:** `GET /v1/players/{id}/basic` or `…/playerinfo/{id}`.

Recommended poll interval for live matches: **10 s** for `match`, `events`,
`timeline`, `commentary` (`max-age=5`) and `lineups` (`max-age=10`); **30 s** for
the matchweek list and `stats` (`max-age=30`). Do not poll matches whose `period`
is `FullTime`. Note `stale-while-revalidate=120–600` on everything — CloudFront may
serve stale content for up to that long after `max-age` expires.

## Endpoints

### `GET /v1/competitions/{comp}/seasons/{season}/matchweeks/{n}/matches`

| | |
|---|---|
| **Purpose** | All matches of one matchweek. The natural "scoreboard" call. |
| **Parameters** | path: `comp` `8`, `season` `2026`, `n` 1–38; query: `_limit` (default 10 — a matchweek has 10, so fine) |
| **Samples** | [`matchweek-matches.pre.json`](samples/matchweek-matches.pre.json) (MW 4, all `PreMatch`) · [`matchweek-matches.final.json`](samples/matchweek-matches.final.json) (MW 3, all `FullTime`) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=30, stale-while-revalidate=600` |

**Response shape**

```
pagination { _limit, _prev, _next }     – cursor strings, null when no more
data[]
  matchId, competition "Premier League", ground "Emirates Stadium, London"
  kickoff "2026-09-06 16:30:00", kickoffTimezone "BST"|"GMT"   – LOCAL time, see quirks
  period "PreMatch"|"FullTime"|…
  homeTeam / awayTeam { id, name, shortName, score?, halfTimeScore?, redCards? }   – no abbr here
  clock "95"          – minutes, only once started
  resultType "NormalResult", attendance   – only when finished
```

An unknown matchweek returns `200` with `data: []`. Unlike `/v2/matches`, the team
objects here lack `abbr`.

### `GET /v2/matches` &nbsp;·&nbsp; `GET /v2/matches/{id}`

| | |
|---|---|
| **Purpose** | Filterable match list across the season (or across competitions), and a single match header. |
| **Parameters** | `competition=8`, `season=2026`, `matchweek=`, `team=` (one id), `period=` (e.g. `PreMatch`), `kickoff>…` / `kickoff<…` (ISO local datetime, URL-encode `>`/`<`), `_sort=kickoff:desc`, `_limit` (default 10, 100 works), `_next`/`_prev` (cursor from `pagination`) |
| **Samples** | [`matches.json`](samples/matches.json) (first 5) · [`matches-daterange.json`](samples/matches-daterange.json) (2026-09-12/13) · [`matches-team.json`](samples/matches-team.json) (Arsenal) · [`matches-period.json`](samples/matches-period.json) · [`match.final.json`](samples/match.final.json) · [`match.pre.json`](samples/match.pre.json) · [`match.pre-matchday.json`](samples/match.pre-matchday.json) · [`match.live.json`](samples/match.live.json) · [`match.halftime.json`](samples/match.halftime.json) · [`match.live-second-half.json`](samples/match.live-second-half.json) · [`match.404.json`](samples/match.404.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=5, stale-while-revalidate=120` |

**Response shape** (single match; list items are the same)

```
matchId, competitionId "8", competition, phase "1", matchWeek 3
seasonId "2026", seasonInfo { name "Season 2026/2027", id }
kickoff "2026-09-06 16:30:00", kickoffTimezone "BST", kickoffTimezoneString "Europe/London"
period, clock "95", resultType "NormalResult", attendance, ground
homeTeam / awayTeam { id, name, shortName, abbr, score, halfTimeScore, redCards }
```

`score`, `halfTimeScore`, `redCards` and `clock` are absent the day before a match but
**appear, all zero, about an hour before kick-off while `period` is still `PreMatch`**
(11:52:52Z for a 13:00Z kick-off, 2026-09-13). Presence of a score is therefore not a
sign that the match has started: only `period` says that. `resultType` and `attendance`
really are absent until `FullTime`. Without `competition=` the list spans **every competition
in the platform** (FA Cup, EFL, women's, youth …), and those rows can lack
`kickoff` and team names — always pass `competition`. Unknown id → `404`
`{"title": "The service encountered an error", "status": 404, "detail": "Could not find requested entity"}`.

Ordering is by kickoff ascending; `_sort=kickoff:desc` reverses. Unknown query
parameters are silently ignored (`date=`, `from=`, `teams=` do nothing) — **`team=` (one
id) works**: `?competition=8&season=2026&team=3&_limit=100` is a club's whole season in
one page (38 rows, verified 2026-09-16).

### `GET /v1/matches/{id}/timeline`

| | |
|---|---|
| **Purpose** | Ordered event stream of the match with UTC timestamps. The best single events source. |
| **Samples** | [`timeline.final.json`](samples/timeline.final.json) (33 events) · [`timeline.pre.json`](samples/timeline.pre.json) (`[]`) · [`timeline.live.json`](samples/timeline.live.json) (3 events, 35') · [`timeline.halftime.json`](samples/timeline.halftime.json) (4 events) · [`timeline.live-second-half.json`](samples/timeline.live-second-half.json) (28 events, with a penalty and a straight red) |
| **Last verified** | 2026-09-11 (live 2026-09-13) |
| **Cache** | `max-age=5` |

Array of `{ periodId "1"|"2", minutes, seconds, eventType, tag, teamId?, playerId?,
isoTimestampUtc, timestampUtc, timestamp (local) }`. `minutes`/`seconds` are the
match clock at the event (`minutes: 1, seconds: 17` = 1:17; 45+ is expressed as
`minutes ≥ 45` within `periodId "1"`).

**Observed `eventType`** *(four matches; may be incomplete)*: `LINEUP_CONFIRMED`,
`FIRST_HALF_START`, `FIRST_HALF_END`, `SECOND_HALF_START`, `SECOND_HALF_END`, `GOAL`,
**`PENALTY_SCORED`**, `OWN_GOAL`, `GOAL_DISALLOWED`, `VAR_GOAL_DISALLOWED`,
`YELLOW_CARD`, **`RED_CARD`**, `PLAYER_SUBSTITUTE_OFF`, `PLAYER_SUBSTITUTE_ON`.
`tag` groups them: `lineup`, `phase`, `goals`, `penalty`, `disallowed goals`,
`booking`, `substitutes`.

**A converted penalty is `PENALTY_SCORED`, not `PENALTY_GOAL`** (Groß, 69:12,
2026-09-13; `tag: "penalty"`). The name matters: it is a goal, and a consumer that
does not recognise it loses both the row and every running score after it. `events`
files the same goal under the scoring side with `goalType: "Penalty"`.

Still unseen: `SECOND_YELLOW`, a missed or saved penalty, and the extra-time /
shoot-out phases (not applicable in the league).

`periodId` is `"1"`/`"2"` for the halves and **`"16"` for `LINEUP_CONFIRMED`**, which is
stamped `minutes: 0` about an hour before kick-off.

### `GET /v1/matches/{id}/events`

| | |
|---|---|
| **Purpose** | Goals, cards and substitutions grouped per team — the scoring-summary view. |
| **Samples** | [`events.final.json`](samples/events.final.json) · [`events.pre.json`](samples/events.pre.json) · [`events.live-second-half.json`](samples/events.live-second-half.json) (a penalty and a straight red) |
| **Last verified** | 2026-09-11 (live 2026-09-13) |
| **Cache** | `max-age=5` |

```
homeTeam / awayTeam
  id, name, shortName
  goals[] { goalType "Goal"|"Penalty"|"Own", period "FirstHalf"|"SecondHalf", time "25", playerId, assistPlayerId?, timestamp "20260906T165505+0100" }
  cards[] { type "Yellow"|"StraightRed", period, time, playerId, timestamp }
  subs[]  { period, time, playerOnId, playerOffId, timestamp }
```

`goalType` other values (not yet observed): `Penalty`, `Own`. `card.type`: `Red`,
`SecondYellow` expected. Timestamps here are compact local-with-offset strings, not
ISO — prefer the timeline's `isoTimestampUtc`.

### `GET /v3/matches/{id}/lineups`

| | |
|---|---|
| **Purpose** | Starting XI, substitutes, formation grid, managers. |
| **Samples** | [`lineups.final.json`](samples/lineups.final.json) · [`lineups.pre.json`](samples/lineups.pre.json) (empty until ~1 h before kick-off) · [`lineups.live.json`](samples/lineups.live.json) |
| **Last verified** | 2026-09-11 (live 2026-09-13) |
| **Cache** | `max-age=10` |

```
home_team / away_team
  teamId
  players[] { id, firstName, lastName, knownName?, shirtNum "1", isCaptain,
              position "Goalkeeper"|"Defender"|"Midfielder"|"Forward"|"Substitute", subPosition? (for substitutes) }
  formation { formation "4-2-3-1", lineup [[gk],[def…],[mid…],[att…]…] (player ids per line), subs [ids], teamId }
  managers[] { id, firstName, lastName, type "Manager" }
```

Note the snake_case keys (`home_team`), unique to this endpoint. Players who came on
keep `position: "Substitute"`; combine with `events.subs` to know who is on the pitch.

**The document is written once and never changes again.** Over 364 polls of
2026-09-13 it was published at 11:53Z and was byte-identical for the rest of the match,
through nine substitutions and a sending-off, so `lineups.live.json` above is the
pre-match body. Treat it as static from publication (`max-age=10` notwithstanding) and
take who is on the pitch from the timeline.

### `GET /v3/matches/{id}/stats`

Array of two `{ side "Home"|"Away", teamId, stats{…} }` with ~180 Opta team stats
(`goals`, `possessionPercentage`, `totalScoringAtt`, `ontargetScoringAtt`,
`expectedGoals`, `expectedGoalsOnTarget`, `totalPass`, `accuratePass`, `cornerTaken`,
`fkFoulLost`, `yellowCard`, `totalTackle`, `saves` …). Samples:
[`stats.final.json`](samples/stats.final.json) ·
[`stats.live.json`](samples/stats.live.json). Returns `[]` before kick-off and fills
within about four minutes of it, then moves on almost every poll. `max-age=30`. Values
are floats.

**The two rows are not in a fixed order** - the 2026-09-13 live bodies put `Away`
first. Pick the side by `teamId` or `side`, never by index. The count differs per side
too (178 keys away, 172 home in the same body), so a key missing on one side is normal.

### `GET /v1/matches/{id}/momentum`

| | |
|---|---|
| **Purpose** | Opta match feed: period start/end times, injury time, scores, goals/cards/subs/VAR with wall-clock timestamps, full lineups with formation places, officials, plus per-minute "momentum" predictions. |
| **Sample** | [`momentum.final.json`](samples/momentum.final.json) (77 KB) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=30` |

```
matchInfo { id (Opta UUID), opId "2645215", date "2026-09-06Z", time "15:30:00Z" (UTC), localDate, localTime,
            week, numberOfPeriods 2, periodLength 45, var "1", lastUpdated, description,
            competition{opId "8", name, knownName, competitionCode "EPL"}, tournamentCalendar{name "2026/2027", startDate, endDate},
            stage{name "Regular Season"}, contestant[] {opId, name, shortName, officialName, code "ARS", position "home"|"away", country{}}, venue{} }
liveData
  matchDetails { periodId 14, matchStatus "Played", winner "home"|"away"|"draw", matchLengthMin, matchLengthSec,
                 period[] { id 1|2, start, end (UTC), lengthMin, lengthSec, announcedInjuryTime (seconds) },
                 scores { ht{home, away}, ft{…}, total{…} } }
  goal[], card[], substitute[], VAR[]   – each with periodId, timeMin, timeMinSec "1:17", timestamp (UTC), op*Id numeric ids, names
  lineUp[] { opContestantId, formationUsed "4231", player[] { opPlayerId, matchName, shirtNumber, position, positionSide, formationPlace } }
  matchDetailsExtra { attendance, matchOfficial[] }
  predictions[] { type "Momentum", timeMin, periodId, prediction[] {type, probability} }
```

This is the only endpoint that gives **kick-off in UTC** (`matchInfo.date` + `time`)
and precise **period start times**, which is what a running clock has to be derived
from (`now − period[current].start`). Opta `matchStatus` values (documented by Opta,
not yet observed here): `Fixture`, `Playing`, `Played`, `Cancelled`, `Postponed`,
`Suspended`, `Awarded`; `periodId` 1/2 halves, 10 half-time, 14 full-time, 16
pre-match (to verify live).

### `GET /v2/matches/{id}/commentary?lang=en`

Paginated text commentary, newest **last** (`_limit`, cursor `_next`).
[Sample](samples/commentary.final.json). Items:
`{ type, comment, time "2'", timestamp (local), team1?, team2?, player1?, player2? }`.
Observed `type`: `lineup`, `start`, `goal`, `VAR cancelled goal`, `attempt saved`,
`corner`, `free kick won`, `free kick lost`, `offside`, `start delay`, `end delay`
(Opta commentary vocabulary; more exist). `max-age=5`.

### `GET /v1/matches/{id}/officials`

`{ matchId, matchOfficials[] { official{firstName, lastName, name}, type } }` with
`type` ∈ `Referee`, `Assistant Referee#1`, `Assistant Referee#2`, `Fourth official`,
`Video Assistant Referee`, `Assistant VAR Official`. Available **before** the match.
Samples: [officials.final.json](samples/officials.final.json) · [officials.pre.json](samples/officials.pre.json). No official ids. `max-age=30`.

### `GET /v2/matches/{id}/preview`

Pre-match only: `previousMeetings[]` (last H2H matches), `headToHead{}` (all-time
totals) and `seasonPerformance` (form). [Sample](samples/preview.pre.json).

### `GET /v5/competitions/{comp}/seasons/{season}/standings`

| | |
|---|---|
| **Purpose** | League table, with home/away splits. |
| **Parameters** | Also `…/matchweeks/{n}/standings` for the table as of a matchweek ([sample](samples/standings-matchweek.json)) |
| **Sample** | [`standings.json`](samples/standings.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=30` |

```
matchweek 3, live false, deductions[]
season { name, id }, competition { code "EN_PR", name, id }
tables[] { entries[] { team{id, name, shortName, abbr},
                       overall { position, startingPosition, played, won, drawn, lost, goalsFor, goalsAgainst, points },
                       home {…}, away {…} } }
```

One table for the league (`tables[0]`, 20 entries, ordered by position). No goal
difference field — compute `goalsFor − goalsAgainst`. `live: true` presumably flags
a table that includes in-progress matches (not yet observed).
`GET /v1/competitions/8/seasons/2026/teamform` ([sample](samples/teamform.json))
returns, per team, the last results (`form[]`) and the `next` fixture.

### `GET /v1/competitions/{comp}/seasons/{season}/teams`

`{ pagination, data[] { id, name, shortName, abbr, stadium{name, city, country, capacity} } }`.
Default `_limit` is **10** — pass `_limit=30` to get all 20
([sample](samples/teams.json)). `GET /v2/teams/{id}` ([sample](samples/team.json))
returns only `{id, name, shortName, abbr}`; `GET /v2/teams-by-id?id=3,8` ([sample](samples/teams-by-id.json)) batches.
No logo/crest URL appears in the score API. Its public resource host serves
`https://resources.premierleague.com/premierleague/badges/50/t{teamId}.png`
(legacy Opta `t` prefix); the core derives that official URL for every mapped team.

### `GET /v2/competitions/{comp}/seasons/{season}/teams/{teamId}/squad`

`{ team{id, name, shortName}, players[] { id, name{first, last, display}, shirtNum (int),
position "Goalkeeper"|"Defender"|"Midfielder"|"Forward", country{isoCode, country, demonym},
countryOfBirth, dates{birth, joinedClub}, height (cm), weight (kg), preferredFoot, loan 0|1 } }`.
[Sample](samples/squad.json) (Arsenal, 26 players). `max-age=30`.

### Players

| Endpoint | Sample | Returns |
|---|---|---|
| `GET /v1/players/{id}/basic` | [player-basic.json](samples/player-basic.json) | `{id, firstName, lastName, name, position, country, currentTeam}` — the cheap lookup |
| `GET /v1/competitions/8/seasons/2026/playerinfo/{id}` | [playerinfo.json](samples/playerinfo.json) | Squad-entry shape (bio, shirt number, height/weight, foot) for that season |
| `GET /v1/players/{id}` | [player.json](samples/player.json) | **Array** of one squad-entry per competition × season the player has been registered in (44 rows for Raya) — heavy; use `basic`/`playerinfo` |
| `GET /v1/players/{id}/career` | [player-career.json](samples/player-career.json) | Opta career feed: `person[].membership[].stat[]` per competition/season with apps, goals, cards, minutes |
| `GET /v2/players-by-id?id=a,b` | [players-by-id.json](samples/players-by-id.json) | Batch name lookup |
| `GET /v2/competitions/8/seasons/2026/players/{id}/stats` | [player-season-stats.json](samples/player-season-stats.json) | ~100 season stats (`goals`, `goalAssists`, `expectedGoals`, `timePlayed`, `starts` …) |
| `GET /v1/competitions/8/seasons/2026/players/{id}/matches/all` | [player-matches.json](samples/player-matches.json) | `currentAndPreviousMatches[]`, `upcomingMatches[]` for the player's club |
| `GET /v1/competitions/8/seasons/2026/players?_limit=` | [players-season.json](samples/players-season.json) | All registered players (paginated, includes trialists) |
| `GET /v3/competitions/8/seasons/2026/players/stats/leaderboard?_sort=goals:desc&_limit=` | [leaderboard.json](samples/leaderboard.json) | Player leaderboard; **without `_sort` the order is arbitrary and `stats` may be `null`** |

### Other verified endpoints

| Endpoint | Sample | Notes |
|---|---|---|
| `GET /v2/competitions/8/details` | [competition-details.json](samples/competition-details.json) | `seasons[] {id, season "Season 2026/2027"}` — 35 seasons; the season-id ↔ label map |
| `GET /v1/competitions/8/seasons/2026/structure` | [structure.json](samples/structure.json) | `{currentPhase "1", structure[] {type "L"}}` (league; cups have groups/rounds) |
| `GET /v1/competitions/8/seasons/2026/teams/{id}/form` | [team-form.json](samples/team-form.json) | Last matches (match objects) |
| `GET /v1/competitions/8/seasons/2026/teams/{id}/nextfixture` | [team-nextfixture.json](samples/team-nextfixture.json) | One match object |
| `GET /v2/competitions/8/seasons/2026/teams/{id}/stats` | [team-season-stats.json](samples/team-season-stats.json) | Team season stats |
| `GET /v2/competitions/8/teams/{a}/headtohead/{b}` · `/v1/competitions/8/meetings/{a}/{b}` | [headtohead.json](samples/headtohead.json) · [meetings.json](samples/meetings.json) | All-time totals · list of past meetings |
| `GET /v2/competitions/8/teams/stats/leaderboard?_sort=goals:desc` | [team-leaderboard.json](samples/team-leaderboard.json) | All-time team leaderboard (`season: "1"` rows are per-season) |
| `GET /v1/competitions/8/seasons/2026/managers` | [managers.json](samples/managers.json) | Managers with club and dates |
| `GET /v1/competitions/8/seasons/2026/awards` | — | `404` this early in the season |
| `GET /v1/metadata/match/{id}` | — | `200` but `[]` |

Also in the site bundle, not yet called: `/v1/competitions/8/seasons/2026/phases/{n}/matches`,
`/v1/competitions/8/teams/stats/leaderboard/alltime`, `/v1/competitions/8/players/{id}/stats`,
`/v1/managers/{id}`, `/v1/competitions/8/managers/{id}/stats`, `/v1/competitions/8/{players|managers}/awards/leaderboard`,
`/v2/competitions/8/players/stats/leaderboard` (all-time).

### Legacy API (`footballapi.pulselive.com`)

Verified 2026-09-11 with no headers (it used to require `Origin: https://www.premierleague.com`).
`GET /football/fixtures?comps=1&pageSize=2&statuses=C&sort=desc&altIds=true`
([sample](samples/legacy-fixtures.json)) returns fixtures with `status` `U`/`L`/`C`,
`kickoff.millis` (epoch, i.e. UTC), `clock { secs, label "90+5'00" }`, `phase`,
`gameweek`, `teams[].team.altIds.opta`. Competition id is **1** here (not 8) and
season/team ids differ from SDP — use `altIds.opta` to cross-reference. Kept as a
fallback for a seconds-precision clock until the SDP live behaviour is captured; do
not build on it, it is the retired site's API and may vanish.

## Game states

`period` on match objects. Every value in the regulation sequence was observed end to
end in a 364-poll recording of Coventry City 0-5 Brighton (match `2645228`, MW 4,
2026-09-13, polled at 5 s from 05:17Z to 14:58Z); the extra-time and shoot-out values
are the ones the site's UI handles and the legacy API emits, and a league match cannot
produce them.

| `period` | Core `GameState` | `clock` | Observed |
|---|---|---|---|
| `PreMatch` | `SCHEDULED` | absent until ~1 h before kick-off, then `"0"` | ✅ |
| `FirstHalf` | `LIVE` | cumulative minutes, `"0"`…`"46"` | ✅ |
| `HalfTime` | `INTERMISSION` | **frozen** at the first half's last minute (`"47"`) | ✅ |
| `SecondHalf` | `LIVE` | restarts at `"45"` and runs to `"96"` | ✅ |
| `FullTime` | `FINAL` | frozen at the last minute (`"97"`), `resultType: "NormalResult"` | ✅ |
| `ExtraFirstHalf`, `ExtraHalfTime`, `ExtraSecondHalf`, `ShootOut` | `LIVE`/`INTERMISSION` | n/a | ✗ cups only |
| `FullTime90` | `INTERMISSION` | n/a | ✗ cups only |

The transition timings from that recording, all UTC, for a 13:00 kick-off:

| At | What happened |
|---|---|
| 11:52:52 | `period` still `PreMatch`, but `clock`, `score`, `halfTimeScore` and `redCards` appear, all zero |
| 11:56:34 | `LINEUP_CONFIRMED` in the timeline; `lineups` had filled at 11:53 |
| 13:01:00 | `FirstHalf` (the `FIRST_HALF_START` event is stamped 13:00:17) |
| 13:47:35 | `HalfTime`, `clock` `"47"` |
| 14:03:21 | `SecondHalf`, `clock` back to `"45"` |
| 14:54:45 | `FullTime` with `resultType` and `attendance` |

`resultType` was `NormalResult`; `AbandonedResult`, `Postponed` and `Awarded` are
still unobserved and may instead surface as a `period` value or by the fixture
disappearing from the matchweek - see TODO.

What changes between states (all confirmed): the zeroed `score` / `halfTimeScore` /
`redCards` / `clock` block appears about an hour before kick-off and `resultType` /
`attendance` only at `FullTime`; `lineups` fills ~1 h before and then **never changes
again** (see that endpoint); `timeline` and `events` fill; `stats` goes from `[]` to
two entries within four minutes of kick-off and then moves on almost every poll;
`halfTimeScore` **tracks the live score during the first half** and freezes at the
break, so it is only a half-time score from `HalfTime` onwards.

## Quirks & gotchas

- **Kick-off times are local, not UTC.** `kickoff` is `"YYYY-MM-DD HH:mm:ss"` in
  `kickoffTimezoneString` (`Europe/London`), with `kickoffTimezone` `BST`/`GMT` as a
  label. Convert with the IANA zone; do not assume `+01:00`. `momentum.matchInfo`
  has the UTC time, and timeline events have `isoTimestampUtc`. `events[].timestamp`
  is a compact `20260906T165505+0100`.
- **A zeroed score appears before kick-off.** About an hour before a match the header
  gains `score: 0`, `halfTimeScore: 0`, `redCards: 0` and `clock: "0"` while `period` is
  still `PreMatch` (11:52:52Z for a 13:00Z kick-off, 2026-09-13). Read the state from
  `period` alone; keying on the presence of a score shows `0-0` and a running clock for
  a match that has not started.
- **`clock` is a whole cumulative minute with no seconds, and it is not monotonic.** It
  freezes at the first half's last minute through half time (`"47"`) and then **steps
  back to `"45"`** when the second half starts, because the second half's minutes are
  cumulative from 45 rather than from where the first half stopped. A clock that only
  ever moves forwards will be wrong for one poll per match.
- **`halfTimeScore` is the live score during the first half.** It tracks `score` until
  the break and freezes there, so it is only a half-time score from `HalfTime` onwards.
  Before that, deriving a first-half period score from it is the same as using `score`.
- **A converted penalty is `PENALTY_SCORED`** in the timeline (and `goalType: "Penalty"`
  in `events`), not the `PENALTY_GOAL` the site's typings suggest. See the timeline
  endpoint.
- **`/v3/matches/{id}/stats` rows are unordered** and the two sides carry different
  numbers of keys. Select by `teamId`/`side`.
- **`/v3/matches/{id}/lineups` never changes after publication** - substitutions are
  only in the timeline.
- **Everything is a string** — ids, `clock` (`"95"`), `season`, `period`,
  `shirtNum` in lineups (but an **int** in squads). Stats are floats.
- **Default page size is 10** on every list endpoint (`pagination._limit`). Pass
  `_limit` explicitly (teams → 20, matchweeks → 10, season → 380). Paginate with the
  opaque `_next` cursor (`?…&_next=<cursor>`), not page numbers.
- **Unknown query parameters are ignored silently**, and unknown *matchweeks* return
  an empty list, not `404`. Only unknown entity ids `404`.
- **Filters use operators in the key**: `kickoff>…` and `kickoff<…` (URL-encode `>`
  as `%3E`, `<` as `%3C`). **Correction (2026-09-12, found live):** the comparison is a
  plain *string* comparison against `"YYYY-MM-DD HH:mm:ss"` — a bound written with a
  `T` (`kickoff>2026-09-12T00:00:00`) sorts *after* every kick-off of that day because
  `" "` < `"T"`, so it silently excludes them. Use date-only bounds
  (`kickoff>2026-09-12&kickoff<2026-09-13`) or a space. The compact `20260912` form
  does not match.
- **`/v2/matches` without `competition=` mixes every competition**, including
  rows with no `kickoff` — filter client-side or always pass `competition=8`.
- **`abbr` is only on `/v2/matches*` team objects**, not on the matchweek list. Join
  to `/v1/competitions/8/seasons/2026/teams` for abbreviations and stadiums.
- **Endpoint versions are per path** (`v1`…`v5`); the same resource can exist at
  several versions with different shapes (e.g. `/v1/players/{id}` vs `/v1/players/{id}/basic`).
  Use the versions documented here — they are what the site calls.
- **Caching:** CloudFront (`X-Cache`), `max-age` 5–3600 s plus long
  `stale-while-revalidate`, so a value can be up to `max-age + 120–600 s` old. No
  `ETag` / `Last-Modified`. gzip honoured (77 KB → 6.7 KB).
- **CORS** is `*` but `Vary: Origin` — the header is only emitted when an `Origin`
  is sent, which is what browsers do anyway.
- **Rate limiting:** none observed across ~100 requests in a few minutes.
- **Season ids are not years before 2011/12** (`"1"` = 2000/01, `"11"` = 1992/93);
  map through `competitions/8/details.seasons`.
- **Player `/v1/players/{id}` is an array**, one row per registration; the `id`
  field there is `{competitionId, seasonId, playerId}` — the plain id is
  `id.playerId`.
- The site also exposes a `/v3/graphql` endpoint on the same host (seen in the
  bundle). Not probed.

## Out of scope

- `api.premierleague.com/*` — account, personalisation, payments (needs login).
- `resources.premierleague.com` / `resources.premierleague.pulselive.com` — images
  (badges, photos). Link out; do not commit.
- Fantasy Premier League (`fantasy.premierleague.com/api/…`) — a separate, also
  key-less API with its own ids; useful for FPL, not for live scores. Not mapped.
- Live audio/video (`LIVESTREAM_*`, Evergent) — subscription.

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `/v2/competitions/8/details` | `seasons[]` | Stage = `structure.currentPhase`; single `L` phase for the league |
| Game (id, teams, start time) | matchweek list / `/v2/matches` | `matchId`, `homeTeam`, `awayTeam`, `kickoff` + `kickoffTimezoneString`, `ground` | **Convert kickoff from Europe/London**; UTC directly available only in `momentum.matchInfo` |
| GameState | match object | `period`, `resultType` | see table; every regulation value confirmed live 2026-09-13. A zeroed score appears while `period` is still `PreMatch`, so read the state from `period` alone |
| Score by period | match object | `halfTimeScore`, `score` per team | Only HT and FT totals; per-half from `timeline` goals. `halfTimeScore` tracks the live score until the break, so it is a half-time score only from `HalfTime` on |
| Clock / period | match object (`clock` minutes), `momentum.liveData.matchDetails.period[]` (UTC start/end, injury time) | | **Gap:** whole cumulative minutes only, confirmed live - no seconds anywhere in the match object. It freezes through half time and **steps back** from 47 to 45 at the restart, so it is not monotonic. Derive seconds from period start times, or use the legacy API's `clock.secs` |
| GameEvent | `/v1/matches/{id}/timeline` (+ `/events` for assists) | `eventType`, `minutes`/`seconds`, `teamId`, `playerId`, `isoTimestampUtc` | Assist ids only in `events.goals[].assistPlayerId`, stamped with the **conventional** minute while the timeline carries the floor minute (1:17 → timeline `1`, events `"2"`); join on `minutes + 1`. A converted penalty is `PENALTY_SCORED` (`goalType: "Penalty"` in `events`), **not** `PENALTY_GOAL`. An `OWN_GOAL` timeline event carries the **scorer's** `teamId` (Miley, Leeds–Newcastle 2026-09-14) while `events` files it under the beneficiary with `goalType: "Own"`, and the core credits the beneficiary |
| Lineups | `/v3/matches/{id}/lineups` | `players[]`, `formation.lineup`, `formation.subs`, `managers[]` | Full, with formation grid. Player names inline. Written once ~1 h before kick-off and never updated, so substitutions come from the timeline |
| Team | `/v1/competitions/8/seasons/2026/teams` | `id`, `name`, `shortName`, `abbr`, `stadium` | No crest URL |
| Player | `/v1/competitions/8/seasons/2026/playerinfo/{id}` or `squad` | bio | Full (nationality, DOB, height, weight, foot) |
| Standings | `/v5/…/standings` | `tables[0].entries[]` | Overall + home + away splits; compute GD; `deductions[]` |
| Officials | `/v1/matches/{id}/officials` | `matchOfficials[]` | Names only |

## TODO

- [x] Live samples captured 2026-09-13 and curated 2026-09-25 (Coventry 0-5 Brighton,
      364 polls): `match`, `timeline`, `events`, `lineups` and `stats` in `FirstHalf`,
      `HalfTime` and `SecondHalf`. `clock` has **no** seconds, only whole cumulative
      minutes.
- [ ] `standings` during a match: whether `live` flips to `true` is still unobserved
      (the capture followed one match, not the table).
- [ ] `momentum` while playing (`matchStatus` / `periodId`) - not polled by the capture.
- [ ] Observe a postponed/abandoned fixture and record how it is represented.
- [x] `RED_CARD`, `PENALTY_SCORED` and `OWN_GOAL` observed. Still missing:
      `SECOND_YELLOW`, a missed or saved penalty.
- [ ] Decide whether the legacy `footballapi.pulselive.com` clock is worth keeping
      as a fallback once SDP live behaviour is known.

## Changelog

| Date | Change |
|---|---|
| 2026-09-25 | Live states curated from the 2026-09-13 capture of Coventry 0-5 Brighton (364 polls): 9 new samples across `FirstHalf`/`HalfTime`/`SecondHalf` and a pre-kick-off match-day header. Game states table and transition timings rewritten from observation. Found and fixed: a converted penalty is `PENALTY_SCORED`, which the mapper did not recognise, so the goal vanished from the timeline and every later running score was one short. Also recorded: the zeroed score that appears an hour before kick-off, the clock stepping back from 47 to 45 at the restart, `halfTimeScore` tracking the live score in the first half, `lineups` never changing after publication, unordered `stats` rows, `RED_CARD` / `StraightRed` and `periodId: "16"` for `LINEUP_CONFIRMED`. |
| 2026-09-12 | Core provider built. Correction: the `kickoff>`/`kickoff<` filter is a string comparison — use date-only bounds (see quirks). |
| 2026-09-11 | Initial mapping. Endpoint map extracted from the premierleague.com bundle; 45 endpoints verified; 46 samples (pre-match + full-time, standings, squads, players, legacy API). |
