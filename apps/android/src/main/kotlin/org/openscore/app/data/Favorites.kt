package org.openscore.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.openscore.clubs.Clubs
import org.openscore.model.Game
import org.openscore.model.Sport
import org.openscore.model.TeamRef

/**
 * Something the user follows. Teams are keyed on the core's club id where the crosswalk knows
 * the club, so following Bayern in the Bundesliga also follows them in the Champions League;
 * otherwise on `league/teamId`.
 */
@Serializable
sealed class Favorite {
    abstract val key: String
    abstract val label: String
    abstract val sport: Sport

    @Serializable
    @SerialName("team")
    data class Team(
        val clubId: String?,
        val leagueId: String,
        val teamId: String,
        val name: String,
        override val sport: Sport,
    ) : Favorite() {
        override val key: String get() = clubId ?: "$leagueId/$teamId"
        override val label: String get() = name
    }

    @Serializable
    @SerialName("league")
    data class League(
        val leagueId: String,
        val name: String,
        override val sport: Sport,
    ) : Favorite() {
        override val key: String get() = "league:$leagueId"
        override val label: String get() = name
    }

    companion object {
        fun team(ref: TeamRef, sport: Sport): Team = Team(ref.clubId, ref.leagueId, ref.id, ref.name, sport)
        fun league(league: org.openscore.model.League): League = League(league.id, league.name, league.sport)
    }
}

/** The team key a [Favorite.Team] would have for this side, so a game can be matched without building one. */
fun TeamRef.favoriteKey(): String = clubId ?: "$leagueId/$id"

/** Persisted in SharedPreferences as one JSON list; small enough that a whole rewrite per change is fine. */
class FavoritesStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("favorites", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }
    private val serializer = ListSerializer(Favorite.serializer())

    private val _favorites = MutableStateFlow(load())
    val favorites: StateFlow<Set<Favorite>> = _favorites

    fun has(favorite: Favorite): Boolean = _favorites.value.any { it.key == favorite.key }

    fun toggle(favorite: Favorite) {
        val current = _favorites.value
        val next = if (current.any { it.key == favorite.key }) current.filterNot { it.key == favorite.key }.toSet() else current + favorite
        save(next)
    }

    fun remove(favorite: Favorite) = save(_favorites.value.filterNot { it.key == favorite.key }.toSet())

    private fun save(next: Set<Favorite>) {
        _favorites.value = next
        prefs.edit().putString(KEY, json.encodeToString(serializer, next.toList())).apply()
    }

    private fun load(): Set<Favorite> {
        val raw = prefs.getString(KEY, null) ?: return emptySet()
        return runCatching { json.decodeFromString(serializer, raw).toSet() }.getOrDefault(emptySet())
    }

    private companion object {
        const val KEY = "list"
    }
}

/** What a set of favourites means for fetching and filtering. */
class FavoriteFilter(favorites: Set<Favorite>) {
    private val teamKeys: Set<String> = favorites.filterIsInstance<Favorite.Team>().map { it.key }.toSet()
    private val leagueIds: Set<String> = favorites.filterIsInstance<Favorite.League>().map { it.leagueId }.toSet()

    /** Namespaces (see [Clubs.namespace]) that a followed club has an id in, plus the league it was followed from. */
    private val teamNamespaces: Set<String> = favorites.filterIsInstance<Favorite.Team>().flatMap { f ->
        val club = f.clubId?.let(Clubs::byId)
        (club?.ids?.keys ?: emptySet()) + Clubs.namespace(f.leagueId)
    }.toSet()

    val isEmpty: Boolean get() = teamKeys.isEmpty() && leagueIds.isEmpty()

    /** Leagues that can hold a followed game; only these are asked for a day. */
    fun leaguesToFetch(all: Collection<org.openscore.model.League>): List<String> =
        all.filter { it.id in leagueIds || Clubs.namespace(it.id) in teamNamespaces }.map { it.id }

    fun matches(game: Game): Boolean =
        game.leagueId in leagueIds || game.home.favoriteKey() in teamKeys || game.away.favoriteKey() in teamKeys

    /** A racing series has no teams to follow; its sessions are shown when the league itself is. */
    fun followsLeague(leagueId: String): Boolean = leagueId in leagueIds
}
