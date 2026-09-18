# live-capture

Polls one game (or a day's worth) through its core `LeagueProvider` and keeps **every raw
response the provider read** plus **the mapped model at every poll** — the raw material for
the live-state samples under `apis/**/samples` and for checking that a provider's live
mapping (state, clock, period scores, situation) matches what the feed really does.

```
./gradlew :tools:live-capture:run --args="--league shl"                       # list today's games, no capture
./gradlew :tools:live-capture:run --args="--league shl --next"                # first unfinished game today
./gradlew :tools:live-capture:run --args="--league shl --game p2qoh7wot5"     # one game
./gradlew :tools:live-capture:run --args="--game shl/p2qoh7wot5,nhl/2026010001 --idle 5m --max 16h"   # several leagues, one JVM
./gradlew :tools:live-capture:run --args="--league premier-league,serie-a --live"                    # everything live right now
```

`./gradlew :tools:live-capture:installDist` builds `tools/live-capture/build/install/live-capture/bin/live-capture`
for long runs under `nohup`.

## How it works

For each selected game the tool wraps the shared `KtorFetcher` in a `RecordingFetcher` and
builds its own provider on top of it, so every response that provider reads (and only those —
cache hits are the same bytes again) is captured. Then it loops:

1. `provider.game(id)`, plus `events(id)` when the game call did not include them and
   `lineups(id)` (both optional, `--no-events` / `--no-lineups`).
2. Computes the game's **signature** — state, raw state, score, period number, clock running
   flag, period scores, ending, event and lineup counts, situation — and diffs it with the
   previous poll. The running clock is deliberately not part of it: it changes at every poll.
3. Appends one line to `ticks.jsonl` with the mapped model, always.
4. When the signature changed, at the first poll, or every `--checkpoint` (5 min) while live,
   writes every raw body of this poll as `NNNN-<state>-<endpoint>.json|xml|txt` (skipping a body
   identical to the last one saved for that URL), the Feed v1 rendering as
   `NNNN-<state>.game.json`, and an `index.jsonl` line per file (URL, status, content type).
5. Waits `--interval` (15 s, floor 10 s per docs/principles.md) while live or within 15 min of
   kick-off, `--idle` (60 s) before that, 60 s after the game went final — three more polls
   (`--after-final`) to catch stats settling and the ending being set — then stops.

A provider that throws is recorded too: the tick carries the error and the raw bodies that
caused it are saved, so a feed's "empty body until kick-off" behaviour is captured rather than
lost.

## Reading a capture

```
build/capture/<league>/<gameId>/
  ticks.jsonl                                  one line per poll: state, rawState, score, period, clock, running, periodScores, situation, events, changed[], saved[]
  0001-scheduled-gameday_game-overview_x.json  raw body, named by poll number, mapped state and endpoint path (GraphQL: root field)
  0001-scheduled.game.json                     Feed v1 rendering of the mapped game at that poll
  index.jsonl                                  file ↔ URL ↔ poll, with HTTP status and content type
```

`grep '"changed"' ticks.jsonl` lists the transitions; the raw files saved at those polls are the
candidates for `apis/<sport>/<league>/samples/<endpoint>.<state>.json`. Curate by hand: rename
into the sample convention, truncate long arrays as CONTRIBUTING.md describes, add the file
to the league README's sample table and to the provider test that replays it.

Everything under `build/` is git-ignored; nothing is committed until curated.
