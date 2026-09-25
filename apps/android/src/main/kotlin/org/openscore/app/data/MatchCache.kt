package org.openscore.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
import kotlinx.datetime.LocalDate
import org.openscore.cache.DayListing
import org.openscore.cache.DayListingStore
import org.openscore.cache.SeasonScheduleStore
import org.openscore.cache.SeasonSnapshot
import org.openscore.model.Game
import org.openscore.model.GameEnding
import org.openscore.model.GameState
import org.openscore.model.Score
import org.openscore.model.StageKind
import org.openscore.model.TeamRef
import org.openscore.providers.sportality.HockeyAllsvenskanProvider
import org.openscore.providers.ufc.UfcProvider
import kotlin.time.Instant

/**
 * What the settings screen can see of the local score storage. Room holds two normalized
 * things and nothing else — never a response body, never match detail (events, lineups, stats):
 *
 * - the complete season of each league without a day route of its own ([SeasonScheduleStore]):
 *   HockeyAllsvenskan's schedule and the UFC's known cards. Each provider re-imports its own and
 *   re-reads the games that can change; one league's snapshot never displaces another's;
 * - every other league's day listings as last read ([DayListingStore]); the core's
 *   `CachedDayListingProvider` decides from a day's games whether it can be served or must be
 *   read again, and a stored day is only ever served whole.
 *
 * Both are stamped so a build with a fixed mapper never shows what an older one wrote: day
 * rows carry the installed build and are dropped when it changes.
 */
interface ScoresCache {
    suspend fun sizeBytes(): Long
    suspend fun clear()
}

object NoopScoresCache : ScoresCache {
    override suspend fun sizeBytes(): Long = 0
    override suspend fun clear() = Unit
}

/**
 * Everything a scorecard and a match header draw from a [Game] that a listing can carry: a
 * final's period scores and how it was decided included, so a restored result is never a
 * poorer record than the one that was fetched. Clock, events, lineups and stats are live detail
 * and stay out. Embedded in both tables so the two never drift apart.
 */
data class GameRecord(
    val leagueId: String,
    val id: String,
    val seasonId: String?,
    val stage: String?,
    val competition: String?,
    val venue: String?,
    val startTimeEpochMs: Long,
    val startTimeTbd: Boolean,
    val scheduleDate: String?,
    val state: String,
    val homeScore: Int?,
    val awayScore: Int?,
    /** [PeriodScoresCodec] form of [Game.periodScores]. */
    val periodScores: String,
    val ending: String?,
    val rawState: String?,
    val homeId: String,
    val homeName: String,
    val homeAbbreviation: String?,
    val homeLogoUrl: String?,
    val homeClubId: String?,
    val awayId: String,
    val awayName: String,
    val awayAbbreviation: String?,
    val awayLogoUrl: String?,
    val awayClubId: String?,
)

/** One game of a stored season. */
@Entity(tableName = "cached_games", primaryKeys = ["leagueId", "id"])
data class CachedGame(@Embedded val game: GameRecord)

/** One game of a stored day listing; [date] is the league's own calendar date the listing was asked for. */
@Entity(tableName = "cached_day_games", primaryKeys = ["leagueId", "date", "id"])
data class CachedDayGame(val date: String, @Embedded val game: GameRecord)

/**
 * Marker for one stored day listing, written in the same transaction as its games. [build] is
 * the installed APK's update time: a new build disowns every day an older mapper wrote.
 */
@Entity(tableName = "cached_days", primaryKeys = ["leagueId", "date"])
data class CachedDay(
    val leagueId: String,
    val date: String,
    val fetchedAtEpochMs: Long,
    val build: Long,
)

/** Completion marker written atomically after every game in a season has been normalized. */
@Entity(tableName = "cached_seasons", primaryKeys = ["leagueId", "seasonId"])
data class CachedSeason(
    val leagueId: String,
    val seasonId: String,
    val savedAtEpochMs: Long,
)

@Dao
interface CachedGameDao {
    @Query("SELECT * FROM cached_games WHERE leagueId = :leagueId AND seasonId = :seasonId ORDER BY startTimeEpochMs, id")
    suspend fun seasonGames(leagueId: String, seasonId: String): List<CachedGame>

    @Query("SELECT * FROM cached_seasons WHERE leagueId = :leagueId AND seasonId = :seasonId")
    suspend fun season(leagueId: String, seasonId: String): CachedSeason?

    /** The one season stored for a league; [RoomScoresCache.save] keeps each league to one. */
    @Query("SELECT * FROM cached_seasons WHERE leagueId = :leagueId ORDER BY savedAtEpochMs DESC LIMIT 1")
    suspend fun season(leagueId: String): CachedSeason?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSeason(season: CachedSeason)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(games: List<CachedGame>)

    @Query("SELECT * FROM cached_days WHERE leagueId = :leagueId AND date = :date")
    suspend fun day(leagueId: String, date: String): CachedDay?

    @Query("SELECT * FROM cached_day_games WHERE leagueId = :leagueId AND date = :date ORDER BY startTimeEpochMs, id")
    suspend fun dayGames(leagueId: String, date: String): List<CachedDayGame>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDay(day: CachedDay)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDayGames(games: List<CachedDayGame>)

    @Query("DELETE FROM cached_day_games WHERE leagueId = :leagueId AND date = :date")
    suspend fun deleteDayGames(leagueId: String, date: String)

    /** Days another build wrote, or nobody has looked at in a month, and then the games no day claims. */
    @Query("DELETE FROM cached_days WHERE build != :build OR fetchedAtEpochMs < :olderThanEpochMs")
    suspend fun pruneDays(build: Long, olderThanEpochMs: Long)

    @Query("DELETE FROM cached_day_games WHERE NOT EXISTS (SELECT 1 FROM cached_days d WHERE d.leagueId = cached_day_games.leagueId AND d.date = cached_day_games.date)")
    suspend fun pruneOrphanDayGames()

    @Query(
        "SELECT COALESCE(SUM(LENGTH(leagueId) + LENGTH(id) + LENGTH(homeName) + LENGTH(awayName) + LENGTH(periodScores) + IFNULL(LENGTH(competition), 0) + IFNULL(LENGTH(venue), 0) + IFNULL(LENGTH(homeLogoUrl), 0) + IFNULL(LENGTH(awayLogoUrl), 0) + 160), 0) FROM cached_games",
    )
    suspend fun seasonPayloadBytes(): Long

    @Query(
        "SELECT COALESCE(SUM(LENGTH(leagueId) + LENGTH(id) + LENGTH(date) + LENGTH(homeName) + LENGTH(awayName) + LENGTH(periodScores) + IFNULL(LENGTH(competition), 0) + IFNULL(LENGTH(venue), 0) + IFNULL(LENGTH(homeLogoUrl), 0) + IFNULL(LENGTH(awayLogoUrl), 0) + 160), 0) FROM cached_day_games",
    )
    suspend fun dayPayloadBytes(): Long

    @Query("DELETE FROM cached_games")
    suspend fun clear()

    @Query("DELETE FROM cached_seasons")
    suspend fun clearSeasons()

    /** One league's stored season only: the leagues with a snapshot must not evict each other. */
    @Query("DELETE FROM cached_games WHERE leagueId = :leagueId")
    suspend fun clear(leagueId: String)

    @Query("DELETE FROM cached_seasons WHERE leagueId = :leagueId")
    suspend fun clearSeasons(leagueId: String)

    @Query("DELETE FROM cached_days")
    suspend fun clearDays()

    @Query("DELETE FROM cached_day_games")
    suspend fun clearDayGames()
}

@Database(entities = [CachedGame::class, CachedSeason::class, CachedDay::class, CachedDayGame::class], version = 6, exportSchema = false)
abstract class OpenScoreCacheDatabase : RoomDatabase() {
    abstract fun games(): CachedGameDao
}

class RoomScoresCache private constructor(
    private val database: OpenScoreCacheDatabase,
    private val dao: CachedGameDao,
    /** The installed APK's update time; day rows from any other build are never served. */
    private val build: Long,
) : ScoresCache, SeasonScheduleStore, DayListingStore {
    /** Concurrent league saves race here; the worst a lost update costs is one extra prune. */
    @Volatile
    private var pruned = false

    override suspend fun load(leagueId: String): SeasonSnapshot? = database.withTransaction {
        if (leagueId !in SNAPSHOT_LEAGUES) return@withTransaction null
        val season = dao.season(leagueId) ?: return@withTransaction null
        SeasonSnapshot(
            season.seasonId,
            Instant.fromEpochMilliseconds(season.savedAtEpochMs),
            dao.seasonGames(leagueId, season.seasonId).map { it.game.toGame() },
        )
    }

    override suspend fun save(leagueId: String, snapshot: SeasonSnapshot) {
        require(leagueId in SNAPSHOT_LEAGUES) { "No season snapshot is persisted for '$leagueId'" }
        requireSeason(leagueId, snapshot.seasonId, snapshot.games)
        database.withTransaction {
            // The season payload is atomic and complete, so replace this league's stored season
            // whole - and only this league's, so the other snapshot league keeps its own.
            dao.clearSeasons(leagueId)
            dao.clear(leagueId)
            dao.upsert(snapshot.games.map { CachedGame(it.toRecord()) })
            dao.upsertSeason(CachedSeason(leagueId, snapshot.seasonId, snapshot.savedAt.toEpochMilliseconds()))
        }
    }

    override suspend fun update(leagueId: String, seasonId: String, games: List<Game>) {
        if (leagueId !in SNAPSHOT_LEAGUES || games.isEmpty()) return
        requireSeason(leagueId, seasonId, games)
        database.withTransaction {
            // Only a season that was imported whole may be amended; rows without a marker would
            // be exactly the partial schedule [load] promises never to hand back.
            if (dao.season(leagueId, seasonId) == null) return@withTransaction
            dao.upsert(games.map { CachedGame(it.toRecord()) })
        }
    }

    private fun requireSeason(leagueId: String, seasonId: String, games: List<Game>) {
        require(games.all { it.leagueId == leagueId && it.seasonId == seasonId }) {
            "Season snapshot contains a different league or season"
        }
    }

    override suspend fun load(leagueId: String, date: LocalDate): DayListing? = database.withTransaction {
        val day = dao.day(leagueId, date.toString())?.takeIf { it.build == build } ?: return@withTransaction null
        DayListing(leagueId, date, Instant.fromEpochMilliseconds(day.fetchedAtEpochMs), dao.dayGames(leagueId, date.toString()).map { it.game.toGame() })
    }

    override suspend fun save(listing: DayListing) {
        val date = listing.date.toString()
        database.withTransaction {
            if (!pruned) {
                // Once per process: what another build wrote, and days nobody has opened in a month.
                dao.pruneDays(build, System.currentTimeMillis() - DAY_RETENTION_MS)
                dao.pruneOrphanDayGames()
                pruned = true
            }
            // The games and their marker land together; a listing is only ever served whole.
            dao.deleteDayGames(listing.leagueId, date)
            dao.upsertDayGames(listing.games.map { CachedDayGame(date, it.toRecord()) })
            dao.upsertDay(CachedDay(listing.leagueId, date, listing.fetchedAt.toEpochMilliseconds(), build))
        }
    }

    override suspend fun sizeBytes(): Long = dao.seasonPayloadBytes() + dao.dayPayloadBytes()

    override suspend fun clear() {
        database.withTransaction {
            dao.clearSeasons()
            dao.clear()
            dao.clearDays()
            dao.clearDayGames()
        }
    }

    companion object {
        /**
         * The leagues with no day route upstream, whose providers are handed this store as their
         * [SeasonScheduleStore] by `OpenScore.default`. Taken from the providers rather than
         * spelled out here: a store that silently refused a league it was given would cost that
         * league its snapshot on every process start, and say nothing about it.
         */
        val SNAPSHOT_LEAGUES: Set<String> = setOf(HockeyAllsvenskanProvider.LEAGUE.id, UfcProvider.LEAGUE.id)

        /** Longer than the timeline's look-back, so a day scrolled to once a month stays free. */
        private const val DAY_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

        fun create(context: Context): RoomScoresCache {
            // A cache: an older schema is dropped and the data read again on first use rather
            // than migrated. Version 6 added the day listings and the shared game record.
            val db = Room.databaseBuilder(context.applicationContext, OpenScoreCacheDatabase::class.java, "openscore-read-cache")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            val build = context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
            return RoomScoresCache(db, db.games(), build)
        }
    }
}

private fun Game.toRecord() = GameRecord(
    leagueId = leagueId, id = id, seasonId = seasonId, stage = stage?.name,
    competition = competition, venue = venue, startTimeEpochMs = startTime.toEpochMilliseconds(),
    startTimeTbd = startTimeTbd, scheduleDate = scheduleDate?.toString(), state = state.name,
    homeScore = score?.home, awayScore = score?.away,
    periodScores = PeriodScoresCodec.encode(periodScores), ending = ending?.name, rawState = rawState,
    homeId = home.id, homeName = home.name, homeAbbreviation = home.abbreviation,
    homeLogoUrl = home.logoUrl, homeClubId = home.clubId,
    awayId = away.id, awayName = away.name, awayAbbreviation = away.abbreviation,
    awayLogoUrl = away.logoUrl, awayClubId = away.clubId,
)

private fun GameRecord.toGame() = Game(
    leagueId = leagueId, id = id, seasonId = seasonId,
    stage = stage?.let { runCatching { enumValueOf<StageKind>(it) }.getOrNull() },
    competition = competition, venue = venue,
    startTime = Instant.fromEpochMilliseconds(startTimeEpochMs), startTimeTbd = startTimeTbd,
    // Every field of a restored row is read defensively: this is a cache an older build may
    // have written, and one unreadable value must not fail the whole day it belongs to.
    scheduleDate = scheduleDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
    home = TeamRef(leagueId, homeId, homeName, homeAbbreviation, homeLogoUrl, homeClubId),
    away = TeamRef(leagueId, awayId, awayName, awayAbbreviation, awayLogoUrl, awayClubId),
    state = runCatching { enumValueOf<GameState>(state) }.getOrDefault(GameState.UNKNOWN),
    score = if (homeScore != null && awayScore != null) Score(homeScore, awayScore) else null,
    periodScores = PeriodScoresCodec.decode(periodScores),
    ending = ending?.let { runCatching { enumValueOf<GameEnding>(it) }.getOrNull() },
    rawState = rawState,
)
