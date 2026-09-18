package org.openscore.app.alerts

import android.content.Intent

/**
 * The game a notification was about, carried on the intent that opens the app so the tap lands
 * on the match sheet rather than wherever the app was last left. Three fields, because that is
 * what reopening it costs: the league and id the provider's `game()` needs, and the league's
 * day for the listing fallback when a provider has no `game()`.
 */
data class GameLink(val leagueId: String, val gameId: String, val date: String) {

    fun putInto(intent: Intent): Intent = intent
        .putExtra(EXTRA_LEAGUE, leagueId)
        .putExtra(EXTRA_GAME, gameId)
        .putExtra(EXTRA_DATE, date)

    companion object {
        private const val EXTRA_LEAGUE = "org.openscore.app.extra.LEAGUE"
        private const val EXTRA_GAME = "org.openscore.app.extra.GAME"
        private const val EXTRA_DATE = "org.openscore.app.extra.DATE"

        fun of(alert: PendingAlert): GameLink = GameLink(alert.leagueId, alert.gameId, alert.date)

        /** Null for a plain launcher start, and for anything else that carries no game. */
        fun from(intent: Intent?): GameLink? {
            val leagueId = intent?.getStringExtra(EXTRA_LEAGUE) ?: return null
            val gameId = intent.getStringExtra(EXTRA_GAME) ?: return null
            val date = intent.getStringExtra(EXTRA_DATE) ?: return null
            return GameLink(leagueId, gameId, date)
        }

        /**
         * Strips the game once it has been acted on. The intent outlives the tap — Android hands
         * the same one back after a rotation — so a link left on it reopens the sheet over
         * whatever the reader had moved on to.
         */
        fun clear(intent: Intent) {
            intent.removeExtra(EXTRA_LEAGUE)
            intent.removeExtra(EXTRA_GAME)
            intent.removeExtra(EXTRA_DATE)
        }
    }
}
