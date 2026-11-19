package com.opentune.integrations.instagram

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opt-in music-note sync policy. No Instagram credentials or private endpoints are embedded.
 * The publisher must be supplied by a separately verified, user-authorized integration.
 * Never call this on the playback/UI thread.
 */
data class MusicNoteTrack(
    val title: String,
    val artist: String,
    val isrc: String? = null,
)

sealed interface PublishResult {
    data object Published : PublishResult
    data object Unsupported : PublishResult
    data class Failed(val retryable: Boolean) : PublishResult
}

fun interface MusicNotePublisher {
    suspend fun publish(track: MusicNoteTrack): PublishResult
}

/**
 * Defaults to disabled and rate-limited. A minimum interval is NOT a guarantee
 * against Instagram account restrictions.
 */
class MusicNoteSyncCoordinator(
    private val publisher: MusicNotePublisher,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val minimumIntervalMillis: Long = 15 * 60 * 1000L,
) {
    private val mutex = Mutex()
    var enabled: Boolean = false
    private set
    private var lastPublishedKey: String? = null
    private var lastAttemptAt: Long? = null

    fun setEnabled(value: Boolean) {
        enabled = value
    }

    suspend fun onTrackChanged(track: MusicNoteTrack): PublishResult? = mutex.withLock {
        if (!enabled) return@withLock null
        val title = track.title.trim()
        val artist = track.artist.trim()
        if (title.isBlank() || artist.isBlank()) return@withLock null
        val key = "${title.lowercase()}|${artist.lowercase()}|${track.isrc.orEmpty()}"
        if (key == lastPublishedKey) return@withLock null
        val now = nowMillis()
        if (lastAttemptAt?.let { now - it < minimumIntervalMillis } == true) return@withLock null
        lastAttemptAt = now
        try {
            val result = publisher.publish(track.copy(title = title, artist = artist))
            if (result == PublishResult.Published) lastPublishedKey = key
            result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            PublishResult.Failed(retryable = true)
        }
    }
}
