# Ligue 1 API (LFP / ligue1.com — `ma-api.ligue1.fr`)

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | France |
| **Official site** | https://ligue1.com (Ligue de Football Professionnel) |
| **Base URL** | `https://ma-api.ligue1.fr` (no version prefix) |
| **Auth** | None for everything documented here. The site's client adds `Authorization: Bearer <Auth0 JWT>` only when a fan is logged in (votes, opt-ins, "view-as") |
| **Format** | JSON (UTF-8), Express behind nginx (`X-Powered-By: Express`, `hostname: l1api01`) |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *`; `OPTIONS` preflight `204` with `Access-Control-Allow-Methods: GET,HEAD,PUT,PATCH,POST,DELETE` and the requested headers echoed back (`If-None-Match` verified) |
| **WAF / UA requirement** | None. Works with no `User-Agent`, no `Accept`, none of the site's custom headers |
| **Conditional requests** | Weak `ETag` on every response; `If-None-Match` → `304` verified. No `Cache-Control`, no `Last-Modified`, no CDN in front |
| **Last full verification** | 2026-09-11 |
| **Status** | ✅ verified in every regular-season state: pre-match, line-ups published, first half, half-time, second half, full time (Rennes 1–0 OM, 2026-09-11, polled every 20 s from 18:33Z to 20:42Z). Extra time / shoot-out / postponed states not yet observed |

## Overview

ligue1.com is a Next.js/React-Native-Web app built by **MPG** (Mon Petit Gazon, the
fantasy-football company the LFP partnered with in 2024). Its data layer is a plain
REST API at `ma-api.ligue1.fr` whose data is Opta/Stats Perform (`shortOptaId`,
`optaUuid`, per-player Opta stat keys such as `accurate_pass`) plus Second Spectrum
physical metrics (`total_distance`, `count_high_speed_running`) and LFP metadata
(officials, delegates, stadiums, broadcasters, Ligue 1+ streaming ids).

The same API — same endpoints, same shapes — also serves **Ligue 2** (championship
`4`), **National** (`11`), **Coupe de France** (`10`), **Trophée des Champions**
(`15`), the promotion **play-offs** (`16`) and the three UEFA club competitions
(`6`/`13`/`14`, all 36 clubs, not only the French ones). Verified for 1, 4, 6, 10, 15.

What it gives us: fixtures with UTC kick-offs, a single match resource carrying
score, period, minute, **second-precision period start/end timestamps**, goals
(with assist and VAR decision), cards (with reason), substitutions, penalty
shoot-outs, full line-ups with formation slot and per-player Opta stats and a live
rating, team stats incl. xG, officials, stadium and attendance, a merged event
`timeline`, a **live-updating** league table (general/home/away, any game-week
range), scorer/assist charts, 14 player and 20 club "advanced" leaderboards, club
and player histories back to 1993, and a cross-competition "matches by date" feed.
There is no WebSocket/SSE for scores — the site polls the match resource and the
standings every **60 s** (`refetchInterval: 6e4`, `staleTime: 3e5`).

All samples in [`samples/`](samples/) were captured on **2026-09-11** (season 2026/27,
game-week 4, including a full live series of Rennes–OM). See [`samples/_meta.md`](samples/_meta.md).

## Identifiers

Ids are namespaced strings, **season-scoped for clubs and players**:

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Championship (competition) | small integer | `1` Ligue 1 · `4` Ligue 2 · `11` National · `10` Coupe de France · `15` Trophée des Champions · `16` play-offs · `6` UCL · `13` UEL · `14` UECL | `GET /championships-settings` (also gives `optaId`/`optaUuid`, `clubsQuantity`, `gameWeeks`, `season`, `seasonsHistory`) |
| Season | integer = **start year** (`2026` = 2026/27) | `2026` | `championships-settings.championships.1.season`; `?season=` on most list endpoints |
| Game-week | integer 1…34 (L1), 1…38 (L2) | `4` | `GET /championship-calendar/{championshipId}/nearest-game-weeks` |
| Match | `l1_championship_match_<n>` | `l1_championship_match_73855` (Rennes–OM, GW 4) | any match list, field `matchId`. `<n>` is an LFP id for L1/L2, and the **Opta match id** for cups/UEFA (`l1_championship_match_2636517`, `shortOptaId: 2636517`) |
| Club | `l1_championship_club_<season>_<shortId>` | `l1_championship_club_2026_13` (PSG, `shortId` 13, `shortOptaId` 149) | match `home.clubId`; `GET /championship-clubs`. Non-French clubs use `shortId` ≥ 9 000 000 000 (`…_2026_9000002238`) |
| Player | `l1_championship_player_<season>_<clubShortId>_<shortLfpId>` | `l1_championship_player_2026_13_77349` (Chevalier, `shortOptaId` 485335) | line-ups (`home.playersIds[]`), rankings, `championship-players-pool` |
| Stadium | `l1_stadium_<n>` | `l1_stadium_20` (Parc des Princes) | `championship-club.stadiumId`, match `stadium.id` |
| Manager / official | bare integer | `40073` (Luis Enrique) | match `home.manager.id`, `officials[].id` |
| Event | Opta event id string | `"2964138035"` | `home.goals[].eventId` (goals only; cards/subs have no id — key them on `side+type+timestamp`) |

Because club and player ids embed the season **and the club**, the same person has a
different id every season and after a transfer (`championship-player-history` links
them via `currentPlayerId`; `shortOptaId`/`shortLfpId` are the stable keys).

Match ids are **not** validated against the season — passing a bare number
(`/championship-match/73845`) returns `400` `ZodError`; an unknown id returns
`404 {"message":"championshipMatchNotFound"}`; an unknown game-week `404 gameWeekNotFound`.

## Discovery path

1. **Competitions & current season:** `GET /championships-settings` → `championships[id]`
   with `season`, `firstGameWeekNumber`/`lastGameWeekNumber`, `gameWeeks`, `clubsQuantity`.
   Cache for a day.
2. **Which game-week is on:** `GET /championship-calendar/1/nearest-game-weeks` →
   `previousGameWeek` / `currentGameWeek` / `nextGameWeek`, each with `matchesIds[]`,
   `startDate`, `endDate`, `displayEndDate`. Or the whole season:
   `GET /championship-calendar/1` (`gameWeeks{1..34}`).
3. **Fixtures with scores:** `GET /championship-matches/championship/1/current`
   (the game-week the site is showing) or `…/game-week/{n}` — 9 match summaries with
   `date` (UTC), `period`, `isLive`, `matchTime`, `home/away.score`, club identities,
   broadcasters. Across all competitions by day:
   `GET /championships-daily-calendars/matches?timezone=UTC&daysLimit=3&lookAfter=true`.
4. **Live state, events, line-ups, stats — one call:**
   `GET /championship-match/{matchId}` (150–500 KB). Everything a score app needs is
   in here; there is no lighter "header" resource.
5. **Standings:** `GET /championship-standings/1/general` (`home`/`away` variants,
   `?season=`, `?firstGameWeekNumber=&lastGameWeekNumber=`). Updated **live** while
   matches are in play.
6. **Club / player pages:** `GET /championship-club/{clubId}` + `…/identity`,
   `GET /championship-club-summary/{clubId}` (squad, fixtures, results),
   `GET /championship-player/{playerId}` + `championship-player-stats` /
   `-advanced-stats` / `-history`.

Recommended poll interval for a live match: **20–30 s** on `/championship-match/{id}`
(the site uses 60 s). There is no edge cache — every request hits the origin and the
body is regenerated (the `ETag` changes on almost every live request, see quirks), so
`If-None-Match` only saves bandwidth outside live matches. Observed latency between
the event's own `timestamp`/`periodsDates` stamp and its appearance in the response
(20 s polling): kick-off ≤ 25 s, half-time 17–38 s, second-half start ≤ 17 s, full
time 21–27 s, yellow cards 6–26 s, **goal 30–50 s** (Opta confirmation), substitutions
≤ 40 s.

## Endpoints

All paths relative to `https://ma-api.ligue1.fr`. No headers are required. The site
sends `application: ligue1`, `platform: web`, `client-version`, `timezone` (IANA) and
`client-language` (`fr-FR`/`en-GB`/`es-ES`) — none of them changed any response we
compared (localised strings come back as `{ "en-GB", "es-ES", "fr-FR" }` maps, booking
reasons are French-only).

### Competition & calendar

#### `GET /championships-settings`

| | |
|---|---|
| **Purpose** | Every competition the API knows, with current season and game-week bounds |
| **Sample** | [`samples/championships-settings.json`](samples/championships-settings.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

`{ championships: { "1": { name, shortName, shortBrand, code ("fr_l1"), competitionType
("championship" / "cup" / "mixed"), season, firstSeason (1993), seasonsHistory[],
gameWeeks, firstGameWeekNumber, lastGameWeekNumber, clubsQuantity, optaId, optaUuid,
isLfpChampionship, lang[], logoUrl, color, l1Plus* ids, votes{…}, startDate/endDate
(cups) } … } }`. `GET /championships-order` ([sample](samples/championships-order.json))
is the site's nav order (`championshipId`, `url`, `active`).

#### `GET /championship-calendar/{championshipId}`

| | |
|---|---|
| **Purpose** | All game-weeks of a season with their match ids and date window |
| **Parameters** | `season` (start year; default current) |
| **Sample** | [`samples/championship-calendar.json`](samples/championship-calendar.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 (also `?season=2025`) |

`{ gameWeeks: { "1": { gameWeekNumber, matchesIds[9], startDate, endDate,
lastRegularMatchDate, displayEndDate } … } }`. Dates are UTC kick-offs of the first and
last match; `displayEndDate` is when the site stops showing the round (Tuesday 07:00Z).

#### `GET /championship-calendar/{championshipId}/nearest-game-weeks`

| | |
|---|---|
| **Purpose** | Previous / current / next game-week objects (same shape as above) |
| **Sample** | [`samples/championship-calendar-nearest-game-weeks.json`](samples/championship-calendar-nearest-game-weeks.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

#### `GET /championships-daily-calendars/matches`

| | |
|---|---|
| **Purpose** | Matches of **all competitions** grouped by calendar day — the site's home-page scores strip |
| **Parameters** | `timezone` (**required**, IANA name; `400 ZodError "Invalid timezone"` otherwise) · `daysLimit` (number of days with matches to return) · `lookAfter` (`true` = forward from `fromDate`, `false` = backward) · `fromDate` (`YYYY-MM-DD`, default today) |
| **Sample** | [`samples/championships-daily-calendars-matches.json`](samples/championships-daily-calendars-matches.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

`{ fromDate, lookAfter, daysLimit, previousDate, nextDate, results: { byDate: {
"2026-09-12": { "<championshipId>": { "<gameWeek>": { matchesIds[] } } } }, matches: {
"<matchId>": <match summary> } } }`. Page with `fromDate = nextDate`/`previousDate`.
Days are computed in the given `timezone`.

### Matches

#### `GET /championship-matches/championship/{championshipId}/current`

| | |
|---|---|
| **Purpose** | Match summaries of the game-week the site currently shows (switches to the next round on `displayEndDate`) |
| **Sample** | [`samples/championship-matches-current.pre.json`](samples/championship-matches-current.pre.json) (GW 4 before kick-off) · [`samples/championship-matches-current.live.json`](samples/championship-matches-current.live.json) (Rennes–OM `secondHalf` 1-0, `matchTime: "52'"`) · [`samples/championship-matches-current.final.json`](samples/championship-matches-current.final.json) (`fullTime`, `"90' +4"`) · [`samples/championship-matches-current.ucl.json`](samples/championship-matches-current.ucl.json) (championship 6, 18 matches, `roundType: "round"`) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

`{ currentMatches: [ … ] }`. Each summary: `matchId`, `championshipId`, `gameWeekNumber`,
`date` (UTC ISO), `period`, `isLive`, `matchTime` (only while live/after), `home`/`away`
`{ clubId, clubIdentity, score (absent before kick-off) }`, `broadcasters { local[],
international[] }`, `poster`, `l1Plus { live { playId, state, startAt, liveAt } }`,
`unknownMatch`, and for cups `roundType`. Sorted by kick-off. No line-ups or events.

#### `GET /championship-matches/championship/{championshipId}/game-week/{n}`

| | |
|---|---|
| **Purpose** | Same summaries for a specific round |
| **Parameters** | `season` |
| **Sample** | [`samples/championship-matches-game-week.pre.json`](samples/championship-matches-game-week.pre.json) (GW 4) · [`samples/championship-matches-game-week.final.json`](samples/championship-matches-game-week.final.json) (GW 3, all `fullTime`) captured 2026-09-11 |
| **Last verified** | 2026-09-11 (also `?season=2025`, `?season=2024`) |

`{ matches: [ … ] }` — note the different top-level key from `current`. Unknown round → `404 gameWeekNotFound`.

#### `GET /championship-match/{matchId}`

| | |
|---|---|
| **Purpose** | **The match resource** — state, clock, score, events, line-ups, stats, officials, venue |
| **Sample** | [`samples/championship-match.pre.json`](samples/championship-match.pre.json) (`preMatch`, no line-ups — Ligue 2 match) · [`samples/championship-match.pre-lineups.json`](samples/championship-match.pre-lineups.json) (`preMatchWithPlayers`, 12 min before kick-off) · [`samples/championship-match.live-kickoff.json`](samples/championship-match.live-kickoff.json) (`firstHalf`, 0-0, 25 s after kick-off) · [`samples/championship-match.live-halftime.json`](samples/championship-match.live-halftime.json) (`halfTime`, `"45' +1"`, 3 cards + 1 sub) · [`samples/championship-match.live.json`](samples/championship-match.live.json) (`secondHalf`, 1-0, 50 s after the goal) · [`samples/championship-match.final-fresh.json`](samples/championship-match.final-fresh.json) (`fullTime` 27 s after the whistle, before post-processing) · [`samples/championship-match.final.json`](samples/championship-match.final.json) (PSG–Monaco 1-2 a week later, post-processed) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

**Response shape** (top level): `id`, `type`, `shortOptaId`, `championshipId`,
`season`, `gameWeekNumber`, `date` (scheduled kick-off, UTC), `period`,
`periodsDates`, `home`, `away`, `stadium { id, name, address, capacity, attendance,
googleMapsData }`, `officials[] { id, name, type }`, `delegates[]`, `broadcasters`,
`videos`, `poster`, `userOptins`, `l1Plus`, and for cups `round { number, type }`.
Keys that **appear once the match has started**: `startDate` (= `firstHalfStartedAt`),
`matchTime`, `timeline[]`, `preGameQuotations { home, draw, away }` (odds).

- **`periodsDates`** — `{}` before kick-off, then `firstHalfStartedAt`,
  `firstHalfEndedAt`, `secondHalfStartedAt`, `secondHalfEndedAt` (extra-time keys
  presumably `extraFirstHalf…`, unverified) as **second-precision UTC** timestamps.
  This is the clock source: `elapsed = now − <current period>StartedAt`.
- **`matchTime`** — string `"35'"` / `"45' +1"` / `"90' +4"`. It is
  `floor(elapsed minutes since the period started)` (+45 in the second half; flipped
  `2'`→`3'` at 3:05 elapsed, `45'`→`46'` at 46:09), i.e. one less than the conventional
  "minute in progress". Event `time` strings use the **conventional** minute instead
  (a card stamped at 32:15 elapsed is `"33'"` while `matchTime` read `"32'"`). Frozen
  at the final value after full time.
- **`home` / `away`** — `clubId`, `clubIdentity`, `score` (absent pre-match),
  `shootOutScore` (cups), `manager { id, firstName, lastName, knownName }`,
  `formation` (`"41212"`, digits per line), `playersIds[]` (starters first, in
  `formationPlace` order), `players { <id>: … }`, `goals[]`, `canceledGoals[]`,
  `missedPenalties[]`, `substitutions[]`, `bookings[]`, `penaltyShots[]`, `stats`,
  `bestPlayersIdsByStats`, `clubStanding { rank }`, `shortClubOptaId`, `assets`,
  `clubWebsiteLink`. Before line-ups are published only `playersIds`/`players`
  (the whole squad, `playedMatch: false`) and `stats` exist — no `score`, `goals`… keys.
- **Events** (all with `side`, `time` `"31'"` / `"90' +4"`, `timestamp` epoch ms UTC):
  `goals[] { eventId, type ("goal" | "own" | "penalty"), scorerId, assistProviderId,
  varDecision }`, `bookings[] { eventId, playerId, type, reason }`,
  `substitutions[] { subOffId, subOnId }`, `missedPenalties[]`, `canceledGoals[]`,
  `penaltyShots[]` (shoot-outs). **Live vs post-processed:** while the match is on and
  for at least 5 min after, `bookings[].reason` is an Opta code (`"foul"`,
  `"argument"`), bookings carry `eventId`, and `assistProviderId` is *absent* on
  goals; the week-old sample has French text reasons (`"Comportement antisportif"`),
  no `eventId` on bookings, `assistProviderId` (or `null`) on every goal and
  `stadium.attendance` filled — a post-match job rewrites the record.
- **`timeline[]`** — the same events merged in match order as `{ type, side, time,
  timestamp, scorerId | playerId | subOnId/subOffId, varDecision }`.
- **`players.<id>`** — `playerIdentity { id, shortOptaId, firstName, lastName,
  birthDate, countryName, countryShortCode, countryImageUrl, preferredFoot,
  jerseyNumber, assets { facePictures, bustPictures } }`, `position` (1–4),
  `realUltraPosition`, `formationPlace` (0 = bench), `shirtNumber`, `startedMatch`,
  `sub` (0/1), `playedMatch`, `goals`, `goalsAssists`, `ownGoals`, `wonDribbles`,
  `rating` (live Opta-style rating, updates during play), `fl1` (fantasy points),
  `stats { <opta key>: n }` (~90 keys once the match runs: `accurate_pass`,
  `total_scoring_att`, `duel_won`, `touches`, `mins_played`, `total_distance`, …).
- **`stats`** (team) — `possessionPercentage`, `passes`, `bigChances`,
  `expectedGoals`, `scoringAttempts`, `ontargetScoringAttempts`,
  `blockedScoringAttempts`, `offsides`, `corners`, `fouls`, `total_distance` (m),
  `count_high_speed_running`, `bookingsData { yellow, red }`, `minFirstRed`.
  Physical metrics (`total_distance`, `count_high_speed_running`) lag: `0` in the
  first half-hour, populated by half-time.
- **`officials[].type`**: 1 referee, 2/3 assistants, 4 fourth official, 5 VAR, 6 AVAR
  (7–13 observers/delegates in `delegates[]`).

#### `GET /championship-match/{matchId}/pre-live-stats`

| | |
|---|---|
| **Purpose** | Pre-match pack: each side's previous match, standing, best players, form; head-to-head |
| **Sample** | [`samples/championship-match-pre-live-stats.json`](samples/championship-match-pre-live-stats.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

`{ id, championshipId, gameWeekNumber, season, date, home { clubId, clubIdentity,
previousMatch, clubStandings, playersRankings, best*Ids[], mostUsedPlayersIds[],
playersData{} }, away, oppositions { matches[], matchesResults }, broadcasters, userOptins }`.

#### `GET /championship-match/{matchId}/oppositions`

| | |
|---|---|
| **Purpose** | Head-to-head history (100 matches, newest first) |
| **Parameters** | `ignoreMatchStatsOppositions=true` (site flag; effect not observed) |
| **Sample** | [`samples/championship-match-oppositions.json`](samples/championship-match-oppositions.json) (**truncated to 5 of 100**) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

#### `GET /championship-match/{matchId}/story/man-of-the-match`

| | |
|---|---|
| **Purpose** | Post-match "story" slides (MOTM with stats, key moments) |
| **Sample** | [`samples/championship-match-story-man-of-the-match.json`](samples/championship-match-story-man-of-the-match.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

#### `GET /videos/match/{matchId}`

| | |
|---|---|
| **Purpose** | Highlight/summary videos attached to a match (Strapi CMS ids, Ligue 1+ `playId`s) |
| **Sample** | [`samples/videos-match.json`](samples/videos-match.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

### Standings & leaderboards

#### `GET /championship-standings/{championshipId}/{general|home|away}`

| | |
|---|---|
| **Purpose** | League table, **updated live** during matches |
| **Parameters** | `season` · `firstGameWeekNumber` + `lastGameWeekNumber` (table over a range of rounds) |
| **Sample** | [`samples/championship-standings.json`](samples/championship-standings.json) · [`samples/championship-standings-home.json`](samples/championship-standings-home.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 (also `?season=2025`, `?firstGameWeekNumber=1&lastGameWeekNumber=2`, championships 4 and 6) |

`{ competitionType, season, standings: { "1": { rank, clubId, clubIdentity, points,
played, wins, draws, losses, forGoals, againstGoals, goalsDifference, forGoalsAway,
winsAway, gameWeekStartingRank, rankDelta, higherWinsInARow, yellowCards, redCards,
seasonResults[] { opponentClubId, resultLetter (w/d/l), side, score { home, away } },
allSeasonResults[] (34 slots, `null` = not played) } … } }` keyed by rank as a string.
While Rennes–OM was 0-0 in play the table already showed Rennes with `played: 4`,
`points: 8` — treat it as a live table and do not cache it as "final" mid-round.

#### `GET /championship-players-ranking/{championshipId}/{scorers|assists}`

| | |
|---|---|
| **Purpose** | Top scorers / assist providers |
| **Parameters** | `season` |
| **Sample** | [`samples/championship-players-ranking-scorers.json`](samples/championship-players-ranking-scorers.json) · [`samples/championship-players-ranking-assists.json`](samples/championship-players-ranking-assists.json) (**both truncated to the first 5** of 63 / 50) captured 2026-09-11 |
| **Last verified** | 2026-09-11 (also `?season=2025`, 878 KB) |

`{ scorers[] { rank, clubId, playerIdentity, totalGoals, totalPenaltiesScored,
totalGoalsAssists, totalScoredMatches, totalPointsWon, totalYellowCards,
totalRedCards, additionalStats { totalMinutesPlayed } }, clubsIdentities{} }`.

#### `GET /championship-players-advanced-rankings/{championshipId}/top` and `…/stat/{stat}`

| | |
|---|---|
| **Purpose** | 14 player leaderboards at once (`top`), or one full leaderboard (`stat`) |
| **Parameters** | `season` · `limit` (verified on `stat`) · `top` also accepts the site's filter params (position/club — unverified) |
| **Sample** | [`samples/championship-players-advanced-rankings-top.json`](samples/championship-players-advanced-rankings-top.json) (**each ranking truncated to 5, `playersData` to those players**; full response 531 KB) · [`samples/championship-players-advanced-rankings-stat.json`](samples/championship-players-advanced-rankings-stat.json) (`goals`, `season=2025&limit=3`) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

`{ rankings: { goals[], assists[], expectedGoals[], cleanSheets[], minutesPlayed[],
contestsWon[], shotsOnTarget[], passAccuracy[], distanceCovered[],
highIntensityRuns[], ballsTouched[], successfulPasses[], interceptions[],
successfulTackles[] } (arrays of `shortLfpId`), playersData: { "<shortLfpId>": {
identity, clubId, stats[] (positional, see `statsIndexes`), … } }, statsIndexes: {
"<statName>": <index into stats[]> } }`. `stat/{stat}` returns `{ stat, ranking[],
playersData }`; valid `stat` names are the `statsIndexes` keys (~50).

#### `GET /championship-clubs-advanced-rankings/{championshipId}/top` and `…/stat/{stat}`

| | |
|---|---|
| **Purpose** | Club leaderboards (`totalGoals`, `totalAssists`, `totalExpectedGoals`, `totalCleanSheets`, `totalDistanceCovered`, `averagePossession`, …) |
| **Sample** | [`samples/championship-clubs-advanced-rankings-top.json`](samples/championship-clubs-advanced-rankings-top.json) · [`samples/championship-clubs-advanced-rankings-stat.json`](samples/championship-clubs-advanced-rankings-stat.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

### Clubs

#### `GET /championship-clubs`

| | |
|---|---|
| **Purpose** | Every club the API knows for the current season (**155**, all competitions, keyed by id) |
| **Sample** | [`samples/championship-clubs.json`](samples/championship-clubs.json) (**truncated to 3 of 155 entries** — PSG, Rennes and one UEFA club; full response is 1.1 MB) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

`{ championshipsClubs: { "<clubId>": { id, shortId, season, shortOptaId, optaUuid,
name, officialName, shortName, displayName, businessName, acronym, knownNames[],
stadiumId, championships { "<id>": … }, manager, primaryColor, secondaryColor,
websiteLink, appLink, trophies[], remarkableStats, countryImageUrl } } }`. Filter on
`championships` to get the 18 Ligue 1 clubs. `GET /championships-clubs` (plural-plural)
is a fantasy-API route and `404`s here.

#### `GET /championship-club/{clubId}` · `…/identity` · `GET /championship-club-summary/{clubId}` · `GET /championship-club-history/{clubId}`

| | |
|---|---|
| **Purpose** | Club record · the light `clubIdentity` object (logo/colours/trigram) · club page pack (standing, stats, previous/next match, fixtures `matchesIds[]` + `matches{}`, squad by position, best players) · season-by-season finishes since 1993 (`lfpSeasons`) |
| **Parameters** | `season` on `summary` — **ignored** (identical body for 2025) |
| **Sample** | [`championship-club.json`](samples/championship-club.json) · [`championship-club-identity.json`](samples/championship-club-identity.json) · [`championship-club-summary.json`](samples/championship-club-summary.json) · [`championship-club-history.json`](samples/championship-club-history.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

`GET /championships-club-calendar/{clubId}/ics` returns the club's fixtures as an
iCalendar file (`text/calendar`, verified, not sampled).

### Players

#### `GET /championship-player/{playerId}` · `-stats` · `-advanced-stats` · `-history`

| | |
|---|---|
| **Purpose** | Identity + per-competition registration (`championships{ position, jerseyNumber, realsPositions[] }`) · season totals per competition and per-match lines (`matches{}`) · ~48 aggregated Opta stats (`stats.total`, `stats.championships`) · career by season/club (`career{year}{championship}{club}`), `trophies[]`, `currentPlayerId` |
| **Parameters** | `season` on `-stats` — **ignored** |
| **Sample** | [`championship-player.json`](samples/championship-player.json) · [`championship-player-stats.json`](samples/championship-player-stats.json) · [`championship-player-advanced-stats.json`](samples/championship-player-advanced-stats.json) · [`championship-player-history.json`](samples/championship-player-history.json) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

**Quirk:** `-advanced-stats` for a 2026 player id answered with **last season's**
object (`playerId: l1_championship_player_2025_13_77349`, `updatedAt: 2026-06-29`),
`?season=2026` included — it appears to be rebuilt at season end only.

#### `GET /championship-players-pool/{championshipId}`

| | |
|---|---|
| **Purpose** | All registered players of the competition (524 for L1) with identity, club, position and season stats |
| **Parameters** | the site passes filters (`position`, …) — `?position=1` returned the full pool unchanged |
| **Sample** | [`samples/championship-players-pool.json`](samples/championship-players-pool.json) (**truncated to 5 of 524 players**; full response 1.6 MB) captured 2026-09-11 |
| **Last verified** | 2026-09-11 |

`GET /championship-players-statuses/{championshipId}/game-week/{n}` (injuries /
suspensions per round) returned empty maps for GW 4
([sample](samples/championship-players-statuses.json)); the populated shape is unverified.

## Game states

`period` is the single state field, on match summaries and on the match resource
(`isLive` on summaries is `period ∈ {firstHalf, halfTime, secondHalf, extra…}`).
Values from the client's `matchPeriods` enum:

| `period` | Observed | Meaning |
|---|---|---|
| `preMatch` | ✅ | scheduled; no `score`, no event arrays, whole squad in `players` |
| `preMatchWithPlayers` | ✅ | line-ups published (~1–2 h before kick-off): `startedMatch`/`sub`/`formationPlace`/`formation` set, still no `score` |
| `firstHalf` | ✅ | `periodsDates.firstHalfStartedAt`, `startDate`, `matchTime`, `score: 0`, `timeline: []` appear |
| `halfTime` | ✅ | `firstHalfEndedAt` set; `matchTime` frozen at `"45' +N"`; `isLive` stays `true` |
| `secondHalf` | ✅ | `secondHalfStartedAt` set; `matchTime` resumes at `"45'"` |
| `extraFirstHalf` · `extraHalfTime` · `extraSecondHalf` | ⏳ | cups only |
| `fullTime90` | ⏳ | end of 90 min when extra time follows (cups) |
| `fullTimePens` · `ShootOut` (sic, capital S) | ⏳ | shoot-out in progress / decided (`penaltyShots[]`, `shootOutScore`) |
| `fullTime` | ✅ | final; `isLive: false`, `matchTime` `"90' +4"`, all four `periodsDates` (`secondHalfEndedAt` = whistle) |

Postponed/abandoned matches have no distinct `period` in the enum; the client has a
separate Opta `matchPeriodId` map (`101` pre-match, `109` post-match, `127`
postponed) that we have not seen surface in the API. `unknownMatch: true` marks a
fixture whose opponents are not yet known (cups).

**Timeline / event `type` values** (client enum `championshipMatchTimelineEvents`):
`regularGoal`, `penaltyGoal`, `ownGoal`, `missedPenalty`, `canceledGoal`,
`varAcceptedGoal`, `varRejectedGoal`, `yellowCard`, `redCard`,
`tacticalSubstitution`, `injurySubstitution`, `retiredSubstitution`, `penaltyShot`.
Observed: `regularGoal`, `yellowCard`, `tacticalSubstitution`. Timeline entries are
in match order (oldest first); a half-time substitution is stamped `"45'"` with the
real wall-clock `timestamp`. In the per-side arrays: `goals[].type` `goal` / `own` /
`penalty`; `bookings[].type` `yellow` / `secondYellow` / `straightRed` (`red` also in
one enum); `varDecision` 0 none / 1 accepted / 2 rejected. `position` 1 GK, 2 DF,
3 MF, 4 FW; `realUltraPosition` 10 GK, 20 CB, 21 full-back, 30 DM, 31 AM, 40 ST;
`realsPositions[]` strings (`goalkeeper`, `centralDefender`, `leftWingBack`, …,
`striker`). Cup `round.type` / `roundType`: `qualifierRound`, `round`, `playOffs`,
`knockoutRound`, `roundOf32`, `roundOf16`, `roundOf8`, `quarterFinals`,
`semiFinals`, `thirdAndFourthPlace`, `final`.

## Quirks & gotchas

- **One big match resource.** There is no light live-score endpoint: polling a live
  match means 150–300 KB per request (`players.*.stats` is most of it). The game-week
  list (`…/current`, 28 KB) carries `period`, `matchTime` and scores for all 9
  matches — poll that for a scoreboard and the full resource only for the open match.
- **Clock:** `matchTime` is `floor(minutes since <period>StartedAt)` — display
  `matchTime + 1` if you want the usual "36th minute" convention, or derive your own
  from `periodsDates`, which are exact to the second and in UTC.
- **Everything is UTC** (`date`, `periodsDates`, event `timestamp` in epoch ms,
  calendar `startDate`/`endDate`). `championships-daily-calendars` groups by the
  `timezone` you pass.
- **Body churn while live:** consecutive responses differ even with no event —
  `home.clubStanding.rank` and `away.clubStanding.rank` flip-flopped between the
  pre-match rank and the live-projected rank (5↔2, 10↔8) on the same backend host.
  Do not diff on `ETag`; diff on `period`, `score`, `matchTime` and the event arrays.
- **Standings are live** (in-play results already counted). `gameWeekStartingRank`
  and `rankDelta` give the movement.
- **No `Cache-Control`.** Responses are not cached anywhere; be a good citizen
  (≥ 20 s between polls of the same match). Weak `ETag` + `304` work for the
  static-ish resources (calendar, clubs, players).
- **Ids are season- and club-scoped**; a transferred player gets a new id
  mid-season (history endpoint has `currentPlayerId`). `championship-clubs` includes
  all 155 clubs across competitions (UEFA opponents with `shortId ≥ 9e9`).
- **`season` param is ignored** by `championship-club-summary` and
  `championship-player-stats`; `championship-player-advanced-stats` served
  last season's object.
- **Validation errors are `400` with a Zod payload** (`[{ type: "Query"|"Params",
  errors: { name: "ZodError", message } }]`); not-found is `404 { statusCode, error,
  message: "championshipMatchNotFound" | "gameWeekNotFound" }`; unknown routes return
  Express's HTML `Cannot GET …` page (`404`, `text/html`).
- **Ligue 2 / cups / UEFA** are the same API with another `championshipId`; the
  `season` for a cup is the year the edition started (`Coupe de France` 2025/26 is
  `season: 2025`), and its match ids are Opta ids.
- **Booking reasons and stadium names are French** (`"Comportement antisportif"`,
  `"PARC DES PRINCES"`); club/player names are as registered with the LFP.
- **Image assets** live on S3 (`s3.eu-west-1.amazonaws.com/image.mpg/…` for logos,
  flags, posters; `s3.eu-west-3.amazonaws.com/ligue1.image/players/…` for player
  pictures). Public, no signing.
- The site's Ligue 1+ player opens a WebSocket for video only; scores are polled.

## Out of scope

- `https://api.mpg.football` (`L1_FANTASY_API_URL`) — the fantasy game's API. Its
  `/championship-players-pool/{id}/best-players`, `/coach/…`, `/result/…`,
  `/user-ranking/…`, `/prizes-pools/…`, `/mercato/…` routes need a Bearer token or
  are fantasy-only. `/championships-settings` and `/championship-calendar/{id}` also
  exist there (same data).
- `https://is-fans-prod-fanbase-api.azurewebsites.net` (`L1_FANBASE_API_URL`) —
  fan profile (`/user/me`, opt-ins, avatars). Auth0 login.
- `ma-api.ligue1.fr` write routes: `PATCH /championship-match/{id}/vote/*`,
  `/optin/*`, `/user-matches-votes/*`, `/user/me/*` — Bearer token.
- CMS routes on `ma-api`: `/menus`, `/headers`, `/smartlist/{id}`, `/video/{id}`,
  `/article/categories`, `/international-broadcasters/v2` — public (verified `200`)
  but editorial, not needed for scores.
- `https://www.lfp.fr` — the league's corporate site, no data API found.

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `/championships-settings` | `championships.{id}.season`, `gameWeeks`, `seasonsHistory` | `season` = start year; one call covers L1, L2, cups, UEFA |
| Game (id, teams, start time) | `/championship-matches/championship/{id}/current` or `/game-week/{n}` | `matchId`, `home.clubId`, `away.clubId`, `date`, `gameWeekNumber` | UTC; venue only on the full match resource |
| GameState | match summary / `/championship-match/{id}` | `period`, `isLive` | full enum known; halfTime/secondHalf/extra/pens samples pending |
| Score | summary / match | `home.score`, `away.score`, `shootOutScore` | absent before kick-off; half-time score = count `goals[]` with `timestamp < firstHalfEndedAt` |
| Clock / period | match | `periodsDates.*`, `matchTime`, `period` | **seconds-accurate** period starts; `matchTime` is floor-minutes |
| GameEvent | match | `home/away.goals[]`, `bookings[]`, `substitutions[]`, `missedPenalties[]`, `canceledGoals[]`, `penaltyShots[]`; merged `timeline[]` | `time` string + epoch `timestamp`; assist and VAR on goals; card reason in French |
| Lineups | match | `formation`, `playersIds[]`, `players.<id>.{startedMatch, sub, formationPlace, position, shirtNumber}` | published with `preMatchWithPlayers`; no pitch coordinates (slot index only) |
| Team | `clubIdentity` anywhere; `/championship-club/{id}` | `id`, `shortId`, `shortOptaId`, `name`, `shortName`, `trigram`, `primaryColor`, `assets.logo` | id changes every season (`shortId`/`shortOptaId` stable) |
| Player | match `players`, `/championship-player/{id}` | `playerIdentity.*`, `position`, `jerseyNumber`, `birthDate`, nationality, pictures | id changes per season and club |
| Standings | `/championship-standings/{id}/general` | `standings.{rank}.*` | live; home/away splits; `allSeasonResults` form |
| Officials / venue | match | `officials[]` (type 1–6), `stadium`, `attendance` | attendance filled after the match |
| Team / player stats | match | `home.stats`, `players.<id>.stats`, `rating` | xG, possession, distance; per-player Opta keys |

## TODO

- [ ] Find out when the post-match rewrite runs (French booking reasons, `assistProviderId`,
      `attendance`, `videos`) — it had not run 5 min after full time.
- [ ] Observe `penaltyGoal`, `ownGoal`, `redCard`, `canceledGoal`/VAR types in
      `timeline`, and a shoot-out (`fullTimePens`, `ShootOut`, `penaltyShots[]`) in
      Coupe de France.
- [ ] Observe a postponed match (`unknownMatch`? `period`?).
- [ ] Find the filter params of `championship-players-pool` and
      `championship-players-advanced-rankings/{id}/top` (site passes `params`).
- [ ] Check whether `championship-players-statuses` fills up (injuries/suspensions)
      closer to a round.

## Changelog

| Date | Change |
|---|---|
| 2026-09-11 | Initial mapping: 30 endpoints, 42 samples incl. a full live series (pre-match → line-ups → kick-off → half-time → second half with a goal → full time). API host and endpoint list extracted from the ligue1.com Next.js chunks (`L1_API_URL`, axios `apiClient.get(...)` templates). |
