# KHL API

| | |
|---|---|
| **Sport** | Ice hockey |
| **Country / region** | Russia, Belarus, Kazakhstan, China |
| **Official site** | https://www.khl.ru (geo-blocked + anti-bot, see below) |
| **Base URL** | `https://khl.api.webcaster.pro/api/khl_mobile/` (primary) · mirror `https://api-video.khl.ru/api/khl_site/` (needs `application=khl_web`) |
| **Auth** | None |
| **Format** | JSON. Errors are JSON `{"error":{"code","message"}}` **with HTTP 200** |
| **CORS** | **Yes** — `Access-Control-Allow-Origin: *` |
| **WAF / UA requirement** | None on webcaster.pro. `khl.ru` itself requires a browser `User-Agent` and an anti-bot cookie, and geo-blocks some regions. |
| **Last full verification** | 2026-09-11 |
| **Status** | ✅ verified (pre-game + final REG/OT/SO) · 🚧 live-state samples pending |

## Overview

The KHL's public data lives in the backend of its **official mobile app and video
platform**, operated by Webcaster (`webcaster.pro`). The same API is used by
khl.ru's own front-end through the mirror host `api-video.khl.ru/api/khl_site/`
(the site's `Api.js` calls `events_v2.json`, `event_v2.json`, `events_alloc.json`,
`calendar_days.json`, … with `application=khl_web`). It is key-less, CORS-open,
served in **English (`locale=en`) or Russian (default)**, and has history back to
2008/09. Live updates are pushed over **MQTT** (`mq.webcaster.pro:8883`, topic
`event_data`) — see [Live updates](#live-updates).

**khl.ru is not a usable source.** From this machine `www.khl.ru` and `text.khl.ru`
answer `403` (obfuscated nginx geo-block page) to any plain request; with a browser
`User-Agent` the site sets Servicepipe-style anti-bot cookies (`spid`, `spsc`) and
then renders Bitrix pages whose data is embedded per page. That violates the
project's no-cookie rule and is fragile, so it is documented under
[Out of scope](#out-of-scope). The `text.khl.ru/api/v1/…` routes from the unverified
note **do not exist**.

Samples captured **2026-09-11**, one week into the 2026–27 regular season
(`stage_id=407`, started 2026-09-04). No game was live at capture time.

## Identifiers

| ID | Format | Example | How to obtain |
|---|---|---|---|
| Stage (season × phase) | int | `407` = Regular 2026/27, `395` = Playoff 2025/26, `370` = Regular 2025/26 | `data.json` → `stages_v2[]` (`{id, khl_id, title, type regular\|playoff, season "2026/2027"}`) and `current_stage_id` |
| Event (game) | int | `3000051` | `events_v2.json` → `event.id`. **Webcaster id, not the KHL id** |
| KHL game id | int | `901978` | `event.khl_id` / `match_id`; used in `outer_url` (`khl.ru/game/{outer_stage_id}/{khl_id}/…`) |
| Team | int | `26` = Lokomotiv | `data.json` → `teams[]`; `team_a.id` / `team_b.id`. Also has `khl_id` (`1`) |
| Player | int | `18295` | `players_v2.json` → `player.id`; `khl_id` (`21886`) is the khl.ru id |
| Date | Unix seconds (`start_at_day`) or ms (`start_at`, `end_at`) | `1788555600` / `1788602400000` | Moscow-day boundaries, see quirks |
| Event type | int | `18` = match, `24` = match (upcoming/pay-per-view?), `4` highlights, `6` moments … | `type_id`; `data.json.video_types` lists video kinds. Only `18` and `24` observed on matches |

**Teams 2026–27 (22, webcaster `id` → Russian `name`):** 8 Динамо М, 10 Авангард, 12 Амур, 16 ЦСКА, 18 Спартак, 22 Торпедо, 24 Сибирь, 26 Локомотив, 28 Трактор, 30 Металлург Мг, 32 Салават Юлаев, 36 Нефтехимик, 38 Динамо Мн, 40 Ак Барс, 42 Северсталь, 44 СКА, 46 Барыс, 56 Автомобилист, 61 Адмирал, 105 Лада, 113 ХК Сочи, 315 Драконы. Use [`samples/data.json`](samples/data.json) `teams[]` as the authoritative list (`locale=en` on `teams_v2.json` gives English names); `teams_for_filter[]` (34) includes historical clubs.

## Discovery path

1. **Bootstrap:** `GET data.json` → `current_stage_id`, `stages_v2[]`, `teams[]`,
   `server_time`, `mqtt_broker`.
2. **Games on a date range:**
   `GET events_v2.json?locale=en&q[start_at_gt_time_from_unixtime]={from}&q[start_at_lt_time_from_unixtime]={to}`
   (seconds). `events_alloc.json` lists the Unix days that have games.
3. **A game:** `GET event_v2.json?id={id}&locale=en` → header, per-period scores,
   `goals[]`, `violations[]`, `text_events[]`, per-player `match_stats`, `start_fives`,
   officials, arena, head-to-head.
4. **Standings:** `GET tables_v2.json?locale=en&stage_id={stage}` (all seasons, 1.2 MB)
   or `tables.json` (current season only).
5. **Rosters / players:** `GET players_v2.json?locale=en&team_id={team}`.

Recommended poll interval for live games: **10–15 s** on `event_v2.json`
(`Cache-Control: max-age=0, private`; `ETag` present but conditional requests not
tested), or subscribe to MQTT. Send `Accept-Encoding: gzip` (1.2 MB → 69 KB).

## Endpoints

### `GET events_v2.json`

| | |
|---|---|
| **Purpose** | Game list — paged, filterable. |
| **Parameters** | `locale` — `en` / `ru` (default ru); `stage_id`; `page` (16 per page, `page_size` **ignored**); `order_direction` — `asc` / `desc` (default desc by `start_at`); `q[start_at_gt_time_from_unixtime]`, `q[start_at_lt_time_from_unixtime]` — seconds; `q[team_a_or_team_b_in][]` — team id (repeatable); `not_only_matches=1` includes non-match videos; `q[type_id_in][]`. `q[start_at_day_eq]` had **no effect**. |
| **Samples** | [`events_v2.json`](samples/events_v2.json) (default, en) · [`events_v2.stage-370.json`](samples/events_v2.stage-370.json) (2025–26) · [`events_v2.date-range.json`](samples/events_v2.date-range.json) (one day) · [`events_v2.team.json`](samples/events_v2.team.json) · [`events_v2.ru.json`](samples/events_v2.ru.json) |
| **Last verified** | 2026-09-11 |
| **Cache** | `max-age=0, private, must-revalidate` |

Bare array of `{ "event": {…} }` wrappers. **Default (no filter) returns the season's
last games first** (March 2027 fixtures), not today's — always filter. Event summary:

```
id, khl_id, match_id, stage_id, outer_stage_id, stage_name "Regular 2026/2027", type_id
game_state_key      – not_yet_started | finished  (live value NOT YET CAPTURED; the mobile app also uses "in_progress")
period              – null before; -1 when finished; live: presumably 1..5
name "Lokomotiv - Traktor", location (city)
start_at_day (s), start_at (ms), event_start_at (ms, broadcast start), end_at (ms)
team_a / team_b     – { id, khl_id, name, location, image }   (team_a = home)
score "5:1", scores { first_period "2:0", second_period, third_period, overtime, bullitt }  – null when not played
sscore              – null in samples
not_regular, hd, has_video, free, m3u8_url, feed_url, iframe_url, iframe_code, image, previews[], condensed_game_id, highlight_id
tickets, infographics, infographics_enabled, likes_enabled, commentator, yandex_*, balancer_type
```

### `GET event_v2.json?id={id}&locale=en`

| | |
|---|---|
| **Purpose** | Full game detail — the live/boxscore endpoint. |
| **Samples** | [`event_v2.pre.json`](samples/event_v2.pre.json) (3000171, CSKA–Admiral) · [`event_v2.final.json`](samples/event_v2.final.json) (3000051, REG 5–1) · [`event_v2.final-overtime.json`](samples/event_v2.final-overtime.json) (2786578) · [`event_v2.final-shootout.json`](samples/event_v2.final-shootout.json) (2786714) · [`event_v2.not-found.json`](samples/event_v2.not-found.json) — all `quotes[]` truncated to 2 |
| **Last verified** | 2026-09-11 |

`{ "event": {…} }` with everything from the summary plus:

```
season "2026/2027", stage_type regular|playoff, arena { id, name, city, address, capacity, geo{lat,long}, website, phone, image }
mref1, mref2, lref1, lref2    – referees / linesmen (Russian names even with locale=en)
commentators_names, views, announce, social_tags, outer_url (khl.ru game page)
team_a / team_b + { shots, gf, ppg, shg, ppc (PP chances), vbr (faceoffs won), pim,
                    total_puck_control_time, total_distance_travelled, offensive_blue_line_crossings_count,
                    start_fives[] {id, khl_id, shirt_number, name, role_key, image},
                    players[] { id, khl_id, shirt_number, name, role_key forward|defensemen|goaltender, image,
                                match_stats[] {id, title, val, max} },
                    top_players[] }
goals[]        { time (s from game start), period, score, status "Powerplay"|…, status_abbr PP|SH|EN|…,
                 author{shirt_number, name, team_id, gps}, assistants[]{shirt_number, name, aps}, quote{video} }
violations[]   { time, period, penalty_time (min), penalty_reason "Interference", violator{shirt_number, name, team_id}, quote }
text_events[]  { seconds, period (1–5 or null), time_s "47:56" (game-clock, cumulative), type, text, score,
                 m3u8_url/feed_url/iframe_url (clip) }   – newest first
quotes[]       – video clips (goals, penalties, saves, recap); large
scores, sscore, this_pair_stat{events_count, team_a{wins_count, goals_count, points_count}, team_b{…}}
other_events_with_both_teams[], condensed_game{}, highlight{}, bets{}, transaction_types[], transactions[]
```

**`text_events.type`** *(observed)*: `state` (period/game start & end — `"Start of 1
period"`, `"End of overtime"`, `"Start of shootout"`, `"End of the game"`), `goal`,
`violation`, `replace` (goaltender change), `bullet` (shootout attempt —
`"Shootout by 20.Lockhart Lucas (Spartak) vs 31.Ozolin Yaroslav (Neftekhimik). Missed."`),
`info` (final stat line). `period` is `4` for OT, `5` for shootout, `null` for
game-level states.

**`match_stats` ids:** skaters `shots, goals, fo, fow, toi (minutes, float), si (shifts),
pim, hits, bls, topSpeed, distanceTravelled, allPasses, successfulPasses`; goalies
`toi, pim, topSpeed, distanceTravelled, allPasses, successfulPasses` (no saves — use
`text_events` `info` line or team `shots`). `max` is the game-high for that stat.

Before the game: `players`, `start_fives`, `text_events`, `goals`, `violations` are
empty; `period: null`. **Unknown id → `200`** with
`{"error":{"code":404,"message":"…"}}` (Russian).

### `GET data.json`

Verified 2026-09-11 · [`samples/data.json`](samples/data.json) (**truncated** to
sport keys). Bootstrap object: `current_stage_id`, `stages_v2[]` (39 stages,
2008/09–2026/27, `type regular|playoff`), `teams[]` (22 current), `teams_for_filter[]`
(34 incl. historical), `server_time` (ms), `server_day` (s), `mqtt_broker{host, port,
secure, event_topic}`, `video_types[]`, `selections[]` (ready-made `events_v2`
filter URLs), `country` (caller's geo, `"MT"` here), `geo_error`. The rest is
app/payment/user config.

### `GET tables_v2.json?locale=en&stage_id={stage}` &nbsp;·&nbsp; `GET tables.json`

| | |
|---|---|
| **Samples** | [`tables_v2.json`](samples/tables_v2.json) (**truncated** to 2 of 19 seasons — original 1.2 MB) · [`tables.json`](samples/tables.json) |
| **Last verified** | 2026-09-11 |

`tables_v2`: bare array, one entry per season `{season, stages[{id, title, type,
regular[], playin[], playoff[], display_rule}], tables{championship[]…}}` — **all
seasons are returned regardless of `stage_id`**. Row: `id, khl_id, name, location,
image, division, division_key, conference, conference_key, gp, w, otw, sow, sol,
otl, l, pts, pts_pct, gf, ga` — **all counts are strings** (`"3"`). `tables.json`
is the current season only: `{regular[{team{…, website, foundation_year,
head_coach, social_networks}}], playoff[], hopecup[]}`.

### `GET players_v2.json?locale=en&team_id={team}` &nbsp;·&nbsp; `GET teams_v2.json` &nbsp;·&nbsp; `GET teams.json`

Verified 2026-09-11 · [`players_v2.json`](samples/players_v2.json) ·
[`teams_v2.json`](samples/teams_v2.json) · [`teams.json`](samples/teams.json).

- `players_v2`: `[{player{id, khl_id, name "Abramov Vitaly", shirt_number, role_key,
  height, weight, age, birthday (s), country, stick l|r, image, flag_image_url,
  team{}, teams[] (career), stats[] {id, title, val, max, min?} season stats,
  quotes[] clips, seasons_count, positions}}]`. `team_id` filter returned 16 entries
  regardless of team in testing (paging/filtering semantics unclear — see TODO).
- `teams_v2`: `[{team{id, khl_id, name, location, image, division, division_key,
  conference, conference_key, …}}]` (Russian without `locale`).
- `teams`: same with standings counts, `website`, `foundation_year`, `head_coach`,
  `social_networks`.

### Other verified endpoints

| Endpoint | Sample | Notes |
|---|---|---|
| `GET events_alloc.json[?timezone=+03:00]` | [events_alloc.json](samples/events_alloc.json) | Bare array of Unix days (s) that have games — 182 days for 2026–27 |
| `GET calendar_days.json` | [calendar_days.json](samples/calendar_days.json) (truncated) | `{year: {month: [days]}}` with games, 2008–2026 |
| `GET events_attrs.json` | [events_attrs.json](samples/events_attrs.json) (truncated) | Filter vocabularies (Russian): match types, persons (4862), arenas, moment types |
| `GET team_info?locale=en&team_id={id}` | — | Returns an **HTML** page (in-app web view), not JSON |

Not existing (`404 text/html`): `stages_v2.json`, `stages.json` (needs params),
`seasons.json`, `tournaments.json`, `protocol.json`, `player_info`.

### Live updates

`data.json.mqtt_broker` = `{host: "mq.webcaster.pro", port: 8883, secure: true,
event_topic: "event_data"}` — the app subscribes over MQTT/TLS for live event
pushes. Not tested (no MQTT client in the mapping environment); payload shape
unknown. Polling `event_v2.json` is the fallback. See TODO.

## Game states

| Source | Values | Core `GameState` |
|---|---|---|
| `game_state_key` | `not_yet_started`, `finished` observed; `in_progress` expected live | SCHEDULED / LIVE / FINAL |
| `period` | `null` pre, `-1` final; live 1–3, 4 = OT, 5 = SO (from `text_events.period`) | clock/period |
| `scores.overtime` / `scores.bullitt` | non-null when played | REG / OT / SO |
| `text_events[0]` (newest) `type=state` | `"Start of 2 period"`, `"End of 2 period"` … | INTERMISSION detection: last state text starts with `End of` and game not finished |

**Correction (2026-09-12):** `text_events.time_s` is the **local wall-clock time** (`"19:31"` start of
period 1, `"21:56"` end of game) and `seconds` counts broadcast seconds — neither is game
time. Game time exists only on `goals[].time` and `violations[].time` (seconds from the
start of the game; period 2 starts at 1200, OT at 3600). There is no running-clock field.

## Quirks & gotchas

- **Two id systems everywhere:** webcaster `id` vs `khl_id` on events, teams and
  players. Use webcaster `id` for API calls; `khl_id` only to link to khl.ru.
- **Timestamps mix units:** `start_at_day` / `birthday` / alloc days are **seconds**;
  `start_at`, `event_start_at`, `end_at`, `server_time`, clip `start_ts` are
  **milliseconds**. Day boundaries are Moscow time (UTC+3).
- **Default list is not "today".** Unfiltered `events_v2.json` returns the latest
  fixtures of the current stage (end of season) in descending order.
- **`page_size` is ignored** (always 16). Page with `page=`.
- **Errors are `200`** with an `error` object; unknown routes are `404 text/html`.
- **Standings numbers are strings.**
- **`locale=en` is partial:** team/player names are translated; referees
  (`mref1`…), `info` text lines, `events_attrs`, `quote.description` stay Russian.
- **Video everywhere:** `quotes[]`, `m3u8_url`, `iframe_code` bloat every response
  (a finished game is ~190 KB, 40 clips). Ignore them in DTOs; do not store.
- `team_info` is HTML; `players_v2` filters behave oddly (see TODO).
- `bets{}` (bookmaker odds) is present on games — ignore.
- The `api-video.khl.ru/api/khl_site/` mirror requires `application=khl_web` and
  returns the same JSON; prefer `khl.api.webcaster.pro`.

## Out of scope

- **`www.khl.ru`, `en.khl.ru`, `text.khl.ru`**: geo-blocked (`403`) for plain
  requests; requires browser UA + anti-bot cookies (`spid`/`spsc`). Data is embedded
  in Bitrix-rendered pages, not an API. Fails the no-cookie principle — not used.
- The `text.khl.ru/api/v1/games`, `/seasons/{id}/games`, `/games/{id}/text`,
  `/lineup`, `/teams/{id}/roster` routes in the unverified note — fictional.
- `data.json` payment / subscription / user keys, `buy.json`, `login.json`, etc.
- Video streams (`m3u8_url`) — licensed content; link, don't embed.

## Core model mapping

| Core concept | Source endpoint | Field(s) | Notes / gaps |
|---|---|---|---|
| League / season | `data.json` | `stages_v2[]` (`season`, `type`) | Stage id doubles as season+phase |
| Game (id, teams, start time) | `events_v2.json` | `id`, `start_at` (ms), `team_a`/`team_b`, `location`, `arena` (detail) | `team_a` is home |
| GameState | `event_v2` | `game_state_key`, `period`, latest `state` text event | Live value unverified |
| Score by period | `scores` | `first_period` … `bullitt` as `"h:a"` strings | Parse strings |
| Clock / period | `period`, `text_events[].time_s` | | **Gap:** no running clock |
| GameEvent | `goals[]`, `violations[]`, `text_events[]` | goals with assists + strength, penalties with reason/minutes, goalie changes, SO attempts | No shots/coords; player ids only via `shirt_number`+`name` in `goals[]` (match to `players[]`) |
| Lineups | `team_a.players[]`, `start_fives[]` | `role_key` | No line numbers. `replace` text events sometimes carry a wrong `period` (1 for a late pull) |
| Team | `data.json.teams[]`, `teams_v2` | `id`, `name`, `location`, `image`, conference/division | |
| Player | `players_v2` | bio + season stats + career teams | **Gap:** `team_id` filter is ignored and there is no by-id endpoint, so the core provider declares neither `ROSTER` nor `PLAYER` until paging/filtering is understood |
| Standings | `tables_v2` / `tables` | rows by conference/division | Strings → ints |
| Shootout | `text_events` `bullet` + `scores.bullitt` | per-attempt text | Parse text for shooter/goalie/result |

## TODO

- [ ] Capture a live game (`event_v2.json` while `game_state_key` ≠ finished) —
      KHL plays almost daily; next games 2026-09-11 14:20Z (Lada–Torpedo `3000167`),
      15:50Z (CSKA–Admiral `3000171`).
- [ ] Subscribe to the MQTT broker once and record the `event_data` payload shape.
- [ ] Work out `players_v2.json` paging/filtering (`team_id` had no effect;
      16 per page).
- [ ] Confirm `type_id` 18 vs 24 semantics (24 = not yet played? pay-per-view?).
- [ ] Test `If-None-Match` against the `ETag`.

## Changelog

| Date | Change |
|---|---|
| 2026-09-11 | Initial mapping via the webcaster.pro mobile API; 12 endpoints verified, 19 samples captured. khl.ru documented as out of scope (geo-block + anti-bot cookie); unverified note's routes confirmed fictional. |
| 2026-09-12 | Core provider (`org.openscore.providers.khl`) built on this doc. Corrections: `text_events.time_s` is wall-clock, not game time; playoff entries in `tables_v2` are brackets (`level`/`pairs`), not rows. |
