package aurius.kura

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.ImageDecoder
import android.util.DisplayMetrics
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors

class DetailActivity : BaseVaultActivity() {
    private lateinit var db: BooruDb
    private lateinit var vault: CryptoVault
    private val bg = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var itemId: Long = -1
    private var itemIds: LongArray? = null
    private var currentPosition: Int = -1
    private var item: Item? = null
    @Volatile
    private var playFile: File? = null
    @Volatile
    private var isTornDown: Boolean = false
    @Volatile
    private var activePlaySessionId: Long = 0L
    private var videoView: VideoView? = null
    private var rotateBtn: ImageView? = null
    @Volatile
    private var activeAnimationBytes: ByteArray? = null
    private lateinit var rootFrame: FrameLayout
    private var isDetailTourShowing = false
    private var videoPosition: Int = 0

    private var isFullscreen = false
    private var normalMediaHeight = 0
    private var viewportHeight = 0
    private lateinit var bar: LinearLayout
    private lateinit var counterText: TextView
    private lateinit var sc: ScrollView
    private lateinit var col: LinearLayout
    private lateinit var blankRunway: View
    private lateinit var mediaContainer: FrameLayout
    private lateinit var mediaBox: FrameLayout
    private var zoomImageView: ZoomImageView? = null

    // Minimalistic bottom video controls
    private var controlsOverlay: FrameLayout? = null
    private var playPauseBtn: TextView? = null
    private var videoSeekBar: SeekBar? = null
    private var timeText: TextView? = null
    private var fsToggleBtn: TextView? = null
    private var volumeBar: SeekBar? = null
    private var muteBtn: TextView? = null
    private var isUserScrubbing = false
    private var controlsVisible = true

    // In-app volume & playback speed state
    private var currentMediaPlayer: android.media.MediaPlayer? = null
    private var isMuted: Boolean = false
    private var appVol: Float = 0.8f
    private var isHolding2x: Boolean = false
    private var speedBadge: TextView? = null
    private var seekNoticeBadge: TextView? = null
    private val hideSeekNoticeRunnable = Runnable { seekNoticeBadge?.visibility = View.GONE }

    private lateinit var metaBar: LinearLayout
    private lateinit var tagHeader: TextView
    private lateinit var tagListCol: LinearLayout
    private lateinit var editRow: LinearLayout
    private lateinit var autocompleteScroll: HorizontalScrollView
    private lateinit var info: TextView
    private lateinit var favBtn: TextView

    private val autoHideControlsRunnable = Runnable {
        if (videoView?.isPlaying == true) {
            hideControls()
        }
    }

    private val updateProgressRunnable = object : Runnable {
        override fun run() {
            val vv = videoView
            if (vv != null && vv.isPlaying && !isUserScrubbing) {
                val pos = vv.currentPosition
                val dur = vv.duration
                if (dur > 0) {
                    val safeMax = (dur - 800).coerceAtLeast(1)
                    videoSeekBar?.max = safeMax
                    videoSeekBar?.progress = pos.coerceAtMost(safeMax)
                    timeText?.text = "${formatTime(pos)} / ${formatTime(dur)}"
                }
            }
            mainHandler.postDelayed(this, 300)
        }
    }

    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        VaultLock.isPickingMedia = false
        if (uri == null || item == null) return@registerForActivityResult
        val curItem = item!!
        bg.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    vault.exportToStream(curItem.fileName, out)
                } ?: throw java.io.IOException("Cannot open output stream")
                mainHandler.post { toast("File exported successfully") }
            } catch (e: Exception) {
                mainHandler.post { toast("Export failed: ${e.message}") }
            }
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        volumeControlStream = android.media.AudioManager.STREAM_MUSIC
        db = BooruDb(this)
        vault = CryptoVault(this)

        itemId = intent.getLongExtra("id", -1)
        itemIds = intent.getLongArrayExtra("item_ids")
        currentPosition = intent.getIntExtra("position", -1)
        if (itemIds != null && currentPosition == -1 && itemId != -1L) {
            currentPosition = itemIds!!.indexOf(itemId)
        }
        if (itemId < 0 && (itemIds == null || itemIds!!.isEmpty())) { finish(); return }
        if (itemId < 0 && itemIds != null && currentPosition in itemIds!!.indices) {
            itemId = itemIds!![currentPosition]
        }

        build()
        load()
    }

    override fun onPause() {
        super.onPause()
        videoView?.let {
            if (it.isPlaying) {
                videoPosition = it.currentPosition
                it.pause()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed || !VaultLock.isUnlocked) return
        if (isFullscreen) {
            hideSystemBars()
            updateFullscreenDimensions()
        } else {
            applyWindowFeatures()
        }
        if (zoomImageView != null && zoomImageView?.drawable == null) {
            load()
        }
        videoView?.let {
            if (videoPosition > 0) {
                it.seekTo(videoPosition)
                it.start()
                playPauseBtn?.setPlayPauseIcon(true)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        zoomImageView?.setImageDrawable(null)
    }

    override fun onDestroy() {
        isTornDown = true
        VaultLock.isPickingMedia = false
        mainHandler.removeCallbacksAndMessages(null)
        bg.shutdownNow()
        val staleAnimBytes = activeAnimationBytes
        activeAnimationBytes = null
        try { staleAnimBytes?.let { java.util.Arrays.fill(it, 0.toByte()) } } catch (_: Exception) {}
        cleanPlayFile()
        super.onDestroy()
    }

    @Synchronized
    private fun cleanPlayFile() {
        activePlaySessionId = System.nanoTime()
        val stalePlayFile = playFile
        playFile = null
        val staleAnimBytes = activeAnimationBytes
        activeAnimationBytes = null
        try {
            staleAnimBytes?.let { java.util.Arrays.fill(it, 0.toByte()) }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                videoView?.stopPlayback()
            } else {
                mainHandler.post {
                    try { videoView?.stopPlayback() } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        // Zero-filling is unbounded I/O; keep it off the UI thread.
        BaseVaultActivity.runMaintenance {
            try {
                stalePlayFile?.let { vault.secureShred(it) }
                listOf("play", "play_decoy").forEach { dir ->
                    val d = File(cacheDir, dir)
                    if (d.exists()) d.listFiles()?.forEach { f -> if (f.isFile) vault.secureShred(f) }
                }
            } catch (_: Exception) {}
        }
    }

    private fun navigateNext() {
        val ids = itemIds ?: return
        if (ids.isEmpty()) return
        if (currentPosition < ids.size - 1) {
            ThemeUtils.vibrateTick(mediaContainer)
            navigateToIndex(currentPosition + 1)
        } else {
            toast("Last item")
        }
    }

    private fun navigatePrev() {
        val ids = itemIds ?: return
        if (ids.isEmpty()) return
        if (currentPosition > 0) {
            ThemeUtils.vibrateTick(mediaContainer)
            navigateToIndex(currentPosition - 1)
        } else {
            toast("First item")
        }
    }

    private fun navigateToIndex(pos: Int) {
        val ids = itemIds ?: return
        if (pos in ids.indices && pos != currentPosition) {
            currentPosition = pos
            itemId = ids[pos]
            cleanPlayFile()
            load()
        }
    }



    private fun formatTime(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        val m = totalSec / 60
        val s = totalSec % 60
        return String.format("%02d:%02d", m, s)
    }

    private fun resetAutoHide() {
        mainHandler.removeCallbacks(autoHideControlsRunnable)
        if (videoView?.isPlaying == true) {
            mainHandler.postDelayed(autoHideControlsRunnable, 3500)
        }
    }

    private fun showControls() {
        val overlay = controlsOverlay ?: return
        overlay.visibility = View.VISIBLE
        controlsVisible = true
        playPauseBtn?.setPlayPauseIcon(videoView?.isPlaying == true)
        val eff = if (isMuted) 0f else appVol
        volumeBar?.progress = (eff * 100).toInt()
        muteBtn?.setVolumeIcon(eff == 0f)
        resetAutoHide()
    }

    private fun hideControls() {
        controlsOverlay?.visibility = View.GONE
        controlsVisible = false
        mainHandler.removeCallbacks(autoHideControlsRunnable)
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun flashSeekNotice(text: String) {
        seekNoticeBadge?.let { badge ->
            badge.text = text
            badge.visibility = View.VISIBLE
            mainHandler.removeCallbacks(hideSeekNoticeRunnable)
            mainHandler.postDelayed(hideSeekNoticeRunnable, 800)
        }
    }

    private fun safeSeekForward() {
        val vv = videoView ?: return
        val dur = vv.duration
        if (dur <= 0) return
        val cur = vv.currentPosition
        val target = cur + 10000
        val safePos = if (target >= dur - 800) {
            0 // video was almost finished: loop cleanly to beginning without hitting native EOF error!
        } else {
            target.coerceIn(0, (dur - 800).coerceAtLeast(0))
        }
        try {
            vv.seekTo(safePos)
            videoSeekBar?.progress = safePos
            timeText?.text = "${formatTime(safePos)} / ${formatTime(dur)}"
        } catch (_: Exception) {}
        flashSeekNotice("+10s")
        ThemeUtils.vibrateTick(vv)
        resetAutoHide()
    }

    private fun safeSeekBackward() {
        val vv = videoView ?: return
        val dur = vv.duration
        val cur = vv.currentPosition
        val safePos = (cur - 10000).coerceAtLeast(0)
        try {
            vv.seekTo(safePos)
            videoSeekBar?.progress = safePos
            if (dur > 0) {
                timeText?.text = "${formatTime(safePos)} / ${formatTime(dur)}"
            }
        } catch (_: Exception) {}
        flashSeekNotice("-10s")
        ThemeUtils.vibrateTick(vv)
        resetAutoHide()
    }

    private fun roundedBg(color: Int, r: Float = 24f, strokeColor: Int = 0, strokeWidthPx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = r
            if (strokeWidthPx > 0 && strokeColor != 0) {
                setStroke(strokeWidthPx, strokeColor)
            }
        }

    private fun build() {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeUtils.backdrop(prefs)
        }

        // Top Action Bar
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(8))
        }

        val back = TextView(this).apply {
            text = "‹ Back"
            textSize = 17f
            setTextColor(prefs.textColor())
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { finish() }
        }
        bar.addView(back)

        counterText = TextView(this).apply {
            textSize = 13f
            setTextColor(prefs.textColorSecondary())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        bar.addView(counterText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val fullscreenTopBtn = TextView(this).apply {
            text = "Fullscreen"
            textSize = 13f
            setTextColor(prefs.textColor())
            background = ThemeUtils.buttonBackground(prefs, false, dp(14).toFloat())
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { setFullscreen(true) }
        }
        bar.addView(fullscreenTopBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(10) })

        val exportBtn = TextView(this).apply {
            text = "Export"
            textSize = 13f
            setTextColor(prefs.textColor())
            background = ThemeUtils.buttonBackground(prefs, false, dp(14).toFloat())
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener {
                item?.let { itm ->
                    VaultLock.isPickingMedia = true
                    exportDoc.launch(itm.fileName)
                }
            }
        }
        bar.addView(exportBtn)

        favBtn = TextView(this).apply {
            textSize = 24f
            setPadding(dp(16), dp(4), dp(12), dp(4))
            setOnClickListener { toggleFav() }
        }
        bar.addView(favBtn)

        root.addView(bar)

        // Main Content ScrollView: Holds centered media at the top, followed by metadata, trash, and Gelbooru tags
        sc = object : ScrollView(this) {
            override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
                if (isFullscreen) return false
                return super.onInterceptTouchEvent(ev)
            }

            override fun onTouchEvent(ev: MotionEvent): Boolean {
                if (isFullscreen) return false
                return super.onTouchEvent(ev)
            }
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            background = ThemeUtils.backdrop(prefs)
            isFillViewport = true
        }

        col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }

        // Media Container: Holds the media centered vertically and horizontally
        mediaContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        mediaBox = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        mediaContainer.addView(mediaBox)
        col.addView(mediaContainer)

        // Metadata & Actions Bar (Directly below media: file info + sleek black and white neon trash button)
        metaBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        info = TextView(this).apply {
            setTextColor(prefs.textColorSecondary())
            textSize = 12f
            setPadding(0, 0, dp(12), 0)
        }
        metaBar.addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Rotation is only wired up for still images. Video playback has no
        // content-rotation API in the platform (VideoView/MediaPlayer expose
        // none), and GIF frames are composited by ImageDecoder which ignores
        // user rotation -- offering the button there just produced broken output.
        // Always added, then hidden once the mime is known. Gating on `item`
        // here lost the race: build() runs before the async load assigns it, so
        // the button was never created for any item at all.
        // Rotation stays disabled for video (the platform exposes no
        // content-rotation API, so the old view-level turn squashed the picture)
        // and for GIF (ImageDecoder composes frames itself and ignores rotation).
        rotateBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_rotate)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ThemeUtils.surfaceGlass(prefs, dp(18).toFloat(), 1)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            contentDescription = "Rotate image"
            setOnClickListener { v ->
                ThemeUtils.vibrateClick(v)
                rotateMedia()
            }
        }
        metaBar.addView(rotateBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            rightMargin = dp(8)
        })

        val trashBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_neon_trash)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ThemeUtils.surfaceGlass(prefs, dp(18).toFloat(), 1)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener { v ->
                ThemeUtils.vibrateClick(v)
                confirmDelete()
            }
        }
        metaBar.addView(trashBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        col.addView(metaBar)

        tagHeader = TextView(this).apply {
            text = "Tags"
            setTextColor(prefs.textColorSecondary())
            textSize = 14f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(16), dp(14), dp(16), dp(8))
        }
        col.addView(tagHeader)

        // Gelbooru-style Line-by-Line Tag Listing
        tagListCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(8))
        }
        col.addView(tagListCol)

        // Interactive Tag Autocomplete Bar
        autocompleteScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(dp(16), 0, dp(16), dp(6))
        }
        val autocompleteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autocompleteScroll.addView(autocompleteRow)
        col.addView(autocompleteScroll)

        // Tag Editing Row
        editRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(20))
        }

        val edit = EditText(this).apply {
            hint = "Add tags: cat, blue_eyes …"
            setHintTextColor(Color.GRAY)
            setTextColor(prefs.textColor())
            textSize = 14f
            isSingleLine = true
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
            background = ThemeUtils.surfaceGlass(prefs, 18f, 1)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }
        editRow.addView(edit, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        // Scrolls so the tag input row sits flush with the bottom of the visible scroll area
        // (which is the keyboard's top edge when the IME insets are reported).
        val scrollTagRowIntoView = {
            sc.post {
                val visibleBottom = sc.height - sc.paddingBottom
                val want = editRow.top + editRow.height - visibleBottom
                val maxScroll = (sc.getChildAt(0)?.height ?: 0) - visibleBottom
                sc.scrollTo(0, want.coerceIn(0, maxOf(0, maxScroll)))
            }
        }
        val scrollTagRowIntoViewSoon = { sc.postDelayed({ scrollTagRowIntoView() }, 140) }

        edit.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) scrollTagRowIntoViewSoon()
        }
        edit.setOnClickListener { scrollTagRowIntoViewSoon() }

        var sanitizing = false
        edit.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                if (s == null) return
                // Safety net: some IMEs insert a newline despite single-line. Collapse it away.
                val raw = s.toString()
                if (!sanitizing && (raw.contains('\n') || raw.contains('\r'))) {
                    sanitizing = true
                    val cleaned = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
                    s.replace(0, s.length, cleaned)
                    edit.setSelection(cleaned.length)
                    sanitizing = false
                    return
                }
                val rawInput = raw
                val currentToken = rawInput.substringAfterLast(',').trim()
                if (currentToken.length >= 1) {
                    bg.execute {
                        val suggestions = db.suggestTags(currentToken, limit = 8)
                        mainHandler.post {
                            autocompleteRow.removeAllViews()
                            if (suggestions.isEmpty()) {
                                autocompleteScroll.visibility = View.GONE
                            } else {
                                autocompleteScroll.visibility = View.VISIBLE
                                scrollTagRowIntoView()
                                for ((tagName, count) in suggestions) {
                                    val chip = TextView(this@DetailActivity).apply {
                                        setText("${Tags.displayName(tagName)} ($count)")
                                        textSize = 12f
                                        setTextColor(Tags.color(tagName, prefs))
                                        background = ThemeUtils.surfaceGlass(prefs, 14f, 1)
                                        setPadding(dp(16), dp(8), dp(16), dp(8))
                                        setOnClickListener {
                                            ThemeUtils.vibrateTick(this)
                                            val fullText = edit.text.toString()
                                            val lastComma = fullText.lastIndexOf(',')
                                            val prefix = if (lastComma >= 0) {
                                                fullText.substring(0, lastComma + 1).trim() + " "
                                            } else ""
                                            edit.setText(prefix + tagName + ", ")
                                            edit.setSelection(edit.text.length)
                                            autocompleteScroll.visibility = View.GONE
                                            scrollTagRowIntoView()
                                        }
                                    }
                                    autocompleteRow.addView(chip, LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                     ).apply { rightMargin = dp(8) })
                                }
                            }
                        }
                    }
                } else {
                    autocompleteScroll.visibility = View.GONE
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        val addTagsAction: () -> Unit = {
            val textToAdd = edit.text.toString()
            val tags = Tags.parseList(textToAdd)
            if (tags.isEmpty()) {
                toast("No valid tags entered")
            } else {
                ThemeUtils.vibrateClick(edit)
                val startSession = VaultLock.sessionId
                bg.execute {
                    if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@execute
                    val cur = db.tagsFor(itemId).toMutableList()
                    cur.addAll(tags)
                    if (VaultLock.sessionId != startSession) return@execute
                    db.setTags(itemId, cur.distinct())
                    mainHandler.post {
                        if (isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@post
                        edit.text.clear()
                        autocompleteScroll.visibility = View.GONE
                        load()
                        toast("Added ${tags.joinToString(", ")}")
                        scrollTagRowIntoViewSoon()
                    }
                }
            }
        }

        edit.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE ||
                actionId == EditorInfo.IME_ACTION_GO ||
                actionId == EditorInfo.IME_ACTION_SEND
            ) {
                addTagsAction()
                true
            } else {
                false
            }
        }

        edit.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                if (event.action == KeyEvent.ACTION_DOWN) addTagsAction()
                true
            } else {
                false
            }
        }

        val save = TextView(this).apply {
            text = "ADD"
            textSize = 14f
            setTextColor(ThemeUtils.buttonTextColor(prefs, true))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ThemeUtils.buttonBackground(prefs, true, dp(18).toFloat())
            setPadding(dp(24), dp(16), dp(24), dp(16))
            setOnClickListener {
                addTagsAction()
            }
        }
        editRow.addView(save, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(12) })
        col.addView(editRow)

        // Blank runway below the tag box: guarantees there is scrollable space to lift the
        // input clear of the soft keyboard even when IME insets are not reported.
        blankRunway = View(this)
        col.addView(blankRunway, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(440)
        ))

        sc.addView(col)
        root.addView(sc)

        val dismissKeyboard = {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(edit.windowToken, 0)
            edit.clearFocus()
        }
        mediaContainer.setOnClickListener { dismissKeyboard() }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val topInset = if (prefs.edgeToEdge && !prefs.hideStatusBar) sysBars.top else 0
            val bottomInset = if (prefs.edgeToEdge) sysBars.bottom else 0
            val imeBottom = ime.bottom

            if (isFullscreen) {
                sc.setPadding(0, 0, 0, 0)
                col.setPadding(0, 0, 0, 0)
                updateFullscreenDimensions()
            } else {
                bar.setPadding(dp(16), topInset + dp(12), dp(16), dp(8))

                // Shrink the scroll viewport by the keyboard so the tag row can sit above it.
                sc.setPadding(0, 0, 0, imeBottom)
                col.setPadding(0, 0, 0, if (imeBottom > 0) dp(16) else dp(32) + bottomInset)

                val screenH = resources.displayMetrics.heightPixels
                val barH = dp(56) + topInset
                val occluded = if (imeBottom > 0) imeBottom else bottomInset
                val vpH = screenH - barH - occluded
                if (vpH > 300) {
                    viewportHeight = vpH
                    item?.let { itm -> updateMediaContainerHeight(itm.width, itm.height) }
                }
                if (imeBottom > 0) scrollTagRowIntoView()
            }
            insets
        }

        rootFrame = FrameLayout(this).apply {
            addView(root, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        setContentView(rootFrame)

        // Android Back button handler: if in fullscreen, back exits fullscreen without finishing activity!
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    setFullscreen(false)
                } else if (controlsVisible && videoView != null) {
                    hideControls()
                } else {
                    isEnabled = false
                    finish()
                }
            }
        })
    }

    private fun TextView.setPlayPauseIcon(playing: Boolean) {
        setCompoundDrawablesWithIntrinsicBounds(
            if (playing) R.drawable.ic_pause else R.drawable.ic_play, 0, 0, 0
        )
    }

    private fun TextView.setFullscreenIcon(full: Boolean) {
        setCompoundDrawablesWithIntrinsicBounds(
            if (full) R.drawable.ic_collapse else R.drawable.ic_expand, 0, 0, 0
        )
    }

    private fun TextView.setVolumeIcon(muted: Boolean) {
        setCompoundDrawablesWithIntrinsicBounds(
            if (muted) R.drawable.ic_volume_off else R.drawable.ic_volume_up, 0, 0, 0
        )
    }

    private fun getRealScreenDimensions(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            Pair(bounds.width(), bounds.height())
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            Pair(dm.widthPixels, dm.heightPixels)
        }
    }

    private fun computeMediaContainerHeight(itemW: Int, itemH: Int): Int {
        val screenW = resources.displayMetrics.widthPixels
        val screenH = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val minH = (160 * density).toInt()
        val maxH = if (viewportHeight > 300) viewportHeight else (screenH * 0.72).toInt()

        if (itemW <= 0 || itemH <= 0) return maxH
        val targetH = (screenW.toLong() * itemH / itemW).toInt()
        return targetH.coerceIn(minH, maxH)
    }

    private fun updateMediaContainerHeight(itemW: Int, itemH: Int) {
        if (isFullscreen) return
        val targetH = computeMediaContainerHeight(itemW, itemH)
        val lp = mediaContainer.layoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            targetH
        )
        if (lp.height != targetH) {
            lp.height = targetH
            mediaContainer.layoutParams = lp
        }
    }

    private fun updateFullscreenDimensions() {
        val (_, realH) = getRealScreenDimensions()
        val lp = mediaContainer.layoutParams
        if (lp == null) {
            mediaContainer.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                realH
            )
        } else if (lp.height != realH) {
            lp.height = realH
            mediaContainer.layoutParams = lp
        }
        col.setPadding(0, 0, 0, 0)
        sc.setPadding(0, 0, 0, 0)
        sc.scrollTo(0, 0)
    }

    private fun setFullscreen(enable: Boolean) {
        if (isFullscreen == enable) return
        isFullscreen = enable

        if (enable) {
            item?.let { itm ->
                if (itm.mime.startsWith("video") && itm.width > itm.height) {
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                }
            }
            bar.visibility = View.GONE
            metaBar.visibility = View.GONE
            tagHeader.visibility = View.GONE
            tagListCol.visibility = View.GONE
            autocompleteScroll.visibility = View.GONE
            editRow.visibility = View.GONE
            blankRunway.visibility = View.GONE

            updateFullscreenDimensions()
            hideSystemBars()
            fsToggleBtn?.setFullscreenIcon(true)
            resetAutoHide()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
            bar.visibility = View.VISIBLE
            metaBar.visibility = if (prefs.showMediaDetails) View.VISIBLE else View.GONE
            tagHeader.visibility = View.VISIBLE
            tagListCol.visibility = View.VISIBLE
            editRow.visibility = View.VISIBLE
            blankRunway.visibility = View.VISIBLE

            val density = resources.displayMetrics.density
            val dp = { v: Int -> (v * density).toInt() }
            col.setPadding(0, 0, 0, dp(32))

            val itm = item
            if (itm != null) {
                updateMediaContainerHeight(itm.width, itm.height)
            } else {
                val vpH = if (viewportHeight > 300) viewportHeight else (resources.displayMetrics.heightPixels * 0.75).toInt()
                mediaContainer.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    vpH
                )
            }
            showSystemBars()
            fsToggleBtn?.setFullscreenIcon(false)
            resetAutoHide()
        }
        zoomImageView?.resetToFitCenter()
    }

    private fun hideSystemBars() {
        try {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } catch (_: Throwable) {}
        try {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
        } catch (_: Throwable) {}
    }

    private fun showSystemBars() {
        try {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.show(WindowInsetsCompat.Type.systemBars())
        } catch (_: Throwable) {}
        applyWindowFeatures()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (isFullscreen) {
            updateFullscreenDimensions()
            hideSystemBars()
        } else {
            val screenH = resources.displayMetrics.heightPixels
            val density = resources.displayMetrics.density
            val dp = { v: Int -> (v * density).toInt() }
            val vpH = screenH - dp(56)
            if (vpH > 300) viewportHeight = vpH

            val itm = item
            if (itm != null) {
                updateMediaContainerHeight(itm.width, itm.height)
            } else {
                mediaContainer.layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    if (viewportHeight > 300) viewportHeight else (screenH * 0.75).toInt()
                )
            }
        }
        zoomImageView?.resetToFitCenter()
    }

    private fun load() {
        val startSession = VaultLock.sessionId
        bg.execute {
            if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@execute
            val it = try { db.get(itemId) } catch (_: Exception) { null }
            val tagCounts = try { db.tagsWithCountFor(itemId) } catch (_: Exception) { emptyList() }
            val fileBytes = if (it != null) {
                try { vault.fileFor(it.fileName).length() } catch (_: Exception) { 0L }
            } else 0L

            mainHandler.post {
                if (isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@post
                if (it == null) { finish(); return@post }
                item = it
                rotateBtn?.visibility =
                    if (it.mime.startsWith("image/") && it.mime != "image/gif") View.VISIBLE else View.GONE
                favBtn.text = if (it.favorite) "★" else "☆"
                favBtn.setTextColor(if (it.favorite) prefs.accentColor() else prefs.textColorSecondary())

                val durText = if (it.durationMs > 0) " • ${it.durationMs / 1000}s" else ""
                val sizeText = if (fileBytes > 0) {
                    if (fileBytes < 1024 * 1024) "${fileBytes / 1024} KB"
                    else String.format(java.util.Locale.US, "%.1f MB", fileBytes / (1024f * 1024f))
                } else ""
                val sizePart = if (sizeText.isNotEmpty()) " • $sizeText" else ""
                info.text = "${it.mime.substringAfter('/').uppercase()} • ${it.width}×${it.height}$durText$sizePart"
                metaBar.visibility = if (prefs.showMediaDetails && !isFullscreen) View.VISIBLE else View.GONE

                val ids = itemIds
                if (ids != null && ids.size > 1) {
                    counterText.text = "${currentPosition + 1} / ${ids.size}"
                } else {
                    counterText.text = ""
                }

                renderMedia(it)
                renderTagsWithCounts(tagCounts)
                checkDetailTutorial()
            }
        }
    }

    private fun checkDetailTutorial() {
        if (isDetailTourShowing || prefs.detailTutorialCompleted || VaultLock.isDecoy) return
        if (isFinishing || isDestroyed) return
        rootFrame.postDelayed({
            if (isDetailTourShowing || prefs.detailTutorialCompleted || isFinishing || isDestroyed) return@postDelayed
            val steps = mutableListOf<SpotlightTourView.Step>()

            if (mediaContainer.isShown && mediaContainer.height > 100) {
                steps.add(
                    SpotlightTourView.Step(
                        target = mediaContainer,
                        title = "Interactive Media Viewer",
                        description = "Pinch with two fingers to zoom. Tap once to toggle immersive full-screen mode, and swipe left or right across the screen to browse between files."
                    )
                )
            }

            if (metaBar.isShown) {
                steps.add(
                    SpotlightTourView.Step(
                        target = metaBar,
                        title = "File Info & Quick Actions",
                        description = "Check file dimensions, format, and size. For still photos, tap ⟳ to rotate 90° clockwise. Tap 🗑 to permanently shred and delete from the vault."
                    )
                )
            }

            if (tagHeader.isShown) {
                steps.add(
                    SpotlightTourView.Step(
                        target = tagHeader,
                        title = "Booru Tag System",
                        description = "Tags have colored categories:\n• Character (Blue): c:miku or char:miku\n• Artist (Coral): a:name or art:name\n• Series (Purple): s:series or copy:title\n• Meta (Orange): m:tag\nPlain tags stay neutral. Tap ✕ to remove."
                    )
                )
            }

            if (editRow.isShown) {
                steps.add(
                    SpotlightTourView.Step(
                        target = editRow,
                        title = "Add & Autocomplete Tags",
                        description = "Type tags separated by commas or spaces. Use 1-letter prefixes (c:, a:, s:, m:) or full names (char:, art:, series:). Suggestions appear as you type—tap ADD or Enter to save."
                    )
                )
            }

            if (steps.isEmpty()) return@postDelayed
            isDetailTourShowing = true
            SpotlightTourView.show(rootFrame, prefs, steps) {
                prefs.detailTutorialCompleted = true
                isDetailTourShowing = false
                sc.smoothScrollTo(0, 0)
            }
        }, 350)
    }

    private fun renderMedia(it: Item) {
        mediaBox.removeAllViews()
        mainHandler.removeCallbacks(updateProgressRunnable)
        mainHandler.removeCallbacks(autoHideControlsRunnable)
        cleanPlayFile()
        videoView = null
        zoomImageView = null
        controlsOverlay = null
        playPauseBtn = null
        videoSeekBar = null
        timeText = null
        fsToggleBtn = null
        volumeBar = null
        muteBtn = null

        val screenH = resources.displayMetrics.heightPixels
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        if (isFullscreen) {
            if (it.mime.startsWith("video") && it.width > it.height) {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            } else {
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_USER
            }
            updateFullscreenDimensions()
        }

        if (it.mime.startsWith("video")) {
            if (!isFullscreen) {
                updateMediaContainerHeight(it.width, it.height)
            }

            val videoContainer = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                setBackgroundColor(Color.BLACK)
            }

            val vv = VideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
            }
            videoView = vv
            videoContainer.addView(vv)

            val badge = TextView(this).apply {
                text = "2× SPEED"
                textSize = 13f
                setTextColor(prefs.textColor())
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = roundedBg(0xCC000000.toInt(), dp(16).toFloat())
                setPadding(dp(16), dp(8), dp(16), dp(8))
                visibility = View.GONE
            }
            speedBadge = badge
            videoContainer.addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = dp(20)
            })

            val seekBadge = TextView(this).apply {
                textSize = 16f
                setTextColor(prefs.textColor())
                setTypeface(null, android.graphics.Typeface.BOLD)
                background = roundedBg(0xDD000000.toInt(), dp(18).toFloat())
                setPadding(dp(20), dp(10), dp(20), dp(10))
                visibility = View.GONE
            }
            seekNoticeBadge = seekBadge
            videoContainer.addView(seekBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

            isMuted = prefs.alwaysStartMuted
            appVol = prefs.appVolume
            val initialVolProgress = if (isMuted) 0 else (appVol * 100).toInt()

            // Minimalistic Bottom Overlay (No buttons in the middle!)
            val overlay = FrameLayout(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }
            controlsOverlay = overlay

            // Docked at the bottom
            val bottomController = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(
                        0x00000000,          // Transparent fade
                        0x88000000.toInt(),  // Translucent dark glass
                        0xDD000000.toInt()   // Solid dark base
                    )
                )
                setPadding(dp(12), dp(10), dp(12), dp(10))
                setOnClickListener { /* consume clicks to prevent dismiss */ }
            }

            // 1. Scrubber SeekBar (Minimalist track)
            val scrubber = SeekBar(this).apply {
                max = 1000
                progress = 0
                progressTintList = ColorStateList.valueOf(prefs.accentColor())
                thumbTintList = ColorStateList.valueOf(prefs.accentColor())
                setPadding(dp(6), 0, dp(6), 0)
            }
            videoSeekBar = scrubber
            scrubber.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val dur = vv.duration
                        val safePos = if (dur > 0) progress.coerceIn(0, (dur - 800).coerceAtLeast(0)) else progress
                        try { vv.seekTo(safePos) } catch (_: Exception) {}
                        timeText?.text = "${formatTime(safePos)} / ${formatTime(dur)}"
                        resetAutoHide()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {
                    isUserScrubbing = true
                    resetAutoHide()
                }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    isUserScrubbing = false
                    resetAutoHide()
                }
            })
            bottomController.addView(scrubber, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))

            // 2. Minimalist Single-Line Controls Row
            val controlsRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(4), dp(4), dp(4), 0)
            }

            // Play / Pause
            val playBtn = TextView(this).apply {
                text = ""
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(prefs.textColor())
                gravity = Gravity.CENTER
                background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setPlayPauseIcon(true)
                setOnClickListener {
                    if (vv.isPlaying) {
                        vv.pause()
                        setPlayPauseIcon(false)
                        mainHandler.removeCallbacks(autoHideControlsRunnable)
                    } else {
                        vv.start()
                        setPlayPauseIcon(true)
                        resetAutoHide()
                    }
                }
            }
            playPauseBtn = playBtn
            controlsRow.addView(playBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })

            // Rewind 10s
            val rewindBtn = TextView(this).apply {
                text = "-10s"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(prefs.textColor())
                gravity = Gravity.CENTER
                background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setOnClickListener { safeSeekBackward() }
            }
            controlsRow.addView(rewindBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(6) })

            // Forward 10s
            val fwdBtn = TextView(this).apply {
                text = "+10s"
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(prefs.textColor())
                gravity = Gravity.CENTER
                background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
                setPadding(dp(8), dp(5), dp(8), dp(5))
                setOnClickListener { safeSeekForward() }
            }
            controlsRow.addView(fwdBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(8) })

            // Timestamp (00:00 / 00:00)
            val timeLbl = TextView(this).apply {
                text = "00:00 / 00:00"
                textSize = 11f
                setTextColor(prefs.textColorSecondary())
                setPadding(dp(2), dp(2), dp(4), dp(2))
            }
            timeText = timeLbl
            controlsRow.addView(timeLbl)

            // Spacer
            val spacer = View(this)
            controlsRow.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

            // Mute / Unmute
            val mute = TextView(this).apply {
                text = ""
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(prefs.textColor())
                gravity = Gravity.CENTER
                background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setVolumeIcon(isMuted || appVol == 0f)
            }
            muteBtn = mute

            // In-App Independent Volume SeekBar (0..100)
            val vBar = SeekBar(this).apply {
                max = 100
                progress = initialVolProgress
                progressTintList = ColorStateList.valueOf(prefs.accentColor())
                thumbTintList = ColorStateList.valueOf(prefs.textColor())
                setPadding(dp(4), 0, dp(4), 0)
            }
            volumeBar = vBar
            vBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val vol = progress / 100f
                        appVol = vol
                        prefs.appVolume = vol
                        if (vol > 0f) {
                            isMuted = false
                        }
                        val eff = if (isMuted) 0f else appVol
                        try { currentMediaPlayer?.setVolume(eff, eff) } catch (_: Exception) {}
                        mute.setVolumeIcon(eff == 0f)
                        resetAutoHide()
                    }
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { resetAutoHide() }
                override fun onStopTrackingTouch(sb: SeekBar?) { resetAutoHide() }
            })

            mute.setOnClickListener {
                isMuted = !isMuted
                if (!isMuted && appVol <= 0.05f) {
                    appVol = 0.8f
                    prefs.appVolume = 0.8f
                }
                val eff = if (isMuted) 0f else appVol
                try { currentMediaPlayer?.setVolume(eff, eff) } catch (_: Exception) {}
                vBar.progress = (eff * 100).toInt()
                mute.setVolumeIcon(eff == 0f)
                ThemeUtils.vibrateTick(mute)
                resetAutoHide()
            }
            controlsRow.addView(mute)
            controlsRow.addView(vBar, LinearLayout.LayoutParams(dp(75), LinearLayout.LayoutParams.WRAP_CONTENT))

            // Fullscreen toggle button
            val fsBtn = TextView(this).apply {
                text = ""
                textSize = 11f
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(prefs.textColor())
                gravity = Gravity.CENTER
                background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
                setPadding(dp(10), dp(8), dp(10), dp(8))
                setFullscreenIcon(isFullscreen)
                setOnClickListener {
                    setFullscreen(!isFullscreen)
                    setFullscreenIcon(isFullscreen)
                    resetAutoHide()
                }
            }
            fsToggleBtn = fsBtn
            controlsRow.addView(fsBtn, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) })

            bottomController.addView(controlsRow)

            overlay.addView(bottomController, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
            ))

            videoContainer.addView(overlay)

            // Video Gestures:
            // Single tap: toggle controls
            // Double-tap left: seek -10s
            // Double-tap right: seek +10s
            // Double-tap center: toggle fullscreen
            // Long press: hold for 2x speed (releases back to 1x)
            // Horizontal fling: navigate next/prev
            val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    toggleControls()
                    return true
                }

                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val w = videoContainer.width
                    if (w > 0) {
                        val x = e.x
                        when {
                            x < w * 0.35f -> safeSeekBackward()
                            x > w * 0.65f -> safeSeekForward()
                            else -> {
                                setFullscreen(!isFullscreen)
                                ThemeUtils.vibrateClick(videoContainer)
                            }
                        }
                    } else {
                        setFullscreen(!isFullscreen)
                    }
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    if (vv.isPlaying && Build.VERSION.SDK_INT >= 23) {
                        try {
                            currentMediaPlayer?.let { mp ->
                                mp.playbackParams = mp.playbackParams.setSpeed(2.0f)
                                isHolding2x = true
                                speedBadge?.visibility = View.VISIBLE
                                ThemeUtils.vibrateClick(videoContainer)
                            }
                        } catch (_: Exception) {}
                    }
                }

                override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                    if (e1 != null) {
                        val dx = e2.x - e1.x
                        val dy = e2.y - e1.y
                        if (Math.abs(dx) > Math.abs(dy) * 1.2f && Math.abs(dx) > 100) {
                            if (dx < 0) {
                                navigateNext()
                            } else {
                                navigatePrev()
                            }
                            ThemeUtils.vibrateTick(videoContainer)
                            return true
                        }
                    }
                    return false
                }
            })

            val touchListener = View.OnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                    if (isHolding2x) {
                        isHolding2x = false
                        speedBadge?.visibility = View.GONE
                        if (Build.VERSION.SDK_INT >= 23) {
                            try {
                                currentMediaPlayer?.let { mp ->
                                    mp.playbackParams = mp.playbackParams.setSpeed(1.0f)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                }
                gd.onTouchEvent(event)
                true
            }
            vv.setOnTouchListener(touchListener)
            videoContainer.setOnTouchListener(touchListener)
            overlay.setOnTouchListener(touchListener)

            mediaBox.addView(videoContainer)

            val currentJobId = activePlaySessionId
            val startVaultSession = VaultLock.sessionId
            bg.execute {
                try {
                    if (isTornDown || isFinishing || isDestroyed || activePlaySessionId != currentJobId || VaultLock.sessionId != startVaultSession) {
                        return@execute
                    }
                    val f = vault.decryptToPlayCache(it.fileName)
                    if (isTornDown || isFinishing || isDestroyed || activePlaySessionId != currentJobId || VaultLock.sessionId != startVaultSession) {
                        vault.secureShred(f)
                        return@execute
                    }
                    playFile = f
                    mainHandler.post {
                        if (isTornDown || isFinishing || isDestroyed || activePlaySessionId != currentJobId || VaultLock.sessionId != startVaultSession) {
                            cleanPlayFile()
                            return@post
                        }
                        vv.setOnPreparedListener { mp ->
                            if (isTornDown || isFinishing || isDestroyed || activePlaySessionId != currentJobId || VaultLock.sessionId != startVaultSession) {
                                cleanPlayFile()
                                return@setOnPreparedListener
                            }
                            currentMediaPlayer = mp
                            if (mp.videoWidth > 0 && mp.videoHeight > 0) {
                                updateMediaContainerHeight(mp.videoWidth, mp.videoHeight)
                            }
                            mp.isLooping = true
                            val eff = if (isMuted) 0f else appVol
                            try { mp.setVolume(eff, eff) } catch (_: Exception) {}
                            val dur = vv.duration
                            if (dur > 0) {
                                scrubber.max = (dur - 800).coerceAtLeast(1)
                                timeLbl.text = "00:00 / ${formatTime(dur)}"
                            }
                            if (prefs.autoplayDetailVideos) {
                                playBtn.setPlayPauseIcon(true)
                                vv.start()
                                mainHandler.post(updateProgressRunnable)
                                resetAutoHide()
                            } else {
                                playBtn.setPlayPauseIcon(false)
                                showControls()
                            }
                        }
                        vv.setOnErrorListener { _, _, _ ->
                            if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startVaultSession) return@setOnErrorListener true
                            try {
                                vv.seekTo(0)
                                if (prefs.autoplayDetailVideos) {
                                    vv.start()
                                    playBtn.setPlayPauseIcon(true)
                                } else {
                                    playBtn.setPlayPauseIcon(false)
                                }
                                return@setOnErrorListener true
                            } catch (_: Exception) {}
                            playBtn.setPlayPauseIcon(false)
                            showControls()
                            true
                        }
                        vv.setVideoPath(f.absolutePath)
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            toast("Failed to decrypt video: ${e.message}")
                        }
                    }
                }
            }
        } else {
            if (!isFullscreen) {
                updateMediaContainerHeight(it.width, it.height)
            }

            val iv = ZoomImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER
                )
                setBackgroundColor(Color.BLACK)
                onSingleTap = {
                    setFullscreen(!isFullscreen)
                }
                onSwipeLeft = {
                    ThemeUtils.vibrateTick(this)
                    navigateNext()
                }
                onSwipeRight = {
                    ThemeUtils.vibrateTick(this)
                    navigatePrev()
                }
            }
            zoomImageView = iv
            mediaBox.addView(iv)

            val gifBoxW = if (iv.width > 0) iv.width else resources.displayMetrics.widthPixels
            val gifBoxH = if (iv.height > 0) iv.height else (if (viewportHeight > 300) viewportHeight else (screenH * 0.72).toInt())
            val startVaultSession = VaultLock.sessionId
            bg.execute {
                try {
                    if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startVaultSession) return@execute
                    if (it.mime == "image/gif" && Build.VERSION.SDK_INT >= 28) {
                        val anim = vault.animatedImage(it.fileName, gifBoxW, gifBoxH)
                        if (anim != null) {
                            activeAnimationBytes = anim.sourceBytes
                            mainHandler.post {
                                if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startVaultSession) {
                                    activeAnimationBytes?.let { b -> java.util.Arrays.fill(b, 0.toByte()) }
                                    activeAnimationBytes = null
                                    return@post
                                }
                                iv.setImageDrawable(anim.drawable)
                                if (anim.drawable.intrinsicWidth > 0 && anim.drawable.intrinsicHeight > 0) {
                                    updateMediaContainerHeight(anim.drawable.intrinsicWidth, anim.drawable.intrinsicHeight)
                                }
                                if (anim.drawable is android.graphics.drawable.Animatable) {
                                    anim.drawable.start()
                                }
                            }
                            return@execute
                        }
                    }

                    // A bare `?:` chain is dangerous here: on OOM each stage
                    // returns null, so the same file gets decrypted and decoded
                    // two or three more times while still out of memory. Retry
                    // once at a smaller size instead, and give up cleanly.
                    var bmp = try {
                        vault.decodeDisplayImage(it.fileName, maxDim = 3200, extraRotation = it.rotation)
                    } catch (_: OutOfMemoryError) {
                        android.util.Log.w("KuraDetail", "OOM on full-res decode, retrying small")
                        try { vault.sampledImage(it.fileName, 1024, extraRotation = it.rotation) }
                        catch (_: OutOfMemoryError) { null } catch (_: Throwable) { null }
                    } catch (_: Throwable) { null }
                    if (bmp == null && !it.mime.startsWith("video")) {
                        bmp = try {
                            vault.sampledImage(it.fileName, 1024, extraRotation = it.rotation)
                        } catch (_: OutOfMemoryError) { null } catch (_: Throwable) { null }
                    }

                    mainHandler.post {
                        if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startVaultSession) return@post
                        if (bmp != null) {
                            iv.setImageBitmap(bmp)
                            updateMediaContainerHeight(bmp.width, bmp.height)
                        } else {
                            toast("Failed to render image")
                        }
                    }
                } catch (_: Throwable) {
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed && VaultLock.sessionId == startVaultSession) {
                            toast("Image decoding error")
                        }
                    }
                }
            }
        }
    }

    private fun renderTagsWithCounts(tagCounts: List<Pair<String, Int>>) {
        tagListCol.removeAllViews()
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        tagHeader.text = "Tags (${tagCounts.size})"

        if (tagCounts.isEmpty()) {
            val emptyTv = TextView(this).apply {
                text = "No tags yet. Add tags below."
                setTextColor(prefs.textColorSecondary())
                textSize = 13f
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            tagListCol.addView(emptyTv)
            return
        }

        for ((tagName, count) in tagCounts) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = ThemeUtils.surfaceGlass(prefs, dp(12).toFloat(), 1)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    ThemeUtils.vibrateTick(this)
                    val intent = Intent(this@DetailActivity, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        putExtra("search_tag", Tags.displayName(tagName))
                    }
                    startActivity(intent)
                    finish()
                }
            }

            val tagColor = Tags.color(tagName, prefs)

            val nameTv = TextView(this).apply {
                text = Tags.displayName(tagName)
                textSize = 14f
                setTextColor(tagColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            row.addView(nameTv)

            val countTv = TextView(this).apply {
                text = " ($count)"
                textSize = 12f
                setTextColor(prefs.textColorSecondary())
                setPadding(dp(4), 0, 0, 0)
            }
            row.addView(countTv)

            val spacer = View(this)
            row.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))

            val deleteTagBtn = TextView(this).apply {
                text = "✕"
                textSize = 13f
                setTextColor(prefs.textColorSecondary())
                setPadding(dp(10), dp(4), dp(4), dp(4))
                setOnClickListener {
                    ThemeUtils.vibrateTick(this)
                    removeTag(tagName)
                }
            }
            row.addView(deleteTagBtn)

            tagListCol.addView(row, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(6)
            })
        }
    }

    private fun removeTag(t: String) {
        val startSession = VaultLock.sessionId
        bg.execute {
            if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@execute
            val cur = db.tagsFor(itemId).toMutableList()
            cur.remove(t)
            if (VaultLock.sessionId != startSession) return@execute
            db.setTags(itemId, cur)
            mainHandler.post {
                if (!isFinishing && !isDestroyed && VaultLock.sessionId == startSession) load()
            }
        }
    }

    private fun toggleFav() {
        ThemeUtils.vibrateClick(favBtn)
        val startSession = VaultLock.sessionId
        bg.execute {
            if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@execute
            val cur = db.get(itemId) ?: return@execute
            if (VaultLock.sessionId != startSession) return@execute
            db.setFavorite(itemId, !cur.favorite)
            mainHandler.post {
                if (!isFinishing && !isDestroyed && VaultLock.sessionId == startSession) load()
            }
        }
    }

    private fun confirmDelete() {
        val mediaType = if (item?.mime?.startsWith("video") == true) "video" else if (item?.mime == "image/gif") "GIF" else "photo"
        val startSession = VaultLock.sessionId
        AlertDialog.Builder(this)
            .setTitle("Delete $mediaType from Vault?")
            .setMessage("Permanently remove this $mediaType from your encrypted vault?")
            .setPositiveButton("Delete") { _, _ ->
                bg.execute {
                    if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@execute
                    cleanPlayFile()
                    val name = db.delete(itemId)
                    if (name != null && VaultLock.sessionId == startSession) vault.delete(name)
                    mainHandler.post {
                        if (isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@post
                        toast("Deleted $mediaType from vault")
                        val ids = itemIds
                        if (ids != null && ids.size > 1) {
                            val newIds = ids.filter { it != itemId }.toLongArray()
                            itemIds = newIds
                            val nextPos = currentPosition.coerceAtMost(newIds.size - 1)
                            navigateToIndex(nextPos)
                        } else {
                            finish()
                        }
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun rotateMedia() {
        val cur = item ?: return
        val startSession = VaultLock.sessionId
        bg.execute {
            if (isTornDown || isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@execute
            val newRot = db.rotateItem(cur.id, 90)
            CryptoVault.evictThumbnail(cur.fileName)
            mainHandler.post {
                if (isFinishing || isDestroyed || VaultLock.sessionId != startSession) return@post
                toast("Rotated to ${newRot}°")
                load()
            }
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
