# Sample provenance

All XML samples were captured **2026-09-12 07:10–07:27 UTC** with
`curl -A OpenScore/0.1 https://c01.fogis.se/fogistemplates.se/livescore/xml/<file>`
and re-indented with `xmllint --format` (attribute values untouched).
`livescore-ticker.json` is `GET https://www.svenskfotboll.se/api/livescore-ticker/`
at the same time, pretty-printed with `python3 -m json.tool`.

| Sample | Source file | Notes |
|---|---|---|
| `overview.today.xml` | `overview-1-20260912.xml` | 90 games, all `NOT_STARTED` (captured ~3 h before the first kick-off) |
| `overview.live.xml` | `overview-1-20260913.xml` | captured **2026-09-13 12:38 UTC**, not re-indented: 49 games, nine `*_IN_PROGRESS`; shows the per-game `<events>` the overview carries while live (`HALFSTARTED` with `day-time`, goals) — e.g. Hammarby–Brommapojkarna 1–1 in the 29th minute |
| `overview.live.second-half.xml` | `overview-1-20260913.xml` | captured **2026-09-13 13:08 UTC**: the same day with Hammarby–Brommapojkarna (`6529991`) in the second half — the overview still lists only the first half's `HALFSTARTED` |
| `game-info.live.second-half.xml` | `game-info-6529991.xml` | same moment: both `HALFSTARTED` events (14:01:02 and 15:04:35 local), 2–1, `livescorereporttype="1"` |
| `overview.district.xml` | `overview-11-20260912.xml` | Medelpad district association |
| `overview.future-empty.xml` | `overview-1-20270301.xml` | empty, `status="200"` |
| `overview.past-403.xml` | `overview-1-20250912.xml` | empty, `status="403"` (previous season) |
| `changes.xml` | `changes-1.xml` | |
| `tournaments.xml` | `tournaments-1.xml` | 161 tournaments |
| `tournaments.district.xml` | `tournaments-11.xml` | |
| `schedule.superettan.xml` | `schedule-133340.xml` | Superettan 2026, 30 rounds / 240 games |
| `schedule.svenska-cupen.xml` | `schedule-137810.xml` | Svenska Cupen 2026/27, round 2 (men) |
| `game-info.pre.xml` | `game-info-6529989.xml` | AIK – Västerås SK FK, Allsvenskan round 21, KO 2026-09-12 15:00 local |
| `game-info.final.xml` | `game-info-6536131.xml` | GIF Sundsvall 0–3 Örebro SK, Superettan round 23, 2026-09-10 |
| `game-info.final.cup-shootout.xml` | `game-info-6827326.xml` | Athletic Eskilstuna – IK Oddevold, Svenska Cupen 2026/27 round 2, 2026-08-18; 4–4 aet, 10–11 incl. shootout |
| `game-info.unknown-id.xml` | `game-info-1.xml` | the `status="403"` stub returned for an unknown id |
| `lineup.pre.xml` | `lineup-6529989.xml` | empty `<lineup/>` before publication |
| `lineup.final.xml` | `lineup-6536131.xml` | |

Nothing is truncated.
