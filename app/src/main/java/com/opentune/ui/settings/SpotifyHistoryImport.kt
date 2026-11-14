package com.opentune.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.opentune.data.history.History
import com.opentune.data.history.SpotifyHistoryParser
import java.io.BufferedInputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Native on-device Spotify export import. No Spotify credentials or upload required. */
@Composable
fun SpotifyHistoryImport() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("Choose Spotify's Extended Streaming History ZIP or JSON export.") }
    var pending by remember { mutableStateOf<List<SpotifyHistoryParser.Listen>>(emptyList()) }
    var skipped by remember { mutableStateOf(0) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        pending = emptyList()
        status = "Reading history on this device…"
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val records = ArrayList<SpotifyHistoryParser.Listen>()
                    var invalid = 0
                    fun accept(content: String) {
                        val parsed = SpotifyHistoryParser.parse(content)
                        records.addAll(parsed.listens)
                        invalid += parsed.skipped
                    }
                    val name = context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }.orEmpty()
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        if (name.endsWith(".zip", ignoreCase = true)) {
                            ZipInputStream(BufferedInputStream(input)).use { zip ->
                                while (true) {
                                    val entry = zip.nextEntry ?: break
                                    if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true) &&
                                        (entry.name.substringAfterLast('/').startsWith("endsong_", true) ||
                                            entry.name.substringAfterLast('/').startsWith("StreamingHistory", true))) {
                                        val bytes = java.io.ByteArrayOutputStream()
                                        zip.copyTo(bytes)
                                        accept(bytes.toString(Charsets.UTF_8.name()))
                                    }
                                    zip.closeEntry()
                                }
                            }
                        } else {
                            require(name.endsWith(".json", ignoreCase = true)) { "Select a ZIP or JSON export" }
                            accept(input.bufferedReader().readText())
                        }
                    } ?: error("Unable to open selected file")
                    require(records.isNotEmpty()) { "No supported music listens found in this export" }
                    records to invalid
                }
            }.onSuccess { (records, invalid) ->
                pending = records
                skipped = invalid
                status = "Found ${records.size} music listens. Skipped $invalid unsupported entries. Review and import below."
            }.onFailure { error ->
                status = "Import could not be read: ${error.message ?: "Unknown error"}"
            }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Spotify listening history", style = MaterialTheme.typography.titleMedium)
        Text("Import your Spotify data export from either account. Files stay on this phone. Existing OpenTune history is preserved; the latest 10,000 listens are retained.")
        OutlinedButton(onClick = { picker.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) }, enabled = !busy) {
            Text(if (busy) "Reading…" else "Choose Spotify ZIP / JSON")
        }
        Text(status, style = MaterialTheme.typography.bodySmall)
        if (pending.isNotEmpty()) {
            Button(onClick = {
                busy = true
                scope.launch {
                    val count = withContext(Dispatchers.Default) { History.mergeSpotify(pending) }
                    pending = emptyList()
                    status = "Imported $count new listens. Skipped $skipped unsupported entries. Repeated imports do not duplicate retained listens."
                    busy = false
                }
            }, enabled = !busy) { Text("Import ${pending.size} listens") }
        }
    }
}
