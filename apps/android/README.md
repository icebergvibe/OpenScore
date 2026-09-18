# apps/android

The OpenScore Android app: Jetpack Compose over [`core/`](../../core/README.md), talking to the
leagues' public APIs directly. No key, no account, no server of its own, no analytics.

```
./gradlew :apps:android:assembleDebug          # apps/android/build/outputs/apk/debug/android-debug.apk
./gradlew :apps:android:testDebugUnitTest      # timeline rows, formatting, favourites
```

`local.properties` (gitignored) must point at an Android SDK with platform 37:
`sdk.dir=/home/you/Android/Sdk`. The app compiles against 37 because Compose 1.12, core-ktx 1.19
and Coil 3.6 require it; `core/` stays on 36.

## What it shows

Four destinations on a bottom bar, three of them feeds over one timeline:

| Destination | What it is |
|---|---|
| **Scores** | A day-by-day timeline of every league the core covers for the selected sport (Hockey · Football · Baseball · Motorsport · MMA). Each day is a sticky heading, then one band per league with its games under it. |
| **Live** | The same timeline cut to one day and to games in play, polled while on screen. |
| **Following** | Games of followed teams and leagues across every sport, filtered on the device from the same day listings. |
| **Settings** | Dark mode, the followed list with a bell per favourite, notification choices, the offline scores store, and where the scores come from. |

A match card gives the team names the wide column, aligns scores to their rows and keeps the status at the edge. The status pill is the kick-off time before the
game, the clock while it runs (`67'`, `2 12:34`, `Bot 7`), `HT` / `Int 2` / `Mid 7` in breaks, and
`FT` / `Final/OT` / `Pens` afterwards; football and hockey get a sliver under it showing how far
through the game is. Tapping a card opens a full-screen match page — header, follow chips, period scores,
lineups side by side, stats with bars, and the key events (goals, cards, penalties, changes,
shoot-out kicks) pushed to the side they belong to. Match, table and team pages have an explicit
back action and preserve the detail stack. Tapping a league band opens its table.
Long-pressing a card follows either side or the league.

A fight has no score, so its card shows the division and whether a belt is on the line where
the clock would go and, once decided, `W` / `L` / `D` / `NC` beside each corner with the method
(`KO/TKO R1`, `Sub R2`, `UD`) in the status pill; the league band's subtitle is the event
(`UFC 331: Van vs. Pantoja 2`). The match page adds a Bout/Result section — division, rounds,
belt, card slot, then how it ended and the judges' cards — and the card's tracked timeline and
per-fight strike/takedown stats once a fight has started. Fights on a card share their
segment's start time and the main event walks out hours later, so the score poll keeps a fight
that has not begun as due for six hours rather than the usual three.

The filter sheet (the tune icon) narrows a sport to some of its leagues, searches the days
already loaded, and follows leagues. **Umbrella feeds are opt-in**: Fogis carries every Swedish
tier down to the districts and would swamp "all football", so it is left out of the whole-sport
selection and ticked on there. Allsvenskan, Superettan and Svenska Cupen are proper leagues of
their own (the core cuts them out of the same feed and adds allsvenskan.se's tables).

## Team pages

Tap a team's badge or name in a match header, or its row in a league table. The page is
about the **club**, not one league entry: the core's crosswalk (`TeamRef.clubId`) names the
club in every league it plays in, so Bayern opened from a Champions League fixture and Bayern
opened from the Bundesliga table are the same page. Its tabs — **Overview, Games, Standings,
Roster, Stats** — each appear only where at least one of the club's leagues has the
capability (`TEAM`, `TEAM_SCHEDULE`, `STANDINGS`, `ROSTER`, `TEAM_STATS`), and each loads,
fails and retries on its own.

- The tables of the club's leagues are read first; a table that names the club makes that
  league a member for this season, and cups without a table stay members for games. The
  first domestic member is the **home** league: its profile, squad and season label head
  the page (calendar year for MLB, MLS, Allsvenskan and Superettan; July–June elsewhere).
- Games merge every member league's `teamSchedule` within its season window, so a club's
  league, cup and European fixtures share one list; rows from another competition carry its
  name. Upcoming, Results and All filters; W/L markers and scores from the club's side.
- Standings show each member's table, the club's own group first; tapping another team
  opens its page.
- MLB adds what its API has and the football feeds do not: regular-season record, streak and
  splits, last-five form, division tables, ballpark details, headshots, and batting and
  pitching totals under Stats (`TEAM_STATS` is MLB-only for now).

While resumed, the page re-reads the day listings of the club's leagues every 60 s only when a
game is live or due (`wantsScorePoll`), and every season section every five minutes; the
header's refresh reloads all of them. Start times use the phone's timezone; schedule dates
are the league's own (MLB's official date). Back returns to the previous match, table or
team.

## Formula 1

Motorsport on the sport rail opens a season view instead of a timeline: the calendar of
rounds with their sessions (practice, sprint qualifying, sprint, qualifying, race), each
round's classification once it is run, and the driver and constructor tables — from the
core's `RacingProvider`, which reads [Jolpica F1](https://github.com/jolpica/jolpica-f1), the
open-source successor to the Ergast API; the data is cached for a day. Following F1 (the star
in the filter sheet) lists each session — practice, qualifying, sprint, race — on its day in
Following, and Live on this rail shows the sessions whose scheduled window is open. There is no
live timing behind those rows: "Now" is read off the schedule and a typical session length, and
the rows open nothing — the classification is on the season view once a session is run.

## Notifications

Pre-game reminders, match start, goals (runs in baseball), cards (in hockey the majors and
misconducts, not every minor), half time and breaks, final results, and a game being called
off, for the favourites whose bell is on —
posted by the app itself, with no push service and no Google Play services. The `alerts` package:

- `AlertScheduler` rebuilds the queue once a day (3 a.m.) and whenever the bells or kinds
  change: one read of yesterday, today and tomorrow's listings for the followed leagues turns
  every followed game into `PendingAlert` rows (a fight queues only its start and its result —
  there are no goals, cards or breaks to announce, and its start is its card segment's, so the
  reminder is filed under the card and segment). Exactly one `AlarmManager` alarm is
  outstanding at a time (`setAndAllowWhileIdle`, so no exact-alarm permission); it fires,
  delivers what is due, and arms the next. `AlertReceiver` runs the work with `goAsync` under a
  20 s budget; `BootReceiver` rebuilds after a reboot or an update. Resuming the app is a free
  wake-up: the schedule is checked for staleness and overdue rows are delivered at once.
- A pre-game reminder costs no request when it fires. Every other kind re-reads the league's
  day listing when due — the core's `gamesOn` carries state and score for finished games too,
  so a start, a goal, a break and a result are all answered by **one request per league per
  poll**, whatever is followed. Cards are the exception (bookings live only in `events()`), and
  the scorer's name is looked up once per goal rather than per poll; both only where the
  provider has `EVENTS`.
- `AlertRules` holds the decisions free of Android (tested on the JVM): what a game turns into,
  when a running game is looked at again (read off the football minute or hockey period), and
  what a due row does. Live watches seed themselves silently on the first look, so switching
  goals on at half time does not announce the first half. A game found postponed or cancelled
  — by a rebuild that still holds rows for it, or by any watch when it falls due — is said
  once under a shared id, and its reminder is dropped rather than fired for nothing.
- `AlertsStore` (which favourites notify, which kinds, lead time) and `AlertQueue` (pending
  rows, recently delivered ids, last-refresh stamp) are SharedPreferences. A notification tap
  carries a `GameLink` (league, id, day); `MainActivity` pushes a `MatchKey` for it over a
  cleared back stack and the match screen finds the game by id.
- Five channels — reminders, start, results, goals/cards (the one at high importance), breaks
  — so each can be silenced from Android's own settings. Android 13's `POST_NOTIFICATIONS` is
  asked for when the first bell goes on.

## Release builds

`./gradlew :apps:android:assembleRelease` produces an R8-shrunk APK. It is signed when a
properties file with `storeFile`, `storePassword`, `keyAlias` and `keyPassword` is found at
`~/.config/openscore/keystore.properties` (or wherever `OPENSCORE_KEYSTORE_PROPERTIES`
points); without one the build still succeeds but the APK is unsigned and a phone will refuse
it with "App not installed". The keystore itself never goes in the repository (`*.jks` is
ignored) — back it up: every later build has to be signed with the same key to install over
the one on a phone.

## How it reads the core

```
MainActivity ─ MainScreen (NavDisplay) ─┬─ HomeKey  ─ HomeScreen ─┬─ FeedScreen ── FeedViewModel ── ScoresRepository ── OpenScore (core)
                                        │                         ├─ FilterSheet                                      │
                                        │                         └─ SettingsScreen                                   │
                                        ├─ MatchKey ─ MatchScreen ─── MatchViewModel ─────────────────────────────────┤
                                        ├─ TableKey ─ StandingsScreen ─ StandingsViewModel ───────────────────────────┤
                                        └─ TeamKey  ─ TeamScreen ──── TeamViewModel ──────────────────────────────────┘
```

- Navigation is one Navigation 3 back stack (`ui/nav`): the tab scaffold at the root, then the
  match, table and team screens in the order they were opened, all in the activity's window —
  pushes slide in, pops slide out, predictive back previews the screen underneath. Keys carry
  ids (`@Serializable`), so the stack is saved across process death; the `Game` a card already
  had is handed to the match screen through the activity-scoped `GameSeeds`, and a screen
  restored without one fetches by id. Each screen's ViewModel is scoped to its back-stack entry
  (`rememberViewModelStoreNavEntryDecorator`): opening a team page from a match and coming back
  finds the match as it was, and popping a screen clears its ViewModel.

- `ScoresRepository` is a thin door to `OpenScore.default(KtorFetcher)`: `gamesOn(date, leagues)`
  through the aggregator as a flow (concurrent per league, one emission as each league answers,
  a failing league is reported, not fatal), `game` / `events` / `lineups` / `standings` / `live`
  through the league's provider. Every call runs on `Dispatchers.Default`, so parsing never
  happens on the main thread. Politeness — the 10 s floor, the per-URL cache, ETags — lives in
  the core's fetcher, which is why there is exactly one of it (`OpenScoreApp`).
- Room stores three normalized things, and nothing else (`RoomScoresCache`, one class behind the
  core's `SeasonScheduleStore` and `DayListingStore`):
  - **every league's day listings as last read.** The core's `CachedDayListingProvider` wraps
    each provider and answers a day from the store while nothing in it can have changed: a day
    whose games are all over for a week, fixtures not yet due and empty days for an hour. A day
    with a game due within five minutes or running is always read live and never served stale;
    when the network fails, any stored day with nothing due is served whatever its age — that is
    what opens offline. Rows carry the installed APK's update time, so a new build (a mapper fix)
    disowns everything an older one wrote; days not opened in a month are pruned.
  - **HockeyAllsvenskan's complete season**, the one league without a day route. Its provider
    re-reads the 127 KB gzipped season page after six hours, re-reads games that are due, live or
    recently over through the ~700 B game route, and writes a final back with its period scores
    and decision, so a restored result is as complete as a fetched one.
  - **UFC's known cards**, the other league without a listing route (`seasonId` `cards`). The
    core's `UfcProvider` restores them on start instead of re-sweeping the id space, and re-reads
    a restored card once because the rows keep a fight's state but not its result.

  Match details (events, lineups, stats) and response bodies are never persisted. The database
  is a cache: a schema change drops it and the data is read again on first use.
- Team logos from every league use Coil's separate 32 MiB on-disk LRU cache. Nothing is prefetched:
  an image is saved only after a screen first requests it, reused across process restarts, and the
  least-recently-used images are evicted if the cache reaches its limit. Android may also clear this
  cache when the device is short on storage.
- `FeedViewModel` holds fetched days per feed, keyed on the leagues asked for, so Scores and
  Live share them. A day is drawn as leagues answer (`DayState.Loaded.pending` names the rest;
  a league keeps its previous games until its new answer is in). Opening a feed loads only
  today, so a quiet off-season cannot multiply into a week of speculative calls across every
  league. The explicit timeline affordance fetches unfetched days two at a time; after a drag
  opts in, it can continue through a small bounded run. The poll
  (`refreshLive`, every 60 s while resumed) re-asks only the leagues with a game in play or
  on the doorstep of kick-off (`wantsScorePoll`: scheduled within five minutes, or kicked off
  within the last three hours without going live — the same rule the team page uses) and
  splices their games into the held day.
- `buildTimelineRows` flattens the ±90-day window into what the list draws: unfetched days
  collapse into one scanning row each side, a fetched empty day is dropped (only Live says
  "nothing live"), a day some of whose leagues failed gets a quiet retry line under it.
- Dates are the leagues' own (`LeagueProvider.gamesOn` convention: the NHL buckets by US Eastern
  date, European leagues by local date); kick-off times are shown in the phone's zone.
- Favourites (`FavoritesStore`, SharedPreferences) key teams on the core's club id where the
  crosswalk knows the club — following Bayern in the Bundesliga follows them in the Champions
  League — and on `league/teamId` otherwise. `FavoriteFilter` turns the set into the leagues to
  ask and the games to keep.
- Every core `Capability` the UI depends on is checked (`STANDINGS` before making a band
  tappable, `EVENTS` / `LINEUPS` / `LIVE_UPDATES` in the match sheet) rather than caught.

## Not yet

Season selection, player pages and team leaders, team stats outside MLB,
push transports (the core still polls where a league offers SSE),
desktop/web targets.

## Verifying on a device

Every screen here was checked on a real Android session (Waydroid) rather than only in unit
tests; the loop is plain `adb`:

```
./gradlew :apps:android:assembleDebug -q
adb install -r apps/android/build/outputs/apk/debug/android-debug.apk
adb shell am start -n org.openscore.app/.MainActivity
adb exec-out screencap -p > screen.png
adb logcat -d | grep -E "AndroidRuntime|FATAL"          # after a crash
```

Live states can only be checked while a league actually has a game running.
