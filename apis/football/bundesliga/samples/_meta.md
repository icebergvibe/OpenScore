# Sample provenance

All samples captured **2026-09-11 ~17:35–17:50 UTC** (pre-match / finished states)
with `curl -A "OpenScore/0.1 (+https://github.com/openscore; api-mapping)"` and no
other headers (none are required), pretty-printed with
`json.dump(indent=2, ensure_ascii=False)`. The exact URL for each is in the README
next to the sample link. Base URL:
`https://bundesliga-web-prod.europe-west1.firebasedatabase.app/`, competition
`DFL-COM-000001`, season `DFL-SEA-0001KA` (2026/27). `config.json` is from
`https://wapp.bapi.bundesliga.com/config/configNode.json`.

Match used for every state (`*.pre.json` → `*.live.json` → `*.halftime.json` →
`*.live2.json` → `*.final.json`):

| matchId | Matchday | Match | Captured |
|---|---|---|---|
| `DFL-MAT-J043GZ` | 3 (`DFL-DAY-004CBV`) | 1. FC Union Berlin 1–3 FC Schalke 04, kick-off 2026-09-11 18:30:21Z, full time 20:34Z (90+14) | `pre` 17:35–17:50Z (line-ups already published) · `live` 18:49Z (`FIRST_HALF`, 20') · `halftime` 19:17Z (`HALF`, 45+3) · `live2` 19:33Z (`SECOND_HALF`, 46') · `final` 20:36Z (`FINAL_WHISTLE`, re-captured 2 min after the whistle once `minuteOfPlay` had settled) |

The live snapshots were taken by a poller reading `matches/{id}` every 60 s and
snapshotting `match`, `match-basic`, `match-stats`, `match-lineup`, `liveTable` and
`matches-matchday` on each `matchStatus` transition (plus once at 20'). Three SSE
streams were recorded in parallel; they are summarised in the README, not committed.

Club samples use Bayern München (`DFL-CLU-00000G`); the player season-stats sample is
Maximilian Eggestein (`DFL-OBJ-00258B`). `liveTable.2bl.json` and
`matches-matchday.2bl.json` are 2. Bundesliga (`DFL-COM-000002`, matchday 5);
`liveTable.2025-26.json` is season `DFL-SEA-0001K9`.

## Truncations

None — Firebase responses are id-keyed objects and CONTRIBUTING forbids truncating
objects. Instead, the very large nodes were **not captured whole**:

| Node | Size | What is captured instead |
|---|---|---|
| `/all/…/matchdays.json` | 2.0 MB (BL) / 3.5 MB (2BL) | `matchdays-shallow.json` + one complete match child (`match-data.*.json`) |
| `/en/…/matchdays/{md}.json` | ~460 KB | `matchday-detail-shallow.json` + single match nodes (`match.*.json`) |

`matches.json` (whole season, 306 matches, 330 KB) is complete as returned.
