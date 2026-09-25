# api-health

Checks that every endpoint documented under [`apis/`](../../apis) still answers and still
looks like its captured sample — the automatic half of
[docs/principles.md → "When things break"](../../docs/principles.md#when-things-break).

```
./gradlew :tools:api-health:run                                   # every league
./gradlew :tools:api-health:run --args="--league nhl,shl"         # some leagues
./gradlew :tools:api-health:run --args="--list"                   # what would run, no requests
./gradlew :tools:api-health:run --args="--markdown build/health.md --json build/health.json"
```

Exit code is `1` when any check **fails**; add `--fail-on-warn` to treat shape drift as
failure too. `--no-providers` / `--no-endpoints` run only one half.

## What one run does

For each `apis/<sport>/<league>/health.json`, in file order, one request at a time and no
faster than the file's `minDelayMs` (default 250 ms), the tool:

1. **Requests the endpoint** with the core's polite `KtorFetcher` (descriptive `User-Agent`,
   GET only, 15 s connect / 30 s read timeout) and checks the HTTP status.
2. **Parses the body** as JSON or XML (or accepts any non-empty text).
3. **Asserts the `keys`** the check lists — the handful of paths the core actually reads.
   Missing → ❌ fail.
4. **Compares the shape with the sample**: every key path the sample has down to `depth`
   levels (default 2) must exist in the live response. Missing → ⚠️ warn, listed by name.
   Children of a `null`/empty value in the live response are excused (an off-day `games: []`
   cannot carry `games[].id`), so a quiet day is not drift.

Then, for the league's core provider, it drives the discovery path for real —
`gamesOn(today)`, `game(first id)` if there is one, `standings()` — so a feed that answers
`200` but no longer parses into the model is caught as well.

| Mark | Meaning |
|---|---|
| ✅ | answers, required keys present, sample shape intact |
| ⚠️ | answers, but the sample has paths the live response lacks — check whether the API changed or the sample/doc is stale |
| ❌ | wrong status, not JSON/XML, required key missing, request failed, or the provider threw |
| ⏭️ | not run (a placeholder could not be resolved, e.g. LaLiga keys not discoverable) |

## `health.json`

One per league, next to the README it mirrors. **Keep it in sync with the docs**: when you
add or remove an endpoint in a league README, add or remove its check here.

```jsonc
{
  "league": "nhl",                         // core league id (OpenScore.default)
  "name": "NHL",
  "baseUrl": "https://api-web.nhle.com/v1",
  "headers": { "x-version": "2" },         // optional, sent with every check
  "minDelayMs": 250,                       // optional politeness floor
  "checks": [
    { "name": "score/{date}", "path": "/score/{today}", "sample": "score.json", "keys": ["games[]"] },
    { "path": "/roster/TOR/current", "sample": "roster-current.json", "keys": ["forwards[].id"] },
    { "name": "unknown id", "path": "/v2/matches/1", "status": [404] },
    { "url": "https://other.host/x", "format": "text" },
    { "name": "team(AIK)", "query": "{team(abbrv:\"AIK\"){abbrv name}}", "sample": "team.json" },
    { "path": "overview-1-{today|yyyyMMdd}.xml", "format": "xml", "sample": "overview.today.xml",
      "keys": ["todays-games/game/@id"] }
  ]
}
```

Per check: exactly one of `path` (relative to `baseUrl`), `url` (absolute) or `query`
(GraphQL, sent as `?query=`; the response must carry `data` and no `errors`). Optional
`headers`, `status` (accepted codes, default `[200]`; a documented non-2xx status is the
whole check), `format` (`json` | `xml` | `text`), `sample`, `depth` (`0` disables the shape
comparison), `mapOfObjects` (the response is an id-keyed map — compare the first value's
shape), `keys`, `body`, `note`.

**`body`** makes the check a `POST` instead of a `GET`, carrying that JSON as the request
body (placeholders resolve in it as they do in a path). It is only for a route that answers a
*read* this way and has no `GET` equivalent - HockeyAllsvenskan's table, squads, leaderboards
and play-by-play, which all answer `405` to a `GET`. The tool never calls anything that
mutates; docs/principles.md, "Read-only", says where the line is. Since the body is usually
what picks the answer, pin it whole: a wrong field there is often a `200` with nothing in it
rather than an error.

**Key paths**: `games[].id` (any element), `clock.running`, `[].id` for a bare array; XML
uses `/` and `@attr`: `schedule/tournament/round/game/@status`. Arrays are unions, so a
key present on any element counts.

**Placeholders** in paths, URLs, queries and header values: `{today}`, `{today+3}`,
`{today-1}` (UTC, ISO), `{today|yyyyMMdd}` (any `DateTimeFormatter` pattern),
`{today|epoch}` (seconds at 00:00 UTC), `{year}`, and `{secret.laliga.publicService}` /
`{secret.laliga.webview}` for the page-embedded LaLiga keys, read once per run. Anything
else in braces (a GraphQL selection set) is left alone.

Prefer checks whose parameters come from the samples (a finished game id, last season's
standings) — they are stable and the shape comparison is exact. Use `{today}` for the
"what is on now" endpoints and skip the sample there or expect quiet days.

The tests in `src/test` verify every `health.json` against the repository: samples exist,
every `keys` path is present in the referenced sample, placeholders resolve, names are
unique. `./gradlew :tools:api-health:test` never touches the network.

## Continuous checking

[`.github/workflows/api-health.yml`](../../.github/workflows/api-health.yml) runs the tool
weekly (and on demand), publishes the Markdown report as the job summary and keeps the JSON
as an artifact. A ❌ there is the signal to mark the endpoint `⚠️ broken since <date>` in
the league README and open an issue, per docs/principles.md.
