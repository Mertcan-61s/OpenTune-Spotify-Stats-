package com.opentune.integrations.instagram

import com.opentune.data.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.transformLatest

internal data class MusicNoteTrack(val id: String, val title: String, val artist: String) {
    val searchText: String get() = "$title $artist"
}

internal fun musicNoteTrack(song: Song?, sounding: Boolean, connected: Boolean): MusicNoteTrack? {
    if (song == null || !sounding || !connected) return null
    val title = song.title.trim()
    val artist = song.artist.trim()
    if (title.isEmpty() || artist.isEmpty()) return null
    return MusicNoteTrack(song.videoId, title, artist)
}

internal const val MUSIC_NOTE_SETTLE_MS = 3_000L

/** Clear the old suggestion immediately, then wait for the next track to settle. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun musicNoteTracks(
    songs: Flow<Song?>,
    sounding: Flow<Boolean>,
    connected: Flow<Boolean>,
): Flow<MusicNoteTrack?> = combine(songs, sounding, connected, ::musicNoteTrack)
    .distinctUntilChanged()
    .transformLatest { track ->
        emit(null)
        if (track != null) {
            delay(MUSIC_NOTE_SETTLE_MS)
            emit(track)
        }
    }
    .distinctUntilChanged()
