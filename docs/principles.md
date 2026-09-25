# Principles for using unofficial APIs

The APIs OpenScore uses exist to power the leagues' own websites and apps. They are
not offered to us; we are guests. These rules apply to everything in this repo — docs,
core, tools and apps.

## Identify yourself

Send a descriptive `User-Agent`, e.g.

```
User-Agent: OpenScore/0.3 (+https://github.com/icebergvibe/OpenScore)
```

Some hosts sit behind a WAF (Cloudflare etc.) that rejects unusual agents. If a host
*requires* a browser-like agent to respond, document that fact in the league README.
Prefer honesty; fall back to a browser UA only when there is no other way.

## Rate limits

- **Live games:** poll a game endpoint no more often than every **10 seconds**, and
  only while the game is actually live.
- **Schedules / standings / rosters:** these change rarely. Cache them for minutes to
  hours. Never poll them in a tight loop.
- **Discovery:** when mapping an API, make one request at a time, look at the result,
  then decide the next request. Do not enumerate ID ranges.
- Respect `Cache-Control`, `ETag` and `Last-Modified` headers when present; send
  `If-None-Match` / `If-Modified-Since` where the server supports it.

## Read-only

Only `GET` requests. Never call anything that looks like it mutates state.

Some sites put a *read* behind `POST`: a route that answers with exactly what an anonymous
visitor sees, whose parameters are too many or too structured for a query string.
HockeyAllsvenskan serves its squads, its league table, its per-club stat leaderboards and its
play-by-play this way, and answers `405` to a `GET` on any of them. Those are reachable through
`QueryFetcher`, which is a separate interface from `Fetcher` so that "read-only by construction"
stays true of the transport every other provider uses. The bar for using it: the route must
answer a question, there must be no `GET` that answers the same one, and the body must carry
nothing but the question. Anything that creates, changes or deletes is still out, whatever
method it wants.

## Budget every read

- A list screen uses one compact date or season response. Do not fan out per fixture
  for presentation-only metadata such as crests.
- A detail screen asks only for data valid in the current game state; empty pre-game
  event, lineup and statistics routes wait until play begins.
- Prefer a tiny hash, revision or overview endpoint before reloading a large live
  document. If the upstream has none, keep the polling floor.
- Do not scan future dates speculatively to make an empty screen look busy.
- Bound per-host concurrency and request duration, and do not add unconditional
  automatic retries: during an upstream incident they multiply the load.

`KtorFetcher` in `core/` implements all of this once, so a provider only has to choose
its cache lifetimes.

## Nothing private

Only endpoints that respond without credentials, cookies, tokens or keys. If an
endpoint needs a session cookie from the league's site, it is out of scope even if
the cookie is "free".

**One exception — public client-side keys.** An API that requires a static key is in
scope only if *all* of the following hold, and the league README says so under an
"Access" heading:

1. The key is a constant embedded in the league's public web page or JS bundle and is
   sent by every anonymous visitor's browser (no login, no token exchange, no
   per-session issuance).
2. The API does not check `Origin`/`Referer` or any other proof that the caller is the
   league's own site.
3. The same key has been stable for a long time (say where you checked — Wayback,
   community projects).

Document how to read the key from the page so a provider can refresh it, and never
treat a key that was obtained by logging in, sniffing an app, or reverse-engineering an
obfuscated secret as public. [LaLiga](../apis/football/la-liga/README.md) is documented
under this exception; NFL (`api.nfl.com`, client key + secret → token exchange) does
not qualify.

## One source per league, and explicit provenance

A league's own API is the primary source for that league. A second source is adopted
only to cover a league OpenScore does not have, or for a capability the primary feed
demonstrably lacks (the ESPN roster route supplies squads for the Bundesliga and the
UEFA competitions, whose feeds have none) — and only after it has been mapped,
health-checked and replay-tested like any other feed. Team, player and event ids are
local to each provider: nothing is joined by display name, and a club is linked across
leagues only through the hand-curated crosswalk in `core/` (see
[data-model.md](data-model.md#clubs-across-leagues)). A source that only re-publishes
another vendor's data is never the sole live path.

## Terms of service

Several leagues' terms of service restrict automated access. This project documents
what the public endpoints return; users of the project are responsible for their own
compliance. Do not store or redistribute media (images, video, audio) from these APIs
in this repository — link to it.

## When things break

APIs change without notice. When you notice a broken endpoint:

1. Do not delete the documentation. Mark it `⚠️ broken since <date>` in the league README.
2. Open an issue with the request, the old sample, and the new response.
3. The health tool ([`tools/api-health`](../tools/api-health/README.md)) exists to catch
   this automatically — it runs weekly in CI over every `apis/<sport>/<league>/health.json`.
   Keep that file in sync with the README: add a check when you add an endpoint, and fix
   the sample when the shape changes.
