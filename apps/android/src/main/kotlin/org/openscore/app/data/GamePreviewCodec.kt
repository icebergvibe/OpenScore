package org.openscore.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.openscore.model.GamePreview
import org.openscore.model.PlayerRef

/**
 * [GamePreview] as one nullable text column, JSON because its two sides are whole player refs.
 * A scheduled MLB card shows the probable pitchers from it, so a stored day without it would be
 * a poorer card than the one first read. A value that does not parse is dropped rather than
 * failing the row, as [PeriodScoresCodec] does with a segment.
 */
object GamePreviewCodec {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(val label: String, val home: StoredPlayer? = null, val away: StoredPlayer? = null)

    @Serializable
    private data class StoredPlayer(
        val leagueId: String,
        val id: String,
        val name: String,
        val jerseyNumber: Int? = null,
        val position: String? = null,
        val headshotUrl: String? = null,
    )

    fun encode(preview: GamePreview?): String? =
        preview?.let { json.encodeToString(Stored.serializer(), Stored(it.label, it.home?.stored(), it.away?.stored())) }

    fun decode(text: String?): GamePreview? {
        if (text.isNullOrEmpty()) return null
        val stored = runCatching { json.decodeFromString(Stored.serializer(), text) }.getOrNull() ?: return null
        return GamePreview(stored.label, stored.home?.ref(), stored.away?.ref())
    }

    private fun PlayerRef.stored() = StoredPlayer(leagueId, id, name, jerseyNumber, position, headshotUrl)

    private fun StoredPlayer.ref() = PlayerRef(leagueId, id, name, jerseyNumber, position, headshotUrl)
}
