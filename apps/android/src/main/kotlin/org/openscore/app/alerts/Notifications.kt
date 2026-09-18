package org.openscore.app.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.openscore.app.MainActivity
import org.openscore.app.R

/**
 * Plain Android notifications, posted by the app itself: there is no push service behind them
 * and nothing about what is followed leaves the phone. One channel per kind, so reminders,
 * results and in-play events can be silenced independently from Android's own settings.
 */
object Notifications {

    private val AlertKind.channelId: String
        get() = when (this) {
            AlertKind.KICKOFF -> "kickoff"
            AlertKind.STARTED -> "start"
            AlertKind.RESULT -> "results"
            AlertKind.LIVE -> "live"
            AlertKind.BREAK -> "break"
        }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        fun channel(kind: AlertKind, name: String, description: String, importance: Int = NotificationManager.IMPORTANCE_DEFAULT) {
            manager.createNotificationChannel(NotificationChannel(kind.channelId, name, importance).apply { this.description = description })
        }
        // Also carries the notice that a game has been called off: the reminder's own news, undone.
        channel(AlertKind.KICKOFF, "Pre-game reminders", "Before a followed game starts, or when one is called off")
        // Separate from the reminder rather than folded into it: one fires on the time the game
        // was supposed to start, the other on it actually starting, and a delayed match is
        // exactly when someone wants the second without the first having been any use.
        channel(AlertKind.STARTED, "Match start", "When a followed game gets under way")
        channel(AlertKind.RESULT, "Final results", "When a followed game finishes")
        // The one channel worth interrupting for: a goal read half an hour later is just the
        // score, which the results channel already gives you.
        channel(AlertKind.LIVE, "Goals and cards", "While a followed game is being played", NotificationManager.IMPORTANCE_HIGH)
        channel(AlertKind.BREAK, "Half time and breaks", "When a followed game pauses between periods")
    }

    /** False when the user has denied POST_NOTIFICATIONS or silenced the app entirely. */
    fun enabled(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun post(context: Context, post: Post, link: GameLink) {
        if (!enabled(context)) return
        ensureChannels(context)
        val id = post.id.hashCode()

        // The notification's own id doubles as the request code, and has to: PendingIntents are
        // matched on everything about the intent except its extras, so a shared request code
        // would have every alert in the shade open whichever game was notified last.
        val open = PendingIntent.getActivity(
            context,
            id,
            link.putInto(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, post.kind.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(post.title)
            .setContentText(post.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(post.text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            // A busy match posts several; grouping lets the system fold them into one entry per
            // game instead of a column of near-identical rows.
            .apply { if (post.grouped) setGroup("${link.leagueId}/${link.gameId}") }
            .build()

        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call; nothing to do.
        }
    }
}
