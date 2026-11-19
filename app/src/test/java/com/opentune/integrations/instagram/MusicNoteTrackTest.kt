package com.opentune.integrations.instagram

import com.opentune.data.model.Song
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MusicNoteTrackTest {
    private val first = Song("first", "  Şarkı ", " Sanatçı ", null)
    private val second = Song("second", "A Different Song", "Another Artist", null)
    private val third = Song("third", "Third Song", "Artist", null)

    @Test fun searchTextPreservesNamesAndTrimsSurroundingWhitespace() {
        assertEquals("Şarkı Sanatçı", musicNoteTrack(first, sounding = true, connected = true)?.searchText)
    }

    @Test fun requiresSoundingConnectedPlaybackAndUsableMetadata() {
        assertNull(musicNoteTrack(null, sounding = true, connected = true))
        assertNull(musicNoteTrack(first, sounding = false, connected = true))
        assertNull(musicNoteTrack(first, sounding = true, connected = false))
        assertNull(musicNoteTrack(first.copy(title = " "), sounding = true, connected = true))
        assertNull(musicNoteTrack(first.copy(artist = " "), sounding = true, connected = true))
    }

    @Test fun rapidSkipsClearOldTrackAndOnlySuggestTheStableTrack() = runTest {
        val songs = MutableStateFlow<Song?>(first)
        val updates = mutableListOf<String?>()
        backgroundScope.launch {
            musicNoteTracks(songs, MutableStateFlow(true), MutableStateFlow(true)).collect { updates += it?.id }
        }
        runCurrent()
        advanceTimeBy(MUSIC_NOTE_SETTLE_MS)
        runCurrent()
        assertEquals(listOf(null, "first"), updates)

        songs.value = second
        runCurrent()
        assertEquals(listOf(null, "first", null), updates)
        advanceTimeBy(MUSIC_NOTE_SETTLE_MS - 1)
        songs.value = third
        runCurrent()
        advanceTimeBy(MUSIC_NOTE_SETTLE_MS)
        runCurrent()
        assertEquals(listOf(null, "first", null, "third"), updates)
    }

    @Test fun pauseAndDisconnectClearSuggestionAndResumeNeedsAStableTrack() = runTest {
        val sounding = MutableStateFlow(false)
        val connected = MutableStateFlow(true)
        val updates = mutableListOf<String?>()
        backgroundScope.launch {
            musicNoteTracks(MutableStateFlow(first), sounding, connected).collect { updates += it?.id }
        }
        runCurrent()
        advanceTimeBy(MUSIC_NOTE_SETTLE_MS)
        assertEquals(listOf<String?>(null), updates)

        sounding.value = true
        runCurrent()
        advanceTimeBy(MUSIC_NOTE_SETTLE_MS)
        runCurrent()
        assertEquals(listOf(null, "first"), updates)
        sounding.value = false
        runCurrent()
        assertEquals(listOf(null, "first", null), updates)

        sounding.value = true
        runCurrent()
        advanceTimeBy(MUSIC_NOTE_SETTLE_MS)
        runCurrent()
        connected.value = false
        runCurrent()
        assertEquals(listOf(null, "first", null, "first", null), updates)
    }

    @Test fun artworkChangesKeepTheSuggestionAndTrackIdDistinguishesRecordings() = runTest {
        val songs = MutableStateFlow(first)
        val updates = mutableListOf<String?>()
        backgroundScope.launch {
            musicNoteTracks(songs, MutableStateFlow(true), MutableStateFlow(true)).collect { updates += it?.id }
        }
        runCurrent()
        advanceTimeBy(MUSIC_NOTE_SETTLE_MS)
        runCurrent()

        songs.value = first.copy(thumbnailUrl = "cover")
        runCurrent()
        assertEquals(listOf(null, "first"), updates)

        songs.value = first.copy(videoId = "alternate-recording")
        runCurrent()
        advanceTimeBy(MUSIC_NOTE_SETTLE_MS)
        runCurrent()
        assertEquals(listOf(null, "first", null, "alternate-recording"), updates)
    }
}
