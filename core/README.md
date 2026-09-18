# core

Kotlin Multiplatform library that turns the APIs documented in [`apis/`](../apis) into one
sport-agnostic model. Targets: **JVM** and **Android** today; all code lives in
`commonMain`, so desktop/web targets are a build-file change, not a rewrite.

```
org.openscore.model          Sport-agnostic spine: League, Season, Game, GameEvent, Team,
                             Player, Lineup, StandingsTable  (docs/data-model.md)
org.openscore.model.hockey   Hockey leaves: HockeyEventType, GoalDetails, PenaltyDetails, …
org.openscore.model.football Football leaves: FootballEventType, FootballGoalDetails, CardDetails, …
org.openscore.model.baseball Baseball leaves: BaseballEventType, PlateAppearanceDetails, BaseballSituation (Game.situation)
org.openscore.model.motorsport Racing: RacingSeason, RacingRound, RacingSession, RacingClassification, RacingStandings
org.openscore.clubs          Club crosswalk: TeamRef.clubId, the same club across leagues (docs/data-model.md)
org.openscore.provider       LeagueProvider interface, Capability, BaseLeagueProvider (polling live())
                             and RacingProvider for non-two-team racing surfaces
org.openscore.net            Fetcher (read-only GET), KtorFetcher (UA, cache floor, ETag), OpenScoreJson
org.openscore.cache          Durable, normalized stores an app plugs in: DayListingStore + CachedDayListingProvider
                             (a settled or upcoming day is served from it, a due day is always read live, the last
                             read stands in offline), SeasonScheduleStore (HockeyAllsvenskan's season)
org.openscore.feed           Feed v1: the JSON contract (docs/feed-v1.md) + FeedMapper (model → feed)
org.openscore.OpenScore      Aggregator: all providers behind one door, cross-league gamesOn()
org.openscore.providers.*    One package per platform — each DTOs + Mapper + Provider:
                             hockey: nhl, liiga, sportality (SHL, and HockeyAllsvenskan's own provider), chl, khl
                             football: ligue1, bundesliga, premierleague, seriea, laliga, mls, malta,
                                       fogis (all Swedish tiers, XML; SwedishLeagueProvider cuts Allsvenskan,
                                       Superettan and Svenska Cupen out of it, tables from sportomedia),
                                       uefa (Champions / Europa / Conference League)
                             espn: EspnRosters — squads only, for the Bundesliga and UEFA-only clubs
                             football/FootballPeriods: shared 1H/2H/ET/PENS conventions and minute labels
                             baseball: mlb
                             motorsport: jolpica — JolpicaProvider, the one RacingProvider (Formula 1)
org.openscore.testing (jvm)  SampleFetcher + per-league sample routes, for tests and offline mode
```

## Leagues

| League id | Provider | Notable gaps (see `capabilities`) |
|---|---|---|
| `nhl` | `NhlProvider` | positional lineups only |
| `liiga` | `LiigaProvider` | no running flag on the clock; intermission is a heuristic |
| `shl` | `ShlProvider` (`SportalityProvider`) | SSE push not wired yet; event/lineup player ids are Statnet ids, roster ids are athlete UUIDs |
| `hockeyallsvenskan` | `HockeyAllsvenskanProvider` | the site has no day route: the whole season is read from the match page and kept as a normalized snapshot (`SeasonScheduleStore`), refreshed after six hours, with due/live/just-finished games re-read one by one; schedule, game and period scores only — no standings, events, lineups or live detail yet |
| `chl` | `ChlProvider` | no clock, no shots/coordinates, penalty details only as text |
| `khl` | `KhlProvider` | no clock (period only), no roster/player endpoints, MQTT push not wired |
| `ligue1` | `Ligue1Provider` | second-precision clock; 150–500 KB match resource → live() polls at 20 s |
| `bundesliga` | `BundesligaProvider` | Firebase RTDB; whole-minute clock; squads from ESPN through the crosswalk (the DFL's own are key-gated), no player endpoint; SSE not wired |
| `premier-league` | `PremierLeagueProvider` | five small calls per game; own goals credited to the beneficiary from the `events` grouping (the timeline attributes them to the scorer's team) |
| `serie-a` | `SerieAProvider` | 40 s edge cache; roster = the season's registrations (`?seasonId=`); no player endpoint |
| `la-liga` | `LaLigaProvider` | needs the public page-embedded keys (`LaLigaKeys.discover`); second-precision period timestamps |
| `mls` | `MlsProvider` | first-party `stats-api` (`no-store`, honoured) + `sportapi` pre-game metadata; no live capability until a live sample is captured; club schedule spans every competition the club plays |
| `malta-premier` | `MaltaProvider` | minute-only events, no positions/formations/stats or reliable team roster; one `result` call per started match |
| `allsvenskan`, `superettan`, `svenska-cupen` | `SwedishLeagueProvider` (games, live, events, lineups from `FogisProvider`; standings/team/roster/player from the Sportomedia `AllsvenskanProvider` / `SuperettanProvider`; the cup has no table) | Fogis answers every live tick where Sportomedia rate-limits and freezes its clock, so the games come from Fogis and only the tables from Sportomedia. Fogis team ids under these league ids (crosswalk namespace `fogis`) |
| `ucl`, `uel`, `uecl` | `ChampionsLeagueProvider`, `EuropaLeagueProvider`, `ConferenceLeagueProvider` (shared `UefaProvider`) | second-precision clock from the timeline's phase markers; squads from ESPN through the crosswalk (UEFA has no squad endpoint); no assists in the MAIN timeline; `live()` polls the 300-byte `livescore` hash and re-reads the match on change; live states not yet observed |
| `fogis` | `FogisProvider` | every SvFF competition in one feed (`Game.competition`), an umbrella apps treat as opt-in; XML; no standings/team/player; clock derived from wall-clock stamps, on the day view too |
| `mlb` | `MlbProvider` | no clock — period-only `Clock` (`Bot 7`) plus `Game.situation` (outs, count, runners); `live()` polls the 120 KB (gzipped) feed at 10 s; standings `points` = wins; probable pitchers / decisions not mapped |
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
