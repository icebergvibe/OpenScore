# Sample provenance

Captured **2026-09-16 ~21:20 UTC** with `curl -H "User-Agent: OpenScore/0.1"`, pretty-printed
with `python3 -m json.tool --indent 2 --no-ensure-ascii`. Complete as returned.

| File | URL | Note |
|---|---|---|
| `roster.bayern.json` | `site.web.api.espn.com/apis/common/v3/sports/soccer/ger.1/teams/132/roster?season=2026` | 25 players, two coaches |
| `roster.sturm-graz.json` | `…/uefa.europa/teams/3746/roster?season=2026` | 31 players, `coach: null` |
| `teams.ger.1.json` | `site.api.espn.com/apis/site/v2/sports/soccer/ger.1/teams` | 18 clubs |
| `teams.uefa.europa.conf.json` | `…/uefa.europa.conf/teams` | 36 clubs |
