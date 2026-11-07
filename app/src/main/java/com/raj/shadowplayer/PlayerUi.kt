package com.raj.shadowplayer

import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.content.DialogInterface
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
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.OpenableColumns
import android.provider.Settings
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
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.PopupWindow
import android.widget.ScrollView
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import android.widget.CheckBox
import java.io.File

/** Full screen gestures: width of the left and the right tap zone (fraction of the screen width). The rest is the middle. */
private const val FS_SIDE_ZONE = 0.30f

/** Full screen gestures: sliding a finger across the whole screen width moves the video by this much. */
private const val FS_SWIPE_FULL_WIDTH_MS = 120_000L

/** Full screen button strip: how many icons are visible at once (the others are reached by sliding the strip sideways). */
private const val FS_STRIP_VISIBLE = 5

/** Full screen button strip: size of one icon cell in dp. */
private const val FS_ITEM_DP = 46

/** Full screen: sliding a finger up / down along this fraction of the screen height changes brightness (left half) or volume (right half) from 0 to 100%. */
private const val FS_ADJUST_SPAN = 0.85f


/**
 * The full screen player screen (picture, on-picture subtitles, hidden gesture controls, button strip, 3-dot menu, dialogs).
 * Taken over from the old web-page player: every call that used to go to the page now goes to [SentenceEngine].
 */
@androidx.annotation.OptIn(UnstableApi::class)
class PlayerUi(
    private val act: AppCompatActivity
) : SentenceEngine.Host {
    private val activityProvider: () -> AppCompatActivity? = { act }

    /** Asks the screen to open the previous (-1) / next (+1) video of the list. Returns false when there is none. */
    var requestVideo: (Int) -> Boolean = { false }

    /** Root view of the screen. */
    val panel: FrameLayout = LayoutInflater.from(act).inflate(R.layout.panel_player, null, false) as FrameLayout

    private val playerView: PlayerView = panel.findViewById(R.id.nv_player)
    private val prefs: SharedPreferences = act.getSharedPreferences("shadowplayer", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var exo: ExoPlayer? = null
    private var loadedKey: String? = null
    private var hasMedia = false
    private var wantVisible = false
    private var fullscreen = true      // this app is always full screen
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

    // [FS_TOOLS] more strip buttons: copy / edit / delete subtitle, subtitles on-off, jump, landscape-portrait, lock
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val fsCopy = ImageView(act)
    private val fsEdit = ImageView(act)
    private val fsDelete = ImageView(act)
    private val fsSubsToggle = ImageView(act)
    private val fsJump = ImageView(act)
    private val fsOrient = ImageView(act)
    private val fsLock = ImageView(act)
    private var subsVisible = prefs.getBoolean("fs_subs_on", true)   // subtitles drawn on the picture (on / off toggle)
    private var fsPortrait = prefs.getBoolean("fs_portrait", false)  // full screen in portrait instead of landscape
    private var locked = false                                       // lock: no gesture, no controls, only picture + subtitle
    private val unlockBtn = ImageView(act)
    private val unlockHide = Runnable { unlockBtn.visibility = View.GONE }
    // slide up / down: left half = brightness, right half = volume (small read-out on the picture)
    private val hudBox = LinearLayout(act)
    private val hudIcon = ImageView(act)
    private val hudFill = View(act)
    private val hudText = TextView(act)
    private val hudHide = Runnable { hudBox.visibility = View.GONE }
    private var adjMode = 0               // 0 = none, 1 = brightness, 2 = volume
    private var adjStartY = 0f
    private var adjStartLevel = 0f
    private var adjLevel = 0f
    // what the page has in its Words and Range boxes (the page sends it), shown in the 3-dot menu and its dialogs
    private var filterWords = ""
    private var filterRange = ""
    // playback is paused while an edit / delete / jump dialog is open and resumes afterwards
    private var resumeAfterDialog = false

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


    private val settings = PlayerSettings(prefs)
    val engine = SentenceEngine(this, settings) { exo }

    // ---- SentenceEngine.Host
    override fun onSubtitle(text: String, playing: Boolean) { setSubtitle(text, playing) }
    override fun onFlags(starred: Boolean, bookmarked: Boolean, customRepeat: Int, globalRepeat: Int, delaySec: Double, curIndex: Int) {
        setFlags(starred, bookmarked, customRepeat, globalRepeat, delaySec, curIndex)
    }
    override fun onSub2(text: String, on: Boolean, has: Boolean) { setSub2(text, on, has) }
    override fun onFilters(words: String, range: String) { filterWords = words; filterRange = range }
    override fun toast(msg: String) { toastShort(msg) }

    init {
        panel.visibility = View.VISIBLE
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
        fsCopy.setImageResource(R.drawable.ic_copy)
        fsEdit.setImageResource(R.drawable.ic_edit)
        fsDelete.setImageResource(R.drawable.ic_delete)
        fsSubsToggle.setImageResource(R.drawable.ic_subs_on)
        fsJump.setImageResource(R.drawable.ic_jump)
        fsOrient.setImageResource(R.drawable.ic_orient_landscape)
        fsLock.setImageResource(R.drawable.ic_lock)
        fsCopy.contentDescription = "Copy this subtitle"
        fsEdit.contentDescription = "Edit this subtitle"
        fsDelete.contentDescription = "Delete this subtitle"
        fsSubsToggle.contentDescription = "Subtitles on / off"
        fsJump.contentDescription = "Jump to a subtitle number"
        fsOrient.contentDescription = "Landscape / portrait"
        fsLock.contentDescription = "Lock the screen"
        for (b in listOf(fsPrev, fsPlay, fsNext, fsUnlink, fsHide, fsBookmark, fsAudio, fsRepeat, fsStar, fsSub2, fsMore,
            fsCopy, fsEdit, fsDelete, fsSubsToggle, fsJump, fsOrient, fsLock)) {
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
        for (v in listOf<View>(fsPrev, fsPlay, fsNext, fsUnlink, fsHide, fsBookmark, fsAudio, fsRepeatWrap, fsStar, fsSub2,
            fsCopy, fsEdit, fsDelete, fsSubsToggle, fsJump, fsOrient, fsLock)) {
            fsControls.addView(v, LinearLayout.LayoutParams(itemPx, itemPx))
        }
        fsStrip.addView(fsControls, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        fsPrev.setOnClickListener { engine.cmd("prev"); poke() }
        fsPlay.setOnClickListener { engine.cmd("toggle"); poke() }
        fsNext.setOnClickListener { engine.cmd("next"); poke() }
        fsUnlink.setOnClickListener { setUnlinked(!unlinked); poke() }
        fsHide.setOnClickListener { engine.cmd("hide"); poke() }
        fsBookmark.setOnClickListener { engine.cmd("bookmark"); poke() }
        fsStar.setOnClickListener { engine.cmd("star"); poke() }
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
                Toast.makeText(act.applicationContext, "Upload a translated subtitle (SRT) from the 3-dot menu first", Toast.LENGTH_SHORT).show()
            } else {
                engine.cmd("sub2")
            }
            poke()
        }
        fsRepeatWrap.setOnClickListener { showRepeatDialog() }
        fsMore.setOnClickListener { showMoreMenu() }
        fsCopy.setOnClickListener { copySubtitle(); poke() }
        fsEdit.setOnClickListener { openEditDialog(); poke() }
        fsDelete.setOnClickListener { openDeleteDialog(); poke() }
        fsSubsToggle.setOnClickListener { setSubsVisible(!subsVisible); poke() }
        fsJump.setOnClickListener { showJumpDialog() }
        fsOrient.setOnClickListener { setPortrait(!fsPortrait); poke() }
        fsLock.setOnClickListener { setLocked(true) }
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
            engine.scrubStart()
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
                engine.scrubEnd(ms)
            } else {
                engine.scrubEnd(-1L)
            }
            poke()
        }
        panel.addView(seekBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM))

        // [FS_LOCK] unlock button: only shows for a moment after a tap while the screen is locked
        unlockBtn.setImageResource(R.drawable.ic_lock_open)
        unlockBtn.setPadding(dp(14), dp(14), dp(14), dp(14))
        unlockBtn.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(170, 0, 0, 0))
        }
        unlockBtn.contentDescription = "Unlock the screen"
        unlockBtn.visibility = View.GONE
        unlockBtn.setOnClickListener { setLocked(false) }
        val unlockLp = FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER_VERTICAL or Gravity.END)
        unlockLp.setMargins(0, 0, dp(28), 0)
        panel.addView(unlockBtn, unlockLp)

        // [FS_ADJUST] small read-out while sliding for brightness / volume (touches pass through)
        hudBox.orientation = LinearLayout.HORIZONTAL
        hudBox.gravity = Gravity.CENTER_VERTICAL
        hudBox.setPadding(dp(14), dp(10), dp(16), dp(10))
        hudBox.background = GradientDrawable().apply {
            setColor(Color.argb(175, 0, 0, 0))
            cornerRadius = dp(22).toFloat()
        }
        hudIcon.setImageResource(R.drawable.ic_volume)
        hudBox.addView(hudIcon, LinearLayout.LayoutParams(dp(22), dp(22)))
        val hudTrack = FrameLayout(act)
        hudTrack.background = GradientDrawable().apply {
            setColor(Color.argb(80, 255, 255, 255))
            cornerRadius = dp(3).toFloat()
        }
        hudFill.background = GradientDrawable().apply {
            setColor(Color.WHITE)
            cornerRadius = dp(3).toFloat()
        }
        hudTrack.addView(hudFill, FrameLayout.LayoutParams(0, MATCH, Gravity.START))
        val trackLp = LinearLayout.LayoutParams(dp(120), dp(5))
        trackLp.setMargins(dp(10), 0, dp(10), 0)
        hudBox.addView(hudTrack, trackLp)
        hudText.setTextColor(Color.WHITE)
        hudText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        hudText.setTypeface(hudText.typeface, android.graphics.Typeface.BOLD)
        hudText.minWidth = dp(40)
        hudBox.addView(hudText, LinearLayout.LayoutParams(WRAP, WRAP))
        hudBox.visibility = View.GONE
        val hudLp = FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
        hudLp.setMargins(0, dp(26), 0, 0)
        panel.addView(hudBox, hudLp)

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
        // The app draws its own subtitles from the .srt data; never show the ones embedded in the MKV.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
        p.volume = if (muted) 0f else 1f
        p.addListener(listener)
        playerView.player = p
        exo = p
        return p
    }

    private val listener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) recovering = false
            engine.publishNow()
            if (playbackState == Player.STATE_ENDED) {
                savePos()
                // in the sentence player the engine decides what happens at the end; plain / unlinked play moves on to the next video
                if (!engine.hasSubs || engine.isFree) {
                    if (!requestVideo(1)) showFsUi()
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            engine.publishNow()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            engine.publishNow()
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
            if (tryRecover(error)) return
            recovering = false
            Toast.makeText(act.applicationContext, "Video error: " + describe(error), Toast.LENGTH_LONG).show()
            engine.publishNow()
        }
    }

    private fun describe(e: PlaybackException): String = when (e.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION ->
            "the video file was moved, deleted, or the app lost access to it. Go back and open it again"
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


    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()

    /** The video always fills the screen; this only places the subtitle / bars. */
    fun relayout() {
        panel.setPadding(insetL, insetT, insetR, 0)
        layoutFsOverlay()
    }

    /** Sizes and shows / hides the on-video subtitle and the three buttons. */
    private fun layoutFsOverlay() {
        val dm = act.resources.displayMetrics
        val shortSide = minOf(dm.widthPixels, dm.heightPixels)
        subtitleText.setTextSize(TypedValue.COMPLEX_UNIT_PX, shortSide * 0.055f)
        sub2Text.setTextSize(TypedValue.COMPLEX_UNIT_PX, shortSide * 0.045f)
        val lp = subBox.layoutParams as FrameLayout.LayoutParams
        if (dm.heightPixels > dm.widthPixels) {
            // portrait: the subtitle sits right under the picture, which is centred on the screen
            val sidePortrait = maxOf(dp(16), (dm.widthPixels * 0.05f).toInt())
            val contentW = (dm.widthPixels - insetL - insetR).coerceAtLeast(1)
            val contentH = (dm.heightPixels - insetT).coerceAtLeast(1)
            val videoH = minOf(contentW / videoAspect, contentH.toFloat())
            val top = insetT + ((contentH + videoH) / 2f).toInt() + dp(14)
            lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            lp.setMargins(sidePortrait, top, sidePortrait, 0)
        } else {
            val side = maxOf(dp(24), (maxOf(dm.widthPixels, dm.heightPixels) * 0.12f).toInt())
            // subtitle sits above the position bar, which is at the very bottom
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.setMargins(side, 0, side, dp(38) + (shortSide * 0.03f).toInt())
        }
        subBox.layoutParams = lp
        syncSubtitleViews()
        // position bar at the bottom edge, below the subtitle (full width: the button strip is at the top now)
        val slp = seekBar.layoutParams as FrameLayout.LayoutParams
        slp.setMargins(dp(14), 0, dp(14), dp(6))
        seekBar.layoutParams = slp
        fsPlay.setImageResource(if (engine.uiPlaying) R.drawable.ic_pause else R.drawable.ic_play)
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
                if (locked) {
                    // locked: a tap only shows the unlock button for a moment
                    showUnlockBtn()
                    return true
                }
                onFsSingleTap(e.x)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (locked) return true
                onFsDoubleTap()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                if (e1 == null || !fullscreen || locked) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                // already sliding for brightness / volume
                if (adjMode != 0) {
                    updateAdjust(e2.y)
                    return true
                }
                if (!swiping) {
                    // a clearly vertical slide: left half = brightness, right half = volume
                    if (Math.abs(dy) >= slop * 2 && Math.abs(dy) > Math.abs(dx) * 1.2f) {
                        startAdjust(e1.x, e1.y)
                        updateAdjust(e2.y)
                        return true
                    }
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
                if (adjMode != 0) endAdjust()
            }
            true
        }
    }

    private fun onFsSingleTap(x: Float) {
        if (!fullscreen || locked) return
        val w = tapLayer.width.toFloat().coerceAtLeast(1f)
        val cmd = when {
            x < w * FS_SIDE_ZONE -> "prev"
            x > w * (1f - FS_SIDE_ZONE) -> "next"
            else -> "toggle"
        }
        engine.cmd(cmd)
        poke()
    }

    private fun onFsDoubleTap() {
        if (!fullscreen || locked) return
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
        engine.scrubStart()
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
            engine.scrubEnd(swipeTargetMs)
        } else {
            engine.scrubEnd(-1L)
        }
        poke()
    }

    private fun showFsUi() {
        if (!fullscreen || locked) return
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
        val show = fullscreen && fsUiVisible && !locked
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

    // ------------------------------------------------------------------ [FS_TOOLS] copy / edit / delete / jump / lock / orientation / brightness + volume

    private fun toastShort(msg: String) {
        Toast.makeText(act.applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun dialogOwner(): AppCompatActivity? {
        val a = activityProvider() ?: return null
        return if (a.isFinishing) null else a
    }

    private fun shorten(t: String): String = if (t.length > 90) t.substring(0, 90) + "\u2026" else t

    // ---- copy

    private fun copySubtitle() {
        val t = subtitleText.text.toString().trim()
        if (t.isEmpty()) {
            toastShort("No subtitle to copy")
            return
        }
        try {
            val cm = act.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("subtitle", t))
            toastShort("Subtitle copied")
        } catch (e: Exception) {
            toastShort("Could not copy")
        }
    }

    // ---- subtitles on / off

    private fun setSubsVisible(on: Boolean) {
        subsVisible = on
        prefs.edit().putBoolean("fs_subs_on", on).apply()
        syncSubtitleViews()
        updateFlagUi()
        toastShort(if (on) "Subtitles on" else "Subtitles off")
    }

    // ---- landscape / portrait

    fun fsOrientation(): Int =
        if (fsPortrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

    private fun setPortrait(on: Boolean) {
        fsPortrait = on
        prefs.edit().putBoolean("fs_portrait", on).apply()
        if (fullscreen) activityProvider()?.requestedOrientation = fsOrientation()
        updateFlagUi()
        relayout()
    }

    // ---- lock

    private fun setLocked(on: Boolean) {
        if (on && !fullscreen) return
        locked = on
        main.removeCallbacks(unlockHide)
        unlockBtn.visibility = View.GONE
        if (on) {
            uiPinned = false
            fsUiVisible = false
            main.removeCallbacks(hideRunnable)
            main.removeCallbacks(uiTick)
            morePopup?.dismiss()
            applyFsUi()
            toastShort("Screen locked. Tap the screen to show the unlock button.")
        } else {
            applyFsUi()
            toastShort("Screen unlocked")
            showFsUi()
        }
    }

    private fun showUnlockBtn() {
        main.removeCallbacks(unlockHide)
        unlockBtn.alpha = 1f
        unlockBtn.visibility = View.VISIBLE
        main.postDelayed(unlockHide, 2500)
    }

    // ---- brightness (left half) and volume (right half): slide up / down

    private fun currentBrightness(): Float {
        val cur = activityProvider()?.window?.attributes?.screenBrightness ?: -1f
        if (cur >= 0f) return cur
        return try {
            Settings.System.getInt(act.contentResolver, Settings.System.SCREEN_BRIGHTNESS) / 255f
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun applyBrightness(level: Float): Float {
        val v = level.coerceIn(0.02f, 1f)
        val a = activityProvider() ?: return v
        val lp = a.window.attributes
        lp.screenBrightness = v
        a.window.attributes = lp
        return v
    }

    /** Full screen starts with the brightness the learner chose last time (if any); leaving it gives the phone's own brightness back. */
    private fun applyScreenBrightness(on: Boolean) {
        val a = activityProvider() ?: return
        val lp = a.window.attributes
        val saved = prefs.getFloat("fs_bright", -1f)
        lp.screenBrightness = if (on && saved >= 0f) saved.coerceIn(0.02f, 1f) else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        a.window.attributes = lp
    }

    private fun audioManager(): AudioManager = act.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun currentVolume(): Float {
        return try {
            val am = audioManager()
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
        } catch (e: Exception) {
            0.5f
        }
    }

    private fun applyVolume(level: Float) {
        try {
            val am = audioManager()
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level * max).coerceIn(0, max), 0)
        } catch (e: Exception) {
            // some phones refuse (Do Not Disturb rules): the hardware keys still work
        }
    }

    private fun startAdjust(x: Float, y: Float) {
        val w = tapLayer.width.toFloat().coerceAtLeast(1f)
        adjMode = if (x < w / 2f) 1 else 2
        adjStartY = y
        adjStartLevel = if (adjMode == 1) currentBrightness() else currentVolume()
        adjLevel = adjStartLevel
        main.removeCallbacks(hudHide)
    }

    private fun updateAdjust(y: Float) {
        val h = tapLayer.height.toFloat().coerceAtLeast(1f)
        val level = (adjStartLevel + (adjStartY - y) / (h * FS_ADJUST_SPAN)).coerceIn(0f, 1f)
        if (adjMode == 1) {
            adjLevel = applyBrightness(level)
        } else {
            adjLevel = level
            applyVolume(level)
        }
        showHud(adjMode == 1, adjLevel)
    }

    private fun endAdjust() {
        if (adjMode == 1) prefs.edit().putFloat("fs_bright", adjLevel).apply()
        adjMode = 0
        main.removeCallbacks(hudHide)
        main.postDelayed(hudHide, 700)
    }

    private fun showHud(brightness: Boolean, level: Float) {
        hudIcon.setImageResource(if (brightness) R.drawable.ic_brightness else R.drawable.ic_volume)
        hudText.text = Math.round(level * 100f).toString() + "%"
        val lp = hudFill.layoutParams
        lp.width = (dp(120) * level).toInt()
        hudFill.layoutParams = lp
        main.removeCallbacks(hudHide)
        hudBox.visibility = View.VISIBLE
    }

    // ---- pause the film while an edit / delete / jump dialog is open

    private fun holdPlayback() {
        main.removeCallbacks(hideRunnable)
        resumeAfterDialog = engine.uiPlaying
        if (resumeAfterDialog) engine.cmd("toggle")
    }

    private fun resumePlaybackNow() {
        if (!resumeAfterDialog) return
        resumeAfterDialog = false
        engine.cmd("toggle")
    }

    private fun dialogClosed() {
        resumePlaybackNow()
        poke()
    }

    // ---- edit

    private fun showEditDialog(ix: Int, text: String) {
        val ownerAct = dialogOwner() ?: return
        if (ix < 0) return
        holdPlayback()
        val input = EditText(ownerAct)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.setText(text)
        input.setSelection(input.text.length)
        input.minLines = 2
        input.maxLines = 5
        input.gravity = Gravity.TOP or Gravity.START
        val holder = FrameLayout(ownerAct)
        holder.setPadding(dp(22), dp(8), dp(22), 0)
        holder.addView(input, FrameLayout.LayoutParams(MATCH, WRAP))
        val d = AlertDialog.Builder(ownerAct)
            .setTitle("Edit subtitle " + (ix + 1))
            .setView(holder)
            .setPositiveButton("Save") { _, _ ->
                val t = input.text.toString().replace(Regex("\\s+"), " ").trim()
                if (t.isNotEmpty() && t != text) {
                    engine.editSentence(ix, t)
                }
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { dialogClosed() }
            .create()
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
    }

    // ---- delete

    private fun showDeleteDialog(ix: Int, text: String) {
        val ownerAct = dialogOwner() ?: return
        if (ix < 0) return
        holdPlayback()
        AlertDialog.Builder(ownerAct)
            .setTitle("Delete subtitle " + (ix + 1) + "?")
            .setMessage(text + "\n\nThis removes it from your subtitle list for good.")
            .setPositiveButton("Delete") { _, _ ->
                resumePlaybackNow()
                engine.deleteSentence(ix)
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { dialogClosed() }
            .show()
    }

    // ---- jump

    private fun showJumpDialog() {
        val ownerAct = dialogOwner() ?: return
        val total = engine.total
        if (total <= 0) {
            toastShort("Load subtitles first")
            return
        }
        holdPlayback()
        val start = (flagCurIdx + 1).coerceIn(1, total)
        val pair = stepperRow(ownerAct, start.toString(), 1.0, 1.0, total.toDouble(), false, false, "")
        val holder = LinearLayout(ownerAct)
        holder.setPadding(dp(24), dp(8), dp(24), 0)
        holder.addView(pair.first, LinearLayout.LayoutParams(MATCH, WRAP))
        val d = AlertDialog.Builder(ownerAct)
            .setTitle("Jump to subtitle")
            .setMessage("Type a number from 1 to " + total + " and the practice starts there.")
            .setView(holder)
            .setPositiveButton("Go", null)
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { dialogClosed() }
            .create()
        d.setOnShowListener {
            d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val n = pair.second.text.toString().trim().toIntOrNull()
                if (n == null || n < 1 || n > total) {
                    toastShort("Enter a number from 1 to " + total)
                } else {
                    resumeAfterDialog = false      // the jump itself starts the playback
                    engine.jumpToNumber(n)
                    d.dismiss()
                }
            }
        }
        d.show()
    }

    // ---- upload subtitles: see pickSubtitles / onSubsPicked at the end of this file

    // ---- words filter

    private fun showWordsDialog() {
        val ownerAct = dialogOwner() ?: return
        main.removeCallbacks(hideRunnable)
        val input = EditText(ownerAct)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "5 or 5-8"
        input.setText(filterWords)
        input.setSelection(input.text.length)
        input.gravity = Gravity.CENTER
        val holder = FrameLayout(ownerAct)
        holder.setPadding(dp(24), dp(8), dp(24), 0)
        holder.addView(input, FrameLayout.LayoutParams(MATCH, WRAP))
        val d = AlertDialog.Builder(ownerAct)
            .setTitle("Words")
            .setMessage("Practice only subtitles with this many words.\n5 = five words or more, 5-8 = from five to eight words. Empty = every subtitle.")
            .setView(holder)
            .setPositiveButton("Set", null)
            .setNeutralButton("Clear") { _, _ -> engine.setWords("") }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { poke() }
            .create()
        d.setOnShowListener {
            d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val v = input.text.toString().replace(" ", "")
                if (v.isEmpty() || Regex("[0-9]+(-[0-9]+)?").matches(v)) {
                    d.dismiss()
                    engine.setWords(v)
                } else {
                    toastShort("Use a number like 5 or a range like 5-8")
                }
            }
        }
        d.show()
    }

    // ---- range (+ pinned ranges, hide range, delete range)

    private fun showRangeDialog() {
        val ownerAct = dialogOwner() ?: return
        main.removeCallbacks(hideRunnable)
        val input = EditText(ownerAct)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.hint = "e.g. 1-5"
        input.setText(filterRange)
        input.setSelection(input.text.length)
        input.gravity = Gravity.CENTER
        var dlg: AlertDialog? = null
        fun valid(): String? {
            val v = input.text.toString().replace(" ", "")
            return if (Regex("[0-9]+(-[0-9]+)?").matches(v)) v else null
        }
        fun mk(label: String, onTap: () -> Unit): Button {
            val b = Button(ownerAct)
            b.text = label
            b.isAllCaps = false
            b.setOnClickListener { onTap() }
            return b
        }
        val pinBtn = mk("Pin range") {
            val v = valid()
            if (v == null) {
                toastShort("Enter a range like 1-5 first")
            } else {
                dlg?.dismiss()
                engine.pinRange(v)
            }
        }
        val pinnedBtn = mk("Pinned ranges") {
            dlg?.dismiss()
            showPinnedDialog(engine.pinnedJson())
        }
        val hideBtn = mk("Hide / show range") {
            val v = valid()
            if (v == null) {
                toastShort("Enter a range like 1-5 first")
            } else {
                dlg?.dismiss()
                engine.toggleHideRange(v)
            }
        }
        val deleteBtn = mk("Delete range") {
            val v = valid()
            if (v == null) {
                toastShort("Enter a range like 1-5 first")
            } else {
                dlg?.dismiss()
                confirmDeleteRange(v)
            }
        }
        val rowA = LinearLayout(ownerAct)
        rowA.orientation = LinearLayout.HORIZONTAL
        rowA.addView(pinBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        rowA.addView(pinnedBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        val rowB = LinearLayout(ownerAct)
        rowB.orientation = LinearLayout.HORIZONTAL
        rowB.addView(hideBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        rowB.addView(deleteBtn, LinearLayout.LayoutParams(0, WRAP, 1f))
        val col = LinearLayout(ownerAct)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(8), dp(24), 0)
        col.addView(input, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(rowA, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(rowB, LinearLayout.LayoutParams(MATCH, WRAP))
        val scroll = ScrollView(ownerAct)
        scroll.addView(col)
        val d = AlertDialog.Builder(ownerAct)
            .setTitle("Range")
            .setMessage("Practice only these subtitle numbers, like 1-5. Empty = all.")
            .setView(scroll)
            .setPositiveButton("Apply", null)
            .setNeutralButton("Clear") { _, _ -> engine.setRange("") }
            .setNegativeButton("Close", null)
            .setOnDismissListener { poke() }
            .create()
        dlg = d
        d.setOnShowListener {
            d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val v = input.text.toString().replace(" ", "")
                if (v.isEmpty() || Regex("[0-9]+(-[0-9]+)?").matches(v)) {
                    d.dismiss()
                    engine.setRange(v)
                } else {
                    toastShort("Use a range like 1-5")
                }
            }
        }
        d.show()
    }

    private fun showPinnedDialog(arr: JSONArray) {
        val ownerAct = dialogOwner() ?: return
        if (arr.length() == 0) {
            toastShort("No pinned ranges yet. Type a range, then tap Pin range.")
            return
        }
        val ranges = Array(arr.length()) { arr.optString(it, "") }
        val labels = Array(arr.length()) { "Range " + ranges[it] }
        main.removeCallbacks(hideRunnable)
        val d = AlertDialog.Builder(ownerAct)
            .setTitle("Pinned ranges (hold to remove)")
            .setItems(labels) { _, which -> engine.playPinned(ranges[which]) }
            .setNegativeButton("Close", null)
            .setOnDismissListener { poke() }
            .create()
        d.setOnShowListener {
            d.listView.setOnItemLongClickListener { _, _, pos, _ ->
                engine.unpin(ranges[pos])
                d.dismiss()
                main.postDelayed({ showPinnedDialog(engine.pinnedJson()) }, 250)
                true
            }
        }
        d.show()
    }

    // ---- starred subtitles

    private fun showStarredDialog(arr: JSONArray) {
        val ownerAct = dialogOwner() ?: return
        if (arr.length() == 0) {
            toastShort("No starred subtitles yet. Tap the star button to add one.")
            return
        }
        val idx = IntArray(arr.length())
        val labels = Array(arr.length()) { "" }
        for (k in 0 until arr.length()) {
            val pair = arr.getJSONArray(k)
            idx[k] = pair.getInt(0)
            labels[k] = (idx[k] + 1).toString() + ".  " + shorten(pair.getString(1))
        }
        main.removeCallbacks(hideRunnable)
        val d = AlertDialog.Builder(ownerAct)
            .setTitle("Starred (hold to remove)")
            .setItems(labels) { _, which -> engine.jumpToNumber(idx[which] + 1) }
            .setNegativeButton("Close", null)
            .setOnDismissListener { poke() }
            .create()
        d.setOnShowListener {
            d.listView.setOnItemLongClickListener { _, _, pos, _ ->
                engine.unstar(idx[pos])
                d.dismiss()
                main.postDelayed({ showStarredDialog(engine.starredJson()) }, 250)
                true
            }
        }
        d.show()
    }

    // ---- bulk delete

    private fun showBulkDeleteDialog(arr: JSONArray) {
        val ownerAct = dialogOwner() ?: return
        val n = arr.length()
        if (n == 0) {
            toastShort("Load subtitles first")
            return
        }
        val idx = IntArray(n)
        val labels = Array(n) { "" }
        val checked = BooleanArray(n)
        for (k in 0 until n) {
            val pair = arr.getJSONArray(k)
            idx[k] = pair.getInt(0)
            labels[k] = (idx[k] + 1).toString() + ".  " + shorten(pair.getString(1))
        }
        main.removeCallbacks(hideRunnable)
        val d = AlertDialog.Builder(ownerAct)
            .setTitle("Bulk delete")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("Delete", null)
            .setNeutralButton("Select all", null)
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { poke() }
            .create()
        d.setOnShowListener {
            d.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener {
                val allOn = checked.any { !it }
                val lv = d.listView
                for (k in 0 until n) {
                    checked[k] = allOn
                    lv.setItemChecked(k, allOn)
                }
            }
            d.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val sel = ArrayList<Int>()
                for (k in 0 until n) {
                    if (checked[k]) sel.add(idx[k])
                }
                if (sel.isEmpty()) {
                    toastShort("Tick the subtitles to delete first")
                } else {
                    d.dismiss()
                    engine.bulkDelete(sel)
                }
            }
        }
        d.show()
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
        // subtitles on / off: lit (orange) when the subtitles are switched off
        fsSubsToggle.setImageResource(if (subsVisible) R.drawable.ic_subs_on else R.drawable.ic_subs_off)
        if (subsVisible) {
            fsSubsToggle.alpha = 0.35f
            fsSubsToggle.clearColorFilter()
        } else {
            fsSubsToggle.alpha = 0.95f
            fsSubsToggle.setColorFilter(Color.parseColor("#FF8A4C"))
        }
        // landscape / portrait: shows the mode the screen is in now, lit (orange) in portrait
        fsOrient.setImageResource(if (fsPortrait) R.drawable.ic_orient_portrait else R.drawable.ic_orient_landscape)
        if (fsPortrait) {
            fsOrient.alpha = 0.95f
            fsOrient.setColorFilter(Color.parseColor("#FF8A4C"))
        } else {
            fsOrient.alpha = 0.35f
            fsOrient.clearColorFilter()
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
        sub2Text.visibility = if (fullscreen && subsVisible && sub2On && sub2Has && sub2Str.isNotEmpty() && subtitleText.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun fmtDelay(d: Double): String {
        val r = Math.round(d * 10.0) / 10.0
        return if (r == Math.floor(r)) r.toInt().toString() else r.toString()
    }

    /** Three dots menu (top right). Add new entries here with item(...) (and header(...) for a new group). */
    private fun showMoreMenu() {
        main.removeCallbacks(hideRunnable)
        morePopup?.dismiss()
        val box = LinearLayout(act)
        box.orientation = LinearLayout.VERTICAL
        box.minimumWidth = dp(240)
        box.setPadding(0, dp(6), 0, dp(6))
        val scroll = ScrollView(act)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#F2202124"))
        bg.cornerRadius = dp(12).toFloat()
        scroll.background = bg
        scroll.addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val pw = PopupWindow(scroll, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)
        pw.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        pw.isOutsideTouchable = true
        pw.elevation = dp(8).toFloat()
        pw.setOnDismissListener {
            morePopup = null
            poke()
        }
        fun header(title: String) {
            val h = TextView(act)
            h.text = title
            h.setTextColor(Color.argb(140, 255, 255, 255))
            h.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            h.setTypeface(h.typeface, android.graphics.Typeface.BOLD)
            h.letterSpacing = 0.08f
            h.setPadding(dp(18), dp(12), dp(22), dp(2))
            box.addView(h, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        fun item(title: String, sub: String, onClick: () -> Unit) {
            val row = LinearLayout(act)
            row.orientation = LinearLayout.VERTICAL
            row.setPadding(dp(18), dp(9), dp(22), dp(9))
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
        // playback
        item("Delay sentence", "Pause between sentences: " + fmtDelay(flagDelay) + " s") { showDelayDialog() }
        item("Playback settings", "Speed, passage loops, lines per group, subtitle sync, shuffle") { showPlaybackDialog() }
        header("VIDEO")
        item("Previous video", "Play the video before this one in the list") { if (!requestVideo(-1)) toastShort("This is the first video") }
        item("Next video", "Play the video after this one in the list") { if (!requestVideo(1)) toastShort("This is the last video") }
        item("Find subtitles next to the video", "Looks for a .srt with the same name (one-time all-files permission)") { (act as? PlayerActivity)?.askAllFilesAccess() }
        // subtitle files
        header("SUBTITLE FILES")
        item("Upload subtitles", "Choose the main .srt file") { pickSubtitles("main") }
        item("Upload translated subtitles", "Choose a second .srt (shown under the main line)") { pickSubtitles("second") }
        // what is practiced
        header("PRACTICE FILTER")
        item("Words", if (filterWords.isEmpty()) "Off: every subtitle" else "Only subtitles with " + filterWords + " words") { showWordsDialog() }
        item("Range", if (filterRange.isEmpty()) "All subtitles" else "Subtitles " + filterRange) { showRangeDialog() }
        // the subtitle list
        header("SUBTITLE LIST")
        item("Starred subtitles", "Jump to a favorite") { showStarredDialog(engine.starredJson()) }
        item("Bulk delete", "Pick many subtitles and delete them") { showBulkDeleteDialog(engine.sentencesJson()) }
        morePopup = pw
        // tall menu: never taller than the screen, scrolls inside
        box.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val maxH = (act.resources.displayMetrics.heightPixels - dp(72)).coerceAtLeast(dp(160))
        pw.height = minOf(box.measuredHeight, maxH)
        pw.showAsDropDown(fsMore, 0, 0, Gravity.END)
    }

    /** [-] number [+] row. allowEmpty: going below [min] clears the field (empty = "not set"). */
    private fun stepperRow(c: Context, initial: String, step: Double, min: Double, max: Double,
                           decimal: Boolean, allowEmpty: Boolean, hint: String): Pair<LinearLayout, EditText> {
        val row = LinearLayout(c)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val edit = EditText(c)
        edit.inputType = if (decimal) (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED) else InputType.TYPE_CLASS_NUMBER
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
                    engine.setDelay(clean.toDoubleOrNull() ?: 0.0)
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
                    engine.setRepeatAll(all.coerceAtMost(100))
                }
                if (targetIdx >= 0) {
                    val one = (onePair.second.text.toString().trim().toIntOrNull() ?: 0).coerceIn(0, 100)
                    if (one != startOne) {
                        engine.setRepeatOne(targetIdx, one)
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
        main.postDelayed({ if (!released) engine.publishNow() }, 60)
    }

    /** Unlink: the page stops repeating sentences and the film plays from start to end; subtitles still follow the film. */
    private fun setUnlinked(on: Boolean) {
        unlinked = on
        updateUnlinkUi()
        engine.setUnlinked(on)
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
        syncSubtitleViews()
    }

    /** Subtitle lines on the picture: shown only in full screen, only when there is text and the on / off toggle is on. */
    private fun syncSubtitleViews() {
        subtitleText.visibility = if (fullscreen && subsVisible && subtitleText.text.isNotEmpty()) View.VISIBLE else View.GONE
        applySub2()
    }


    // ------------------------------------------------------------------ dialogs that are new in the standalone app

    private fun openEditDialog() {
        val ix = engine.currentIndex
        if (ix < 0) {
            toastShort("No subtitle loaded yet")
            return
        }
        showEditDialog(ix, engine.textAt(ix))
    }

    private fun openDeleteDialog() {
        val ix = engine.currentIndex
        if (ix < 0) {
            toastShort("No subtitle loaded yet")
            return
        }
        showDeleteDialog(ix, engine.textAt(ix))
    }

    private fun confirmDeleteRange(v: String) {
        val ownerAct = dialogOwner() ?: return
        AlertDialog.Builder(ownerAct)
            .setTitle("Delete range " + v + "?")
            .setMessage("These subtitles are removed from your list for good.")
            .setPositiveButton("Delete") { _, _ ->
                if (!engine.deleteRange(v)) toastShort("Use a range like 1-5")
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { poke() }
            .show()
    }

    /** Speed, passage loops, lines per group, subtitle sync, shuffle, starred only. */
    private fun showPlaybackDialog() {
        val ownerAct = dialogOwner() ?: return
        main.removeCallbacks(hideRunnable)
        val speed = stepperRow(ownerAct, fmtDelay(settings.speed.toDouble()), 0.1, 0.5, 2.0, true, false, "1")
        val loops = stepperRow(ownerAct, settings.passageLoops.toString(), 1.0, 1.0, 1000.0, false, false, "")
        val lines = stepperRow(ownerAct, settings.lineCount.toString(), 1.0, 1.0, 20.0, false, false, "")
        val sync = stepperRow(ownerAct, fmtDelay(settings.syncOffset), 0.1, -30.0, 30.0, true, false, "0")
        val shuffle = CheckBox(ownerAct)
        shuffle.text = "Shuffle sentences"
        shuffle.isChecked = settings.shuffle
        val starOnly = CheckBox(ownerAct)
        starOnly.text = "Practice starred subtitles only"
        starOnly.isChecked = settings.starredOnly
        val col = LinearLayout(ownerAct)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(24), dp(8), dp(24), 0)
        val full = LinearLayout.LayoutParams(MATCH, WRAP)
        fun label(t: String) {
            val tv = TextView(ownerAct)
            tv.text = t
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            tv.setPadding(0, dp(8), 0, dp(2))
            col.addView(tv)
        }
        label("Video speed (0.5 - 2.0)")
        col.addView(speed.first, full)
        label("Passage loops (how many times the whole list repeats)")
        col.addView(loops.first, full)
        label("Lines per group (subtitles played together)")
        col.addView(lines.first, full)
        label("Subtitle sync in seconds (+ later, - earlier)")
        col.addView(sync.first, full)
        col.addView(shuffle, full)
        col.addView(starOnly, full)
        val sv = ScrollView(ownerAct)
        sv.addView(col)
        AlertDialog.Builder(ownerAct)
            .setTitle("Playback settings")
            .setView(sv)
            .setPositiveButton("Set") { _, _ ->
                val sp = speed.second.text.toString().trim().replace(',', '.').toFloatOrNull()
                if (sp != null) settings.speed = sp.coerceIn(0.5f, 2f)
                val lp = loops.second.text.toString().trim().toIntOrNull()
                if (lp != null && lp >= 1) settings.passageLoops = lp.coerceAtMost(1000)
                val ln = lines.second.text.toString().trim().toIntOrNull()
                if (ln != null && ln >= 1) settings.lineCount = ln.coerceAtMost(20)
                val sy = sync.second.text.toString().trim().replace(',', '.').toDoubleOrNull()
                if (sy != null) settings.syncOffset = Math.round(sy.coerceIn(-30.0, 30.0) * 10.0) / 10.0
                settings.shuffle = shuffle.isChecked
                settings.starredOnly = starOnly.isChecked
                engine.settingsChanged()
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { poke() }
            .show()
    }

    // ------------------------------------------------------------------ lifecycle

    /** Back button. Returns true when it was used up (screen locked). */
    fun handleBack(): Boolean {
        if (locked) {
            toastShort("Screen is locked. Tap the screen, then the lock button, to unlock.")
            return true
        }
        return false
    }

    fun onPause() {
        if (released) return
        savePos()
        engine.onPause()
        exo?.pause()
    }

    fun onResume() {
        if (released) return
        engine.onResume()
        applyScreenBrightness(true)
    }

    fun release() {
        if (released) return
        savePos()
        released = true
        main.removeCallbacksAndMessages(null)
        engine.release()
        exo?.let {
            it.removeListener(listener)
            playerView.player = null
            it.release()
        }
        exo = null
    }

    // ------------------------------------------------------------------ remembered position

    private var data: VideoData? = null

    private fun savePos() {
        val p = exo ?: return
        val d = data ?: return
        if (!hasMedia) return
        val st = p.playbackState
        if (st == Player.STATE_IDLE) return
        d.positionMs = if (st == Player.STATE_ENDED) 0L else p.currentPosition.coerceAtLeast(0L)
    }

    private val posSaver = object : Runnable {
        override fun run() {
            if (released) return
            savePos()
            main.postDelayed(this, 4000)
        }
    }

    // ------------------------------------------------------------------ opening a video

    /** Opens a video. Its saved subtitles / stars / position come back; without subtitles it plays like a normal video player. */
    fun open(item: VideoItem) {
        if (released) return
        savePos()
        val p = ensurePlayer()
        val d = VideoData(act.applicationContext, item.key)
        data = d
        engine.attach(d)
        loadedKey = item.key
        hasMedia = true
        audioPrefApplied = false
        skippedAudioToastShown = false
        lastWarnMsg = ""
        errRetries = 0
        errRetryAtMs = -1L
        recovering = false
        audioList = emptyList()
        audioChipWanted = false
        unlinked = false
        updateUnlinkUi()
        videoAspect = 16f / 9f
        uiPinned = false
        fsUiVisible = false
        subScanGen++
        applyFsUi()
        relayout()
        val savedMain = d.loadMainSrt()
        val cues: List<Srt.Cue> = if (savedMain != null) Srt.parse(savedMain) else emptyList()
        val startMs = if (cues.isEmpty()) d.positionMs.coerceAtLeast(0L) else 0L
        p.setMediaItem(MediaItem.fromUri(item.uri), startMs)
        p.prepare()
        val savedSecond = d.loadSecondSrt()
        if (savedSecond != null) engine.loadSecond(Srt.parse(savedSecond), false)
        if (cues.isNotEmpty()) {
            engine.loadMain(cues, false)
            engine.begin(true, false)
        } else {
            engine.loadMain(emptyList(), false)
            p.playWhenReady = true
            findSubtitles(item)
        }
        main.removeCallbacks(posSaver)
        main.postDelayed(posSaver, 4000)
    }

    /** New main subtitles (uploaded, found next to the video, or read out of the video): the sentence player takes over from the picture's position. */
    private fun applyMainSubs(list: List<Srt.Cue>, persist: Boolean) {
        val p = exo
        val wasPlaying = p != null && p.playWhenReady && p.playbackState != Player.STATE_ENDED
        engine.loadMain(list, persist)
        engine.begin(wasPlaying, true)
    }

    // ------------------------------------------------------------------ subtitles: next to the video, uploaded, or inside the video

    private fun findSiblingSrt(item: VideoItem): File? {
        val path = item.path ?: return null
        val vf = File(path)
        val dir = vf.parentFile ?: return null
        var files: Array<File>? = null
        try {
            files = dir.listFiles()
        } catch (e: Exception) {
            files = null
        }
        if (files == null) return null
        val base = vf.nameWithoutExtension
        var best: File? = null
        for (f in files) {
            if (!f.name.endsWith(".srt", true)) continue
            val nb = f.nameWithoutExtension
            if (nb.equals(base, true)) return f
            if (best == null && nb.startsWith(base, true)) best = f
        }
        return best
    }

    private fun findSubtitles(item: VideoItem) {
        val sib = findSiblingSrt(item)
        if (sib != null) {
            try {
                val list = Srt.parse(Srt.decode(sib.readBytes()))
                if (list.isNotEmpty()) {
                    applyMainSubs(list, true)
                    toastShort("Subtitles loaded: " + sib.name)
                    return
                }
            } catch (e: Exception) {
                // fall through to the subtitles inside the video
            }
        }
        scanEmbeddedSubs(item.uri)
    }

    private fun pickSubtitles(kind: String) {
        val a = activityProvider() as? PlayerActivity
        if (a == null || a.isFinishing) return
        try {
            a.launchSubsPicker(kind)
        } catch (e: Exception) {
            toastShort("Could not open the file picker")
        }
    }

    /** The learner picked a subtitle file (main or translated). */
    fun onSubsPicked(kind: String, uri: Uri) {
        if (released) return
        val appCtx = act.applicationContext
        Thread {
            val bytes: ByteArray? = try {
                appCtx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            } catch (e: Throwable) {
                null
            }
            main.post {
                if (!released) {
                    if (bytes == null || bytes.isEmpty()) {
                        toastShort("Could not read that file")
                    } else if (bytes.size > 12 * 1024 * 1024) {
                        toastShort("That file is too big to be a subtitle file")
                    } else {
                        val list = Srt.parse(Srt.decode(bytes))
                        if (list.isEmpty()) {
                            toastShort("No subtitles found in that file")
                        } else if (kind == "second") {
                            engine.loadSecond(list, true)
                            toastShort("Translated subtitles loaded: " + list.size + " lines")
                        } else {
                            applyMainSubs(list, true)
                            toastShort("Subtitles loaded: " + list.size + " lines")
                        }
                    }
                }
            }
        }.start()
    }

    @Volatile private var subScanGen = 0

    /** Looks for text subtitle tracks inside the video (MKV / MP4). Read-only, in the background, never touches playback. */
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
            if (r.pictureTracks > 0) {
                Toast.makeText(act.applicationContext, "The subtitles inside this video are pictures and cannot be read. Open the 3-dot menu and upload an .srt file.", Toast.LENGTH_LONG).show()
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
        val list = Srt.parse(srt)
        if (list.isEmpty()) return
        applyMainSubs(list, true)
        toastShort("Subtitles loaded: " + track.label() + " (" + list.size + " lines)")
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

