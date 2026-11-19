package com.opentune.data.history

import com.opentune.data.model.Song
import com.opentune.data.spotify.PlaylistImport
import com.opentune.data.spotify.Spotify
import java.util.LinkedHashMap

/** Spotify history identifies a recording, not a playable YouTube stream. */
internal object ImportedHistoryTracks {
    private val matches = object : LinkedHashMap<String, Song>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>?) = size > 256
    }

    suspend fun resolve(song: Song): Song? {
        val key = "${song.videoId}|${song.title}|${song.artist}"
        synchronized(matches) { matches[key] }?.let { return it }
        val track = Spotify.Track(song.title, listOf(song.artist), song.albumName, 0)
        val found = PlaylistImport.match(track) ?: return null
        synchronized(matches) { matches[key] = found }
        return found
    }
}
