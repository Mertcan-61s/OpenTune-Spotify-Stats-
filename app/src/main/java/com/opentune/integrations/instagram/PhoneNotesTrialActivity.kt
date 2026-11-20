package com.opentune.integrations.instagram

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ComponentName
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputFilter
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.opentune.playback.PlaybackService
import com.google.common.util.concurrent.ListenableFuture
import java.util.Locale
import java.util.concurrent.Executor
import org.json.JSONObject

/** A short, visible trial. The isolated WebView owns its Instagram login for this session. */
class PhoneNotesTrialActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var column: LinearLayout
    private lateinit var status: TextView
    private lateinit var account: EditText
    private lateinit var action: Button
    private var browser: WebView? = null
    private var connection: ListenableFuture<MediaController>? = null
    private var player: MediaController? = null
    private var playing: MusicNoteTrack? = null
    private var stableSince = 0L
    private var expected = ""
    private var held: MusicNoteTrack? = null
    private var step = "login"
    private var generation = 0
    private var active = false
    private var closed = false
    private val script by lazy { assets.open("instagram/notes-trial.js").bufferedReader().use { it.readText() } }

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = updateSong()
    }

    private fun current(): MusicNoteTrack? {
        val p = player ?: return null
        val item = p.currentMediaItem ?: return null
        val title = item.mediaMetadata.title?.toString()?.trim().orEmpty()
        val artist = item.mediaMetadata.artist?.toString()?.trim().orEmpty()
        if (!p.isPlaying || title.isEmpty() || artist.isEmpty() || title.length > 512 || artist.length > 512) return null
        return MusicNoteTrack(item.mediaId, title, artist)
    }

    private fun updateSong() {
        val track = current()
        if (playing == track) return
        playing = track
        stableSince = SystemClock.elapsedRealtime()
        if (held != null && held != track) stopTrial("Şarkı değişti veya durdu. Deneme sona erdi.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
            fitsSystemWindows = true
        }
        setContentView(column)
        handler.postDelayed({ finish() }, 15 * 60_000L)
        column.addView(TextView(this).apply {
            text = "OpenTune · Telefonda müzik notu denemesi"
            textSize = 19f
        })
        status = TextView(this).apply {
            text = "Yan hesabının adını gir. Giriş Instagram’ın sayfasında yapılır. Bir şarkı çalarken gelen kutusunu aç. Web sürümünde müzikli not desteği hesabına göre değişebilir."
        }
        column.addView(status)
        account = EditText(this).apply {
            hint = "Deneme hesabının kullanıcı adı"
            setSingleLine()
            filters = arrayOf(InputFilter.LengthFilter(31))
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        }
        column.addView(account)
        val buttons = LinearLayout(this)
        action = Button(this).apply {
            text = "Instagram’ı burada aç"
            setOnClickListener { onAction() }
        }
        buttons.addView(action, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            text = "Kapat"
            setOnClickListener { finish() }
        })
        column.addView(buttons)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        connection = future
        future.addListener({
            if (!closed && !isFinishing) {
                player = runCatching { future.get() }.getOrNull()
                player?.addListener(listener)
                updateSong()
            }
        }, Executor { handler.post(it) })
    }

    private fun onAction() {
        if (browser == null) {
            expected = account.text.toString().trim().removePrefix("@").lowercase(Locale.ROOT)
            if (!expected.matches(Regex("[a-z0-9._]{1,30}"))) {
                status.text = "Geçerli bir Instagram kullanıcı adı gir."
                return
            }
            account.visibility = View.GONE
            action.isEnabled = false
            openBrowser()
            return
        }
        if (step == "login") {
            updateSong()
            val song = playing
            if (song == null || SystemClock.elapsedRealtime() - stableSince < 10_000L) {
                status.text = "Önce OpenTune’da bir şarkı çal ve en az 10 saniye dinle."
                return
            }
            if (!inInbox()) {
                status.text = "Giriş yaptıktan sonra @${expected} hesabının gelen kutusuna git."
                return
            }
            held = song
            step = "open"
            status.text = "${song.title} — ${song.artist} aranıyor."
            action.isEnabled = false
            drive()
        } else if (step == "ready") {
            step = "submit"
            action.isEnabled = false
            drive()
        } else if (step == "review") {
            action.isEnabled = false
            drive()
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openBrowser() {
        val web = WebView(this)
        browser = web
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        web.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false)
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.isForMainFrame && !instagram(request.url)

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (!instagram(Uri.parse(url.orEmpty()))) {
                    view.stopLoading()
                    stopTrial("Instagram dışındaki sayfa engellendi.")
                } else if (held != null) {
                    stopTrial("Sayfa değişti. Deneme sona erdi.")
                }
            }
        }
        column.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
        // This process has its own WebView data directory, independent of other integrations.
        // Also clear any session left by Android killing the preceding trial.
        WebStorage.getInstance().deleteAllData()
        CookieManager.getInstance().removeAllCookies {
            if (closed || isFinishing) return@removeAllCookies
            action.text = "Çalan parçayı dene"
            action.isEnabled = true
            web.loadUrl(INBOX)
        }
    }

    private fun instagram(uri: Uri) =
        uri.scheme == "https" && uri.host == "www.instagram.com" && uri.port == -1 && uri.userInfo == null

    private fun inInbox(): Boolean {
        val uri = Uri.parse(browser?.url.orEmpty())
        return instagram(uri) && uri.path == "/direct/inbox/"
    }

    private fun drive(attempt: Int = 0) {
        val web = browser ?: return
        val song = held ?: return
        if (!active || !inInbox() || current() != song) {
            stopTrial("Gelen kutusu veya çalan şarkı değişti. Deneme durdu.")
            return
        }
        val epoch = generation
        val operation = step
        val args = listOf(operation, expected, song.title, song.artist).joinToString(",") { JSONObject.quote(it) }
        web.evaluateJavascript("($script)($args)") { raw ->
            if (closed || !active || epoch != generation || operation != step) return@evaluateJavascript
            val result = raw?.trim('"')
            when {
                result == "account" -> stopTrial("Gelen kutusunda @${expected} hesabı doğrulanamadı.")
                result == "sent" -> stopTrial("Paylaş düğmesine basıldı. Yakın Arkadaşlar listesindeki ikinci hesaptan notu açıp sesi kontrol et.")
                result == "audience" && operation in listOf("review", "composer") -> {
                    step = "review"
                    status.text = "Instagram’da hedef kitleyi Yakın Arkadaşlar olarak seç, sonra tekrar kontrol et."
                    action.text = "Kitleyi kontrol et"
                    action.isEnabled = true
                }
                result == "ready" -> {
                    step = "ready"
                    status.text = "@${expected} · ${song.title} — ${song.artist} · Yakın Arkadaşlar"
                    action.text = "Bir kez paylaş"
                    action.isEnabled = true
                }
                result in listOf("music", "search", "select", "review", "composer") -> {
                    step = result!!
                    handler.postDelayed({ if (epoch == generation) drive() }, 650)
                }
                operation != "submit" && operation != "open" && attempt < 6 -> {
                    handler.postDelayed({ if (epoch == generation) drive(attempt + 1) }, 650)
                }
                else -> stopTrial("Müzikli not ekranı, tekil kayıt veya hedef kitle doğrulanamadı. Deneme durdu.")
            }
        }
    }

    private fun stopTrial(message: String) {
        generation++
        held = null
        step = "done"
        action.isEnabled = false
        status.text = message
    }

    override fun onResume() {
        super.onResume()
        active = true
        browser?.onResume()
    }

    override fun onPause() {
        active = false
        if (held != null) stopTrial("Deneme ekranı kapandı. Otomatik işlem durdu.")
        browser?.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        player?.removeListener(listener)
        connection?.let { MediaController.releaseFuture(it) }
        browser?.let {
            it.stopLoading()
            column.removeView(it)
            it.clearHistory()
            it.clearCache(true)
            it.destroy()
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies { CookieManager.getInstance().flush() }
        }
        super.onDestroy()
    }

    private companion object {
        const val INBOX = "https://www.instagram.com/direct/inbox/"
    }
}
