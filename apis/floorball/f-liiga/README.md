# F-Liiga API

| | |
|---|---|
| **Sport** | Floorball |
| **Competition mapped** | F-Liiga Men |
| **Country / region** | Finland |
| **Official site** | https://fliiga.com |
| **Base URL** | `https://fliiga.com` |
| **Auth** | None for the mapped F-Liiga pages, WordPress REST routes and AJAX proxy |
| **Formats** | JSON plus one server-rendered HTML schedule |
| **Upstream statistics system** | TorneoPal, accessed only through F-Liiga's public proxy |
| **Timezone** | `Europe/Helsinki`; API timestamps include their UTC offset |
| **CORS** | WordPress REST open; schedule and AJAX routes do not expose cross-origin CORS |
| **WAF** | Imunify360 bot protection challenges a client it reads as automating, for the whole domain; it expires - see below |
| **Last full verification** | 2026-09-23 |
| **Status** | ✅ mapped and implemented as `FliigaProvider` (league id `f-liiga`) · 🚧 live WebSocket not yet captured |

## Overview

F-Liiga's WordPress site exposes a useful key-less data layer over TorneoPal. It has three
parts:

1. public WordPress REST records - one `ottelut` post per match and one `joukkueet` post per
   season team - which carry the TorneoPal, WordPress and club identifiers in their ACF
   `meta` and, with `_fields`, answer the whole season's fixtures in a couple of kilobytes;
2. public WordPress AJAX routes for compact game status, full match data, standings and
   player statistics;
3. the men's schedule page, rendered on the server, which is how the site itself shows all
   of this to a reader and the only place the `OT`/`PS` marker appears on a played card.

The provider reads (1) and (2); the page is documented here but not on its path, because it
costs 327 KB where the records cost about four and does not reach beyond the fortnight of
fixtures it lists.

This mapping covers **F-Liiga Men** only. The same pages and scoreboard actions accept the
women's series (`naiset`), but it should be mapped as a separate provider after its own
samples are captured.

The examples used throughout are:

- final: TorneoPal match `929774`, WordPress post `34595`, OLS 3–4 O2-Jyväskylä after
  overtime on 2026-09-20;
- pre-game: TorneoPal match `929763`, WordPress post `34639`, SPV–Jymy at
  `2026-09-21T18:30:00+03:00`.

## Access, caching and CORS

No key, login, cookie or custom header is required for any mapped route. Use a descriptive
user agent:

```sh
curl --compressed \
  -H 'User-Agent: OpenScore/0.1 (+https://github.com/icebergvibe/OpenScore)' \
  'https://fliiga.com/wp-admin/admin-ajax.php?action=match_live_data&lang=en&match_id=929774'
```

The AJAX routes returned `Cache-Control: no-cache, must-revalidate, max-age=0, no-store,
private` and `CF-Cache-Status: DYNAMIC`. The site's own cards poll the compact
`match_live_data` action every **15 seconds** around a game. OpenScore should use the same
interval and must not poll the much larger `match_teams` response as a score clock.

With `Origin: https://example.com`, WordPress REST echoed that origin and allowed
credentials. The schedule page and `admin-ajax.php` responses did not return
`Access-Control-Allow-Origin`; browsers therefore need the feed-server proxy for those
routes. Android and the feed server can call them directly.

The AJAX routes carry `Content-Type: text/html` even when their body is JSON. Select the
parser by endpoint, not response media type.

### The Imunify360 challenge

The site sits behind Cloudflare and, on the origin, Imunify360, whose bot protection
challenges a client it has decided is automating. The challenge covers the whole domain,
including the plain `/en/matches/men/` page, and this is what a challenged client gets:

| Request `Accept` | What the challenged client got |
|---|---|
| `application/json`, or any value naming it | `403` with `{"message": "Access denied by Imunify360 bot-protection. IPs used for automation should be whitelisted"}` |
| `*/*`, `text/html, */*;q=0.5`, a browser's own string, or no `Accept` at all | `200` with a 12 KB `<title>One moment, please…</title>` interstitial |
| `*/*;q=0.5` | `415` from the origin's openresty, which is WordPress rejecting the header rather than the WAF |

**It is not a property of the platform and it is not permanent.** What was observed:

| When | Client | Result |
|---|---|---|
| 2026-09-23 | The app on a Waydroid device, and that device's own `curl` | challenged |
| 2026-09-23 | A desktop client on the **same public IP**, at the same moment | served |
| 2026-09-24 | That desktop, under the OpenScore agent, a browser's agent and no agent at all | challenged |
| 2026-09-24 | The app on a phone, on that same wifi and on mobile data | served |

So the challenge follows whichever client has been making automated-looking requests, and it
lifts again by itself. The machine that maps the feed is exactly the client that earns it:
`tools/api-health`, the live smoke tests and a capture all aim repeat traffic at the origin
from one address, which is worth knowing before reading a red health check here as a feed
regression. An ordinary reader's device is served.

Nothing in the request talks a challenged client out of it. The `User-Agent` makes no
difference, and passing the interstitial means running its JavaScript and carrying the
cookie it sets, which is a login flow by another name and out of scope under
[`docs/principles.md`](../../../docs/principles.md). OpenScore therefore sends its ordinary
headers and lets the failure surface: with the default `Accept`, a challenged client gets a
clean `403` rather than an HTML page that fails to parse, which is the more legible of the
two. The feed server, running where requests are served, is the way around it for a client
that is challenged.

## Identifiers

All identifiers must be discovered rather than guessed.

| ID | Example | Where it comes from |
|---|---|---|
| Season id | `sb2026` | `match_teams.season.torneopal_id` |
| Match TorneoPal id | `929774` | Upcoming schedule card, slug lookup, or match response |
| Match WordPress id | `34595` | `ottelut` slug lookup / `match_teams.wp_post_id` |
| Match slug | `ols-o2-jyvaskyla-20-9-2026` | Schedule game-centre URL |
| Season-team TorneoPal id | `2677` (SPV) | Match team object or WordPress team metadata |
| Club TorneoPal id | `622` (SPV) | `joukkueet.meta._liittyva_seura_torneopal_id` or standings `club.torneopal_id` |
| Team WordPress id | `8157` (SPV) | Schedule card, standings, match slug metadata |
| Player TorneoPal id | `47983` | Lineup, event, player table or WordPress player metadata |
| Player WordPress id | `24997` | Player table / profile REST route |
| Event TorneoPal id | `42142566` | `events[].torneopal_event_id` |

A season-team id changes with team registration and is not the best long-term club
identity. A provider should use the TorneoPal **club id** as `Team.id`. Resolve the current
season-team and WordPress ids through the batched team records described below.

## Discovery path

1. Read the season's match records: `ottelut` ordered by id, descending, one hundred at a
   time with a trimmed `_fields`, until a page holds no more `Miehet` rows of the current
   season. Two pages cover a twelve-team season; the third is the stop. Each row gives the
   TorneoPal match id, the throw-off with its Helsinki offset, both teams' WordPress and
   season-team ids, the recorded score and the attendance.
2. Select by the Helsinki calendar date, or by a team's WordPress id for a team schedule.
3. Fetch standings once. Its `wp_post_id` values provide the exact twelve ids for a single
   batched `joukkueet?include=…` request, which resolves season-team ids to stable club ids;
   the table also carries the club names and crests.
4. For a match whose throw-off has passed but whose record has no result yet, use
   `match_live_data`: it is the only route with a state, and the one the site polls every
   15 seconds while a match runs.
5. Use `match_teams` when opening match detail. It supplies lineups, lines, events, player
   totals, period scores and shot coordinates in one document.
6. Player leaderboard rows provide both TorneoPal and WordPress player ids, the squad per
   team and every season total. Use the WordPress player route when the profile post is
   wanted instead.

The schedule page and the slug lookup below remain the way to go from a game-centre URL a
reader has in hand to a match id, and the page is the only place a played card carries its
`OT`/`PS` marker. Neither is on the provider's path.

## Endpoints

Every supported endpoint below was called and captured on **2026-09-21**.

### `GET /wp-json/wp/v2/ottelut?per_page=100&page={n}&orderby=id&order=desc&_fields={fields}`

| | |
|---|---|
| **Purpose** | The season's fixtures and recorded results, with the whole id bridge |
| **Required parameters** | `per_page`, `page`, `orderby=id`, `order=desc`, `_fields` |
| **Samples** | [`matches.page1.json`](samples/matches.page1.json), [`matches.page2.json`](samples/matches.page2.json), [`matches.page3.json`](samples/matches.page3.json) |
| **Last verified** | 2026-09-23 |

`_fields` accepts dotted sub-fields, and trimming the record to the bridge is what makes this
route cheap: 100 rows are about **2 KB** on the wire against 4.3 KB with the whole `meta`,
because the SEO prose is most of a full record.

```text
id,slug,modified,
meta._torneopal_id,meta._ottelu_aika,
meta._kotijoukkue_torneopal_id,meta._kotijoukkue_wp_id,
meta._vierasjoukkue_torneopal_id,meta._vierasjoukkue_wp_id,
meta._kotimaalit,meta._vierasmaalit,
meta._kausi,meta._sarja,meta._ryhma_nimi,meta._kierros_nimi,meta._yleisomaara
```

The post type holds every season and both series - 2,998 records on 2026-09-23, per
`X-WP-Total` - and there is no meta filter, so the ordering does the selecting: records are
created a season at a time, so `orderby=id&order=desc` puts the current season at the top.
On the capture, page 1 was 100 `Miehet` rows, page 2 held the remaining 68 plus 32 `Naiset`,
and page 3 was `Naiset` only, which is the signal to stop. Filter on `_sarja` and `_kausi`
rather than trusting a page boundary.

Quirks:

- `_kotimaalit` / `_vierasmaalit` are `0` until the record is written, and the write can be
  hours after the final whistle: match `929774` finished at about 19:56 Helsinki on
  2026-09-20 and its record was `modified` at 03:02 the next morning. Read a result only
  once goals or `_yleisomaara` say something, and ask `match_live_data` otherwise;
- there is no status field and no `OT`/`PS` marker, so a record can say what a match ended
  but never how.

### `GET /en/matches/men/`

| | |
|---|---|
| **Purpose** | The page a reader sees: results so far this season, about a fortnight of fixtures |
| **Format** | Server-rendered HTML |
| **Sample** | [`schedule.html`](samples/schedule.html), 327,053 bytes |
| **Last verified** | 2026-09-23 |

Not a complete season: the 2026-09-23 page carried 15 played cards (every result since the
season opened on 09-09) and 7 fixtures, the furthest 2026-09-30. Nearly all of the 327 KB is
site chrome; a card is about 1.2 KB. The page groups cards into upcoming and played
containers. Useful card fields are:

```text
class                 match-Fixture or match-Played
data-match-id         TorneoPal id (present on upcoming cards)
data-match-month      English month and year
data-match-home-team  WordPress team id
data-match-away-team  WordPress team id
data-match-gameday    Unix timestamp
match-link href       stable game-centre URL and slug
```

Played cards contain the score, attendance and an `OT`/`PS` marker where applicable, but
did not carry `data-match-id` in the captured HTML. Always retain the link: the slug lookup
below resolves its id without guessing. This is the only route seen to say that a finished
match went to overtime or penalty shots - the records and the compact card do not, and
`match_teams` only implies it through its period scores.

### `GET /wp-json/wp/v2/ottelut?slug={slug}&_fields=id,slug,link,modified,meta`

| | |
|---|---|
| **Purpose** | Resolve one discovered game-centre slug to match and team ids |
| **Sample request** | `slug=ols-o2-jyvaskyla-20-9-2026` |
| **Sample** | [`match-by-slug.final.json`](samples/match-by-slug.final.json) |
| **Last verified** | 2026-09-23 |

`slug` is an array parameter, so several slugs can be resolved in one request:
`?slug=ols-o2-jyvaskyla-20-9-2026,nokian-krp-lasb-20-9-2026` returned both records
(515 bytes, verified 2026-09-23). The result is a zero- or one-element array per slug
asked for. `id` is the WordPress match id. Important metadata fields are:

```text
_torneopal_id
_ottelu_aika                     ISO timestamp with Helsinki offset
_kotijoukkue_torneopal_id        home season-team id
_kotijoukkue_wp_id               home WordPress team id
_vierasjoukkue_torneopal_id      away season-team id
_vierasjoukkue_wp_id             away WordPress team id
_kotimaalit / _vierasmaalit
_kausi / _sarja / _ryhma_nimi
_yleisomaara
```

### `GET /wp-admin/admin-ajax.php?action=match_live_data&lang=en&match_id={torneopalMatchId}`

| State | Match | Sample |
|---|---|---|
| Pre-game | `929763` | [`match-summary.pre.json`](samples/match-summary.pre.json) |
| Final | `929774` | [`match-summary.final.json`](samples/match-summary.final.json) |

This is the small route used by the site's score cards. It returns match date/time, an ISO
timestamp, status, venue, attendance, score, winner, team ids/names, WordPress link and a
compact `player_stats[]` list. The pre-game response was 4,976 bytes minified and the final
response 5,090 bytes.

Observed status values are `Fixture` and `Played`. The site's JavaScript also handles
`Live`, `Break` and `Penalties` - `child-frontend.js`, read 2026-09-23, switches on exactly
those three, renders `live_minutes` as `N´` beside the score and `PS` in place of it while
the status is `Penalties`, and re-polls every 15 s - but none has been seen in a captured
response, so they are mapped without being claimed as a live capability.

`played_time` was the string `180` on every capture, a regulation final and an overtime one
alike; it is the fixture's slot, not how long the match took.

### `GET /wp-admin/admin-ajax.php?action=match_teams&match_id={torneopalMatchId}`

| State | Match | Sample |
|---|---|---|
| Pre-game | `929763` | [`match-detail.pre.json`](samples/match-detail.pre.json) |
| Final | `929774` | [`match-detail.final.json`](samples/match-detail.final.json), `events` truncated from 426 to the first 100 |

Despite its action name, this is the full match document:

```text
match identity, state, local timestamp, venue, attendance, referees
season, group, playoff-series fields, ticket/stream links
home_team / away_team with ids, logos and WordPress links
result { home_goals, away_goals, period_scores }
lineups.home / lineups.away { players[], coaches[] }
events[]
```

The pre-game capture already had twenty players per team arranged into numbered positions
such as `VL/1`, `KH/1`, `OL/1`, `VP/1`, `OP/1` and `MV/1`; retain the raw code and split the
suffix as the line number. Player rows include shirt number, captain marker, goals,
assists, penalties, plus/minus, shots, blocks, saves, goals conceded and per-period goalie
totals. Pre-game `events[]` contained only fifty `vaihto` lineup-registration events at
`0:00`; do not expose these as game events.

A scheduled match's document is small (2 KB on the wire) and a finished one's is 15 KB
compressed, so it belongs on a match screen and never in a score poll.

Each row also carries a `type` that classifies it without reading Finnish: `GOAL`, `PENALTY`
or `EVENT`. The final capture had 426 low-level events, one `GOAL` per goal and one
`PENALTY`. Observed `event_code` values were:

```text
ottelualkoi, vaihto, mvvaihto, jaksoalkoi, aloitus, aloitusvoitto,
aloitushavio, hallinta, laukaus, torjunta, laukausblokattu, blokki,
laukausmaali, paastetty, maali, syotto, laukausohi, plus, miinus,
jaksoloppui, jaksovaihtui, 2min, otteluloppui
```

Expose only meaningful main events: `maali` as goal, the penalty codes as penalty, shots and
faceoffs for their own sake, and the period/match boundary codes as markers. The rest are
rows that belong to one of those and are folded into it through `connected_event_id`:

| Main row | Its children | What they add |
|---|---|---|
| `maali` | `syotto` | the assist; floorball credits one |
| `laukausmaali` (the shot that scored, not connected to `maali`) | `paastetty` | the goalkeeper beaten. Match it to its goal on side + period + `time_sec`; it is also where a goal's coordinates are, since `maali` has none |
| `laukaus` | `torjunta` | the save, and so the goalkeeper |
| `laukausblokattu` | `blokki` | who blocked it |
| `aloitus` (no side, no player of its own) | `aloitusvoitto`, `aloitushavio` | who won and lost the faceoff |
| any goal | `plus`, `miinus` | the ten players on the floor; the lineup totals already carry this |

`hallinta` (possession) stands alone and is dropped. The fifty `vaihto` rows at `0:00` are
the squad registration rather than line changes and are not game events.

Each event also has period, cumulative display `time` (`61:03` for an overtime goal), elapsed
period `time_sec` (`1173` at `59:33`, which is the third period's own clock), team side
(`koti`/`vieras`), player id/name/number and `score_after`. A penalty's length is in its code
(`2min`), its infraction short form in `description_raw` (`MRK`) and in full in
`event_description_fi` (`Mailarike`).

Periods are `1`–`3` and `4` for overtime. `ottelualkoi` carries period `0` and `otteluloppui`
carried period `9`: both are the match's own boundary markers rather than a real period.

Shot events carry `location` as `"y,x"` in TorneoPal rink units - first `y` across the rink,
observed in −844…832, then `x` along it, observed in 122…1384 and measured from the shooting
team's own end, which is why the site mirrors it for one side. A faceoff writes its spot in
the same field with a zone suffix (`1004,2000/3`), which is not a shot location. A goal's
`placement` (`31,93`) is where in the net the ball went, not where the shot came from. The
site's rendering is:

```text
yPercent = 50 + rawY / 1000 * 50       (invert for home)
xPercent = (400 + rawX) / 4000 * 100   (invert for away)
```

The final sample's `period_scores` were `1–2`, `1–0`, `1–1`, then an `overtime` value of
`3–3`. That overtime value is the score entering overtime, not the overtime-period score;
derive the OT period as final score minus regulation totals (`0–1` here).

### Scoreboards

All scoreboard requests use `GET /wp-admin/admin-ajax.php` with the action named below
and these common parameters:

| Parameter | Value mapped |
|---|---|
| `season` | `2026-2027` |
| `phase` | empty for regular season; `playoffs` is offered by the site but not sampled |
| `series` | `miehet` |
| `lang` | `en` |

#### `action=fliiga_scoreboard_standings`

Sample: [`standings.json`](samples/standings.json) · last verified **2026-09-21**.

The twelve rows include rank, GP, regulation wins/losses, overtime results, goals,
points, points/game, shutouts, recent results, team imagery and both WordPress and club ids.
The source fields map as:

```text
wins              regulation wins
overtime_wins     overtime wins (also present upstream as tied_won)
overtime_losses   overtime losses
loses             regulation losses
```

One recent-result string was `?-6` even though the aggregate row had complete goal totals;
do not parse `matches[].result` as an authoritative score when either side is `?`.

#### `action=fliiga_scoreboard_points`

Sample: [`player-points.json`](samples/player-points.json), truncated from 363 to the first
150 rows (523 KB pretty-printed, 35 KB on the wire) · last verified **2026-09-23**.

Rows contain TorneoPal and WordPress player ids, profile link, name, birth date, shirt
number, portrait, role/team and season totals for games, goals, assists, shots, blocks,
faceoffs, plus/minus and penalty minutes. The 363 rows are every registered outfield player
of the twelve teams (`role` is `Kenttäpelaaja` throughout), 130 of them with no appearance
yet, which makes the two boards together a usable squad list. `standing` is the player's rank
in this ranking, not the team's in the table, and the row names its team without an id - the
join back to a club id is on that display name, which the table spells the same way.

#### `action=fliiga_scoreboard_goalkeepers`

Sample: [`goalkeepers.json`](samples/goalkeepers.json), all 43 rows · last verified
**2026-09-23**.

This uses the same player shape with `role` `Maalivahti`, and adds useful saves, saves/game,
goals conceded, goals-against average and save percentage fields. It is the other half of the
squad: no goalkeeper appears on the points board.

### Teams

`GET /wp-json/wp/v2/joukkueet?include={comma-separated WordPress ids}&per_page={count}&orderby=include&_fields=id,slug,link,title,meta._torneopal_id,meta._liittyva_seura_torneopal_id,meta._liittyva_seura_wp_id`

Sample: [`teams.json`](samples/teams.json), all twelve current men's teams in standings
order, 388 bytes on the wire · last verified **2026-09-23**.

Build `include` from standings `wp_post_id`, rather than hard-coding the captured list.
Each record connects:

```text
id                                      WordPress team id
meta._torneopal_id                      current season-team id
meta._liittyva_seura_torneopal_id       stable TorneoPal club id
meta._synsam_pelaaja_torneopal_id       promoted-player id, not team identity
```

Standings and match detail already contain display names, venues and crests, so this route
is only the identity bridge; `title.rendered` is the HTML-escaped page title
(`Nokian KrP &#8211; miehet`) rather than a name worth showing.

### Player metadata

`GET /wp-json/wp/v2/pelaajat/{wordpressPlayerId}`

Sample: Valtteri Viitakoski, WordPress id `24997`:
[`player.json`](samples/player.json) · last verified **2026-09-21**.

The response connects the WordPress player to TorneoPal player id `47983`, season-team id
`2576` and team WordPress id `8152`. It also supplies the profile name, portrait, birth
date and gender. The leaderboard remains the better source for shirt number and current
season totals.

## Live transport: discovered, not yet captured

The game-centre script opens:

```text
wss://fliiga.api.jj-net.com/ws/matches/{torneopalMatchId}
```

at scheduled start. Its message handler expects the same rich structure as `match_teams`,
including `status`, `result`, `lineups` and `events`, and reconnects after a close. A
connection to upcoming match `929763` before start produced no initial message. No in-play
WebSocket frame or live AJAX response has been captured, so do not advertise
`LIVE_UPDATES`, `CLOCK` or `INTERMISSION_STATE` yet. A future live capture should verify
message cadence, whether frames are snapshots or deltas, clock semantics and reconnect
behaviour. Until then, the 15-second compact AJAX poll is only a candidate live fallback.

## Out of scope

- TorneoPal's direct REST API documents an `api_key` and explicitly says it is for
  server-to-server use. OpenScore must use only F-Liiga's public proxy.
- `GET /wp-json/fliiga/v1/static-data` is listed in the site's REST index but returned HTTP
  403 with “invalid or missing API key” when called with its declared parameters. It is not
  a supported endpoint.
- Playoff scoreboards and F-Liiga Women are visible variants but were not sampled in this
  mapping.

## Captured samples

All JSON samples are pretty-printed with two-space indentation. The one truncated array
(`player-points.json`) is identified above; every other array is complete.

- [`matches.page1.json`](samples/matches.page1.json)
- [`matches.page2.json`](samples/matches.page2.json)
- [`matches.page3.json`](samples/matches.page3.json)
- [`schedule.html`](samples/schedule.html)
- [`match-by-slug.final.json`](samples/match-by-slug.final.json)
- [`match-summary.pre.json`](samples/match-summary.pre.json)
- [`match-summary.final.json`](samples/match-summary.final.json)
- [`match-summary.final-krp.json`](samples/match-summary.final-krp.json)
- [`match-detail.pre.json`](samples/match-detail.pre.json)
- [`match-detail.final.json`](samples/match-detail.final.json)
- [`standings.json`](samples/standings.json)
- [`player-points.json`](samples/player-points.json)
- [`goalkeepers.json`](samples/goalkeepers.json)
- [`teams.json`](samples/teams.json)
- [`player.json`](samples/player.json)

## Core model mapping

This is what `FliigaProvider` (`core/src/commonMain/kotlin/org/openscore/providers/fliiga`) does.

| Core concept | F-Liiga source | Mapping / limitation |
|---|---|---|
| `League` | Static | `League("f-liiga", FLOORBALL, "F-Liiga", "FI", "https://fliiga.com")`; men only |
| `Season.id` / `Game.seasonId` | `_kausi` / `season.name` | `2026-2027`, which is also the `season` parameter every scoreboard action takes; `season.torneopal_id` (`sb2026`) names the same season but no route accepts it |
| `StageKind` | `group_name` / phase | `Runkosarja` → `REGULAR`; playoffs not sampled |
| `Game.id` | `torneopal_id` | Stable numeric string, taken from the match record |
| `Game.startTime` | `match_datetime` / `_ottelu_aika` | Parse the included Helsinki UTC offset |
| League calendar date | `match_date` or epoch in Helsinki | `Europe/Helsinki` convention |
| `Game.venue` | `venue` / `venue_name` | Plain string |
| `Game.home` / `away` | Match plus batched team identity bridge | Stable club id, display name, WordPress crest |
| `Game.state` | `status`, or the record's goals and attendance | `Fixture` → `SCHEDULED`, `Played` → `FINAL`, `Live`/`Penalties` → `LIVE`, `Break` → `INTERMISSION`; the last three are mapped from the site's script, not from a capture |
| `Game.score` | `result` or summary goals | Ignore schedule/meta zeroes while scheduled |
| `Game.ending` | `period_scores` and the shoot-out running score on events | An `overtime` entry means overtime; any event with a non-zero `penalty_shootout_score_after` means penalty shots. Null on a listing row: nothing in the records or the card says how a match was decided |
| `Game.periodScores` | `result.period_scores` plus final total | Correct the overtime quirk documented above |
| `Game.clock` / intermission | `live_minutes` | Mapped to the period it falls in and the elapsed minutes inside it, but unobserved, so `CLOCK` and `INTERMISSION_STATE` are not claimed |
| `Game.events` | `events[]` | Goals, penalties and period markers; related rows join by event id |
| `Game.lineups` | `lineups` | Twenty players per side, lines/positions, captains and coaches |
| `Game.stats` | Lineup totals and low-level events | Team totals can be summed; no separate team-total object |
| `StandingsTable` | Standings action | One regular-season table; site rank breakpoints are 8/10/12 |
| `StandingsRow` | Standings row | Regulation/OT W-L split, GF/GA/GD, points and form |
| `Team` | Standings + `joukkueet` batch | Use TorneoPal club id; WordPress id bridges current season ids |
| `teamSchedule` | Full schedule page | Filter both WordPress team ids and Helsinki dates locally |
| `Player` | Leaderboard + WordPress player record | TorneoPal player id, profile, team, number, portrait and totals |
| Team roster | Both leaderboards | Every registered player of the team, outfield and goalkeepers, matched on the team's display name |

Capabilities:

```text
GAMES_BY_DATE, GAME, EVENTS, LINEUPS, STANDINGS, TEAM, TEAM_SCHEDULE,
ROSTER, PLAYER, PERIOD_SCORES, EVENT_COORDINATES, LINE_GROUPS
```

Live, clock and intermission capabilities stay off until an in-play capture proves the wire
shape, so `live()` throws `UnsupportedCapabilityException`. What the app still shows live is
the day listing: a match inside the result window is read from the compact card each time the
listing is asked, which is where its score comes from.

### What the provider reads, and when

| Call | Requests |
|---|---|
| `gamesOn(date)` | the season's record pages (3 × ~2 KB, kept an hour) + the table and the team records for the identity bridge (2.7 KB, kept five minutes and a day) + one compact card per match inside the window below |
| `game(id)` / `events` / `lineups` | one `match_teams` - 2 KB before the throw-off, 15 KB after |
| `standings` | one table read |
| `roster` / `player` | both leaderboards (35 KB + 7 KB, kept five minutes) |
| `teamSchedule` | the same season records, filtered locally |

A match is read from the compact card when its throw-off was less than three hours ago, or  - 
because a record can be written hours late - when its record still shows no result and the
throw-off was less than a day ago. Outside that, a listing costs no per-match request at all.

## Changelog

| Date | Change |
|---|---|
| 2026-09-24 | Re-tested the Imunify360 challenge: the desktop that had been running the health checks and live tests is now challenged itself, while the app on a phone reads the league on the same wifi and on mobile data. Rewrote the section - the challenge follows the client that automates, not the platform, and it expires. |
| 2026-09-23 | Implemented as `FliigaProvider` in `core/`. Adopted the `ottelut` season listing with a trimmed `_fields` as the schedule route in place of the 327 KB HTML page, whose "complete current schedule" claim was wrong - it carries this season's results and about a fortnight of fixtures. Verified that `slug` resolves several slugs at once. Recaptured `match-detail.final.json` complete, both leaderboards and the table, and trimmed the team records' `_fields` to the bridge. |
| 2026-09-21 | Initial F-Liiga Men mapping: schedule discovery, match-id resolution, pre/final summaries, pre/final full match data, standings, teams and player statistics. WebSocket located but not yet captured live. |
