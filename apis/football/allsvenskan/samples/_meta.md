# Sample provenance

All samples captured **2026-09-12 06:24–06:27 UTC** by `POST https://gql.sportomedia.se/graphql`
with `User-Agent: OpenScore/0.1`, `Content-Type: application/json` and the field
selections shown in the README (the ones allsvenskan.se sends), pretty-printed with
`json.dump(indent=2, ensure_ascii=False)`. Season `2026`, round 21 in progress.

Matches used:

| id | League | Match | State | Used by |
|---|---|---|---|---|
| `6529993` | `allsvenskan` | BK Häcken 1–1 Mjällby AIF, round 21, 2026-09-11 17:00Z | `FINISHED` / `FINISHED_RECENTLY` (captured ~11 h after FT) | `match.final.json`, `lineups.final.json`, `match-stats.final.json` |
| `6529989` | `allsvenskan` | AIK – Västerås SK, round 21, 2026-09-12 13:00Z | `UPCOMING` (captured 6.5 h before KO; line-ups not yet published) | `match.pre.json`, `lineups.pre.json`, `match-stats.pre.json`, `match-config.json` |
| `5258746` | `conferenceleague` | Shamrock Rovers 0–1 Hammarby, UECL Q3 2nd leg, 2026-08-14 | `FINISHED` | `match.final.cup.json` |

`standings-for-league.superettan.json` and `matches-for-league.superettan.json` were
captured against `configLeagueName: "superettan"` (round 23 complete, 2026-09-12 06:38Z
for the standings) with the same field selections as their Allsvenskan counterparts.

Club samples use AIK (`team`, `squad`, `team-staff`, `team-form`, `standings-for-team`),
except `matches-for-team.json` (Hammarby `HAM`, because `AIK` errors — see README).
`player.json` is Andronikos Kakoullis (`1500426`).

## Truncations

Arrays only; objects are intact.

| File | Truncation |
|---|---|
| `matches-for-league.season.json` | `matches[]` first 12 of 240 (full response 158 KB) |
| `statistics.json` | `player[]` first 5 of 698 (full response 561 KB); `team[]` complete |
| `leaders.goals.json` / `.assists.json` / `.yellowCards.json` | `leaders[]` first 20 of 173 / 174 / 250 |

Everything else is complete as returned. Error responses (`match-stats.pre.json`)
are saved verbatim because they are the documented behaviour.

## Live states

The five live bodies are polls of a `tools/live-capture` recording of game `6529991`
(Hammarby 3-1 IF Brommapojkarna, Allsvenskan round 21, 2026-09-13), 375 polls at ~15 s
from 05:17Z to 13:59Z.

```
./gradlew :tools:live-capture:run --args="--league allsvenskan --game 6529991 --max 10h"
```

| Sample | Poll | Feed state |
|---|---|---|
| `match.pre-starting.json` | 130 (12:01:11Z) | `UPCOMING`/`UPCOMING_STARTING`, newest event `START` |
| `match.live.json` | 155 (12:11:31Z) | `ONGOING`, `PERIOD_FIRST_HALF`, `"11'"`, 1-0 |
| `match.halftime.json` | 247 (12:48:34Z) | `ONGOING`, `PERIOD_FIRST_HALF`, **`"HT"`**, newest `PERIOD_RESULT`, 2-1 |
| `match.live.stale-during-break.json` | 249 (12:49:31Z) | the **same break**, but an older in-play view again: `"45+1"` with newest `GOAL` |
| `match.live-second-half.json` | 288 (13:09:23Z) | `ONGOING`, `PERIOD_SECOND_HALF`, `"49'"`, 2-1 |

`match.live.stale-during-break.json` is kept on purpose. Two polls after `"HT"` the feed
served an in-play body again, so any state read from a single response flaps during the
break. That is upstream, it cannot be mapped away, and it is one more reason the core
reads Swedish games from Fogis.

108 of the 375 polls returned nothing at all (62 `429` on `match`, 29 `429` on `lineups`,
12 socket timeouts), which is why several adjacent polls have no body on disk.

Pretty-printed with `jq .`; nothing truncated. The raw recording is under
`build/capture/allsvenskan/` and is git-ignored.
