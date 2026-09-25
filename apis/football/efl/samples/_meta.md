# Sample provenance

All samples captured **2026-09-17 ~21:18–21:20 UTC** with
`curl -H "User-Agent: OpenScore/0.1 (+https://github.com/OpenScore/OpenScore)" -H "Accept: application/json"`
and no other headers (Firestore `runQuery` samples: `POST` with `Content-Type: application/json`,
no key), pretty-printed with `python3 -m json.tool --indent 2 --no-ensure-ascii`. The exact
URL for each is in the README next to the sample link.

Matches used:

| id | Match | State | Used by |
|---|---|---|---|
| g2685170 | Manchester City 5–0 Norwich City, Carabao Cup R3, 2026-09-17 18:30Z | `FullTime`, `NormalResult` | `match.final`, `stats-match.final`, `matches-date.final`, `firestore-live-feed.doc`, `firestore-live-feed.runquery` |
| g2685182 | Peterborough United 3–3 Barnsley (7–6 pens), Carabao Cup R3, 2026-09-15 18:30Z | `FullTime`, `PenaltyShootout` | `match.final.shootout`, `firestore-live-feed.runquery.shootout-day` |
| g2634379 | Southampton 2–1 Middlesbrough aet, Championship play-off semi 2nd leg, 2026-05-12 | `FullTime`, `Aggregate`, `ninetyScore`/`extraScore` set | `match.final.extra-time` |
| g2634991 | Hull City 1–0 Middlesbrough, Championship play-off final, Wembley, 2026-05-23 | `FullTime`; **four `matchTeams`** (two placeholders) | `match.final.playoff-final` |
| g2647335 | Bristol City v Watford, Championship round 8, 2026-09-18 19:00Z (captured ~22 h before kick-off) | `PreMatch` | `match.pre`, `stats-match.pre` |
| g2647874 | AFC Wimbledon 0–0 MK Dons, League One, 2026-09-17 19:00Z | `FullTime` | second match in `firestore-live-feed.runquery` |

Team/season samples use the Championship (`competitionID=10`), the Carabao Cup (`2`),
season `2026` and Wrexham (`t109`) for the club schedule. `matches-date.empty` is
2026-09-21 (a Monday with no Championship or cup match) and shows the `404` an empty day
returns. `matches-season-page` is page 2 (`page.size=10`) of the Championship season so the
pagination `links` are visible without a 527 KB sample.

Firestore epoch bounds: `1789603200` = 2026-09-17 00:00Z (`runquery`),
`1789430400`–`1789516800` = 2026-09-15 (`runquery.shootout-day`).

## Truncations

- `stats-teams.json` — every stat array (`goals`, `assists`, … 27 of them) cut to its
  first **5** rows; the full response for one competition is 510 KB (24 rows per stat,
  each with a full `team` object). Objects are intact.

Everything else is complete as returned.

## Live states

The five live bodies are polls of a `tools/live-capture` recording of `g2647335` (Bristol
City 1-0 Watford, Championship round 8, 2026-09-18), 601 polls at ~15 s from 17:04Z to
20:55Z, over `/matches/{id}` and `/stats/match/{id}`.

```
nohup tools/live-capture/build/install/live-capture/bin/live-capture --league championship --game g2647335 --max 4h
```

| Sample | Poll | Feed state |
|---|---|---|
| `match.pre-matchday.json` | 67 (18:10:25Z) | `attributes.period: "PreMatch"`, `matchDetails` and both line-ups have just appeared, 50 min before kick-off |
| `match.live.json` | 282 (19:31:28Z) | `matchDetails.period: "FirstHalf"`, `matchTime` 31, 1-0 |
| `match.halftime.json` | 346 (19:47:49Z) | `matchDetails.period: "HalfTime"`, `matchTime` 48, `formattedMatchTime` `"45' +3'"`, `halfScore` still null |
| `match.live-second-half.json` · `stats-match.live.json` | 590 (20:50:10Z) | `matchDetails.period: "SecondHalf"`, `matchTime` 93, 17 events, one poll so the pair is consistent |

In every one of those live bodies the **outer** `attributes.period` still reads `PreMatch`;
across the whole recording it said `PreMatch` 69 times and `FullTime` once. That is the point
of keeping `match.live.json` and `match.halftime.json` rather than only the half-time one.

The day listing (`/matches?from=…`) was **not** polled by this capture, so there is still no
live `matches-date` sample and no evidence about whether its flat `matchPeriod` goes stale the
same way.

Pretty-printed with `jq .`; nothing truncated. The raw recording is under
`build/capture/championship/` and is git-ignored.
