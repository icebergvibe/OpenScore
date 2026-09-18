# MLS capture metadata

Captured from the public APIs used by mlssoccer.com on **2026-09-14**, with
Accept: application/json and the repository's descriptive User-Agent.

| File | Exact request | Notes |
|---|---|---|
| seasons.json | stats-api.mlssoccer.com/competitions/MLS-COM-000001/seasons | 2026 current season in this capture. |
| matches.day.pre.json | stats-api.mlssoccer.com/matches/seasons/MLS-SEA-0001KA?competition_id=MLS-COM-000001&match_date=2026-09-20&per_page=1000 | Upcoming schedule. |
| match.final.json | stats-api.mlssoccer.com/matches/MLS-MAT-0009LH | San Diego–Philadelphia final. |
| match-events.final.json | stats-api.mlssoccer.com/matches/MLS-MAT-0009LH/key_events | Newest-first event feed. |
| match-stats.final.json | stats-api.mlssoccer.com/statistics/clubs/matches/MLS-MAT-0009LH | Same match. |
| standings.2026.json | stats-api.mlssoccer.com/competitions/MLS-COM-000001/seasons/MLS-SEA-0001KA/standings | One 30-club table. |
| roster.san-diego.json | stats-api.mlssoccer.com/players/seasons/MLS-SEA-0001KA/clubs/MLS-CLU-000065?per_page=100 | San Diego roster. |
| club.san-diego.json | stats-api.mlssoccer.com/clubs/MLS-CLU-000065 | Club profile (captured 2026-09-16). |
| matches.club.json | stats-api.mlssoccer.com/matches/seasons/MLS-SEA-0001KA?match_date[gte]=2026-01-01&match_date[lte]=2026-12-31&team_id=MLS-CLU-000065&per_page=100&sort=match_date | San Diego's 2026 schedule across competitions, incl. two "MLS Test" rows (captured 2026-09-16). |
| clubs.season.json | stats-api.mlssoccer.com/clubs/competitions/MLS-COM-000001/seasons/MLS-SEA-0001KA?per_page=100 | The season's 30 clubs (captured 2026-09-16). |
| match-metadata.pre.json | sportapi.mlssoccer.com/api/matches/MLS-MAT-0009LX | Inter Miami–San Diego pre-game fallback. |
| match.pre.json / match-events.pre.json | Corresponding stats-api paths for MLS-MAT-0009LX | Actual 404 bodies: rich stats resources are not yet created for an upcoming fixture. |
