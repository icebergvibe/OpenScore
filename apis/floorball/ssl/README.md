# SSL API

| | |
|---|---|
| **Sport** | Floorball |
| **Competition mapped** | SSL Herr (Swedish men's top division) |
| **Country / region** | Sweden |
| **Official site** | https://www.ssl.se |
| **Base URL** | `https://www.ssl.se/api` |
| **Auth** | None — public endpoints used by the SSL website |
| **Format** | JSON, except that several unavailable game-day routes answer with an empty body |
| **Statistics provider** | `ibis` |
| **CORS** | No usable cross-origin CORS on `www.ssl.se/api` |
| **WAF / User-Agent requirement** | None observed; OpenScore should still identify itself |
| **Last full verification** | 2026-09-21 |
| **Status** | ✅ mapped and implemented as `SslProvider` (league id `ssl`) · 🚧 live state not yet captured |

## Overview

ssl.se is a Sportality (`s8y.se`) site. Its route families and much of its reference-data
schema resemble the Sportality API documented for [SHL](../../hockey/shl/README.md), but
the similarity stops at the platform boundary: SSL's statistics say `provider: "ibis"`,
its standings columns differ, and the SHL game overview, play-by-play and boxscore routes
do not return floorball data. A floorball provider must therefore advertise a smaller
capability set and must not inherit the hockey event mapper unchanged.

This mapping covers **SSL Herr**. The bootstrap also lists SSL Dam and both Swedish Cup
competitions:

| Competition | Series code | Series UUID |
|---|---|---|
| SSL Herr | `SSLHerr` | `qRl-8B5kOFjKL` |
| SSL Dam | `SSLDam` | `qRl-8B5wsw8lj` |
| Svenska Cupen Herr | `SCM` | `qZo-330EsKpZc` |
| Svenska Cupen Dam | `SCW` | `qZo-330Twnpf3` |

They should be separate `LeagueProvider`s if added later rather than four competitions
hidden behind one league id. The two games used throughout this mapping are:

- final: `msmb27in1v`, Mullsjö AIS 10–6 Visby IBK, 2026-09-20;
- pre-game: `l3vlqyge1x`, Jönköpings IK–Warberg IC, scheduled 2026-09-22.

## Access, caching and time

No key, login, cookie, token or custom header is required. Requests with and without the
site's `x-s8y-instance-id: ssl1_ssl` header returned the same game document, so that header
is not part of the API contract. A reproducible request is simply:

```sh
curl --compressed \
  -H 'User-Agent: OpenScore/0.1 (+https://github.com/icebergvibe/OpenScore)' \
  'https://www.ssl.se/api/sports-v2/game-info/msmb27in1v'
```

Responses are served through Cloudflare and Varnish, carry weak `ETag`s and did not carry
`Cache-Control` in the captured schedule, header or game responses. `gameheader` had an
edge `Age` of 6 seconds in one capture. Use OpenScore's own conservative lifetimes: a short
cache only for a game that is actually due/live, minutes for standings, and hours for the
bootstrap, full schedule, teams and rosters. Never poll faster than the repository's 10 s
floor.

The API returns `Access-Control-Allow-Credentials: true` and `Vary: Origin` but no
`Access-Control-Allow-Origin`, including when called with `Origin: https://example.com`.
Android and the feed server can call it directly; a browser needs the feed server as a proxy.

Time fields use two conventions:

- ISO values such as `rawStartDateTime` and the `game-info.startDateTime` are UTC;
- the schedule's plain `startDateTime` (`2026-09-22 18:30:00`) is Swedish local time;
- `gameheader.date` and the map key are the league's Swedish calendar date.

## Identifiers

All identifiers are opaque and must be discovered rather than guessed.

| ID | Example | Where to obtain it |
|---|---|---|
| Season UUID | `vt4kt77vxk` (2026/2027) | Bootstrap `season[].uuid` |
| Series UUID | `qRl-8B5kOFjKL` (SSL Herr) | Bootstrap `series[].uuid` |
| Game-type UUID | `qQ9-af37Ti40B` (`regular`) | Bootstrap `gameType[].uuid` / `defaultSsgtFilter.gameType` |
| `ssgtUuid` | `qqing3frfp` | Bootstrap current default or schedule response; season + series + game type |
| Game UUID | `msmb27in1v` | `gameheader` or `game-schedule.gameInfo[].uuid` |
| External game id | `1693400` | `game-info.gameInfo.extId`; used by some site modules, not as OpenScore identity |
| Team UUID | `2cdf-32c4bo2vU` | Schedule, game, standings or team list |
| Innebandy.se team id | `4097` | `all-teams.extIds[]` / standings `info.teamId`; not needed for the mapped routes |
| Site instance id | `mul1_mul` | Team `ownerInstanceId` / game `instanceId`; also appears in crest URLs |
| Athlete UUID | `ihdjejtzeo` | Roster `players[].uuid` |

For 2026/27 SSL Herr the bootstrap returns season `vt4kt77vxk`, series
`qRl-8B5kOFjKL`, regular game type `qQ9-af37Ti40B` and `ssgtUuid` `qqing3frfp`.
Use the three component UUIDs for the season schedule and the `ssgtUuid` for standings,
teams and matchup statistics.

## Discovery path

1. **Bootstrap once per season:** `GET /sports-v2/season-series-game-types-filter`.
   Select `SSLHerr` from `series[]`; for the current default, read the three UUIDs from
   `defaultSsgtFilter` and the combined id from `ssgtUuid`.
2. **Today / near term:** `GET /gameday/gameheader`, then retain games whose
   `seriesCode == "SSLHerr"`. The response mixes SSL Herr and SSL Dam.
3. **Any date / team season:** read the complete `game-schedule` with the three UUIDs and
   filter `rawStartDateTime` in `Europe/Stockholm`. The schedule is also sufficient for a
   team schedule by matching the stable team UUID.
4. **One game:** `GET /sports-v2/game-info/{gameUuid}` for identity, state, score, venue
   and OT/shootout flags.
5. **Post-game statistics:** after a final state, request the small promo-bar statistics
   route, or the larger game-day comparison route.
6. **Standings / teams:** use the current `ssgtUuid`; roster and profile calls then use
   the returned team and athlete UUIDs.
7. **Live:** not yet established. Capture a real SSL match before enabling polling or any
   live capability; the shared SHL game-day routes were empty for the completed sample.

## Endpoints

Every working endpoint below was called and captured on **2026-09-21**.

### `GET /sports-v2/season-series-game-types-filter`

| | |
|---|---|
| **Purpose** | Discover seasons, series, game types and the site's current default `ssgtUuid` |
| **Parameters** | None required |
| **Sample** | [`season-series-game-types-filter.json`](samples/season-series-game-types-filter.json) |
| **Last verified** | 2026-09-21 |

Response fields:

```text
series[]            { uuid, code, names[] }
season[]            { uuid, code, names[] }
gameType[]          { uuid, code, names[] }
defaultSsgtFilter   { season, series, gameType }
ssgtUuid            combined id for that default selection
```

The response exposes eleven seasons back through 2016/17, four series and one regular
game type in the captured state. It supplies only one default `ssgtUuid`; use the explicit
component UUIDs when requesting another listed competition.

### `GET /gameday/gameheader`

| | |
|---|---|
| **Purpose** | Compact scoreboard for a rolling set of nearby dates, all SSL series mixed |
| **Parameters** | None |
| **Sample** | [`gameheader.json`](samples/gameheader.json) |
| **Last verified** | 2026-09-21 |

The root object is keyed by `YYYY-MM-DD`. The capture held 20 games over six dates from
2026-09-20 through 2026-09-26; that window is observational, not a contract. Each game has:

```text
uuid, startDateTime (UTC ISO), date, played, overtime, shootout,
ssgtUuid, seriesCode, displayName, venue, roundNumber, roundLabel,
homeTeam/awayTeam { name, code, result, logo }
```

Important quirks:

- filter by `seriesCode`; both `SSLHerr` and `SSLDam` were present;
- an unplayed game's `result` is `0`, not absent, so only read it when `played == true`;
- this route has no explicit live state, clock or period.

### `GET /sports-v2/game-schedule?seasonUuid={season}&seriesUuid={series}&gameTypeUuid={type}&gamePlace=all&played=all`

| | |
|---|---|
| **Purpose** | Complete schedule and result set for one season/series/game type |
| **Required parameters** | `seasonUuid`, `seriesUuid`, `gameTypeUuid` |
| **Filters verified** | `gamePlace=all`, `played=all` |
| **Sample request** | SSL Herr 2026/27 using `vt4kt77vxk`, `qRl-8B5kOFjKL`, `qQ9-af37Ti40B` |
| **Sample** | [`game-schedule.json`](samples/game-schedule.json), `gameInfo` truncated from 182 to 8; all 14 `teamList` rows retained |
| **Last verified** | 2026-09-21 |

The full response was 222,898 bytes decoded and about 9 KB Brotli-compressed. Shape:

```text
gameInfo[] {
  uuid, rawStartDateTime (UTC), startDateTime (Swedish local, no zone),
  state, overtime, shootout, ssgtUuid,
  homeTeamInfo/awayTeamInfo {
    status, uuid, ownerInstanceId, code, names{}, score, icon
  },
  venueInfo{}, seriesInfo{}, roundNumber, roundLabel
}
teamList[]
ssgtUuid
```

Observed states are `pre-game` and `post-game`. Before a match, team `score` is the string
`"N/A"`; after it, the value is an integer. The `seriesInfo.code` value was sometimes the
display form `"SSL Herr"`, unlike `gameheader.seriesCode == "SSLHerr"`; use the series UUID
or normalize the value instead of requiring exact cross-route equality.

### `GET /sports-v2/game-info/{gameUuid}`

| State | Request id | Sample |
|---|---|---|
| Pre-game | `l3vlqyge1x` | [`game-info.pre.json`](samples/game-info.pre.json) |
| Final | `msmb27in1v` | [`game-info.final.json`](samples/game-info.final.json) |

Last verified: **2026-09-21**.

```text
gameInfo {
  gameUuid, extId, startDateTime (UTC), arenaName,
  state, overtime, shootout, seriesCode, seriesName,
  roundNumber, roundLabel, seriesDisplayName
}
homeTeam/awayTeam { names{}, uuid, instanceId, icon, score, ... }
ssgtUuid, seriesUuid
```

This route uses underscore states (`pre_game`, `post_game`) where the schedule uses hyphens.
Before the game, team `score` is `""`; after it, the score is an integer. It contains enough
data to construct both the requested pre-game model and the 10–6 final result without any
game-day endpoint.

### `GET /statistics-v2/league-standings?ssgtUuid={ssgt}`

| | |
|---|---|
| **Purpose** | Current table and qualification/relegation ranges |
| **Sample request** | `ssgtUuid=qqing3frfp` |
| **Sample** | [`league-standings.json`](samples/league-standings.json) |
| **Last verified** | 2026-09-21 |

Response: `{dataColumn[], groupings[], leagueStandings[], provider: "ibis"}`. The fourteen
rows carry:

```text
Rank, GP, Diff, G, GA, OTW, Points, RegL, RegT, RegW,
info { code, teamId, teamInfo { teamUuid, teamMedia, teamNames{}, ... } }
```

`groupings` marked ranks 1–8 as `Playoff`, 9–12 as `MissedPlayoff` and 13–14 as
`Relegation`. `RegT` is a game tied after regulation, not a final draw: after one round,
the overtime winner had `RegT=1, OTW=1, Points=2`, while the loser had
`RegT=1, OTW=0, Points=1`. A core mapper can therefore use:

```text
wins        = RegW + OTW
losses      = RegL
otherLosses = RegT - OTW
```

and retain `RegW`, `RegT` and `OTW` in `StandingsRow.extra`. This is an inference from the
observed rows and should be replay-tested with later overtime/shootout results.

### Teams

#### `GET /sports-v2/all-teams/{ssgtUuid}`

Sample: [`all-teams.json`](samples/all-teams.json) · last verified **2026-09-21**.

For `qqing3frfp` this returned fourteen team objects with stable `uuid`, `teamCode`,
`teamNames`, `ownerInstanceId`, `logo`/`icon`, `series[]`, `sport[]`, contact data and
Innebandy.se external ids. Prefer `teamNames.code` over `teamCode`: the latter is sometimes
a generic value such as `HERR`. Do not use the embedded historical `series[]` to decide
current membership; the schedule's `teamList` and fixtures are authoritative.

#### `GET /sports-v2/teams/{teamUuid}`

Sample request: Mullsjö `2cdf-32c4bo2vU` · sample:
[`team.json`](samples/team.json) · last verified **2026-09-21**.

Compact shape: `{names{}, info{}, contact[], uuid, nationality, arenaName, publicUrl,
icon, ownerInstanceId, siteDisplayName}`. This is suitable for a team detail call; the
all-teams response remains cheaper when a screen needs every identity at once.

### Players

#### `GET /sports-v2/athletes/by-team-uuid/{teamUuid}`

Sample request: Mullsjö `2cdf-32c4bo2vU` · sample:
[`athletes-by-team.json`](samples/athletes-by-team.json) · last verified **2026-09-21**.

The root is a positional group array. Mullsjö returned `GK` (Målvakter), `D` (Backar),
`F` (Forwards) and `C` (Centrar), 21 athletes in total. Every player has an athlete UUID,
name, nationality, jersey number and optional portrait objects. The saved sample retains
all four groups but truncates each `players[]` array to its first two entries because the
image `srcset` strings made the raw response 437 KB.

#### `GET /statistics-v2/athlete/profile-page?playerUuid={athleteUuid}&masterSiteInstanceId=`

Sample request: Samuel Jönsson Dolke `ihdjejtzeo` · sample:
[`athlete-profile-page.json`](samples/athlete-profile-page.json) · last verified
**2026-09-21**.

Returns the athlete UUID, name, birth date, nationality, jersey number, position/code,
current team, season/career stat arrays, `isInSquad`, portrait media and
`statisticsProvider: "ibis"`.

### Post-game statistics

All three requests below were verified for `msmb27in1v` on **2026-09-21**.
The sample parameters are `ssgtUuid=qqing3frfp`, home team
`2cdf-32c4bo2vU` and away team `c699-cd81gmNJ2`.

| Endpoint | Sample | Notes |
|---|---|---|
| `GET /gameday/game-info/{gameUuid}` | [`gameday-game-info.final.json`](samples/gameday-game-info.final.json) | `teamHead2Head[]`, empty `bestScorers[]`, and an empty `teamPeriod.stats[]` |
| `GET /gameday/post-game-data/team-stats/{gameUuid}?ssgtUuid={ssgt}&homeTeamUuid={home}&awayTeamUuid={away}` | [`post-game-team-stats.final.json`](samples/post-game-team-stats.final.json) | Same comparison rows with team names/logos |
| `GET /gameday/post-game-data/promo-bar-stats/{gameUuid}/{homeTeamUuid}/{awayTeamUuid}?ssgtUuid={ssgt}` | [`promo-bar-stats.final.json`](samples/promo-bar-stats.final.json) | Smallest clean totals response |

The promo response contains four pairs:

| Caption | Meaning | Mullsjö–Visby |
|---|---|---|
| `G` | Goals | 10–6 |
| `SOG` | Shots on goal | 27–22 |
| `SVS` | Saves | 16–17 |
| `PIM` | Penalty minutes | 2–4 |

The game-day response did not expose period-by-period goals: `teamPeriod.stats` was empty.

### Pre-game matchup and form

Both calls require the current `ssgtUuid` plus home and away team UUIDs. They were verified
for Jönköpings IK–Warberg IC on **2026-09-21**, using `ssgtUuid=qqing3frfp`,
`homeTeamUuid=5b11-cd81tVTqP` and `awayTeamUuid=c437-b283ovsh6`.

| Endpoint | Sample | Shape |
|---|---|---|
| `GET /statistics-v2/game-center/team-h2h?ssgtUuid={ssgt}&homeTeamUuid={home}&awayTeamUuid={away}` | [`team-h2h.pre.json`](samples/team-h2h.pre.json) | Current-season aggregate comparison rows (`GP/G/GA`, `W/T/L`, `OTW`) |
| `GET /statistics-v2/game-form?ssgtUuid={ssgt}&homeTeamUuid={home}&awayTeamUuid={away}` | [`game-form.pre.json`](samples/game-form.pre.json) | Three `lastMeetings`, five recent games per side, small team stat summaries and table positions |

These are optional preview material rather than requirements for schedule/result support.

## Live, events and lineups: tried but unavailable

The following shared Sportality routes were called for the completed game
`msmb27in1v` on 2026-09-21. They are recorded so a future mapper does not assume the
working SHL routes also work for SSL:

| Request | Observed response | Capture |
|---|---|---|
| `GET /gameday/game-overview/msmb27in1v` | HTTP 200, zero-byte body | [`game-overview.final.empty.txt`](samples/game-overview.final.empty.txt) |
| `GET /gameday/play-by-play/msmb27in1v` | HTTP 200, zero-byte body | [`play-by-play.final.empty.txt`](samples/play-by-play.final.empty.txt) |
| `GET /gameday/team-stats/msmb27in1v` | HTTP 200, zero-byte body | [`team-stats.final.empty.txt`](samples/team-stats.final.empty.txt) |
| `GET /gameday/player-stats/msmb27in1v` | HTTP 200, zero-byte body | [`player-stats.final.empty.txt`](samples/player-stats.final.empty.txt) |
| `GET /gameday/boxscore/msmb27in1v` | HTTP 500 JSON error | [`boxscore.final.error.json`](samples/boxscore.final.error.json) |
| `GET /gameday/periodstats/msmb27in1v` | HTTP 500 JSON error | [`periodstats.final.error.json`](samples/periodstats.final.error.json) |
| `GET /statte/pre-game/1693409` | HTTP 500 JSON error | [`statte-pre-game.pre.error.json`](samples/statte-pre-game.pre.error.json) |

These unusable routes are intentionally not health-checked as supported endpoints. No event
list, lineup, live clock, intermission marker or per-period score source has been verified.
The next useful investigation is to capture `game-info` and `gameheader` during a real game
and see whether their state/score changes are timely. Until then, do not advertise
`LIVE_UPDATES`, `EVENTS`, `LINEUPS`, `CLOCK`, `INTERMISSION_STATE` or `PERIOD_SCORES`.

## Out of scope: Allsvenskan on stats.innebandy.se

The federation's [2026/27 schedule page](https://www.innebandy.se/tavling/tavlingar/allsvenskan/spelschema-allsvenskan-202627)
identifies stats series `44034` as **Allsvenskan Herr**. Its public schedule and match URLs
were investigated on **2026-09-21**, including match `1693729`, but they are only a static
SPA shell and are not a key-less data source.

The site's JavaScript first calls `https://api.innebandy.se/StatsAppApi/api/startkit`.
That response issues a new Bearer JWT with an approximately 30-minute lifetime and points
the client at `https://api.innebandy.se/v2/api/`. Calling
`GET /competitions/44034/matches` directly without that token returned HTTP 401 with a
Bearer challenge; match detail uses the same authenticated API. The token response was
not captured or committed, and the issued token was deliberately not used: exchanging
for a per-session access token is outside the public static-key exception in
[`docs/principles.md`](../../../docs/principles.md#nothing-private).

The federation's supported [iBIS API service](https://www.innebandy.se/ibis-play/ibis-api-tjanst)
is also out of scope. It requires an agreement, a SEK 3,000 seasonal fee, issued login
credentials, and OAuth2 Bearer authentication. Consequently there is no Allsvenskan
provider, captured response, or health check. Revisit this only if the federation exposes
a genuinely unauthenticated feed that does not mint a session token.

## Captured samples

All response bodies are pretty-printed with two-space indentation and otherwise preserve
the returned values. Only arrays explicitly called out above are truncated.

- [`season-series-game-types-filter.json`](samples/season-series-game-types-filter.json)
- [`gameheader.json`](samples/gameheader.json)
- [`game-schedule.json`](samples/game-schedule.json)
- [`game-info.pre.json`](samples/game-info.pre.json)
- [`game-info.final.json`](samples/game-info.final.json)
- [`league-standings.json`](samples/league-standings.json)
- [`all-teams.json`](samples/all-teams.json)
- [`team.json`](samples/team.json)
- [`athletes-by-team.json`](samples/athletes-by-team.json)
- [`athlete-profile-page.json`](samples/athlete-profile-page.json)
- [`gameday-game-info.final.json`](samples/gameday-game-info.final.json)
- [`post-game-team-stats.final.json`](samples/post-game-team-stats.final.json)
- [`promo-bar-stats.final.json`](samples/promo-bar-stats.final.json)
- [`team-h2h.pre.json`](samples/team-h2h.pre.json)
- [`game-form.pre.json`](samples/game-form.pre.json)

## Core model mapping

This is what `SslProvider` (`core/src/commonMain/kotlin/org/openscore/providers/ssl`) does.

| Core concept | SSL source | Mapping / limitation |
|---|---|---|
| `League` | Static | `League("ssl", FLOORBALL, "SSL", "SE", "https://www.ssl.se")`; SSL Herr only |
| `Season.id` / `Game.seasonId` | `ssgtUuid` | Combined season + series + game type; `qqing3frfp` for SSL Herr 2026/27 |
| `StageKind` | Game type / series | `REGULAR` for the mapped `regular` schedule |
| `Game.id` | `gameInfo[].uuid` / `gameInfo.gameUuid` | Opaque game UUID |
| `Game.startTime` | `rawStartDateTime` or detail `startDateTime` | Parse ISO value as UTC |
| League calendar date | `gameheader.date` or UTC converted to `Europe/Stockholm` | European local-date convention |
| `Game.venue` | `venueInfo.name`, `arenaName`, or header `venue` | Trim incidental trailing whitespace |
| `Game.home` / `away` | Schedule/detail team objects | Stable team UUID, long name, abbreviation, SVG crest URL |
| `Game.state` | Schedule/detail state or header `played` | `pre-game`/`pre_game` → `SCHEDULED`; `post-game`/`post_game` → `FINAL`; live shape unverified |
| `Game.score` | Detail integer scores; schedule scores when final | Ignore header zeros when `played == false`; ignore `"N/A"` / `""` pre-game values |
| `Game.ending` | `overtime`, `shootout` | At final: shootout first, then overtime, otherwise regulation |
| `Game.stats` | Promo-bar statistics | `shotsOnGoal`, `saves`, `penaltyMinutes`; goals already live in `score` |
| `Game.periodScores` | — | Not available in the completed sample |
| `Game.clock` / intermission | — | Not available; live state not captured |
| `Game.events` | — | Shared play-by-play route returned an empty body |
| `StandingsTable` | `league-standings` | One league table; rank-zone descriptions retained in row `extra` |
| `StandingsRow` | `GP`, `RegW`, `RegT`, `RegL`, `OTW`, `G`, `GA`, `Diff`, `Points` | Use the overtime mapping documented above |
| `Team` | `all-teams` or `teams/{uuid}` | UUID identity, names and remote crest URL |
| `teamSchedule` | Full season `game-schedule` | Filter both team sides and inclusive Swedish dates locally; no per-day fan-out |
| `Player` / roster | `athletes/by-team-uuid`, athlete profile | Athlete UUID, name, number, position, nationality and portrait |
| Lineups | — | `boxscore` returned HTTP 500 |

Capabilities:

```text
GAMES_BY_DATE, GAME, STANDINGS, TEAM, TEAM_SCHEDULE, ROSTER, PLAYER
```

Match statistics are populated by `game()` after a final state; they have no capability of
their own. Live and detail capabilities remain absent until a live capture proves their
source and freshness.

### What the provider reads, and when

| Call | Requests |
|---|---|
| `gamesOn(date)` in the `gameheader` window | bootstrap + `gameheader` + `all-teams`, then one `game-info` per game whose start has passed while the scoreboard still says unplayed |
| `gamesOn(date)` outside it | bootstrap + the full `game-schedule` (223 KB, kept an hour) |
| `game(id)` | `game-info`, plus the promo-bar totals once the state is final |
| `teamSchedule` | the same season schedule, filtered locally on the team UUID and Swedish dates |
| `standings` / `team` / `roster` / `player` | one request each; `all-teams` and the bootstrap are kept a day |

`Game.events` stays `null` rather than empty: SSL has no event source at all, which is not the
same as a game that has had none yet. There are no period scores and no clock, and
`live()` throws `UnsupportedCapabilityException`.

Since ssl.se is the same Sportality platform as shl.se, the bootstrap, scoreboard, schedule,
game, team and athlete responses decode with the shared `Spt*` DTOs in
`providers/sportality`. Only the two shapes the statistics provider owns are SSL's own
(`SslDtos.kt`): the table's `Reg*`/`OTW` columns and the promo-bar totals.

## Changelog

| Date | Change |
|---|---|
| 2026-09-23 | Implemented as `SslProvider` in `core/`. No endpoint changed; the mapping's inference about `RegT`/`OTW` is now asserted by `SslProviderTest` against the captured table. |
| 2026-09-21 | Audited Allsvenskan Herr on stats.innebandy.se; its SPA exchanges for a short-lived Bearer token and the supported iBIS API is credentialed and paid, so it is recorded as out of scope. |
| 2026-09-21 | Initial SSL Herr mapping: bootstrap, rolling and full schedules, pre/final game detail, standings, teams, rosters, player profiles, matchup/form and post-game statistics. Shared Sportality live/event/lineup routes recorded as unavailable. |
