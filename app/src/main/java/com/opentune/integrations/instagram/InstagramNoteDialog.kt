package com.opentune.integrations.instagram

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.opentune.data.settings.AppSettings
import com.opentune.i18n.Tr
import com.opentune.playback.PlayerConnection
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import com.opentune.ui.theme.Text

private const val INSTAGRAM_PACKAGE = "com.instagram.android"

@Composable
internal fun InstagramNoteDialog(player: PlayerConnection, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var track by remember(player) { mutableStateOf<MusicNoteTrack?>(null) }
    var error by remember(track) { mutableStateOf<String?>(null) }
    LaunchedEffect(lifecycle, player) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                musicNoteTracks(player.currentSong, player.isSounding, player.connected).collect { track = it }
            } finally {
                track = null
            }
        }
    }

    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Instagram Music Notes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val candidate = track
                if (candidate == null) {
                    Text("Waiting for a playing song…")
                } else {
                    Text(AnnotatedString(candidate.searchText), maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text("Copy the song and open Instagram. In your inbox, create a Note, tap Music, and paste the search text. Choose the recording and share it there.")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            val candidate = track
            SheetButton("Copy and open Instagram", enabled = candidate != null, onClick = {
                val current = musicNoteTrack(player.currentSong.value, player.isSounding.value, player.connected.value)
                if (candidate == null || candidate != current || !AppSettings.ui.value.instagramMusicNotes ||
                    lifecycle.currentState != Lifecycle.State.RESUMED
                ) return@SheetButton

                error = openInstagramWithSearchText(context, candidate.searchText)
                if (error == null) onDismiss()
            })
        },
    )
}

internal fun openInstagramWithSearchText(context: Context, searchText: String): String? {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
        ?: return "Could not copy the song."
    val clip = ClipData.newPlainText(Tr.t("Instagram Music Notes"), searchText)
    clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    try {
        clipboard.setPrimaryClip(clip)
    } catch (_: SecurityException) {
        return "Could not copy the song."
    }

    val unavailable = "Song copied. The Instagram app could not be opened."
    return try {
        val intent = context.packageManager.getLaunchIntentForPackage(INSTAGRAM_PACKAGE) ?: return unavailable
        context.startActivity(intent)
        null
    } catch (_: ActivityNotFoundException) {
        unavailable
    } catch (_: SecurityException) {
        unavailable
    }
}
