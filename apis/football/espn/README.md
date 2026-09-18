# ESPN soccer rosters (site.web.api.espn.com) — a squad supplement, not a league

| | |
|---|---|
| **Sport** | Football (association) |
| **Used for** | **Squads only**, for clubs whose own feed has no key-less squad: the 18 Bundesliga clubs (bundesliga.com's `wapp.bapi` person/club resources are gated by `x-api-key`) and the UEFA entrants whose domestic league OpenScore does not cover (`comp.uefa.com` has no squad endpoint) |
| **Official site** | https://www.espn.com/soccer/ (the API is the site's own, undocumented, key-less) |
| **Base URL** | `https://site.web.api.espn.com/apis/common/v3/sports/soccer` (team directories on `https://site.api.espn.com/apis/site/v2/sports/soccer`) |
| **Auth** | None |
| **Format** | JSON |
| **CORS** | `Access-Control-Allow-Origin: *` |
| **Conditional requests** | None observed; `Cache-Control: max-age=300` on rosters, `max-age=95` on team directories |
| **Last full verification** | 2026-09-16 |
| **Status** | ✅ roster route verified for Bundesliga, Champions League, Europa League and Conference League clubs; **not** a game, score, table or live source and never registered as a league |

## Why it is here

[docs/principles.md](../../../docs/principles.md#one-source-per-league-and-explicit-provenance) says why ESPN is not used for more: larger and
less detailed than every direct feed for scores and events, so not a replacement for any
of them. Its one use under the source rules is rule 2 — *a narrowly defined fallback for
a capability the primary feed demonstrably lacks* — and that capability is the squad:

- **Bundesliga:** everything key-less lives in the Firebase Realtime Database, which has
  no club or person directory; the REST host that has them answers `403 Forbidden for
  non bundesliga top level usage` without the site's `x-api-key`.
- **UEFA:** `comp.uefa.com/v2/players` filters by player ids or by competition + season
  (5 MB, every player of the phase) — there is no per-team squad.

Clubs from leagues OpenScore covers (Premier League, LaLiga, Serie A, Ligue 1, MLS,
Allsvenskan, …) never use it: the app reads their squad from their own league.

## Identifiers

ESPN team ids are ESPN's own (Bayern `132`, Sturm Graz `3746`, AGF `7853`) and are
**hand-verified** against the team directories below, then stored in the club crosswalk
under the `espn` namespace ([docs/data-model.md](../../../docs/data-model.md#clubs-across-leagues)). Nothing is matched by
name at run time. Athlete ids are ESPN's too; the core returns them as
`PlayerRef(leagueId = "espn", id = …)` — that is the id space they live in.

## Endpoints

### `GET /apis/common/v3/sports/soccer/{league}/teams/{teamId}/roster?season={startYear}`

| | |
|---|---|
| **Purpose** | The club's registered squad for the season, grouped by position |
| **Parameters** | `{league}`: `ger.1`, `uefa.champions`, `uefa.europa`, `uefa.europa.conf` (any competition ESPN files the club under; domestic slugs such as `den.1` work too). **`season`** (start year, `2026` = 2026/27) is required in practice: without it `uefa.europa.conf` answers `500`, and the league-agnostic `all` slug pads the list with reserve-team players (56 for Bayern against the 25 registered) |
| **Samples** | [`samples/roster.bayern.json`](samples/roster.bayern.json) (`ger.1/132`, 25 players, coach) · [`samples/roster.sturm-graz.json`](samples/roster.sturm-graz.json) (`uefa.europa/3746`, 31 players, no coach block) captured 2026-09-16 |
| **Cache** | `max-age=300`; the core keeps a roster for an hour |

```
{ team { id, displayName, abbreviation }, season { year, name },
  coach: [ { id, firstName, lastName } ],
  positionGroups: [ { position: null, athletes: [ {
      id, firstName, lastName, displayName, fullName, shortName, jersey ("9"),
      position { id, name ("Goalkeeper"), abbreviation ("G" | "D" | "M" | "F") },
      displayDOB ("1995-09-15T07:00Z" — date part is the birthday), age,
      height (inches), displayHeight ("6' 3\""), weight (lbs), displayWeight,
      birthPlace { country? }, citizenship?, flag?, headshot? (absent for soccer),
      status { type: "active" }, statistics […] } ] } ] }
```

The keeper group comes first, then the outfield players; `position` on the group is
null, the athlete's `position.abbreviation` is what to read. Bodies are 110–225 KB
because every athlete carries a `statistics` block and links; only the fields above are
mapped.

### `GET https://site.api.espn.com/apis/site/v2/sports/soccer/{league}/teams`

| | |
|---|---|
| **Purpose** | The competition's team directory — how the `espn` ids in the crosswalk were found and are re-checked each season |
| **Samples** | [`samples/teams.ger.1.json`](samples/teams.ger.1.json) (18) · [`samples/teams.uefa.europa.conf.json`](samples/teams.uefa.europa.conf.json) (36) captured 2026-09-16 |

`sports[0].leagues[0].teams[].team { id, displayName, shortDisplayName, abbreviation,
logos[] }`. Names differ from the clubs' own (`FC Cologne`, `Bayern Munich`, `Red Star
Belgrade`, `AGF`), which is why ids are curated rather than derived.

## Not used

- `site.api.espn.com/…/scoreboard`, `summary`, `standings`: measured against the direct
  feeds they were several times larger and less complete for everything OpenScore covers
  (an EPL match summary is ~430 KB decoded against ~35 KB for the five Pulselive bodies;
  the F1 season is ~40× the Jolpica body).
- `site.web.api.espn.com/…/roster?enable=stats` and the athlete `statistics` blocks: not
  a capability of the model yet.
- `sports.core.api.espn.com`: a `$ref` graph that fans out per request.

## Core model mapping

| Model | Endpoint | Fields | Notes |
|---|---|---|---|
| Player | `roster` | `id`, `displayName`, `firstName`/`lastName`, `jersey`, `position.abbreviation` (G/D/M/F → GK/DF/MF/FW), `displayDOB`, `height`/`weight` (inches, lbs → cm, kg), `citizenship`/`birthPlace.country` | `PlayerRef.leagueId = "espn"`; `teamId` = the ESPN team id |

`EspnRosters` in `org.openscore.providers.espn` serves it; `BundesligaProvider` and the
UEFA providers advertise `ROSTER` only when given one, and answer through
`Clubs.club(league, id).ids["espn"]`.
