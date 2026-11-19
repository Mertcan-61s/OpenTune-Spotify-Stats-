package com.opentune.integrations.instagram

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import androidx.media3.common.Player
import com.opentune.data.settings.AppSettings
import com.opentune.playback.toSong
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

internal fun bridgeAddress(input: String): HttpUrl? = input.trim().toHttpUrlOrNull()?.takeIf {
    (it.isHttps || it.host == "127.0.0.1" || it.host == "::1") &&
        it.username.isEmpty() && it.password.isEmpty() && it.encodedPath == "/" && it.query == null && it.fragment == null
}

internal data class NotePairing(val address: HttpUrl, val key: String) {
    private fun json() = JSONObject().put("address", address.toString()).put("key", key)

    fun save(context: Context) {
        val file = storage(context)
        val stream = file.startWrite()
        try {
            stream.write(json().toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(stream)
        } catch (e: Exception) {
            file.failWrite(stream)
            throw e
        }
    }

    suspend fun post(path: String, data: JSONObject): JSONObject {
        val request = Request.Builder().url(address.newBuilder().addPathSegment(path).build())
            .header("Authorization", "Bearer $key")
            .post(data.toString().toRequestBody("application/json".toMediaType())).build()
        return suspendCancellableCoroutine { continuation ->
            val call = http.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = continuation.resumeWithException(e)
                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!it.isSuccessful) throw IOException("Bridge HTTP ${it.code}")
                            continuation.resume(JSONObject(it.body!!.source().readUtf8LineStrict(4096)))
                        }
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            })
        }
    }

    companion object {
        private val http by lazy {
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
                .retryOnConnectionFailure(false).callTimeout(55, TimeUnit.SECONDS).build()
        }
        private fun storage(context: Context) = AtomicFile(File(context.noBackupFilesDir, "notes-pairing.json"))
        fun load(context: Context): NotePairing? {
            val file = storage(context)
            if (!file.baseFile.exists()) return null
            val data = JSONObject(file.readFully().toString(Charsets.UTF_8))
            return bridgeAddress(data.getString("address"))?.let { NotePairing(it, data.getString("key")) }
        }
    }
}

internal class NoteSchedule(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    private val send: suspend (MusicNoteTrack) -> Unit,
) {
    private var current: MusicNoteTrack? = null
    private var job: Job? = null
    private var nextAttempt = 0L

    fun update(track: MusicNoteTrack?) {
        if (current == track) return
        current = track
        job?.cancel()
        if (track == null) return
        job = scope.launch {
            delay(maxOf(10_000L, nextAttempt - now()))
            nextAttempt = now() + 125_000L
            send(track)
        }
    }
}

internal object AutoMusicNotes {
    val status = MutableStateFlow("Waiting for stable playback")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player = WeakReference<Player>(null)
    private var schedule: NoteSchedule? = null

    fun settingChanged(context: Context) {
        status.value = "Waiting for stable playback"
        changed(context, player.get())
    }

    fun changed(context: Context, active: Player?) {
        if (player.get() !== active) player = WeakReference(active)
        if (!AppSettings.ui.value.autoInstagramNotes || active == null || !active.isPlaying) {
            schedule?.update(null)
            return
        }
        val app = context.applicationContext
        val scheduler = schedule ?: NoteSchedule(scope, SystemClock::elapsedRealtime) { track ->
            try {
                val pairing = withContext(Dispatchers.IO) { NotePairing.load(app) }
                if (pairing == null) {
                    status.value = "Connect your Notes bridge"
                    return@NoteSchedule
                }
                val query = JSONObject().put("title", track.title).put("artist", track.artist)
                var match = pairing.post("resolve", query)
                if (match.optString("result") == "cooldown") {
                    delay(match.optLong("retry_after", 120).coerceIn(1, 125) * 1000L)
                    if (!AppSettings.ui.value.autoInstagramNotes) return@NoteSchedule
                    match = pairing.post("resolve", query)
                }
                val result = if (match.optString("result") == "matched") {
                    val p = player.get()
                    if (!AppSettings.ui.value.autoInstagramNotes || p == null ||
                        musicNoteTrack(p.currentMediaItem?.toSong(), p.isPlaying, true) != track) return@NoteSchedule
                    pairing.post("publish", JSONObject().put("ticket", match.getString("ticket")))
                } else match
                status.value = when (result.optString("result")) {
                    "recorded" -> "Instagram returned the music Note in your inbox"
                    "same" -> "This recording was already submitted"
                    "no_match" -> "No unique title and artist match"
                    "existing" -> "Your existing Instagram Note was kept"
                    "cooldown" -> "The bridge is limiting updates"
                    "unconfirmed" -> "Submission could not be confirmed. Check Instagram"
                    "blocked" -> "Bridge paused. Check the Instagram session before restarting"
                    else -> "Bridge could not process the track"
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                status.value = "Bridge unavailable or pairing rejected"
            }
        }.also { schedule = it }
        scheduler.update(musicNoteTrack(active.currentMediaItem?.toSong(), true, true))
    }
}
