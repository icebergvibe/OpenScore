# Lidl Unihockey Prime League API access audit

| | |
|---|---|
| **Sport** | Floorball |
| **Competition audited** | Lidl Unihockey Prime League Men |
| **Country / region** | Switzerland |
| **Official game centre** | https://myapp.swissunihockey.ch/LeagueOrganizer/Magazine/1 |
| **League id** | `9380` (2026/27 men) |
| **Example team** | `8200` (Zug United, L-UPL Men) |
| **Current API host** | `https://swissunihockey-api.azurewebsites.net` |
| **Authentication** | Web: member Bearer token. Android: packaged app credential, with Bearer on authenticated reads |
| **Last verified** | 2026-09-21 |
| **Status** | ⛔ out of scope: no complete key-less schedule/game feed |

## Conclusion

There is no provider OpenScore can adopt under its key-less-data policy. The public Hub
page is a single-page application shell. Its current schedule, table, team and game data
come from an Azure API that returns HTTP 401 to the anonymous web-client request. The
official Android app does not provide a DEL-style key-less alternative: it sends a private
app credential on every request, and the required reads use its authenticated request path.

Two old routes on the `myapp.swissunihockey.ch` host still expose fragments of current
data, including the league table and individual team headers. They cannot discover the
season schedule or game ids, however, and their adjacent schedule routes have either been
removed or return empty arrays. They are not enough for `GAMES_BY_DATE`, `GAME` or a viable
league provider.

No response sample or `health.json` is committed because this audit did not identify a
supported endpoint set. Requiring a private credential is recorded here rather than
capturing or redistributing that credential.

## Browser client

The supplied URLs use hash routes:

```text
https://myapp.swissunihockey.ch/LeagueOrganizer/Magazine/1#/leaguesite/9380/table
https://myapp.swissunihockey.ch/LeagueOrganizer/Magazine/1#/team/8200
```

The fragment never reaches the server. Both URLs first load the same 469 KB HTML shell.
Its public configuration names:

```text
serverUrl:    https://myapp.swissunihockey.ch
newServerUrl: https://swissunihockey-api.azurewebsites.net
```

The current `axiosDatamanager` sends reads to `newServerUrl`. Its request interceptor reads
a `JWT_token` cookie and adds `Authorization: Bearer …`; it does not create an anonymous
token. On **2026-09-21**, all of these exact requests returned HTTP 401, an empty body and
`WWW-Authenticate: Bearer` when called without credentials:

```text
GET /api/leagueapi/getleagueheader?LeagueID=9380
GET /api/leagueapi/getcomingleaguegames?LeagueID=9380&LastGameID=0
GET /api/statisticstableapi/initleaguetable?LeagueID=9380
GET /api/teamapi/getteamheader?TeamID=8200&LoadTeamBanner=false&LoadMemberInformation=false
```

`X-Platform: 2` is the web-client platform header; adding it does not change the result.
The HTML shell itself needs no login and sets only Azure affinity cookies, but it contains
no league, team or game payload.

## Legacy-host leftovers

The previous API remains partly reachable below `https://myapp.swissunihockey.ch/api`.
These reads were tried without cookies, tokens, keys or browser impersonation:

| Request | Result on 2026-09-21 |
|---|---|
| `GET /leagueapi/getleagueheader?LeagueID=9380` | HTTP 200. Identifies “Lidl Unihockey Prime League Men 2026/27” and embeds the current standings as an HTML string in `TableURL`. |
| `GET /teamapi/getteamheader?TeamID=8200&LoadTeamBanner=false&LoadMemberInformation=false` | HTTP 200. Identifies team `8200` as Zug United's men's L-UPL team and supplies club, arena, gender and shirt metadata. |
| `GET /leagueapi/getcomingleaguegames?LeagueID=9380&LastGameID=0` | HTTP 404: the controller no longer has this action. |
| `GET /leagueapi/getleagueteams?LeagueID=9380` | HTTP 404 `Member Not Found`. |
| `GET /leagueapi/initleaguetable?LeagueID=9380` | HTTP 200, but only `{LeagueID: 0, LeagueTable: null, Statistics: null}`. |
| `GET /teamapi/getcomingteamgames?TeamID=8200&LastGameID=0&NrOfGames=10` | HTTP 200 with `[]`. |
| `GET /teamapi/getpreviousteamgames?TeamID=8200&LastGameID=0` | HTTP 200 with `[]`. |

The two useful responses are genuine key-less data and expose
`Access-Control-Allow-Origin: *`, but they form a dead end:

- the table HTML contains league-engagement row ids, not the Sportswik team ids needed by
  team and game routes;
- no working league schedule route supplies game ids;
- the supplied team cannot yield past or future games through the remaining team routes;
- the league header embeds image data, which OpenScore must not redistribute as a sample.

Adopting only these leftovers would create a standings-only integration with no discovery
path and a high risk of disappearing when the legacy controller is removed.

## Android app audit

The public Android package
[`ch.swissunihockey.app`](https://play.google.com/store/apps/details?id=ch.swissunihockey.app),
version `1.5.2.4` (`15204`), was inspected after the web path proved gated. It is a native
Sportswik client and uses the same Azure API host as the website.

Its request layer adds:

```text
x-api-key:             value loaded from the packaged string resources
X-Platform:            3
X-Platform-Version:    1.5.2.4
Authorization:         Bearer {member access token}, on non-anonymous requests
```

The app credential was neither copied nor used. The disassembled call sites classify the
league schedule, team list, standings/statistics and team schedule methods as ordinary
authenticated reads. The session manager obtains and refreshes their Bearer token from the
member login. A request shaped like the mobile client but without its app credential and
member token returned the same HTTP 401.

This differs materially from the [DEL mapping](../../hockey/del/README.md): DEL's app
backend accepts the documented reads without any key, token, login or cookie. A credential
extracted from an APK is explicitly not public under
[`docs/principles.md`](../../../docs/principles.md#nothing-private), even if every installed
copy of the app contains it.

## Official data services

Swiss unihockey's
[webmaster page](https://www.swissunihockey.ch/de/administration/services/webmaster/)
confirms that its API becomes paid for the 2026/27 season: CHF 500 per club per year, with
registration required. The replacement Web Components are free for clubs but also require
registration. The legacy federation APIs likewise document an API key or an authentication
token.

These are legitimate options for a club website, but not for OpenScore's key-less core.
The paid livestream subscription is separate from the problem here; even basic schedules
and standings are gated on the current Hub API.

## Revisit when

Re-open this mapping if one of the following appears:

- the Hub restores anonymous schedule, standings and game reads on its web API;
- a static browser credential is published in the public page or JavaScript, remains
  stable, works without a token exchange, and satisfies the repository's public-key
  exception;
- Swiss unihockey publishes a registration-free JSON feed or Web Component data source.

Until then, do not add a `swiss-prime-league` provider or advertise any core capability.

## Changelog

| Date | Change |
|---|---|
| 2026-09-21 | Audited the Hub SPA, current Azure API, legacy-host routes, official Android app 1.5.2.4 and federation API offering. Recorded the men's Prime League as out of scope because the required data cannot be reached without a private app or member credential. |
