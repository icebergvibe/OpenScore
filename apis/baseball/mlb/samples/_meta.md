# Sample provenance

All samples captured **2026-09-11 ~13:00 UTC** with
`curl -H "User-Agent: OpenScore/0.1" -H "Accept: application/json"`, pretty-printed
with `python3 -m json.tool --indent 2 --no-ensure-ascii`. The exact URL for each is
in the README next to the sample link.

Games used:

| gamePk | Game | State | Used by |
|---|---|---|---|
| 823498 | NYM @ NYY, 2026-09-11 23:05Z | `S` Scheduled (captured ~10 h before first pitch) | `*.pre.json` |
| 823088 | TEX @ SEA, 2026-09-10 20:10Z | `F` Final, 9 innings, SEA 4–3 | `*.final.json`, `feed-live-timestamps`, `feed-live-diffPatch` (startTimecode `20260910_223956`), `contextMetrics` |
| 823175 | 2026-09-07, 11 innings | `F` Final | `linescore.final-extra.json` |
| 824134 | 2026-04-03 → postponed (rain) → makeup 2026-04-04 as game 2 of a split DH | `DR` then `F` | `schedule.postponed.json`, `schedule.doubleheader.json` |

Team/player samples use the New York Yankees (147) and Aaron Judge (592450).

## Truncations

Per CONTRIBUTING, arrays in oversized responses were shortened; objects were not
edited except where noted.

- `feed-live.final.json` — `liveData.plays.allPlays` cut to the first **8 of 67**
  plays, `playsByInning` to the first 2 innings. **Additionally** the two player maps
  (`gameData.players`, `liveData.boxscore.teams.*.players`) were reduced to the 12
  players referenced by the kept plays and `decisions`. Every kept entry is complete.
  The full, untrimmed boxscore for the same game is `boxscore.final.json`.
- `feed-live.pre.json` — the same two player maps reduced to 10 players (both
  probable pitchers + first two `bench` and `bullpen` ids per team). Nothing else
  trimmed.
- `playByPlay.final.json` — `allPlays` cut to 8, `playsByInning` to 2 (same as above).
- `people-stats.gameLog.json` — `stats[0].splits` cut to the **last 5** of 61 games.

Everything else is complete as returned.

## 2026-09-12 additions (~15:55–16:10 UTC)

Captured with `urllib` and the same headers, `json.dump(indent=2, ensure_ascii=False)`.

| Sample | URL | Notes |
|---|---|---|
| `feed-live.live.json` | `/v1.1/game/823088/feed/live?timecode=20260910_220000` | `I` In Progress, bottom 7, TEX 3–0, runner on 2nd, 1–2 count. `allPlays` cut to the **last 3 of 48** (45 `other_out`, 46 `hit_by_pitch` with the `pitching_substitution` action, 47 in progress), `playsByInning` to the last inning; player maps reduced to the people referenced by those plays, the linescore, probables and the first two batting-order / first bench / first bullpen ids per side (21 people) |
| `feed-live.pre-game.json` | `/v1.1/game/824224/feed/live` | COL @ DET 2026-09-12 17:10Z, captured ~75 min before first pitch: `P` Pre-Game, batting orders posted, one `game_advisory` play. Same player-map trimming (28 people) |
| `linescore.live.json` | `/v1/game/823088/linescore?timecode=20260910_220000` | complete |
| `linescore.live-break.json` | `/v1/game/823088/linescore?timecode=20260910_214500` | `inningState: "End"`, inning 6, 3 outs; complete |
| `schedule.hydrated.pre-game.json` | `/v1/schedule?sportId=1&date=2026-09-12&hydrate=team,linescore,probablePitcher,decisions` | 15 games, four `P` and eleven `S`; complete |
| `standings.hydrated.json` | `/v1/standings?leagueId=103,104&hydrate=team,division` | complete |
| `playByPlay.final.fields.json` | `/v1/game/823088/playByPlay?fields=allPlays,result,type,event,eventType,description,rbi,awayScore,homeScore,isOut,about,atBatIndex,halfInning,isTopInning,inning,isComplete,isScoringPlay,hasOut,count,balls,strikes,outs,matchup,batter,pitcher,id,fullName,postOnFirst,postOnSecond,postOnThird,runners,movement,originBase,start,end,outBase,isOut,outNumber,details,runner,isScoringEvent,movementReason,playEvents,index,player,position,abbreviation,hitData,launchSpeed,launchAngle,totalDistance,trajectory,coordinates,coordX,coordY,scoringPlays,playsByInning,startIndex,endIndex,top,bottom` | all 67 plays, nothing truncated — the `fields=` whitelist itself drops pitch tracking and timestamps |

## Team-page captures — 2026-09-14

Sent `User-Agent: OpenScore/0.1 (team-page verification)`; no credentials or cookies.

- `team-stats.season.json`: `GET https://statsapi.mlb.com/api/v1/teams/147/stats?stats=season&group=hitting,pitching&season=2026`, complete response.
- `schedule.team-season.json`: `GET https://statsapi.mlb.com/api/v1/schedule?sportId=1&teamId=147&startDate=2026-01-01&endDate=2026-12-31&hydrate=team,linescore,probablePitcher,decisions`. Original response: 1,242,882 bytes, 193 dates. Only `dates[]` was truncated, retaining September 10–15 (six dates); objects and top-level totals are unchanged.
