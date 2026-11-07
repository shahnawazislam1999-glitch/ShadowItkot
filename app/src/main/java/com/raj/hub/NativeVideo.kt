package com.raj.hub

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.Shader
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.InputType
import android.text.TextPaint
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.PopupWindow
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.TextView
import android.util.TypedValue
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SniffFailure
import androidx.media3.extractor.TrackOutput
import androidx.media3.ui.PlayerView
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

/**
 * Native video player (Media3 ExoPlayer) for the web pages in this app.
 *
 *  - MKV / MP4 / WebM etc. are played straight from the file. Nothing is converted or re-encoded,
 *    and nothing is loaded into memory: ExoPlayer reads only the parts it needs (works for multi-GB files).
 *  - The picked video is remembered: first through a persistable Android URI permission (no copy),
 *    and only if Android refuses that, through a byte-exact copy in the app's private storage.
 *  - The page keeps using its normal <video> calls; a small script in the page forwards them to [cmd]
 *    and receives player state back through window.__nvOn(...).
 */
/** Full screen gestures: width of the left and the right tap zone (fraction of the screen width). The rest is the middle. */
private const val FS_SIDE_ZONE = 0.30f

/** Full screen gestures: sliding a finger across the whole screen width moves the video by this much. */
private const val FS_SWIPE_FULL_WIDTH_MS = 120_000L

/** Full screen button strip: how many icons are visible at once (the others are reached by sliding the strip sideways). */
private const val FS_STRIP_VISIBLE = 5

/** Full screen button strip: size of one icon cell in dp. */
private const val FS_ITEM_DP = 46

@androidx.annotation.OptIn(UnstableApi::class)
class NativeVideo(
    private val ctx: Context,
    private val appId: String,
    private val web: View,
    private val activityProvider: () -> AppCompatActivity?,
    private val evalJs: (String) -> Unit
) {
    /** Same as the old activity, only it also survives when the screen is closed. */
    private val act: Context = ctx

    private class Source(val uri: Uri, val name: String, val size: Long, val stamp: Long, val kind: String)

    /** Add this view above the WebView (it is hidden until a video is opened). */
    val panel: FrameLayout = LayoutInflater.from(act).inflate(R.layout.panel_native_video, null, false) as FrameLayout

    private val playerView: PlayerView = panel.findViewById(R.id.nv_player)
    private val tiles = TextTileView(act)
    private val prefs: SharedPreferences = act.getSharedPreferences("rajhub", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var exo: ExoPlayer? = null
    private var loadedKey: String? = null
    private var hasMedia = false
    private var wantVisible = false
    private var fullscreen = false
    private var released = false
    private var muted = false

    private var lastSeq = 0
    private var seekPending = false
    private var seekDone = 0
    private var tickCount = 0
    private var copyGen = 0

    // full screen: clean picture with the subtitle drawn on it + three dim buttons (previous / play-pause / next)
    private val subtitleText = TextView(act)
    // [FS_STRIP] horizontal scrolling strip (top right): prev, play, next, unlink, hide | bookmark, audio, repeat, favorite
    private val fsStrip = HorizontalScrollView(act)
    private val fsControls = LinearLayout(act)
    private val fsPrev = ImageView(act)
    private val fsPlay = ImageView(act)
    private val fsNext = ImageView(act)
    private val fsUnlink = ImageView(act)
    private val fsHide = ImageView(act)
    private val fsBookmark = ImageView(act)
    private val fsAudio = ImageView(act)
    private val fsRepeatWrap = FrameLayout(act)
    private val fsRepeat = ImageView(act)
    private val fsRepeatBadge = TextView(act)
    private val fsStar = ImageView(act)
    // [FS_SUB2] second subtitle (translated SRT): toggle button in the strip + a line below the main subtitle
    private val fsSub2 = ImageView(act)
    private val subBox = LinearLayout(act)
    private val sub2Text = TextView(act)
    private var sub2Str = ""
    private var sub2On = false
    private var sub2Has = false
    // [FS_MORE] three dots (top right) -> small menu (Delay sentence)
    private val fsMore = ImageView(act)
    private var morePopup: PopupWindow? = null

    // state of the current sentence, sent by the page (see setFlags)
    private var flagStar = false
    private var flagBookmark = false
    private var flagCustomRepeat = 0      // repeat count set for this sentence only (0 = none)
    private var flagGlobalRepeat = 0      // repeat count for every sentence
    private var flagDelay = 0.0           // pause between sentences, seconds
    private var flagCurIdx = -1           // page's index (0 based) of the current sentence

    // full screen: everything except picture + subtitle stays hidden until the screen is tapped, then hides again by itself
    private val tapLayer = View(act)
    private val seekBar = FsSlider(act)
    private var unlinked = false          // "free play": the page stops repeating sentences, the film just plays
    private var fsUiVisible = false
    private var uiPinned = false          // double tap: controls stay on screen until the next double tap
    private var scrubbing = false
    private var swiping = false           // finger is down and sliding left / right on the picture
    private var swipeStartMs = 0L
    private var swipeTargetMs = 0L
    private val seekHint = TextView(act)
    private var lastScrubAt = 0L
    private var audioChipWanted = false
    private var ctlWanted = true
    private val hideRunnable = Runnable { hideFsUi() }
    private val uiTick = object : Runnable {
        override fun run() {
            if (released || !fsUiVisible) return
            refreshSliders()
            main.postDelayed(this, 250)
        }
    }

    private var videoAspect = 16f / 9f
    private var insetL = 0
    private var insetT = 0
    private var insetR = 0

    private var fxMirror = false
    private var fxBlurCssPx = 0f
    private var fxOldTv = false

    // audio language switching (dual audio MKV, e.g. English / Hindi)
    private var audioList: List<Tracks.Group> = emptyList()
    private var audioPrefApplied = false
    private val audioChip = ImageView(act)
    private var skippedAudioToastShown = false
    private var lastWarnMsg = ""

    // automatic recovery after a read error (see tryRecover)
    private var errRetries = 0
    private var errRetryAtMs = -1L
    private var recovering = false
    private var recoverWantsPlay = false

    init {
        panel.visibility = View.GONE
        panel.addView(tiles, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // invisible layer that catches the "tap to show the controls" touch while full screen
        tapLayer.visibility = View.GONE
        setupFsGestures()
        panel.addView(tapLayer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        // small time read-out in the middle while sliding (touches pass through)
        seekHint.setTextColor(Color.WHITE)
        seekHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        seekHint.setTypeface(seekHint.typeface, android.graphics.Typeface.BOLD)
        seekHint.gravity = Gravity.CENTER
        seekHint.setPadding(dp(18), dp(8), dp(18), dp(8))
        seekHint.background = GradientDrawable().apply {
            setColor(Color.argb(150, 0, 0, 0))
            cornerRadius = dp(14).toFloat()
        }
        seekHint.visibility = View.GONE
        panel.addView(seekHint, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        // only a small speaker icon, very faint (10% opacity) so it does not distract from the picture
        audioChip.setImageResource(R.drawable.ic_audio_track)
        audioChip.alpha = 0.10f
        audioChip.setPadding(dp(10), dp(10), dp(10), dp(10))
        audioChip.contentDescription = "Audio language"
        audioChip.visibility = View.GONE
        audioChip.setOnClickListener { showAudioDialog() }
        val chipLp = FrameLayout.LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END)
        chipLp.setMargins(0, dp(4), dp(4), 0)
        panel.addView(audioChip, chipLp)

        // on-video subtitle (touches pass through to the player)
        subtitleText.setTextColor(Color.parseColor("#FFE24A"))
        subtitleText.setTypeface(subtitleText.typeface, android.graphics.Typeface.BOLD)
        subtitleText.gravity = Gravity.CENTER
        subtitleText.setShadowLayer(7f, 0f, 1.5f, Color.BLACK)
        subtitleText.setLineSpacing(0f, 1.05f)
        subtitleText.visibility = View.GONE
        // [FS_SUB2] second subtitle line, drawn right below the main subtitle
        sub2Text.setTextColor(Color.parseColor("#BDE7FF"))
        sub2Text.setTypeface(sub2Text.typeface, android.graphics.Typeface.BOLD)
        sub2Text.gravity = Gravity.CENTER
        sub2Text.setShadowLayer(7f, 0f, 1.5f, Color.BLACK)
        sub2Text.setLineSpacing(0f, 1.05f)
        sub2Text.visibility = View.GONE
        subBox.orientation = LinearLayout.VERTICAL
        subBox.gravity = Gravity.CENTER_HORIZONTAL
        subBox.addView(subtitleText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        subBox.addView(sub2Text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        panel.addView(subBox, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))

        // [FS_STRIP] very dim buttons in a horizontal strip (top right, left of the three dots).
        // Only FS_STRIP_VISIBLE icons fit; slide the strip sideways to reach the rest.
        fsControls.orientation = LinearLayout.HORIZONTAL
        fsStrip.visibility = View.GONE
        fsStrip.isHorizontalScrollBarEnabled = false
        fsStrip.overScrollMode = View.OVER_SCROLL_NEVER
        fsPrev.setImageResource(R.drawable.ic_prev)
        fsPlay.setImageResource(R.drawable.ic_play)
        fsNext.setImageResource(R.drawable.ic_next)
        fsPrev.contentDescription = "Previous sentence"
        fsPlay.contentDescription = "Play or pause"
        fsNext.contentDescription = "Next sentence"
        fsUnlink.setImageResource(R.drawable.ic_unlink)
        fsHide.setImageResource(R.drawable.ic_eye_off)
        fsUnlink.contentDescription = "Unlink subtitles: play the film normally"
        fsHide.contentDescription = "Hide this subtitle"
        fsBookmark.setImageResource(R.drawable.ic_bookmark_border)
        fsAudio.setImageResource(R.drawable.ic_audio_track)
        fsRepeat.setImageResource(R.drawable.ic_repeat)
        fsStar.setImageResource(R.drawable.ic_star_border)
        fsSub2.setImageResource(R.drawable.ic_translate)
        fsMore.setImageResource(R.drawable.ic_more_vert)
        fsBookmark.contentDescription = "Bookmark this subtitle"
        fsAudio.contentDescription = "Audio track"
        fsRepeatWrap.contentDescription = "How many times each sentence repeats"
        fsStar.contentDescription = "Add this subtitle to favorites"
        fsSub2.contentDescription = "Second subtitle on / off"
        fsMore.contentDescription = "More"
        for (b in listOf(fsPrev, fsPlay, fsNext, fsUnlink, fsHide, fsBookmark, fsAudio, fsRepeat, fsStar, fsSub2, fsMore)) {
            b.alpha = 0.35f
            b.setPadding(dp(11), dp(11), dp(11), dp(11))
        }
        // repeat icon with a small number in the corner (how many times each sentence repeats)
        fsRepeatBadge.setTextColor(Color.WHITE)
        fsRepeatBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        fsRepeatBadge.setTypeface(fsRepeatBadge.typeface, android.graphics.Typeface.BOLD)
        fsRepeatBadge.setShadowLayer(3f, 0f, 1f, Color.BLACK)
        fsRepeatBadge.alpha = 0.75f
        fsRepeatBadge.visibility = View.GONE
        fsRepeatWrap.addView(fsRepeat, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val badgeLp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END)
        badgeLp.setMargins(0, 0, dp(5), dp(4))
        fsRepeatWrap.addView(fsRepeatBadge, badgeLp)
        val itemPx = dp(FS_ITEM_DP)
        for (v in listOf<View>(fsPrev, fsPlay, fsNext, fsUnlink, fsHide, fsBookmark, fsAudio, fsRepeatWrap, fsStar, fsSub2)) {
            fsControls.addView(v, LinearLayout.LayoutParams(itemPx, itemPx))
        }
        fsStrip.addView(fsControls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fsPrev.setOnClickListener { evalJs("window.__rajMedia&&window.__rajMedia.cmd('prev')"); poke() }
        fsPlay.setOnClickListener { evalJs("window.__rajMedia&&window.__rajMedia.cmd('toggle')"); poke() }
        fsNext.setOnClickListener { evalJs("window.__rajMedia&&window.__rajMedia.cmd('next')"); poke() }
        fsUnlink.setOnClickListener { setUnlinked(!unlinked); poke() }
        fsHide.setOnClickListener { evalJs("window.__rajMedia&&window.__rajMedia.cmd('hide')"); poke() }
        fsBookmark.setOnClickListener { evalJs("window.__rajMedia&&window.__rajMedia.cmd('bookmark')"); poke() }
        fsStar.setOnClickListener { evalJs("window.__rajMedia&&window.__rajMedia.cmd('star')"); poke() }
        fsAudio.setOnClickListener {
            if (audioList.size >= 2) {
                main.removeCallbacks(hideRunnable)
                showAudioDialog()
            } else {
                Toast.makeText(act.applicationContext, "This video has only one audio track", Toast.LENGTH_SHORT).show()
                poke()
            }
        }
        fsSub2.setOnClickListener {
            if (!sub2Has) {
                Toast.makeText(act.applicationContext, "Upload a second subtitle (SRT) in the page first", Toast.LENGTH_SHORT).show()
            } else {
                evalJs("window.__rajMedia&&window.__rajMedia.cmd('sub2')")
            }
            poke()
        }
        fsRepeatWrap.setOnClickListener { showRepeatDialog() }
        fsMore.setOnClickListener { showMoreMenu() }
        // keep the controls up while the finger is on the strip (sliding it sideways)
        fsStrip.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) main.removeCallbacks(hideRunnable)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) poke()
            false
        }
        val stripLp = FrameLayout.LayoutParams(itemPx * FS_STRIP_VISIBLE, itemPx, Gravity.TOP or Gravity.END)
        stripLp.setMargins(0, dp(4), dp(6) + itemPx, 0)
        panel.addView(fsStrip, stripLp)
        val moreLp = FrameLayout.LayoutParams(itemPx, itemPx, Gravity.TOP or Gravity.END)
        moreLp.setMargins(0, dp(4), dp(6), 0)
        fsMore.visibility = View.GONE
        panel.addView(fsMore, moreLp)

        // [FS_SEEKBAR] position bar (bottom of the screen, below the subtitle)
        seekBar.contentDescription = "Video position"
        seekBar.visibility = View.GONE
        seekBar.onStart = {
            scrubbing = true
            main.removeCallbacks(hideRunnable)
            evalJs("window.__rajMedia&&window.__rajMedia.scrubStart()")
        }
        seekBar.onChange = { f ->
            val dur = exo?.duration ?: C.TIME_UNSET
            if (dur != C.TIME_UNSET && dur > 0L) {
                val ms = (f * dur).toLong()
                seekBar.setLabels(fmtMs(ms), fmtMs(dur))
                val now = SystemClock.uptimeMillis()
                if (now - lastScrubAt > 90L) {
                    lastScrubAt = now
                    liveSeek(ms)
                }
            }
        }
        seekBar.onEnd = { f ->
            scrubbing = false
            val dur = exo?.duration ?: C.TIME_UNSET
            if (dur != C.TIME_UNSET && dur > 0L) {
                val ms = (f * dur).toLong()
                liveSeek(ms)
                evalJs("window.__rajMedia&&window.__rajMedia.scrubEnd(" + ms + ")")
            } else {
                evalJs("window.__rajMedia&&window.__rajMedia.scrubEnd(-1)")
            }
            poke()
        }
        panel.addView(seekBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM))

        playerView.setFullscreenButtonClickListener { setFullscreen(!fullscreen) }
        playerView.subtitleView?.visibility = View.GONE
    }

    // ------------------------------------------------------------------ player

    private fun ensurePlayer(): ExoPlayer {
        exo?.let { return it }
        val renderers = DefaultRenderersFactory(act.applicationContext).setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15_000, 40_000, 1_000, 2_000)
            .setTargetBufferBytes(64 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            // keeps the last seconds in memory so "repeat this sentence" seeks backwards instantly
            .setBackBuffer(20_000, true)
            .build()
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val p = ExoPlayer.Builder(act.applicationContext, renderers)
            .setMediaSourceFactory(DefaultMediaSourceFactory(act.applicationContext, TolerantExtractorsFactory(DefaultExtractorsFactory())))
            .setLoadControl(loadControl)
            .setAudioAttributes(audio, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // The page draws its own subtitles from the .srt file; never show the ones embedded in the MKV.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        p.volume = if (muted) 0f else 1f
        p.addListener(listener)
        playerView.player = p
        exo = p
        applyFx()
        return p
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) recovering = false
            if ((playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) && seekPending) {
                seekPending = false
                seekDone++
            }
            pushState()
            updateTicker()
            if (playbackState == Player.STATE_ENDED) savePos()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            pushState()
            updateTicker()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            pushState()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            pushState()
        }

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.width > 0 && videoSize.height > 0) {
                videoAspect = (videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height).coerceIn(0.4f, 4f)
                relayout()
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            checkTracks(tracks)
        }

        override fun onPlayerError(error: PlaybackException) {
            if (tryRecover(error)) {
                pushState()
                return
            }
            recovering = false
            val o = JSONObject()
            o.put("e", "err")
            o.put("code", 4)
            o.put("msg", describe(error))
            emit(o)
            pushState()
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "the video file was moved, deleted, or the app lost access to it. Tap 🎬+ to pick it again"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
            "this phone cannot decode the video format inside this file"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ->
            "the file looks damaged or is not a supported video container"
        else -> "playback failed (" + e.errorCodeName + errorDetail(e) + ")"
    }

    /** Short technical detail (real cause + where in the video) so a failure can be understood from the screen alone. */
    private fun errorDetail(e: PlaybackException): String {
        val sb = StringBuilder()
        var t: Throwable? = e.cause
        var depth = 0
        var root: Throwable? = null
        while (t != null && depth < 6) {
            root = t
            t = t.cause
            depth++
        }
        if (root != null) {
            sb.append(", ").append(root.javaClass.simpleName)
            val top = root.stackTrace.firstOrNull()
            if (top != null) sb.append(" @").append(top.className.substringAfterLast('.')).append('.').append(top.methodName).append(':').append(top.lineNumber)
        }
        val p = exo
        if (p != null) {
            val d = p.duration
            sb.append(", at ").append(fmtMs(p.currentPosition))
            if (d != C.TIME_UNSET && d > 0L) sb.append(" of ").append(fmtMs(d))
        }
        return sb.toString()
    }

    private fun fmtMs(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) / 1000L)
        val h = s / 3600L
        val m = (s % 3600L) / 60L
        val sec = s % 60L
        return if (h > 0L) String.format(Locale.US, "%d:%02d:%02d", h, m, sec) else String.format(Locale.US, "%d:%02d", m, sec)
    }

    /** Read errors that may well succeed if the same spot is simply opened again. */
    private fun isRetryable(e: PlaybackException): Boolean = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        PlaybackException.ERROR_CODE_UNSPECIFIED -> true
        else -> false
    }

    /**
     * A read error used to stop everything at once (the page then showed "playback failed"). Now the same spot is
     * re-opened up to two times first (the second time with a completely fresh source), and the page is told about
     * the error only if that also fails. Returns true when a retry was started.
     */
    private fun tryRecover(error: PlaybackException): Boolean {
        val p = exo ?: return false
        if (released || !hasMedia || !isRetryable(error)) return false
        val pos = p.currentPosition.coerceAtLeast(0L)
        // an error far from the previous one is a new problem: count from zero again
        if (errRetryAtMs < 0L || Math.abs(pos - errRetryAtMs) > 5000L) errRetries = 0
        errRetryAtMs = pos
        if (errRetries >= 2) return false
        errRetries++
        val attempt = errRetries
        recoverWantsPlay = p.playWhenReady
        recovering = true
        val item = p.currentMediaItem
        // never touch the player from inside its own error callback
        main.post {
            val q = exo
            if (!released && q != null && recovering) {
                if (attempt >= 2 && item != null) q.setMediaItem(item, q.currentPosition.coerceAtLeast(0L))
                q.prepare()
                q.playWhenReady = recoverWantsPlay
            }
        }
        return true
    }

    /** Warns (once per video) when the phone has no decoder for the sound or the picture. */
    private fun checkTracks(t: Tracks) {
        var audioGroups = 0
        var audioOn = false
        var videoGroups = 0
        var videoOn = false
        val audioNames = ArrayList<String>()
        val videoNames = ArrayList<String>()
        updateAudioTracks(t)
        for (g in t.groups) {
            val mime = if (g.length > 0) (g.getTrackFormat(0).sampleMimeType ?: "") else ""
            val nice = mime.substringAfter('/').uppercase().ifEmpty { "?" }
            if (g.type == C.TRACK_TYPE_AUDIO) {
                audioGroups++
                if (g.isSelected) audioOn = true
                audioNames.add(nice)
            } else if (g.type == C.TRACK_TYPE_VIDEO) {
                videoGroups++
                if (g.isSelected) videoOn = true
                videoNames.add(nice)
            }
        }
        val msg = StringBuilder()
        if (audioGroups > 0 && !audioOn) {
            msg.append("This phone cannot decode the sound in this file (" + audioNames.joinToString(", ") + "), so it plays without sound. ")
            msg.append("Re-save the audio as AAC; the video can stay untouched. ")
        }
        if (videoGroups > 0 && !videoOn) {
            msg.append("This phone cannot decode the picture in this file (" + videoNames.joinToString(", ") + "). ")
        }
        if (msg.isNotEmpty() && msg.toString().trim() != lastWarnMsg) {
            lastWarnMsg = msg.toString().trim()
            val o = JSONObject()
            o.put("e", "warn")
            o.put("msg", msg.toString().trim())
            emit(o)
            Toast.makeText(act.applicationContext, msg.toString().trim(), Toast.LENGTH_LONG).show()
        }
    }

    // ------------------------------------------------------------------ audio language

    private fun langKey(code: String): String {
        val c = code.lowercase()
        return when {
            c.startsWith("en") -> "en"
            c.startsWith("hi") -> "hi"
            else -> c.take(2)
        }
    }

    private fun audioName(g: Tracks.Group, i: Int): String {
        val f = g.getTrackFormat(0)
        val code = f.language ?: ""
        val label = f.label
        val lang = when (langKey(code)) {
            "en" -> "English"
            "hi" -> "Hindi"
            else -> if (code.isNotEmpty() && code != "und") Locale.forLanguageTag(code).getDisplayLanguage(Locale.ENGLISH).ifEmpty { code } else ""
        }
        return when {
            lang.isNotEmpty() && label != null && !label.contains(lang, true) -> "$lang ($label)"
            lang.isNotEmpty() -> lang
            label != null -> label
            else -> "Audio " + (i + 1)
        }
    }

    private fun updateAudioTracks(t: Tracks) {
        val all = t.groups.filter { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
        val list = all.filter { it.isSupported }
        audioList = list
        if (all.size > list.size && list.isNotEmpty() && !skippedAudioToastShown) {
            skippedAudioToastShown = true
            Toast.makeText(act.applicationContext, "Another audio track in this file uses a format this phone cannot play, so it is not listed.", Toast.LENGTH_LONG).show()
        }
        if (list.size < 2) {
            audioChipWanted = false
            applyFsUi()
            return
        }
        if (!audioPrefApplied) {
            audioPrefApplied = true
            val wantLang = prefs.getString(key("aud_lang"), null)
            var idx = -1
            if (wantLang != null) idx = list.indexOfFirst { langKey(it.getTrackFormat(0).language ?: "") == wantLang }
            if (idx < 0) {
                val wantIdx = prefs.getInt(key("aud_idx"), -1)
                if (wantIdx in list.indices && wantLang == null) idx = wantIdx
            }
            if (idx >= 0 && !list[idx].isSelected) {
                selectAudio(idx, false)
                return // the player reports the tracks again after the switch
            }
        }
        val sel = list.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        audioChipWanted = true
        applyFsUi()
    }

    private fun selectAudio(idx: Int, remember: Boolean) {
        val p = exo ?: return
        val g = audioList.getOrNull(idx) ?: return
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(g.mediaTrackGroup, 0))
            .build()
        if (remember) {
            prefs.edit()
                .putString(key("aud_lang"), langKey(g.getTrackFormat(0).language ?: "").ifEmpty { "?" })
                .putInt(key("aud_idx"), idx)
                .apply()
        }
    }

    private fun showAudioDialog() {
        val list = audioList
        val ownerAct = activityProvider() ?: return
        if (list.size < 2 || ownerAct.isFinishing) return
        val names = Array(list.size) { audioName(list[it], it) }
        val cur = list.indexOfFirst { it.isSelected }.coerceAtLeast(0)
        AlertDialog.Builder(ownerAct)
            .setTitle("Audio")
            .setSingleChoiceItems(names, cur) { d, which ->
                selectAudio(which, true)
                d.dismiss()
            }
            .show()
    }

    // ------------------------------------------------------------------ state -> page

    private fun emit(o: JSONObject) {
        if (released) return
        try {
            evalJs("window.__nvOn&&window.__nvOn(" + o.toString() + ")")
        } catch (e: Exception) {
            // WebView is gone
        }
    }

    private fun pushState() {
        val p = exo ?: return
        if (released) return
        // while a read error is being retried the page just sees normal buffering (no stop, no pause event)
        val st = if (recovering) Player.STATE_BUFFERING else p.playbackState
        val o = JSONObject()
        o.put("e", "state")
        o.put("q", lastSeq)
        o.put("t", p.currentPosition.coerceAtLeast(0L))
        val d = p.duration
        o.put("d", if (d == C.TIME_UNSET) -1L else d)
        o.put("pwr", if (recovering) recoverWantsPlay else (p.playWhenReady && (st == Player.STATE_READY || st == Player.STATE_BUFFERING)))
        o.put("pl", p.isPlaying)
        o.put("st", st)
        o.put("r", p.playbackParameters.speed.toDouble())
        o.put("sk", seekDone)
        emit(o)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            val p = exo ?: return
            checkSeek()
            pushState()
            tickCount++
            if (tickCount % 50 == 0) savePos()
            val st = p.playbackState
            if (p.playWhenReady && (st == Player.STATE_READY || st == Player.STATE_BUFFERING)) {
                main.postDelayed(this, 100)
            }
        }
    }

    private fun updateTicker() {
        main.removeCallbacks(tick)
        val p = exo ?: return
        val st = p.playbackState
        if (p.playWhenReady && (st == Player.STATE_READY || st == Player.STATE_BUFFERING)) main.postDelayed(tick, 100)
    }

    /** Safety net: a seek inside already-buffered data may not change the player state at all. */
    private fun checkSeek() {
        val p = exo ?: return
        if (seekPending && (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_ENDED)) {
            seekPending = false
            seekDone++
        }
    }

    private fun savePos() {
        val p = exo ?: return
        if (!hasMedia || loadedKey == null) return
        val st = p.playbackState
        if (st == Player.STATE_IDLE) return
        val pos = if (st == Player.STATE_ENDED) 0L else p.currentPosition.coerceAtLeast(0L)
        prefs.edit().putLong("vid_pos_$appId", pos).apply()
    }

    // ------------------------------------------------------------------ page -> player (main thread)

    fun cmd(seq: Int, op: String, arg: Double) {
        if (released) return
        val p = exo
        if (p != null) {
            when (op) {
                "play" -> {
                    if (p.playbackState == Player.STATE_ENDED) p.seekToDefaultPosition()
                    if (p.playbackState == Player.STATE_IDLE && p.currentMediaItem != null) p.prepare()
                    p.play()
                }
                "pause" -> p.pause()
                "seek" -> {
                    errRetries = 0
                    seekPending = true
                    var target = arg.toLong().coerceAtLeast(0L)
                    // a subtitle time past the end of the file must land on the last moment, not beyond it
                    val dur = p.duration
                    if (dur != C.TIME_UNSET && dur > 100L && target >= dur) target = dur - 50L
                    p.seekTo(target)
                    main.postDelayed({ checkSeek(); pushState() }, 120)
                }
                "speed" -> p.setPlaybackSpeed(arg.toFloat().coerceIn(0.25f, 4f))
                "retry" -> if (p.playbackState == Player.STATE_IDLE && p.currentMediaItem != null) {
                    errRetries = 0
                    p.prepare()
                }
            }
        }
        lastSeq = seq
        pushState()
        updateTicker()
    }

    fun setMuted(on: Boolean) {
        muted = on
        exo?.volume = if (on) 0f else 1f
    }

    fun setControls(on: Boolean) {
        ctlWanted = on
        playerView.useController = on && !fullscreen
    }

    fun setVisible(on: Boolean) {
        wantVisible = on
        applyVisibility()
    }

    fun setOverlay(text: String?, sizePct: Double, opacity: Double, on: Boolean) {
        tiles.update(text ?: "", sizePct.toFloat(), opacity.toFloat(), on)
    }

    fun setFx(mirror: Boolean, blurCssPx: Double, oldTv: Boolean) {
        fxMirror = mirror
        fxBlurCssPx = blurCssPx.toFloat()
        fxOldTv = oldTv
        applyFx()
    }

    private fun applyFx() {
        val v = playerView.videoSurfaceView ?: return
        try {
            v.scaleX = if (fxMirror) -1f else 1f
            if (fxOldTv) {
                val gray = ColorMatrix()
                gray.setSaturation(0f)
                val k = 1.24f
                val o = -40f
                gray.postConcat(ColorMatrix(floatArrayOf(
                    k, 0f, 0f, 0f, o,
                    0f, k, 0f, 0f, o,
                    0f, 0f, k, 0f, o,
                    0f, 0f, 0f, 1f, 0f
                )))
                gray.postConcat(ColorMatrix(floatArrayOf(
                    1.08f, 0f, 0f, 0f, 0f,
                    0f, 1.0f, 0f, 0f, 0f,
                    0f, 0f, 0.85f, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f
                )))
                val paint = Paint()
                paint.colorFilter = ColorMatrixColorFilter(gray)
                v.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            } else if (v is TextureView) {
                v.setLayerType(View.LAYER_TYPE_NONE, null)
            }
            if (Build.VERSION.SDK_INT >= 31) applyBlur(v, fxBlurCssPx * act.resources.displayMetrics.density)
        } catch (e: Exception) {
            // effects are optional
        }
    }

    @RequiresApi(31)
    private fun applyBlur(v: View, px: Float) {
        v.setRenderEffect(if (px > 0.5f) RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP) else null)
    }

    // ------------------------------------------------------------------ layout / fullscreen

    fun setInsets(l: Int, t: Int, r: Int) {
        if (l == insetL && t == insetT && r == insetR) return
        insetL = l
        insetT = t
        insetR = r
        relayout()
    }

    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    /** Panel height follows the video's shape but never takes more than 40% of the screen (like the old page player). */
    fun relayout() {
        val lp = panel.layoutParams as? LinearLayout.LayoutParams ?: return
        val dm = act.resources.displayMetrics
        val newH: Int
        if (fullscreen) {
            // clean full screen: the video fills the screen, the page is hidden (it keeps running in the background)
            newH = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            val availW = (dm.widthPixels - insetL - insetR).coerceAtLeast(1)
            val maxH = maxOf(dp(96), (dm.heightPixels * 0.4f).toInt())
            newH = (availW / videoAspect).toInt().coerceIn(dp(96), maxH) + insetT
        }
        panel.setPadding(insetL, insetT, insetR, 0)
        if (lp.height != newH) {
            lp.height = newH
            panel.layoutParams = lp
        }
        web.visibility = if (fullscreen) View.GONE else View.VISIBLE
        // black behind the picture while full screen (instead of the app's dark blue)
        (panel.parent as? View)?.setBackgroundColor(if (fullscreen) Color.BLACK else Color.TRANSPARENT)
        layoutFsOverlay()
    }

    /** Sizes and shows / hides the on-video subtitle and the three buttons. */
    private fun layoutFsOverlay() {
        val dm = act.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, shortSide * 0.055f)
        sub2Text.setTextSize(TypedValue.COMPLEX_UNIT_PX, shortSide * 0.045f)
        val lp = subBox.layoutParams as FrameLayout.LayoutParams
        val side = maxOf(dp(24), (maxOf(dm.widthPixels, dm.heightPixels) * 0.12f).toInt())
        // subtitle sits above the position bar, which is at the very bottom
        lp.setMargins(side, 0, side, dp(38) + (shortSide * 0.03f).toInt())
        subBox.layoutParams = lp
        applySub2()
        subtitleText.visibility = if (fullscreen && subtitleText.text.isNotEmpty()) View.VISIBLE else View.GONE
        // position bar at the bottom edge, below the subtitle (full width: the button strip is at the top now)
        val slp = seekBar.layoutParams as FrameLayout.LayoutParams
        slp.setMargins(dp(14), 0, dp(14), dp(6))
        seekBar.layoutParams = slp
        fsPlay.setImageResource(if (MediaBus.playing) R.drawable.ic_pause else R.drawable.ic_play)
        applyFsUi()
    }

    // ------------------------------------------------------------------ full screen: hidden controls, tap to show

    /**
     * Full screen touch gestures (all on the picture; the buttons and bars sit above this layer and keep working as before):
     *  - single tap in the middle  = play / pause
     *  - single tap on the right   = next sentence
     *  - single tap on the left    = previous sentence
     *  - double tap                = show all controls and keep them; double tap again = hide them
     *  - press and slide left / right = move the video backward / forward (the page's sentence player follows when you let go)
     */
    @Suppress("ClickableViewAccessibility")
    private fun setupFsGestures() {
        val slop = ViewConfiguration.get(act).scaledTouchSlop
        val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
        var downAt = 0L
        var upAt = 0L
        val detector = GestureDetector(act, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                // a long press that ends without sliding is not a tap
                if (upAt - downAt > longPressMs) return false
                onFsSingleTap(e.x)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onFsDoubleTap()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || !fullscreen) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (!swiping) {
                    // start only for a clearly horizontal slide, so a shaky tap is never taken for a slide
                    if (Math.abs(dx) < slop * 2 || Math.abs(dx) < Math.abs(dy) * 1.2f) return false
                    startSwipe()
                }
                updateSwipe(dx)
                return true
            }
        })
        // press-and-hold must still allow sliding, so the detector's own long-press mode is off
        detector.setIsLongpressEnabled(false)
        tapLayer.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) downAt = e.eventTime
            if (e.actionMasked == MotionEvent.ACTION_UP) upAt = e.eventTime
            detector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                if (swiping) endSwipe()
            }
            true
        }
    }

    private fun onFsSingleTap(x: Float) {
        if (!fullscreen) return
        val w = tapLayer.width.toFloat().coerceAtLeast(1f)
        val cmd = when {
            x < w * FS_SIDE_ZONE -> "prev"
            x > w * (1f - FS_SIDE_ZONE) -> "next"
            else -> "toggle"
        }
        evalJs("window.__rajMedia&&window.__rajMedia.cmd('$cmd')")
        poke()
    }

    private fun onFsDoubleTap() {
        if (!fullscreen) return
        if (uiPinned) {
            uiPinned = false
            hideFsUi()
        } else {
            uiPinned = true
            showFsUi()
        }
    }

    private fun startSwipe() {
        val p = exo ?: return
        swiping = true
        scrubbing = true
        swipeStartMs = p.currentPosition.coerceAtLeast(0L)
        swipeTargetMs = swipeStartMs
        main.removeCallbacks(hideRunnable)
        evalJs("window.__rajMedia&&window.__rajMedia.scrubStart()")
        seekHint.visibility = View.VISIBLE
    }

    private fun updateSwipe(dx: Float) {
        val p = exo ?: return
        val w = tapLayer.width.toFloat().coerceAtLeast(1f)
        val dur = p.duration
        var target = swipeStartMs + (dx / w * FS_SWIPE_FULL_WIDTH_MS).toLong()
        target = target.coerceAtLeast(0L)
        if (dur != C.TIME_UNSET && dur > 100L && target >= dur) target = dur - 50L
        swipeTargetMs = target
        val delta = (target - swipeStartMs) / 1000L
        seekHint.text = (if (delta >= 0) "+" else "-") + fmtMs(Math.abs(delta) * 1000L) + "   " + fmtMs(target)
        if (dur != C.TIME_UNSET && dur > 0L) {
            seekBar.setFraction(target.toFloat() / dur.toFloat())
            seekBar.setLabels(fmtMs(target), fmtMs(dur))
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastScrubAt > 90L) {
            lastScrubAt = now
            liveSeek(target)
        }
    }

    private fun endSwipe() {
        swiping = false
        scrubbing = false
        seekHint.visibility = View.GONE
        val dur = exo?.duration ?: C.TIME_UNSET
        if (dur != C.TIME_UNSET && dur > 0L) {
            liveSeek(swipeTargetMs)
            evalJs("window.__rajMedia&&window.__rajMedia.scrubEnd(" + swipeTargetMs + ")")
        } else {
            evalJs("window.__rajMedia&&window.__rajMedia.scrubEnd(-1)")
        }
        poke()
    }

    private fun showFsUi() {
        if (!fullscreen) return
        fsUiVisible = true
        applyFsUi()
        refreshSliders()
        main.removeCallbacks(uiTick)
        main.postDelayed(uiTick, 250)
        poke()
    }

    /** Any touch on a control: keep the controls up for two more seconds. */
    private fun poke() {
        main.removeCallbacks(hideRunnable)
        if (fsUiVisible && !uiPinned) main.postDelayed(hideRunnable, 2000)
    }

    private fun hideFsUi() {
        if (scrubbing || seekBar.dragging) {
            poke()
            return
        }
        if (uiPinned) return     // pinned by a double tap: only another double tap hides it
        fsUiVisible = false
        main.removeCallbacks(uiTick)
        main.removeCallbacks(hideRunnable)
        applyFsUi()
    }

    private fun fade(v: View, show: Boolean) {
        if (!fullscreen) {
            v.animate().cancel()
            v.alpha = 1f
            v.visibility = View.GONE
            return
        }
        if (show) {
            if (v.visibility != View.VISIBLE) {
                v.alpha = 0f
                v.visibility = View.VISIBLE
            }
            v.animate().alpha(1f).setDuration(140).start()
        } else {
            if (v.visibility != View.VISIBLE) {
                v.animate().cancel()
                v.visibility = View.INVISIBLE
                return
            }
            v.animate().alpha(0f).setDuration(140).withEndAction {
                if (!(fullscreen && fsUiVisible)) v.visibility = View.INVISIBLE
            }.start()
        }
    }

    /** One place decides what is visible: normal screen = as before; full screen = only picture + subtitle until tapped. */
    private fun applyFsUi() {
        val show = fullscreen && fsUiVisible
        tapLayer.visibility = if (fullscreen) View.VISIBLE else View.GONE
        fade(fsStrip, show)
        fade(fsMore, show)
        fade(seekBar, show)
        // the faint speaker chip is only for the normal (not full screen) player; full screen has the audio button in the strip
        if (!audioChipWanted || fullscreen) {
            audioChip.visibility = View.GONE
        } else {
            audioChip.alpha = 0.10f
            audioChip.visibility = View.VISIBLE
        }
        updateFlagUi()
    }

    private fun refreshSliders() {
        val p = exo
        if (p != null && !seekBar.dragging && !scrubbing) {
            val dur = p.duration
            val pos = p.currentPosition.coerceAtLeast(0L)
            if (dur != C.TIME_UNSET && dur > 0L) {
                seekBar.setFraction(pos.toFloat() / dur.toFloat())
                seekBar.setLabels(fmtMs(pos), fmtMs(dur))
            }
        }
    }

    // ------------------------------------------------------------------ [FS_FLAGS] bookmark / favorite / repeat / delay

    /** Sent by the page: star + bookmark state of the current sentence, repeat counts, delay (seconds), current sentence index. */
    fun setFlags(starred: Boolean, bookmarked: Boolean, customRepeat: Int, globalRepeat: Int, delaySec: Double, curIndex: Int) {
        flagStar = starred
        flagBookmark = bookmarked
        flagCustomRepeat = customRepeat
        flagGlobalRepeat = globalRepeat
        flagDelay = delaySec
        flagCurIdx = curIndex
        updateFlagUi()
    }

    private fun updateFlagUi() {
        fsBookmark.setImageResource(if (flagBookmark) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
        if (flagBookmark) {
            fsBookmark.alpha = 0.95f
            fsBookmark.setColorFilter(Color.parseColor("#6EC1FF"))
        } else {
            fsBookmark.alpha = 0.35f
            fsBookmark.clearColorFilter()
        }
        fsStar.setImageResource(if (flagStar) R.drawable.ic_star else R.drawable.ic_star_border)
        if (flagStar) {
            fsStar.alpha = 0.95f
            fsStar.setColorFilter(Color.parseColor("#FFD54A"))
        } else {
            fsStar.alpha = 0.35f
            fsStar.clearColorFilter()
        }
        val eff = if (flagCustomRepeat > 0) flagCustomRepeat else flagGlobalRepeat
        if (eff > 0) {
            fsRepeatBadge.text = eff.toString()
            fsRepeatBadge.setTextColor(if (flagCustomRepeat > 0) Color.parseColor("#FF8A4C") else Color.WHITE)
            fsRepeatBadge.visibility = View.VISIBLE
        } else {
            fsRepeatBadge.visibility = View.GONE
        }
        fsAudio.alpha = if (audioList.size >= 2) 0.35f else 0.18f
        if (sub2Has && sub2On) {
            fsSub2.alpha = 0.95f
            fsSub2.setColorFilter(Color.parseColor("#7CE38B"))
        } else {
            fsSub2.alpha = if (sub2Has) 0.35f else 0.18f
            fsSub2.clearColorFilter()
        }
    }

    /** Sent by the page: text of the second subtitle for the sentence that is playing, whether it is turned on, whether one is loaded. */
    fun setSub2(text: String, on: Boolean, has: Boolean) {
        sub2Str = text
        sub2On = on
        sub2Has = has
        applySub2()
        updateFlagUi()
    }

    private fun applySub2() {
        sub2Text.text = sub2Str
        sub2Text.visibility = if (fullscreen && sub2On && sub2Has && sub2Str.isNotEmpty() && subtitleText.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun fmtDelay(d: Double): String {
        val r = Math.round(d * 10.0) / 10.0
        return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
    }

    /** Three dots menu (top right). Add new entries here with item(...). */
    private fun showMoreMenu() {
        main.removeCallbacks(hideRunnable)
        morePopup?.dismiss()
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.minimumWidth = dp(230)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2202124"))
        bg.cornerRadius = dp(12).toFloat()
        box.background = bg
        box.setPadding(0, dp(6), 0, dp(6))
        val pw = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = dp(8).toFloat()
        pw.setOnDismissListener {
            morePopup = null
            poke()
        }
        fun item(title: String, sub: String, onClick: () -> Unit) {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(18), dp(10), dp(22), dp(10))
            val t = TextView(act)
            t.text = title
            t.setTextColor(Color.WHITE)
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            val u = TextView(act)
            u.text = sub
            u.setTextColor(Color.argb(170, 255, 255, 255))
            u.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            row.addView(t)
            row.addView(u)
            row.setOnClickListener {
                pw.dismiss()
                onClick()
            }
            box.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // 1. delay sentence
        item("Delay sentence", "Pause between sentences: " + fmtDelay(flagDelay) + " s") { showDelayDialog() }
        morePopup = pw
        pw.showAsDropDown(fsMore, 0, 0, Gravity.END)
    }

    /** [-] number [+] row. allowEmpty: going below [min] clears the field (empty = "not set"). */
    private fun stepperRow(c: Context, initial: String, step: Double, min: Double, max: Double,
                           decimal: Boolean, allowEmpty: Boolean, hint: String): Pair<LinearLayout, EditText> {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val edit = EditText(c)
        edit.inputType = if (decimal) (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL) else InputType.TYPE_CLASS_NUMBER
        edit.setText(initial)
        edit.hint = hint
        edit.gravity = Gravity.CENTER
        edit.setSelectAllOnFocus(true)
        val minus = Button(c)
        minus.text = "\u2212"
        val plus = Button(c)
        plus.text = "+"
        fun cur(): Double? = edit.text.toString().trim().replace(',', '.').toDoubleOrNull()
        fun setNum(v: Double) {
            edit.setText(if (decimal) fmtDelay(v) else v.toInt().toString())
        }
        minus.setOnClickListener {
            val v = cur()
            if (v == null) {
                if (!allowEmpty) setNum(min)
            } else if (allowEmpty && v - step < min) {
                edit.setText("")
            } else {
                setNum((v - step).coerceAtLeast(min))
            }
        }
        plus.setOnClickListener {
            val v = cur()
            setNum(if (v == null) min else (v + step).coerceAtMost(max))
        }
        row.addView(minus, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        row.addView(edit, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(plus, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.WRAP_CONTENT))
        return Pair(row, edit)
    }

    /** Delay sentence: the page's pause between sentences, in seconds. */
    private fun showDelayDialog() {
        val ownerAct = activityProvider() ?: return
        if (ownerAct.isFinishing) return
        main.removeCallbacks(hideRunnable)
        val pair = stepperRow(ownerAct, fmtDelay(flagDelay), 0.5, 0.0, 60.0, true, false, "0")
        val holder = LinearLayout(ownerAct)
        holder.setPadding(dp(24), dp(8), dp(24), 0)
        holder.addView(pair.first, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        AlertDialog.Builder(ownerAct)
            .setTitle("Delay sentence")
            .setMessage("Pause after each sentence before the next one starts, in seconds. 0 = no pause.")
            .setView(holder)
            .setPositiveButton("Set") { _, _ ->
                val v = pair.second.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (v != null && v >= 0.0) {
                    val clean = fmtDelay(v.coerceAtMost(60.0))
                    evalJs("window.__rajMedia&&window.__rajMedia.cmd('delay:" + clean + "')")
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { poke() }
            .show()
    }

    /** Repeat: how many times every sentence repeats, and (optionally) a different count for this sentence only. */
    private fun showRepeatDialog() {
        val ownerAct = activityProvider() ?: return
        if (ownerAct.isFinishing) return
        main.removeCallbacks(hideRunnable)
        // the film keeps playing under the dialog, so remember which sentence this is for
        val targetIdx = flagCurIdx
        val startAll = flagGlobalRepeat
        val startOne = flagCustomRepeat
        val allPair = stepperRow(ownerAct, if (startAll > 0) startAll.toString() else "1", 1.0, 1.0, 100.0, false, false, "")
        val onePair = stepperRow(ownerAct, if (startOne > 0) startOne.toString() else "", 1.0, 1.0, 100.0, false, true, "same")
        val col = LinearLayout(ownerAct)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(8), dp(24), 0)
        fun label(t: String): TextView {
            val tv = TextView(ownerAct)
            tv.text = t
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            tv.setPadding(0, dp(8), 0, dp(2))
            return tv
        }
        val full = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        col.addView(label("Every sentence repeats (times)"))
        col.addView(allPair.first, full)
        if (targetIdx >= 0) {
            col.addView(label("This sentence only (empty = same as above)"))
            col.addView(onePair.first, full)
        }
        AlertDialog.Builder(ownerAct)
            .setTitle("Repeat")
            .setView(col)
            .setPositiveButton("Set") { _, _ ->
                val all = allPair.second.text.toString().trim().toIntOrNull()
                if (all != null && all >= 1 && all != startAll) {
                    evalJs("window.__rajMedia&&window.__rajMedia.cmd('repeatAll:" + all.coerceAtMost(100) + "')")
                }
                if (targetIdx >= 0) {
                    val one = (onePair.second.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 100)
                    if (one != startOne) {
                        evalJs("window.__rajMedia&&window.__rajMedia.cmd('repeatOne:" + targetIdx + ":" + one + "')")
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { poke() }
            .show()
    }

    /** Moves the picture while the finger slides on the position bar (the page's sentence player is paused meanwhile). */
    private fun liveSeek(ms: Long) {
        val p = exo ?: return
        var target = ms.coerceAtLeast(0L)
        val dur = p.duration
        if (dur != C.TIME_UNSET && dur > 100L && target >= dur) target = dur - 50L
        p.seekTo(target)
        main.postDelayed({ if (!released) pushState() }, 60)
    }

    /** Unlink: the page stops repeating sentences and the film plays from start to end; subtitles still follow the film. */
    private fun setUnlinked(on: Boolean) {
        unlinked = on
        updateUnlinkUi()
        evalJs("window.__rajMedia&&window.__rajMedia.cmd('" + (if (on) "unlink:1" else "unlink:0") + "')")
    }

    private fun updateUnlinkUi() {
        if (unlinked) {
            fsUnlink.alpha = 0.95f
            fsUnlink.setColorFilter(Color.parseColor("#FF8A4C"))
            fsUnlink.contentDescription = "Film plays normally (tap to link subtitles again)"
        } else {
            fsUnlink.alpha = 0.35f
            fsUnlink.clearColorFilter()
            fsUnlink.contentDescription = "Unlink subtitles: play the film normally"
        }
    }

    /** The sentence that is playing now (sent by the page) and whether it is playing. Shown on the video while full screen. */
    fun setSubtitle(text: String, playing: Boolean) {
        subtitleText.text = text
        fsPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        subtitleText.visibility = if (fullscreen && text.isNotEmpty()) View.VISIBLE else View.GONE
        applySub2()
    }

    private fun applyVisibility() {
        val show = wantVisible && hasMedia
        panel.visibility = if (show) View.VISIBLE else View.GONE
        if (!show && fullscreen) setFullscreen(false)
        if (show) relayout()
    }

    /** Called by the page's video full screen button (and by the player's own full screen button). */
    fun setFullscreenFromPage(on: Boolean) {
        setFullscreen(on)
    }

    private fun setFullscreen(on: Boolean) {
        if (on && !(hasMedia && panel.visibility == View.VISIBLE)) {
            // nothing to show full screen: make sure the page knows it is off
            notifyFullscreen(false)
            return
        }
        if (on == fullscreen) {
            notifyFullscreen(on)   // keeps a reloaded page in step
            return
        }
        fullscreen = on
        morePopup?.dismiss()
        // always start and end full screen in the normal (linked, repeating) mode, with the controls hidden
        if (unlinked) {
            unlinked = false
            evalJs("window.__rajMedia&&window.__rajMedia.cmd('unlink:0')")
        }
        updateUnlinkUi()
        fsUiVisible = false
        uiPinned = false
        swiping = false
        seekHint.visibility = View.GONE
        scrubbing = false
        main.removeCallbacks(uiTick)
        main.removeCallbacks(hideRunnable)
        playerView.useController = ctlWanted && !on
        activityProvider()?.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        relayout()
        notifyFullscreen(on)
    }

    /** The screen was recreated (rotation lock, Recents, ...) while full screen: put the orientation back and tell the page again. */
    fun reapplyFullscreen() {
        if (!fullscreen) return
        activityProvider()?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        relayout()
        notifyFullscreen(true)
    }

    private fun notifyFullscreen(on: Boolean) {
        evalJs("window.__onNvFullscreen&&window.__onNvFullscreen(" + (if (on) "true" else "false") + ")")
    }

    /** Back button: leaves full screen first. Returns true when it was used. */
    fun handleBack(): Boolean {
        if (fullscreen) {
            setFullscreen(false)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ lifecycle

    fun onPause() {
        if (released) return
        savePos()
    }

    fun setBackground(on: Boolean) {
        // no-op: the original player code is kept as it was
    }

    fun setAlign(leftDp: Float, widthDp: Float, radiusDp: Float) {
        // no-op: the video keeps its original full width
    }

    fun release() {
        if (released) return
        savePos()
        released = true
        copyGen++
        main.removeCallbacksAndMessages(null)
        exo?.let {
            it.removeListener(listener)
            playerView.player = null
            it.release()
        }
        exo = null
    }

    // ------------------------------------------------------------------ remembered video

    private fun key(name: String): String = name + "_" + appId

    private fun canOpen(uri: Uri): Boolean = try {
        act.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (e: Exception) {
        false
    }

    private fun readInfo(uri: Uri): Pair<String, Long> {
        var name = "video"
        var size = -1L
        try {
            act.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (ni >= 0) name = c.getString(ni) ?: name
                    val si = c.getColumnIndex(OpenableColumns.SIZE)
                    if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                }
            }
        } catch (e: Exception) {
            // keep defaults
        }
        if (size < 0) {
            try {
                act.contentResolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            } catch (e: Exception) {
                // keep -1
            }
        }
        return Pair(name, size)
    }

    private fun privateDir(): File = File(act.filesDir, "videos/$appId")

    private fun removePrivateExcept(keep: File?) {
        try {
            privateDir().listFiles()?.forEach { f ->
                if (keep == null || f.absolutePath != keep.absolutePath) f.delete()
            }
        } catch (e: Exception) {
            // ignore
        }
    }

    /** Where the remembered video can be read from right now, or null. Safe to call from any thread. */
    private fun resolveSource(): Source? {
        val name = prefs.getString(key("vid_name"), "video") ?: "video"
        val size = prefs.getLong(key("vid_size"), 0L)
        val stamp = prefs.getLong(key("vid_stamp"), 0L)
        val priv = prefs.getString(key("vid_priv"), null)
        if (priv != null) {
            val f = File(priv)
            if (f.isFile && f.length() > 0L) return Source(Uri.fromFile(f), name, f.length(), stamp, "private")
        }
        val u = prefs.getString(key("vid_uri"), null) ?: return null
        val uri = try { Uri.parse(u) } catch (e: Exception) { return null }
        return if (canOpen(uri)) Source(uri, name, size, stamp, "content") else null
    }

    fun canResolve(): Boolean = resolveSource() != null

    fun savedJson(): String {
        val s = resolveSource() ?: return ""
        return infoJson(s)
    }

    /** Name of a remembered video that can no longer be opened (so the page can say which one to pick again). */
    fun missingName(): String {
        if (resolveSource() != null) return ""
        if (prefs.getString(key("vid_uri"), null) == null && prefs.getString(key("vid_priv"), null) == null) return ""
        return prefs.getString(key("vid_name"), "") ?: ""
    }

    private fun infoJson(s: Source): String {
        val o = JSONObject()
        o.put("name", s.name)
        o.put("size", s.size)
        o.put("stamp", s.stamp)
        o.put("source", s.kind)
        return o.toString()
    }

    private fun notifyPage() {
        val s = resolveSource() ?: return
        evalJs("window.__onNativeVideo&&window.__onNativeVideo(" + infoJson(s) + ")")
    }

    /** Opens the remembered video in the player (position restored). Called by the page. */
    fun openSaved() {
        if (released) return
        val s = resolveSource() ?: return
        val p = ensurePlayer()
        val k = s.uri.toString() + "|" + s.stamp
        val startMs = prefs.getLong(key("vid_pos"), 0L).coerceAtLeast(0L)
        if (k != loadedKey) {
            loadedKey = k
            hasMedia = true
            seekPending = false
            audioPrefApplied = false
            skippedAudioToastShown = false
            lastWarnMsg = ""
            errRetries = 0
            errRetryAtMs = -1L
            recovering = false
            audioList = emptyList()
            audioChipWanted = false
            applyFsUi()
            videoAspect = 16f / 9f
            p.setMediaItem(MediaItem.fromUri(s.uri), startMs)
            p.playWhenReady = false
            p.prepare()
        }
        val o = JSONObject()
        o.put("e", "load")
        o.put("name", s.name)
        o.put("size", s.size)
        o.put("pos", p.currentPosition.coerceAtLeast(0L))
        o.put("sk", seekDone)
        emit(o)
        applyVisibility()
        relayout()
        pushState()
        if (fullscreen) notifyFullscreen(true)
    }

    // ------------------------------------------------------------------ subtitles stored inside the video

    @Volatile private var subScanGen = 0

    /**
     * Looks for text subtitle tracks inside the video that was just picked (MKV / MP4) and offers them to the page
     * as an .srt, so the Sentence Index fills without a separate subtitle file. Read-only, runs in the background and
     * never touches playback; any problem simply means "nothing found".
     */
    private fun scanEmbeddedSubs(uri: Uri) {
        val gen = ++subScanGen
        val appCtx = act.applicationContext
        val t = Thread {
            var res: EmbeddedSubs.Result? = null
            try {
                val pfd = appCtx.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input ->
                        res = EmbeddedSubs.scan(input.channel) { gen != subScanGen || released }
                    }
                }
            } catch (e: Throwable) {
                res = null
            }
            val r = res
            main.post { if (gen == subScanGen && !released) onSubsScanned(r) }
        }
        t.isDaemon = true
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }

    private fun onSubsScanned(r: EmbeddedSubs.Result?) {
        if (r == null) return
        if (r.tracks.isEmpty()) {
            // no text track: stay quiet (the page already says to load an .srt), except when the subtitles are pictures
            if (r.pictureTracks > 0) {
                Toast.makeText(act.applicationContext, "The subtitles inside this video are pictures and cannot be read. Please load an .srt file.", Toast.LENGTH_LONG).show()
            }
            return
        }
        val owner = activityProvider()
        if (r.tracks.size == 1 || owner == null || owner.isFinishing) {
            deliverSubs(r.tracks[EmbeddedSubs.bestIndex(r.tracks).coerceAtLeast(0)])
            return
        }
        val labels = Array(r.tracks.size) { r.tracks[it].label() + " - " + r.tracks[it].cues.size + " lines" }
        var sel = EmbeddedSubs.bestIndex(r.tracks).coerceAtLeast(0)
        AlertDialog.Builder(owner)
            .setTitle("Subtitles found in this video")
            .setSingleChoiceItems(labels, sel) { _, which -> sel = which }
            .setPositiveButton("Load") { _, _ -> deliverSubs(r.tracks[sel]) }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun deliverSubs(track: EmbeddedSubs.Track) {
        val srt = EmbeddedSubs.toSrt(track.cues)
        if (srt.isBlank()) return
        evalJs(
            "window.__onEmbeddedSubs&&window.__onEmbeddedSubs(" + JSONObject.quote(track.label()) + "," +
                JSONObject.quote(srt) + "," + track.cues.size + ")"
        )
    }

    /** The user picked a video with Android's file picker. */
    fun onPicked(uri: Uri) {
        val cr = act.contentResolver
        var persisted = false
        try {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            persisted = cr.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        } catch (e: Exception) {
            // this provider does not allow keeping access
        }
        if (!canOpen(uri)) {
            Toast.makeText(act.applicationContext, "Could not open that file", Toast.LENGTH_LONG).show()
            return
        }
        val info = readInfo(uri)
        val stamp = System.currentTimeMillis()
        copyGen++
        if (persisted) {
            commit(uri, info.first, info.second, stamp, null)
            notifyPage()
            scanEmbeddedSubs(uri)
        } else {
            startPrivateCopy(uri, info.first, info.second, stamp)
        }
    }

    private fun commit(uri: Uri, name: String, size: Long, stamp: Long, priv: File?) {
        // the player still holds the previous video: stop it so its position cannot overwrite the new entry
        exo?.pause()
        loadedKey = null
        val e = prefs.edit()
        e.putString(key("vid_uri"), uri.toString())
        e.putString(key("vid_name"), name)
        e.putLong(key("vid_size"), size)
        e.putLong(key("vid_stamp"), stamp)
        e.putLong(key("vid_pos"), 0L)
        if (priv != null) e.putString(key("vid_priv"), priv.absolutePath) else e.remove(key("vid_priv"))
        e.apply()
        removePrivateExcept(priv)
    }

    /** Android would not let us keep access to the file, so keep an exact copy in private storage. */
    private fun startPrivateCopy(uri: Uri, name: String, size: Long, stamp: Long) {
        val gen = copyGen
        val dir = privateDir()
        dir.mkdirs()
        if (size > 0) {
            val free = try { StatFs(act.filesDir.path).availableBytes } catch (e: Exception) { Long.MAX_VALUE }
            if (free < size + 64L * 1024 * 1024) {
                commit(uri, name, size, stamp, null)
                Toast.makeText(act.applicationContext, "Not enough free space to keep a copy. The video plays now but will not be remembered after you close the app.", Toast.LENGTH_LONG).show()
                notifyPage()
                return
            }
        }
        Toast.makeText(act.applicationContext, "Saving a private copy of the video so it is remembered…", Toast.LENGTH_LONG).show()
        val ext = name.substringAfterLast('.', "mkv").filter { it.isLetterOrDigit() }.take(5).ifEmpty { "bin" }
        Thread {
            val tmp = File(dir, "video_$stamp.part")
            val dest = File(dir, "video_$stamp.$ext")
            var ok = false
            try {
                val input = act.contentResolver.openInputStream(uri)
                if (input != null) {
                    input.use { ins ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(1 shl 20)
                            var total = 0L
                            var lastPct = -1
                            while (true) {
                                if (gen != copyGen || released) throw InterruptedException()
                                val n = ins.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                total += n.toLong()
                                if (size > 0) {
                                    val pct = (total * 100L / size).toInt().coerceIn(0, 100)
                                    if (pct != lastPct) {
                                        lastPct = pct
                                        main.post {
                                            if (gen == copyGen && !released) {
                                                val o = JSONObject()
                                                o.put("e", "copy")
                                                o.put("pct", pct)
                                                emit(o)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ok = tmp.length() > 0L && tmp.renameTo(dest)
                }
            } catch (t: Throwable) {
                ok = false
            }
            if (!ok) tmp.delete()
            main.post { finishCopy(gen, ok, dest, uri, name, size, stamp) }
        }.start()
    }

    private fun finishCopy(gen: Int, ok: Boolean, dest: File, uri: Uri, name: String, size: Long, stamp: Long) {
        if (gen != copyGen || released) {
            if (ok) dest.delete()
            return
        }
        if (ok) {
            commit(uri, name, dest.length(), stamp, dest)
            notifyPage()
            scanEmbeddedSubs(Uri.fromFile(dest))
        } else if (canOpen(uri)) {
            commit(uri, name, size, stamp, null)
            Toast.makeText(act.applicationContext, "Could not keep a copy. The video plays now but will not be remembered after you close the app.", Toast.LENGTH_LONG).show()
            notifyPage()
            scanEmbeddedSubs(uri)
        } else {
            Toast.makeText(act.applicationContext, "Could not read that video file", Toast.LENGTH_LONG).show()
        }
    }

    /** Forget the remembered video completely (also deletes any private copy). */
    fun forget() {
        copyGen++
        exo?.let {
            it.stop()
            it.clearMediaItems()
        }
        loadedKey = null
        hasMedia = false
        recovering = false
        prefs.edit()
            .remove(key("vid_uri")).remove(key("vid_name")).remove(key("vid_size"))
            .remove(key("vid_stamp")).remove(key("vid_priv")).remove(key("vid_pos"))
            .remove(key("aud_lang")).remove(key("aud_idx"))
            .apply()
        removePrivateExcept(null)
        applyVisibility()
    }
}

/**
 * Draws the page's "overlay text" on top of the native video: the text repeated in a grid that fills the picture
 * (same layout rules as the page's own overlay), with adjustable size and opacity.
 */
class TextTileView(ctx: Context) : View(ctx) {
    private var text = ""
    private var sizePct = 8f
    private var on = false
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = false
        isFocusable = false
        visibility = View.GONE
        paint.color = Color.WHITE
        paint.isFakeBoldText = true
        paint.textAlign = Paint.Align.CENTER
        paint.setShadowLayer(8f, 0f, 3f, 0xA6000000.toInt())
    }

    fun update(t: String, pct: Float, opacity: Float, enabled: Boolean) {
        text = t
        sizePct = pct.coerceIn(2f, 40f)
        on = enabled
        alpha = opacity.coerceIn(0f, 1f)
        visibility = if (on && text.isNotBlank()) View.VISIBLE else View.GONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!on || text.isBlank()) return
        val pad = width * 0.03f
        val w = width - 2f * pad
        val h = height - 2f * pad
        if (w <= 0f || h <= 0f) return
        paint.textSize = maxOf(6f, h * sizePct / 100f)
        val lines = text.split("\n")
        val fm = paint.fontMetrics
        val lineH = fm.descent - fm.ascent
        var pw = 1f
        for (l in lines) pw = maxOf(pw, paint.measureText(l))
        val ph = maxOf(1f, lineH * lines.size)
        var cols = maxOf(1, (w / (pw * 1.35f)).toInt())
        var rows = maxOf(1, (h / (ph * 1.5f)).toInt())
        while (cols * rows > 1800) {
            cols = maxOf(1, (cols * 0.9f).toInt())
            rows = maxOf(1, (rows * 0.9f).toInt())
        }
        val cw = w / cols
        val ch = h / rows
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val cx = pad + (c + 0.5f) * cw
                val top = pad + (r + 0.5f) * ch - ph / 2f
                for (i in lines.indices) {
                    canvas.drawText(lines[i], cx, top + i * lineH - fm.ascent, paint)
                }
            }
        }
    }
}

/**
 * Some movie files stop a little before the place their own header says they end (cut, re-muxed or half-copied
 * files). Android's player then reports a read error ("end of file reached unexpectedly") the moment it gets
 * there, which would only affect the last sentences of a subtitle file. These two classes turn "the file ended
 * early" into a normal end of the video, but only after the picture/sound layout and seek table were already
 * read, so a file that is broken from the start still reports its error as before. Nothing is converted or
 * re-encoded.
 */
@androidx.annotation.OptIn(UnstableApi::class)
private class TolerantExtractorsFactory(private val base: ExtractorsFactory) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(base.createExtractors())

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        wrap(base.createExtractors(uri, responseHeaders))

    private fun wrap(src: Array<Extractor>): Array<Extractor> =
        Array<Extractor>(src.size) { i -> TolerantExtractor(src[i]) }
}

@androidx.annotation.OptIn(UnstableApi::class)
private class TolerantExtractor(private val base: Extractor) : Extractor {
    private var tracksEnded = false
    private var seekMapSent = false

    override fun sniff(input: ExtractorInput): Boolean = base.sniff(input)

    override fun getSniffFailureDetails(): List<SniffFailure> = base.getSniffFailureDetails()

    override fun init(output: ExtractorOutput) {
        tracksEnded = false
        seekMapSent = false
        base.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = output.track(id, type)

            override fun endTracks() {
                tracksEnded = true
                output.endTracks()
            }

            override fun seekMap(seekMap: SeekMap) {
                seekMapSent = true
                output.seekMap(seekMap)
            }
        })
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        try {
            return base.read(input, seekPosition)
        } catch (e: IOException) {
            if (tracksEnded && seekMapSent && (endedEarly(e) || e is ParserException)) return Extractor.RESULT_END_OF_INPUT
            throw e
        } catch (e: RuntimeException) {
            // damaged bytes near the end of the file made the parser throw (e.g. IllegalStateException)
            if (tracksEnded && seekMapSent) return Extractor.RESULT_END_OF_INPUT
            throw e
        }
    }

    override fun seek(position: Long, timeUs: Long) = base.seek(position, timeUs)

    override fun release() = base.release()

    override fun getUnderlyingImplementation(): Extractor = base.getUnderlyingImplementation()

    private fun endedEarly(e: Throwable): Boolean {
        var t: Throwable? = e
        var depth = 0
        while (t != null && depth < 6) {
            if (t is EOFException) return true
            t = t.cause
            depth++
        }
        return false
    }
}
