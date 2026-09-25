package org.openscore.app

import android.app.Application
import android.content.Context
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Cache
import java.io.File
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.svg.SvgDecoder
import okio.Path.Companion.toPath
import org.openscore.OpenScore
import org.openscore.app.alerts.AlertsStore
import org.openscore.app.data.FavoritesStore
import org.openscore.app.data.RoomScoresCache
import org.openscore.app.data.ScoresRepository
import org.openscore.app.data.SettingsStore
import org.openscore.net.KtorFetcher
import org.openscore.net.FetchMetrics

/**
 * Process-wide singletons. One [KtorFetcher] for the whole app is what makes the core's
 * politeness rules hold: its per-URL cache is what stops two screens asking the same league
 * for the same day twice.
 */
class OpenScoreApp : Application(), SingletonImageLoader.Factory {

    /** Per-host counters for cache effectiveness, bytes decoded and upstream rate limits. */
    val fetchMetrics: FetchMetrics = FetchMetrics()

    /**
     * The in-process fetcher remains the source of politeness, while OkHttp preserves HTTP
     * validators and cacheable bodies across Android process death. This is a direct client
     * cache: no OpenScore server sits between the app and a league API.
     */
    val fetcher: KtorFetcher by lazy {
        val engine = OkHttp.create {
            config { cache(Cache(File(cacheDir, "openscore-http"), HTTP_CACHE_BYTES)) }
        }
        KtorFetcher(engine = engine, userAgent = USER_AGENT, observer = fetchMetrics)
    }
    /** Durable, normalized: the two season snapshots (HockeyAllsvenskan, the UFC's cards) and every league's day listings as last read. */
    val scoresCache: RoomScoresCache by lazy { RoomScoresCache.create(this) }
    val openScore: OpenScore by lazy { OpenScore.default(fetcher, seasonScheduleStore = scoresCache, dayListingStore = scoresCache) }
    val repository: ScoresRepository by lazy { ScoresRepository(openScore, scoresCache) }
    val favorites: FavoritesStore by lazy { FavoritesStore(this) }
    val settings: SettingsStore by lazy { SettingsStore(this) }
    val alerts: AlertsStore by lazy { AlertsStore(this) }

    /**
     * Images are fetched only when first displayed. Coil then keeps the original response in a
     * bounded on-disk LRU cache so team crests from every league remain available across process
     * restarts (and usually offline) without prefetching a league's full logo set.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("openscore-images").absolutePath.toPath())
                    .maxSizeBytes(IMAGE_CACHE_BYTES)
                    .build()
            }
            .components { add(SvgDecoder.Factory()) }
            .build()

    companion object {
        const val USER_AGENT = "OpenScore-Android/0.1 (+https://github.com/icebergvibe/OpenScore)"
        private const val HTTP_CACHE_BYTES: Long = 32L * 1024 * 1024
        private const val IMAGE_CACHE_BYTES: Long = 32L * 1024 * 1024

        fun from(context: Context): OpenScoreApp = context.applicationContext as OpenScoreApp
    }
}
