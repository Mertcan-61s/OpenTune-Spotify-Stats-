package com.opentune.data.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class SpotifyHistoryParserTest {
    @Test fun extendedHistoryParsesTrackAndSkipsPodcast() {
        val input = """[
          {"ts":"2024-01-02T03:04:05Z","ms_played":120000,
           "master_metadata_track_name":"Blind",
           "master_metadata_album_artist_name":"Korn",
           "master_metadata_album_album_name":"Korn",
           "spotify_track_uri":"spotify:track:abc"},
          {"ts":"2024-01-02T03:04:05Z","ms_played":5000,
           "episode_name":"Podcast"}
        ]"""
        val result = SpotifyHistoryParser.parse(input)
        assertEquals(1, result.listens.size)
        assertEquals(1, result.skipped)
        assertEquals("Blind", result.listens.single().title)
        assertEquals(120000L, result.listens.single().listenedMs)
        assertEquals("spotify:track:abc", result.listens.single().spotifyUri)
    }

    @Test fun legacyStreamingHistoryParsesLocalTime() {
        val input = """[{"endTime":"2022-11-12 14:30","artistName":"Artist",
          "trackName":"Track","msPlayed":45000}]"""
        val result = SpotifyHistoryParser.parse(input, ZoneId.of("UTC"))
        assertEquals(1, result.listens.size)
        assertEquals(45000L, result.listens.single().listenedMs)
        assertTrue(result.listens.single().endedAtMs > 0)
    }

    @Test fun invalidRowsAreSkipped() {
        val result = SpotifyHistoryParser.parse("""[
          {"ts":"bad","ms_played":3000,"master_metadata_track_name":"A",
           "master_metadata_album_artist_name":"B"},
          {"ts":"2024-01-02T03:04:05Z","ms_played":0,
           "master_metadata_track_name":"A","master_metadata_album_artist_name":"B"}
        ]""")
        assertEquals(0, result.listens.size)
        assertEquals(2, result.skipped)
    }

    @Test fun readsMultipleZipEntriesWithoutClosingTheArchive() {
        val row = """[{"ts":"2024-01-02T03:04:05Z","ms_played":3000,"master_metadata_track_name":"Blind","master_metadata_album_artist_name":"Korn"}]"""
        val bytes = java.io.ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(bytes).use { zip ->
            listOf("endsong_0.json", "endsong_1.json").forEach { name ->
                zip.putNextEntry(java.util.zip.ZipEntry(name))
                zip.write(row.toByteArray())
                zip.closeEntry()
            }
        }
        java.util.zip.ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            assertTrue(zip.nextEntry != null)
            assertEquals(1, SpotifyHistoryParser.parse(zip).listens.size)
            zip.closeEntry()
            assertTrue(zip.nextEntry != null)
            assertEquals(1, SpotifyHistoryParser.parse(zip).listens.size)
        }
    }

    @Test fun malformedFieldDoesNotDiscardOtherRows() {
        val input = """[{"ts":"2024-01-02T03:04:05Z","ms_played":3,"master_metadata_track_name":{},"master_metadata_album_artist_name":"Korn"},
          {"ts":"2024-01-02T03:04:05Z","ms_played":3,"master_metadata_track_name":"Blind","master_metadata_album_artist_name":"Korn"}]"""
        val result = SpotifyHistoryParser.parse(input)
        assertEquals(1, result.listens.size)
        assertEquals(1, result.skipped)
    }
}
