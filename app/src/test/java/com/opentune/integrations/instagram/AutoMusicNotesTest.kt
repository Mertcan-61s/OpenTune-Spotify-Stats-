package com.opentune.integrations.instagram

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutoMusicNotesTest {
    @Test fun pairingOnlyUsesTlsOrDeviceLoopback() {
        assertNotNull(bridgeAddress("https://notes.example"))
        assertNotNull(bridgeAddress("http://127.0.0.1:8765"))
        for (url in listOf("http://example.org", "https://bad@notes.example",
                "https://notes.example/path", "https://notes.example/?key=secret", "file:///tmp/key")) {
            assertNull(url, bridgeAddress(url))
        }
    }

    @Test fun skipsPausesAndCooldownKeepOnlyLatestStableTrack() = runTest {
        val submitted = mutableListOf<String>()
        val scheduler = NoteSchedule(backgroundScope, { testScheduler.currentTime }) { submitted += it.id }
        val song = MusicNoteTrack("a", "One", "Artist")
        scheduler.update(song)
        advanceTimeBy(9_000)
        scheduler.update(null)
        advanceTimeBy(20_000)
        assertTrue(submitted.isEmpty())
        scheduler.update(song)
        advanceTimeBy(10_001)
        scheduler.update(song.copy(id = "b"))
        advanceTimeBy(20_000)
        scheduler.update(song.copy(id = "c"))
        advanceTimeBy(120_001)
        assertEquals(listOf("a", "c"), submitted)
    }

    @Test fun stopOrChangeDuringLookupCancelsPublication() = runTest {
        val sent = mutableListOf<String>()
        val scheduler = NoteSchedule(backgroundScope, { testScheduler.currentTime }) {
            delay(3_000)
            sent += it.id
        }
        scheduler.update(MusicNoteTrack("a", "One", "Artist"))
        advanceTimeBy(11_000)
        scheduler.update(null)
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(sent.isEmpty())
    }
}
