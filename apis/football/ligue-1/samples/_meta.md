# Sample provenance

All samples captured **2026-09-11 18:26–20:38 UTC** (static ones 18:26–18:46, the
Rennes–OM live series polled every 20 s from 18:33 to 20:42) with `curl -A "OpenScore/0.1"` and no other headers
(none are required), pretty-printed with `json.dump(indent=2, ensure_ascii=False)`.
The exact URL for each is in the README next to the sample link. Base URL:
`https://ma-api.ligue1.fr`, championship `1` (Ligue 1), season `2026` (2026/27).

Matches used:

| matchId | Match | State | Used by |
|---|---|---|---|
| `l1_championship_match_73855` | Stade Rennais v Olympique de Marseille, GW 4, 2026-09-11 18:45Z | `preMatchWithPlayers` (18:33:58Z) → `firstHalf` (18:45:38Z; kick-off stamped 18:45:13Z) → `halfTime` (19:31:59Z; stamped 19:31:21Z) → `secondHalf` (19:55:21Z, 1-0; goal stamped 19:54:31Z) → `fullTime` (20:37:39Z; stamped 20:37:12Z). Final score Rennes 1–0 OM | `championship-match.pre-lineups.json`, `championship-match.live-kickoff.json`, `championship-match.live-halftime.json`, `championship-match.live.json`, `championship-match.final-fresh.json`, `championship-matches-current.{pre,live,final}.json`, `championship-match-pre-live-stats.json` |
| `l1_championship_match_73845` | PSG 1–2 AS Monaco, GW 3, 2026-09-04 19:05Z | `fullTime` | `championship-match.final.json`, `-oppositions`, `-story-man-of-the-match`, `videos-match.json` |
| `l1_championship_match_74174` | Ligue 2 (championship `4`) GW 6 match, 2026-09-12 | `preMatch` (line-ups not yet published) | `championship-match.pre.json` |

Club samples use PSG (`l1_championship_club_2026_13`) except `championship-club.json`
(Rennes, `l1_championship_club_2026_14`); player samples use Lucas Chevalier
(`l1_championship_player_2026_13_77349`). `championship-matches-current.ucl.json` is
championship `6` (UEFA Champions League, matchday 1).

## Truncations

Arrays only (or map entries where noted); objects are intact.

| File | Truncation |
|---|---|
| `championship-clubs.json` | `championshipsClubs` map reduced to 3 of 155 entries (PSG, Rennes, one UEFA club); full response 1.1 MB |
| `championship-players-pool.json` | `players[]` first 5 of 524 (full response 1.6 MB) |
| `championship-players-ranking-scorers.json` | `scorers[]` first 5 of 63 |
| `championship-players-ranking-assists.json` | `assists[]` first 5 of 50 |
| `championship-players-advanced-rankings-top.json` | every `rankings.*[]` first 5; `playersData` map reduced to the players still referenced (full response 531 KB) |
| `championship-match-oppositions.json` | `oppositionsMatches[]` first 5 of 100 |

Everything else is complete as returned.
