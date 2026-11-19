package com.opentune.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.opentune.data.settings.AppSettings
import com.opentune.integrations.instagram.AutoMusicNotes
import com.opentune.integrations.instagram.NotePairing
import com.opentune.integrations.instagram.bridgeAddress
import com.opentune.ui.components.FloatingDialog
import com.opentune.ui.components.SheetButton
import com.opentune.ui.theme.Text
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Composable
internal fun AutoNotesDialog(onDismiss: () -> Unit) {
    val app = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { runCatching { NotePairing.load(app) }.getOrNull() }
        if (saved != null && address.isEmpty() && key.isEmpty()) {
            address = saved.address.toString()
            key = saved.key
        }
    }
    FloatingDialog(
        onDismissRequest = onDismiss,
        title = { Text("Automatic Music Notes") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your bridge receives the title and artist during playback and shares matching music with Close Friends. The unofficial Instagram API can trigger account restrictions. Use a test account.")
                OutlinedTextField(address, { address = it; key = ""; error = false }, enabled = !busy,
                    singleLine = true, label = { Text("Your bridge URL") })
                OutlinedTextField(key, { key = it; error = false }, enabled = !busy, singleLine = true,
                    label = { Text("Bridge pairing key") }, visualTransformation = PasswordVisualTransformation())
                if (error) Text("Couldn't pair. Check the address, key and bridge status.")
            }
        },
        confirmButton = {
            val url = bridgeAddress(address)
            SheetButton("Connect and enable", enabled = !busy && url != null && key.trim().length >= 32, onClick = {
                if (url == null) return@SheetButton
                val pairing = NotePairing(url, key.trim())
                busy = true
                scope.launch {
                    try {
                        check(pairing.post("check", JSONObject()).getString("result") == "ready")
                        withContext(Dispatchers.IO) { pairing.save(app) }
                        AppSettings.updateUi { it.copy(autoInstagramNotes = true) }
                        AutoMusicNotes.settingChanged(app)
                        onDismiss()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        error = true
                    } finally {
                        busy = false
                    }
                }
            })
        },
        dismissButton = { SheetButton("Cancel", onClick = onDismiss, closes = true) },
    )
}
