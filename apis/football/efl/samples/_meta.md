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
