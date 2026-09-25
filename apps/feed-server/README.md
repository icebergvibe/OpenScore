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

It binds `0.0.0.0` and is meant for your own machine or your own network. There is no auth
and no rate limit, and one `/v1/games` asking for every league fans out to one upstream per
league: the shared fetcher caps concurrency per host, but the leagues sit on two dozen
different hosts, so the cap does not bound the request as a whole. Exposed to the open
internet it lets anyone drive that fan-out at the league APIs under your IP and your
`User-Agent`, which is how a project gets rate-limited or blocked by the feeds it depends on.
Put it behind something that authenticates and rate-limits before letting it out, or keep it
local. The Android app needs none of this: it talks to the leagues directly, with no server
in between.

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
