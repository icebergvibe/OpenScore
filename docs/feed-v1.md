# OpenScore Feed v1

One JSON shape for every league. Produced by `org.openscore.feed.FeedMapper` in `core/`
and served by [`apps/feed-server`](../apps/feed-server). Apps can either talk HTTP to the
server or embed `core` and call `FeedMapper` themselves — the JSON is identical.

Conventions:

- **Read-only, no auth, CORS `*`.** Every response is `application/json` except the live
  stream (`text/event-stream`). Every response carries `X-OpenScore-Feed: 1`.
- **Ids are strings** and only meaningful together with `league`.
- **Times** are ISO-8601 UTC (`2026-09-29T21:00:00Z`); **dates** are `YYYY-MM-DD`;
  **durations** are whole seconds.
- **Enums** are upper-case names; unknown values from a league surface as `UNKNOWN`
  plus the raw string in `rawState` / `rawType`.
- **`null` means "not provided by this league"**; an empty array means "provided, none".
  What a league can provide is listed in its `capabilities`.

## Routes

| Route | Returns | Notes |
|---|---|---|
| `GET /v1/leagues` | `[League]` | Every league with a provider and its capabilities. |
| `GET /v1/games?date=YYYY-MM-DD&league=a,b` | `GamesResponse` | Default date: today (UTC). Default leagues: all. Dates are interpreted in each **league's own convention** (NHL: US Eastern). Failing leagues land in `errors`, the rest still return. Games are sorted by `startTime`. When an umbrella feed and a dedicated league that share ids are both asked (Fogis with Allsvenskan/Superettan), the umbrella's copy is dropped. |
| `GET /v1/games/{league}/{id}` | `Game` | Full game incl. `events` where the league is cheap about it. |
| `GET /v1/games/{league}/{id}/events` | `[Event]` | |
| `GET /v1/games/{league}/{id}/lineups` | `[Lineup]` | Home first, then away. Empty before lineups are posted. |
| `GET /v1/games/{league}/{id}/live?interval=10` | SSE stream | `event: game` with a `Game` in `data` on every change; `event: end` when final; `event: error` with an `Error` on failure. Interval is clamped to ≥ 10 s. |
| `GET /v1/standings/{league}?season=` | `Standings` | Current season unless `season` (league-native id) is given. |
| `GET /v1/teams/{league}/{id}` | `Team` | |
| `GET /v1/teams/{league}/{id}/schedule?startDate=YYYY-MM-DD&endDate=YYYY-MM-DD` | `[Game]` | Inclusive date range, up to 366 days; requires `TEAM_SCHEDULE` (MLB first). |
| `GET /v1/teams/{league}/{id}/stats?season=YYYY` | `TeamSeasonStats` | Regular-season totals; requires `TEAM_STATS` (MLB first). |
| `GET /v1/teams/{league}/{id}/roster` | `[Player]` | |
| `GET /v1/players/{league}/{id}` | `Player` | |

### Errors

```json
{"error": {"league": "nhl", "code": "not_found", "message": "NHL: …/gamecenter/42/play-by-play → 404"}}
```

| `code` | HTTP | Meaning |
|---|---|---|
| `bad_request` | 400 | Malformed parameter (e.g. `date`). |
| `not_found` | 404 | The league does not know this id. |
| `unknown_league` | 404 | No provider for `{league}`. |
| `unsupported` | 501 | The league lacks this capability (see `/v1/leagues`). |
| `upstream` | 502 | The league's API failed or returned something unparsable. |
| `internal` | 500 | Bug; please report. |

Inside `GamesResponse.errors` the same object appears per failed league, with HTTP 200.

## Types

### League

```json
{"id": "nhl", "sport": "HOCKEY", "name": "National Hockey League", "country": "US",
 "zone": "America/New_York", "websiteUrl": "https://www.nhl.com",
 "capabilities": ["CLOCK", "EVENTS", "GAME", "GAMES_BY_DATE", "LINEUPS", "LIVE_UPDATES", "…"]}
```

`sport`: `HOCKEY | FLOORBALL | FOOTBALL | BASEBALL | MMA` (F1 is not a league of the feed).
`zone`: the IANA zone this league keeps its calendar in, which is the convention `date` on
`/v1/games` is read in. It is also what a `startTime` must be converted to to get the day a game
is filed under: an NHL game at 20:00 New York is the NHL's day and the next day in Europe, and
only one of those two days answers with that game.
A UFC `game` has both sides as fighters and its `situation.kind` is `fight` (below).
`capabilities`: `GAMES_BY_DATE GAME EVENTS LINEUPS STANDINGS TEAM TEAM_SCHEDULE TEAM_STATS ROSTER PLAYER LIVE_UPDATES LIVE_PUSH CLOCK CLOCK_RUNNING_FLAG INTERMISSION_STATE PERIOD_SCORES EVENT_COORDINATES LINE_GROUPS`.

### Game

```json
{
  "league": "nhl", "id": "2025020444", "seasonId": "20252026", "stage": "REGULAR", "competition": null,
  "startTime": "2025-12-13T00:00:00Z", "venue": "Scotiabank Arena",
  "home": {"league": "nhl", "id": "TOR", "name": "Toronto Maple Leafs", "abbreviation": "TOR", "logoUrl": "https://…/TOR_light.svg"},
  "away": {"league": "nhl", "id": "MTL", "name": "Montréal Canadiens", "abbreviation": "MTL", "logoUrl": "https://…/MTL_light.svg"},
  "state": "FINAL",
  "score": {"home": 1, "away": 2},
  "clock": null,
  "situation": null,
  "periodScores": [
    {"period": {"number": 1, "type": "REGULATION", "label": "1"}, "home": 0, "away": 0},
    {"period": {"number": 2, "type": "REGULATION", "label": "2"}, "home": 0, "away": 1},
    {"period": {"number": 3, "type": "REGULATION", "label": "3"}, "home": 1, "away": 0},
    {"period": {"number": 4, "type": "OVERTIME",   "label": "OT"}, "home": 0, "away": 0},
    {"period": {"number": 5, "type": "SHOOTOUT",   "label": "SO"}, "home": 0, "away": 1}
  ],
  "ending": "SHOOTOUT",
  "events": null,
  "stats": {"shots": {"home": "23", "away": "34"}, "possession": {"home": "48", "away": "52"}},
  "rawState": "OFF/OK"
}
```

- `stage`: `PRESEASON | REGULAR | PLAYOFF | OTHER | null`.
- `competition`: the competition's name where one provider serves several (`"Div 2 Norra Svealand, herr 2026"` from Fogis, cup rounds), else `null`.
- `stats`: per-team match statistics keyed by name, both values as strings. Common keys: `possession` (percent), `shots`, `shotsOnTarget`, `xg`, `corners`, `fouls`, `offsides`, `passes`, `yellowCards`, `redCards`; provider-specific extras are documented in the league README. Empty when the league publishes none.
- `state`: `SCHEDULED | PRE_GAME | LIVE | INTERMISSION | FINAL | POSTPONED | SUSPENDED | CANCELLED | UNKNOWN`.
  `INTERMISSION` only appears for leagues with `INTERMISSION_STATE`; others stay `LIVE` between periods.
- `score` is `null` until the game starts.
- `clock` is present only while `state` is `LIVE`/`INTERMISSION`. With `CLOCK` it carries times; a league without `CLOCK` may still send it with only `period` set (KHL):
  `{"period": Period, "elapsedSeconds": 754, "remainingSeconds": 446, "running": true, "label": "12:34"}` — either
  seconds field may be `null` (leagues count in different directions); `running` is `null`
  without `CLOCK_RUNNING_FLAG`. `label` is the display form in the sport's convention
  (`"12:34"` hockey, `"67'"` / `"45'+2"` football) or `null`. In football `elapsedSeconds`
  is time within the current half (the label carries the cumulative minute).
- `situation`: sport-specific live state with a `kind`, or `null`. Baseball (MLB) sends it
  while `LIVE`/`INTERMISSION`:
  `{"kind": "baseball", "inning": 7, "half": "BOTTOM", "outs": 0, "balls": 1, "strikes": 2, "onFirst": null, "onSecond": PlayerRef, "onThird": null, "batter": PlayerRef, "pitcher": PlayerRef, "onDeck": PlayerRef}`
  — `half` is `TOP | MIDDLE | BOTTOM | END` (`MIDDLE`/`END` are the breaks, state `INTERMISSION`).
  The baseball `clock` is period-only: `{"period": {"number": 7, "type": "REGULATION", "label": "7"}, "elapsedSeconds": null, "remainingSeconds": null, "running": null, "label": "Bot 7"}`;
  extra innings are `OVERTIME` periods labelled `10`, `11`, …
  A fight (UFC) sends it in every state:
  `{"kind": "fight", "scheduledRounds": 5, "roundMinutes": [5, 5, 5, 5, 5], "weightClass": "Flyweight", "title": "UFC Flyweight Title", "cardSegment": "Main", "cardPosition": 1, "result": null}`
  — `result`, once decided, is `{"winner": "<home or away team id>" | null, "method": "KO_TKO | SUBMISSION | DECISION | NO_CONTEST | OVERTURNED | OTHER", "methodLabel": "Decision - Unanimous", "round": 5, "time": "5:00", "detail": "Rear Naked Choke" | null, "notes": null, "homeOutcome": "WIN | LOSS | DRAW | NO_CONTEST", "awayOutcome": …, "homeBonuses": ["Performance of the Night"], "awayBonuses": [], "fightOfTheNight": false, "scorecards": [{"judge": "Sal D'amato", "home": 50, "away": 45}]}`.
  A fight's `score` is always `null`; `cardPosition` 1 is the main event.
- `periodScores`: one entry per segment played so far, in order. A shootout is one entry
  credited 1–0 to the winner. Absent (empty) without `PERIOD_SCORES`. Baseball: one entry
  per inning; a half-inning not played reads `0`.
- `ending`: `REGULATION | OVERTIME | SHOOTOUT | null`, set once `FINAL`.
- `events`: `null` when the route does not include them, `[]` when nothing has happened.
- `Period.type`: `REGULATION | OVERTIME | SHOOTOUT | UNKNOWN`; `number` counts across all
  segments (NHL: 1–3, OT = 4, SO = 5; football: 1H = 1, 2H = 2, ET1 = 3, ET2 = 4, PENS = 5);
  `label` is display text (`1`, `OT`, `2OT`, `SO`, `1H`, `2H`, `ET1`, `PENS` …).

### Event

```json
{
  "id": "354", "type": "goal", "rawType": "goal",
  "period": {"number": 2, "type": "REGULATION", "label": "2"},
  "elapsedSeconds": 505, "remainingSeconds": 695, "timeLabel": "08:25",
  "team": {"league": "nhl", "id": "MTL", "name": "Montréal Canadiens", "abbreviation": "MTL", "logoUrl": "…", "clubId": "montreal-canadiens"},
  "players": [{"league": "nhl", "id": "8481540", "name": "Cole Caufield", "jerseyNumber": 13, "position": "R", "headshotUrl": "…"}, "…"],
  "score": {"home": 0, "away": 1},
  "coordinates": {"x": -87.0, "y": -3.0},
  "description": "Goal — Cole Caufield (15) · Juraj Slafkovský, Lane Hutson [PP]",
  "details": {"kind": "goal", "scorer": {"…"}, "assists": ["…"], "strength": "PP", "emptyNet": false, "shotType": "wrist", "goalie": {"…"}, "scorerSeasonTotal": 15},
  "sortOrder": 407
}
```

- `timeLabel`: display form of the event time (`"08:25"`, `"67'"`, `"90'+4"`) or `null`.
- `players`: primary actor first (scorer, penalised player, shooter, faceoff winner …).
- `score`: score **after** the event, where the league says (goals).
- `coordinates`: league-native surface coordinates (see the league README); only with `EVENT_COORDINATES`.
- `details.kind` selects the sport-specific shape below; `details` is `null` for events without one.

#### Hockey event types and details

| `type` | `details.kind` | Details fields |
|---|---|---|
| `goal` | `goal` | `scorer`, `assists[]`, `strength` (`EV PP SH EN PS`), `emptyNet`, `shotType`, `goalie`, `scorerSeasonTotal` |
| `penalty` | `penalty` | `player`, `drawnBy`, `servedBy`, `minutes`, `infraction`, `severity` (league-native: `MIN`, `MAJ` …) |
| `delayed-penalty` | — | |
| `shot`, `missed-shot`, `blocked-shot` | `shot` | `shooter`, `outcome` (`ON_GOAL MISSED BLOCKED`), `goalie`, `blockedBy`, `shotType`, `reason` |
| `hit` | `hit` | `hitter`, `hittee` |
| `faceoff` | `faceoff` | `winner`, `loser` |
| `giveaway`, `takeaway` | — | player in `players[0]` |
| `stoppage` | `stoppage` | `reason` |
| `period-start`, `period-end`, `game-end` | — | |
| `goalie-change` | — | |
| `shootout-attempt` | `shootout-attempt` | `shooter`, `goalie`, `scored`, `shotType` — shootout goals are **not** `goal` events |
| `other` | — | see `rawType` |

Player-valued fields are `PlayerRef` objects (`league, id, name, jerseyNumber, position, headshotUrl`) or `null`.

#### Football event types and details

| `type` | `details.kind` | Details fields |
|---|---|---|
| `goal`, `own-goal`, `penalty-goal` | `football-goal` | `scorer`, `assist`, `goalKind` (`OPEN_PLAY PENALTY OWN_GOAL FREE_KICK HEADER OTHER`), `varDecision` (`CONFIRMED OVERTURNED` or null), `scorerSeasonTotal`. All three count as goals; `score` is the score after the goal. `team` is the side credited with the goal — on an `own-goal` the scorer's opponents, while `scorer` plays for the other side |
| `penalty-missed` | `penalty-miss` | `player`, `savedBy`, `outcome` |
| `goal-disallowed` | `goal-disallowed` | `player`, `reason` |
| `yellow-card`, `second-yellow`, `red-card` | `card` | `player`, `card` (`YELLOW SECOND_YELLOW RED`), `reason` (league-native text) |
| `substitution` | `substitution` | `playerOn`, `playerOff`, `reason` (`tactical`, `injury` … where known) |
| `var` | — | review text in `description` |
| `period-start`, `period-end`, `game-end` | — | half/extra-time boundaries; `game-end` is the final whistle |
| `shootout-attempt` | `shootout-attempt` | one kick of a penalty shoot-out: `shooter`, `goalie`, `scored` — shoot-out goals are **not** `goal` events |
| `other` | — | shots, corners, offsides, timeouts etc. where the feed has them; see `rawType` |

Football `Period` labels are `1H`, `2H`, `ET1`, `ET2`, `PENS`; `elapsedSeconds` counts within the half and `timeLabel` gives the conventional cumulative minute with stoppage (`"90'+4"`).

#### Baseball event types and details

Events come at two granularities: one per **completed plate appearance** (`id` = the
at-bat index) and the base-running plays, substitutions and ejections in between
(`id` = `atBat.index`, sorted before the plate appearance they happened in). Pitches,
timeouts, mound visits and status advisories are not events. No baseball type counts as a
goal; `score` is set on scoring plays only. `timeLabel` is `Top 3` / `Bot 9`; `coordinates`
are the spray-chart position of a ball in play.

| `type` | `details.kind` | Details fields |
|---|---|---|
| `single`, `double`, `triple`, `home-run`, `walk`, `intentional-walk`, `hit-by-pitch`, `strikeout`, `field-out`, `force-out`, `fielders-choice`, `double-play`, `triple-play`, `sacrifice-fly`, `sacrifice-bunt`, `error`, `interference`, `runner-out` (as a play result) | `plate-appearance` | `batter`, `pitcher`, `rbi`, `out`, `outsAfter`, `scoringPlay`, `pitches`, `runners[]` (`runner`, `from`, `to` (`1B 2B 3B score`), `out`, `scored`), `battedBall` (`launchSpeedMph`, `launchAngle`, `distanceFt`, `trajectory`) or null. `players` = batter, pitcher |
| `stolen-base`, `caught-stealing`, `pickoff`, `wild-pitch`, `passed-ball`, `balk`, `runner-out` | `base-running` | `runner`, `from`, `to`, `out` — MLB fills only `runner` and `out`; the movement is in `description` |
| `substitution` | `baseball-substitution` | `playerIn`, `replaces` (outgoing player's name, or null), `position` (`P`, `PH`, `PR`, `C` …), `substitutionKind` (`pitching`, `offensive`, `defensive`, `switch`). `team` is the side making the change |
| `ejection` | — | |
| `other` | — | injuries, runner placed on second in extras, defensive indifference …; see `rawType` |

### Lineup

```json
{"gameId": "2025030416",
 "team": {"league": "nhl", "id": "VGK", "…": "…"},
 "groups": [{"kind": "FORWARDS", "label": "Forwards", "players": ["PlayerRef", "…"]}, {"kind": "DEFENSE", "…": "…"}, {"kind": "GOALIES", "…": "…"}],
 "headCoach": null,
 "formation": null}
```

Football lineups use `STARTERS` (in formation order, goalkeeper first) and `BENCH`, with `formation` as digits per line (`"433"`, `"41212"`) where published.

`kind`: `FORWARDS DEFENSE GOALIES LINE PAIRING STARTERS BENCH SCRATCHES OTHER`. Real
line/pairing structure only where the league has `LINE_GROUPS`.

### Standings

```json
{"league": "nhl", "seasonId": "20252026", "stage": "REGULAR", "grouping": "division",
 "groups": [{"label": "Atlantic", "rows": [
   {"team": {"…"}, "rank": 1, "played": 82, "wins": 50, "losses": 23, "draws": null, "otherLosses": 9,
    "points": 109, "goalsFor": 280, "goalsAgainst": 230, "goalDifference": 50,
    "extra": {"conference": "Eastern", "conferenceRank": "1", "leagueRank": "3", "clinch": "y", "regulationWins": "40", "streak": "W3", "home": "27-11-3", "road": "23-12-6", "last10": "7-2-1"}}
 ]}]}
```

`grouping`: `division | conference | league | group`. `extra` keys are per league and
documented in the league README; values are always strings.

### Team, Player

```json
{"league": "nhl", "id": "TOR", "name": "Toronto Maple Leafs", "abbreviation": "TOR",
 "logoUrl": "…", "logoDarkUrl": "…", "placeName": "Toronto", "commonName": "Maple Leafs",
 "arena": null, "conference": "Eastern", "division": "Atlantic", "country": null,
 "clubId": "toronto-maple-leafs"}
```

Every team reference (`home`, `away`, event `team`, standings `team`, `Team`) carries
`clubId`: OpenScore's own stable slug for the club, identical across leagues —
`"manchester-united"` whether the game comes from `premier-league` or `ucl`, `"hammarby"` from
`allsvenskan` or `fogis`. It is `null` when the club is not in the crosswalk (lower-tier
Svenska Cupen sides, for instance). Key favourites on `clubId ?? league + "/" + id`. See
[data-model.md](data-model.md#clubs-across-leagues).

```json
{"league": "nhl", "id": "8478403", "name": "Jack Eichel", "firstName": "Jack", "lastName": "Eichel",
 "jerseyNumber": 9, "position": "C", "headshotUrl": "…", "birthDate": "1996-10-28",
 "birthPlace": "North Chelmsford, Massachusetts", "nationality": "USA", "heightCm": 188, "weightKg": 94,
 "handedness": "R", "teamId": "VGK", "active": true}
```

`position` is the league's own code (`C L R D G` in the NHL, `VP/OP/KH/VL/OL/MV` in Liiga,
`GK/D/F` on Sportality, `GK/DE/FW` in the CHL; football providers normalise to `GK/DF/MF/FW`);
`nationality` is as the league gives it (alpha-3 for the NHL/Liiga/CHL/Ligue 1, alpha-2 for
SHL/Premier League/LaLiga, country names for Malta/Allsvenskan). `country` on a team is alpha-3 where known.

## Versioning

Additive changes (new fields, new enum values, new `details.kind`s, new capabilities)
happen within v1 — consumers must ignore unknown fields and tolerate unknown enum
values. Anything that removes or re-types a field is a new `/v2`.

### Team season totals and schedule dates

`TeamSeasonStats` has `leagueId`, `teamId`, `seasonId` and `groups[]`.
Each group has a stable provider-defined `key`, a display `label` and `stats[]`;
each stat has `key`, `label`, `value`. Values are strings, including counts, rates
and baseball innings (for example `.235`, `204`, `1332.1`). Empty groups mean no
statistics available; missing statistics are omitted. MLB supplies `hitting`
(Batting) and `pitching` (Pitching) groups.

Games have an additive nullable `scheduleDate` (`YYYY-MM-DD`): the official date
under which the provider files the game, where supplied. This can differ from
`startTime`'s calendar date in UTC or the viewer's timezone. MLB schedule responses
supply it through `officialDate`; other providers and match detail responses may
leave it null. Where it is null, the day a game is filed under is `startTime` read in the
league's `zone` - never in the viewer's, which names a different day either side of midnight
and is not a day `/v1/games` would return that game for.

`startTimeTbd` (additive boolean, default `false`) says the league has fixed the day but
not the kick-off time yet: `startTime` is then the start of that day in the league's zone
and should be shown as a date only. Serie A publishes such rows (`"matchDateUtc":
"2026-11-28Z"`, `isUnknownKickOffTime: true`) for matchdays weeks ahead.
