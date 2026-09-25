# Sample provenance

All samples captured **2026-09-11 ~13:30–14:00 UTC** with
`curl -H "User-Agent: OpenScore/0.1" -H "Accept: application/json"` and no other
headers, pretty-printed with `python3 -m json.tool --indent 2 --no-ensure-ascii`.
The exact URL for each is in the README next to the sample link.

Matches used:

| matchId | Match | State | Used by |
|---|---|---|---|
| 2645215 | Arsenal 2–1 Chelsea, MW 3, 2026-09-06 16:30 BST | `FullTime` | `*.final.json`, `momentum`, `stats`, `commentary`, `officials.final` |
| 2645225 | Bournemouth v Brentford, MW 4, 2026-09-12 15:00 BST | `PreMatch` (captured ~26 h before kick-off) | `*.pre.json`, `preview.pre` |
| 2645230 | Leeds United 4–1 Newcastle United, MW 4, 2026-09-14 20:00 BST (captured 2026-09-16) | `FullTime` | `timeline.final.own-goal.json`, `events.final.own-goal.json` — Miley's own goal: the timeline stamps it `teamId: "4"` (Newcastle, the scorer's side, floor minute 31) while `events` lists it under Leeds with `goalType: "Own"` at the conventional minute 32 |
| 2645228 | Coventry City 0–5 Brighton and Hove Albion, MW 4, 2026-09-13 14:00 BST | `PreMatch` → `FullTime` | every `*.live*.json` and `*.halftime.json`, plus `match.pre-matchday.json`, see Live states below |

Team/player samples use Arsenal (`3`), David Raya (`154561`) and Kai Havertz (`219847`).
`legacy-fixtures.json` is from the old `footballapi.pulselive.com` API for
cross-reference only.

## Truncations

None. Every sample is complete as returned. List endpoints were called with the
`_limit` shown in the README (the default is 10).

## Live states

The live bodies were not curled by hand: they are polls lifted out of a
`tools/live-capture` recording of match `2645228` (Coventry City 0-5 Brighton, MW 4,
2026-09-13), 364 polls at 5 s from 05:17Z to 14:58Z, all five match endpoints per
poll.

```
./gradlew :tools:live-capture:run --args="--league premier-league --game 2645228 --max 10h"
```

| Sample | Poll | Feed state |
|---|---|---|
| `match.pre-matchday.json` | 79 (11:52:52Z) | `PreMatch`, the first poll carrying a zeroed `score`/`clock` block, ~67 min before kick-off |
| `match.live.json` | 159 (13:36:08Z) | `FirstHalf`, `clock` `"35"`, 0-1 |
| `timeline.live.json` | 160 (13:36:41Z) | 3 events: `LINEUP_CONFIRMED`, `FIRST_HALF_START`, `GOAL` |
| `match.halftime.json` · `timeline.halftime.json` | 179 (13:47:35Z) | `HalfTime`, `clock` frozen at `"47"`, `FIRST_HALF_END` present |
| `match.live-second-half.json` · `timeline.live-second-half.json` · `events.live-second-half.json` · `stats.live.json` | 353 (14:52:56Z) | `SecondHalf`, `clock` `"94"`, 0-5, one poll so the four bodies are consistent with each other |
| `lineups.live.json` | 81 (11:53Z) | the published line-ups, byte-identical for the rest of the match |

The `match.live.json` and `timeline.live.json` pair is one poll apart because the
capture writes a body only when it changed, so a single state can need two adjacent
polls to get a complete set.

Pretty-printed with `jq .`; nothing truncated. The raw recording lives under
`build/capture/premier-league/` and is git-ignored.
