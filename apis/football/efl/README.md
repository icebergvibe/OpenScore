# EFL API (Gamechanger `multi-club-matches` + Firestore live feed)

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | England (and Wales) — Championship and Carabao Cup (League Cup) documented; the same API serves League One, League Two and the EFL Trophy |
| **Official site** | https://www.efl.com/match-centre/ |
| **Base URL** | `https://multi-club-matches.webapi.gc.eflservices.co.uk/v2` (REST) · `https://firestore.googleapis.com/v1/projects/eflcom/databases/(default)/documents` (live event push) |
| **Auth** | None — public endpoints used by efl.com. Firestore reads need **no key either** (security rules allow anonymous reads of `live-feed`) |
| **Format** | JSON:API-flavoured JSON (UTF-8): `data[]`/`data{type,id,attributes}`, `meta`, `links`; errors `{"errors":[{"title","detail","status"}]}`. `Content-Type` is `application/vnd.api+json` on most routes but plain `application/json` on `/matches*` and `/broadcasters` |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *` on every REST response; `OPTIONS` preflight `200` allowing `GET,OPTIONS`. Firestore echoes the request `Origin` |
| **Live push** | **Yes** — Firestore collection `live-feed` (goals, red cards, HT, FT, end of 90 before pens) that efl.com subscribes to with `onSnapshot`. Readable key-less over Firestore's REST `runQuery`, ~20 s behind Opta's event timestamp |
| **WAF / UA requirement** | None. AWS API Gateway behind CloudFront; a request with no `User-Agent` succeeds |
| **Last full verification** | 2026-09-17 |
| **Status** | ✅ verified pre-match, full-time, penalty shoot-out, extra time, two-leg play-offs · 🚧 in-play REST states not yet observed (next: Bristol City v Watford 2026-09-18 19:00Z, Championship round 8 on 2026-09-19) |

## Overview

efl.com is a Nuxt site on EFL Digital's **Gamechanger** platform, the same system that
powers most EFL club websites (`*.webapi.gc.eflservices.co.uk`, with club variants such as
`gc.millwallfcservices.co.uk`). All match data comes from one micro-service,
`multi-club-matches` v2, which wraps Opta: match ids are `g` + Opta match id, team ids
`t` + Opta team id and player ids `p` + Opta player id — the same numeric namespace the
[Premier League API](../premier-league/README.md) uses without the prefix (`t43` ↔ `"43"`
Manchester City, `g2685170` ↔ Pulselive `matchId "2685170"`).

It is a small API: one list route with filters, one match document that already contains
the header, lineups, kits and events, a five-column match-stats route, a league table,
teams, season stats and a broadcaster list. There is no player, squad, commentary or
head-to-head route. In-play updates for the site's "Matchday Live" vidiprinter come from a
**Firebase Firestore** collection (`eflcom` project) that can be read anonymously.

Competition ids are Opta's: **`10` Championship**, **`2` League Cup (Carabao Cup)**; also
`11` League One, `12` League Two, `7` EFL Trophy — those three are served by the same
routes but are not verified here beyond appearing in `/competitions`. Play-offs live inside
the league's own competition id.

All samples in [`samples/`](samples/) were captured on **2026-09-17** ~21:20 UTC; see
[`samples/_meta.md`](samples/_meta.md).

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Competition | small int (Opta) | `10` Championship · `2` Carabao Cup · `11` League One · `12` League Two · `7` EFL Trophy | `GET /competitions` |
| Season | int = **start year** | `2026` = 2026/27 | Constant `currentSeason: 2026` in the site bundle; the API accepts earlier seasons (`2025` verified, `/matches` with no filter reaches back to 2004) |
| Match | `g` + Opta match id, string | `g2685170` | `/matches` list, `id`; also the `fixtureID` in `https://www.efl.com/match-centre/{id}` |
| Round / match-day | string in match detail | `"7"` (league round), `"4"` (cup round), `"47"`–`"49"` (play-off semis / final) | `matches/{id}.matchDay` only — not filterable |
| Team | `t` + Opta team id, string, stable across seasons | `t109` Wrexham, `t45` Norwich, `t43` Manchester City | `GET /teams?competitionID=…&seasonID=…`; `t9830` is the `TBC` placeholder |
| Player | `p` + Opta player id, string | `p466052` Rayan Cherki | Match detail lineups and events only (no player route) |
| Event | Opta event id, string | `"2971125029"` | `matches/{id}.matchTeams[].events.*[].eventID`; also the suffix of Firestore doc ids |
| Kit | string | `"4978"` | `matches/{id}.matchTeams[].kitID` with hex colours |
| Firestore document | `{matchId}_{eventId}` or `{matchId}_HalfTime` / `_FullTimePens` / `_FullTime` | `g2685170_2971125029`, `g2685170_FullTime` | `live-feed` collection |

**Championship 2026/27 team ids:** t41 Birmingham · t5 Blackburn · t30 Bolton · t113
Bristol City · t90 Burnley · t97 Cardiff · t33 Charlton · t24 Derby · t83 Lincoln · t25
Middlesbrough · t103 Millwall · t45 Norwich · t47 Portsmouth · t107 Preston · t52 QPR · t49
Sheffield United · t20 Southampton · t110 Stoke · t80 Swansea · t57 Watford · t35 West Brom ·
t21 West Ham · t39 Wolves · t109 Wrexham. `teamShortName` is the display short name
(`"PNE"`, `"QPR"`, `"Sheffield Utd"`); `teamNameInitials` is `null` everywhere observed, so
there is no three-letter code.

## Discovery path

1. **Competitions + "is anything live":** `GET /competitions?includeLiveMatchCount=true`
   → five rows with `liveMatchCount` (lags a finished game by some minutes — treat as a
   hint, not truth).
2. **Fixtures for a day:** `GET /matches?from=2026-09-19%2000:00:00Z&to=2026-09-19%2023:59:59Z&competitionID=10,2&seasonID=2026&page.size=100`
   → compact rows with `kickOffDateUTC`, `matchPeriod`, `matchMinutes`, scores, half-time
   and penalty scores, crests. **A day with no match is `404`**, not an empty list.
   For a whole season use `page.size=600` (the Championship's 552 rows come back in one
   527 KB page).
3. **Match detail:** `GET /matches/{id}` — header, referee, attendance, kits, formations,
   starting XI + bench with positions and shirt numbers, and events grouped per team
   (`goals`, `bookings`, `subs`, `shootout`, `var`). Pre-match it is 3.7 KB with empty
   `players` and `events`; finished 25–40 KB.
4. **Match stats:** `GET /stats/match/{id}` — possession, shots, on target, corners, fouls
   per team; `{"data": []}` before kick-off.
5. **Live events without polling the document:** Firestore `runQuery` on `live-feed` with
   `eventTime > <midnight UTC epoch>` (see [Live feed](#live-feed-firestore)) — one call
   covers every EFL match of the day (~10 KB per match); goals arrive ~20 s after Opta's
   timestamp.
6. **Table:** `GET /league-tables?competitionID=10&seasonID=2026` (snapshot, see quirks).
   **Teams:** `GET /teams?competitionID=10&seasonID=2026`. **Club schedule:**
   `GET /matches?seasonID=2026&teamID=t109&page.size=100` (all competitions).

Recommended poll interval for a live match: **15 s** on `GET /matches/{id}` (no edge
cache, every request hits the origin — do not go faster), **30–60 s** for the day list,
and the Firestore query at **15–30 s** as the cheap "did anything happen" probe (or the
Firebase SDK listener for real push). Stop polling once `period`/`matchPeriod` is
`FullTime`. Tables and teams: cache for an hour or more.

## Endpoints — `multi-club-matches` v2

Paths are relative to `https://multi-club-matches.webapi.gc.eflservices.co.uk/v2`. Every
response was `x-cache: Miss from cloudfront` with no `Cache-Control`/`ETag` — nothing is
edge-cached and conditional requests are not supported. Unknown query parameters are
silently ignored (`matchDay=`, `matchPeriod=`, `limit=` on stats do nothing). Routes that
do not exist (`/competitions/{id}`, `/teams/{id}`, `/matches/{id}/events` …) return API
Gateway's `403 {"message":"Missing Authentication Token"}` — that message means "no such
route", not "needs a key".

### `GET /competitions?includeLiveMatchCount=true`

| | |
|---|---|
| **Purpose** | The five competitions and how many matches are in play in each |
| **Parameters** | `includeLiveMatchCount` — adds `liveMatchCount` |
| **Sample** | [`competitions.json`](samples/competitions.json) captured 2026-09-17 |
| **Last verified** | 2026-09-17 |
| **Cache** | none |

**Response shape**

```
data[] { type "competition", id "10", attributes { competitionName, competitionCode "EN_D1", crestURL null, liveMatchCount } }
meta { totalCount, count }
```

`competitionCode`: `EN_D1` Championship, `EN_D2` League One, `EN_D3` League Two, `EN_FL`
EFL Trophy, `EN_LC` League Cup. `links.self` points at `multi-club-matches.gc.eflservices.co.uk`
(no `webapi`) — that host **does not resolve**; ignore the links.

### `GET /matches`

| | |
|---|---|
| **Purpose** | Filterable fixture/result list — the scoreboard call, the season list and the club schedule |
| **Parameters** | `from`, `to` — `YYYY-MM-DD HH:MM:SSZ` (URL-encode the space, `T` also accepted); a bare date means midnight, so `to=2026-09-19` **excludes** that day · `competitionID` — one id or a comma list · `seasonID` — optional when a date range is given · `teamID` — one `t…` id · `page.size` (default 10, 600 verified) · `page.number` (1-based) |
| **Samples** | [`matches-date.final.json`](samples/matches-date.final.json) (Carabao Cup, 2026-09-17) · [`matches-date.pre.json`](samples/matches-date.pre.json) (Championship, 2026-09-19) · [`matches-date.empty.json`](samples/matches-date.empty.json) (404) · [`matches-season-cup.json`](samples/matches-season-cup.json) (all 84 Carabao Cup ties so far) · [`matches-season-page.json`](samples/matches-season-page.json) (page 2 of 10, pagination links) · [`matches-team.json`](samples/matches-team.json) (Wrexham, all competitions) |
| **Last verified** | 2026-09-17 |
| **Cache** | none |

**Response shape**

```
data[] { type "match", id "g2685170",
  attributes {
    kickOffDateUTC "2026-09-17 18:30:00"     – UTC, no zone marker
    TBC null | true                          – kick-off time not fixed
    homeTeam / awayTeam { crest, name, officialName, shortName, initials, score, halfScore,
                          penaltyScore, liveStreamingUrl, teamURL }
    matchMinutes 93, matchPeriod "FullTime", formattedMatchTime "90' +3'"
    resultType "NormalResult" | "PenaltyShootout" | "Aggregate" | null
    postponementReason null | "Frozen Pitch" | "Fixture Clash" | "Waterlogged Pitch"
    competitionID 2
    matchWinner { name, crest, … } | null
  },
  links.self }
meta { totalCount, count }
links { self, first, prev, next, last }      – first/next/last only when paginated
```

Ordered by kick-off ascending. Before kick-off `score`, `halfScore`, `matchMinutes` and
`resultType` are `null` and `formattedMatchTime` is `""`. `matchWinner` is present for
normal wins **and** shoot-out wins in this list (unlike the detail's top-level
`matchWinnerID`). Without any filter the list spans every season since 2004 (17 156 rows).

### `GET /matches/{id}`

| | |
|---|---|
| **Purpose** | Everything about one match: header, officials, kits, lineups, events |
| **Parameters** | path: `id` `g…` |
| **Samples** | [`match.pre.json`](samples/match.pre.json) · [`match.final.json`](samples/match.final.json) (Man City 5–0 Norwich, cup) · [`match.final.shootout.json`](samples/match.final.shootout.json) (Peterborough 3–3 Barnsley, 7–6 pens) · [`match.final.extra-time.json`](samples/match.final.extra-time.json) (Southampton 2–1 Middlesbrough aet, play-off semi 2nd leg) · [`match.final.playoff-final.json`](samples/match.final.playoff-final.json) (Hull 1–0 Middlesbrough, Wembley — four `matchTeams`) · [`match.404.json`](samples/match.404.json) |
| **Last verified** | 2026-09-17 |
| **Cache** | none |

**Response shape**

```
data { type "match", id, attributes {
  matchID, competitionID, seasonID 2026, kickOffUTC "2026-09-17 18:30:00", TBC
  groupName null, matchDay "4", matchType "Regular"|"Cup Short"|"Cup"|"2nd Leg", leg null
  matchWinnerID "t43"|null, gameWinnerID, gameWinnerType, legWinnerTypeID   – null everywhere observed except matchWinnerID
  period "PreMatch"|"FullTime"|…, postponementReason, roundNumber null, roundType null
  venue "Etihad Stadium", venueCity, refereeName null, homeTeamID "t43", awayTeamID "t45"
  isBroadcast "gb_only"|"global"|null
  matchDetails null (pre) | { attendance, awayAttendance, kickOffUTC "…:00.000000", matchTime 93,
                              period, refereeName "Ruebyn Ricardo", resultType, weather "Clear",
                              matchWinner {team…}|null, formattedMatchTime "90' +3'" }
  matchTeams[] {                                – filter by homeTeamID/awayTeamID, see quirks
    matchID, teamID, kitID, formation "4231", score, halfScore, ninetyScore, extraScore, penaltyScore
    kit { kitID, kitType "home"|"away"|"third", colour1 "#66CCFF", colour2 … colour5, seasonID }
    team { teamID, teamName, teamOfficialName, teamShortName, crestURL, stadiumName, teamURL,
           streamingURL, ticketURL, matchCentreURL, retailURL, facebookURL, twitterURL, … }
    players {} (pre) | { Start[11], Sub[9] } of
      { playerID, formationPlace 1–11 (0 on the bench), playerPosition, playerSubPosition,
        shirtNumber, playerStatus "Start"|"Sub", playerName { firstName, lastName, knownName, customKnownName } }
    events { goals[], bookings[], subs[], shootout[], var[] }   – see below
  }
  homeTeam / awayTeam { team… }                – same object as matchTeams[].team
} }
```

**Events.** Every event carries `eventID`, `matchID`, `teamID`, `eventTimestamp`
(`"2026-09-17 18:58:25.000000"`, UTC wall-clock of the Opta event), `eventTime` (display
minute, 29 for 28:17), `eventMinute`/`eventSecond` (floor), `eventPeriod`,
`formattedEventTime` (`"29'"`, `"45' +1'"`, `"116'"`), and exactly one of:

- `goalEvents { eventID, playerID, goalType, player }` — `goalType`: `Goal`, `Penalty`,
  `Own` *(observed)*.
- `bookingEvents { eventID, playerID, card "Yellow"|"Red", cardType "Yellow"|"SecondYellow"
  (|"Red" presumably), cardPeriod, reason, player }` — reasons observed: `Foul`, `Dissent`,
  `Argument`, `Time wasting`, `Not Retreating`, `Off the ball foul`, `Persistent Infringement`.
- `substitutionEvents { eventID, subOnID, subOffID, reason "Tactical"|"Injury", subPeriod
  1|2|4 (int), subOnPlayer, subOffPlayer }`.
- `shootoutEvents { eventID, playerID, outcome "Scored"|"Missed"|"Saved", player }` —
  `eventTime` counts on from the final minute (91, 92, …), `eventPeriod`/`eventMinute` null.
- `varEvents` — none observed yet (`var[]` always empty).

`eventPeriod` is `FirstHalf`/`SecondHalf`/`ExtraFirstHalf`/`ExtraSecondHalf` on goals and
bookings but the **numeric string `"1"`/`"2"`/`"4"`** on substitutions (whose own
`subPeriod` is the same number as an int). Own goals are filed
under the **beneficiary** (the team whose score they raise), so `len(goals[]) == score` for
every team; the scorer's real side must be looked up in the other team's `players`. The
nested `player` objects inside events are a **stale join** — their `matchID`, `teamID`,
`shirtNumber` and `formationPlace` come from some other match the player appeared in
(`teamID: "t12969"` for a Man City goalscorer) — use only the `playerID` and resolve it
against `matchTeams[].players`.

**Scores.** `score` is the final score including extra time; `halfScore` the half-time
score; `ninetyScore`/`extraScore` are only filled when extra time was played
(`ninetyScore: 1, extraScore: 1` → 2 aet); `penaltyScore` is the shoot-out tally. In the
Carabao Cup ties go **straight to penalties after 90 minutes** (no extra time until the
semi-finals), so a shoot-out sample has `matchTime` 95–101 and `formattedMatchTime`
`"90' +11'"`.

**Winner.** Top-level `matchWinnerID` is set only for wins inside 90 minutes (`null` after
a shoot-out or on aggregate); `matchDetails.matchWinner.teamID` is set for every decided
tie including shoot-outs and second legs (`resultType: "Aggregate"`).

Unknown id → `404 {"errors":[{"detail":"Match not found","title":"Not Found","status":"404"}]}`.

### `GET /stats/match/{id}`

| | |
|---|---|
| **Purpose** | Team totals for one match |
| **Samples** | [`stats-match.final.json`](samples/stats-match.final.json) · [`stats-match.pre.json`](samples/stats-match.pre.json) (`{"data": []}`) |
| **Last verified** | 2026-09-17 |
| **Cache** | none |

```
data[] { type "match-stats", id "g2685170", attributes { teamID, competitionID, seasonID,
         possession 65.9, shots 14, shotsOnTarget 6|null, corners, fouls, team {…} } }
```

Two rows, home first. `shotsOnTarget` is `null` rather than `0` (Norwich, 4 shots, none
on target). That is the whole stat set — no passes, xG or per-player numbers here.

### `GET /league-tables?competitionID={id}&seasonID={season}`

| | |
|---|---|
| **Purpose** | League table (Championship, League One, League Two). `404 "No league table found"` for the cup |
| **Samples** | [`league-tables.json`](samples/league-tables.json) · [`league-tables.404.json`](samples/league-tables.404.json) |
| **Last verified** | 2026-09-17 |
| **Cache** | none, but the data is a **snapshot** — `meta.snapshotDate "2026-09-17 11:15:40"` |

```
data[] { type "team", id "t21", attributes { teamName, teamNameInitials, teamShortName, crestURL,
         startDayPosition, position, won, drawn, lost, goalsFor, goalsAgainst, goalDifference,
         points, played, form "W,W,W,W,D" } }
meta { count 24, snapshotID, snapshotDate, roundID null }
```

Rows are in table order. `form` lists the last five results **newest first**.
`startDayPosition` is the position at the start of the day. Deductions are already in
`points`. `seasonID=2025` returns last season's final table (Coventry 95 pts).

### `GET /league-table-rounds` and `GET /league-tables/{season}/7/?roundID=…`

Group tables for the EFL Trophy (competition `7`): `/league-table-rounds?competitionID=7&seasonID=2026`
lists round ids (`"Northern Group A"` …), then `/league-tables/2026/7/?roundID=Northern%20Group%20A,…`
returns one table per group. `404 "No league table rounds found"` for `10` and `2`
([`league-table-rounds.404.json`](samples/league-table-rounds.404.json)). Out of scope for
the Championship/Carabao Cup mapping; noted so nobody re-tries it.

### `GET /teams?competitionID={ids}&seasonID={season}`

| | |
|---|---|
| **Purpose** | Clubs in a competition with names, crests, stadium and every club URL |
| **Parameters** | `competitionID` — id or comma list (one row per team **per competition**, so `10,2` returns Norwich twice) · `seasonID` · `page.size` (default 100; `10,2` has 117 rows) |
| **Samples** | [`teams.json`](samples/teams.json) (Championship, 24) · [`teams-cup.json`](samples/teams-cup.json) (Carabao Cup, 93 = 92 clubs + `t9830` "TBC") |
| **Last verified** | 2026-09-17 |
| **Cache** | none |

```
data[] { type "team", id "t41", attributes {
  teams { teamID, teamName, teamOfficialName, teamShortName, teamNameInitials, teamURL, crestURL,
          stadiumName, streamingURL, ticketURL, matchCentreURL, retailURL, facebookURL, twitterURL },
  competitions { competitionID, competitionName, competitionCode, crestURL } } }
```

Premier League clubs in the cup carry only `teamName`/`crestURL` (no short name, stadium or
URLs — the CMS only knows EFL members). Sorted by name.

### `GET /matches/firstlast?seasonID={season}`

Two rows: first and last kick-off of the season (`2026-08-01 14:00:00` → `2027-05-08 14:00:00`;
the play-offs are added later). [`matches-firstlast.json`](samples/matches-firstlast.json).

### `GET /stats/teams/{season}/?competitionID={ids}` · `GET /stats/players/{season}/?competitionID={ids}&page.size=&page.number=`

| | |
|---|---|
| **Purpose** | Season leaderboards: 27 team stats and 26 player stats per competition |
| **Samples** | [`stats-teams.json`](samples/stats-teams.json) (**arrays truncated to 5 rows** — the full response is 510 KB for one competition) · [`stats-players.json`](samples/stats-players.json) (page 1 of 3 rows per stat) |
| **Last verified** | 2026-09-17 |
| **Cache** | none |

```
data[] { type "team-stats", id "2026-10", attributes { competitionID, seasonID,
         goals[] { team {…}, value }, assists[], yellowCards[], redCards[], shots[], shotsOnTarget[],
         hitWoodwork[], headedGoals[], penaltyGoals[], goalsInsideBox[], goalsOutsideBox[], offsides[],
         passes[], passAccuracy[], interceptions[], tackleSuccess[], clearances[], aerialDuelsWon[],
         ownGoals[], ownGoalsConceded[], penaltiesConceded[], totalFoulsConceded[], goalsConceded[],
         penaltiesSaved[], blocks[] } }
```

Player stats have the same shape with `player { firstName, lastName, knownName }` +
`teamName` + `team {…}` per row, `passingAccuracy` and `cleanSheets` instead of
`passAccuracy`/`ownGoalsConceded`, and `meta.statPageCount[competitionID][stat]` for
paging. **Player rows carry no `playerID`** — names only — so they cannot be joined to
lineups. `limit=` on team stats is ignored (all 24 rows come back).

### `GET /broadcasters`

| | |
|---|---|
| **Purpose** | "Upcoming TV games": the match list plus which broadcaster shows each one |
| **Parameters** | `from`, `to` (bare dates work here) · `seasonId` (lower-case d) · `competitionID` · `onlyBroadcast=true` (only matches with a broadcaster) · `includeBroadcasters=true` (adds `broadcasters[]`) · `page.size` (default 10) · `page.number` |
| **Sample** | [`broadcasters.json`](samples/broadcasters.json) (Championship 2026-09-18 → 20) |
| **Last verified** | 2026-09-17 |

Rows are the `/matches` list shape plus `broadcasters[] { name "Viaplay", logo "viaplay.png", url }`.
Captured from a German IP every match listed Viaplay, so the list is probably resolved
per caller country — the UK view (Sky Sports) is unverified. The match detail's
`isBroadcast` (`gb_only` / `global` / null) is the rights flag the site uses to hide club
streams.

## Live feed (Firestore)

efl.com's "Matchday Live" ticker is a Firestore `onSnapshot` on collection **`live-feed`**
in project **`eflcom`**, filtered `eventTime > <today 00:00 UTC>` and ordered descending.
The collection's security rules allow anonymous reads, so the standard Firestore REST API
works with **no API key, no token** (the bundle's web `apiKey` is not needed):

```
POST https://firestore.googleapis.com/v1/projects/eflcom/databases/(default)/documents:runQuery
Content-Type: application/json

{"structuredQuery":{"from":[{"collectionId":"live-feed"}],
  "where":{"fieldFilter":{"field":{"fieldPath":"eventTime"},"op":"GREATER_THAN","value":{"integerValue":"1789603200"}}},
  "orderBy":[{"field":{"fieldPath":"eventTime"},"direction":"DESCENDING"}]}}
```

| | |
|---|---|
| **Samples** | [`firestore-live-feed.runquery.json`](samples/firestore-live-feed.runquery.json) (2026-09-17: two matches, 9 docs) · [`firestore-live-feed.runquery.shootout-day.json`](samples/firestore-live-feed.runquery.shootout-day.json) (2026-09-15: 12 matches, 75 docs, includes `FullTimePens` + shoot-out tallies) · [`firestore-live-feed.doc.json`](samples/firestore-live-feed.doc.json) (`GET …/documents/live-feed/g2685170_FullTime`) · [`firestore-live-feed.list.json`](samples/firestore-live-feed.list.json) (`GET …/documents/live-feed?pageSize=2`, oldest docs first with `nextPageToken`) |
| **Last verified** | 2026-09-17 |
| **Read-only** | `runQuery`, `GET` document and `GET` list all work; `listCollectionIds` is `403 PERMISSION_DENIED` — nothing else in the project is readable |

**Document shape** (Firestore typed values — `integerValue` strings, `mapValue.fields`):

```
live-feed/{matchID}_{eventID | HalfTime | FullTimePens | FullTime}
  matchID "g2685182", competitionID 2, eventType, eventTime 1789676179 (unix s)
  matchTime "29'" | "45' +3'" | "90' +5'"
  teamName, teamCrest, playerName "Rayan Cherki"          – Goal / Booking only
  homeTeam / awayTeam { name, shortName?, crest, score,
                        shootOutScore?, shootOutHitsMisses?: [1,1,0,…] }   – on the final FullTime after pens
createTime / updateTime                                   – server timestamps
```

`eventType` *(observed + what the site's card component handles)*: `Goal`, `Booking`
(rendered "Off", i.e. red cards; none observed in 87 docs), `HalfTime`, `FullTimePens`
(end of 90 minutes, shoot-out about to start; `matchTime "95'"`), `FullTime` (final,
carries the shoot-out arrays when there was one). Scores in `homeTeam`/`awayTeam` are the
running score **after** the event. No substitutions, yellow cards or kick-off docs.

Latency measured against the REST event timestamps: goals `createTime` 17–23 s after
`eventTimestamp`; `FullTime` within a minute of the whistle. Documents are never updated
after creation (`createTime == updateTime`), so `eventTime > lastSeen` is a complete delta
query. The collection keeps history (the oldest documents are from 2024/25), and a
`where matchID == …` filter needs a composite index that does not exist (`400
FAILED_PRECONDITION`) — filter by `eventTime` range and match client-side. For true push,
the Firebase SDK's `onSnapshot`/`Listen` channel is what the site uses; there is no SSE.

## Game states

The site's own state machine (from the bundle) over `period` / `matchPeriod`:

| Site state | `period` values |
|---|---|
| upcoming | `PreMatch` (also `Pre-Match`, `null`) |
| active | `FirstHalf`, `HalfTime`, `SecondHalf`, `ExtraFirstHalf`, `ExtraSecondHalf`, `ShootOut`, `FullTime90`, `FullTimePens` |
| complete | `FullTime` |
| postponed | `Postponed` in `period` **or** `resultType` |

Only `PreMatch` and `FullTime` have been observed on the REST routes so far (the two
matches in play on 2026-09-17 had finished by the time the API was found); the others are
Opta's standard period names and the ones the site renders (`HT`, `FT`, `PEN`, `P`).
`Abandoned`/`Cancelled` are unverified.

| | Pre-match | Live (expected) | Full-time |
|---|---|---|---|
| list `matchPeriod` / `matchMinutes` / `formattedMatchTime` | `PreMatch` / `null` / `""` | period / minute / `"37'"` | `FullTime` / 93 / `"90' +3'"` |
| detail `matchDetails` | `null` | object | object with `attendance`, `resultType` |
| detail `players` | `{}` | `{Start, Sub}` (when the lineups appear is unverified — likely ~1 h before kick-off) | `{Start, Sub}` |
| detail `events.*` | `[]` | growing | complete |
| `/stats/match` | `{"data": []}` | rows | rows |
| `resultType` | `null` | `null`? | `NormalResult` / `PenaltyShootout` / `Aggregate` |

A postponed-then-replayed match keeps its `postponementReason` after it is played
(`"Frozen Pitch"` on a `FullTime` row), so the reason alone does not mean "currently
postponed" — only `period`/`resultType == "Postponed"` does.

## Quirks & gotchas

- **Times are UTC without a marker** (`"2026-09-17 18:30:00"` = 19:30 BST). `eventTimestamp`
  and `matchDetails.kickOffUTC` add `.000000`.
- **Empty day = 404** `"No matches found"`. Treat 404 on `/matches` as an empty list.
- **Bare `to=` date is exclusive** — it is parsed as midnight. Always send
  `to=YYYY-MM-DD 23:59:59Z` (or the next day).
- **`matchTeams` may contain more than two teams.** The play-off final document still holds
  the two semi-final placeholders (`t20` Southampton and `t9830` TBC with null scores and
  no players) beside Hull and Middlesbrough. Pick the entries whose `teamID` equals
  `homeTeamID`/`awayTeamID`.
- **`formattedMatchTime` does not understand extra time**: a 127-minute match reads
  `"90' +37'"`; use `matchTime`/`matchMinutes` plus `ninetyScore`/`extraScore` instead.
  `formattedEventTime` on events is fine (`"116'"`).
- **`matchDay` is a round label, not chronological** — Championship round 6 was played on
  2026-09-15, round 7 on 2026-09-12; the play-off semis are `"47"`/`"48"` and the final
  `"49"`. Cup rounds count from `"1"` (2026-08-01) upward.
- **Nested `player` objects in events are stale** (wrong `matchID`/`teamID`/shirt) — join on
  `playerID` only. **Player stats have no `playerID`** at all.
- **Substitutions use numeric `eventPeriod`/`subPeriod` (`"2"`, `"4"`)** while goals and
  cards use `SecondHalf`/`ExtraSecondHalf`.
- **No caching at all** — every call is an origin hit on API Gateway; no ETag, no
  `Cache-Control`, no 304. Keep polling modest.
- **`liveMatchCount` lags** — both finished matches still counted as live 10 minutes after
  full time.
- **Crests** are 1001×1001 PNGs of ~190 KB at `https://crests.gc.eflservices.co.uk/t{id}.png`
  (S3 + CloudFront, `max-age=315569520`, no SVG, no CORS header). Cache them; do not load
  24 of them into a list at full size.
- **Team `teamNameInitials` is always null** — no abbreviations anywhere; derive from
  `teamShortName` or the club table.
- **Two content types** (`application/vnd.api+json` vs `application/json`) for the same
  JSON:API shape depending on the route.
- Firestore `integerValue`s are strings; `shootOutHitsMisses` is an `arrayValue` of
  `integerValue` 1/0.

## Out of scope

- `https://matchdata.webapi.gc.eflservices.co.uk/v1` (`matchapi` in the site config),
  `teams.webapi…/v2` (`loanedplayersapi`), `client-settings.webapi…/v1` — configured but
  never called by efl.com's pages; the obvious paths return the API Gateway `403` (no route).
- `live-blog.webapi.gc.eflservices.co.uk/v2/blogs` exists (`404 "No Blog found."` for a
  match id) but efl.com does not use it for matches; club sites might.
- `club-pages.webapi…/v2/pages/{club}` sends a Cognito bearer token — out per principles.
- `streamline.webapi…/v2` (iFollow video, packages, entitlements), `stripe.gc…`,
  `advertising.webapi…`, `promo-overlays`, `experiments`, `sponsors`, `webapi…/v1/news`
  (efl.com news, key-less but not match data), `videos.webapi…/v2/categories` — site plumbing.
- `organisations.webapi…/v1/organisations` answers `{"Items":[],"Count":0}` — a DynamoDB
  scan with nothing to give anonymous callers.
- `global-variables.webapi…/v2/global-variables` is readable but stale (`defaultSeasonID: 2024`).
- `https://images.gc.eflservices.co.uk/fit-in/{w}x{h}/{uuid}.png` is a Thumbor-style
  handler for CMS images (sponsors, news); it does **not** resize crests (`302` to S3).
- The site's Firebase `apiKey` (`AIzaSyBeGbb…`, `authDomain eflcom.firebaseapp.com`) is in
  the bundle but, as shown above, not needed for reads.

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | constant | `competitionID` 10 / 2, `seasonID` 2026 | Season is the start year; `/matches/firstlast` gives the span. Two `League`s (`championship`, `carabao-cup`) over one API; play-offs are stage `PLAYOFF` inside competition 10 (`matchType` `2nd Leg`/`Cup`, `matchDay` ≥ 47) |
| Game (id, teams, start time) | `/matches` list | `id`, `homeTeam`/`awayTeam` (names, crest), `kickOffDateUTC` (+`Z`) | Team ids only in the detail (`homeTeamID`) — the list gives names and crest URLs, whose file name `t{id}.png` carries the id |
| GameState | list `matchPeriod`, `resultType`; detail `period` | `PreMatch`→SCHEDULED, `FirstHalf`/`SecondHalf`/`Extra*`/`ShootOut`/`FullTime90`/`FullTimePens`→LIVE, `HalfTime`→INTERMISSION (opt-in), `FullTime`→FINAL, `Postponed`→POSTPONED | `TBC: true` still SCHEDULED; abandoned/cancelled unverified |
| Score by period | detail `matchTeams[]` | `halfScore`, `ninetyScore`, `extraScore`, `score`, `penaltyScore` | Period scores for 1H/2H/ET derivable; `GameEnding` from `resultType` + `extraScore` |
| Clock / period | list `matchMinutes` (int), detail `matchDetails.matchTime` | minute only, no seconds, no running flag | Same class of clock as the Premier League: elapsed minute, `running` null |
| GameEvent (goal, card, sub, shoot-out, VAR) | detail `matchTeams[].events` | `eventID`, `eventTime`, `eventPeriod`, `goalType`, `card`/`cardType`, `subOnID`/`subOffID`, shoot-out `outcome` | Own goal side = beneficiary (like Ligue 1/UEFA) — normalise per data-model.md; resolve players through lineups; `varEvents` shape unknown |
| Lineups | detail `matchTeams[].players.Start/Sub` | `formation`, `formationPlace`, `playerPosition`, `shirtNumber`, names | STARTERS + BENCH; no coach/manager, no substitutes-used flag (derive from `subs`) |
| Team | `/teams` | `teamID`, `teamName`, `teamShortName`, `crestURL`, `stadiumName`, URLs | No abbreviation; kit colours per match in the detail |
| Player | detail lineups only | `playerID`, `firstName`/`lastName`/`knownName`, position, shirt | No player route, no bio, no headshots; season stats are name-keyed |
| Standings | `/league-tables` | all standard columns + `form`, `startDayPosition` | Snapshot, not live; none for the cup |
| Match stats | `/stats/match/{id}` | possession, shots, on target, corners, fouls | Nothing per player |
| Live push | Firestore `live-feed` | `Goal`/`Booking`/`HalfTime`/`FullTimePens`/`FullTime` | Cheap delta probe; no kick-off/2nd-half-start doc, so period starts still need the REST document |
| Broadcast | `/broadcasters?includeBroadcasters=true`, detail `isBroadcast` | `broadcasters[].name` | Probably geo-resolved |
| Club crosswalk | — | `t{n}` = Opta team id = Premier League API `"{n}"` | A shared `opta` namespace would cover both leagues, but the [crosswalk](../../../docs/data-model.md#clubs-across-leagues) rules out external ids — keep an `efl` namespace and let the curated table pair them |

## Changelog

| Date | Change |
|---|---|
| 2026-09-17 | Initial mapping from the efl.com Nuxt bundle: `multi-club-matches` v2 (11 routes, 24 samples incl. shoot-out, extra time and play-off final), Firestore `live-feed` (4 samples), enum survey over 25 finished matches; health checks pass live (23/23 on 2026-09-18). In-play REST states pending. |
