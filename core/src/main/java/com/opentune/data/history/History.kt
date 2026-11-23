package com.opentune.data.history

import android.content.Context
import com.opentune.data.DebugLog as Log
import com.opentune.data.model.Song
import com.opentune.data.model.durationMillis
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** One listen: a track that played long enough to count. */
@Serializable
data class PlayRecord(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val albumName: String? = null,
    val playedAt: Long,
    val listenedMs: Long = 0,
    val explicit: Boolean? = null,
    val artistId: String? = null,
    /** A listen made elsewhere on the account (YouTube Music, another app), brought in from its history. */
    val fromAccount: Boolean = false,
) {
    fun toSong() = Song(videoId, title, artist, thumbnailUrl, durationText, albumName = albumName, isExplicit = explicit, artistId = artistId)
}

data class TopEntry(val key: String, val title: String, val subtitle: String, val thumbnailUrl: String?, val plays: Int, val listenedMs: Long, val song: Song?)

/** Exact title and artist match joins the same song across Spotify and local playback. */
internal fun PlayRecord.statisticsSongKey(): String {
    val titleKey = title.trim().lowercase(java.util.Locale.ROOT)
    val artistKey = artist.trim().lowercase(java.util.Locale.ROOT)
    return if (titleKey.isEmpty() || artistKey.isEmpty()) "id:$videoId" else "name:$artistKey\u001f$titleKey"
}

data class ReplaySummary(
    val totalPlays: Int,
    val listenedMs: Long,
    /** How many different songs and artists were played; the top lists stop at 25. */
    val songCount: Int,
    val artistCount: Int,
    /** How many different albums, among the listens that name one. */
    val albumCount: Int = 0,
    val topSongs: List<TopEntry>,
    val topArtists: List<TopEntry>,
    val topAlbums: List<TopEntry>,
    /** Time listened in each hour of the day, midnight first, in the phone's time zone. */
    val byHour: List<Long> = List(24) { 0L },
    /** Time listened on each day of the week, Monday first. */
    val byWeekday: List<Long> = List(7) { 0L },
    /** Days with any listening. */
    val activeDays: Int = 0,
    /** The day with the most listening (its start, in ms) and how long it was. */
    val biggestDay: Pair<Long, Long>? = null,
)

/**
 * Listening history, kept on this device. The legacy JSON is retained as a
 * recovery copy; new listens are saved individually in an indexed store.
 */
object History {
    private const val MAX_RECORDS = 100_000
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PlayRecord.serializer())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _records = MutableStateFlow<List<PlayRecord>>(emptyList())
    /** Newest first. */
    val records: StateFlow<List<PlayRecord>> = _records.asStateFlow()

    private val lock = Any()
    private val writes = Channel<Change>(Channel.UNLIMITED)
    private val beforeLoad = ArrayList<Change>()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private var initialized = false
    private var ready = false

    private sealed interface Change {
        data class Add(val records: List<PlayRecord>) : Change
        data class Update(val playedAt: Long, val listenedMs: Long) : Change
        data class Replace(val records: List<PlayRecord>) : Change
        data class Barrier(val done: CompletableDeferred<Unit>) : Change
    }

    /** JSON parsing, database migration and archive loading never block app startup. */
    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
        }
        val app = context.applicationContext
        scope.launch {
            val db = HistoryDatabase(app)
            var warned = false
            var initial: List<PlayRecord> = emptyList()
            while (true) {
                try {
                    initial = db.load(File(app.filesDir, "history.json"), MAX_RECORDS)
                    break
                } catch (e: Exception) {
                    Log.w("History", "Couldn't load history; the existing files are unchanged", e)
                    if (!warned) {
                        com.opentune.data.Pills.error("Couldn't load listening history. Your existing history files were kept.", app)
                        warned = true
                    }
                    delay(5_000)
                }
            }
            synchronized(lock) {
                var combined = initial
                for (change in beforeLoad) combined = applyChange(combined, change)
                _records.value = combined
                beforeLoad.forEach { writes.trySend(it).getOrThrow() }
                beforeLoad.clear()
                ready = true
                _loaded.value = true
            }
            // One writer preserves the order of listens, duration updates and
            // imports. On failure the same transaction is retried before later writes.
            for (change in writes) {
                var saveWarned = false
                while (true) {
                    try {
                        when (change) {
                            is Change.Add -> db.add(change.records, MAX_RECORDS)
                            is Change.Update -> db.updateListened(change.playedAt, change.listenedMs)
                            is Change.Replace -> db.replace(change.records)
                            is Change.Barrier -> change.done.complete(Unit)
                        }
                        break
                    } catch (e: Exception) {
                        Log.w("History", "Couldn't save history; will retry", e)
                        if (!saveWarned) {
                            com.opentune.data.Pills.error("Couldn't save listening history yet. Retrying.", app)
                            saveWarned = true
                        }
                        delay(5_000)
                    }
                }
            }
        }
    }

    suspend fun awaitLoaded() { loaded.first { it } }

    /** Wait for all preceding writes to commit before reporting an import as complete. */
    suspend fun awaitPersisted() {
        awaitLoaded()
        val done = CompletableDeferred<Unit>()
        writes.send(Change.Barrier(done))
        done.await()
    }

    /** Changes are sent to the writer immediately rather than waiting for a debounce. */
    fun flush() = Unit

    private fun applyChange(current: List<PlayRecord>, change: Change): List<PlayRecord> = when (change) {
        is Change.Barrier -> current
        is Change.Replace -> change.records
        is Change.Add -> {
            if (change.records.size == 1) {
                val record = change.records.single()
                var low = 0
                var high = current.size
                while (low < high) {
                    val middle = (low + high) ushr 1
                    if (current[middle].playedAt > record.playedAt) low = middle + 1 else high = middle
                }
                if (low >= MAX_RECORDS) current else ArrayList<PlayRecord>((current.size + 1).coerceAtMost(MAX_RECORDS)).apply {
                    addAll(current.subList(0, low))
                    add(record)
                    addAll(current.subList(low, (MAX_RECORDS - 1).coerceAtMost(current.size)))
                }
            } else (change.records + current).sortedByDescending { it.playedAt }.take(MAX_RECORDS)
        }
        is Change.Update -> {
            val index = current.indexOfFirst { it.playedAt == change.playedAt }
            if (index < 0) current else ArrayList(current).apply {
                var i = index
                while (i < size && this[i].playedAt == change.playedAt) {
                    if (!this[i].fromAccount) this[i] = this[i].copy(listenedMs = change.listenedMs)
                    i++
                }
            }
        }
    }

    private fun submit(change: Change) = synchronized(lock) {
        _records.value = applyChange(_records.value, change)
        if (ready) writes.trySend(change).getOrThrow() else beforeLoad += change
    }

    /** Records a listen and returns its key for [updateListened]. */
    fun record(song: Song, listenedMs: Long): Long = synchronized(lock) {
        var now = System.currentTimeMillis()
        // Duration updates use the timestamp as a key. Avoid sharing it with
        // an existing listen, including one imported from Spotify.
        fun exists(at: Long): Boolean {
            val current = _records.value
            var low = 0
            var high = current.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (current[middle].playedAt > at) low = middle + 1 else high = middle
            }
            return low < current.size && current[low].playedAt == at
        }
        while (exists(now)) now++
        val r = PlayRecord(song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName, now, listenedMs, song.isExplicit, song.artistId)
        submit(Change.Add(listOf(r)))
        now
    }

    /**
     * Brings in listens from the account's own history: [sections] as YouTube
     * Music groups it ("Today", "Yesterday", "This week"), newest first. A song
     * already in this history on that day (played here, and so reported there,
     * or brought in before) isn't added again. YouTube Music doesn't say when in
     * the day, so each is placed in order through its day.
     */
    suspend fun mergeAccount(sections: List<Pair<String, List<Song>>>, now: Long = System.currentTimeMillis()) {
        awaitLoaded()
        while (true) {
            val snapshot = _records.value
            val added = accountListens(snapshot, sections, now)
            if (added.isEmpty()) return
            val combined = applyChange(snapshot, Change.Add(added))
            synchronized(lock) {
                if (_records.value === snapshot) {
                    _records.value = combined
                    writes.trySend(Change.Add(added)).getOrThrow()
                    return
                }
            }
        }
    }

    internal fun accountListens(existing: List<PlayRecord>, sections: List<Pair<String, List<Song>>>, now: Long): List<PlayRecord> {
        val day = 24 * 3_600_000L
        val clock = java.util.Calendar.getInstance().apply { timeInMillis = now }
        clock.set(java.util.Calendar.HOUR_OF_DAY, 0); clock.set(java.util.Calendar.MINUTE, 0)
        clock.set(java.util.Calendar.SECOND, 0); clock.set(java.util.Calendar.MILLISECOND, 0)
        val today = clock.timeInMillis
        val out = mutableListOf<PlayRecord>()
        // Only the latest eligible play of each song matters for the window.
        // Avoid copying and rescanning the whole archive for every account song.
        val latest = HashMap<String, Long>()
        for (record in existing) {
            if (record.playedAt <= now && record.playedAt > (latest[record.videoId] ?: Long.MIN_VALUE)) {
                latest[record.videoId] = record.playedAt
            }
        }
        for ((title, songs) in sections) {
            // The window the section covers, and where in it to place its songs.
            val (from, to) = when (title.trim().lowercase()) {
                "today" -> today to now
                "yesterday" -> today - day to today
                "this week" -> today - 6 * day to today - day
                else -> continue
            }
            val step = ((to - from) / (songs.size + 1)).coerceIn(1_000L, 10 * 60_000L)
            songs.forEachIndexed { i, song ->
                val known = (latest[song.videoId] ?: Long.MIN_VALUE) >= from - day
                if (known) return@forEachIndexed
                val length = song.durationText.durationMillis() ?: 180_000L
                val record = PlayRecord(
                    song.videoId, song.title, song.artist, song.thumbnailUrl, song.durationText, song.albumName,
                    playedAt = (to - (i + 1) * step).coerceAtLeast(from), listenedMs = length,
                    explicit = song.isExplicit, artistId = song.artistId, fromAccount = true,
                )
                out += record
                latest[record.videoId] = maxOf(latest[record.videoId] ?: Long.MIN_VALUE, record.playedAt)
            }
        }
        return out
    }

    /**
     * Imports Spotify listens into the same records used by Replay/Wrapped.
     * Re-importing the same exports is idempotent. Distinct repeated listens
     * at different timestamps remain distinct. The normal 100k cap applies.
     * Spotify IDs are prefixed so they cannot be mistaken for YouTube video IDs.
     */
    suspend fun mergeSpotify(listens: List<SpotifyHistoryParser.Listen>): Int {
        awaitLoaded()
        fun identity(r: PlayRecord): String =
            "${r.playedAt}|${r.listenedMs}|${r.title.trim().lowercase()}|${r.artist.trim().lowercase()}"
        while (true) {
        val snapshot = _records.value
        val known = snapshot.mapTo(HashSet()) { identity(it) }
        val added = ArrayList<PlayRecord>()
        for (listen in listens) {
            val record = PlayRecord(
                videoId = "spotify:" + (listen.spotifyUri?.removePrefix("spotify:track:")
                    ?: (listen.artist.trim().lowercase() + "/" + listen.title.trim().lowercase())),
                title = listen.title,
                artist = listen.artist,
                albumName = listen.album,
                playedAt = listen.endedAtMs,
                listenedMs = listen.listenedMs,
                fromAccount = true,
            )
            if (known.add(identity(record))) added += record
        }
        if (added.isEmpty()) return 0
        val combined = applyChange(snapshot, Change.Add(added))
        var accepted = false
        synchronized(lock) {
            if (_records.value === snapshot) {
                _records.value = combined
                writes.trySend(Change.Add(added)).getOrThrow()
                accepted = true
            }
        }
        if (accepted) {
            awaitPersisted()
            return added.size
        }
        }
    }

    fun updateListened(playedAt: Long, listenedMs: Long) {
        submit(Change.Update(playedAt, listenedMs))
    }

    fun clear() {
        submit(Change.Replace(emptyList()))
    }

    /** Each track once, most recent listen first. */
    fun recents(list: List<PlayRecord>, limit: Int = 40): List<Song> {
        if (limit <= 0) return emptyList()
        // The home screen only needs a few unique songs, not a distinct copy
        // of the entire Spotify archive (which may contain 100,000 listens).
        val seen = HashSet<String>(limit * 2)
        val result = ArrayList<Song>(limit)
        for (record in list) {
            if (seen.add(record.videoId)) {
                result.add(record.toSong())
                if (result.size == limit) break
            }
        }
        return result
    }

    fun replay(list: List<PlayRecord>, sinceMs: Long): ReplaySummary {
        val window = list.filter { it.playedAt >= sinceMs }
        val clock = java.util.Calendar.getInstance()
        val byDay = window.groupBy { r ->
            clock.timeInMillis = r.playedAt
            clock.set(java.util.Calendar.HOUR_OF_DAY, 0); clock.set(java.util.Calendar.MINUTE, 0)
            clock.set(java.util.Calendar.SECOND, 0); clock.set(java.util.Calendar.MILLISECOND, 0)
            clock.timeInMillis
        }.mapValues { (_, plays) -> plays.sumOf { it.listenedMs } }
        fun top(group: (PlayRecord) -> String?, title: (List<PlayRecord>) -> Pair<String, String>, songOf: Boolean): List<TopEntry> =
            window.groupBy { group(it) ?: "" }
                .filterKeys { it.isNotBlank() }
                .map { (key, plays) ->
                    val (t, s) = title(plays)
                    TopEntry(key, t, s, plays.first().thumbnailUrl, plays.size, plays.sumOf { it.listenedMs }, if (songOf) plays.first().toSong() else null)
                }
                .sortedWith(compareByDescending<TopEntry> { it.plays }.thenByDescending { it.listenedMs })
                .take(25)
        return ReplaySummary(
            totalPlays = window.size,
            listenedMs = window.sumOf { it.listenedMs },
            songCount = window.distinctBy { it.statisticsSongKey() }.size,
            artistCount = window.map { Wrapped.mainArtist(it.artist) }.filter { it.isNotBlank() }.distinct().size,
            albumCount = window.mapNotNull { it.albumName?.takeIf { a -> a.isNotBlank() } }.distinct().size,
            topSongs = top({ it.statisticsSongKey() }, { p -> p.first().title to p.first().artist }, songOf = true),
            topArtists = top({ Wrapped.mainArtist(it.artist) }, { p -> Wrapped.mainArtist(p.first().artist) to "" }, songOf = true),
            topAlbums = top({ it.albumName }, { p -> (p.first().albumName ?: "") to p.first().artist }, songOf = false),
            byHour = clock.let { c ->
                val hours = LongArray(24)
                window.forEach { r -> c.timeInMillis = r.playedAt; hours[c.get(java.util.Calendar.HOUR_OF_DAY)] += r.listenedMs }
                hours.toList()
            },
            byWeekday = clock.let { c ->
                val days = LongArray(7)
                // Calendar counts Sunday as 1; Monday first here.
                window.forEach { r -> c.timeInMillis = r.playedAt; days[(c.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7] += r.listenedMs }
                days.toList()
            },
            activeDays = byDay.size,
            biggestDay = byDay.maxByOrNull { it.value }?.toPair(),
        )
    }

    /** Called on the sync IO dispatcher; retrying a committed page never adds another play. */
    suspend fun mergeDesktop(page: List<PlayRecord>) {
        awaitLoaded()
        while (true) {
            val snapshot = _records.value
            val keys = snapshot.mapTo(HashSet()) { it.videoId to it.playedAt }
            val fresh = page.filter { keys.add(it.videoId to it.playedAt) }
            if (fresh.isEmpty()) break
            val change = Change.Add(fresh)
            val combined = applyChange(snapshot, change)
            val committed = synchronized(lock) {
                if (_records.value !== snapshot) false else {
                    _records.value = combined
                    writes.trySend(change).getOrThrow()
                    true
                }
            }
            if (committed) break
        }
        awaitPersisted()
    }

    fun exportJson(): JsonElement = json.encodeToJsonElement(serializer, _records.value)

    fun importJson(element: JsonElement) {
        submit(Change.Replace(json.decodeFromJsonElement(serializer, element).sortedByDescending { it.playedAt }.take(MAX_RECORDS)))
    }
}
