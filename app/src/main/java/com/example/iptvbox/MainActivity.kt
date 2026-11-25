package com.example.iptvbox

import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.math.min

class Ch(val name: String, val group: String?, val url: String)

class Src(val name: String, val url: String)

class Entry(val label: String, val kind: Int, val idx: Int = -1, val group: String? = null)

class MainActivity : AppCompatActivity() {

    companion object {
        // Link file txt trên Google Drive. Mỗi dòng trong file là link của MỘT NGUỒN (một danh sách nhiều kênh,
        // thường là file m3u rút gọn qua bit.ly, gg.gg...). Bấm vào nguồn thì app mới tải danh sách kênh của nó.
        // Muốn đổi link mà không build lại: tạo file source.txt trong Android/data/com.example.iptvbox/files/
        // và ghi link mới vào dòng đầu tiên.
        const val LIST_URL = "https://drive.google.com/file/d/16lo5B7kaqmre5z4kh-sfWVRoi-r0xlmj/view?usp=sharing"
        const val AUTOPLAY_LAST = true      // mở app là tự phát lại kênh xem lần cuối
        const val UA = "Mozilla/5.0 (Linux; Android 6.0.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Safari/537.36"
        const val KIND_UP = 0
        const val KIND_SRC = 1
        const val KIND_GROUP = 2
        const val KIND_CH = 3
        const val KIND_REFRESH = 4
        const val KIND_INFO = 5
        val FF_MIMES = setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_MPEG_L2
        )
        const val OSD_MS = 6000L
        const val MAX_RETRY = 5
        const val MAX_BYTES = 12_000_000
        const val DIRECT_STREAM = "#EXT-X-TARGETDURATION:direct"   // dấu hiệu nội bộ: link là luồng phát
    }

    private val aspectNames = arrayOf("Tỉ lệ gốc", "Full cả 2 lề", "Kéo giãn lề ngang", "Kéo giãn lề dọc")

    private lateinit var prefs: SharedPreferences
    private lateinit var root: FrameLayout
    private lateinit var leftPanel: LinearLayout
    private lateinit var rightPanel: View
    private lateinit var divider: View
    private lateinit var videoHost: FrameLayout
    private lateinit var surface: SurfaceView
    private lateinit var placeholder: View
    private lateinit var controls: LinearLayout
    private lateinit var titleV: TextView
    private lateinit var artistV: TextView
    private lateinit var statusV: TextView
    private lateinit var aspectLabel: TextView
    private lateinit var audioLabel: TextView
    private lateinit var btnAudio: ImageButton
    private lateinit var pathV: TextView
    private lateinit var hud: TextView
    private lateinit var btnPlay: ImageButton
    private lateinit var rv: RecyclerView
    private lateinit var player: ExoPlayer

    private val adapter = Adapter()
    private val ui = Handler(Looper.getMainLooper())

    // ---- Dữ liệu: danh sách nguồn -> kênh của từng nguồn ----
    private var srcs: List<Src> = emptyList()
    private val chanMap = HashMap<String, List<Ch>>()     // link nguồn -> các kênh đã tải
    private val textHash = HashMap<String, Int>()         // link nguồn -> mã băm nội dung đã lưu (để biết có thay đổi không)
    private val fetchedThisRun = HashSet<String>()        // nguồn đã cập nhật từ mạng trong lần chạy này
    private var viewSrc = -1                              // -1 = màn hình danh sách nguồn
    private var viewGroup: String? = null
    private var srcMsg = ""                               // thông báo của nguồn đang mở (đang tải / lỗi)
    private var listMsg = ""                              // thông báo của danh sách nguồn
    private val webTried = HashSet<String>()              // nguồn đã thử mở bằng trình duyệt ẩn trong lần chạy này
    private var web: WebView? = null
    private var webToken = 0
    private val errTL = ThreadLocal<String>()
    private val headTL = ThreadLocal<String>()
    private var lastErr: String
        get() = errTL.get() ?: ""
        set(v) { errTL.set(v) }
    private var lastHead: String
        get() = headTL.get() ?: ""
        set(v) { headTL.set(v) }
    @Volatile private var streamUa = UA                 // User-Agent dùng khi phát kênh hiện tại

    // ---- Phát ----
    private var playSrc = -1
    private var playIdx = -1
    private var playUrl: String? = null
    private var playSrcUrl: String? = null
    private var active = false                // đang xem (hoặc đang kết nối) một kênh; false = đã dừng
    private var ready = false
    private var resumeOnStart = false
    private var autoStarted = false
    private var aspectMode = 0
    private var passthrough = false
    private var fullscreen = false
    private var osdVisible = false
    private var ffmpegOn = false
    private var vw = 0f
    private var vh = 0f
    private var retryCount = 0
    private var playToken = 0
    private var numBuf = ""

    private val hideHudR = Runnable { hud.visibility = View.GONE }
    private val hideOsdR = Runnable { hideOsd() }
    private val retryR = Runnable { if (playIdx >= 0) playChannel(playSrc, playIdx, true) }
    private val numCommitR = Runnable {
        val n = numBuf.toIntOrNull()
        numBuf = ""
        if (n != null) {
            if (n in 1..chans(playSrc).size) playChannel(playSrc, n - 1) else showHud("Không có kênh $n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sslInfo = SslFix.install(this)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("iptvbox", MODE_PRIVATE)
        root = findViewById(R.id.root)
        leftPanel = findViewById(R.id.leftPanel)
        rightPanel = findViewById(R.id.rightPanel)
        divider = findViewById(R.id.divider)
        videoHost = findViewById(R.id.videoHost)
        surface = findViewById(R.id.surface)
        placeholder = findViewById(R.id.placeholder)
        controls = findViewById(R.id.controls)
        titleV = findViewById(R.id.title)
        artistV = findViewById(R.id.artist)
        statusV = findViewById(R.id.status)
        aspectLabel = findViewById(R.id.aspectLabel)
        audioLabel = findViewById(R.id.audioLabel)
        btnAudio = findViewById(R.id.btnAudio)
        pathV = findViewById(R.id.path)
        hud = findViewById(R.id.hud)
        btnPlay = findViewById(R.id.btnPlay)
        rv = findViewById(R.id.rv)

        aspectMode = prefs.getInt("aspect", 0)
        passthrough = prefs.getBoolean("audio_passthrough", false)

        // Lần trước bộ giải mã FFmpeg làm app đóng đột ngột thì tắt nó đi (âm thầm)
        val cp = getSharedPreferences("crash", MODE_PRIVATE)
        if (cp.getBoolean("ffmpeg", false)) {
            cp.edit().clear().commit()
            prefs.edit().putBoolean("ffmpeg_blocked", true).commit()
        }

        dlog("SslFix: " + sslInfo)
        buildPlayer(false)

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        videoHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspect() }
        videoHost.setOnClickListener { if (playIdx >= 0) enterFullscreen() }

        btnPlay.setOnClickListener { togglePlay(); if (fullscreen) scheduleOsdHide() }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { step(1); if (fullscreen) scheduleOsdHide() }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { step(-1); if (fullscreen) scheduleOsdHide() }
        findViewById<ImageButton>(R.id.btnAspect).setOnClickListener {
            aspectMode = (aspectMode + 1) % aspectNames.size
            prefs.edit().putInt("aspect", aspectMode).apply()
            updateAspectUi()
            applyAspect()
            if (fullscreen) scheduleOsdHide()
        }
        btnAudio.setOnClickListener {
            passthrough = !passthrough
            prefs.edit().putBoolean("audio_passthrough", passthrough).apply()
            updateAudioUi()
            reloadCurrent()
            if (fullscreen) scheduleOsdHide()
        }
        updateAspectUi()
        updateAudioUi()

        // Hiện ngay danh sách nguồn đã lưu lần trước (nếu có), rồi cập nhật từ mạng ở nền
        val cached = readFile(mainCacheFile())
        if (cached != null) {
            srcs = parseSources(cached)
            listMsg = ""
            render(true)
            autoPlayLast()
        } else {
            listMsg = "Đang tải danh sách nguồn..."
            render(true)
        }
        refreshList(cached, false)
    }

    // ---------- Vòng đời ----------

    override fun onStart() {
        super.onStart()
        if (resumeOnStart && playIdx >= 0) {
            resumeOnStart = false
            playChannel(playSrc, playIdx, true)
        }
    }

    override fun onStop() {
        // Không phát nền: thoát ra ngoài thì ngắt luồng, quay lại sẽ tự kết nối lại
        resumeOnStart = playIdx >= 0 && active
        stopStream("Đã dừng")
        getSharedPreferences("crash", MODE_PRIVATE).edit().remove("ffmpeg").apply()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        cancelWeb()
        ++playToken
        player.release()
        super.onDestroy()
    }

    // ---------- Bộ phát ----------

    // Mặc định dùng bộ giải mã của máy. FFmpeg chỉ bật khi kênh có âm thanh mà máy không giải mã được.
    private fun buildPlayer(withFfmpeg: Boolean) {
        val base = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF)
            .setEnableDecoderFallback(true)
        val rf: RenderersFactory = if (!withFfmpeg) base else RenderersFactory { handler, videoL, audioL, textOut, metaOut ->
            val list = base.createRenderers(handler, videoL, audioL, textOut, metaOut).toMutableList()
            val ff = FfmpegAudioRenderer(handler, audioL, DefaultAudioSink.Builder(this).build())
            val idx = list.indexOfFirst { it.trackType == C.TRACK_TYPE_AUDIO }
            list.add(if (idx >= 0) idx else list.size, ff)
            list.toTypedArray()
        }
        val http = DataSource.Factory {
            DefaultHttpDataSource.Factory()
                .setUserAgent(streamUa)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
                .createDataSource()
        }
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 30000, 1500, 3000)
            .build()
        player = ExoPlayer.Builder(this, rf)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .setLoadControl(load)
            .build()
        ffmpegOn = withFfmpeg
        player.setVideoSurfaceView(surface)
        player.addListener(playerListener)
    }

    private fun reloadWithFfmpeg() {
        if (ffmpegOn || playIdx < 0 || prefs.getBoolean("ffmpeg_blocked", false)) return
        getSharedPreferences("crash", MODE_PRIVATE).edit().putBoolean("ffmpeg", true).commit()
        player.release()
        ready = false
        buildPlayer(true)
        playChannel(playSrc, playIdx, true)
    }

    // Đổi chế độ âm thanh: dựng lại bộ phát và nối lại kênh đang xem
    private fun reloadCurrent() {
        val was = playIdx >= 0 && active
        ++playToken
        player.release()
        ready = false
        buildPlayer(false)
        getSharedPreferences("crash", MODE_PRIVATE).edit().remove("ffmpeg").commit()
        if (was) playChannel(playSrc, playIdx, true) else updatePlayIcon()
    }

    private fun updateAudioUi() {
        audioLabel.text = if (passthrough) "Âm thanh: Truyền thẳng (loa thùng, ampli)" else "Âm thanh: Giải mã (hợp với TV)"
        btnAudio.setColorFilter(if (passthrough) 0xFF80DEEA.toInt() else Color.WHITE)
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    ready = true
                    retryCount = 0
                    statusV.text = "Trực tiếp"
                }
                Player.STATE_BUFFERING -> statusV.text = if (ready) "Đang tải..." else "Đang kết nối..."
                Player.STATE_ENDED -> scheduleRetry()
                else -> {}
            }
            updatePlayIcon()
        }

        override fun onTracksChanged(tracks: Tracks) {
            var needFf = false
            for (g in tracks.groups) {
                if (g.type != C.TRACK_TYPE_AUDIO) continue
                for (i in 0 until g.length) {
                    val m = g.getTrackFormat(i).sampleMimeType
                    if (g.isTrackSelected(i) && m != null && m in FF_MIMES) needFf = true
                }
            }
            val unsupported = tracks.containsType(C.TRACK_TYPE_AUDIO) && !tracks.isTypeSupported(C.TRACK_TYPE_AUDIO)
            // Chuyển sang bộ giải mã bổ sung một cách âm thầm
            if (((needFf && !passthrough) || unsupported) && !ffmpegOn && !prefs.getBoolean("ffmpeg_blocked", false)) {
                ui.post { reloadWithFfmpeg() }
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            updatePlayIcon()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            vw = videoSize.width * videoSize.pixelWidthHeightRatio
            vh = videoSize.height.toFloat()
            applyAspect()
        }

        override fun onPlayerError(error: PlaybackException) {
            ready = false
            scheduleRetry()
        }
    }

    // ---------- Phím điều khiển ----------

    private fun enterDigit(d: Int) {
        if (numBuf.length >= 4) numBuf = ""
        numBuf += d
        showHud("Kênh: $numBuf", 2000L)
        ui.removeCallbacks(numCommitR)
        ui.postDelayed(numCommitR, 1500L)
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val code = e.keyCode
        if (fullscreen && e.action == KeyEvent.ACTION_DOWN) {
            val digit = when (code) {
                in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> code - KeyEvent.KEYCODE_0
                in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> code - KeyEvent.KEYCODE_NUMPAD_0
                else -> -1
            }
            if (digit >= 0) {
                if (e.repeatCount == 0) enterDigit(digit)
                return true
            }
        }
        if (fullscreen && !osdVisible) {
            val mine = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER ||
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT ||
                code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            if (!mine) return super.dispatchKeyEvent(e)
            if (e.action == KeyEvent.ACTION_DOWN && e.repeatCount == 0) {
                when (code) {
                    KeyEvent.KEYCODE_DPAD_UP -> step(1)       // lên = kênh kế tiếp
                    KeyEvent.KEYCODE_DPAD_DOWN -> step(-1)    // xuống = kênh trước
                    else -> showOsd()                         // OK / trái / phải = hiện bảng điều khiển
                }
            }
            return true
        }
        if (fullscreen && osdVisible && e.action == KeyEvent.ACTION_DOWN) scheduleOsdHide()
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_BACK -> {
                if (fullscreen) {
                    exitFullscreen()
                    return true
                }
                if (viewGroup != null || viewSrc >= 0) {
                    goUp()
                    return true
                }
                return super.onKeyDown(code, e)
            }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> if (active) togglePlay()
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_CHANNEL_UP -> step(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_CHANNEL_DOWN -> step(-1)
            else -> return super.onKeyDown(code, e)
        }
        return true
    }

    // ---------- Toàn màn hình ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun enterFullscreen() {
        if (fullscreen) return
        fullscreen = true
        osdVisible = false
        rightPanel.visibility = View.GONE
        divider.visibility = View.GONE
        leftPanel.setPadding(0, 0, 0, 0)
        leftPanel.removeView(controls)
        controls.setBackgroundColor(0xCC000000.toInt())
        controls.setPadding(dp(48), dp(8), dp(48), dp(12))
        controls.visibility = View.GONE
        root.addView(controls, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        videoHost.isClickable = false
        videoHost.isFocusable = false
        currentFocus?.clearFocus()
    }

    private fun exitFullscreen() {
        if (!fullscreen) return
        hideOsd()
        fullscreen = false
        root.removeView(controls)
        controls.setBackgroundColor(Color.TRANSPARENT)
        controls.setPadding(0, 0, 0, 0)
        controls.visibility = View.VISIBLE
        leftPanel.addView(controls, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        leftPanel.setPadding(dp(24), dp(24), dp(24), dp(24))
        rightPanel.visibility = View.VISIBLE
        divider.visibility = View.VISIBLE
        videoHost.isClickable = true
        videoHost.isFocusable = true
        // Kênh vẫn phát tiếp ở khung nhỏ. Đưa danh sách về đúng nguồn/nhóm của kênh đang xem, tiêu điểm vào kênh đó.
        if (playIdx >= 0 && playSrc in srcs.indices) {
            viewSrc = playSrc
            viewGroup = chans(playSrc).getOrNull(playIdx)?.group
        }
        render(true)
    }

    private fun showOsd() {
        if (!fullscreen) return
        osdVisible = true
        controls.visibility = View.VISIBLE
        btnPlay.requestFocus()
        scheduleOsdHide()
    }

    private fun hideOsd() {
        osdVisible = false
        ui.removeCallbacks(hideOsdR)
        controls.visibility = View.GONE
    }

    private fun scheduleOsdHide() {
        ui.removeCallbacks(hideOsdR)
        ui.postDelayed(hideOsdR, OSD_MS)
    }

    private fun showHud(text: String, ms: Long = 1500L) {
        hud.text = text
        hud.visibility = View.VISIBLE
        ui.removeCallbacks(hideHudR)
        ui.postDelayed(hideHudR, ms)
    }

    // ---------- Tỉ lệ hiển thị ----------

    private fun applyAspect() {
        val w = videoHost.width
        val h = videoHost.height
        if (w <= 0 || h <= 0) return
        var tw = w
        var th = h
        if (vw > 0f && vh > 0f) {
            val s = min(w / vw, h / vh)
            val w0 = (vw * s).toInt()
            val h0 = (vh * s).toInt()
            when (aspectMode) {
                0 -> { tw = w0; th = h0 }
                1 -> { tw = w; th = h }
                2 -> { tw = w; th = h0 }
                else -> { tw = w0; th = h }
            }
        }
        val lp = surface.layoutParams as FrameLayout.LayoutParams
        if (lp.width != tw || lp.height != th) {
            lp.width = tw
            lp.height = th
            lp.gravity = Gravity.CENTER
            surface.layoutParams = lp
        }
    }

    private fun updateAspectUi() {
        aspectLabel.text = "Tỉ lệ hiển thị: " + aspectNames[aspectMode]
    }

    // ---------- Mạng: tải văn bản (đi theo chuyển hướng của link rút gọn) ----------

    private fun readFile(f: File): String? = try {
        if (f.isFile) f.readText(Charsets.UTF_8) else null
    } catch (e: Exception) { null }

    private fun writeFile(f: File, s: String) {
        try { f.writeText(s, Charsets.UTF_8) } catch (e: Exception) {}
    }

    private fun mainCacheFile() = File(filesDir, "sources.txt")

    private fun srcCacheFile(url: String) = File(filesDir, "src_" + Integer.toHexString(url.hashCode()) + ".txt")

    private fun isHtml(t: String): Boolean {
        val head = t.trimStart().take(600).lowercase()
        return head.startsWith("<!doctype") || head.startsWith("<html") || head.contains("<html") || head.contains("<head>")
    }

    // Một số link rút gọn không chuyển hướng bằng HTTP mà bằng trang HTML nhỏ (meta refresh / JavaScript)
    private fun htmlRedirect(html: String, base: String): String? {
        val pats = listOf(
            Regex("""<meta[^>]+http-equiv\s*=\s*["']?refresh["']?[^>]*content\s*=\s*["'][^"']*url\s*=\s*([^"'>\s]+)""", RegexOption.IGNORE_CASE),
            Regex("""location\.replace\(\s*["']([^"']+)["']"""),
            Regex("""location(?:\.href)?\s*=\s*["']([^"']+)["']""")
        )
        for (p in pats) {
            val m = p.find(html) ?: continue
            try {
                val u = URL(URL(base), m.groupValues[1].trim()).toString()
                if (u != base && (u.startsWith("http://") || u.startsWith("https://"))) return u
            } catch (e: Exception) {
            }
        }
        return null
    }

    private fun errText(e: Exception): String = when (e) {
        is SSLException -> "lỗi chứng chỉ bảo mật"
        is UnknownHostException -> "không tìm thấy máy chủ / mất mạng"
        is SocketTimeoutException -> "quá thời gian chờ"
        else -> e.javaClass.simpleName
    }

    // Ghi nhật ký chẩn đoán vào Android/data/com.example.iptvbox/files/debug.txt (để gửi cho người hỗ trợ khi nguồn lỗi)
    private fun dlog(msg: String) {
        try {
            val f = getExternalFilesDir(null)?.let { File(it, "debug.txt") } ?: return
            if (f.length() > 200_000) f.delete()
            f.appendText(msg.replace('\n', ' ') + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }

    // Một số máy chủ chặn (403/400/429) hoặc đưa trình duyệt về trang chủ: thử lần lượt vài kiểu User-Agent
    private fun httpGetText(start: String): String? {
        val uas = listOf(UA, "VLC/3.0.18 LibVLC/3.0.18", "curl/7.88.1")
        var firstR: String? = null
        var firstErr = ""
        var firstHead = ""
        for ((i, ua) in uas.withIndex()) {
            val r = httpGetOnce(start, ua)
            if (i == 0) { firstR = r; firstErr = lastErr; firstHead = lastHead }
            if (r != null && !isHtml(r)) return r
            val retry = if (r == null) (lastErr == "HTTP 403" || lastErr == "HTTP 400" || lastErr == "HTTP 429")
                else (lastErr == "trang web trung gian" || lastErr.startsWith("link rút gọn"))
            if (!retry) { if (i == 0) return r; break }
        }
        lastErr = firstErr
        lastHead = firstHead
        return firstR
    }

    private fun httpGetOnce(start: String, ua: String): String? {
        lastErr = ""
        lastHead = ""
        var u = start
        for (hop in 0 until 10) {
            var c: HttpURLConnection? = null
            try {
                c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.setRequestProperty("User-Agent", ua)
                c.setRequestProperty("Accept", "*/*")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location")
                    if (loc == null) { lastErr = "chuyển hướng lỗi (HTTP $code)"; return null }
                    u = URL(URL(u), loc).toString()
                    continue
                }
                if (code != 200) { lastErr = "HTTP $code"; return null }
                val ctype = (c.contentType ?: "").lowercase()
                val upath = u.lowercase().substringBefore('?')
                // File danh sách M3U hay được trả với kiểu audio/x-mpegurl: KHÔNG được coi là luồng phát
                val isListType = ctype.contains("mpegurl") || ctype.contains("m3u") || ctype.startsWith("text/")
                if (!isListType && (ctype.startsWith("video/") || ctype.contains("mp2t") ||
                    (ctype.contains("octet-stream") && (upath.endsWith(".ts") || upath.endsWith(".mp4") || upath.endsWith(".flv") || upath.endsWith(".mkv"))))
                ) return DIRECT_STREAM   // luồng video trực tiếp: đừng tải cả luồng về như văn bản
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                c.inputStream.use { ins ->
                    while (out.size() < MAX_BYTES) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        // Dữ liệu nhị phân (có byte 0 ngay đầu) thì là luồng video, không phải danh sách văn bản
                        if (out.size() == 0 && !isListType) {
                            var bin = false
                            for (k in 0 until minOf(n, 512)) if (buf[k] == 0.toByte()) { bin = true; break }
                            if (bin) return DIRECT_STREAM
                        }
                        out.write(buf, 0, n)
                    }
                }
                val text = String(out.toByteArray(), Charsets.UTF_8).replace("﻿", "")
                if (isHtml(text)) {
                    val next = htmlRedirect(text, u)
                    if (next != null && hop < 9) { u = next; continue }
                    lastErr = "trang web trung gian"
                    try {
                        val fin = URL(u)
                        val fp = fin.path ?: ""
                        val op = URL(start).path ?: ""
                        if ((fp == "" || fp == "/") && op.length > 1) lastErr = "link rút gọn đã bị xoá (chuyển về trang chủ)"
                        else if (Regex("^ww\\d+\\.").containsMatchIn(fin.host ?: "")) lastErr = "tên miền hết hạn (trang đỗ tên miền)"
                    } catch (x: Exception) {
                    }
                    lastHead = u + " :: " + text.trim().take(300)
                }
                return text
            } catch (e: Exception) {
                lastErr = errText(e)
                if (e is SSLException) lastHead = e.toString().take(250)
                return null
            } finally {
                try { c?.disconnect() } catch (e: Exception) {}
            }
        }
        return null
    }

    // ---------- Danh sách nguồn (file txt trên Drive) ----------

    private fun listSource(): String {
        try {
            val f = getExternalFilesDir(null)?.let { File(it, "source.txt") }
            if (f != null && f.isFile) {
                val l = f.readLines().firstOrNull { it.isNotBlank() }?.replace("﻿", "")?.trim()
                if (!l.isNullOrEmpty()) return l
            }
        } catch (e: Exception) {
        }
        return LIST_URL
    }

    // Link chia sẻ Google Drive -> link tải trực tiếp. Link khác (GitHub raw...) dùng nguyên.
    private fun sourceCandidates(s: String): List<String> {
        if (s.contains("google.com")) {
            val m = Regex("""/file/d/([A-Za-z0-9_-]+)""").find(s) ?: Regex("""[?&]id=([A-Za-z0-9_-]+)""").find(s)
            if (m != null) {
                val id = m.groupValues[1]
                return listOf(
                    "https://drive.google.com/uc?export=download&id=$id",
                    "https://drive.usercontent.google.com/download?id=$id&export=download&confirm=t"
                )
            }
        }
        return listOf(s)
    }

    private fun fetchMainList(): String? {
        for (u in sourceCandidates(listSource())) {
            val t = httpGetText(u) ?: continue
            if (t.isBlank() || isHtml(t)) continue
            return t
        }
        return null
    }

    // Tải ở nền. Có thay đổi thì lưu lại và cập nhật; không tải được thì giữ danh sách cũ.
    private fun refreshList(old: String?, manual: Boolean) {
        if (manual) {
            listMsg = "Đang cập nhật..."
            updatePath()
        }
        Thread {
            val text = fetchMainList()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                var changed = false
                listMsg = ""
                if (text == null) {
                    if (srcs.isEmpty()) listMsg = "Không tải được danh sách nguồn"
                    else if (manual) listMsg = "Không tải được, dùng danh sách đã lưu"
                } else if (text != old) {
                    writeFile(mainCacheFile(), text)
                    applySources(parseSources(text))
                    changed = true
                } else if (manual) {
                    listMsg = "Đã là bản mới nhất"
                }
                if (!fullscreen) {
                    if (changed || srcs.isEmpty()) render(manual) else updatePath()
                }
                if (listMsg.isNotEmpty() && srcs.isNotEmpty()) {
                    ui.postDelayed({ listMsg = ""; if (!fullscreen) updatePath() }, 4000L)
                }
                if (!autoStarted) autoPlayLast()
            }
        }.start()
    }

    private fun applySources(list: List<Src>) {
        val viewUrl = srcs.getOrNull(viewSrc)?.url
        srcs = list
        viewSrc = if (viewUrl == null) -1 else list.indexOfFirst { it.url == viewUrl }
        if (viewSrc < 0) viewGroup = null
        playSrc = if (playSrcUrl == null) -1 else list.indexOfFirst { it.url == playSrcUrl }
    }

    private fun deriveName(url: String): String {
        return try {
            val u = Uri.parse(url)
            val segs = (u.path ?: "").split('/').filter { it.isNotEmpty() && !it.equals("raw", true) }
            segs.lastOrNull() ?: (u.host ?: url)
        } catch (e: Exception) { url }
    }

    // Mỗi dòng là một nguồn: "link" hoặc "Tên|link" (hoặc "Tên,link"...). Dòng bắt đầu bằng # là ghi chú.
    private fun parseSources(text: String): List<Src> {
        val out = ArrayList<Src>()
        val seen = HashSet<String>()
        for (raw in text.lines()) {
            val line = raw.replace("﻿", "").trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) continue
            val i1 = line.indexOf("http://", ignoreCase = true)
            val i2 = line.indexOf("https://", ignoreCase = true)
            val idx = listOf(i1, i2).filter { it >= 0 }.minOrNull() ?: continue
            val url = line.substring(idx).trim().substringBefore(' ').substringBefore('|')
            if (url.isEmpty() || !seen.add(url)) continue
            var name = line.substring(0, idx).trim().trimEnd('|', ',', ';', '=', '\t', ' ').trim()
            if (name.isEmpty()) name = deriveName(url)
            out.add(Src(name, url))
        }
        return out
    }

    // ---------- Danh sách kênh của một nguồn ----------

    private fun chans(si: Int): List<Ch> {
        val s = srcs.getOrNull(si) ?: return emptyList()
        return chanMap[s.url] ?: emptyList()
    }

    // Luồng phát thật (m3u8 media/master), khác với file danh sách kênh (cũng bắt đầu bằng #EXTM3U)
    private fun isStreamPlaylist(t: String) = t.contains("#EXT-X-TARGETDURATION") || t.contains("#EXT-X-STREAM-INF")

    private fun openSource(si: Int, manual: Boolean) {
        val s = srcs.getOrNull(si) ?: return
        viewSrc = si
        viewGroup = null
        srcMsg = if (chanMap.containsKey(s.url)) "" else "Đang tải..."
        render(true)
        if (!manual && fetchedThisRun.contains(s.url) && chanMap.containsKey(s.url)) return
        loadSource(si, manual)
    }

    // Nạp bản đã lưu (nếu có) rồi tải bản mới nhất từ mạng, ở nền
    private fun loadSource(si: Int, manual: Boolean) {
        val s = srcs.getOrNull(si) ?: return
        if (manual) {
            srcMsg = "Đang cập nhật..."
            updatePath()
        }
        Thread {
            if (!chanMap.containsKey(s.url)) {
                val cached = readFile(srcCacheFile(s.url))
                if (cached != null) {
                    val list = parseChannels(cached)
                    if (list.isNotEmpty()) {
                        runOnUiThread {
                            if (!chanMap.containsKey(s.url)) {
                                chanMap[s.url] = list
                                textHash[s.url] = cached.hashCode()
                                if (viewSrc == si) { srcMsg = ""; if (!fullscreen) render(true) }
                            }
                        }
                    }
                }
            }
            val text = httpGetText(s.url)
            val err = lastErr
            val head = lastHead
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                val bad = text == null || text.isBlank() || isHtml(text)
                // Chỉ mở trình duyệt ẩn khi có hy vọng: trang trung gian thật hoặc bị chặn tạm (403/429/503)
                val worth = err.isEmpty() || err == "trang web trung gian" || err == "HTTP 403" || err == "HTTP 429" || err == "HTTP 503"
                if (bad && worth && (webTried.add(s.url) || manual)) {
                    // Trang trung gian (JavaScript, nút "Tiếp tục"...): mở bằng trình duyệt ẩn để đi tới trang gốc
                    if (!chanMap.containsKey(s.url)) {
                        srcMsg = "Đang mở trang trung gian..."
                        if (viewSrc == si && !fullscreen) render(false)
                    }
                    webFetch(s.url) { t2 ->
                        if (t2 == null) dlog(s.url + " | " + err + " | trình duyệt ẩn: không lấy được | " + head)
                        else dlog(s.url + " | " + err + " | trình duyệt ẩn: OK (" + t2.length + " ký tự)")
                        handleSourceText(s, t2 ?: text, manual, err)
                    }
                } else {
                    if (bad) dlog(s.url + " | " + err + " | không thử trình duyệt ẩn | " + head)
                    handleSourceText(s, text, manual, err)
                }
            }
        }.start()
    }

    private fun handleSourceText(s: Src, text: String?, manual: Boolean, err: String) {
        if (isFinishing) return
        val si = srcs.indexOfFirst { it.url == s.url }
        if (si < 0) return
        if (text == null || isHtml(text) || text.isBlank()) {
            val why = if (err.isNotEmpty()) " ($err)" else ""
            srcMsg = if (!chanMap.containsKey(s.url)) "Không tải được nguồn này$why"
                else if (manual) "Không tải được, dùng bản đã lưu$why" else ""
        } else if (isStreamPlaylist(text)) {
            // Link này là một luồng phát (không phải danh sách): coi như 1 kênh và phát luôn
            chanMap[s.url] = listOf(Ch(s.name, null, s.url))
            srcMsg = ""
            fetchedThisRun.add(s.url)
            if (viewSrc == si && !active) playChannel(si, 0, false, true)
        } else {
            fetchedThisRun.add(s.url)
            if (!(textHash[s.url] == text.hashCode() && chanMap.containsKey(s.url))) {
                val list = parseChannels(text)
                if (list.isEmpty()) {
                    if (!chanMap.containsKey(s.url)) srcMsg = "Nguồn này không có kênh nào đọc được"
                    dlog(s.url + " | không có kênh đọc được | " + text.trim().take(200))
                } else {
                    writeFile(srcCacheFile(s.url), text)
                    textHash[s.url] = text.hashCode()
                    chanMap[s.url] = list
                    srcMsg = ""
                    // Danh sách của nguồn đang phát có thể đổi thứ tự: dò lại vị trí kênh đang xem
                    if (playSrc == si && playUrl != null) {
                        val k = list.indexOfFirst { it.url == playUrl }
                        if (k >= 0) playIdx = k
                    }
                }
            } else if (manual) srcMsg = "Đã là bản mới nhất"
        }
        if (viewSrc == si && !fullscreen) {
            val keep = srcMsg
            render(manual || chanMap[s.url] == null)
            if (keep.isNotEmpty() && chanMap.containsKey(s.url)) {
                ui.postDelayed({ if (srcMsg == keep) { srcMsg = ""; if (!fullscreen) updatePath() } }, 4000L)
            }
        }
    }

    // ---------- Trình duyệt ẩn: đi qua trang trung gian tới trang gốc ----------

    private val extractJs = "(function(){try{var t='';var p=document.querySelector('pre');if(p)t=p.innerText;" +
        "if(!t&&document.body)t=document.body.innerText;" +
        "if(t&&(t.indexOf('#EXTM3U')>=0||t.indexOf('#EXTINF')>=0||/https?:\\/\\/\\S+\\.m3u8/i.test(t)))return t;}catch(e){}return '';})()"

    private val clickJs = "(function(){var re=/(continue|tiếp tục|get link|lấy link|go to link|skip|truy cập|click here|bấm vào đây|nhấn vào đây|open link|download|tải xuống|verify|human)/i;" +
        "var els=document.querySelectorAll('a,button,input[type=submit],input[type=button],div[role=button]');" +
        "for(var i=0;i<els.length;i++){var e=els[i];var t=(e.innerText||e.value||'').trim();" +
        "if(t.length>0&&t.length<40&&re.test(t)&&e.offsetParent!==null){e.click();return 1;}}return 0;})()"

    private fun decodeJs(v: String?): String? {
        if (v == null || v == "null") return null
        return try {
            val o = org.json.JSONTokener(v).nextValue()
            if (o is String) o else null
        } catch (e: Exception) { null }
    }

    private fun cancelWeb() {
        ++webToken
        val w = web ?: return
        web = null
        try { w.stopLoading(); w.loadUrl("about:blank"); w.destroy() } catch (e: Exception) {}
    }

    // Mở trang trong WebView ẩn, đợi trang tự chuyển hướng / thử bấm nút "Tiếp tục", lấy nội dung danh sách kênh.
    // Tối đa ~35 giây. Không vượt được captcha.
    private fun webFetch(pageUrl: String, done: (String?) -> Unit) {
        cancelWeb()
        val my = webToken
        var finished = false
        fun finish(t: String?) {
            if (finished || my != webToken) return
            finished = true
            cancelWeb()
            done(t)
        }
        try {
            val w = WebView(this)
            web = w
            val st = w.settings
            st.javaScriptEnabled = true
            st.domStorageEnabled = true
            st.userAgentString = UA
            st.loadsImagesAutomatically = false
            // Trang trả file (m3u/txt) dưới dạng tải xuống: lấy link tải rồi đọc bằng kết nối thường
            w.setDownloadListener { url, _, _, _, _ ->
                Thread {
                    val t = httpGetText(url)
                    ui.post { if (t != null && !isHtml(t) && t.isNotBlank()) finish(t) }
                }.start()
            }
            w.webViewClient = WebViewClient()
            w.loadUrl(pageUrl)
            val probe = object : Runnable {
                var n = 0
                override fun run() {
                    if (finished || my != webToken) return
                    val cur = web ?: return
                    n++
                    cur.evaluateJavascript(extractJs) { v ->
                        val t = decodeJs(v)
                        if (!t.isNullOrEmpty()) {
                            finish(t)
                        } else if (!finished && my == webToken) {
                            if (n % 2 == 0) cur.evaluateJavascript(clickJs, null)
                            if (n < 14) ui.postDelayed(this, 2500L) else finish(null)
                        }
                    }
                }
            }
            ui.postDelayed(probe, 2500L)
        } catch (e: Throwable) {
            finish(null)
        }
    }

    // Phân tích file danh sách kênh: M3U chuẩn, hoặc từng dòng "Tên|link" / "Tên,link" / chỉ link
    private fun parseChannels(text: String): List<Ch> {
        val out = ArrayList<Ch>()
        var curGroup: String? = null
        var pendName: String? = null
        var pendGroup: String? = null
        val groupRe = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
        for (raw in text.lines()) {
            val line = raw.replace("﻿", "").trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val comma = line.lastIndexOf(',')
                pendName = if (comma >= 0) line.substring(comma + 1).trim().ifEmpty { null } else null
                pendGroup = groupRe.find(line)?.groupValues?.get(1)?.trim()?.ifEmpty { null }
                continue
            }
            if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                pendGroup = line.substring(8).trim().ifEmpty { null }
                continue
            }
            if (line.startsWith("##")) {                       // "## Tên nhóm": các kênh bên dưới thuộc nhóm này
                curGroup = line.trimStart('#').trim().ifEmpty { null }
                continue
            }
            if (line.startsWith("#") || line.startsWith("//")) continue
            if (line.startsWith("[") && line.endsWith("]")) {   // "[Tên nhóm]"
                curGroup = line.substring(1, line.length - 1).trim().ifEmpty { null }
                continue
            }
            val i1 = line.indexOf("http://", ignoreCase = true)
            val i2 = line.indexOf("https://", ignoreCase = true)
            val idx = listOf(i1, i2).filter { it >= 0 }.minOrNull() ?: -1
            var url: String
            var name = ""
            if (idx >= 0) {
                url = line.substring(idx).trim()
                name = line.substring(0, idx).trim().trimEnd('|', ',', ';', '=', '\t', ' ').trim()
            } else continue
            // Link có dạng "link|User-Agent=..." thì chỉ lấy phần link
            val bar = url.indexOf('|')
            if (bar > 0) url = url.substring(0, bar).trim()
            if (name.isEmpty()) name = pendName ?: ("Kênh " + (out.size + 1))
            out.add(Ch(name, pendGroup ?: curGroup, url))
            pendName = null
            pendGroup = null
        }
        return out
    }

    private fun autoPlayLast() {
        if (autoStarted) return
        if (srcs.isEmpty()) return
        autoStarted = true
        if (!AUTOPLAY_LAST) return
        val lastSrc = prefs.getString("last_src", null) ?: return
        val lastUrl = prefs.getString("last_url", null) ?: return
        val si = srcs.indexOfFirst { it.url == lastSrc }
        if (si < 0) return
        Thread {
            var list = chanMap[lastSrc]
            if (list == null) {
                val cached = readFile(srcCacheFile(lastSrc))
                list = if (cached != null) parseChannels(cached) else null
                if (list != null && cached != null) {
                    val l2: List<Ch> = list
                    runOnUiThread { if (!chanMap.containsKey(lastSrc)) { chanMap[lastSrc] = l2; textHash[lastSrc] = cached.hashCode() } }
                }
            }
            val k = list?.indexOfFirst { it.url == lastUrl } ?: -1
            if (k >= 0) {
                runOnUiThread {
                    if (!isFinishing && !active && chanMap.containsKey(lastSrc)) playChannel(srcs.indexOfFirst { it.url == lastSrc }, k, false, true)
                }
            }
        }.start()
    }

    // ---------- Hiển thị danh sách ----------

    private fun buildItems(): List<Entry> {
        val out = ArrayList<Entry>()
        if (viewSrc < 0) {
            out.add(Entry("Cập nhật danh sách nguồn", KIND_REFRESH))
            srcs.forEachIndexed { i, s -> out.add(Entry(s.name, KIND_SRC, i)) }
            if (srcs.isEmpty() && listMsg.isNotEmpty()) out.add(Entry(listMsg, KIND_INFO))
            return out
        }
        val list = chans(viewSrc)
        val g = viewGroup
        out.add(Entry(".. (lên thư mục cha)", KIND_UP))
        if (g == null) {
            out.add(Entry("Cập nhật nguồn này", KIND_REFRESH))
            list.mapNotNull { it.group }.distinct().forEach { out.add(Entry(it, KIND_GROUP, group = it)) }
            list.forEachIndexed { i, c -> if (c.group == null) out.add(Entry("${i + 1}. ${c.name}", KIND_CH, i)) }
        } else {
            list.forEachIndexed { i, c -> if (c.group == g) out.add(Entry("${i + 1}. ${c.name}", KIND_CH, i)) }
        }
        if (list.isEmpty() && srcMsg.isNotEmpty()) out.add(Entry(srcMsg, KIND_INFO))
        return out
    }

    private fun updatePath() {
        val sName = srcs.getOrNull(viewSrc)?.name
        val msg = if (viewSrc < 0) listMsg else srcMsg
        val base = when {
            viewSrc < 0 -> if (srcs.isEmpty()) "Nguồn kênh" else "Nguồn kênh (${srcs.size})"
            viewGroup != null -> "$sName / $viewGroup"
            chans(viewSrc).isNotEmpty() -> "$sName (${chans(viewSrc).size} kênh)"
            else -> sName ?: ""
        }
        pathV.text = if (msg.isNotEmpty() && (viewSrc < 0 && srcs.isNotEmpty() || viewSrc >= 0 && chans(viewSrc).isNotEmpty())) "$base - $msg" else base
    }

    // pick: chọn mục sẽ được đưa tiêu điểm vào (mặc định: kênh đang phát, nếu không có thì mục đầu tiên dùng được)
    private fun render(focus: Boolean = false, pick: ((Entry) -> Boolean)? = null) {
        val items = buildItems()
        adapter.items = items
        adapter.notifyDataSetChanged()
        updatePath()
        var idx = -1
        if (pick != null) idx = items.indexOfFirst(pick)
        if (idx < 0 && playIdx >= 0 && viewSrc == playSrc) idx = items.indexOfFirst { it.kind == KIND_CH && it.idx == playIdx }
        if (idx < 0) idx = items.indexOfFirst { it.kind == KIND_SRC || it.kind == KIND_GROUP || it.kind == KIND_CH }
        if (idx < 0) idx = if (items.size > 1) 1 else 0
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
        if (focus) {
            rv.postDelayed({
                if (!fullscreen) rv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
            }, 100)
        }
    }

    private fun goUp() {
        if (viewGroup != null) {
            val g = viewGroup
            viewGroup = null
            render(true) { it.kind == KIND_GROUP && it.group == g }
        } else if (viewSrc >= 0) {
            val s = viewSrc
            viewSrc = -1
            render(true) { it.kind == KIND_SRC && it.idx == s }
        }
    }

    private fun onEntry(e: Entry) {
        when (e.kind) {
            KIND_UP -> goUp()
            KIND_SRC -> openSource(e.idx, false)
            KIND_GROUP -> { viewGroup = e.group; render(true) }
            KIND_REFRESH -> if (viewSrc < 0) refreshList(readFile(mainCacheFile()), true) else loadSource(viewSrc, true)
            KIND_CH -> {
                if (viewSrc == playSrc && e.idx == playIdx && active) enterFullscreen()
                else playChannel(viewSrc, e.idx, false, true)
            }
            else -> {}
        }
    }

    // ---------- Phát kênh ----------

    private class Resolved(val url: String, val hls: Boolean, val ua: String, val good: Boolean = true)

    private val streamUas = listOf(UA, "VLC/3.0.18 LibVLC/3.0.18", "okhttp/4.9.3")

    // Thử lần lượt vài kiểu User-Agent: một số máy chủ đưa trình duyệt về trang chủ, chỉ trả luồng cho trình phát
    private fun resolveUrl(start: String): Resolved {
        var first: Resolved? = null
        for (ua in streamUas) {
            val r = resolveOnce(start, ua)
            if (first == null) first = r
            if (r.good) return r
        }
        return first!!
    }

    // Đi theo các lần chuyển hướng để lấy link thật và biết có phải m3u8 không
    private fun resolveOnce(start: String, ua: String): Resolved {
        var u = start
        for (hop in 0 until 8) {
            var c: HttpURLConnection? = null
            try {
                c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", ua)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: break
                    u = URL(URL(u), loc).toString()
                    continue
                }
                val byName = u.lowercase().substringBefore('?').endsWith(".m3u8")
                if (code != 200) return Resolved(u, byName, ua, false)
                val ct = (c.contentType ?: "").lowercase()
                val buf = ByteArray(2048)
                val n = try { c.inputStream.read(buf) } catch (e: Exception) { -1 }
                val head = if (n > 0) String(buf, 0, n, Charsets.ISO_8859_1) else ""
                if (isHtml(head)) return Resolved(u, byName, ua, false)    // bị đưa về trang web
                val hls = byName || ct.contains("mpegurl") || head.contains("#EXTM3U")
                return Resolved(u, hls, ua, true)
            } catch (e: Exception) {
                // Lỗi mạng: thử kiểu UA khác cũng không khá hơn, để bộ phát tự thử
                return Resolved(u, u.lowercase().contains(".m3u8"), ua, true)
            } finally {
                try { c?.disconnect() } catch (e: Exception) {}
            }
        }
        return Resolved(u, u.lowercase().contains(".m3u8"), ua, false)
    }

    private fun playChannel(si: Int, i: Int, auto: Boolean = false, goFull: Boolean = false) {
        val c = chans(si).getOrNull(i) ?: return
        playSrc = si
        playIdx = i
        playUrl = c.url
        playSrcUrl = srcs[si].url
        ui.removeCallbacks(retryR)
        if (!auto) {
            retryCount = 0
            prefs.edit().putString("last_src", srcs[si].url).putString("last_url", c.url).apply()
        }
        if (goFull && !fullscreen) enterFullscreen()
        ready = false
        vw = 0f
        vh = 0f
        placeholder.visibility = View.GONE
        titleV.text = c.name
        artistV.text = srcs[si].name + (if (c.group != null) " / " + c.group else "")
        statusV.text = if (auto && retryCount > 0) "Mất kết nối, thử lại ($retryCount/$MAX_RETRY)..." else "Đang kết nối..."
        active = true
        if (ffmpegOn && passthrough) {
            // Chế độ truyền thẳng: quay về bộ phát thường để AC3/DTS... được gửi thẳng ra loa/ampli
            player.release()
            buildPlayer(false)
            getSharedPreferences("crash", MODE_PRIVATE).edit().remove("ffmpeg").commit()
        }
        player.stop()
        val token = ++playToken
        updatePlayIcon()
        if (!auto) {
            adapter.notifyDataSetChanged()
            if (fullscreen) showHud("${i + 1}. ${c.name}", 2500L)
        }
        Thread {
            val r = resolveUrl(c.url)
            runOnUiThread { if (token == playToken) startStream(r) }
        }.start()
    }

    private fun startStream(r: Resolved) {
        try {
            streamUa = r.ua
            val b = MediaItem.Builder().setUri(Uri.parse(r.url))
            if (r.hls) b.setMimeType(MimeTypes.APPLICATION_M3U8)
            player.setMediaItem(b.build())
            player.prepare()
            player.play()
        } catch (e: Throwable) {
            scheduleRetry()
        }
        updatePlayIcon()
    }

    // Mất mạng / luồng bị ngắt: tự kết nối lại tối đa 5 lần, cách nhau 4 giây
    private fun scheduleRetry() {
        if (playIdx < 0) return
        if (retryCount >= MAX_RETRY) {
            active = false
            statusV.text = "Không phát được kênh này - bấm Play để thử lại, hoặc đổi kênh"
            ++playToken
            player.stop()
            ready = false
            updatePlayIcon()
            return
        }
        retryCount++
        statusV.text = "Mất kết nối, thử lại ($retryCount/$MAX_RETRY)..."
        ui.removeCallbacks(retryR)
        ui.postDelayed(retryR, 4000L)
    }

    private fun stopStream(msg: String) {
        ++playToken
        ui.removeCallbacks(retryR)
        retryCount = 0
        active = false
        ready = false
        try { player.stop() } catch (e: Exception) {}
        statusV.text = msg
        updatePlayIcon()
    }

    // Chuyển kênh trong phạm vi nguồn của kênh đang xem
    private fun step(dir: Int) {
        val si = if (playSrc >= 0) playSrc else viewSrc
        val list = chans(si)
        if (list.isEmpty()) return
        val n = if (playIdx < 0 || playSrc != si) 0 else (playIdx + dir + list.size) % list.size
        playChannel(si, n)
    }

    private fun togglePlay() {
        if (playIdx < 0) {
            toast("Chọn một kênh ở danh sách bên phải")
            return
        }
        // Truyền hình trực tiếp: "tạm dừng" = ngắt luồng, bấm lại = kết nối lại (xem đúng thời điểm hiện tại)
        if (active) {
            stopStream("Đã dừng")
            if (fullscreen) showHud("Đã dừng")
        } else {
            playChannel(playSrc, playIdx, false)
        }
    }

    private fun updatePlayIcon() {
        btnPlay.setImageResource(if (active) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- Adapter ----------

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val text: TextView = v.findViewById(R.id.text)
    }

    inner class Adapter : RecyclerView.Adapter<VH>() {
        var items: List<Entry> = emptyList()

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_entry, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            h.text.text = e.label
            h.icon.setImageResource(when (e.kind) {
                KIND_CH, KIND_INFO -> R.drawable.ic_tv
                KIND_REFRESH -> R.drawable.ic_refresh
                else -> R.drawable.ic_folder
            })
            val playing = (e.kind == KIND_CH && viewSrc == playSrc && e.idx == playIdx) ||
                (e.kind == KIND_SRC && e.idx == playSrc && playIdx >= 0)
            val c = 0xFFFFFFFF.toInt()   // toàn bộ danh sách chữ trắng; kênh/nguồn đang xem phân biệt bằng chữ đậm
            h.text.setTextColor(c)
            h.text.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
            h.icon.setColorFilter(c)
            h.itemView.setOnClickListener { onEntry(e) }
        }
    }
}
