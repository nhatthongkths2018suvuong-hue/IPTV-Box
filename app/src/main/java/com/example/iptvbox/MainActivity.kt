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
import java.net.URL
import kotlin.math.min

class Ch(val name: String, val group: String?, val url: String)

class Entry(val label: String, val kind: Int, val idx: Int = -1, val group: String? = null)

class MainActivity : AppCompatActivity() {

    companion object {
        // Link file txt danh sách kênh trên Google Drive (đặt chế độ "Bất kỳ ai có đường liên kết").
        // Muốn đổi link mà không cần build lại: tạo file source.txt trong Android/data/com.example.iptvbox/files/
        // và ghi link mới vào dòng đầu tiên.
        const val LIST_URL = "https://drive.google.com/file/d/16lo5B7kaqmre5z4kh-sfWVRoi-r0xlmj/view?usp=sharing"
        const val AUTOPLAY_LAST = true      // mở app là tự phát lại kênh xem lần cuối
        const val UA = "Mozilla/5.0 (Linux; Android 6.0.1) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Safari/537.36"
        const val KIND_UP = 0
        const val KIND_GROUP = 1
        const val KIND_CH = 2
        const val KIND_REFRESH = 3
        const val KIND_INFO = 4
        val FF_MIMES = setOf(
            MimeTypes.AUDIO_AC3, MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_DTS, MimeTypes.AUDIO_DTS_HD, MimeTypes.AUDIO_DTS_EXPRESS, MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_MPEG_L2
        )
        const val OSD_MS = 6000L
        const val MAX_RETRY = 5
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

    private var channels: List<Ch> = emptyList()
    private var viewGroup: String? = null     // null = màn hình gốc
    private var curIdx = -1                   // kênh đang chọn (vị trí trong channels)
    private var curUrl: String? = null
    private var active = false                // đang xem (hoặc đang kết nối) một kênh; false = đã dừng
    private var ready = false
    private var resumeOnStart = false
    private var autoStarted = false
    private var listMsg = ""
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
    private val retryR = Runnable { if (curIdx >= 0) playChannel(curIdx, true) }
    private val numCommitR = Runnable {
        val n = numBuf.toIntOrNull()
        numBuf = ""
        if (n != null) {
            if (n in 1..channels.size) playChannel(n - 1) else showHud("Không có kênh $n")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

        buildPlayer(false)

        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        videoHost.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyAspect() }
        videoHost.setOnClickListener { if (curIdx >= 0) enterFullscreen() }

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

        // Hiện ngay danh sách đã lưu lần trước (nếu có), rồi cập nhật từ mạng ở nền
        val cached = readCache()
        if (cached != null) {
            channels = parseChannels(cached)
            listMsg = ""
            render(true)
            autoPlayLast()
        } else {
            listMsg = "Đang tải danh sách kênh..."
            render(true)
        }
        refreshList(cached, false)
    }

    // ---------- Vòng đời ----------

    override fun onStart() {
        super.onStart()
        if (resumeOnStart && curIdx >= 0) {
            resumeOnStart = false
            playChannel(curIdx, true)
        }
    }

    override fun onStop() {
        // Không phát nền: thoát ra ngoài thì ngắt luồng, quay lại sẽ tự kết nối lại
        resumeOnStart = curIdx >= 0 && active
        stopStream("Đã dừng")
        getSharedPreferences("crash", MODE_PRIVATE).edit().remove("ffmpeg").apply()
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
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
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(UA)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15000)
            .setReadTimeoutMs(15000)
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
        if (ffmpegOn || curIdx < 0 || prefs.getBoolean("ffmpeg_blocked", false)) return
        getSharedPreferences("crash", MODE_PRIVATE).edit().putBoolean("ffmpeg", true).commit()
        player.release()
        ready = false
        buildPlayer(true)
        playChannel(curIdx, true)
    }

    // Đổi chế độ âm thanh: dựng lại bộ phát và nối lại kênh đang xem
    private fun reloadCurrent() {
        val was = curIdx >= 0 && active
        ++playToken
        player.release()
        ready = false
        buildPlayer(false)
        getSharedPreferences("crash", MODE_PRIVATE).edit().remove("ffmpeg").commit()
        if (was) playChannel(curIdx, true) else updatePlayIcon()
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
                if (viewGroup != null) {
                    leaveGroup()
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
        // Kênh vẫn phát tiếp ở khung nhỏ. Dựng lại danh sách (phòng khi vừa cập nhật) và đưa tiêu điểm về kênh đang xem.
        if (curIdx >= 0) viewGroup = channels.getOrNull(curIdx)?.group
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

    // ---------- Danh sách kênh: tải, lưu, phân tích ----------

    private fun cacheFile() = File(filesDir, "channels.txt")

    private fun readCache(): String? = try {
        val f = cacheFile()
        if (f.isFile) f.readText(Charsets.UTF_8) else null
    } catch (e: Exception) { null }

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

    private fun httpGetText(start: String): String? {
        var u = start
        for (hop in 0 until 8) {
            var c: HttpURLConnection? = null
            try {
                c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 15000
                c.readTimeout = 15000
                c.setRequestProperty("User-Agent", UA)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: return null
                    u = URL(URL(u), loc).toString()
                    continue
                }
                if (code != 200) return null
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                c.inputStream.use { ins ->
                    while (out.size() < 4_000_000) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
                return String(out.toByteArray(), Charsets.UTF_8).replace("﻿", "")
            } catch (e: Exception) {
                return null
            } finally {
                try { c?.disconnect() } catch (e: Exception) {}
            }
        }
        return null
    }

    private fun fetchList(): String? {
        for (u in sourceCandidates(listSource())) {
            val t = httpGetText(u) ?: continue
            val head = t.trimStart().take(20).lowercase()
            if (t.isBlank() || head.startsWith("<!doctype") || head.startsWith("<html")) continue
            return t
        }
        return null
    }

    // Tải ở nền. Có thay đổi thì lưu lại và cập nhật danh sách; không tải được thì giữ danh sách cũ.
    private fun refreshList(old: String?, manual: Boolean) {
        if (manual) {
            listMsg = "Đang cập nhật..."
            updatePath()
        }
        Thread {
            val text = fetchList()
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                var changed = false
                listMsg = ""
                if (text == null) {
                    if (channels.isEmpty()) listMsg = "Không tải được danh sách kênh"
                    else if (manual) listMsg = "Không tải được, dùng danh sách đã lưu"
                } else if (text != old) {
                    try { cacheFile().writeText(text, Charsets.UTF_8) } catch (e: Exception) {}
                    applyChannels(parseChannels(text))
                    changed = true
                } else if (manual) {
                    listMsg = "Đã là bản mới nhất"
                }
                if (!fullscreen) {
                    if (changed || channels.isEmpty()) render(manual) else updatePath()
                }
                if (listMsg.isNotEmpty() && channels.isNotEmpty()) {
                    ui.postDelayed({ listMsg = ""; if (!fullscreen) updatePath() }, 4000L)
                }
                if (!autoStarted) autoPlayLast()
            }
        }.start()
    }

    private fun applyChannels(list: List<Ch>) {
        channels = list
        curIdx = if (curUrl != null) list.indexOfFirst { it.url == curUrl } else -1
        if (viewGroup != null && list.none { it.group == viewGroup }) viewGroup = null
    }

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
            } else if (!line.contains(' ') && line.contains('.') && line.contains('/')) {
                url = "http://$line"                           // dòng thiếu http:// (vd: abc.com/live/index.m3u8)
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
        if (channels.isEmpty()) return
        autoStarted = true
        if (!AUTOPLAY_LAST) return
        val last = prefs.getString("last_url", null) ?: return
        val i = channels.indexOfFirst { it.url == last }
        if (i >= 0) {
            playChannel(i, false, true)
        }
    }

    // ---------- Hiển thị danh sách ----------

    private fun buildItems(): List<Entry> {
        val out = ArrayList<Entry>()
        val g = viewGroup
        if (g == null) {
            out.add(Entry("Cập nhật danh sách kênh", KIND_REFRESH))
            channels.mapNotNull { it.group }.distinct().forEach { out.add(Entry(it, KIND_GROUP, group = it)) }
            channels.forEachIndexed { i, c -> if (c.group == null) out.add(Entry("${i + 1}. ${c.name}", KIND_CH, i)) }
        } else {
            out.add(Entry(".. (lên thư mục cha)", KIND_UP))
            channels.forEachIndexed { i, c -> if (c.group == g) out.add(Entry("${i + 1}. ${c.name}", KIND_CH, i)) }
        }
        if (channels.isEmpty() && listMsg.isNotEmpty()) out.add(Entry(listMsg, KIND_INFO))
        return out
    }

    private fun updatePath() {
        pathV.text = when {
            viewGroup != null -> "Danh sách kênh / $viewGroup"
            channels.isEmpty() -> if (listMsg.isNotEmpty()) listMsg else "Danh sách kênh"
            listMsg.isNotEmpty() -> "Danh sách kênh (${channels.size}) - $listMsg"
            else -> "Danh sách kênh (${channels.size})"
        }
    }

    private fun render(focus: Boolean = false) {
        val items = buildItems()
        adapter.items = items
        adapter.notifyDataSetChanged()
        updatePath()
        var idx = if (curIdx >= 0) items.indexOfFirst { it.kind == KIND_CH && it.idx == curIdx } else -1
        if (idx < 0) idx = if (items.size > 1) 1 else 0
        (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
        if (focus) {
            rv.postDelayed({
                if (!fullscreen) rv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus()
            }, 100)
        }
    }

    private fun leaveGroup() {
        viewGroup = null
        render(true)
    }

    private fun onEntry(e: Entry) {
        when (e.kind) {
            KIND_UP -> leaveGroup()
            KIND_GROUP -> { viewGroup = e.group; render(true) }
            KIND_REFRESH -> refreshList(readCache(), true)
            KIND_CH -> {
                if (e.idx == curIdx && active) enterFullscreen()
                else playChannel(e.idx, false, true)
            }
            else -> {}
        }
    }

    // ---------- Phát kênh ----------

    private class Resolved(val url: String, val hls: Boolean)

    // Đi theo các lần chuyển hướng (bit.ly...) để lấy link thật và biết có phải m3u8 không
    private fun resolveUrl(start: String): Resolved {
        var u = start
        for (hop in 0 until 8) {
            var c: HttpURLConnection? = null
            try {
                c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.setRequestProperty("User-Agent", UA)
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: break
                    u = URL(URL(u), loc).toString()
                    continue
                }
                val byName = u.lowercase().substringBefore('?').endsWith(".m3u8")
                if (code != 200) return Resolved(u, byName)
                val ct = (c.contentType ?: "").lowercase()
                var hls = byName || ct.contains("mpegurl")
                if (!hls) {
                    val buf = ByteArray(2048)
                    val n = c.inputStream.read(buf)
                    if (n > 0) hls = String(buf, 0, n, Charsets.ISO_8859_1).contains("#EXTM3U")
                }
                return Resolved(u, hls)
            } catch (e: Exception) {
                return Resolved(u, u.lowercase().contains(".m3u8"))
            } finally {
                try { c?.disconnect() } catch (e: Exception) {}
            }
        }
        return Resolved(u, u.lowercase().contains(".m3u8"))
    }

    private fun playChannel(i: Int, auto: Boolean = false, goFull: Boolean = false) {
        val c = channels.getOrNull(i) ?: return
        val old = curIdx
        curIdx = i
        curUrl = c.url
        ui.removeCallbacks(retryR)
        if (!auto) {
            retryCount = 0
            prefs.edit().putString("last_url", c.url).apply()
        }
        if (goFull && !fullscreen) enterFullscreen()
        ready = false
        vw = 0f
        vh = 0f
        placeholder.visibility = View.GONE
        titleV.text = c.name
        artistV.text = c.group ?: ""
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
            adapter.refresh(old, i)
            if (fullscreen) showHud("${i + 1}. ${c.name}", 2500L)
        }
        Thread {
            val r = resolveUrl(c.url)
            runOnUiThread { if (token == playToken) startStream(r) }
        }.start()
    }

    private fun startStream(r: Resolved) {
        try {
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
        if (curIdx < 0) return
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

    private fun step(dir: Int) {
        if (channels.isEmpty()) return
        val n = if (curIdx < 0) 0 else (curIdx + dir + channels.size) % channels.size
        playChannel(n)
    }

    private fun togglePlay() {
        if (curIdx < 0) {
            toast("Chọn một kênh ở danh sách bên phải")
            return
        }
        // Truyền hình trực tiếp: "tạm dừng" = ngắt luồng, bấm lại = kết nối lại (xem đúng thời điểm hiện tại)
        if (active) {
            stopStream("Đã dừng")
            if (fullscreen) showHud("Đã dừng")
        } else {
            playChannel(curIdx, false)
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
            val playing = e.kind == KIND_CH && e.idx == curIdx
            val c = 0xFFFFFFFF.toInt()   // toàn bộ danh sách chữ trắng; kênh đang xem phân biệt bằng chữ đậm
            h.text.setTextColor(c)
            h.text.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
            h.icon.setColorFilter(c)
            h.itemView.setOnClickListener { onEntry(e) }
        }

        fun refresh(a: Int, b: Int) {
            items.forEachIndexed { i, e ->
                if (e.kind == KIND_CH && (e.idx == a || e.idx == b)) notifyItemChanged(i)
            }
        }
    }
}
