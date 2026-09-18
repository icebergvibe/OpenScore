# How the UEFA samples were captured

All files were captured on **2026-09-13 between 05:26Z and 05:50Z** with

```
curl -sS -H "User-Agent: OpenScore/0.1 (+https://github.com/openscore/OpenScore)" -H "Accept: application/json" <url>
```

and pretty-printed with `json.dump(indent=2, ensure_ascii=False)`. The exact URL of every
file is in the README's endpoint tables and in [`../health.json`](../health.json).

| File | Request | Notes |
|---|---|---|
| `competitions.json` | `comp.uefa.com/v2/competitions?competitionIds=1,14,2019` | |
| `matches.day.json` | `match.uefa.com/v5/matches?competitionId=1&fromDate=2026-09-08&toDate=2026-09-08&limit=50&offset=0&order=ASC` | UCL league phase MD1, day 1 — 6 finished |
| `matches.day-pre.json` | same with `competitionId=14`, `2026-09-16` | UEL MD1 day 1 — 9 upcoming |
| `matches.day-uecl.json` | same with `competitionId=2019`, `2026-10-15` | UECL MD1 — 18 upcoming |
| `matches.empty.json` | same with `competitionId=1`, `2026-09-13` | `[]` |
| `matches.season.json` | `…/matches?competitionId=1&seasonYear=2027&limit=500&offset=0&order=ASC` | **truncated** to the first 5 of 234 (qualifying round 1) |
| `matches.by-id.json` | `…/matches?matchId=2049553,2050063` | |
| `match.pre.json` | `…/matches/2050063` | Omonia–Celta, UEL MD1, `UPCOMING`, line-ups not out |
| `match.final.json` | `…/matches/2049553` | Real Madrid 2–1 Inter, UCL MD1 |
| `match.final-two-legs.json` | `…/matches/2048635` | TNS–Sabah, first qualifying round second leg, `aggregate` + `winner.aggregate` |
| `match.final-extra-time.json` | `…/matches/2047770` | Juventus 3–2 Galatasaray aet (2025/26 play-off, second leg) |
| `match.final-penalties.json` | `…/matches/2047742` | 2026 final Paris 1–1 Arsenal, 4–3 on penalties |
| `match.not-found.json` | `…/matches/999999999` | HTTP 404 |
| `match-events.main.final.json` | `…/matches/2049553/events?filter=MAIN&order=ASC&limit=500&offset=0` | 38 events |
| `match-events.all.final.json` | same with `filter=ALL` | **truncated** to the first 40 of 152 |
| `match-events.lineup.final.json` | same with `filter=LINEUP` | goals, cards, subs |
| `match-events.phases.final.json` | same with `filter=PHASES` | |
| `match-events.main.final-penalties.json` | `…/matches/2047742/events?filter=MAIN…` | extra time + shoot-out, coach booked (staff actor) |
| `match-events.main.own-goal.json` | `…/matches/2048624/events?filter=MAIN…` | `subType: OWN` and `PENALTY` goals |
| `match-events.phases.final-extra-time.json` | `…/matches/2047770/events?filter=PHASES…` | `CHANGE_PHASE` vs `START_PHASE` through extra time |
| `match-events.main.pre.json` | `…/matches/2050063/events?filter=MAIN…` | `[]` |
| `match-lineups.final.json` / `.pre.json` | `…/matches/{2049553,2050063}/lineups` | |
| `livescore.json` | `…/livescore` | two upcoming Regions' Cup matches (±1 h window) |
| `team-statistics.final.json` / `.pre.json` | `matchstats.uefa.com/v1/team-statistics/{2049553,2050063}` | 429 statistics per team / `[]` |
| `standings.json` / `standings.uel.json` / `standings.previous-season.json` | `standings.uefa.com/v1/standings?competitionId={1,14,1}&seasonYear={2027,2027,2026}&phase=TOURNAMENT` | |
| `teams.json` / `teams.by-id.json` | `comp.uefa.com/v2/teams?competitionId=1&seasonYear=2027&limit=100&offset=0` / `?teamIds=50051,50138` | |
| `players.json` / `players.by-id.json` | `comp.uefa.com/v2/players?competitionId=1&seasonYear=2027&limit=50&offset=0` / `?playerIds=250076574` | |

Live-state samples (`match.live*.json`, `match-events.main.live*.json`, `livescore.live.json`)
are pending — see the README's "Game states".
