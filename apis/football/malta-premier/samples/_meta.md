# Sample provenance

All samples captured **2026-09-11 ~14:00–14:30 UTC** with
`curl -H "User-Agent: OpenScore/0.1" -H "Accept: application/json" -H "x-version: 2"`,
pretty-printed with `python3 -m json.tool --indent 2 --no-ensure-ascii`. The exact
URL for each is in the README next to the sample link.

Matches used:

| id | Match | State | Used by |
|---|---|---|---|
| 53142564 | Zabbar St.Patrick 1–2 Hamrun Spartans, 2026-09-06 18:30Z, Centenary Stadium | `PLAYED` | `*.final.json` |
| 53142579 | Birzebbuga St.Peter's v Hamrun Spartans, 2026-09-11 18:30Z, Victor Tedesco Stadium | `SCHEDULED` (captured ~4 h before kick-off) | `*.pre.json` |

Team/player samples use Hamrun Spartans (`39639`) and Marcello Trotta (`162467`).

## Truncations

- `cms-competitions.json` — the `getCompetitionItemStub` array cut to the first
  **8 of 51** competitions (the rest are youth/women's/hidden entries with the same
  shape; each carries ~5 KB of banner image crops).
- `competitions.json` — `GET /api/competitions` (2,306 rows, 955 KB) cut to the
  first 2 rows plus every row whose `competitionTypeId` is `58539`, `1240053` or
  `58575` (the Malta Premier and its deciders).

Everything else is complete as returned.
