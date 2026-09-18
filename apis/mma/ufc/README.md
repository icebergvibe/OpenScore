# UFC live-stats API

| | |
|---|---|
| **Sport** | Mixed martial arts |
| **Country / region** | USA (events worldwide) |
| **Official site** | https://www.ufc.com |
| **Base URL** | `https://d29dxerjsp82wz.cloudfront.net/api/v3/` |
| **Auth** | None — the JSON ufc.com's event pages poll (`drupalSettings.ufcLiveStatsApi.baseUrl`) |
| **Format** | JSON (UTF-8), uncompressed. Unknown ids answer `200` with an empty object; bad paths `404` with a text body |
| **CORS** | `Access-Control-Allow-Origin: *`, `Access-Control-Allow-Methods: GET`. Preflight (`OPTIONS`) is `403`, so only simple GETs work from browsers — which is all that is needed |
| **WAF / UA requirement** | None. API Gateway behind CloudFront; any or no `User-Agent` succeeds |
| **Last full verification** | 2026-09-18 |
| **Status** | ✅ verified (upcoming + final states, DWCS / Road to UFC, draw, no contest, canceled) · ✅ `UfcProvider` in core · 🚧 live-state samples pending (UFC 331, 2026-09-19 21:30Z) |

## Overview

The stats behind ufc.com's event pages (results, judges' scorecards, round-by-round
strike and takedown counts, the live "fight tracker") come from a two-route JSON API on
`d29dxerjsp82wz.cloudfront.net`. Its data is the FightMetric / UFC Stats database: the
id space is dense from `1` (2008-era cards; `100` is UFC 19 from 1999, `500` a K-1 event)
to the current `1344`, and every UFC, Dana White's Contender Series and Road to UFC card
is there.

There is **no listing route**. ufc.com renders its `/events` page server-side (a Drupal
view) and each event page carries its own numeric id (`event_fmid`) in page settings; the
JSON API only answers `event/live/{fmid}.json` and `fight/live/{fmid}.json`. Because the
id space has no gaps, OpenScore discovers events by sweeping forward from a stored
frontier (see [Discovery path](#discovery-path)) rather than by scraping HTML.

One document per event holds the whole card — every fight, both fighters with records
and bio, the result, judges' scores and the tracked timeline — so a card is one request
however many fights are on it. The per-fight document adds the per-round statistics and
the full per-fighter strike breakdown.

All samples in [`samples/`](samples/) were captured on **2026-09-18**, the day before
UFC 331; the next card is the first chance at live states.

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Event (`fmid`) | int, dense, assigned when the event is created in the stats system — **not chronological** (1317 = UFC 330 on 08-15, 1320 = a Fight Night on 07-18; 1335 is 09-19 but 1332 is 10-17) | `1335` | Sweep (below); also `drupalSettings.eventLiveStats.event_fmid` on any ufc.com event page |
| Fight | int, global | `13017` | `FightCard[].FightId` |
| Fighter | int, global (`FighterId`); `MMAId` is a second, larger id of unknown origin | `3989` / `163597` | `Fighters[].FighterId`. The fighter's ufc.com page is `UFCLink` (`/athlete/First-Last`) |
| Organization | int | `1` UFC, `67` Dana White's Contender Series, `68` Road to UFC | `Organization.OrganizationId` |
| Weight class | int + abbreviation | `8` / `FLW` | `WeightClass`; catch weights are `13` or `37` with `CatchWeight` set (lbs) |
| Venue | int | `160` | `Location.VenueId` |
| Judge / referee | int | `14` / `18` | `FightScores[].JudgeId`, `Referee.RefereeId` |

Fighters are people, not teams: there is no club, no roster and no standings in this
feed. `Corner` (`Red` / `Blue`) is the only "side" — red is the first fighter listed and
by UFC convention the higher-ranked or champion.

## Discovery path

1. **Known events:** keep every event document seen so far. Bootstrap from a seed id
   (`1296`, UFC 326 on 2026-03-07, is the first card sampled here; anything from the last
   year works) and sweep `GET event/live/{id}.json` upward until **8 consecutive empty
   answers** (`{"LiveEventDetail":{}}`, 22 bytes each). Ids are dense: 1296–1344 had no
   gap, and 1345–1360 were all empty on 2026-09-18. Repeat the frontier sweep once a day —
   events get an id well before their card is announced (1343 and 1344 already exist with
   one fight each for October/November).
2. **Today's fights:** from the stored documents, pick fights whose `CardSegmentStartTime`
   falls on the date (a card spans midnight UTC: UFC 331 prelims start 21:30Z on 09-19, the
   main card 01:00Z on 09-20). `StartTime` on the event is the earliest segment.
3. **A specific card:** `GET event/live/{fmid}.json` — the whole card, results, scores and
   tracked timeline. This is the poll target: ufc.com re-reads it every **10 s** while the
   page is not flagged final, and stops afterwards.
4. **One fight's stats:** `GET fight/live/{fightId}.json` — adds `FightStats` (totals per
   fighter) and `RoundStats` (per round). ufc.com polls it every **5 s** only while a
   fight's matchup panel is open. Not needed for a scoreboard.

Do not poll an event whose `Status` is `Final` or `Canceled`; re-read finished cards once
a day for a while, because results can be **overturned** later (`Method: "Overturned"`,
both fighters `No Contest`, `EndingNotes: "Failed Drug Test by …"` — event 1297, fight 12559).

## Endpoints

### `GET /event/live/{fmid}.json`

| | |
|---|---|
| **Purpose** | One event: header, venue, live pointer and the full fight card with results. The only listing-like document and the live poll target. |
| **Parameters** | `fmid` — event id |
| **Samples** | [`samples/event.live.upcoming.json`](samples/event.live.upcoming.json) (1335, UFC 331, three card segments, a title fight) · [`samples/event.live.final.json`](samples/event.live.final.json) (1320, KO/submission/decision results, scorecards, tracking) · [`samples/event.live.final.draw.json`](samples/event.live.final.draw.json) (1302, fight 12742 is a majority draw) · [`samples/event.live.final.no-contest.json`](samples/event.live.final.no-contest.json) (1312, fight 12730 `Could Not Continue`) · [`samples/event.live.final.dwcs.json`](samples/event.live.final.dwcs.json) (1334, Contender Series, one segment, UFC Fight Pass) · [`samples/event.live.canceled.json`](samples/event.live.canceled.json) (1303) · [`samples/event.live.upcoming.no-card.json`](samples/event.live.upcoming.no-card.json) (1342, announced, no fights yet) · [`samples/event.live.unknown.json`](samples/event.live.unknown.json) (999999) |
| **Last verified** | 2026-09-18 |
| **Cache** | No `Cache-Control`, `ETag` or `Last-Modified`. CloudFront serves a `Hit` for a few seconds (`age: 3` observed, `Miss` again at 5 s); the query string is ignored in the cache key. 20–85 KB per card, uncompressed |

**Response shape**

```
LiveEventDetail
  EventId, Name ("UFC 331: Van vs. Pantoja 2"), StartTime ("2026-09-19T21:30Z", UTC, minute precision),
  TimeZone ("GMT-07:00", the venue's offset), Status,
  LiveEventId?, LiveFightId?, LiveRoundNumber?, LiveRoundElapsedTime?   (all null outside a live card)
  Organization { OrganizationId, Name }
  Location { City, State, Country, TriCode, VenueId, Venue }
  FightCard[]                                    FightOrder 1 = main event; the last order fights first
    FightId, FightOrder, Status, CardSegment, CardSegmentStartTime (UTC), CardSegmentBroadcaster
    Fighters[2]
      FighterId, MMAId, Name { FirstName, LastName, NickName? }, Born {...}, FightingOutOf {...},
      Record { Wins, Losses, Draws, NoContests }   (career record *at read time*, see quirks),
      DOB, Age, Stance?, Weight (lb), Height (in), Reach (in), UFCLink, WeightClasses[],
      Corner ("Red" | "Blue"), WeighIn? (lb, after the weigh-in),
      Outcome { OutcomeId?, Outcome? }, KOOfTheNight, SubmissionOfTheNight, PerformanceOfTheNight
    Result
      Method?, EndingRound?, EndingTime? ("2:15", time *into* the round, as results are announced; "5:00" for decisions),
      EndingStrike?, EndingTarget?, EndingPosition?, EndingSubmission?, EndingNotes?, FightOfTheNight,
      FightScores[]  { JudgeId, JudgeFirstName, JudgeLastName, Fighters[2] { FighterId, Score } }   (decisions only)
    WeightClass { WeightClassId, CatchWeight?, Weight ("116-125"), Description, Abbreviation }
    Accolades[] { Type ("Belt" | "Tournament"), Name }
    Referee { RefereeId?, FirstName?, LastName? }     (null until the fight is assigned)
    RuleSet { PossibleRounds, Description }
    FightNightTracking[]  { ActionId, FighterId?, Type, RoundNumber?, RoundTime? (remaining), Timestamp (UTC, seconds) }
```

`FightNightTracking` is the tracked timeline, in order, and is the events source: it is
empty before the fight and stays in the document afterwards (12–51 actions per fight).
`RoundTime` counts **down** from `5:00` (`round_end` at `0:00`/`0:01`) — the opposite
convention to `Result.EndingTime`; `Timestamp` is wall-clock.

**Observed `Status`** (event and fight, may be incomplete): `Upcoming`, `Final`,
`Canceled` (event only, empty `FightCard`). ufc.com's script also handles fight
`Live` and `Over` (fight ended, result not yet official) — not yet observed.

**Observed `CardSegment`:** `Main`, `Prelims1`, `Prelims2`, `null` (fights announced
without a slot). **`CardSegmentBroadcaster`:** `Paramount+`, `UFC Fight Pass`, `null`.

**Observed `Result.Method`:** `KO/TKO`, `TKO - Doctor's Stoppage`, `Submission`,
`Decision - Unanimous`, `Decision - Split`, `Decision - Majority`, `Could Not Continue`
(no contest, e.g. after an accidental foul), `Overturned`. `EndingStrike`: `Punch`,
`Punches`, `Elbow(s)`, `Knee(s)`, `Kick`, `Spinning Back Kick`, `Slam`. `EndingTarget`:
`Head`, `Body`, `Leg`. `EndingPosition`: `At Distance`, `In Clinch`, `On Ground`,
`Standing`, `From Mount`, `From Guard`, `From Half Guard`, `From Side Control`,
`From Back Control`, `From Bottom Guard`, `Standing Back Control`, `After Drop to Guard`.
`EndingSubmission`: `Rear Naked Choke`, `Guillotine Choke`, `Arm Triangle`, `Armbar`,
`Triangle Choke`, `D'Arce Choke`, `Anaconda Choke`, `Von Flue Choke`, `Neck Crank`,
`Heel Hook`, `Calf Slicer`, `Twister`, `Suloev Stretch`. `EndingNotes` is free text
("Corner Stoppage", "Two Points Deducted: Repeated Low Blows by Xiao", "Ninja choke").

**Observed `Outcome`:** `1 Win`, `2 Loss`, `3 Draw`, `4 No Contest`; both null before
the result. **`RuleSet.PossibleRounds`:** 3 (`3 Rnd (5-5-5)`), 5 (`5 Rnd (5-5-5-5-5)`,
title and main events), 4 (`3 Rnd + OT (5-5-5-5)`, Road to UFC tournament bouts), and one
`3 Rnd (10-5-5)` (a Contender Series novelty rule).

**Observed `FightNightTracking.Type`** (49 cards, 2 120 takedown attempts): pre-fight
`fight_open`, `walkout` (per fighter), `tale_of_the_tape`, `staredown`; per round
`round_start` (`RoundTime: 5:00`), `round_end`, `round_pause` + `pause_reason_low_blow` /
`pause_reason_eye_poke` / `pause_reason_doctor` / `pause_reason_generic` (the reason carries
a `FighterId`; which side it names is not documented) + `round_unpause`; scoring actions `knockdown`,
`takedown`, `takedown_attempt`, `submission_attempt`, `reversal` (all with `FighterId`);
end `fight_over`, `unofficial_winner_kotko` / `unofficial_winner_submission` /
`unofficial_winner_decision` (`FighterId` = winner), `results`, `fight_complete`.
Strikes are **not** tracked as actions; they are only in the fight document's totals.

### `GET /fight/live/{fightId}.json`

| | |
|---|---|
| **Purpose** | One fight with the same header as the event card plus per-fighter statistics: totals and per round. The tracked timeline is *not* included. |
| **Parameters** | `fightId` — `FightCard[].FightId` |
| **Samples** | [`samples/fight.live.upcoming.json`](samples/fight.live.upcoming.json) (13017, empty stats) · [`samples/fight.live.final.json`](samples/fight.live.final.json) (12919, five rounds, `OfficialStats: true`, round scorecards) · [`samples/fight.live.unknown.json`](samples/fight.live.unknown.json) |
| **Last verified** | 2026-09-18 |
| **Cache** | As above. 2.6 KB before the fight, ~27 KB after |

**Response shape**

```
LiveFightDetail
  Event { EventId, Name, StartTime, TimeZone, Status, Live*, Location }   (no Organization, no FightCard)
  FightId … RuleSet                  as in the event card, plus OfficialStats (bool)
  Result { …, FightScores[], RoundScores[] { JudgeId, Judge*Name, Rounds[] { RoundNumber, Fighters[2] { FighterId, Score } } } }
  FightStats[2]   { FighterId, Knockdowns, Total*/Sig* strikes attempted/landed by target (Head/Body/Leg) and
                    position (Distance/Clinch/Ground), SigStrikesAccuracy, Takedowns*, TakedownsAccuracy, Reversals,
                    SubmissionsAttempted, NeutralTime, StandingTime, DistanceTime, ClinchTime, GroundTime,
                    ControlTime and the control breakdown (Guard/HalfGuard/SideControl/Mount/BackControl) as "m:ss" }
  RoundStats[2]   { FighterId, Rounds[] { RoundNumber, …the same counters per round… } }
```

`RoundScores` gives the 10-point-must scorecard per round (decisions only) — the
per-round detail behind `FightScores`. `OfficialStats` presumably flips once the
commission's stats are final; both finished samples have it `true`.

## Game states

| `Status` | Where | Meaning | Core `GameState` |
|---|---|---|---|
| `Upcoming` | event, fight | Scheduled; `Result` and `Outcome` null, `FightNightTracking` empty | `SCHEDULED` (fight) — `PRE_GAME` once `walkout` actions appear |
| `Live` | fight (from ufc.com's script) | In progress; event `LiveFightId` / `LiveRoundNumber` / `LiveRoundElapsedTime` point at it | `LIVE` — between rounds when the last action is `round_end` |
| `Over` | fight (script) | Fight ended, result not yet entered (`unofficial_winner_*` may already be tracked) | `LIVE` with `Result` pending — treat as `FINAL` for display once `fight_over` is tracked |
| `Final` | event, fight | Result official | `FINAL` |
| `Canceled` | event | Whole card canceled, `FightCard` empty | `CANCELLED` |

A card is built up over weeks: fights are added, re-slotted and presumably removed from
`FightCard` (compare `FightId`s, never `FightOrder`), segment start times move, and a
new event starts with an empty `FightCard` (1342). Whether a scratched fight disappears
or stays with a status of its own is not yet observed.

The event `Status` while a card is running, the `Live` / `Over` fight statuses, the
format of `LiveRoundElapsedTime` and whether `LiveEventId` is anything but null are
**unverified** until UFC 331 (2026-09-19) is captured.

## Quirks & gotchas

- **Times:** `StartTime` and `CardSegmentStartTime` are UTC with minute precision and a
  bare `Z` (`2026-09-19T21:30Z` — no seconds; parse accordingly). `TimeZone` is the
  venue's fixed offset as text, not an IANA zone. Tracking `Timestamp`s are UTC seconds.
- **Ids are not chronological** and **events exist before their cards** — never infer
  "next event" from the highest id; keep every document and sort by `StartTime`.
- **Unknown id = `200` with `{"LiveEventDetail":{}}`**, never `404`. A `404` (`Page not
  found`, `text/html`) means the path is wrong. The host root is `403` `Missing
  Authentication Token` (API Gateway) — meaningless.
- **No validators, no compression, ~5 s edge TTL.** A 12-fight card costs 75–85 KB per poll;
  at ufc.com's 10 s that is ~500 KB/min while live, so an app should poll the card only
  while a fight is in progress or due and read `fight/live` only on demand.
- **`FightOrder` is descending in time**: order 1 is the main event and fights last.
- **Two clock conventions:** `Result.EndingTime` is elapsed (`5:00` = a full round, `2:15` =
  2:15 into the round), tracking `RoundTime` is remaining (the broadcast clock), and
  `LiveRoundElapsedTime` is named as elapsed — verify the last one before mapping a clock.
- **`Record` is the fighter's current career record**, not a snapshot: it reads the same
  on every card the fighter appears on, so on a finished card it already includes that
  fight (14 fighters checked across two cards each). The pre-fight record is `Record`
  minus `Outcome`.
- **Results can change after `Final`** (overturned on appeal or a failed drug test).
- **Off-season shape:** none — UFC runs year-round; empty weeks simply have no event whose
  `StartTime` falls in them. DWCS cards are Tuesdays in August–September, Road to UFC
  cards come in pairs on consecutive days.
- **Query strings are ignored** by the edge cache, so cache-busting does not bypass the
  short TTL (and is unnecessary).

## Out of scope

- **ufc.com HTML:** `/events` (a Drupal `events_upcoming_past` view with `upcoming` /
  `past` displays, `/views/ajax` for further pages, HTML inside JSON) and event pages
  (200 KB, `max-age=604800`) — the only place an `fmid` is printed. Not used because the
  frontier sweep gives the same ids from JSON.
- **`/matchup/{fmid}/{fightId}/{pre|live|post}`** — the HTML fight panel ufc.com loads in an
  iframe. Everything it shows is in the two JSON routes.
- **ufc.com JSON:API** (`/jsonapi`, Drupal 10) exposes only `node--article`, `node--video`,
  `node--gallery`, `node--landing`, `promo--hero`, media and files — no event, fight or
  athlete resource. `?_format=json` on nodes is `406`.
- **Rankings** (the UFC's divisional rankings) and **athlete profiles** are not in this
  API; ufc.com renders them from Drupal. The fighter bio embedded in each fight is all
  there is.
- **Fight Pass / imggaming** (`dce-frontoffice.imggaming.com`) — video entitlements, needs
  an account.
- Guessed listing routes that do not exist (all `404`): `events.json`, `event/live.json`,
  `event/upcoming.json`, `event/list.json`, `schedule.json`, `event/final/{id}.json`,
  `event/{id}.json`, `fighter/live/{id}.json`, `athlete/{id}.json`, `api/v1|v2/…`.

## Core model mapping

`UfcProvider` (`core/.../providers/ufc`) serves one league, `ufc`, for all three organisations;
the card name is `Game.competition` (`UFC 331: Van vs. Pantoja 2`, `DWCS 10.5`). A fight is a
`Game` with the red corner as `home` and the blue corner as `away`, rounds as periods and no
score; the bout and its result live in `model.combat.FightSituation` (`Sport.MMA`, the first
non-team sport on `LeagueProvider` — F1 stayed on `RacingProvider` because a race is not
two-sided). Game ids are `{fmid}-{fightId}` so one card read answers `game()`.

Listing: the provider keeps every card it has seen as a snapshot (in memory, and in the app's
season store across restarts) and sweeps the id frontier — batches of 8 above the highest known
id every 6 h, plus a jump-and-bisect from a seed id when nothing is stored. A day is answered
from the snapshot in the venue's calendar date; a card that is due or under way is re-read at
the 10 s floor, an open card hourly, a finished one never (except once per process for a card
restored from the store, whose result the store does not keep).

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League | — | `Organization` | One league `ufc`; the organisation is visible in the card name |
| Season / stage | — | — | None in the feed; every game carries `seasonId = "cards"` so the season store can file the snapshot |
| Game (id, sides, start time) | `event/live/{fmid}` | `FightCard[].FightId`, `Fighters[Red]`, `Fighters[Blue]`, `CardSegmentStartTime` (else `StartTime`), `Location.Venue`, `City` | `TeamRef` = the fighter (id `FighterId`, name, `abbreviation` = last name, no logo — headshots are not in the feed); `scheduleDate` = the card's venue-local date |
| GameState | event document | fight `Status`, `FightNightTracking`, event `LiveFightId` | `Final` → FINAL; `Live`/`Over`, or any status with a tracked `round_start` → LIVE; a tracked walkout or the live pointer → PRE_GAME; `Upcoming` else SCHEDULED; an unforeseen status with no rounds tracked → UNKNOWN; event `Canceled` → CANCELLED |
| Clock / period | event document | `LiveRoundNumber`, `LiveRoundElapsedTime`, last `round_*` action; `RuleSet.Description` for round lengths | `Period` = round (`R1`, the `+ OT` round as OVERTIME), `running` from the last round action, elapsed only from the live pointer. **Unverified until live** — hence no `CLOCK` capability yet |
| Score | — | — | Always null |
| Situation | event document | `RuleSet`, `WeightClass`, `Accolades`, `CardSegment`, `FightOrder`, `Result`, `Outcome`, `FightScores` | `FightSituation`: rounds, round minutes, weight class (`Catch Weight (130 lb)`), title, card slot; `result` = winner, method (`KO_TKO` / `SUBMISSION` / `DECISION` / `NO_CONTEST` / `OVERTURNED`), round, time, detail (`Rear Naked Choke`, `Punch to the head` — `EndingPosition` is not shown), notes, outcomes, scorecards, the `*OfTheNight` bonuses per corner. No `credits`: the result says who won |
| GameEvent | event document | `FightNightTracking[]` | `CombatEventType`: knockdown, takedown, takedown attempt, submission attempt, reversal, round start/end, pause (the `pause_reason_*` action is folded into it), resume, walkout, fight start/end, result (`unofficial_winner_*`). `tale_of_the_tape`, `staredown`, `results`, `fight_complete` are dropped. Time = round, `RoundTime` as `remaining`, and the label stated as results are (elapsed) |
| Stats | `fight/live/{fightId}` | `FightStats` | `Game.stats` once a fight has started: `sigStrikesLanded/Attempted`, `sigStrikeAccuracy`, `totalStrikesLanded/Attempted`, `knockdowns`, `takedownsLanded/Attempted`, `submissionAttempts`, `reversals`, `controlTime`, `sig{Head,Body,Leg,Distance,Clinch,Ground}StrikesLanded`. Per-round stats not mapped |
| Lineups / Team / Roster / Player / Standings | — | — | Not supported; a fighter page (from the cards a fighter appears on) is a later step |
| Live | `event/live/{fmid}` | whole document | `live()` polls `game()` every 10 s: the card, plus `fight/live` for the fight itself |

## TODO

- [ ] Capture UFC 331 (2026-09-19, prelims 21:30Z) tick by tick: event `Status` while
      live, fight `Live` / `Over`, `LiveRoundNumber` / `LiveRoundElapsedTime` format,
      `LiveEventId`, the tracking sequence as it grows, and `fight/live` mid-fight
      (`OfficialStats` false, partial `RoundStats`). Then add the `CLOCK` capability if the
      elapsed time is usable.
- [ ] Confirm the frontier sweep finds the next Contender Series / Fight Night id when it
      is created.
- [ ] Fighter pages, and the result in the app's stored record (today a restored card is
      re-read once for it).

## Changelog

| Date | Change |
|---|---|
| 2026-09-18 | Initial mapping. Two routes found in ufc.com's live-stats script, 49 cards (1296–1344) swept for enum values, 11 samples captured. `UfcProvider` with the frontier sweep, `Sport.MMA` and `model.combat`. |
