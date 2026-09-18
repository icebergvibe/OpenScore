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

Team/player samples use Arsenal (`3`), David Raya (`154561`) and Kai Havertz (`219847`).
`legacy-fixtures.json` is from the old `footballapi.pulselive.com` API for
cross-reference only.

## Truncations

None. Every sample is complete as returned. List endpoints were called with the
`_limit` shown in the README (the default is 10).
