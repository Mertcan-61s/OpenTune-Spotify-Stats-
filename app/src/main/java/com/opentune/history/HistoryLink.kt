package com.opentune.history

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import com.opentune.data.history.History
import com.opentune.data.history.PlayRecord
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.json.JSONArray
import org.json.JSONObject

class HistoryPairActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val uri = intent.data
        val host = uri?.getQueryParameter("host").orEmpty()
        val pin = uri?.getQueryParameter("pin").orEmpty()
        val token = uri?.getQueryParameter("token").orEmpty()
        val name = uri?.getQueryParameter("name").orEmpty().take(63)
        val hex = Regex("[0-9a-f]{64}")
        if (uri?.scheme != "opentune" || uri.host != "history-sync" ||
            !host.matches(Regex("[0-9.]{7,15}")) || !pin.matches(hex) || !token.matches(hex)) {
            finish()
            return
        }
        AlertDialog.Builder(this).setTitle("Dinleme kayıtlarını eşitle")
            .setMessage("Bu bilgisayarla ($host) iki yönlü dinleme geçmişi paylaşımına izin verilsin mi?")
            .setNegativeButton("Vazgeç") { _, _ -> finish() }
            .setPositiveButton("Eşleştir") { _, _ ->
                val prefs = getSharedPreferences("history-link", Context.MODE_PRIVATE)
                prefs.edit().putString("name", name).putString("host", host)
                    .putString("pin", pin).putString("token", token).apply()
                finish()
            }.setOnCancelListener { finish() }.show()
    }
}

object HistoryLink {
    private val json = Json { ignoreUnknownKeys = true }
    private val list = ListSerializer(PlayRecord.serializer())
    private fun fingerprint(cert: X509Certificate) =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }

    private fun tls(pin: String) = SSLContext.getInstance("TLS").apply {
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
            override fun checkClientTrusted(chain: Array<X509Certificate>, type: String) {
                throw CertificateException()
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, type: String) {
                if (chain.isEmpty() || fingerprint(chain[0]) != pin) throw CertificateException("Unpaired host")
                chain[0].checkValidity()
            }
        }
        init(null, arrayOf(trust), null)
    }

    fun start(context: Context) {
        val prefs = context.getSharedPreferences("history-link", Context.MODE_PRIVATE)
        val network = context.getSystemService(ConnectivityManager::class.java)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            History.awaitLoaded()
            var previous: List<PlayRecord>? = null
            var previousPin = ""
            var cachedHost = ""
            while (true) {
                val pin = prefs.getString("pin", "").orEmpty()
                val wifi = network?.getNetworkCapabilities(network.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                if (pin.isNotEmpty() && wifi) {
                    try {
                        if (pin != previousPin) {
                            previous = null
                            previousPin = pin
                            cachedHost = ""
                        }
                        val name = prefs.getString("name", "").orEmpty()
                        val configured = prefs.getString("host", "").orEmpty()
                        val token = prefs.getString("token", "").orEmpty()
                        val ssl = tls(pin)
                        suspend fun page(rows: List<PlayRecord>): Boolean {
                            val cursor = prefs.getLong("cursor-$pin", 0)
                            val body = JSONObject().put("cursor", cursor).put("rows",
                                JSONArray(json.encodeToString(list, rows))).toString().toByteArray(Charsets.UTF_8)
                            fun send(host: String): JSONObject {
                                val ip = InetAddress.getByName(host)
                                require(ip is Inet4Address && (ip.isSiteLocalAddress || ip.isLinkLocalAddress))
                                val conn = URL("https://" + ip.hostAddress + ":43187/history").openConnection() as HttpsURLConnection
                                try {
                                    conn.sslSocketFactory = ssl.socketFactory
                                    conn.setHostnameVerifier { _, session -> fingerprint(session.peerCertificates[0] as X509Certificate) == pin }
                                    conn.instanceFollowRedirects = false
                                    conn.connectTimeout = 2500
                                    conn.readTimeout = 15000
                                    conn.requestMethod = "POST"
                                    conn.doOutput = true
                                    conn.setFixedLengthStreamingMode(body.size)
                                    conn.setRequestProperty("Authorization", "Bearer $token")
                                    conn.outputStream.use { it.write(body) }
                                    return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
                                } finally { conn.disconnect() }
                            }
                            val first = cachedHost.ifEmpty { configured }
                            val result = try { send(first).also { cachedHost = first } }
                            catch (e: java.io.IOException) {
                                if (!name.matches(Regex("[A-Za-z0-9-]{1,63}"))) throw e
                                val next = if (first == "$name.local") configured else "$name.local"
                                send(next).also { cachedHost = next }
                            }
                            val incoming = json.decodeFromString(list, result.getJSONArray("rows").toString())
                            if (incoming.isNotEmpty()) History.mergeDesktop(incoming)
                            val next = result.getLong("cursor")
                            if (next != cursor) check(prefs.edit().putLong("cursor-$pin", next).commit())
                            return result.getBoolean("more")
                        }
                        while (page(emptyList())) { /* drain PC pages after a disconnect */ }
                        val snapshot = History.records.value
                        if (snapshot !== previous) {
                            val known = previous?.mapTo(HashSet()) { it.videoId to it.playedAt }
                            val pending = snapshot.asReversed().filter { known == null || (it.videoId to it.playedAt) !in known }
                            // Original archives can contain 100k entries. Remember acknowledged
                            // blocks across launches; the PC still deduplicates individual entries.
                            val archive = context.getSharedPreferences("history-blocks-$pin", Context.MODE_PRIVATE)
                            pending.chunked(400).forEach { block ->
                                val digest = MessageDigest.getInstance("SHA-256")
                                for (item in block) {
                                    val id = item.videoId.toByteArray(Charsets.UTF_8)
                                    digest.update(java.nio.ByteBuffer.allocate(12).putLong(item.playedAt).putInt(id.size).array())
                                    digest.update(id)
                                }
                                val key = android.util.Base64.encodeToString(digest.digest(), android.util.Base64.NO_WRAP)
                                if (block.size < 400 || !archive.contains(key)) {
                                    page(block)
                                    if (block.size == 400) archive.edit().putBoolean(key, true).apply()
                                }
                            }
                            previous = snapshot
                        }
                    } catch (e: Exception) {
                        android.util.Log.d("HistoryLink", "Will retry: " + e.javaClass.simpleName)
                    }
                }
                delay(if (pin.isEmpty()) 5000L else 15000L)
            }
        }
    }
}
