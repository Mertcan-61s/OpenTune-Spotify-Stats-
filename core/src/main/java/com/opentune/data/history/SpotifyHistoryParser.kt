package com.opentune.data.history

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * Offline parser for Spotify Extended Streaming History (endsong_*.json)
 * and the older account-data StreamingHistory*.json format.
 *
 * Parsing does not write to OpenTune history. The caller must preview and
 * confirm the import, then persist records in a store without the 10k cap.
 */
object SpotifyHistoryParser {
    data class Listen(
        val endedAtMs: Long,
        val listenedMs: Long,
        val title: String,
        val artist: String,
        val album: String?,
        val spotifyUri: String?,
    )
    data class ParseResult(val listens: List<Listen>, val skipped: Int)

    private val json = Json { ignoreUnknownKeys = true }
    private val oldDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

    fun parse(contents: String, zone: ZoneId = ZoneId.systemDefault()): ParseResult {
        val root = json.parseToJsonElement(contents)
        require(root is JsonArray) { "Expected a Spotify history JSON array" }
        val listens = ArrayList<Listen>(root.size)
        var skipped = 0
        for (element in root) {
            val row = element as? JsonObject
            fun field(name: String): String? = row?.get(name)?.jsonPrimitive?.contentOrNull
            val title = field("master_metadata_track_name") ?: field("trackName")
            val artist = field("master_metadata_album_artist_name") ?: field("artistName")
            val duration = field("ms_played")?.toLongOrNull() ?: field("msPlayed")?.toLongOrNull()
            val endedAt = runCatching {
                field("ts")?.let { Instant.parse(it).toEpochMilli() }
                    ?: field("endTime")?.let {
                        LocalDateTime.parse(it, oldDate).atZone(zone).toInstant().toEpochMilli()
                    }
            }.getOrNull()
            if (title.isNullOrBlank() || artist.isNullOrBlank() || duration == null ||
                duration <= 0L || endedAt == null) {
                skipped++
                continue
            }
            listens += Listen(
                endedAtMs = endedAt,
                listenedMs = duration,
                title = title,
                artist = artist,
                album = field("master_metadata_album_album_name"),
                spotifyUri = field("spotify_track_uri"),
            )
        }
        return ParseResult(listens, skipped)
    }
}
