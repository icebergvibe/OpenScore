# LaLiga API (laliga.com — `apim.laliga.com`)

| | |
|---|---|
| **Sport** | Football (association) |
| **Country / region** | Spain |
| **Official site** | https://www.laliga.com (Liga Nacional de Fútbol Profesional) |
| **Base URLs** | `https://apim.laliga.com/public-service` (main API, paths `/api/v1/…`) and `https://apim.laliga.com/webview` (match-centre API, paths `/api/web/…`) |
| **Auth** | ⚠️ **Public subscription key required** — see [Access](#access). Header `Ocp-Apim-Subscription-Key` (or query `subscription-key=`), one key per service. Both keys are constants shipped in the HTML of every laliga.com page. No cookies, tokens or Origin/Referer checks |
| **Format** | JSON (UTF-8), Azure API Management in front of a Symfony app (404 pages are Symfony's) |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *` on both services; `OPTIONS` preflight `200` with `Access-Control-Allow-Headers: ocp-apim-subscription-key,content-language` and `Access-Control-Allow-Methods: GET` |
| **WAF / UA requirement** | None. Works with no `User-Agent` |
| **Conditional requests** | No `ETag`. `Last-Modified` is the generation time and Azure Front Door answers `If-Modified-Since` from its own cache (a `304` says nothing about the data). `Cache-Control: public, max-age=N` per endpoint (30 s lists, 60 s match, 300 s line-ups) |
| **Last full verification** | 2026-09-12 |
| **Status** | ✅ verified pre-match, **the whole regulation arc** (RC Celta 1-1 Málaga CF, MD 5, 2026-09-13, 366 polls: `PreMatch` → `FirstHalf` → `HalfTime` → `SecondHalf` → `FullTime`) and full-time. Cup and postponed states not yet observed |

## Access

This is the one league in the repo whose API is not literally key-less, documented
under the "public client-side key" exception in
[docs/principles.md](../../../docs/principles.md#nothing-private):

- `GET https://www.laliga.com/en-GB` → `__NEXT_DATA__.runtimeConfig` contains
  `backendUrl` + `backendSubscription` (public-service) and `webviewUrl` +
  `webviewSubscription` (webview). They are plain constants in the server-rendered HTML,
  sent by every visitor's browser; `runtimeConfig.backendServerUrl`
  (`apim-int.laliga.com`) is the server-side variant and does not resolve publicly.
- Values on 2026-09-12: public-service `c13c3a8e2f6b46da9c5c425cf61fab3e`, webview
  `ee7fcd5c543f4485ba2a48856fc7ece9`. The public-service key is unchanged in the
  Wayback Machine's March 2025 copy of the page. **A provider should still read them
  from the page at start-up** rather than hard-code them.
- Without the header: `401 { statusCode: 401, message: "Access denied due to missing
  subscription key…" }` with `WWW-Authenticate: AzureApiManagementKey`. Wrong key: `401
  "…invalid subscription key…"`. Each key works only on its own service (the webview key
  on public-service → `401`).
- `Content-Language: en|es` (or `?contentLanguage=`) selects the language of names
  (`"Matchday 5"` vs `"Jornada 5"`, positions, roles, commentary). Default is Spanish.
  `Country-Code` / `?countryCode=` is only used by broadcaster endpoints.

## Overview

laliga.com is a Next.js site. Its data layer is LaLiga's own platform (ids `lde_id` =
"LaLiga Data Engine", plus Opta ids everywhere: competition `23`, team `t179`, player
`p60772`, match `g2650810`, venue `v2474`). Two services share the same entity shapes:

- **public-service** (`/api/v1`): competitions, seasons ("subscriptions"), matchdays,
  fixtures with scores and status, match header (with `match_time`), standings, squads,
  players, per-season team/player Opta stats, transfers, broadcasters, news/videos.
- **webview** (`/api/web`): the match centre used by the app's web views — match
  header with **second-precision period start/stop timestamps**, line-ups, events
  (goals/cards/subs with minute:second and a UTC stamp), text commentary, full Opta
  team stats, weekly fixtures, standings, "Beyond Stats".

The same API also carries **LaLiga Hypermotion** (Segunda, `laliga-hypermotion-2026`),
**Copa del Rey**, Supercopa, Liga F (`primera-division-femenina-2026`), Primera
Federación, and Opta mirrors of the Premier League, Serie A, Bundesliga, Ligue 1 and the
UEFA competitions (fixtures/results only — use those leagues' own APIs).

What it gives us: fixtures with UTC kick-offs and a rich status enum; a light match
header with score, status and running minute; exact period timestamps; events with
`minute`/`second`/`period` plus a UTC `date_source`; full line-ups with formation and
captain; Opta commentary in English or Spanish; 137 team stats per match; standings
after any matchday; per-player season stats (60+ keys); squads with photos. No
push transport — the site polls every **60–75 s** (`Countdown` with `60 + rand(15)` s
→ `refreshData()` = `/api/v1/matches/{slug}` + `/api/web/matches/{id}/events` + the
open tab's resource).

All samples in [`samples/`](samples/) were captured on **2026-09-12** (season 2026/27,
matchday 5 pending, matchday 4 and Sevilla–Valencia of MD 5 final). See
[`samples/_meta.md`](samples/_meta.md).

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Competition | slug + small int + Opta id | `primera-division` (id `1`, Opta `23`) · `segunda-division` (`2`, `102`) · `copa-del-rey` (`3`, `331`) · `supercopa-de-espana` (`11`) · `primera-division-femenina` (`15`) | `GET /api/v1/competitions` ([sample](samples/competitions.json)) |
| **Subscription** (= competition season) | slug `<brand>-<startYear>` + int | `laliga-easports-2026` (id `395`, 2026/27) · `laliga-hypermotion-2026` (`396`) · `copa-del-rey-2026` (`413`) · `laliga-easports-2025` | `GET /api/v1/subscriptions?competitionSlug=primera-division` — newest first. Slugs follow the sponsor brand, so use the list, do not build them |
| Season | int **start year** + `season` object | `2026` / `{ id: 102, slug: "season-2026-2027", opta_id: "2026" }` | `subscription.year`; `seasonYear=` on squads/matches |
| Matchday ("gameweek") | int `id` + `week` | `10379` / week `5` | `GET /api/v1/subscriptions/{slug}/gameweeks`; `week=` on matches and standings |
| Match | int `id`, `slug`, `opta_id`, `lde_id` | `102297` / `temporada-2026-2027-laliga-ea-sports-sevilla-fc-valencia-cf-5` / `g2650810` / `240578` | any match list. **public-service takes the slug**, webview line-ups/events/comments take the **int id**, webview stats take the **Opta id** (see [quirks](#quirks--gotchas)) |
| Team | int `id`, `slug`, `shortname`, `opta_id`, `lde_id` | `17` / `sevilla-fc` / `SEV` / `t179` | `subscription.teams[]`, match `home_team`, `GET /api/v1/teams?subscriptionSlug=` |
| Player / person | int `id`, `slug`, Opta id on the role | `15165` / `jude-bellingham` / `roles[0].opta_id: "p244855"` | squads (`person.slug`), `GET /api/v1/players?q=` |
| Venue | int `id`, `slug`, `opta_id` | `17` / `ramon-sanchez-pizjuan` / `v2474` | match `venue` (has IANA `timezone`, lat/long, capacity) |
| Event | int `id` | `472378` | `match_events[].id` |
| Squad entry | int `id` | `84728` | `squads[].id` — one row per player-team-season, has `shirt_number`, `loan` |

Slugs are stable and human-readable; match slugs embed season, brand, both team slugs
and the matchday number. Unknown slug → `404 { error: { code: 404, messages:
["\"Match not found\""] } }`; unknown route → APIM `404 { statusCode, message:
"Resource not found" }`; webview unknown path → Symfony HTML 404 (`text/html`).

## Discovery path

1. **Season & competition:** `GET /api/v1/subscriptions?competitionSlug=primera-division`
   → first entry is the current season: `slug`, `year`, `teams[20]`, `rounds[]` (one
   `Regular` round of 38 `gameweeks` for LaLiga; `Regular` 42 + `Playoff 2ª` 4 for
   Hypermotion), `current_gameweek`, `current_gameweek_standing`. Or
   `GET /api/v1/subscriptions/{slug}` for the same object.
2. **Which matchday is on:** `GET /api/v1/subscriptions/{slug}/current-gameweek` →
   `{ gameweek: { id, week, name, date } }`. `date` is nominal (the Sunday), matches
   start on the Friday.
3. **Fixtures with scores:** `GET /api/v1/matches?subscriptionSlug={slug}&week={n}&limit=100&orderField=date&orderType=asc`
   (`teamSlug={team}` instead of `week=` is one club's whole season, 38 rows; `limit=400`
   without either → 500). Own goals carry the scorer's `lineup.team` (Chust, Athletic–Elche
   2026-09-12) — the core credits the other side.
   (10 matches, 60–100 KB) — `status`, `home_score`/`away_score`, `date` (UTC),
   formations once known, venue, officials. Lighter alternatives: webview
   `GET /api/web/subscriptions/{slug}/week/{n}/matches` (18 KB, no competition/gameweek
   objects) or `GET /api/v1/matches/nextmatcheswidget?subscriptionSlug=` (the current
   matchday, with TV channels).
4. **Match header (poll this):** `GET /api/web/matches/{slug}` — `status`,
   `match_time`, `home_score`, `away_score`, `home_formation`, `period_started
   { FirstHalf: { start, stop }, SecondHalf: … }`, `attempt` (attendance), officials.
   (`GET /api/v1/matches/{slug}` is the same header without `period_started`.)
5. **Events / line-ups / commentary / stats:** `GET /api/web/matches/{id}/events`,
   `…/{id}/lineups`, `…/{id}/comments`, `GET /api/web/matches/opta/{opta_id}/stats`.
6. **Standings:** `GET /api/v1/subscriptions/{slug}/standing` (`?week=n` for the table
   after matchday *n*) — or the webview copy `GET /api/web/subscriptions/{slug}/standing`.
7. **Team / player pages:** `GET /api/v1/teams/{slug}`, `…/squad?seasonYear=`,
   `…/standing?competition=&season=`, `…/stats?subscriptionSlug=`;
   `GET /api/v1/players/{slug}`, `…/stats?subscriptionSlug=`, `…/matches?subscriptionSlug=`.

Recommended poll interval for a live match: **30 s** on the header and events (their
`max-age` is 60 s at the edge, so faster polling only returns cached copies; the site
uses 60–75 s), 60 s on the matchday list (`max-age=30`), line-ups once after
`status` leaves `PreMatch` (`max-age=300`). During the first half `status` is `FirstHalf`
on the list and the match resource carries `match_time` and `period_started.FirstHalf.start`
(2026-09-13 live pair); the update latency across a whole match is still unmeasured — see TODO.

## Endpoints

Every request needs `Ocp-Apim-Subscription-Key` for the service it targets and
should send `Content-Language: en` (samples were captured in English). Paths below are
relative to `https://apim.laliga.com/public-service` unless marked **webview**
(`https://apim.laliga.com/webview`).

The list is what the site's JS calls (77 `utils_get` templates on public-service, 45
on webview, extracted from the Next.js chunks); only the ones useful for scores are
documented. Every other template (news, videos, galleries, ambassadors, brands,
broadcasters, procedures, complaints, leads, "partidazos", "experiences", Microsoft
"beyond stats") was seen but not verified.

### Competitions, seasons & matchdays

#### `GET /api/v1/competitions`

| | |
|---|---|
| **Purpose** | Every competition slug the platform knows (38, incl. historical and foreign) with `opta_id` |
| **Parameters** | `limit`, `orderType`, `showInCalendar` |
| **Sample** | [`samples/competitions.json`](samples/competitions.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ total, competitions: [ { id, name, slug, main, opta_id, lde_id, show_in_calendar?, … } ] }`.
`GET /api/v1/competitions/{slug}?seasonYear=2026` ([sample](samples/competition.json))
adds sponsor-banner fields only.

#### `GET /api/v1/subscriptions?competitionSlug={slug}`

| | |
|---|---|
| **Purpose** | Seasons of a competition, newest first (14 for `primera-division`, back to 2013/14) |
| **Sample** | [`samples/subscriptions.json`](samples/subscriptions.json) (**truncated to 3 of 14**) captured 2026-09-12 |
| **Last verified** | 2026-09-12 (also without the filter: 241 subscriptions across all competitions, incl. `english-premier-league-2026`, `champions-league-2026` mirrors) |

`{ total, subscriptions: [ { id, name, slug, season ("2026-2027"), season_name, year,
teams[], rounds[], current_gameweek, current_gameweek_standing, competition } ] }`.

#### `GET /api/v1/subscriptions/{slug}`

| | |
|---|---|
| **Purpose** | One season: the 20 teams (with shields, colours, `opta_id`) and the round/matchday structure |
| **Sample** | [`samples/subscription.json`](samples/subscription.json) (LaLiga) · [`samples/subscriptions-hypermotion.json`](samples/subscriptions-hypermotion.json) (Hypermotion: 22 teams, `Regular` 42 + `Playoff 2ª` 4 matchdays) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`subscription.rounds[] { id, name, slug, position, has_groups, type ("table"),
gameweeks[] { id, week, name, shortname, date } }`. `current_gameweek` is the matchday
being played, `current_gameweek_standing` the one the table is labelled with (MD 6 on
the Saturday of MD 5).

#### `GET /api/v1/subscriptions/{slug}/current-gameweek` · `…/gameweeks` · `…/standing-gameweeks`

| | |
|---|---|
| **Purpose** | Current matchday pointer · all matchdays (`?week=n` filters to one) · matchdays that have a table |
| **Sample** | [`subscription-current-gameweek.json`](samples/subscription-current-gameweek.json) · [`subscription-gameweeks.json`](samples/subscription-gameweeks.json) · [`subscription-gameweeks-week.json`](samples/subscription-gameweeks-week.json) · [`subscription-standing-gameweeks.json`](samples/subscription-standing-gameweeks.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`gameweek.highlight_note` is a Spanish disclaimer string regardless of language.

#### `GET /api/v1/calendar?startDate=&endDate=&competitionSlug=`

| | |
|---|---|
| **Purpose** | Which competitions/matchdays have games on each day of a range — the calendar strip, **no matches in it** |
| **Parameters** | `startDate`, `endDate` (`YYYY-MM-DD`), `competitionSlug`, `offset`, `limit` (default 20) |
| **Sample** | [`samples/calendar.json`](samples/calendar.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ calendars: [ { date: "2026-09-12 00:00:00", calendar_gameweeks: [ { competition,
season, gameweek, has_games } ] } ] }`.

### Matches

#### `GET /api/v1/matches`

| | |
|---|---|
| **Purpose** | Fixture / result list with scores and status — the schedule endpoint |
| **Parameters** | `subscriptionSlug` (required in practice) · `week` · `teamSlug` · `status` (`PreMatch`, `FullTime`, … verified) · `groupSlug`, `roundSlug` (cups) · `seasonYear` · `limit` (site default 100) · `offset` · `orderField` (`date`) · `orderType` (`asc`/`desc`) |
| **Sample** | [`samples/matches-week.pre.json`](samples/matches-week.pre.json) (MD 5: 1 `FullTime`, 9 `PreMatch`) · [`samples/matches-week.live.json`](samples/matches-week.live.json) (MD 5 on 2026-09-13 12:47Z: 5 `FullTime`, 1 **`FirstHalf`**, 4 `PreMatch`; the list carries no minute) · [`samples/matches-week.final.json`](samples/matches-week.final.json) (MD 4, all `FullTime`) · [`samples/matches-team.json`](samples/matches-team.json) (`teamSlug=real-madrid&limit=3`) · [`samples/matches-status.json`](samples/matches-status.json) (`status=PreMatch&limit=3`) · [`samples/matches-season.json`](samples/matches-season.json) (`orderType=desc&limit=3`) · [`samples/matches-segunda.json`](samples/matches-segunda.json) · [`samples/matches-copa.json`](samples/matches-copa.json) · [`samples/matches-last-season.json`](samples/matches-last-season.json) (`laliga-easports-2025&week=38`) · [`samples/matches-premier-league-mirror.json`](samples/matches-premier-league-mirror.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ total, matches: [ … ] }`. Each match: `id`, `name`, `slug`, `date`, `time` (both the
UTC kick-off, ISO with `+00:00`), `hashtag`, `competition`, `home_score`/`away_score`
(`null` before kick-off), `status`, `home_team`/`away_team` (full team objects with
`shield.resizes`, `color`, `opta_id`, `lde_id`), `match_winner_team` (`null` for draws
and pre-match), `home_formation`/`away_formation` (`"4231"`, `null` until published),
`gameweek`, `venue { name, city, timezone, latitude, longitude, capacity, opta_id }`,
`persons_role[] { person, role }` (officials: `Main Referee`, `Assistant 1/2`, `Fourth
official`, `VAR`, `AVAR`), `channels` (TV, `null` when unknown), `season`,
`is_brand_day`, `temperature`, `ball`, `opta_id`, `lde_id`. No events or line-ups.

**`status` enum** (from the site's JS; observed values in bold): **`PreMatch`**,
**`FirstHalf`**, **`HalfTime`**, **`SecondHalf`**, `ExtraFirstHalf`, `ExtraHalfTime`,
`ExtraSecondHalf`, `ShootOut`, **`FullTime`**, `Abandoned` (site shows "SUSP"),
`Postponed` and `Canceled` (site shows "APLZ"). The site treats `Live` as an alias
for the in-play group.

The regulation sequence was watched end to end on 2026-09-13 (Celta 1-1 Málaga, 366 polls
at 5 s). **`period_started` is the whole clock story** and the better of the two sources:

| At | `status` | `match_time` | `period_started` |
|---|---|---:|---|
| 11:12:24Z | `PreMatch` | 0 | absent |
| 12:03:23Z | `FirstHalf` | 0 | `FirstHalf.start` `12:02:33+00:00` |
| 12:51:14Z | `HalfTime` | 48 | `FirstHalf` gains **`stop` `12:50:31+00:00`** |
| 13:08:08Z | `SecondHalf` | 45 | `SecondHalf.start` `13:06:49+00:00` added |
| 13:58:26Z | `FullTime` | 95 | both halves with `start` and `stop` |

Two things follow. `match_time` is a whole minute that trails the wall clock by up to two
minutes and **steps back from 48 to 45** at the restart, while the `start`/`stop`
timestamps give a second-precise clock and `stop` is what says a period has ended. And
`match_time` keeps counting into the break (48 at half time), so the minute to show during
a break is `stop - start`, not `match_time`. A zeroed `match_time: 0` also appears while
`status` is still `PreMatch`, about an hour before kick-off.

#### `GET /api/v1/matches/{slug}` · **webview** `GET /api/web/matches/{slug}`

| | |
|---|---|
| **Purpose** | Match header: status, score, running minute, formations, officials, attendance. The webview copy adds **`period_started`** |
| **Sample** | [`samples/match.final.json`](samples/match.final.json) · [`samples/match.pre.json`](samples/match.pre.json) · [`samples/wv-match.final.json`](samples/wv-match.final.json) · [`samples/wv-match.pre.json`](samples/wv-match.pre.json) captured 2026-09-12 · [`samples/wv-match.live.json`](samples/wv-match.live.json) (Celta–Málaga at the 44th minute) · [`samples/wv-match.halftime.json`](samples/wv-match.halftime.json) · [`samples/wv-match.live-second-half.json`](samples/wv-match.live-second-half.json) (all three from 2026-09-13) |
| **Last verified** | 2026-09-12 |

`{ match: { …list fields…, match_time, attempt, attempt_official, subscription,
period_started } }`.

- **`match_time`** — integer minutes, absent pre-match, `44` at the 44th minute live,
  `98` at full time of a match with 8 minutes of second-half stoppage (i.e. total elapsed
  minutes, not capped at 90). How often it ticks is unmeasured.
- **`period_started`** (webview only) — absent pre-match; during the first half
  `{ FirstHalf: { start: "2026-09-13T12:02:33+00:00" } }` (no `stop` yet); after the match
  `{ FirstHalf: { start: "2026-09-11T19:02:36+00:00", stop: "…19:53:41+00:00" },
  SecondHalf: { start: "…20:11:24+00:00", stop: "…21:04:25+00:00" } }`. Second-precision
  UTC period boundaries — the clock source (`elapsed = now − <period>.start`).
  Presumably `ExtraFirstHalf`/`ExtraSecondHalf`/`ShootOut` keys in cups (unverified).
- **`attempt`** — attendance (`39939`), `attempt_official` boolean.
- The int `id` is **not** accepted here (`404 Match not found` on public-service,
  webview too); the webview line-ups/events/comments below take **only** the int id.

#### **webview** `GET /api/web/matches/{id}/events`

| | |
|---|---|
| **Purpose** | Goals, cards and substitutions with minute:second, period and UTC stamp |
| **Sample** | [`samples/wv-match-events.final.json`](samples/wv-match-events.final.json) (14 events) · [`samples/wv-match-events.pre.json`](samples/wv-match-events.pre.json) (`{ match_events: [] }`) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ match_events: [ { id, match_event_kind { id, name, collection }, lineup { team { id },
person { name, nickname, firstname, lastname } }, lineup_off (substitutions: the player
going off), assist (goals), time, minute, second, clock, period, date_source } ] }`.

- `match_event_kind` observed: `1 Goal` (collection `goal`), `10 Yellow` (`booking`),
  `13 Injury` and `14 Tactical` (`substitution`). Expected but not yet seen: red card,
  second yellow, own goal, penalty (scored/missed), VAR — collect during live captures.
- `minute`/`second` are elapsed match time (second half continues from 45), `time` is the
  conventional minute (`minute + 1`, or `minute` at an exact `:00`), `clock` is `time` as
  a string, `date_source` the Opta UTC timestamp. An unknown id returns empty arrays,
  not `404`.

#### **webview** `GET /api/web/matches/{id}/lineups`

| | |
|---|---|
| **Purpose** | Both squads for the match: coach, XI in formation order, bench |
| **Sample** | [`samples/wv-match-lineups.final.json`](samples/wv-match-lineups.final.json) (24 per side) · [`samples/wv-match-lineups.pre.json`](samples/wv-match-lineups.pre.json) (empty arrays 6 h before kick-off) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ home_team_lineups: [ { id, position, status, shirt_number, captain, person, photos } ],
away_team_lineups: […] }`. `position 0` is the coach (no `status`/`shirt_number`),
`1–11` the starters in formation order (`status: "start"`, goalkeeper first),
`12+` the bench (`status: "sub"`). `photos` are `assets.laliga.com/squad/…` URLs
keyed by pose (`"003"`) and size. When line-ups are published relative to kick-off is
unverified (the site's `max-age` is 300 s).

#### **webview** `GET /api/web/matches/{id}/comments`

| | |
|---|---|
| **Purpose** | Opta text commentary, newest first (104 entries for a full match), in the request language |
| **Sample** | [`samples/wv-match-comments.final.json`](samples/wv-match-comments.final.json) · [`samples/wv-match-comments.pre.json`](samples/wv-match-comments.pre.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ match_commentaries: [ { content, time, period, match_comment_kind { id } } ] }`.
`time` is the minute (`0` for period markers), `period` one of `PreMatch`,
`FirstHalf`, `SecondHalf`, `FullTime`. `match_comment_kind.id` observed (meaning
derived from the text, may be incomplete): 1 attempt blocked · 2 attempt saved ·
3 corner · 5 period/match end · 6 foul · 7 free kick won · **8 goal** · 9 line-ups
announced · 10 attempt missed · 11 offside · 18 woodwork · **20 yellow card** ·
23 period begins · 24 delay · 25 delay over · **26 substitution** · 31 added time
announced.

#### **webview** `GET /api/web/matches/opta/{opta_id}/stats`

| | |
|---|---|
| **Purpose** | Full Opta team stats for the match (137 keys per team) |
| **Sample** | [`samples/wv-match-stats.final.json`](samples/wv-match-stats.final.json) · [`samples/wv-match-stats.pre.json`](samples/wv-match-stats.pre.json) (`{ match_team_stats: [] }`) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ match_team_stats: [ { opta_team_id ("t191"), stats { accurate_pass, possession_percentage,
total_scoring_att, blocked_scoring_att, post_scoring_att, … } } ] }` — classic Opta F9
keys, **no xG**. Takes the match
`opta_id` (`g2650810`); the int id returns an empty array.

#### **webview** `GET /api/web/subscriptions/{slug}/week/{n}/matches`

| | |
|---|---|
| **Purpose** | Lighter matchday list (18 KB vs 60–100 KB) — same match fields minus `competition`, `gameweek`, formations, `match_winner_team`, `channels`, `opta_id`/`lde_id` |
| **Sample** | [`samples/wv-subscription-week-matches.json`](samples/wv-subscription-week-matches.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

#### `GET /api/v1/matches/nextmatcheswidget?subscriptionSlug=`

| | |
|---|---|
| **Purpose** | The current matchday's matches for the home-page strip, with `channels[]` (TV) and `bet_house` |
| **Sample** | [`samples/matches-nextmatcheswidget.json`](samples/matches-nextmatcheswidget.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

#### `GET /api/v1/matches/{teamSlug}/nextpreviousmatches`

| | |
|---|---|
| **Purpose** | A **team's** previous and next matches (despite the path — a match slug returns `404 Team not found`) |
| **Parameters** | `subscriptionSlug` · `seasonYear` · `previousLimit` (default 1) · `nextLimit` (default 1) · `nextOrderField`/`previousOrderField`, `…OrderType` |
| **Sample** | [`samples/team-nextpreviousmatches.json`](samples/team-nextpreviousmatches.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ match_previous_next: { previous_matches[], next_matches[] } }`.

#### **webview** `GET /api/web/matches/tv?matches[]={id}&matches[]={id}`

| | |
|---|---|
| **Purpose** | TV broadcaster per match id (Spain). Note the PHP-style `matches[]` — `matches=` gives `500` |
| **Sample** | [`samples/wv-matches-tv.json`](samples/wv-matches-tv.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

### Standings & leaderboards

#### `GET /api/v1/subscriptions/{slug}/standing` · **webview** `GET /api/web/subscriptions/{slug}/standing`

| | |
|---|---|
| **Purpose** | League table |
| **Parameters** | `week` (table after matchday *n*; verified `week=3` → `played: 3`) · `groupSlug`, `roundSlug` (cups) · `orderField` (`home`/`away` accepted without visible effect) |
| **Sample** | [`samples/subscription-standing.json`](samples/subscription-standing.json) · [`samples/subscription-standing-week.json`](samples/subscription-standing-week.json) (`week=3`) · [`samples/wv-subscription-standing.json`](samples/wv-subscription-standing.json) (8 KB, lighter team objects) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ total, standings: [ { played, points, won, drawn, lost, goals_for, goals_against,
goal_difference ("+15", a string), position, previous_position, difference_position,
team } ] }`. Sorted by `position`. No home/away split, no form string, no
qualification zones (the per-team variant has a `qualify[]` array). Whether the table
moves during play is unverified.

#### `GET /api/v1/teams/{slug}/standing?competition=&season=`

| | |
|---|---|
| **Purpose** | One team's table row after every matchday so far (position history) |
| **Sample** | [`samples/team-standing.json`](samples/team-standing.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`{ standings: [ { …row…, gameweek { week, date, dateIni, dateEnd (epoch s) }, qualify[] } ] }`.

#### `GET /api/v1/subscriptions/{slug}/players/stats` · `…/teams/stats` · `…/stats`

| | |
|---|---|
| **Purpose** | Season leaderboards: players ranked by one Opta stat · all teams with ~110 season stats · competition totals (games played/pending, top scorer, most goals…) |
| **Parameters** | `stats` (**required** for a ranking: `goals`, `assists`, `yellow_cards`, `red_cards`, `saves`, `total_scoring_att` are what the site uses) · `teamSlug` · `position` · `week` (returned `{}` — unverified) · `limit` (default 20) · `offset`. Do **not** pass `orderField=<stat>` — it empties the response |
| **Sample** | [`samples/subscription-players-stats.json`](samples/subscription-players-stats.json) (`stats=goals&limit=3`) · [`samples/subscription-teams-stats.json`](samples/subscription-teams-stats.json) (**truncated to 3 of 20**) · [`samples/subscription-stats.json`](samples/subscription-stats.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

`player_stats[] { id, name, nickname, slug, position, country, team, shirt_number,
opta_id, stats: [ { name, stat } ], extra_info }` — the whole 60-key stat vector comes
back for each player, not just the ranked one.
`GET /api/v1/subscriptions/{slug}/players/rankings?stats=goals` — the "league leaders"
template in the bundle — returns `{ total: 0 }` for every stat tried
([sample](samples/subscription-players-rankings.json)); use `players/stats`.

### Teams

#### `GET /api/v1/teams?subscriptionSlug=` · `GET /api/v1/teams/{slug}`

| | |
|---|---|
| **Purpose** | Teams of a season (with venue, colours, shields, `opta_id`) · one team |
| **Parameters** | `subscriptionSlug` · `type` · `q` · `limit` (default 15) · `offset` · `orderField` (`id`/`nickname`) · `orderType` |
| **Sample** | [`samples/teams.json`](samples/teams.json) (`max-age=10800`) · [`samples/team.json`](samples/team.json) (`max-age=14400`) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

#### `GET /api/v1/teams/{slug}/squad?seasonYear=` · `…/squad-manager` · `GET /api/v1/squads?teamSlug=&seasonYear=`

| | |
|---|---|
| **Purpose** | Season squad: one row per registered player with `shirt_number`, `position`, `loan`, `person` (bio, `slug`), `photos` (4 poses × sizes), `opta_id` · same but including staff · the same rows via the generic squads search (`q=`, `subscriptionSlug=`) |
| **Parameters** | `seasonYear` (start year) · `limit` (site 50) · `offset` · `orderField` · `orderType` |
| **Sample** | [`samples/team-squad.json`](samples/team-squad.json) (**truncated to 5 of 34**) · [`samples/team-squad-manager.json`](samples/team-squad-manager.json) (**3 of 34**) · [`samples/squads.json`](samples/squads.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

#### `GET /api/v1/teams/{slug}/stats?subscriptionSlug=`

| | |
|---|---|
| **Purpose** | One team's ~110 season stats (`possession_percentage`, `ppda`, `clean_sheets`, `goal_conversion`, …) |
| **Sample** | [`samples/team-stats.json`](samples/team-stats.json) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

### Players

#### `GET /api/v1/players/{slug}` · `…/stats?subscriptionSlug=` · `…/matches?subscriptionSlug=` · **webview** `GET /api/web/players/{slug}` · `GET /api/v1/players?q=`

| | |
|---|---|
| **Purpose** | Bio (birth date/place, height, weight, `country.id` ISO like `GB-ENG`, `roles[].opta_id`, current `team`, `squads[]`) · season stats (60+ keys) · matches played with per-match score · webview copy of the bio · name search across the season |
| **Sample** | [`samples/player.json`](samples/player.json) · [`samples/player-stats.json`](samples/player-stats.json) · [`samples/player-matches.json`](samples/player-matches.json) · [`samples/wv-player.json`](samples/wv-player.json) · [`samples/players.json`](samples/players.json) (`q=vinicius`) captured 2026-09-12 |
| **Last verified** | 2026-09-12 |

Player slugs are first-last (`jude-bellingham`) but not derivable — famous players
sometimes have short slugs (`courtois`, `raul`) and duplicates get `-1`, `-2`; take them
from the squad.

### Misc

- `GET /api/v1/global-data?v3` ([sample](samples/global-data.json)) — app config:
  `APP_CONFIG.home.state` (`on_season`), `COMPETITION_CONFIG`, `STATICS_TIMESTAMP`,
  `VERSION_SPRITE`. No season pointer; `max-age=21600`.
- `GET /api/v1/transfers?competitionSlug=` ([sample](samples/transfers.json)).
- **webview** `GET /api/web/beyond-stats/header` ([sample](samples/wv-beyond-stats-header.json))
  and `/blocks`, `/players`, `/teams`, `/gameweeks` — the Microsoft "Beyond Stats"
  feature; header verified, rest not.
- **webview** `GET /api/web/gameweeks/{id}/summary` → `{}` for MD 4 and MD 5
  ([sample](samples/wv-gameweek-summary.json)); presumably editorial.
- **webview** `GET /api/web/subscriptions/{slug}/teams/{x}/form` → `404` for slug, int
  id and Opta id; `…/faqs` not tried.

## Quirks & gotchas

- **Two services, two keys, three match ids.** Header by slug (both services),
  line-ups/events/comments by int `id` (webview), stats by `opta_id` (webview). Passing
  the wrong kind gives `404` on the header and **empty `200`** on the others — an empty
  events array is not proof there were no events.
- **Keys are public page constants** but still keys: ship the values above and re-read
  `runtimeConfig` from `https://www.laliga.com/en-GB` when the API answers `401`; if
  LaLiga rotates them every consumer breaks at once. See [Access](#access).
- **Edge cache caps liveness.** Azure Front Door honours `max-age`: 30 s lists, 60 s
  match header/events/comments, 90 s webview lists, 180 s stats, 300 s line-ups,
  3–6 h teams/config. `Age`/`X-Cache: TCP_HIT` tell you when you got a cached copy.
  Cache-busting query strings work (`?cb=`) but defeat the purpose; poll at the
  `max-age`.
- **CORS header can be missing on a cache hit.** One `TCP_REMOTE_HIT` response carried
  `Access-Control-Allow-Credentials: true` without `Access-Control-Allow-Origin`;
  fresh responses always had `*`. Browser clients should tolerate a failed request and
  retry.
- **Everything is UTC** (`date`, `time`, `date_source`, `period_started`, `date_of_birth`,
  `foundation`) except `calendar[].date` (`"2026-09-12 00:00:00"`, no zone) and
  `gameweek.date` (nominal midnight of the matchday's Sunday). Venue objects carry the
  IANA `timezone` for local display.
- **Clock:** `match_time` is whole minutes; `period_started.<period>.start` is
  second-precision. Event `minute:second` is elapsed match time (second half from 45:00,
  no separate stoppage field — `88:00` is just minute 88); `time` is the conventional
  minute.
- **`match_time` keeps counting past 90** (`98` = 90 + 8). No `+n` stoppage
  representation.
- **Language is a header** (`Content-Language`) or query (`contentLanguage`); the
  default is Spanish (`"Jornada 5"`). The APIM cache key includes it (`Vary:
  Content-Language`).
- **`persons_role` officials appear pre-match** with `Main Referee`, `Assistant 1/2`,
  `Fourth official`, `VAR`, `AVAR`; the match header adds `attempt` (attendance) after
  the game.
- **Team `color`** is present on most teams, `color_secondary` sometimes; Valencia has
  neither.
- **Brand slugs change with the sponsor** (`laliga-santander-2022` → `laliga-easports-2023`…):
  never build a subscription slug, list them.
- **`players/rankings` is dead** (`{ total: 0 }`, `max-age=3600`); `players/stats` is
  the working leaderboard.
- **`matches/{x}/nextpreviousmatches` takes a team slug**, not a match slug.
- **PHP-style arrays** on webview list params (`matches[]=`).
- **Response sizes:** `matches?week=` is 60–100 KB because every match repeats both
  full team objects with 5 shield URLs each; the webview `week/{n}/matches` is a fifth
  of that. `teams/stats` is 100 KB (all 20 teams × 110 stats) — `limit` did not trim it.
- **Errors:** APIM `401`/`404` are `{ statusCode, message }`; app `404`s are
  `{ error: { code, messages: [ "\"Match not found\"" ] } }` (public-service) or
  RFC-7807-ish `{ type, title, status, detail }` / Symfony HTML (webview);
  `Cache-Control: no-cache, private` on errors.

## Out of scope

- `https://apim-int.laliga.com/public-service` (`backendServerUrl`) — internal name,
  does not resolve.
- `POST` routes on public-service (`/api/v1/complaints`, `/contacts`, `/leads/*`,
  `/press-contact`) — write.
- `/api/v1/private/*` (CMS previews) — need a token.
- `/api/web/videos/create-token`, LaLiga+ / OTT (`ottKey` in `runtimeConfig`) — media.
- The Opta mirrors of other leagues (`english-premier-league-2026`, `italian-serie-a-2026`,
  `german-bundesliga-2026`, `french-ligue-1-2026`, `champions-league-2026`, …) — they
  work (fixtures/results verified for the Premier League) but the leagues' own APIs in
  this repo are richer.
- Fantasy (`fantasylaliga.onelink.me`, `api.mpg.football`-style) and "Fanzone"
  (`fanzoneLoginUrl`) — separate products, login.
- `https://www.laliga.com/_next/data/{buildId}/…json` — the page-props JSON of the
  Next.js pages is key-less but keyed to the build id and is a scrape, not an API.

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `/api/v1/subscriptions?competitionSlug=` | `slug`, `year`, `season`, `rounds[].gameweeks[]` | one "subscription" per competition-season; slug carries the sponsor brand |
| Game (id, teams, start time) | `/api/v1/matches?subscriptionSlug=&week=` | `id`, `slug`, `opta_id`, `home_team`, `away_team`, `date`, `gameweek.week`, `venue` | UTC; three ids to keep |
| GameState | list / header | `status` | 12-value enum incl. extra time, shoot-out, postponed/abandoned; `FirstHalf` observed live, the rest of the in-play values not yet |
| Score | list / header | `home_score`, `away_score` | `null` pre-match; half-time score = count `events` with `period == FirstHalf` |
| Clock / period | **webview** `/api/web/matches/{slug}` | `period_started.<period>.{start,stop}`, `match_time`, `status` | seconds-accurate boundaries (post-match verified; live unverified) |
| GameEvent | **webview** `/api/web/matches/{id}/events` | `match_event_kind`, `lineup.person`, `lineup_off`, `assist`, `minute`, `second`, `period`, `date_source` | goals/cards/subs; red/own-goal/penalty kinds still to observe; players carry names only (no id) — match on `person.name` against line-ups |
| Lineups | **webview** `/api/web/matches/{id}/lineups` | `position` (0 coach, 1–11 XI, 12+ bench), `status`, `shirt_number`, `captain`, header `home_formation` | formation digits + order, no slot coordinates; no player ids (names + photo URL only) |
| Team | `subscription.teams[]`, `/api/v1/teams/{slug}` | `id`, `slug`, `shortname`, `nickname`, `color`, `shield.resizes`, `opta_id` | stable ids |
| Player | `/api/v1/teams/{slug}/squad`, `/api/v1/players/{slug}` | `person.{id, slug, name, nickname, date_of_birth, height, weight, country}`, `shirt_number`, `position`, `roles[].opta_id` | slugs from squads only |
| Standings | `/api/v1/subscriptions/{slug}/standing` | `position`, `played`, `won`, `drawn`, `lost`, `goals_for/against`, `goal_difference`, `points`, `previous_position` | no home/away split; live update unverified |
| Officials / venue | list / header | `persons_role[]`, `venue`, `attempt` | attendance post-match |
| Team / player stats | **webview** `…/opta/{opta_id}/stats`, `/api/v1/subscriptions/{slug}/{players,teams}/stats`, `/api/v1/players/{slug}/stats` | Opta keys | per-match team stats only; no per-match player stats endpoint found |
| Commentary | **webview** `/api/web/matches/{id}/comments` | `content`, `time`, `period`, `match_comment_kind.id` | EN/ES |

## TODO

- [x] **A whole live match captured** 2026-09-13 and curated 2026-09-25: `status` does go
      `FirstHalf`→`HalfTime`→`SecondHalf`→`FullTime`, `match_time` ticks in whole minutes and
      steps back at the restart, and `period_started` gains a `stop` at each break. Still
      open from that list: how stale events are against `date_source`, whether standings move
      during a match, and when line-ups are published - the capture polled the match resource
      59 times but `events` only 11 and `lineups` 3, so it cannot answer those.
- [ ] Observe red card / second yellow / own goal / penalty / VAR event kinds.
- [ ] Cup states (`ExtraFirstHalf`, `ShootOut`) and `period_started` keys in extra time
      — Copa del Rey starts 2026-09-26.
- [x] Keys: the provider ships the documented pair and re-reads `runtimeConfig` only on a
      `401` (the page is 800 KB; reading it on every start cost a second before the first
      request). A `401` with unchanged page keys is passed through as the error it is.

## Changelog

| Date | Change |
|---|---|
| 2026-09-25 | Live states curated from the 2026-09-13 capture of Celta 1-1 Málaga (366 polls): `HalfTime` and `SecondHalf` samples added to the existing first-half pair, so the whole regulation arc is now sampled. Recorded the `period_started` `start`/`stop` behaviour against `match_time`, including `match_time` counting into the break and stepping back at the restart. No mapper change was needed: the clock was already derived from `period_started` with second precision and `running` from the absence of `stop`. |
| 2026-09-12 | Initial mapping: public service + webview endpoints, the page-embedded APIM keys, 58 samples. |
