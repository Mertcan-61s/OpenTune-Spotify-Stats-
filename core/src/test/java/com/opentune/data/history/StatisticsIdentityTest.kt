package com.opentune.data.history

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class StatisticsIdentityTest {
    @Test fun spotifyAndLocalListensForTheSameSongAreCountedTogether() {
        val listens = listOf(
            PlayRecord("spotify:one", "Blind", "Korn", playedAt = 3000, listenedMs = 30_000),
            PlayRecord("youtube-one", " BLIND ", "korn", playedAt = 2000, listenedMs = 60_000),
            PlayRecord("youtube-two", "Blind", "Another artist", playedAt = 1000, listenedMs = 10_000),
        )
        val replay = History.replay(listens, 0)
        assertEquals(3, replay.totalPlays)
        assertEquals(2, replay.songCount)
        assertEquals(2, replay.topSongs.first().plays)
        assertEquals(90_000L, replay.topSongs.first().listenedMs)
        val wrapped = Wrapped.summarize(listens, 0, 4000, TimeZone.getTimeZone("UTC"))
        assertEquals(2, wrapped.songs)
        assertEquals(2, wrapped.topSongs.first().plays)
    }
}
