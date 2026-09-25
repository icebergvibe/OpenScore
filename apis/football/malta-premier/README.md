# Malta Premier API (MFA Match Centre)

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | Malta |
| **Official site** | https://matchcentre.mfa.com.mt (Malta Football Association match centre) |
| **Base URL** | `https://api.mfa.com.mt/api/` (data) · `https://cms.mfa.com.mt/api/` (competition/club metadata, images) |
| **Auth** | None — public endpoints used by the match centre site |
| **Format** | JSON (UTF-8). Errors are empty bodies with the status code (`404`, `204`) |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *`; `OPTIONS` preflight `204` and allows the `x-version` header; `PageCount`/`ItemCount` exposed |
| **WAF / UA requirement** | None. Cloudflare in front; no `User-Agent` needed. The site sends `x-version: 2` on every data call — responses are identical without it, but send it anyway to get what the site gets |
| **Last full verification** | 2026-09-11 |
| **Status** | ✅ verified · ✅ live states captured end to end 2026-09-13 (`SCHEDULED` → `LIVE` → `INTERMISSION` → `LIVE` → `FINAL`) |

## Overview

The MFA match centre is a React app over two small APIs. `api.mfa.com.mt` is a thin
read layer on top of **COMET** (Analyticom's FIFA Connect competition-management
system, which the MFA runs at `comet.mfa.com.mt` — all images are served from there
and ids are COMET ids). `cms.mfa.com.mt` is an Umbraco CMS with competition
branding, club pages, news and the mapping from "competition type" to the current
COMET competition. Data is the official match report: line-ups, goals, cards,
substitutions, referees, and a minute-level `matchTime` while live. There are no
shots/possession stats and no positional data.

The API serves every MFA competition (Premier, Challenge League, National Leagues,
FA Trophy, women's, youth, futsal, national teams) through the same endpoints. This
document verifies **competitionTypeId 58539 — "VBET Malta Premier"**. The
`matches/*` endpoints are competition-agnostic, so the others should work
identically.

All samples in [`samples/`](samples/) were captured on **2026-09-11** (season
2026/27, between matchday 4 and 5). See [`samples/_meta.md`](samples/_meta.md).

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Competition type (the "league") | int | `58539` Malta Premier · `2601248` Challenge League · `58545` FA Trophy · `58546` Super Cup · `58570` Women's League | `GET cms…/competitions/getCompetitionItemStub` → `competitionTypeId` |
| Competition (a COMET competition = one phase/round of a season) | int as string | `"53109748"` "vbet Malta Premier 26/27 – Opening Round – Phase 1" | `standings.phases[].groups[].id`, `match.competitionId`, `GET /competitions` (`competitionFifaId`) |
| Season | int = **end year** of the season | `2027` = 2026/27 | `GET /competitions/{type}/seasons`; `season` on the CMS competition |
| Match | int (COMET match id) | `53142564` | match lists, field `id` |
| Team / club | int (COMET id, stable) | `39639` Hamrun Spartans, `39648` Valletta | `GET /competitions/{type}/teams` |
| Player | int (COMET person id) | `162467` | line-ups, events, `teams/{id}/players` |
| Referee | id as string | `"92720"` | `match.referees[]` |
| Venue | int | `1839` Centenary Stadium | `match.venue` |
| Event | int | `53309666` | `result.events[]` |

**Season structure (2025/26 as observed in [`standings.season-2026.json`](samples/standings.season-2026.json)):**
Opening Round Phase 1 (12 teams) → Opening Round Phase 2 (Top 6 / Bottom 6) →
Closing Round Phase 1 → Closing Round Phase 2 (Top 6 / Bottom 6) → Final Four
(competition type `1240053`) and Relegation Decider (`58575`), which the CMS lists
as `competitionsTypes` of the Premier. Each phase is its own COMET competition id;
matches carry the phase's `competitionId` and `competitionIsMultiplePhase: true`.

**Clubs 2026/27 (12):** Balzan 39612, Birkirkara 39636, Birzebbuga St.Peter's 40417,
Floriana 39637, Gzira United 39615, Hamrun Spartans 39639, Hibernians 39640,
Marsaxlokk 39617, Mosta 39606, Sliema Wanderers 39646, Valletta 39648,
Zabbar St.Patrick 40419 (see [`teams.json`](samples/teams.json)).

## Discovery path

1. **Competition ids:** `GET https://cms.mfa.com.mt/api/competitions/getCompetitionItemStub`
   → find the entry with `title: "VBET Malta Premier"` → `competitionTypeId` 58539,
   `season` "2027", `gameWeek`.
2. **Fixtures:** `GET /competitions/58539/upcomingMatches?pageSize=20` (or
   `?date=YYYY-MM-DD`). **Results:** `GET /competitions/58539/pastMatches?pageSize=20`.
   **Live:** `GET /competitions/58539/matches?status=live`. Each item: `id`,
   `startDate` (UTC), teams, `status`, `isLive`, `matchTime`, `venue`, `referees`.
3. **Score + events:** `GET /matches/{id}/result` → `homeScore`, `awayScore`,
   `matchTime`, `isLive`, `events[]` (goals, penalties, cards, substitutions).
4. **Line-ups:** `GET /matches/{id}/lineup` (empty `{}` per team until published).
5. **Standings:** GET /competitions/58539/standings. The former squad route is not
   team-scoped and is out of scope. **Player:** GET /players/{id}/GetPlayerDetails.

Recommended poll interval for live matches: **15–20 s** on `matches/{id}/result`
(no `Cache-Control` is sent; weak `ETag`s are and `If-None-Match` → `304` works, so
poll conditionally). Poll `competitions/{type}/matches?status=live` every 60 s to
discover matches going live. Do not poll `PLAYED` matches.

## Endpoints

All `api.mfa.com.mt` calls below were made with `x-version: 2`.

### `GET /competitions/{type}/upcomingMatches` &nbsp;·&nbsp; `GET /competitions/{type}/pastMatches`

| | |
|---|---|
| **Purpose** | Fixtures / results, newest-first for past, soonest-first for upcoming. |
| **Parameters** | `pageSize` (default 8), `page` (1-based; total pages in the `PageCount` response header), `date=YYYY-MM-DD`, `teamId`, `season` |
| **Samples** | [`upcomingMatches.json`](samples/upcomingMatches.json) · [`pastMatches.json`](samples/pastMatches.json) · [`pastMatches.page2.json`](samples/pastMatches.page2.json) · [`pastMatches.date.json`](samples/pastMatches.date.json) · [`upcomingMatches.date.json`](samples/upcomingMatches.date.json) · [`pastMatches.team.json`](samples/pastMatches.team.json) · [`pastMatches.season-2026.json`](samples/pastMatches.season-2026.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | No `Cache-Control`; weak `ETag` |

**Response shape** — array of match objects:

```
id, startDate "2026-09-06T18:30:00.0000000Z"  (UTC, 7 fractional digits)
homeTeam / awayTeam { id, name, image }        – image = COMET crest URL
referees[] { id (string), name "SURNAME Given", role "Referee"|"1st Assistant Referee"|"2nd Assistant Referee"|"4th Official"… }
venue { id, name "Centenary Stadium, Ta' Qali", images[] }
status "SCHEDULED"|"PLAYED"|…, isLive, matchTime "FT"|…  (matchTime absent before kick-off)
isLineupAvailable, competitionId "53109748", competitionTypeId 58539, competitionName (often "" in lists), competitionIsMultiplePhase
```

**No scores in the lists** — fetch `/matches/{id}/result` for each match, or take
the score from `standings` for finished rounds. `date=` filters on the UTC date.
`teamId=` works on `pastMatches` but makes `upcomingMatches` answer **400** ("Object
reference not set to an instance of an object", checked 2026-09-16); `upcomingMatches`
only ever lists the next round (six games), so a club's fixtures are read league-wide and
filtered.
`season=2026` returns last season's matches with `competitionName: ""`.

### `GET /competitions/{type}/matches?status=live`

| | |
|---|---|
| **Purpose** | The site's "live now" list. Also accepts `status=upcoming|past`, `teamId`, `season`, `pageSize`, `page`, but for this competition every variant returned `[]` on the capture day (no live match) — only `status=live` is the intended use. |
| **Sample** | [`matches.live-none.json`](samples/matches.live-none.json) (`[]`, `PageCount: 0`) |
| **Last verified** | 2026-09-11 (empty result only) |

### `GET /matches/{id}`

| | |
|---|---|
| **Purpose** | Match header: teams, kick-off, venue, referees, status. Same object as the list items, plus referee `image` and the full `competitionName`. |
| **Samples** | [`match.final.json`](samples/match.final.json) · [`match.pre.json`](samples/match.pre.json) · [`match.live-first-half.json`](samples/match.live-first-half.json) · [`match.halftime.json`](samples/match.halftime.json) · [`match.live.json`](samples/match.live.json) · [`match.live-stoppage.json`](samples/match.live-stoppage.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | weak `ETag`, `If-None-Match` → `304` ✔ |

Unknown id → `404` with an empty body.

### `GET /matches/{id}/result`

| | |
|---|---|
| **Purpose** | **The live endpoint**: score, penalties, match clock label and the event list. |
| **Samples** | [`result.final.json`](samples/result.final.json) · [`result.pre.json`](samples/result.pre.json) · [`result.live-first-half.json`](samples/result.live-first-half.json) · [`result.halftime.json`](samples/result.halftime.json) · [`result.live.json`](samples/result.live.json) · [`result.live-stoppage.json`](samples/result.live-stoppage.json) |
| **Last verified** | 2026-09-11 |

```
id, status "SCHEDULED"|"RUNNING"|"PLAYED", isLive, matchTime "FT"|"HT"|"52'"|"90+4'"  (absent pre-match)
homeScore "1", awayScore "2", homePenalties "0", awayPenalties "0"     – strings; "0" pre-match
events[] { id, type, team "HOME"|"AWAY", time "16'"|"90+3'", playerName, playerId,
           playerNameTwo?, playerIdTwo? }   – for SUBSTITUTION: playerName = on, playerNameTwo = off
```

**Observed `events[].type`** *(may be incomplete)*: `GOAL`, `PENALTY` (a scored
penalty — counts as a goal), `SUBSTITUTION`, `YELLOW` (seen in `lineup` events).
Expected: `RED`, `SECOND_YELLOW`, `OWN_GOAL`, `MISSED_PENALTY`. Events carry
**no period, no seconds and no running score** — order them by array position
(they are chronological) and recompute the score from `GOAL`/`PENALTY`/`OWN_GOAL`.

### `GET /matches/{id}/lineup`

| | |
|---|---|
| **Purpose** | Both squads for the match with per-player events. |
| **Samples** | [`lineup.final.json`](samples/lineup.final.json) · [`lineup.pre.json`](samples/lineup.pre.json) (`{"homeTeam": {}, "awayTeam": {}}` until published) |
| **Last verified** | 2026-09-11 |

```
homeTeam / awayTeam
  coach "AGIUS Gilbert"
  players[] { id, name "Patrik MOHOROVIC", number "30", profilePhoto, startingLineup (bool),
              isCaptain, isGoalkeeper, events[] (same shape as result.events, filtered to this player) }
```

11 starters + bench (22 listed for the home side). No positions or formation
beyond `isGoalkeeper`. `match.isLineupAvailable` tells you whether to call this.

### `GET /matches/{id}/statistics`

`{ matchStatistics[], encounterStatistics[] { id, startDate, score "0 - 0", homeTeamId, awayTeamId },
seasonStatistics[] { title "Matches Played"|"Wins"|"Goals"|"Yellow Cards"|"Red Cards", home, away, total } }`.
`matchStatistics` was `[]` even for a played match — no in-match stats are published.
`encounterStatistics` is the head-to-head list (note its `score` was `"0 - 0"` for a
1–2 match: unreliable). Samples: [`statistics.final.json`](samples/statistics.final.json) · [`statistics.pre.json`](samples/statistics.pre.json). Returns `204` when nothing exists.

### `GET /competitions/{type}/standings`

| | |
|---|---|
| **Purpose** | League table(s), grouped by phase. |
| **Parameters** | `season` (end year) |
| **Samples** | [`standings.json`](samples/standings.json) (2026/27, one phase so far) · [`standings.season-2026.json`](samples/standings.season-2026.json) (2025/26, 4 phases / 6 groups) |
| **Last verified** | 2026-09-11 |

```
competitionTypeId, competitionTypeName, gameWeek
phases[] { competitionTypeId (0), groups[] { id (COMET competition id), competitionName, competitionTypeName,
           data[] { position, club{id, name, image, isLive}, matchesPlayed, wins, draws, losses, goalDifference, points,
                    nextMatch{id, name, image} (the opponent) } } }
```

No goals for/against — only `goalDifference`. Phases are in chronological order;
the current table is the last group of the last phase (or the two Top 6 / Bottom
6 groups once Phase 2 starts).

### `GET /competitions/{type}/teams`

`[ { competitionTypeId, competitionTypeName, data[] { id, name, image, isLive } } ]`
([sample](samples/teams.json)). `isLive` flags clubs currently playing — a cheap
"anything live?" probe.

### `GET /teams/{teamId}/players?competitionTypeId={type}`

The September 2026 capture returned a team-shaped list, but a 2026-09-14 verification
for Hamrun (39639) returned **364 league-wide players**. The endpoint ignores both its
path team id and tested season / competitionId query parameters. It is not used by the
core and Malta Premier does not advertise ROSTER; the historical
[sample](samples/team-players.json) remains only as evidence of the earlier shape.

### `GET /players/{id}/GetPlayerDetails`

`{ id, playerName "TROTTA Marcello", profilePhoto, team{id, name, image}, history[],
details[] { id, title, value } }` with `details` titles `InternationalFirstName`,
`InternationalLastName`, `Gender`, `Nationality`, `NationalityFIFA`, `DateOfBirth`
(`DD/MM/YYYY`), `CountryOfBirth`, `PlaceOfBirth`, `National_team`
([sample](samples/player.json)). `GetPlayerStatistics?season=-1` returned `[]`
([sample](samples/player-statistics.empty.json));
`GetPlayerSeasons` and `GetPlayerHistory` returned `404` — the player pages are
partly unpopulated.

### `GET /competitions/{type}/topScorers?pageSize=`

`[ { competitionTypeId, competitionTypeName, data[] { id, name, profilePhoto, team{…}, goals } } ]`
([sample](samples/topScorers.json)). `season` accepted.

### `GET /competitions/{type}/seasons`

`[ {id 2016, title 2016}, …, {id 2027, title 2027} ]` ([sample](samples/seasons.json)).

### `GET /competitions`

Every COMET competition the MFA has (2,306 rows, **955 KB**) with
`competitionFifaId`, `internationalName`, `season`, `status ACTIVE|INACTIVE`,
`competitionTypeId`, `ageCategory`, `gender`, `discipline`, `dateFrom/To`.
[Sample truncated](samples/competitions.json) to the Malta Premier rows. Use it to
map a `competitionId` seen on a match to its phase name.

### CMS endpoints (`cms.mfa.com.mt/api`)

| Endpoint | Sample | Notes |
|---|---|---|
| `GET /competitions/getCompetitionItemStub` | [cms-competitions.json](samples/cms-competitions.json) (truncated to 8 of 51) | All competitions with `competitionTypeId`, `title`, `season`, `gameWeek`, `isCup`, `liveMatches`, `type` (Seniors/Women/Youth/Futsal/Hide), logos, banners. `Cache-Control: no-cache` |
| `GET /competitions/{type}` | [cms-competition.json](samples/cms-competition.json) | Branding + description HTML + `competitionsTypes` (related deciders) + `isMultiplePhases`. `max-age=1800` |
| `GET /teams/{teamId}` | [cms-team.json](samples/cms-team.json) | Club page: logo, colours, images, `competitionTypes[]`, website, social. `max-age=900` |
| `GET /matches/featured` | — | Homepage carousel (33 KB of banners). Not sampled |
| `GET /news/*`, `/sponsors`, `/navigations`, `/pages` | — | Site content, ignore |

### Other

- `GET /matches/{id}/channel` → `{id, channel ""}` — TV broadcaster name (`"MFA"` =
  streamed on live.mfa.com.mt). [Sample](samples/channel.final.json).
- `GET /polls/{id}/GetPlayerOfTheMatch` → `204` when no poll.
- `GET /nationalTeams/*` — Malta national team fixtures/results/competitions;
  same shapes; not sampled.

## Game states

`status` *(observed: `SCHEDULED`, `RUNNING`, `PLAYED`; the client also handles `LIVE`,
which this feed has never been seen to send)*:

| `status` / `isLive` | `matchTime` | Core `GameState` |
|---|---|---|
| `SCHEDULED`, `isLive: false` | absent | `SCHEDULED` |
| `SCHEDULED`, `isLive: true` | absent, then `"3'"` | `LIVE` - `isLive` leads `status` at kick-off |
| `RUNNING`, `isLive: true` | `"12'"`, `"45+8'"`, `"52'"` | `LIVE` |
| `RUNNING`, `isLive: true` | `"HT"` | `INTERMISSION` |
| `RUNNING`, `isLive: **false**` | `"90+4'"` | `LIVE` - `isLive` drops before the whistle |
| `PLAYED`, `isLive: false` | `"FT"` | `FINAL` |
| postponed / abandoned | — | *unknown — not observed; COMET statuses include `POSTPONED`, `CANCELLED`, `ABANDONED`* |

Observed end to end on 2026-09-13 (match 53142621, Valletta 2-2 Balzan, 656 polls
at 15 s): `SCHEDULED` → `LIVE` → `INTERMISSION` → `LIVE` → `FINAL`, with a goal in first-half
stoppage time. The live status is `RUNNING`, not the `LIVE` the client also accepts.

What changes SCHEDULED → PLAYED: `matchTime` appears (`"FT"`), `isLineupAvailable`
flips, `result` gains scores + `events`, `lineup` fills, `standings` updates.
`competitionName` on the single-match endpoint is filled once played (was `""`
pre-match in lists).

## Quirks & gotchas

- **Two hosts.** Competition ids and branding come from the CMS; matches, tables and
  people from the data API. The CMS `competitionTypeId` is the key between them.
- **`x-version: 2`** is what the site sends. Responses were byte-identical without it
  or with `1`, but it is whitelisted in the CORS preflight, so it costs nothing.
- **Timestamps are UTC** with seven fractional digits (`.0000000Z`) — some parsers
  choke on > 6; trim. Malta is `Europe/Malta` (UTC+1/+2).
- **Season = end year** (`2027` = 2026/27). `seasons` goes back to 2016.
- **Scores are strings** (`"1"`), and `"0"`/`"0"` before kick-off — check `status`,
  not the score, for "not started".
- **No scores in match lists.** One `result` call per match; there is no batch.
- **`status` and `isLive` disagree at both ends of a match, in opposite directions.** At
  kick-off `isLive` turns true while `status` still reads `SCHEDULED` (12 polls in the
  2026-09-13 capture); in stoppage time `isLive` goes back to **false** while `status` still
  reads `RUNNING` (12 polls, 90+4' to the whistle). Neither field alone carries a live match,
  so treat a match as live if **either** says so. Taking `isLive` alone froze the score for
  the last four minutes, because a non-live state stops the score poll.
- **Events have minute labels only** (`"90+3'"`), no period, no seconds, no running
  score, no event for kick-off/half-time/full-time. `SUBSTITUTION`: `playerName` is
  the player coming **on**, `playerNameTwo` the one going off.
- **Roster endpoint is unscoped.** On 2026-09-14, teams/39639/players returned 364
  league-wide players and ignored season and competitionId. Do not use it for a team
  page or fan out into player-detail calls; the core disables ROSTER.
- **`/competitions/{type}/matches` returned `[]`** for every `status`/`season`/`teamId`
  combination on a non-match day. Use `pastMatches`/`upcomingMatches` for lists and
  `?status=live` only for live discovery. (`PageCount: 0` header confirms "no rows",
  not an error.)
- **Standings have no goals for/against**, only `goalDifference`; `nextMatch` is the
  next opponent, not a match id.
- **No `Cache-Control`** on the data API at all; weak `ETag`s with working `304`.
  CMS endpoints have `max-age=900–1800`.
- **`competitions/standings?competitionTypeId=`** (the site's cup-bracket call)
  → `404` for both the league and the FA Trophy on the capture day; cup brackets
  are unverified.
- **Images** are COMET file URLs (`https://comet.mfa.com.mt/file?id=<uuid>`); some
  referees have `file?id=` with an empty id. Do not commit them.
- **Rate limiting:** none observed across ~60 requests.

## Out of scope

- `live.mfa.com.mt` — video streaming (needs an account).
- `cms.mfa.com.mt/api/news/*`, `/sponsors`, `/advertisement`, `/highlights`
  (YouTube) — content, not data.
- `polls` `POST` — voting.
- `comet.mfa.com.mt` itself — the COMET UI requires a login; only `/file?id=` is
  public (images).

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | CMS `getCompetitionItemStub`, `/competitions/{type}/seasons` | `competitionTypeId`, `season` | Stage = COMET phase (`competitionId`); phase names only via `standings` or `/competitions` |
| Game (id, teams, start time) | `upcomingMatches` / `pastMatches` | `id`, `homeTeam`, `awayTeam`, `startDate` (UTC), `venue` | Crest URLs included |
| GameState | match / result | `status`, `isLive`, `matchTime` | Live values observed 2026-09-13; a live match is one where `status` is `RUNNING`/`LIVE` **or** `isLive` is true |
| Score by period | — | — | **Gap:** no half-time score; derive from event minutes ≤ 45 |
| Clock / period | `result.matchTime` | `"12'"`, `"HT"`, `"FT"` | **Gap:** minute label only; no seconds, no period start time |
| GameEvent | `result.events[]` | `type`, `time`, `team`, `playerId`, `playerIdTwo` | Chronological array; no assists, no period, no running score |
| Lineups | `lineup` | `players[]` (`startingLineup`, `isCaptain`, `isGoalkeeper`), `coach` | No positions/formation |
| Team | `competitions/{type}/teams`, CMS `teams/{id}` | `id`, `name`, `image`; colours + images from CMS | No abbreviation — derive |
| Player | `players/{id}/GetPlayerDetails` | `details[]` key/value | DOB is `DD/MM/YYYY`; no height/weight/position |
| Standings | `competitions/{type}/standings` | `phases[].groups[].data[]` | Multi-phase; no GF/GA |
| Officials | match `referees[]` | `name`, `role` | Available pre-match |

## TODO

- [ ] Capture live samples: `matches?status=live`, `matches/{id}/result` (the
      `matchTime` format and `status` value while live), `teams` (`isLive`), and
      `lineup` once published. Next matches: 2026-09-11 18:30Z, 2026-09-12 16:00Z
      and 18:30Z, 2026-09-13 16:00Z and 18:30Z.
- [ ] Observe `RED`/second-yellow/own-goal/missed-penalty event types.
- [ ] Observe a postponed or abandoned match.
- [ ] Verify the cup bracket endpoint on a cup match day (FA Trophy, type `58545`).
- [ ] Check `matches?status=upcoming|past` again on a match day — they may only
      return rows for the current matchday.

## Changelog

| Date | Change |
|---|---|
| 2026-09-11 | Initial mapping. Endpoint map extracted from the match-centre client bundle; 25 endpoints verified; 29 samples (scheduled + played, standings incl. multi-phase 2025/26, squads, players, CMS metadata). |
| 2026-09-14 | Disabled roster capability: the documented team-player route returns the whole league, not a club squad. |
| 2026-09-25 | Live states captured from the 2026-09-13 recording of match 53142621 (8 samples). Live `status` is `RUNNING`, never the `LIVE` the client also accepts; `status` and `isLive` disagree at both ends of a match, which had mapped stoppage time to `UNKNOWN` and frozen the score. |
