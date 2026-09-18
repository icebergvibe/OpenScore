# Contributing to OpenScore

Thanks for helping. This page says what a finished contribution looks like; how you get
there is up to you.

## Ground rules

- **Key-less only.** Do not add anything that needs an API key, login, cookie session or
  paid plan. If the only way to reach a feed is with a key, say so in the league README
  under "Out of scope" and stop there. The one exception — a static key every anonymous
  visitor's browser sends — is spelled out in [docs/principles.md](docs/principles.md#nothing-private).
- **Verify before you document.** Nothing goes into `apis/<sport>/<league>/README.md`
  unless you have actually called it and saved the response in `samples/`.
- **Be polite to the APIs.** Read [docs/principles.md](docs/principles.md): one request at
  a time while exploring, no enumerating id ranges, no polling faster than the floor.
- **Keep it reproducible.** Every sample must be reproducible from the exact request
  documented next to it, with the date it was captured.

## Repository layout

| Path | Purpose |
|---|---|
| `apis/<sport>/<league>/README.md` | The league's API documentation. |
| `apis/<sport>/<league>/samples/` | Real, captured responses. One file per endpoint (+ per state where it matters: pre-game, live, final). |
| `apis/<sport>/<league>/health.json` | The league's endpoint checks for [`tools/api-health`](tools/api-health/README.md): one entry per documented endpoint, pointing at its sample. |
| `docs/` | The principles, the data model and the Feed v1 contract. |
| `core/` | Kotlin Multiplatform library: model, providers, HTTP layer. |
| `tools/` | Developer tooling. |
| `apps/` | Applications built on `core/`. |

## Mapping a league

A league is mapped when someone else can use the API from the README alone. That means it:

- lists every endpoint with the exact URL, parameters and required headers, and links each
  one to a captured sample with a "last verified" date;
- explains the id scheme (season, game, team, player ids) and how to obtain ids without
  guessing;
- gives a discovery path — from nothing to today's games, to one game, to its live state
  and lineups — with a recommended poll interval;
- records what pre-game, live and final responses look like and which fields change;
- notes the quirks: timezone convention, caching and edge-cache age, CORS, WAF / `User-Agent`
  requirements, off-season and empty-day shapes, observed enum values;
- lists what was tried and is out of scope, so nobody re-tries it.

Look at an existing league (for example [apis/hockey/nhl](apis/hockey/nhl/README.md)) for
the shape reviewers are used to, and end with a table that says where each concept of the
[core model](docs/data-model.md) comes from — that is what a provider implementer reads.
Where the hints come from — the site's own network traffic, its app, community projects — is
your call; only what you verified goes in.

Then add a `health.json` listing every endpoint you documented (format in
[tools/api-health/README.md](tools/api-health/README.md)), run
`./gradlew :tools:api-health:test` (checks the file against your samples, offline) and
`./gradlew :tools:api-health:run --args="--league <id>"` (for real), add the league to the
coverage table in the root README, and open a PR. Reviewers will spot-check at least two
endpoints by re-running the documented requests. Opening an issue titled `Map: <League>`
first lets others know you are on it.

## Samples

- Save the response body **as returned**, pretty-printed with 2-space indentation
  (`python3 -m json.tool` or `jq .`). Do not hand-edit values.
- If a response is enormous (> ~300 KB), it is OK to truncate *arrays* (keep the first few
  elements) and say so in the README. Never truncate objects.
- File naming: `<endpoint-slug>.<state>.json`, e.g. `gamecenter-boxscore.live.json`,
  `standings.json`. States: `pre`, `live`, `final`, or omitted if not applicable.
- Every sample a `health.json` check names must exist, and every key the check requires
  must be in it (`./gradlew :tools:api-health:test` enforces this). When an endpoint changes
  shape, recapture the sample and update the README in the same PR.
- [`tools/live-capture`](tools/live-capture/README.md) records a game through its provider
  tick by tick, which is the easiest way to get the live-state samples.

## Code

- Kotlin Multiplatform, Gradle Kotlin DSL, `kotlinx.serialization`, Ktor client. Package
  root `org.openscore`.
- Each league is a `LeagueProvider` in `core/` (layout in [core/README.md](core/README.md)).
  DTOs declare only the fields the provider reads; every saved sample must parse through
  them in a unit test, and provider tests replay the samples through a `SampleFetcher` and
  assert the mapped model. Declare `capabilities` honestly.
- Anything an app consumes goes through [Feed v1](docs/feed-v1.md); changes to it are
  additive only.
- Run `./gradlew check` before opening a PR. The Android modules need an SDK with platform
  37: set `ANDROID_HOME` or put `sdk.dir=…` in a git-ignored `local.properties`.
- Keep READMEs true: a change to what a provider serves, or to an endpoint, updates the
  league README (and its `health.json`) and the module README in the same PR.

## Commit and PR style

- Small, focused PRs. One league per PR during mapping.
- Conventional-ish commit prefixes: `docs(nhl): …`, `core: …`, `tools(health): …`, `apps(android): …`.
- Describe *what you verified and how* in the PR body.

## Licence

OpenScore is licensed under the [GNU General Public License v3.0 or later](LICENSE). By
contributing you agree that your contribution is licensed under the same terms. Do not add
code or assets under a licence incompatible with the GPL.

## Code of conduct

Be kind, be constructive, assume good faith.
