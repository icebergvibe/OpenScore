# Core data model

> Implemented in [`core/src/commonMain/kotlin/org/openscore/model`](../core/src/commonMain/kotlin/org/openscore/model).
> This page is the readable summary; the Kotlin source is the source of truth.

## Design goals

- **Sport-agnostic spine, sport-specific leaves.** `Game`, `Team`, `GameState`, `GameEvent`
  are shared. Event types, event payloads and live "situation" payloads come from a
  per-sport package (`model.hockey`, `model.football`, `model.baseball`).
- **Everything is keyed by (league, id).** IDs are strings; league ids are stable slugs
  (`nhl`, `shl`, `liiga`, …). Never assume ids are numeric or globally unique.
- **Clubs are linked across leagues by a curated crosswalk**, not by name: `TeamRef.clubId`
  is OpenScore's own slug for the club (`manchester-united` in both the Premier League and
  Champions League feeds, `hammarby` in Allsvenskan and the Svenska Cupen feed). See
  [Clubs across leagues](#clubs-across-leagues).
- **Times are UTC instants** (`kotlin.time.Instant`). Conversion to local time is the app's job.
- **Partial data is normal.** Most fields are nullable. A provider returns what its API
  gives; the core never invents values. `Capability` says up front what a league can do.

## Entities

```
League         id, sport (HOCKEY | FOOTBALL | BASEBALL | MOTORSPORT | MMA), name, country?, websiteUrl?
Season         leagueId, id, label ("2026–27"), start?, end?, stages[], current
Stage          id, kind (PRESEASON | REGULAR | PLAYOFF | OTHER), label

Game           leagueId, id, seasonId?, stage?, startTime (UTC), venue?,
               home: TeamRef, away: TeamRef,
               state: GameState, score: Score?, clock: Clock?,
               situation: GameSituation?   sport-specific live state (baseball); null where a clock says it all
               periodScores: [PeriodScore], ending: GameEnding?,
               events: [GameEvent]?      null = not loaded, [] = loaded and none yet
               rawState?                 provider's raw status for debugging

GameState      SCHEDULED | PRE_GAME | LIVE | INTERMISSION | FINAL | POSTPONED | SUSPENDED |
               CANCELLED | UNKNOWN       (isLive covers LIVE + INTERMISSION)
Score          home, away
Period         number (1-based across all segments), type (REGULATION | OVERTIME | SHOOTOUT),
               label ("1", "OT", "2OT", "SO", "1H", "ET1" …)
GameTime       period, elapsed?, remaining?   at least one set when a clock exists
Clock          time: GameTime, running: Boolean?   (null where the feed has no flag)
GameEnding     REGULATION | OVERTIME | SHOOTOUT
PeriodScore    period, home, away
GameSituation  marker interface; sport packages provide BaseballSituation, …

GameEvent      id, type: EventType, rawType, time: GameTime, team?: TeamRef,
               players: [PlayerRef]  (primary actor first), score?  (after the event),
               coordinates?, description?, details: EventDetails?, sortOrder?
EventType      interface { key }; each sport has an enum implementing it
EventDetails   marker interface; sport packages provide GoalDetails, PenaltyDetails, …

TeamRef        leagueId, id, name, abbreviation?, logoUrl?, clubId?   (clubId: cross-league club slug, below)
Team           ref + placeName?, commonName?, arena?, conference?, division?, logoDarkUrl?
PlayerRef      leagueId, id, name, jerseyNumber?, position? (league-native code), headshotUrl?
Player         ref + firstName?, lastName?, birthDate?, birthPlace?, nationality?,
               heightCm?, weightKg?, handedness?, teamId?, active?

Lineup         gameId, team, groups: [LineupGroup], headCoach?
LineupGroup    kind (FORWARDS | DEFENSE | GOALIES | LINE | PAIRING | STARTERS | BENCH |
               SCRATCHES | OTHER), label, players

StandingsTable leagueId, seasonId?, stage?, grouping ("division" | "conference" | "league" | "group"),
               groups: [StandingsGroup]
StandingsGroup label, rows
StandingsRow   team, rank, played, wins, losses, draws?, otherLosses?, points,
               goalsFor?, goalsAgainst?, goalDifference?, extra: Map<String, String>
```

## Provider interface

```kotlin
interface LeagueProvider {
    val league: League
    val capabilities: Set<Capability>
    suspend fun gamesOn(date: LocalDate): List<Game>   // date in the league's own convention
    suspend fun game(id: String): Game
    suspend fun events(gameId: String): List<GameEvent>
    suspend fun lineups(gameId: String): List<Lineup>
    suspend fun standings(seasonId: String? = null): StandingsTable
    suspend fun team(id: String): Team
    suspend fun roster(teamId: String): List<Player>
    suspend fun player(id: String): Player
    fun live(gameId: String, interval: Duration = 10.seconds): Flow<Game>
}
```

`Capability` covers both the methods (`GAMES_BY_DATE`, `GAME`, `EVENTS`, `LINEUPS`,
`STANDINGS`, `TEAM`, `ROSTER`, `PLAYER`, `LIVE_UPDATES`, `LIVE_PUSH`) and the data facts
mapping revealed (`CLOCK`, `CLOCK_RUNNING_FLAG`, `INTERMISSION_STATE`, `PERIOD_SCORES`,
`EVENT_COORDINATES`, `LINE_GROUPS`). Unsupported methods throw
`UnsupportedCapabilityException`; apps check the set instead.

`BaseLeagueProvider` gives every method an "unsupported" default and a polling `live()`
that calls `game()` no faster than every 10 s until the game is final. Push-capable
leagues (SHL/HA via SSE, KHL via MQTT, Bundesliga/Allsvenskan via SSE) will override it.

## Hockey extension (`model.hockey`)

```
HockeyEventType   GOAL | PENALTY | DELAYED_PENALTY | SHOT | MISSED_SHOT | BLOCKED_SHOT | HIT |
                  FACEOFF | GIVEAWAY | TAKEAWAY | STOPPAGE | PERIOD_START | PERIOD_END |
                  GAME_END | GOALIE_CHANGE | SHOOTOUT_ATTEMPT | OTHER
Strength          EV | PP | SH | EN | PS
GoalDetails       scorer, assists[], strength?, emptyNet?, shotType?, goalie?, scorerSeasonTotal?
PenaltyDetails    player, drawnBy?, servedBy?, minutes?, infraction?, severity?
ShotDetails       shooter, outcome (ON_GOAL | MISSED | BLOCKED), goalie?, blockedBy?, shotType?, reason?
FaceoffDetails    winner, loser
HitDetails        hitter, hittee
StoppageDetails   reason
ShootoutAttemptDetails  shooter, goalie, scored, shotType?
```

## Football extension (`model.football`)

```
FootballEventType  GOAL | OWN_GOAL | PENALTY_GOAL | PENALTY_MISSED | GOAL_DISALLOWED | YELLOW_CARD |
                   SECOND_YELLOW | RED_CARD | SUBSTITUTION | VAR | PERIOD_START | PERIOD_END |
                   GAME_END | SHOOTOUT_ATTEMPT | OTHER
GoalKind           OPEN_PLAY | PENALTY | OWN_GOAL | FREE_KICK | HEADER | OTHER
FootballGoalDetails    scorer, assist?, kind, varDecision?, scorerSeasonTotal?
CardDetails            player, card (YELLOW | SECOND_YELLOW | RED), reason?
SubstitutionDetails    playerOn, playerOff, reason?
PenaltyMissDetails     player, savedBy?, outcome?
DisallowedGoalDetails  player, reason?
```

`GameEvent.team` on a goal is always the side **credited** with it. For an `OWN_GOAL` that is
the scorer's opponents — the event sits with the goal it adds to, as on any scoreboard — while
`details.scorer` plays for the other side. The feeds disagree (Ligue 1, Serie A, Fogis and
UEFA already list own goals under the beneficiary; Pulselive, LaLiga and MLS attribute the
event to the scorer's team), so each mapper normalises rather than passing the feed's team
through; a running `score` is derived after that normalisation.

## Baseball extension (`model.baseball`)

```
BaseballEventType  SINGLE | DOUBLE | TRIPLE | HOME_RUN | WALK | INTENTIONAL_WALK | HIT_BY_PITCH | STRIKEOUT |
                   FIELD_OUT | FORCE_OUT | FIELDERS_CHOICE | DOUBLE_PLAY | TRIPLE_PLAY | SACRIFICE_FLY |
                   SACRIFICE_BUNT | ERROR | INTERFERENCE | STOLEN_BASE | CAUGHT_STEALING | PICKOFF |
                   WILD_PITCH | PASSED_BALL | BALK | RUNNER_OUT | SUBSTITUTION | EJECTION | OTHER
                   (none is a "goal": scoring plays are flagged in the details and carry GameEvent.score)
InningHalf         TOP | MIDDLE | BOTTOM | END
BaseballSituation  inning, half, outs, balls, strikes, onFirst?, onSecond?, onThird?, batter?, pitcher?, onDeck?
PlateAppearanceDetails      batter, pitcher, rbi, out, outsAfter, scoringPlay, pitches, runners[RunnerMovement], battedBall?
RunnerMovement              runner, from?, to? ("1B" | "2B" | "3B" | "score"), out   (scored = to == "score")
BattedBall                  launchSpeedMph?, launchAngle?, distanceFt?, trajectory?
BaseRunningDetails          runner, from?, to?, out
BaseballSubstitutionDetails playerIn, replaces? (name), position?, kind? (pitching | offensive | defensive | switch)
```

Innings are `Period`s (`label` = inning number, `OVERTIME` past the scheduled nine); the
`Clock` carries only the period and a `Top 7` / `Bot 7` label, and `Game.situation` holds
the rest. Events come at two granularities — one per completed plate appearance and the
base-running / substitution plays in between; pitches are not events.

## Combat extension (`model.combat`)

```
CombatEventType   KNOCKDOWN | TAKEDOWN | TAKEDOWN_ATTEMPT | SUBMISSION_ATTEMPT | REVERSAL | ROUND_START | ROUND_END |
                  PAUSE | RESUME | WALKOUT | FIGHT_START | FIGHT_END | RESULT | OTHER
FightMethod       KO_TKO | SUBMISSION | DECISION | NO_CONTEST | OVERTURNED | OTHER
FightOutcome      WIN | LOSS | DRAW | NO_CONTEST
Scorecard         judge, home, away
FightResult       winner?, method, methodLabel, round?, time? ("2:15" into the round), detail?, notes?,
                  homeOutcome?, awayOutcome?, scorecards[], homeBonuses[], awayBonuses[], fightOfTheNight
FightSituation    scheduledRounds, roundMinutes[], weightClass?, title?, cardSegment?, cardPosition?, result?
```

A fight (UFC) is a `Game` between two fighters: the red corner is `home`, the blue corner
`away` (each a `TeamRef` whose id is the fighter's), rounds are `Period`s (`R1`; a
tournament bout's extra round is `OVERTIME`), and there is no `Score` — ever. What a
scoreboard shows instead is the `FightSituation`, present on every fight: the bout's format
and, once decided, the `FightResult` — winner, method, round and time, and the night's
bonuses per corner; there are no `credits`. Events are the tracked actions, timed as results
are stated (into the round); a pause carries its reason (`Low blow`) as the description. The card a fight belongs
to is `Game.competition`, and its fights share the card segment's start time.

## Design notes worth knowing

- **Shootouts are events**: each attempt is a `SHOOTOUT_ATTEMPT` in a `Period` of type
  `SHOOTOUT`, and the shootout counts as a 1–0 `PeriodScore` for the winner. Shootout goals
  are *not* `GOAL` events.
- **`Clock` is `GameTime` + `running?`** because leagues count in different directions (NHL
  down, Liiga up, football up in minutes) and some have no running flag. CHL, KHL and MLB
  have no clock at all → `CLOCK` is absent from their capabilities; KHL and MLB still send a
  period-only `Clock` so a scoreboard knows where the game is.
- **`Game.situation`** exists because a baseball scoreboard is inning half, outs, count and
  runners, none of which fit a `Clock`. It is a sport-specific leaf behind a marker
  interface, so hockey and football never see it.
- **`INTERMISSION` is opt-in per league** (`INTERMISSION_STATE`): NHL and SHL/HA expose it,
  others stay `LIVE` between periods.
- **`Period.number` counts across all segments** (NHL: 1–3, OT = 4, SO = 5); the human label
  is separate so "2OT" and "ET1" need no sport logic in apps.
- **`LineupGroupKind`** distinguishes positional groups (NHL boxscore) from real
  lines/pairings (CHL, Liiga, SHL) via the `LINE_GROUPS` capability.
- **`StandingsRow.extra`** carries league-only columns (`regulationWins`, `wildcard`,
  `streak`, home/road/last-10 records for the NHL) rather than growing the row type.

## Clubs across leagues

Every entity is keyed by `(league, id)`, and each feed has its own id system, so the same
club arrives under unrelated ids: Manchester United is Pulselive `1` in the Premier League
feed and `52682` in UEFA's; Frölunda is a Sportality uuid in the SHL and a Corebine hash in
the CHL. Names are worse than ids ("Man Utd" / "Manchester United"; Hammarby IF vs Hammarby
TFF; Inter vs Inter Escaldes), and no external id (Opta, Wikidata) is present in every feed.

So `TeamRef.clubId` is filled from a **hand-curated crosswalk**,
[`core/src/commonMain/kotlin/org/openscore/clubs/ClubTable.kt`](../core/src/commonMain/kotlin/org/openscore/clubs/ClubTable.kt):
one line per club, every native id it has in our feeds on that line, keyed by an *id
namespace* (a league id, except where several leagues share one team-id space — `ucl` also
covers `uel`/`uecl`, `fogis` covers the Swedish leagues, `shl` covers HockeyAllsvenskan —
plus `sportomedia` and `espn` for the two non-league sources; `Clubs.namespace(leagueId)`
is the one place that is encoded). Nothing is derived at runtime and nothing is matched by
name. `null` means "not in the table", normal for lower-tier cup sides; apps key favourites
on `clubId ?: "$leagueId/$id"`.

A slug is permanent — never reused, never renamed; a club that renames itself keeps its
slug and gets a new `Club.name`. The slug rules are in the table's header comment.
`ClubsTest` checks the table is well-formed offline; `ClubsCoverageLiveTest`
(`-Dopenscore.live=true`) checks every team in every league's standings has a club and
prints the missing ones as ready-to-paste lines — run it at the start of each season and
after each UEFA/CHL draw.
