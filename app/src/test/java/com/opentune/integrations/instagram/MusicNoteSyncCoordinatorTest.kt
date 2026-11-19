package com.opentune.integrations.instagram

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicNoteSyncCoordinatorTest {
    private val song = MusicNoteTrack("Blind", "Korn")

    @Test fun disabledByDefaultDoesNotPublish() = runBlocking {
        var calls = 0
        val coordinator = MusicNoteSyncCoordinator(MusicNotePublisher {
            calls++
            PublishResult.Published
        })
        assertNull(coordinator.onTrackChanged(song))
        assertEquals(0, calls)
    }

    @Test fun duplicateTrackDoesNotPublishTwice() = runBlocking {
        var calls = 0
        val coordinator = MusicNoteSyncCoordinator(
            publisher = MusicNotePublisher {
                calls++
                PublishResult.Published
            },
            nowMillis = { 1_000_000L },
        )
        coordinator.setEnabled(true)
        assertEquals(PublishResult.Published, coordinator.onTrackChanged(song))
        assertNull(coordinator.onTrackChanged(song))
        assertEquals(1, calls)
    }

    @Test fun newTrackIsThrottled() = runBlocking {
        var calls = 0
        var clock = 1_000_000L
        val coordinator = MusicNoteSyncCoordinator(
            publisher = MusicNotePublisher {
                calls++
                PublishResult.Published
            },
            nowMillis = { clock },
            minimumIntervalMillis = 900_000L,
        )
        coordinator.setEnabled(true)
        assertEquals(PublishResult.Published, coordinator.onTrackChanged(song))
        clock += 1000L
        assertNull(coordinator.onTrackChanged(MusicNoteTrack("Freak on a Leash", "Korn")))
        assertEquals(1, calls)
        clock += 900_000L
        assertEquals(PublishResult.Published, coordinator.onTrackChanged(MusicNoteTrack("Freak on a Leash", "Korn")))
        assertEquals(2, calls)
    }

    @Test fun blankMetadataDoesNotPublish() = runBlocking {
        var calls = 0
        val coordinator = MusicNoteSyncCoordinator(MusicNotePublisher {
            calls++
            PublishResult.Published
        })
        coordinator.setEnabled(true)
        assertNull(coordinator.onTrackChanged(MusicNoteTrack(" ", "Korn")))
        assertEquals(0, calls)
    }
}
