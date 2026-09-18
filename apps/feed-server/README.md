# feed-server

Serves [OpenScore Feed v1](../../docs/feed-v1.md) over HTTP: every mapped league, one JSON
shape, read-only, CORS-open, with Server-Sent Events for live games. It is also the proxy
a browser app needs for leagues whose APIs have no CORS headers (NHL, SHL, CHL …).

```
./gradlew :apps:feed-server:run                    # http://localhost:8080/v1 against the real leagues
./gradlew :apps:feed-server:run --args="--offline" # same routes, served from apis/**/samples (no network)
./gradlew :apps:feed-server:installDist            # apps/feed-server/build/install/feed-server/bin/feed-server
```

Options: `--port N` (or `PORT`), `--offline`, `OPENSCORE_USER_AGENT` to override the
`User-Agent` (keep it descriptive — see docs/principles.md).

```
curl localhost:8080/v1/leagues
curl "localhost:8080/v1/games?date=2026-10-07&league=nhl"
curl localhost:8080/v1/games/nhl/2026020001
curl -N localhost:8080/v1/games/nhl/2026020001/live
curl localhost:8080/v1/standings/nhl
curl localhost:8080/v1/teams/mlb/147
curl "localhost:8080/v1/teams/mlb/147/schedule?startDate=2026-09-01&endDate=2026-09-30"
curl localhost:8080/v1/teams/mlb/147/roster
curl "localhost:8080/v1/teams/mlb/147/stats?season=2026"
curl localhost:8080/v1/players/mlb/592450
```

The full route list, the JSON shapes and which capability each route needs are in
[docs/feed-v1.md](../../docs/feed-v1.md). Racing (Formula 1) is not on the feed yet.

One `KtorFetcher` is shared by all providers, so the server never asks a league for the
same URL more often than that endpoint's floor (10 s for live game data) no matter how
many clients are connected.
