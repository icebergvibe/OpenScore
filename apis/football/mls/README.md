# Major League Soccer API

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | United States / Canada |
| **Official site** | https://www.mlssoccer.com |
| **Base URLs** | stats-api.mlssoccer.com · sportapi.mlssoccer.com/api |
| **Auth** | None |
| **Format / CORS** | JSON; Access-Control-Allow-Origin: * observed |
| **Caching** | stats-api sends Cache-Control: no-store; metadata is public with a 30-second maximum age |
| **Last full verification** | 2026-09-14 |
| **Status** | ✅ scheduled and finished states replay-tested; live-state capture pending |

## Overview

MLS's public match pages use two first-party hosts. stats-api is the schedule,
in-progress/final match, lineup, event, statistics, standings and roster surface. Its
detailed match route does not exist before a fixture has a stats record; sportapi
metadata is the narrow scheduled-game fallback. No ESPN data is used by this provider.

## Identifiers

| ID | Example | Discovery |
|---|---|---|
| Competition | MLS-COM-000001 | Provider constant for MLS regular season. |
| Season | MLS-SEA-0001KA | Seasons endpoint. |
| Match | MLS-MAT-0009LH | A date schedule's match_id. |
| Club | MLS-CLU-000065 | Schedule, match, standings or roster response. |
| Player | MLS-OBJ-00002Y | Match lineup, event or roster response. |

## Discovery path

1. Read the seasons endpoint and choose the greatest season.
2. Read matches/seasons/{season} with competition_id, match_date and per_page=1000.
   match_date is required; an unbounded season request returns 400. This one compact
   response is the complete list-screen source; club IDs resolve through the official crest catalog.
3. For a scheduled match use sportapi /api/matches/{matchId}. For a stats-created match
   use /matches/{matchId}, then /key_events and match statistics as needed.
4. Use the dedicated standings and season+club roster routes for those screens.

Do not poll faster than **10 seconds**. Live updates are not advertised until a live
response is captured and replay-tested.

## Endpoints

All routes below were verified on 2026-09-14. stats-api sends no-store, so the core
fetcher must not retain its bodies.

| Route | Use | Sample |
|---|---|---|
| /competitions/MLS-COM-000001/seasons | season discovery | [seasons.json](samples/seasons.json) |
| /matches/seasons/{season}?competition_id=MLS-COM-000001&match_date={date}&per_page=1000 | fixtures/results on one schedule date | [matches.day.pre.json](samples/matches.day.pre.json) |
| /matches/{matchId} | result/state, venue, full lineup and coaches once created | [match.final.json](samples/match.final.json) |
| /matches/{matchId}/key_events?per_page=1000 | complete newest-first timeline; follow `next_page_token` as `page_token` if present | [match-events.final.json](samples/match-events.final.json) |
| /statistics/clubs/matches/{matchId} | team match statistics | [match-stats.final.json](samples/match-stats.final.json) |
| /competitions/{competition}/seasons/{season}/standings | overall table | [standings.2026.json](samples/standings.2026.json) |
| /players/seasons/{season}/clubs/{club}?per_page=100 | club roster | [roster.san-diego.json](samples/roster.san-diego.json) |
| /clubs/{club} | club profile: names, code, city, country, founded, colours, `stadium_name` | [club.san-diego.json](samples/club.san-diego.json) |
| /matches/seasons/{season}?match_date[gte]=YYYY-MM-DD&match_date[lte]=YYYY-MM-DD&team_id={club}&per_page=200&sort=match_date | a club's schedule across every competition the feed files it under (regular season, play-offs, Leagues Cup, CONCACAF, friendlies) — the range form `match_date` insists on (`required_without_all`); `no-store` | [matches.club.json](samples/matches.club.json) |
| /clubs/competitions/{comp}/seasons/{season}?per_page=100 | the season's clubs (id, names, code, city, country) | [clubs.season.json](samples/clubs.season.json) |
| sportapi /api/matches/{matchId} | pre-game metadata fallback | [match-metadata.pre.json](samples/match-metadata.pre.json) |

Observed final event types: final_whistle, shot_at_goals including goals, own_goals,
cards, substitutions, corner_kicks, fouls and offsides. Observed match states are
scheduled and finalWhistle. The actual 404 pre-game stats responses are retained in
[samples/_meta.md](samples/_meta.md).

## Core model mapping

| Core concept | Source | Mapping |
|---|---|---|
| Game / schedule | date schedule | IDs, UTC planned kick-off, teams, state and result |
| Pre-game detail | sportapi metadata | name, UTC start, venue, logo and scheduled state |
| Finished detail | match route | score, state, venue, lineup and coaches |
| Events | key-events | goals, own goals, cards, substitutions, full time; other observed types are OTHER |
| Match statistics | club match statistics | possession, shots, shots on target, corners, fouls, offsides, cards and xG |
| Standings | standings | one regular-season overall table |
| Roster | players by season/club | identity, birth date, nationality text, shirt and position |

## Out of scope

stats.mlsdigital.net widget routes require an x-api-key and return 403 without one. We
do not extract browser keys; the public hosts above cover this provider.

## Changelog

| Date | Change |
|---|---|
| 2026-09-15 | Load complete event timelines with `per_page=1000`; added official club crests without per-match schedule fan-out. |
| 2026-09-16 | Club profile and club schedule routes (from the `mlsGetMatches`/`mlsClubsAPI` templates in mlssoccer.com's `scripts_react_shared_utils_js` chunk). The club schedule carries an internal "MLS Test" competition (`MLS-COM-00002R`) whose rows look played — dropped by name. `own_goals` events carry the scorer's `team_id`; the core credits the other side. |
| 2026-09-15 | Removed per-match sportapi enrichment from date listings: one day now costs season discovery (normally cached) plus one schedule request, regardless of fixture count. |
| 2026-09-14 | First-party MLS mapping, samples, health checks and core provider added. |
