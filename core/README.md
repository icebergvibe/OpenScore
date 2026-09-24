# core

Kotlin Multiplatform library that turns the APIs documented in [`apis/`](../apis) into one
sport-agnostic model. Targets: **JVM** and **Android** today; all code lives in
`commonMain`, so desktop/web targets are a build-file change, not a rewrite.

```
org.openscore.model          Sport-agnostic spine: League, Season, Game, GameEvent, Team,
                             Player, Lineup, StandingsTable  (docs/data-model.md)
org.openscore.model.hockey   Hockey leaves: HockeyEventType, GoalDetails, PenaltyDetails, …
org.openscore.model.floorball Floorball leaves: FloorballEventType; the rink details are hockey's, aliased
org.openscore.model.football Football leaves: FootballEventType, FootballGoalDetails, CardDetails, …
org.openscore.model.baseball Baseball leaves: BaseballEventType, PlateAppearanceDetails, BaseballSituation (Game.situation)
org.openscore.model.motorsport Racing: RacingSeason, RacingRound, RacingSession, RacingClassification, RacingStandings
org.openscore.model.combat   Fights: FightSituation (Game.situation: division, rounds, card slot, result), FightResult, CombatEventType
org.openscore.clubs          Club crosswalk: TeamRef.clubId, the same club across leagues (docs/data-model.md)
org.openscore.provider       LeagueProvider interface, Capability, BaseLeagueProvider (polling live())
                             and RacingProvider for non-two-team racing surfaces
org.openscore.net            Fetcher (read-only GET), KtorFetcher (UA, cache floor, ETag), OpenScoreJson
org.openscore.cache          Durable, normalized stores an app plugs in: DayListingStore + CachedDayListingProvider
                             (a settled or upcoming day is served from it, a due day is always read live, the last
                             read stands in offline), SeasonScheduleStore (HockeyAllsvenskan's season, UFC's cards)
org.openscore.feed           Feed v1: the JSON contract (docs/feed-v1.md) + FeedMapper (model → feed)
org.openscore.OpenScore      Aggregator: all providers behind one door, cross-league gamesOn()
org.openscore.providers.*    One package per platform — each DTOs + Mapper + Provider:
                             hockey: nhl, liiga, sportality (SHL, and HockeyAllsvenskan's own provider), chl, khl, del
                             floorball: ssl (the Sportality platform again, so it reuses its DTOs), fliiga
                             football: ligue1, bundesliga, premierleague, efl (Championship, Carabao Cup),
                                       seriea, laliga, mls, malta,
                                       fogis (all Swedish tiers, XML; SwedishLeagueProvider cuts Allsvenskan,
                                       Superettan and Svenska Cupen out of it, tables from sportomedia),
                                       uefa (Champions / Europa / Conference League)
                             espn: EspnRosters — squads only, for the Bundesliga and UEFA-only clubs
                             football/FootballPeriods: shared 1H/2H/ET/PENS conventions and minute labels
                             baseball: mlb
                             motorsport: jolpica — JolpicaProvider, the one RacingProvider (Formula 1)
                             mma: ufc
org.openscore.testing (jvm)  SampleFetcher + per-league sample routes, for tests and offline mode
```

## Leagues

| League id | Provider | Notable gaps (see `capabilities`) |
|---|---|---|
| `nhl` | `NhlProvider` | positional lineups only |
| `liiga` | `LiigaProvider` | no running flag on the clock; intermission is a heuristic |
| `shl` | `ShlProvider` (`SportalityProvider`) | SSE push not wired yet; event/lineup player ids are Statnet ids, roster ids are athlete UUIDs |
| `hockeyallsvenskan` | `HockeyAllsvenskanProvider` | the site has no day route: the whole season is read from the match page and kept as a normalized snapshot (`SeasonScheduleStore`), refreshed after six hours, with due/live/just-finished games re-read one by one; lineups in line/pairing structure from the game page (`LINE_GROUPS`, about two hours before the puck drop); the game document says the period and score in play but has no clock and lagged the ice on the opening night — no standings, events or `LIVE_UPDATES` (play-by-play is a POST route, push is MQTT with handed-out credentials) |
| `chl` | `ChlProvider` | no clock, no shots/coordinates, penalty details only as text |
| `khl` | `KhlProvider` | no clock (period only), no roster/player endpoints, MQTT push not wired |
| `del` | `DelProvider` | the official app's backend (one `query.php`, `requestName=` selects the dataset); game ids are the feed's `uniqueID` (`4389t77`, the tournament half is required by every per-game read); events with strength and assists (assists resolved by jersey number through the two rosters, read once an hour), shots with coordinates through `events()`, lines and pairings; no team stats; the live codes and the elapsed-seconds clock are mapped from the app's string table but unobserved, so `CLOCK` is not claimed yet; playoff series games that were never needed are dropped from listings (`CANCELLED` when read directly) |
| `ssl` | `SslProvider` | ssl.se is the same Sportality platform as shl.se, so the bootstrap, scoreboard, schedule, game, team and athlete routes reuse the `Spt*` DTOs and only the table's `Reg*`/`OTW` columns and the promo-bar totals are SSL's own; the shared game-day routes answer empty (or `500` for the boxscore) for a floorball game, so there are no events, lineups, period scores or clock, and `Game.events` is `null` rather than empty; a scoreboard row that should be under way is refined from `game-info`, the only route with a state; no `LIVE_UPDATES` until a match is captured |
| `f-liiga` | `FliigaProvider` | the season's fixtures come from the `ottelut` WordPress records with a trimmed `_fields` (three pages, ~2 KB each) rather than the 327 KB page; teams are TorneoPal club ids, bridged from the season-team ids the match documents carry through the table and `joukkueet`; `match_teams` is the whole match (lineups by line, events with rink coordinates, period scores) and is never the score poll - a match inside the result window is read from the 5 KB card instead; a record's goals are written after the match, sometimes hours late, so they count as a result only once they say something; the overtime period score is derived (the feed's `overtime` entry is the score *entering* overtime) and a shoot-out is told from the events' running shoot-out score; `Live`/`Break`/`Penalties` are mapped from the site's own script but unobserved, so no `LIVE_UPDATES`, `CLOCK` or `INTERMISSION_STATE` |
| `ligue1` | `Ligue1Provider` | second-precision clock; 150–500 KB match resource → live() polls at 20 s |
| `bundesliga` | `BundesligaProvider` | Firebase RTDB; whole-minute clock; squads from ESPN through the crosswalk (the DFL's own are key-gated), no player endpoint; SSE not wired |
| `premier-league` | `PremierLeagueProvider` | five small calls per game; own goals credited to the beneficiary from the `events` grouping (the timeline attributes them to the scorer's team) |
| `championship`, `carabao-cup` | `ChampionshipProvider`, `CarabaoCupProvider` (shared `EflProvider`) | one match document carries header, lineups and events (plus the five-column stats route once started); minute-only clock; the play-offs are `PLAYOFF` rounds of the Championship, cup ties are `OTHER` with the round as `Game.competition`; no squad or player route, no table for the cup; `live()` polls at 15 s (nothing is edge-cached); in-play states not yet observed. Team ids `t{opta}` under the crosswalk namespace `efl` |
| `serie-a` | `SerieAProvider` | 40 s edge cache; roster = the season's registrations (`?seasonId=`); no player endpoint |
| `la-liga` | `LaLigaProvider` | needs the public page-embedded keys (`LaLigaKeys.discover`); second-precision period timestamps |
| `mls` | `MlsProvider` | first-party `stats-api` (`no-store`, honoured) + `sportapi` pre-game metadata; no live capability until a live sample is captured; club schedule spans every competition the club plays |
| `malta-premier` | `MaltaProvider` | minute-only events, no positions/formations/stats or reliable team roster; one `result` call per started match |
| `allsvenskan`, `superettan`, `svenska-cupen` | `SwedishLeagueProvider` (games, live, events, lineups from `FogisProvider`; standings/team/roster/player from the Sportomedia `AllsvenskanProvider` / `SuperettanProvider`; the cup has no table) | Fogis answers every live tick where Sportomedia rate-limits and freezes its clock, so the games come from Fogis and only the tables from Sportomedia. Fogis team ids under these league ids (crosswalk namespace `fogis`) |
| `ucl`, `uel`, `uecl` | `ChampionsLeagueProvider`, `EuropaLeagueProvider`, `ConferenceLeagueProvider` (shared `UefaProvider`) | second-precision clock from the timeline's phase markers; squads from ESPN through the crosswalk (UEFA has no squad endpoint); no assists in the MAIN timeline; `live()` polls the 300-byte `livescore` hash and re-reads the match on change; live states not yet observed |
| `fogis` | `FogisProvider` | every SvFF competition in one feed (`Game.competition`), an umbrella apps treat as opt-in; XML; no standings/team/player; clock derived from wall-clock stamps, on the day view too |
| `mlb` | `MlbProvider` | no clock — period-only `Clock` (`Bot 7`) plus `Game.situation` (outs, count, runners); `live()` polls the 120 KB (gzipped) feed at 10 s; standings `points` = wins; probable pitchers / decisions not mapped |
| `ufc` | `UfcProvider` | UFC, Contender Series and Road to UFC from the live-stats JSON behind ufc.com; game ids `{eventId}-{fightId}`, the red corner is `home`, `Game.competition` is the card. No listing route: the cards it knows are a snapshot (`SeasonScheduleStore`) and new ones are found by sweeping the id frontier every six hours (a cold start reads ~30 cards); a card due or under way is re-read at the live floor. `game()` adds the fight route's strike/takedown stats once a fight has started. No score, standings, team, roster or player; `FightSituation` carries the division, rounds, belt, card slot and — once decided — the result with scorecards. Live states not yet observed, so no `CLOCK` |
| `f1` | `JolpicaProvider` (a `RacingProvider`, reached through `OpenScore.racingProviderOrNull`) | [Jolpica F1](https://github.com/jolpica/jolpica-f1)'s open-source, Ergast-compatible API (4 req/s burst, 500/h; every response cached a day): season calendar with sessions, race/qualifying/sprint classifications, driver and constructor tables; no live timing, no practice results; not yet mapped under `apis/` |

## Using it

```kotlin
val fetcher = KtorFetcher()                       // one per app; caches per URL
val nhl = NhlProvider(fetcher)

val games = nhl.gamesOn(LocalDate(2026, 10, 7))   // NHL buckets by US Eastern date
val game = nhl.game(games.first().id)             // state, score, clock, period scores, events
nhl.live(game.id).collect { println("${it.score} ${it.clock}") }   // polls every 10 s until final

if (nhl.supports(Capability.LINEUPS)) nhl.lineups(game.id)

// Or across every league, and as Feed v1 JSON:
val all = OpenScore.default(fetcher)
val today = all.gamesOn(LocalDate(2026, 10, 7))          // games from all leagues + per-league errors; Fogis copies of Allsvenskan/Superettan games are dropped
all.gamesOnProgressively(LocalDate(2026, 10, 7)).collect { partial -> /* one emission per league answered; partial.pending names the rest */ }
val json = FeedJson.encodeToString(FeedGame.serializer(), FeedMapper.game(today.games.first()))
```

Every `LeagueProvider` method is optional; check `capabilities` instead of catching
`UnsupportedCapabilityException`.

## Politeness is built in

`KtorFetcher` implements [docs/principles.md](../docs/principles.md): a descriptive
`User-Agent`, an in-memory cache that refuses to re-request a URL younger than the
caller's `maxAge` (providers use 10 s for live game endpoints, minutes/hours for tables),
`If-None-Match` revalidation, bounded request deadlines, and GET only. `pollGame()` clamps
the live interval to 10 s. Different URLs are fetched concurrently; overlapping callers of
one URL share a single request even for `Cache-Control: no-store` (without retaining it).
Each provider's KDoc says what one day listing, one game detail and one live tick cost.

## Adding a league

1. Map and verify the API first ([CONTRIBUTING.md](../CONTRIBUTING.md#mapping-a-league)).
2. `org.openscore.providers.<league>/`: DTOs (`@Serializable`, only the fields you read),
   a pure `<League>Mapper` object (DTO → model), and a `<League>Provider : BaseLeagueProvider`.
3. Declare `capabilities` honestly — see the league README's "Core model mapping" table.
4. Sample routes in `jvmMain/org.openscore.testing/<League>Samples.kt` (URL path → sample
   file) and register them in `SampleFetcher.allLeagues()` so `--offline` mode serves them.
5. Tests in `jvmTest`: a DTO test that parses every file in `apis/<sport>/<league>/samples`,
   and provider tests asserting the mapped model (states, period scores, event details).
6. Add the provider to `OpenScore.default()`. If the sport is new, add its event types under
   `model.<sport>`, its `details` mapping in `FeedMapper`, and document them in docs/feed-v1.md.
7. Optional live smoke test guarded by `-Dopenscore.live=true`, a handful of requests at most.
8. Add the league's teams to `clubs/ClubTable.kt`, merging clubs that already play in another
   of our leagues onto their existing line. `ClubsCoverageLiveTest` prints the missing ones as
   ready-to-paste lines.

## Running

```
./gradlew check                                   # compiles JVM + Android, runs all tests
./gradlew :core:jvmTest -Dopenscore.live=true --tests '*LiveSmokeTest*'   # real requests
./gradlew :tools:api-health:run --args="--league nhl"                     # every documented endpoint + this provider, live
```

### Team-page data

`TEAM_SCHEDULE` (`teamSchedule(teamId, startDate, endDate)`, an inclusive range of the
league's calendar dates) is served by MLB and by every football league except the Fogis
umbrella; `TEAM_STATS` (`teamStats(teamId, seasonId)`, regular-season `TeamSeasonStats`)
by MLB alone. Each football provider reads the cheapest route its API has for one club's
season and filters to the range:

| League | Route | Budget |
|---|---|---|
| Premier League | `/v2/matches?…&team={id}&_limit=100` (`teams=` is the parameter the API ignores) | 1 call, ~20 KB |
| Championship / Carabao Cup | `/matches?seasonID=&teamID=&page.size=100` — every competition the club plays, each provider keeping its own `competitionID` | 1 call shared by both, ~66 KB |
| Ligue 1 | `championship-club-summary/{id}` — the club pack the roster comes from carries the season's fixtures | 1 call |
| LaLiga | `matches?subscriptionSlug=&teamSlug=` | 1 call |
| Serie A | the whole-season `seasons/{id}/matches` (380 rows, 126 KB gzipped, ignores `teamId`) kept 10 min, filtered | 1 call |
| Bundesliga | the whole-season `matches.json` the day view already keeps warm; its rows carry scores | 0 calls when warm |
| UEFA | `matches?…&teamId=` — the drawn fixtures | 1 call |
| MLS | `matches/seasons/{s}?match_date[gte]=&match_date[lte]=&team_id=` — every competition the club plays (Leagues Cup, CONCACAF, play-offs; "MLS Test" rows dropped) | 1 call, `no-store` |
| Malta | `pastMatches?teamId=` + the league-wide `upcomingMatches` (only the next round exists) + one `result` per played match, kept a day | 2 + played |
| Allsvenskan / Superettan / Svenska Cupen | Fogis `schedule-{tournament}.xml` (a league's 240 games), crests from the Sportomedia team list | 1 per tournament |

Team ids for the Swedish leagues are Fogis ids throughout — table rows and profiles are
re-keyed through Sportomedia's own `fogisId` bridge — and `Clubs` links the same club
across leagues (`TeamRef.clubId`), which is how an app builds one page for a club that
plays domestically and in Europe. `Game.startTimeTbd` marks fixtures whose day is fixed
but not the kick-off (Serie A publishes those weeks ahead). These capabilities and
`Game.scheduleDate` / `startTimeTbd` are additive in Feed v1; see
[the contract](../docs/feed-v1.md). Captured team-page responses are replayed in
`MlbTeamPageTest`; `TeamPagesLiveTest` (live, off by default) walks every football league's
table → profile → squad → schedule.
