package aurius.kura

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.widget.VideoView
import java.io.File
import java.nio.ByteBuffer
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.UUID
import java.util.concurrent.Executors

/** Max queued grid thumbnail decodes before the oldest pending one is dropped. */
private const val GRID_QUEUE_DEPTH = 24

/** How many tag suggestions the row shows at once. */
private const val SUGGEST_LIMIT = 14

class MainActivity : BaseVaultActivity() {
    private lateinit var db: BooruDb
    private lateinit var vault: CryptoVault
    private val bg = Executors.newFixedThreadPool(4)
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Dedicated pool for grid thumbnail decodes.
     *
     * Separate from [bg] so a burst of scrolling can't starve import/export work,
     * and bounded with `DiscardOldestPolicy` so a fast fling queues at most
     * [GRID_QUEUE_DEPTH] decodes instead of every cell that scrolled past —
     * previously each bind enqueued a full decrypt+decode that always ran to
     * completion even after the cell was gone.
     */
    private val gridDecodeExecutor: java.util.concurrent.ThreadPoolExecutor by lazy {
        java.util.concurrent.ThreadPoolExecutor(
            2, 3, 30L, java.util.concurrent.TimeUnit.SECONDS,
            java.util.concurrent.LinkedBlockingQueue(GRID_QUEUE_DEPTH),
            { r -> Thread(r, "kura-grid-decode").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } },
            java.util.concurrent.ThreadPoolExecutor.DiscardOldestPolicy()
        )
    }

    /** Marks a rebind that only needs selection visuals refreshed. */
    private val PAYLOAD_SELECTION = Any()

    private lateinit var rootFrame: FrameLayout
    private lateinit var rootLayout: LinearLayout
    private lateinit var bar: LinearLayout
    private lateinit var search: EditText
    private lateinit var srow: LinearLayout
    private lateinit var sc: HorizontalScrollView
    private lateinit var suggRow: LinearLayout
    private lateinit var grid: RecyclerView
    private lateinit var empty: TextView
    private lateinit var countView: TextView
    private lateinit var favToggle: TextView
    private lateinit var sortToggle: TextView
    private lateinit var collapseBtn: TextView
    private lateinit var progress: ProgressBar
    private lateinit var topImportBtn: ImageView
    private lateinit var setBtn: TextView
    private var bottomBar: LinearLayout? = null
    private var circularFab: ImageView? = null
    private lateinit var categoryBar: LinearLayout
    private lateinit var categoryCapsule: LinearLayout
    private val categoryViews = mutableMapOf<String, TextView>()

    // Reels Feed components
    private lateinit var reelsContainer: FrameLayout
    private lateinit var reelsMediaBox: FrameLayout
    private lateinit var reelsEmptyText: TextView
    private lateinit var reelsTypePill: TextView
    private lateinit var reelsDimensText: TextView
    private lateinit var reelsTagsRow: LinearLayout
    private var reelsFavBtn: TextView? = null
    private var reelsSoundBtn: TextView? = null
    private var reelsHeartAnim: TextView? = null
    private var reelsVideoView: VideoView? = null
    private var reelsMediaPlayer: MediaPlayer? = null
    private var reelsImageView: ImageView? = null
    @Volatile
    private var reelsPlayFile: File? = null
    @Volatile
    private var activeReelsAnimationBytes: ByteArray? = null
    @Volatile
    private var activeReelsJobId: Long = 0L
    private var reelsPool: List<Item> = emptyList()
    private val reelsHistory: MutableList<Item> = mutableListOf()
    private var reelsIndex: Int = -1
    private var reelsMuted: Boolean = false

    // Pinch-to-column and horizontal category swipe detectors
    private var lastScaleTime = 0L
    private val scaleDetector by lazy {
        ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                val now = System.currentTimeMillis()
                if (now - lastScaleTime < 350) return false

                val curCols = prefs.columns
                if (factor > 1.25f && curCols > 2) {
                    val newCols = curCols - 1
                    prefs.columns = newCols
                    (grid.layoutManager as? GridLayoutManager)?.spanCount = newCols
                    adapter.notifyDataSetChanged()
                    lastScaleTime = now
                    ThemeUtils.vibrateTick(grid)
                    Toast.makeText(this@MainActivity, "$newCols columns", Toast.LENGTH_SHORT).show()
                    return true
                } else if (factor < 0.80f && curCols < 4) {
                    val newCols = curCols + 1
                    prefs.columns = newCols
                    (grid.layoutManager as? GridLayoutManager)?.spanCount = newCols
                    adapter.notifyDataSetChanged()
                    lastScaleTime = now
                    ThemeUtils.vibrateTick(grid)
                    Toast.makeText(this@MainActivity, "$newCols columns", Toast.LENGTH_SHORT).show()
                    return true
                }
                return false
            }
        })
    }

    private val gridSwipeDetector by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null && !isMultiSelect) {
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 80) {
                        if (dx < 0) cycleCategory(true) else cycleCategory(false)
                        return true
                    }
                }
                return false
            }
        })
    }

    private var lastCategorySwitchTime = 0L
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var isHorizontalGridSwipe = false

    private var items: List<Item> = emptyList()

    private lateinit var adapter: GridAdapter
    private var searchRunnable: Runnable? = null
    // The query the visible suggestion row belongs to, read on the worker thread.
    @Volatile private var suggQuery = ""

    private var isMultiSelect: Boolean = false
    private val selectedIds = mutableSetOf<Long>()
    private lateinit var multiSelectBar: LinearLayout
    private lateinit var multiSelectCount: TextView
    private lateinit var selectAllBtn: TextView

    private var currentTheme: String = ""
    private var currentAccent: String = ""
    private var currentImmersive: Boolean = true
    private var currentHideStatus: Boolean = false

    private var pendingExportItem: Item? = null
    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        VaultLock.isPickingMedia = false
        if (uri == null || pendingExportItem == null) return@registerForActivityResult
        val curItem = pendingExportItem!!
        bg.execute {
            try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    vault.exportToStream(curItem.fileName, out)
                } ?: throw java.io.IOException("Cannot open output stream")
                mainHandler.post { Toast.makeText(this, "Exported successfully", Toast.LENGTH_SHORT).show() }
            } catch (e: Exception) {
                mainHandler.post { Toast.makeText(this, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    private val pickDocs = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        VaultLock.isPickingMedia = false
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        for (uri in uris) {
            try {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: Exception) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: Exception) {}
            }
        }
        askBulkTags { tags, delOriginals -> importUris(uris, tags, delOriginals) }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        volumeControlStream = AudioManager.STREAM_MUSIC
        db = BooruDb(this)
        vault = CryptoVault(this)
        currentTheme = prefs.themeMode
        currentAccent = prefs.accent
        currentImmersive = prefs.immersiveMode
        currentHideStatus = prefs.hideStatusBar

        if (prefs.categoryFilter.equals("reels", ignoreCase = true)) {
            prefs.categoryFilter = "flow"
        }

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isMultiSelect) {
                    exitMultiSelect()
                } else if (prefs.categoryFilter.lowercase() in listOf("flow", "reels")) {
                    selectCategory("all")
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        build()
        CrashGuard.lastCrash(this)?.let { trace ->
            CrashGuard.clearCrash(this)
            startActivity(Intent(this, CrashActivity::class.java).putExtra("trace", "Previous run crash trace:\n$trace"))
        }
        reload()
        // The row is populated from the search box, so seed it once the box and
        // the row exist; otherwise it sits empty until the first keystroke.
        requestSugg(search.text?.toString().orEmpty())
        handleIncomingShareIntent(intent)
        checkSearchTagIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent?.let {
            handleIncomingShareIntent(it)
            checkSearchTagIntent(it)
        }
    }

    private fun checkSearchTagIntent(i: Intent?) {
        val tag = i?.getStringExtra("search_tag")
        if (!tag.isNullOrBlank()) {
            i.removeExtra("search_tag")
            search.setText(tag.trim() + " ")
            search.setSelection(search.text.length)
            if (!prefs.showSearchBar || prefs.headerCollapsed) {
                prefs.headerCollapsed = false
                applyCustomizationVisibility()
            }
            reload()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            reelsVideoView?.pause()
        } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        cleanReelsMedia()
        mainHandler.removeCallbacksAndMessages(null)
        bg.shutdownNow()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed || !VaultLock.isUnlocked) return
        if (currentTheme != prefs.themeMode || currentAccent != prefs.accent ||
            currentImmersive != prefs.immersiveMode || currentHideStatus != prefs.hideStatusBar) {
            currentTheme = prefs.themeMode
            currentAccent = prefs.accent
            currentImmersive = prefs.immersiveMode
            currentHideStatus = prefs.hideStatusBar
            recreate()
            return
        }

        if (grid.layoutManager is GridLayoutManager) {
            (grid.layoutManager as GridLayoutManager).spanCount = prefs.columns
        }
        rebuildCategoryTabs()
        applyCustomizationVisibility()
        adapter.notifyDataSetChanged()
        reload()

        if (!VaultLock.isDecoy) {
            prefs.consumeIntruderNotice()?.let { alertText ->
                AlertDialog.Builder(this)
                    .setTitle("Intruder Warning")
                    .setMessage(alertText)
                    .setPositiveButton("Dismiss", null)
                    .setNeutralButton("View History") { _, _ ->
                        startActivity(Intent(this, SettingsActivity::class.java))
                    }
                    .show()
            }
        }

        if (!prefs.tutorialCompleted && !VaultLock.isDecoy) {
            showTutorialDialog()
        }
    }

    private var isTutorialShowing = false

    private fun showTutorialDialog() {
        if (isTutorialShowing) return
        if (isFinishing || isDestroyed) return
        isTutorialShowing = true

        rootFrame.post {
            if (isFinishing || isDestroyed || prefs.tutorialCompleted) {
                isTutorialShowing = false
                return@post
            }

            val steps = mutableListOf<SpotlightTourView.Step>()

            if (::topImportBtn.isInitialized && topImportBtn.isShown) {
                steps.add(
                    SpotlightTourView.Step(
                        target = topImportBtn,
                        title = "Quick Import (+)",
                        description = "Tap '+' to securely encrypt and import photos, videos, and GIFs into your private vault. All media is AES-256-GCM encrypted."
                    )
                )
            }

            if (::categoryCapsule.isInitialized && categoryCapsule.isShown) {
                steps.add(
                    SpotlightTourView.Step(
                        target = categoryCapsule,
                        title = "Categories & Flow Mode",
                        description = "Switch between All, Photos, Videos, GIFs, or immersive vertical Flow reel player. You can also swipe left or right across the screen to switch tabs."
                    )
                )
            }

            if (::srow.isInitialized && srow.isShown) {
                steps.add(
                    SpotlightTourView.Step(
                        target = srow,
                        title = "Search & Filter Bar",
                        description = "Search tags (prefix -tag to exclude), tap ★ to filter favorites, or tap sort to organize your vault."
                    )
                )
            }

            if (::setBtn.isInitialized && setBtn.isShown) {
                steps.add(
                    SpotlightTourView.Step(
                        target = setBtn,
                        title = "Settings & Security",
                        description = "Configure custom themes, decoy vault for coercion defense, auto-lock timeouts, intruder attempt logs, and password-protected backups."
                    )
                )
            }

            if (steps.isEmpty()) {
                isTutorialShowing = false
                return@post
            }

            SpotlightTourView.show(rootFrame, prefs, steps) {
                prefs.tutorialCompleted = true
                isTutorialShowing = false
                checkMediaClickTutorial()
            }
        }
    }

    private fun checkMediaClickTutorial() {
        if (prefs.mediaClickTutorialCompleted || !prefs.tutorialCompleted || isTutorialShowing || VaultLock.isDecoy) return
        if (isFinishing || isDestroyed || items.isEmpty()) return
        grid.postDelayed({
            if (prefs.mediaClickTutorialCompleted || isTutorialShowing || isFinishing || isDestroyed || items.isEmpty()) return@postDelayed
            if (grid.visibility != View.VISIBLE) return@postDelayed
            val firstHolder = grid.findViewHolderForAdapterPosition(0)
            val targetView = firstHolder?.itemView ?: return@postDelayed
            if (!targetView.isShown) return@postDelayed
            isTutorialShowing = true
            SpotlightTourView.show(
                rootFrame,
                prefs,
                listOf(
                    SpotlightTourView.Step(
                        target = targetView,
                        title = "Open & Inspect Media",
                        description = "Tap any photo, video, or GIF to open the full-screen viewer. You can manage tags, rotate photos, play videos, and explore details."
                    )
                )
            ) {
                prefs.mediaClickTutorialCompleted = true
                isTutorialShowing = false
            }
        }, 300)
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
        rootFrame = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            background = ThemeUtils.backdrop(prefs)
        }

        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // Multi-Select Action Bar (Top header when batch mode active)
        multiSelectBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeUtils.surfaceGlass(prefs, 0f, 0)
            setPadding(20, 16, 20, 16)
            visibility = View.GONE
        }

        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 18f
            setTextColor(prefs.textColor())
            setPadding(12, 8, 16, 8)
            setOnClickListener { exitMultiSelect() }
        }
        multiSelectBar.addView(closeBtn)

        multiSelectCount = TextView(this).apply {
            text = "0 selected"
            textSize = 16f
            setTextColor(prefs.textColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        multiSelectBar.addView(multiSelectCount, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        selectAllBtn = TextView(this).apply {
            text = "All"
            textSize = 13f
            setTextColor(prefs.accentColor())
            setPadding(12, 8, 12, 8)
            setOnClickListener {
                if (selectedIds.size == items.size && items.isNotEmpty()) {
                    selectedIds.clear()
                    exitMultiSelect()
                } else {
                    selectedIds.clear()
                    items.forEach { selectedIds.add(it.id) }
                    applyCustomizationVisibility()
                    notifySelectionChanged()
                }
            }
        }
        multiSelectBar.addView(selectAllBtn)

        val batchTagsBtn = TextView(this).apply {
            text = "Tag"
            textSize = 13f
            setTextColor(prefs.textColor())
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setPadding(14, 6, 14, 6)
            setOnClickListener { showBulkTagDialog() }
        }
        multiSelectBar.addView(batchTagsBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        val batchFavBtn = TextView(this).apply {
            text = "Fav"
            textSize = 13f
            setTextColor(prefs.accentColor())
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setPadding(14, 6, 14, 6)
            setOnClickListener { toggleBatchFavorites() }
        }
        multiSelectBar.addView(batchFavBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = 8 })

        val batchDelBtn = TextView(this).apply {
            text = "Delete"
            textSize = 13f
            setTextColor(0xFFEF5350.toInt())
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setPadding(14, 6, 14, 6)
            setOnClickListener { showBatchDeleteDialog() }
        }
        multiSelectBar.addView(batchDelBtn)
        rootLayout.addView(multiSelectBar)

        // Top Header Bar
        bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 20, 24, 10)
        }
        val logo = TextView(this).apply {
            text = "kura"
            textSize = 24f
            setTextColor(prefs.textColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        bar.addView(logo, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        countView = TextView(this).apply {
            setTextColor(prefs.textColorSecondary())
            textSize = 13f
        }
        bar.addView(countView)

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // Collapse/Expand Header Toggle
        collapseBtn = TextView(this).apply {
            textSize = 15f
            setTextColor(prefs.textColorSecondary())
            gravity = Gravity.CENTER
            text = if (prefs.headerCollapsed) "▾" else "▴"
            background = ThemeUtils.surfaceGlass(prefs, dp(18).toFloat(), 1)
            setOnClickListener {
                ThemeUtils.vibrateTick(this)
                prefs.headerCollapsed = !prefs.headerCollapsed
                collapseBtn.text = if (prefs.headerCollapsed) "▾" else "▴"
                applyCustomizationVisibility()
            }
        }
        bar.addView(collapseBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            leftMargin = dp(8)
            rightMargin = dp(6)
        })

        // Quick Import (+) Button on top (Black and white neon icon matching lock icon)
        topImportBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_neon_plus)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ThemeUtils.surfaceGlass(prefs, dp(18).toFloat(), 1)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                VaultLock.isPickingMedia = true
                pickDocs.launch(arrayOf("image/*", "video/*"))
            }
        }
        bar.addView(topImportBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            rightMargin = dp(6)
        })

        // Black and white neon lock button (replacing emoji lock)
        val lockBtn = ImageView(this).apply {
            setImageResource(R.drawable.ic_neon_lock)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ThemeUtils.surfaceGlass(prefs, dp(18).toFloat(), 1)
            setPadding(dp(7), dp(7), dp(7), dp(7))
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                lockNow()
            }
        }
        bar.addView(lockBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            rightMargin = dp(6)
        })

        val helpBtn = TextView(this).apply {
            text = "?"
            textSize = 17f
            setTextColor(prefs.textColorSecondary())
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ThemeUtils.surfaceGlass(prefs, dp(18).toFloat(), 1)
            setOnClickListener {
                ThemeUtils.vibrateTick(this)
                Tags.showGuideDialog(this@MainActivity, prefs)
            }
        }
        bar.addView(helpBtn, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            rightMargin = dp(6)
        })

        setBtn = TextView(this).apply {
            text = "⚙"
            textSize = 18f
            setTextColor(prefs.textColorSecondary())
            gravity = Gravity.CENTER
            background = ThemeUtils.surfaceGlass(prefs, dp(18).toFloat(), 1)
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
            }
        }
        bar.addView(setBtn, LinearLayout.LayoutParams(dp(36), dp(36)))
        rootLayout.addView(bar)

        // Search + Filter Bar
        srow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(16, 4, 16, 8)
        }

        search = EditText(this).apply {
            hint = "Search tags (-tag to exclude)…"
            setHintTextColor(Color.GRAY)
            setTextColor(prefs.textColor())
            textSize = 14f
            isSingleLine = true
            maxLines = 1
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = ThemeUtils.surfaceGlass(prefs, 20f, 2)
            setPadding(28, 18, 28, 18)

            val dismissKeyboardAndSearch = {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(windowToken, 0)
                clearFocus()
                searchRunnable?.let { mainHandler.removeCallbacks(it) }
                reload()
            }

            setOnEditorActionListener { _, actionId, event ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH ||
                    actionId == EditorInfo.IME_ACTION_DONE ||
                    actionId == EditorInfo.IME_ACTION_GO ||
                    (event != null && event.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
                ) {
                    dismissKeyboardAndSearch()
                    true
                } else {
                    false
                }
            }

            setOnKeyListener { _, keyCode, event ->
                if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                    dismissKeyboardAndSearch()
                    true
                } else {
                    false
                }
            }

            addTextChangedListener(object : TextWatcher {
                private var sanitizing = false
                override fun afterTextChanged(e: Editable?) {
                    val raw = e?.toString() ?: ""
                    if (!sanitizing && (raw.contains('\n') || raw.contains('\r'))) {
                        sanitizing = true
                        val cleaned = raw.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ')
                        setText(cleaned)
                        setSelection(cleaned.length)
                        sanitizing = false
                        return
                    }
                    requestSugg(raw)
                    searchRunnable?.let { mainHandler.removeCallbacks(it) }
                    searchRunnable = Runnable { reload() }
                    mainHandler.postDelayed(searchRunnable!!, 250)
                }
                override fun beforeTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
                override fun onTextChanged(a: CharSequence?, b: Int, c: Int, d: Int) {}
            })
        }
        srow.addView(search, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        sortToggle = TextView(this).apply {
            textSize = 18f
            setPadding(14, 8, 8, 8)
            text = sortIcon()
            setTextColor(prefs.textColorSecondary())
            setOnClickListener { cycleSort() }
        }
        srow.addView(sortToggle)

        favToggle = TextView(this).apply {
            textSize = 22f
            setPadding(8, 8, 12, 8)
            setOnClickListener {
                prefs.favoritesOnly = !prefs.favoritesOnly
                updateFav()
                reload()
            }
        }
        srow.addView(favToggle)
        updateFav()
        rootLayout.addView(srow)

        // Tag suggestion row, directly under the search field. Sized in dp: the
        // old padding and corner radius were raw pixels, which on a 3x screen
        // made the chips too small to read and too small to tap.
        sc = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dp(10), dp(2), dp(10), dp(6))
            clipToPadding = false
        }
        suggRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        sc.addView(suggRow)
        rootLayout.addView(sc)

        // Media Grid
        val frame = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, prefs.columns)
            background = null
            setHasFixedSize(true)
            addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
                override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                    scaleDetector.onTouchEvent(e)
                    gridSwipeDetector.onTouchEvent(e)

                    when (e.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            swipeStartX = e.x
                            swipeStartY = e.y
                            isHorizontalGridSwipe = false
                        }
                        MotionEvent.ACTION_MOVE -> {
                            val dx = Math.abs(e.x - swipeStartX)
                            val dy = Math.abs(e.y - swipeStartY)
                            if (!isHorizontalGridSwipe && dx > touchSlop && dx > dy * 1.3f) {
                                isHorizontalGridSwipe = true
                            }
                        }
                        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                            val wasSwiping = isHorizontalGridSwipe
                            isHorizontalGridSwipe = false
                            if (wasSwiping) return true
                        }
                    }

                    return scaleDetector.isInProgress || isHorizontalGridSwipe
                }

                override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
                    scaleDetector.onTouchEvent(e)
                    gridSwipeDetector.onTouchEvent(e)
                    if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) {
                        isHorizontalGridSwipe = false
                    }
                }

                override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
            })
        }
        adapter = GridAdapter()
        grid.adapter = adapter
        // Change animations replayed every rebind as a visible flash across the
        // whole grid; cell contents are updated in place instead.
        grid.itemAnimator = null
        // Keep a screen of decoded cells warm so a fling reuses bitmaps instead
        // of re-decrypting everything it just scrolled past.
        grid.setItemViewCacheSize(10)
        frame.addView(grid, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

        empty = TextView(this).apply {
            text = "Vault is empty\nTap + to import private media"
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            textSize = 15f
            visibility = View.GONE
            isClickable = true
            isFocusable = true
            setOnTouchListener { _, event ->
                gridSwipeDetector.onTouchEvent(event)
                true
            }
        }
        frame.addView(empty, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER))

        progress = ProgressBar(this).apply { visibility = View.GONE }
        frame.addView(progress, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        buildReelsContainer(frame)
        rootLayout.addView(frame)

        // Minimalistic Bottom Category Filter Bar
        categoryBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 4, 20, 6)
        }

        val catCapsule = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = ThemeUtils.surfaceGlass(prefs, 22f, 1)
            setPadding(4, 4, 4, 4)
        }
        categoryCapsule = catCapsule
        rebuildCategoryTabs()

        val catSwipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 != null) {
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 80) {
                        if (dx < 0) cycleCategory(true) else cycleCategory(false)
                        return true
                    }
                }
                return false
            }
        })
        categoryBar.setOnTouchListener { _, event ->
            catSwipe.onTouchEvent(event)
            false
        }

        categoryBar.addView(catCapsule, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rootLayout.addView(categoryBar)
        updateCategoryStyles()

        // Optional Full-Width Bottom Bar
        bottomBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(20, 6, 20, 16)
        }
        val importBtn = TextView(this).apply {
            text = "+  IMPORT MEDIA"
            gravity = Gravity.CENTER
            textSize = 15f
            setTextColor(ThemeUtils.buttonTextColor(prefs, true))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = ThemeUtils.importBarBackground(prefs)
            setPadding(0, 20, 0, 20)
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                VaultLock.isPickingMedia = true
                pickDocs.launch(arrayOf("image/*", "video/*"))
            }
        }
        bottomBar!!.addView(importBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rootLayout.addView(bottomBar)

        rootFrame.addView(rootLayout)

        // Optional Circular Floating Action Button (+) with neon icon
        val fabDensity = resources.displayMetrics.density
        val fabSize = (56 * fabDensity).toInt()
        val fabPad = (13 * fabDensity).toInt()
        circularFab = ImageView(this).apply {
            setImageResource(R.drawable.ic_neon_plus)
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = ThemeUtils.fabBackground(prefs)
            if (ThemeUtils.isLightFill(prefs)) {
                setColorFilter(ThemeUtils.onLightFillColor())
            }
            elevation = 12f * fabDensity
            setPadding(fabPad, fabPad, fabPad, fabPad)
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                VaultLock.isPickingMedia = true
                pickDocs.launch(arrayOf("image/*", "video/*"))
            }
        }
        val fabLp = FrameLayout.LayoutParams(fabSize, fabSize, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = (24 * fabDensity).toInt()
            rightMargin = (24 * fabDensity).toInt()
        }
        rootFrame.addView(circularFab, fabLp)

        val pBar = VaultProgressBar(this)
        val pBarLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = (12 * fabDensity).toInt()
        }
        rootFrame.addView(pBar, pBarLp)

        ViewCompat.setOnApplyWindowInsetsListener(rootFrame) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val effectiveEdgeToEdge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM || prefs.edgeToEdge
            val topInset = if (effectiveEdgeToEdge && !prefs.hideStatusBar) sysBars.top else 0
            val bottomInset = if (effectiveEdgeToEdge) sysBars.bottom else 0

            val curPBarLp = pBar.layoutParams as? FrameLayout.LayoutParams
            if (curPBarLp != null) {
                curPBarLp.bottomMargin = bottomInset + (12 * fabDensity).toInt()
                pBar.layoutParams = curPBarLp
            }

            bar.setPadding(24, topInset + 20, 24, 10)
            multiSelectBar.setPadding(20, topInset + 16, 20, 16)

            val isBottomBar = prefs.importButtonPlacement == "bottom_bar"
            val baseFabBottom = if (prefs.showCategoryFilter) (72 * fabDensity).toInt() else (24 * fabDensity).toInt()
            val curFabLp = circularFab?.layoutParams as? FrameLayout.LayoutParams
            if (curFabLp != null) {
                curFabLp.bottomMargin = baseFabBottom + bottomInset
                circularFab?.layoutParams = curFabLp
            }

            val catBottomPad = if (!isBottomBar) bottomInset + 10 else 6
            categoryBar.setPadding(20, 4, 20, catBottomPad)

            bottomBar?.setPadding(20, 6, 20, 16 + bottomInset)

            val gridExtraBottom = (if (prefs.showCategoryFilter) 56 else 0) +
                    (if (isBottomBar) 68 else 0) +
                    bottomInset + 10
            grid.setPadding(0, 0, 0, gridExtraBottom)
            grid.clipToPadding = false

            insets
        }

        setContentView(rootFrame)
        applyCustomizationVisibility()
    }

    private fun rebuildCategoryTabs() {
        categoryCapsule.removeAllViews()
        categoryViews.clear()

        val enabled = prefs.enabledCategories()
        if (enabled.none { it.first.equals(prefs.categoryFilter, ignoreCase = true) }) {
            prefs.categoryFilter = enabled.first().first
        }

        for ((key, title) in enabled) {
            val tv = TextView(this).apply {
                text = title
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(20, 10, 20, 10)
                setOnClickListener {
                    selectCategory(key)
                }
            }
            categoryViews[key] = tv
            categoryCapsule.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        updateCategoryStyles()
    }

    private fun updateCategoryStyles() {
        val cur = prefs.categoryFilter.lowercase()
        for ((k, tv) in categoryViews) {
            val active = (k == cur)
            if (active) {
                tv.background = roundedBg(prefs.accentColor(), 16f)
                tv.setTextColor(ThemeUtils.buttonTextColor(prefs, true))
                tv.setTypeface(null, android.graphics.Typeface.BOLD)
            } else {
                tv.background = null
                tv.setTextColor(prefs.textColorSecondary())
                tv.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun applyCustomizationVisibility() {
        if (isMultiSelect) {
            multiSelectBar.visibility = View.VISIBLE
            bar.visibility = View.GONE
            srow.visibility = View.GONE
            sc.visibility = View.GONE
            categoryBar.visibility = View.GONE
            bottomBar?.visibility = View.GONE
            circularFab?.visibility = View.GONE
            multiSelectCount.text = "${selectedIds.size} selected"
            selectAllBtn.text = if (selectedIds.size == items.size && items.isNotEmpty()) "Deselect" else "All"
            ViewCompat.requestApplyInsets(rootFrame)
            return
        }

        multiSelectBar.visibility = View.GONE
        bar.visibility = View.VISIBLE
        val collapsed = prefs.headerCollapsed
        collapseBtn.text = if (collapsed) "▾" else "▴"

        // Search row visibility
        srow.visibility = if (!prefs.showSearchBar || collapsed) View.GONE else View.VISIBLE
        sortToggle.visibility = if (prefs.showSortFilter) View.VISIBLE else View.GONE
        favToggle.visibility = if (prefs.showFavoritesFilter) View.VISIBLE else View.GONE

        // Tag suggestions visibility
        sc.visibility = if (!prefs.showTagSuggestions || collapsed) View.GONE else View.VISIBLE

        // Bottom Category Bar visibility
        categoryBar.visibility = if (prefs.showCategoryFilter) View.VISIBLE else View.GONE
        updateCategoryStyles()

        // Import Button placement
        val isFlow = prefs.categoryFilter.lowercase() in listOf("flow", "reels")
        when (prefs.importButtonPlacement) {
            "top" -> {
                topImportBtn.visibility = View.VISIBLE
                circularFab?.visibility = View.GONE
                bottomBar?.visibility = View.GONE
            }
            "bottom_circle" -> {
                topImportBtn.visibility = View.GONE
                circularFab?.visibility = if (isFlow) View.GONE else View.VISIBLE
                bottomBar?.visibility = View.GONE
            }
            "bottom_bar" -> {
                topImportBtn.visibility = View.GONE
                circularFab?.visibility = View.GONE
                bottomBar?.visibility = if (isFlow) View.GONE else View.VISIBLE
            }
            else -> { // "hidden"
                topImportBtn.visibility = View.GONE
                circularFab?.visibility = View.GONE
                bottomBar?.visibility = View.GONE
            }
        }

        ViewCompat.requestApplyInsets(rootFrame)
    }

    private fun enterMultiSelect(initialId: Long) {
        isMultiSelect = true
        selectedIds.clear()
        selectedIds.add(initialId)
        applyCustomizationVisibility()
        notifySelectionChanged()
    }

    private fun exitMultiSelect() {
        isMultiSelect = false
        selectedIds.clear()
        applyCustomizationVisibility()
        notifySelectionChanged()
    }

    /**
     * Refreshes selection visuals without touching thumbnails.
     *
     * Every `notifyDataSetChanged()` on a selection change used to re-run
     * `onBindViewHolder` for all cells, which blanked each image and queued a
     * fresh decrypt+decode of everything on screen.
     */
    private fun notifySelectionChanged() {
        if (items.isEmpty()) {
            adapter.notifyDataSetChanged()
        } else {
            adapter.notifyItemRangeChanged(0, items.size, PAYLOAD_SELECTION)
        }
    }

    private fun toggleSelect(id: Long) {
        if (selectedIds.contains(id)) {
            selectedIds.remove(id)
            if (selectedIds.isEmpty()) {
                exitMultiSelect()
                return
            }
        } else {
            selectedIds.add(id)
        }
        applyCustomizationVisibility()
        notifySelectionChanged()
    }

    private fun showBulkTagDialog() {
        if (selectedIds.isEmpty()) return
        val count = selectedIds.size
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }
        val infoText = TextView(this).apply {
            text = "Enter tags for $count selected items (space-separated):"
            setTextColor(prefs.textColorSecondary())
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(infoText)

        val input = EditText(this).apply {
            hint = "e.g. wallpaper anime favorite"
            setTextColor(prefs.textColor())
            setHintTextColor(Color.GRAY)
            setBackgroundColor(prefs.surfaceColor())
            setPadding(24, 20, 24, 20)
        }
        layout.addView(input)

        val replaceCheck = CheckBox(this).apply {
            text = "Replace existing tags instead of appending"
            setTextColor(prefs.textColorSecondary())
            isChecked = false
            setPadding(0, 12, 0, 12)
        }
        layout.addView(replaceCheck)

        val tagScroller = ScrollView(this).apply { isFillViewport = true }
        tagScroller.addView(layout)

        val tagDialog = AlertDialog.Builder(this)
            .setTitle("Bulk Tag ($count items)")
            .setView(tagScroller)
            .setPositiveButton("Apply") { _, _ ->
                val newTags = Tags.parseList(input.text.toString())
                val replace = replaceCheck.isChecked
                val targetIds = selectedIds.toList()
                bg.execute {
                    for (id in targetIds) {
                        val finalTags = if (replace) {
                            newTags
                        } else {
                            val cur = db.tagsFor(id)
                            (cur + newTags).distinct()
                        }
                        db.setTags(id, finalTags)
                    }
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "Tags updated for $count items", Toast.LENGTH_SHORT).show()
                        exitMultiSelect()
                        reload()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .create()

        tagDialog.show()
        input.submitOnEnter(tagDialog)
        tagDialog.keepFieldsVisible(tagScroller)
    }

    private fun toggleBatchFavorites() {
        if (selectedIds.isEmpty()) return
        val count = selectedIds.size
        val targetIds = selectedIds.toList()
        bg.execute {
            val allFavs = targetIds.all { db.get(it)?.favorite == true }
            val newFav = !allFavs
            for (id in targetIds) {
                db.setFavorite(id, newFav)
            }
            mainHandler.post {
                val action = if (newFav) "Added to favorites" else "Removed from favorites"
                Toast.makeText(this@MainActivity, "$action ($count items)", Toast.LENGTH_SHORT).show()
                exitMultiSelect()
                reload()
            }
        }
    }

    private fun showBatchDeleteDialog() {
        if (selectedIds.isEmpty()) return
        val count = selectedIds.size
        val targetIds = selectedIds.toList()

        AlertDialog.Builder(this)
            .setTitle("Shred & Delete $count Items?")
            .setMessage("Permanently shred and delete $count selected items from your encrypted vault? This cannot be undone.")
            .setPositiveButton("Delete All") { _, _ ->
                bg.execute {
                    var deleted = 0
                    for (id in targetIds) {
                        val name = db.delete(id)
                        if (name != null) {
                            vault.delete(name)
                            deleted++
                        }
                    }
                    mainHandler.post {
                        Toast.makeText(this@MainActivity, "Shredded and deleted $deleted items", Toast.LENGTH_SHORT).show()
                        exitMultiSelect()
                        reload()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sortIcon(): String = when (prefs.sortOrder) {
        "date_asc" -> "▲"
        "random" -> "⇄"
        else -> "▼"
    }

    private fun cycleSort() {
        val next = when (prefs.sortOrder) {
            "date_desc" -> "date_asc"
            "date_asc" -> "random"
            else -> "date_desc"
        }
        prefs.sortOrder = next
        sortToggle.text = sortIcon()
        val desc = when (next) {
            "date_asc" -> "Oldest first"
            "random" -> "Random order"
            else -> "Newest first"
        }
        Toast.makeText(this, "Sort: $desc", Toast.LENGTH_SHORT).show()
        reload()
    }

    private fun updateFav() {
        favToggle.text = if (prefs.favoritesOnly) "★" else "☆"
        favToggle.setTextColor(if (prefs.favoritesOnly) prefs.accentColor() else prefs.textColorSecondary())
    }

    private fun lockNow() {
        cleanReelsMedia()
        VaultLock.lock()
        BaseVaultActivity.wipePlayCacheAsync(this)
        try {
            startActivity(lockActivityIntent())
        } catch (_: Exception) {
            try {
                val activeAlias = AppIconManager.getActiveAlias(this)
                startActivity(Intent().setClassName(packageName, "$packageName.$activeAlias").apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
            } catch (_: Exception) {}
        }
        finish()
    }

    private fun reload() {
        bg.execute {
            val q = Tags.parseQuery(search.text?.toString() ?: "")
            val rawRes = try { db.search(q, prefs.favoritesOnly, prefs.sortOrder, prefs.categoryFilter) } catch (_: Exception) { emptyList() }

            // Auto-clean any 0-byte ghost entries from broken restore attempts
            val cleanRes = mutableListOf<Item>()
            for (it in rawRes) {
                val f = vault.fileFor(it.fileName)
                if (!f.exists() || f.length() == 0L) {
                    db.delete(it.id)
                    vault.delete(it.fileName)
                } else {
                    cleanRes.add(it)
                }
            }
            val res = cleanRes
            val total = try { db.count() } catch (_: Exception) { 0 }

            mainHandler.post {
                items = res
                adapter.notifyDataSetChanged()
                if (prefs.categoryFilter.lowercase() in listOf("flow", "reels")) {
                    grid.visibility = View.GONE
                    empty.visibility = View.GONE
                    reelsContainer.visibility = View.VISIBLE
                    countView.text = "Flow (${res.size})"
                    loadReels(res)
                } else {
                    cleanReelsMedia()
                    reelsContainer.visibility = View.GONE
                    grid.visibility = View.VISIBLE
                    empty.visibility = if (res.isEmpty()) View.VISIBLE else View.GONE
                    empty.text = "Vault is empty\nTap + to import private media"
                    empty.setOnClickListener(null)
                    countView.text = "${res.size}/$total"
                }
                checkMediaClickTutorial()
            }
        }
    }

    /**
     * Asks for the suggestions for [text] and draws them when they come back.
     *
     * The search box is the only writer of the suggestion row. Every keystroke
     * posts one query, and a result is dropped only if the box has moved on
     * since, so a slow query can never replace fresher suggestions and the row
     * cannot be left showing something the box no longer says.
     */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun requestSugg(text: String) {
        suggQuery = text
        // The token being typed drives the suggestions. An empty box offers the
        // most used tags instead, the way gelbooru's tag list opens.
        val term = text.substringAfterLast(' ').removePrefix("-").trim()
        bg.execute {
            val cands = try {
                if (term.isEmpty()) db.allTags(12) else db.suggestTags(term, SUGGEST_LIMIT)
            } catch (_: Exception) { emptyList() }
            val q = Tags.parseList(text)
            mainHandler.post {
                if (suggQuery == text) renderSugg(cands, q, term)
            }
        }
    }

    private fun renderSugg(cands: List<Pair<String, Int>>, q: List<String>, term: String) {
        suggRow.removeAllViews()
        var shown = 0
        for ((name, count) in cands) {
            // Already in the query: suggesting it again just adds a duplicate.
            if (q.contains(name) || q.contains(Tags.displayName(name))) continue
            val tag = Tags.displayName(name)
            val chip = TextView(this).apply {
                text = "$tag  $count"
                textSize = 14f
                setTextColor(Tags.color(name, prefs))
                background = ThemeUtils.surfaceGlass(prefs, dp(18).toFloat(), dp(1))
                setPadding(dp(16), dp(10), dp(16), dp(10))
                gravity = android.view.Gravity.CENTER
                minHeight = dp(40)
                maxLines = 1
                isClickable = true
                setOnClickListener {
                    ThemeUtils.vibrateTick(this)
                    // Replace the half-typed token with the whole tag, which is
                    // what tapping a suggestion in any tag field is expected to do.
                    val cur = search.text.toString()
                    val base = if (cur.contains(" ")) cur.substringBeforeLast(" ") + " " else ""
                    search.setText(base + tag + " ")
                    search.setSelection(search.text.length)
                }
            }
            suggRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
            shown++
        }
        if (shown == 0 && term.isNotEmpty()) {
            // Say so, rather than leaving an empty gap that reads as broken.
            val hint = TextView(this).apply {
                text = "No tag matches \"$term\""
                textSize = 13f
                setTextColor(prefs.textColorSecondary())
                setPadding(dp(14), dp(10), dp(14), dp(10))
                maxLines = 1
            }
            suggRow.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            shown++
        }
        if (isMultiSelect) return
        sc.visibility = if (shown > 0 && prefs.showTagSuggestions) View.VISIBLE else View.GONE
    }

    private fun buildReelsContainer(parent: FrameLayout) {
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        reelsContainer = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }

        reelsMediaBox = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
            setBackgroundColor(Color.BLACK)
        }
        reelsContainer.addView(reelsMediaBox)

        reelsEmptyText = TextView(this).apply {
            text = "No media in Flow\nSwipe horizontally to change category or import media"
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            textSize = 15f
            visibility = View.GONE
        }
        reelsContainer.addView(reelsEmptyText, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Center Star overlay for double-tap animation
        val heart = TextView(this).apply {
            text = "★"
            textSize = 72f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        reelsHeartAnim = heart
        reelsContainer.addView(heart, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))

        // Floating Overlays
        val overlay = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }

        // Top-Right: Open in Detail view
        val detailBtn = TextView(this).apply {
            text = "Detail ↗"
            textSize = 12f
            setTextColor(prefs.textColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = roundedBg(0x66000000.toInt(), 14f)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener {
                if (reelsIndex in reelsHistory.indices) {
                    val cur = reelsHistory[reelsIndex]
                    val intent = Intent(this@MainActivity, DetailActivity::class.java)
                    intent.putExtra("id", cur.id)
                    val ids = reelsHistory.map { it.id }.toLongArray()
                    intent.putExtra("item_ids", ids)
                    intent.putExtra("position", reelsIndex)
                    startActivity(intent)
                }
            }
        }
        overlay.addView(detailBtn, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(14); rightMargin = dp(14)
        })

        // Bottom-Left Info Stack
        val infoCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), 0, dp(14), dp(16))
        }

        val metaRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        reelsTypePill = TextView(this).apply {
            textSize = 11f
            setTextColor(ThemeUtils.buttonTextColor(prefs, true))
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = roundedBg(prefs.accentColor(), 10f)
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        metaRow.addView(reelsTypePill)

        reelsDimensText = TextView(this).apply {
            textSize = 12f
            setTextColor(prefs.textColorSecondary())
            setPadding(dp(8), 0, 0, 0)
        }
        metaRow.addView(reelsDimensText)
        infoCol.addView(metaRow)

        reelsTagsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        infoCol.addView(reelsTagsRow)

        overlay.addView(infoCol, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
            bottomMargin = dp(24)
        })

        // Bottom-Right Action Stack (Shifted up above the bottom action bar / + import FAB)
        val actionStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, dp(14), dp(16))
        }

        reelsMuted = prefs.alwaysStartMuted
        val soundBtn = TextView(this).apply {
            text = if (reelsMuted) "MUTE" else "VOL"
            textSize = 11f
            setTextColor(if (reelsMuted) Color.parseColor("#FF6B6B") else prefs.textColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = roundedBg(0x66000000.toInt(), dp(20).toFloat())
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setOnClickListener {
                reelsMuted = !reelsMuted
                text = if (reelsMuted) "MUTE" else "VOL"
                setTextColor(if (reelsMuted) Color.parseColor("#FF6B6B") else prefs.textColor())
                ThemeUtils.vibrateTick(this)
                val effVol = if (reelsMuted) 0f else {
                    val v = prefs.appVolume
                    if (v <= 0.05f) 1.0f else v
                }
                try {
                    reelsMediaPlayer?.setVolume(effVol, effVol)
                } catch (_: Exception) {}
                Toast.makeText(this@MainActivity, if (reelsMuted) "Muted" else "Audio On (${(effVol * 100).toInt()}%)", Toast.LENGTH_SHORT).show()
            }
            setOnLongClickListener {
                prefs.appVolume = 1.0f
                reelsMuted = false
                text = "VOL"
                setTextColor(prefs.textColor())
                try {
                    reelsMediaPlayer?.setVolume(1.0f, 1.0f)
                } catch (_: Exception) {}
                ThemeUtils.vibrateTick(this)
                Toast.makeText(this@MainActivity, "Audio 100%", Toast.LENGTH_SHORT).show()
                true
            }
            visibility = View.GONE
        }
        reelsSoundBtn = soundBtn
        actionStack.addView(soundBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(12) })

        val favBtn = TextView(this).apply {
            text = "☆"
            textSize = 24f
            gravity = Gravity.CENTER
            setTextColor(prefs.textColor())
            background = roundedBg(0x66000000.toInt(), dp(20).toFloat())
            setOnClickListener { toggleReelsFavorite() }
        }
        reelsFavBtn = favBtn
        actionStack.addView(favBtn, LinearLayout.LayoutParams(dp(42), dp(42)).apply { bottomMargin = dp(12) })

        val nextHint = TextView(this).apply {
            text = "▼"
            textSize = 16f
            setTextColor(prefs.textColorSecondary())
            gravity = Gravity.CENTER
            setOnClickListener { nextReel() }
        }
        actionStack.addView(nextHint)

        overlay.addView(actionStack, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            bottomMargin = dp(40)
            rightMargin = dp(14)
        })

        reelsContainer.addView(overlay)

        val reelsGestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (reelsVideoView != null) {
                    val vv = reelsVideoView!!
                    if (vv.isPlaying) vv.pause() else vv.start()
                }
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                triggerReelsFavoriteWithAnimation()
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                if (Math.abs(dx) > Math.abs(dy) * 1.3f && Math.abs(dx) > 100) {
                    if (dx < 0) cycleCategory(true) else cycleCategory(false)
                    return true
                } else if (Math.abs(dy) > Math.abs(dx) * 1.3f && Math.abs(dy) > 100) {
                    if (dy < 0) nextReel() else prevReel()
                    return true
                }
                return false
            }
        })

        reelsContainer.setOnTouchListener { _, event ->
            reelsGestureDetector.onTouchEvent(event)
            true
        }

        parent.addView(reelsContainer, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun cycleCategory(forward: Boolean) {
        lastCategorySwitchTime = System.currentTimeMillis()
        ThemeUtils.vibrateTick(categoryBar)
        val catKeys = prefs.enabledCategories().map { it.first }
        if (catKeys.isEmpty()) return
        val curIndex = catKeys.indexOf(prefs.categoryFilter.lowercase()).let { if (it < 0) 0 else it }
        val nextIndex = if (forward) {
            (curIndex + 1) % catKeys.size
        } else {
            (curIndex - 1 + catKeys.size) % catKeys.size
        }
        selectCategory(catKeys[nextIndex])
    }

    private fun selectCategory(key: String) {
        if (prefs.categoryFilter.lowercase() == key.lowercase()) return
        prefs.categoryFilter = key
        updateCategoryStyles()
        applyCustomizationVisibility()
        reload()
    }

    private fun loadReels(pool: List<Item>) {
        reelsPool = pool
        if (pool.isEmpty()) {
            cleanReelsMedia()
            reelsEmptyText.visibility = View.VISIBLE
            return
        }
        reelsEmptyText.visibility = View.GONE
        if (reelsHistory.isEmpty()) {
            reelsHistory.add(pool.first())
            reelsIndex = 0
            renderCurrentReel()
        } else if (reelsIndex in reelsHistory.indices) {
            val curId = reelsHistory[reelsIndex].id
            val fresh = pool.find { it.id == curId } ?: db.get(curId) ?: pool.first()
            reelsHistory[reelsIndex] = fresh
            renderCurrentReel()
        } else {
            reelsIndex = 0
            renderCurrentReel()
        }
    }

    private fun nextReel() {
        if (reelsPool.isEmpty()) return
        if (reelsIndex < reelsHistory.size - 1) {
            reelsIndex++
            renderCurrentReel()
        } else {
            val unshown = reelsPool.filter { it.id !in reelsHistory.takeLast(10).map { h -> h.id } }
            val nextItem = if (unshown.isNotEmpty()) unshown.random() else reelsPool.random()
            reelsHistory.add(nextItem)
            reelsIndex++
            renderCurrentReel()
        }
    }

    private fun prevReel() {
        if (reelsIndex > 0) {
            reelsIndex--
            renderCurrentReel()
        } else {
            Toast.makeText(this, "Start of Flow", Toast.LENGTH_SHORT).show()
        }
    }

    @Synchronized
    private fun cleanReelsMedia() {
        activeReelsJobId = System.nanoTime()
        val stalePlayFile = reelsPlayFile
        reelsPlayFile = null
        val staleAnimBytes = activeReelsAnimationBytes
        activeReelsAnimationBytes = null
        try {
            staleAnimBytes?.let { java.util.Arrays.fill(it, 0.toByte()) }
            reelsMediaPlayer = null
            reelsVideoView?.stopPlayback()
            reelsVideoView = null
            reelsImageView = null
            reelsMediaBox.removeAllViews()
        } catch (_: Exception) {}
        // Zero-filling the play cache is unbounded I/O — a decrypted video can be
        // gigabytes — so it must never run inline on the UI thread.
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

    private fun updateReelsFavState(fav: Boolean) {
        reelsFavBtn?.text = if (fav) "★" else "☆"
        reelsFavBtn?.setTextColor(if (fav) prefs.accentColor() else prefs.textColor())
    }

    private fun toggleReelsFavorite() {
        if (reelsIndex !in reelsHistory.indices) return
        reelsFavBtn?.let { ThemeUtils.vibrateClick(it) }
        val cur = reelsHistory[reelsIndex]
        val newFav = !cur.favorite
        bg.execute {
            db.setFavorite(cur.id, newFav)
            mainHandler.post {
                reelsHistory[reelsIndex] = cur.copy(favorite = newFav)
                updateReelsFavState(newFav)
            }
        }
    }

    private fun triggerReelsFavoriteWithAnimation() {
        if (reelsIndex !in reelsHistory.indices) return
        reelsHeartAnim?.let { ThemeUtils.vibrateClick(it) }
        val cur = reelsHistory[reelsIndex]
        val newFav = true
        bg.execute {
            db.setFavorite(cur.id, newFav)
            mainHandler.post {
                reelsHistory[reelsIndex] = cur.copy(favorite = newFav)
                updateReelsFavState(newFav)
                reelsHeartAnim?.apply {
                    alpha = 1f
                    scaleX = 0.5f
                    scaleY = 0.5f
                    visibility = View.VISIBLE
                    animate()
                        .scaleX(1.4f)
                        .scaleY(1.4f)
                        .alpha(0f)
                        .setDuration(600)
                        .withEndAction { visibility = View.GONE }
                        .start()
                }
            }
        }
    }

    private fun renderCurrentReel() {
        if (reelsIndex !in reelsHistory.indices) return
        val item = reelsHistory[reelsIndex]
        cleanReelsMedia()

        countView.text = "Flow (${reelsIndex + 1}/${reelsPool.size})"
        reelsTypePill.text = when {
            item.mime.startsWith("video") -> "VIDEO"
            item.mime == "image/gif" -> "GIF"
            else -> "PHOTO"
        }
        val durText = if (item.durationMs > 0) " • ${item.durationMs / 1000}s" else ""
        reelsDimensText.text = "${item.width}×${item.height}$durText"
        reelsSoundBtn?.apply {
            visibility = if (item.mime.startsWith("video")) View.VISIBLE else View.GONE
            text = if (reelsMuted) "MUTE" else "VOL"
            setTextColor(if (reelsMuted) Color.parseColor("#FF6B6B") else prefs.textColor())
        }
        updateReelsFavState(item.favorite)

        reelsTagsRow.removeAllViews()
        val tags = item.tags.take(4)
        for (tag in tags) {
            val chip = TextView(this).apply {
                text = "#$tag"
                textSize = 12f
                setTextColor(prefs.textColor())
                background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
                setPadding(16, 6, 16, 6)
            }
            reelsTagsRow.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = 8
            })
        }

        if (item.mime.startsWith("video")) {
            val vv = VideoView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
            }
            reelsVideoView = vv
            reelsMediaBox.addView(vv)

            val currentJobId = activeReelsJobId
            val startVaultSession = VaultLock.sessionId
            bg.execute {
                try {
                    if (isFinishing || isDestroyed || activeReelsJobId != currentJobId || VaultLock.sessionId != startVaultSession) {
                        return@execute
                    }
                    val f = vault.decryptVideoForPlaybackToCache(item.fileName)
                    if (isFinishing || isDestroyed || activeReelsJobId != currentJobId || VaultLock.sessionId != startVaultSession) {
                        vault.secureShred(f)
                        return@execute
                    }
                    reelsPlayFile = f
                    mainHandler.post {
                        if (isFinishing || isDestroyed || activeReelsJobId != currentJobId || VaultLock.sessionId != startVaultSession || reelsVideoView != vv) {
                            cleanReelsMedia()
                            return@post
                        }
                        vv.setOnPreparedListener { mp ->
                            if (isFinishing || isDestroyed || activeReelsJobId != currentJobId || VaultLock.sessionId != startVaultSession || reelsVideoView != vv) {
                                cleanReelsMedia()
                                return@setOnPreparedListener
                            }
                            reelsMediaPlayer = mp
                            mp.isLooping = true
                            val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
                            try {
                                audioManager?.requestAudioFocus(
                                    null,
                                    AudioManager.STREAM_MUSIC,
                                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                                )
                            } catch (_: Exception) {}
                            val effVol = if (reelsMuted) 0f else {
                                val v = prefs.appVolume
                                if (v <= 0.05f) 1.0f else v
                            }
                            try {
                                mp.setVolume(effVol, effVol)
                            } catch (_: Exception) {}
                            vv.start()
                        }
                        vv.setOnErrorListener { _, _, _ ->
                            try {
                                vv.seekTo(0)
                                vv.start()
                                true
                            } catch (_: Exception) { false }
                        }
                        vv.setVideoPath(f.absolutePath)
                    }
                } catch (e: Exception) {
                    mainHandler.post {
                        if (!isFinishing && !isDestroyed) {
                            Toast.makeText(this@MainActivity, "Failed to load video: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
        } else {
            val iv = ImageView(this).apply {
                layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setBackgroundColor(Color.BLACK)
            }
            val reelsImageView = iv
            reelsMediaBox.addView(iv)

            val gifBoxW = if (iv.width > 0) iv.width else resources.displayMetrics.widthPixels
            val gifBoxH = if (iv.height > 0) iv.height else resources.displayMetrics.heightPixels

            bg.execute {
                try {
                    if (item.mime == "image/gif" && Build.VERSION.SDK_INT >= 28) {
                        val anim = vault.animatedImage(item.fileName, gifBoxW, gifBoxH)
                        if (anim != null) {
                            activeReelsAnimationBytes = anim.sourceBytes
                            mainHandler.post {
                                if (reelsImageView == iv) {
                                    iv.setImageDrawable(anim.drawable)
                                    if (anim.drawable is android.graphics.drawable.Animatable) anim.drawable.start()
                                } else {
                                    activeReelsAnimationBytes?.let { b -> java.util.Arrays.fill(b, 0.toByte()) }
                                    activeReelsAnimationBytes = null
                                }
                            }
                            return@execute
                        }
                    }
                    // See DetailActivity: an OOM must not re-enter the same
                    // failing decode through a `?:` chain.
                    var bmp = try {
                        vault.decodeDisplayImage(item.fileName, maxDim = 2048, extraRotation = item.rotation)
                    } catch (_: OutOfMemoryError) {
                        android.util.Log.w("KuraReels", "OOM on full-res decode, retrying small")
                        try { vault.sampledImage(item.fileName, 1024, extraRotation = item.rotation) }
                        catch (_: OutOfMemoryError) { null } catch (_: Throwable) { null }
                    } catch (_: Throwable) { null }
                    if (bmp == null && !item.mime.startsWith("video")) {
                        bmp = try {
                            vault.sampledImage(item.fileName, 1024, extraRotation = item.rotation)
                        } catch (_: OutOfMemoryError) { null } catch (_: Throwable) { null }
                    }
                    mainHandler.post {
                        if (reelsImageView == iv && bmp != null) {
                            iv.setImageBitmap(bmp)
                        }
                    }
                } catch (_: Throwable) {}
            }
        }
    }

    /** Makes a dialog text field single-line so Enter submits instead of inserting a newline. */
    private fun EditText.submitOnEnter(dialog: AlertDialog) {
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.performClick()
                true
            } else {
                false
            }
        }
    }

    /** Shrinks a dialog's scroll viewport by the keyboard so its fields stay visible above it. */
    private fun AlertDialog.keepFieldsVisible(scroller: ScrollView) {
        val extraPad = (16 * resources.displayMetrics.density).toInt()
        window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ViewCompat.setOnApplyWindowInsetsListener(scroller) { v, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            v.setPadding(0, 0, 0, if (imeBottom > 0) imeBottom + extraPad else 0)
            if (imeBottom > 0) {
                v.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
            }
            insets
        }
    }

    private fun askBulkTags(cb: (List<String>, Boolean) -> Unit) {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val input = EditText(this).apply {
            hint = "Tags (comma or space separated)"
            setHintTextColor(Color.GRAY)
            setTextColor(prefs.textColor())
            setBackgroundColor(prefs.surfaceColor())
            setPadding(24, 20, 24, 20)
        }
        layout.addView(input)

        val autocompleteScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            visibility = View.GONE
            setPadding(0, 8, 0, 8)
        }
        val autocompleteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        autocompleteScroll.addView(autocompleteRow)
        layout.addView(autocompleteScroll)

        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val rawInput = s?.toString() ?: ""
                val currentToken = rawInput.substringAfterLast(',').trim()
                if (currentToken.length >= 1) {
                    bg.execute {
                        val suggestions = db.suggestTags(currentToken, limit = 6)
                        mainHandler.post {
                            autocompleteRow.removeAllViews()
                            if (suggestions.isEmpty()) {
                                autocompleteScroll.visibility = View.GONE
                            } else {
                                autocompleteScroll.visibility = View.VISIBLE
                                for ((tagName, count) in suggestions) {
                                    val chip = TextView(this@MainActivity).apply {
                                        setText("${Tags.displayName(tagName)} ($count)")
                                        textSize = 12f
                                        setTextColor(Tags.color(tagName, prefs))
                                        background = ThemeUtils.surfaceGlass(prefs, 14f, 1)
                                        setPadding(18, 8, 18, 8)
                                        setOnClickListener {
                                            ThemeUtils.vibrateTick(this)
                                            val fullText = input.text.toString()
                                            val lastComma = fullText.lastIndexOf(',')
                                            val prefix = if (lastComma >= 0) {
                                                fullText.substring(0, lastComma + 1).trim() + " "
                                            } else ""
                                            input.setText(prefix + tagName + ", ")
                                            input.setSelection(input.text.length)
                                            autocompleteScroll.visibility = View.GONE
                                        }
                                    }
                                    autocompleteRow.addView(chip, LinearLayout.LayoutParams(
                                        LinearLayout.LayoutParams.WRAP_CONTENT,
                                        LinearLayout.LayoutParams.WRAP_CONTENT
                                    ).apply { rightMargin = 8 })
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

        val delCheck = CheckBox(this).apply {
            text = "Delete unencrypted original(s) from device after import"
            setTextColor(prefs.textColorSecondary())
            textSize = 13f
            isChecked = prefs.deleteOriginalOnImport
            setPadding(12, 16, 12, 16)
        }
        layout.addView(delCheck)

        val tagScroller = ScrollView(this).apply { isFillViewport = true }
        tagScroller.addView(layout)

        val tagDialog = AlertDialog.Builder(this).setTitle("Import Media")
            .setView(tagScroller)
            .setPositiveButton("Import") { _, _ ->
                prefs.deleteOriginalOnImport = delCheck.isChecked
                cb(Tags.parseList(input.text.toString()), delCheck.isChecked)
            }
            .setNegativeButton("Cancel", null)
            .create()

        tagDialog.show()
        input.submitOnEnter(tagDialog)
        tagDialog.keepFieldsVisible(tagScroller)
    }

    private fun handleIncomingShareIntent(incoming: Intent) {
        val action = incoming.action ?: return
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_SEND_MULTIPLE) return

        val uris = mutableListOf<Uri>()
        if (action == Intent.ACTION_SEND) {
            val uri = if (Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                incoming.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            } ?: incoming.data
            uri?.let { uris.add(it) }
        } else if (action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= 33) {
                incoming.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                incoming.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            list?.let { uris.addAll(it) }
        }

        if (uris.isNotEmpty()) {
            for (uri in uris) {
                try {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                    try {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: Exception) {}
                }
            }

            // Clear intent action so rotations do not re-prompt
            incoming.action = null
            incoming.removeExtra(Intent.EXTRA_STREAM)
            intent?.action = null
            intent?.removeExtra(Intent.EXTRA_STREAM)

            askBulkTags { tags, delOriginals ->
                importUris(uris, tags, delOriginals)
            }
        }
    }

    private fun deleteOriginalUri(uri: Uri): Boolean {
        // 1. Try DocumentsContract.deleteDocument if it's a document uri
        try {
            if (DocumentsContract.isDocumentUri(this, uri)) {
                if (DocumentsContract.deleteDocument(contentResolver, uri)) {
                    return true
                }
            }
        } catch (_: Exception) {}

        // 2. Try ContentResolver.delete (standard for MediaStore and external providers)
        try {
            val count = contentResolver.delete(uri, null, null)
            if (count > 0) return true
        } catch (_: Exception) {}

        // 3. Try DocumentFile
        try {
            val df = androidx.documentfile.provider.DocumentFile.fromSingleUri(this, uri)
            if (df != null && df.exists() && df.delete()) {
                return true
            }
        } catch (_: Exception) {}

        // 4. Try direct file path (MediaStore DATA column or file:// URI)
        try {
            if (uri.scheme == "file") {
                val f = File(uri.path ?: "")
                if (f.exists() && f.delete()) return true
            } else if (uri.scheme == "content") {
                val proj = arrayOf(android.provider.MediaStore.MediaColumns.DATA)
                contentResolver.query(uri, proj, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.MediaStore.MediaColumns.DATA)
                    if (idx != -1 && cursor.moveToFirst()) {
                        val path = cursor.getString(idx)
                        if (!path.isNullOrEmpty()) {
                            val f = File(path)
                            if (f.exists() && f.delete()) return true
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return false
    }

    private fun importUris(uris: List<Uri>, tags: List<String>, deleteOriginals: Boolean) {
        if (uris.isEmpty()) return
        VaultProgress.start("Importing Media", total = uris.size, canCancel = true)
        progress.visibility = View.VISIBLE
        bg.execute {
            var ok = 0
            var deleted = 0
            var duplicatesSkipped = 0
            var failed = 0

            try {
                for ((index, uri) in uris.withIndex()) {
                    if (VaultProgress.isCancelled) break
                    VaultProgress.update(current = index + 1, total = uris.size, title = "Importing (${index + 1}/${uris.size})")
                    var createdFileName: String? = null
                    try {
                        val displayName = getDisplayName(uri)
                        val mime = contentResolver.getType(uri) ?: guessMime(displayName)
                        val ext = extFor(mime, displayName)
                        val name = UUID.randomUUID().toString() + ext
                        createdFileName = name

                        val (bytesWritten, sha256Hex) = contentResolver.openInputStream(uri)?.use { inp ->
                            vault.encryptStream(inp, name)
                        } ?: throw java.io.IOException("Unable to open input stream")

                        if (bytesWritten <= 0L) {
                            vault.delete(name)
                            failed++
                            continue
                        }

                        if (prefs.skipDuplicatesOnImport && sha256Hex.isNotEmpty()) {
                            val existing = db.findByHash(sha256Hex)
                            if (existing != null) {
                                vault.delete(name)
                                duplicatesSkipped++
                                if (tags.isNotEmpty()) {
                                    db.addTags(existing.id, tags)
                                }
                                if (deleteOriginals) {
                                    if (deleteOriginalUri(uri)) {
                                        deleted++
                                    }
                                }
                                continue
                            }
                        }

                        val (w, h, d) = try {
                            if (mime.startsWith("video")) {
                                val (vw, vh, vd) = vault.probeVideo(name)
                                Triple(vw, vh, vd)
                            } else {
                                val (iw, ih) = vault.probeImage(name)
                                Triple(iw, ih, 0)
                            }
                        } catch (_: Exception) {
                            Triple(0, 0, 0)
                        }

                        db.insertItem(name, mime, w, h, d, tags, sha256Hex)
                        ok++

                        if (deleteOriginals) {
                            if (deleteOriginalUri(uri)) {
                                deleted++
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("KuraImport", "Failed to import uri: $uri", e)
                        failed++
                        createdFileName?.let { vault.delete(it) }
                    }
                }
            } finally {
                VaultProgress.finish()
            }

            val msg = buildString {
                if (VaultProgress.isCancelled) append("Import cancelled. ")
                append("Imported $ok file(s)")
                if (duplicatesSkipped > 0) append(", $duplicatesSkipped duplicate(s) already in vault")
                if (deleted > 0) append(", deleted $deleted original(s)")
                if (failed > 0) append(", $failed failed")
            }

            mainHandler.post {
                progress.visibility = View.GONE
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                reload()
            }
        }
    }

    private fun getDisplayName(uri: Uri): String {
        return try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: ""
            } ?: uri.lastPathSegment ?: ""
        } catch (_: Exception) {
            uri.lastPathSegment ?: ""
        }
    }

    private fun guessMime(fileName: String): String {
        val n = fileName.lowercase()
        return when {
            n.endsWith(".png") -> "image/png"
            n.endsWith(".gif") -> "image/gif"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".mp4") -> "video/mp4"
            n.endsWith(".webm") -> "video/webm"
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".mov") -> "video/quicktime"
            else -> "image/jpeg"
        }
    }

    private fun extFor(mime: String, originalName: String): String {
        if (originalName.contains(".")) {
            val ext = "." + originalName.substringAfterLast(".").lowercase()
            if (ext.length in 3..5) return ext
        }
        return when (mime) {
            "image/png" -> ".png"
            "image/gif" -> ".gif"
            "image/webp" -> ".webp"
            "video/mp4" -> ".mp4"
            "video/webm" -> ".webm"
            "video/x-matroska" -> ".mkv"
            else -> if (mime.startsWith("video")) ".mp4" else ".jpg"
        }
    }

    /** Dialog shown when holding (long-pressing) a thumbnail. Allows tag editing, favoriting, exporting, or deleting. */
    private fun showEditDialog(item: Item) {
        val ctx = this
        bg.execute {
            val currentTags = db.tagsFor(item.id)
            mainHandler.post {
                val scroll = ScrollView(ctx)
                val layout = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 36, 48, 36)
                }
                scroll.addView(layout)

                val title = TextView(ctx).apply {
                    text = "Edit Media"
                    textSize = 20f
                    setTextColor(prefs.textColor())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                }
                layout.addView(title)

                val meta = TextView(ctx).apply {
                    text = "${item.mime} • ${item.width}×${item.height}" + if (item.durationMs > 0) " • ${item.durationMs / 1000}s" else ""
                    setTextColor(Color.GRAY)
                    textSize = 12f
                    setPadding(0, 4, 0, 16)
                }
                layout.addView(meta)

                val tagLabel = TextView(ctx).apply {
                    text = "Tags (comma or space separated):"
                    setTextColor(prefs.textColorSecondary())
                    textSize = 13f
                    setPadding(0, 8, 0, 6)
                }
                layout.addView(tagLabel)

                val tagInput = EditText(ctx).apply {
                    setText(currentTags.joinToString(", "))
                    setTextColor(prefs.textColor())
                    setHintTextColor(Color.GRAY)
                    hint = "e.g. cat, blue_eyes, wallpaper"
                    background = roundedBg(prefs.surfaceColor(), 12f)
                    setPadding(20, 16, 20, 16)
                    textSize = 14f
                }
                layout.addView(tagInput)

                val favBox = CheckBox(ctx).apply {
                    text = "Mark as Favorite (★)"
                    setTextColor(prefs.textColor())
                    isChecked = item.favorite
                    setPadding(12, 16, 12, 16)
                }
                layout.addView(favBox)

                val actionRow = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 16, 0, 8)
                }

                val exportBtn = TextView(ctx).apply {
                    text = "Export"
                    textSize = 13f
                    setTextColor(prefs.textColor())
                    background = roundedBg(prefs.surfaceColor(), 14f)
                    setPadding(24, 14, 24, 14)
                }
                actionRow.addView(exportBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 12 })

                val mediaTypeStr = if (item.mime.startsWith("video")) "Video" else if (item.mime == "image/gif") "GIF" else "Photo"
                val delBtn = TextView(ctx).apply {
                    text = "Delete $mediaTypeStr"
                    textSize = 13f
                    setTextColor(prefs.textColor())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = roundedBg(0xFFB71C1C.toInt(), 14f)
                    setPadding(24, 14, 24, 14)
                }
                actionRow.addView(delBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                layout.addView(actionRow)

                val dialog = AlertDialog.Builder(ctx)
                    .setView(scroll)
                    .setPositiveButton("Save Changes") { _, _ ->
                        val newTags = Tags.parseList(tagInput.text.toString())
                        val newFav = favBox.isChecked
                        bg.execute {
                            db.setTags(item.id, newTags)
                            db.setFavorite(item.id, newFav)
                            mainHandler.post {
                                Toast.makeText(ctx, "Changes saved", Toast.LENGTH_SHORT).show()
                                reload()
                            }
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .create()

                exportBtn.setOnClickListener {
                    dialog.dismiss()
                    pendingExportItem = item
                    VaultLock.isPickingMedia = true
                    exportDoc.launch(item.fileName)
                }

                delBtn.setOnClickListener {
                    dialog.dismiss()
                    AlertDialog.Builder(ctx)
                        .setTitle("Delete $mediaTypeStr from Vault?")
                        .setMessage("Permanently remove this $mediaTypeStr from your encrypted vault?")
                        .setPositiveButton("Delete") { _, _ ->
                            bg.execute {
                                val name = db.delete(item.id)
                                if (name != null) vault.delete(name)
                                mainHandler.post {
                                    Toast.makeText(ctx, "Deleted $mediaTypeStr", Toast.LENGTH_SHORT).show()
                                    reload()
                                }
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }

                dialog.show()
                tagInput.submitOnEnter(dialog)
                dialog.keepFieldsVisible(scroll)
            }
        }
    }

    inner class GridAdapter : RecyclerView.Adapter<GridAdapter.H>() {
        inner class H(
            val container: FrameLayout,
            val img: ImageView,
            val favBadge: TextView,
            val videoBadge: TextView,
            val checkBadge: TextView
        ) : RecyclerView.ViewHolder(container) {
            /** In-flight thumbnail decode, cancelled when the cell is recycled. */
            var pending: java.util.concurrent.Future<*>? = null
            /** Guards against a late result overwriting a recycled holder. */
            var boundFileName: String? = null
            var boundSession: Long = -1L
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): H {
            val container = FrameLayout(this@MainActivity).apply {
                clipToOutline = true
                isClickable = true
                isFocusable = true
                background = ThemeUtils.cardBackground(prefs, 14f)
            }

            val img = ImageView(this@MainActivity).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                isClickable = true
                isFocusable = false
            }
            container.addView(img, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

            val favBadge = TextView(this@MainActivity).apply {
                text = "★"
                textSize = 14f
                setTextColor(prefs.accentColor())
                setPadding(10, 6, 10, 6)
                background = roundedBg(0xAA000000.toInt(), 8f)
                visibility = View.GONE
            }
            container.addView(favBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END).apply {
                topMargin = 8; rightMargin = 8
            })

            val videoBadge = TextView(this@MainActivity).apply {
                textSize = 11f
                setTextColor(prefs.textColor())
                setPadding(10, 4, 10, 4)
                background = roundedBg(0xAA000000.toInt(), 8f)
                visibility = View.GONE
            }
            container.addView(videoBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                bottomMargin = 8; leftMargin = 8
            })

            val checkBadge = TextView(this@MainActivity).apply {
                text = "✓"
                textSize = 12f
                setTextColor(ThemeUtils.buttonTextColor(prefs, true))
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(10, 4, 10, 4)
                background = roundedBg(prefs.accentColor(), 8f)
                visibility = View.GONE
            }
            container.addView(checkBadge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply {
                topMargin = 8; leftMargin = 8
            })

            return H(container, img, favBadge, videoBadge, checkBadge)
        }

        override fun getItemCount() = items.size

        /**
         * Selection-only rebinds skip the decode entirely. Without this, tapping
         * through 20 items in multi-select triggered 20 full rebinds, each
         * blanking and re-decrypting every visible thumbnail.
         */
        override fun onBindViewHolder(h: H, pos: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads.all { it === PAYLOAD_SELECTION }) {
                val it = items.getOrNull(pos) ?: return
                bindSelection(h, it)
                return
            }
            onBindViewHolder(h, pos)
        }

        private fun bindSelection(h: H, it: Item) {
            val isSelected = selectedIds.contains(it.id)
            if (isSelected) {
                h.container.background = roundedBg(prefs.surfaceColor(), 14f, prefs.accentColor(), 3)
                h.checkBadge.visibility = View.VISIBLE
            } else {
                h.container.background = ThemeUtils.cardBackground(prefs, 14f)
                h.checkBadge.visibility = View.GONE
            }
        }

        override fun onViewRecycled(h: H) {
            h.pending?.cancel(true)
            h.pending = null
            super.onViewRecycled(h)
        }

        override fun onBindViewHolder(h: H, pos: Int) {
            val it = items[pos]
            val screenW = resources.displayMetrics.widthPixels
            val cols = prefs.columns
            val side = (screenW - (cols + 1) * 8) / cols

            val lp = RecyclerView.LayoutParams(side, side).apply {
                setMargins(4, 4, 4, 4)
            }
            h.container.layoutParams = lp

            // Only blank the cell when it is actually showing a different item.
            // Unconditionally clearing made the whole grid flash on every rebind.
            if (h.boundFileName != it.fileName) {
                h.pending?.cancel(true)
                h.pending = null
                h.img.setImageBitmap(null)
            }
            h.boundFileName = it.fileName
            h.img.tag = it.fileName

            // A lock or decoy unlock between bind and delivery invalidates the
            // decode: without this the worker re-populated the plaintext
            // thumbnail cache after lock and painted onto a locked screen.
            val bindSession = VaultLock.sessionId
            h.boundSession = bindSession

            bindSelection(h, it)

            val showBadges = prefs.showGridBadges
            h.favBadge.visibility = if (showBadges && it.favorite) View.VISIBLE else View.GONE
            if (showBadges && it.mime.startsWith("video")) {
                h.videoBadge.visibility = View.VISIBLE
                val durSec = it.durationMs / 1000
                h.videoBadge.text = if (durSec > 0) "${durSec}s" else "VIDEO"
            } else {
                h.videoBadge.visibility = View.GONE
            }

            // Click listener: toggle selection if in multi-select mode, else open full detail view
            val openDetail = View.OnClickListener {
                if (System.currentTimeMillis() - lastCategorySwitchTime < 600) return@OnClickListener
                if (isHorizontalGridSwipe) return@OnClickListener
                val curPos = h.bindingAdapterPosition
                if (curPos != RecyclerView.NO_POSITION && curPos < items.size) {
                    val target = items[curPos]
                    if (isMultiSelect) {
                        toggleSelect(target.id)
                    } else {
                        val intent = Intent(this@MainActivity, DetailActivity::class.java)
                        intent.putExtra("id", target.id)
                        val ids = items.map { it.id }.toLongArray()
                        intent.putExtra("item_ids", ids)
                        intent.putExtra("position", curPos)
                        startActivity(intent)
                    }
                }
            }

            // Long-click listener: toggle selection if in multi-select mode, else enter multi-select mode
            val openEdit = View.OnLongClickListener {
                if (System.currentTimeMillis() - lastCategorySwitchTime < 600) return@OnLongClickListener true
                if (isHorizontalGridSwipe) return@OnLongClickListener true
                val curPos = h.bindingAdapterPosition
                if (curPos != RecyclerView.NO_POSITION && curPos < items.size) {
                    val target = items[curPos]
                    if (isMultiSelect) {
                        toggleSelect(target.id)
                    } else {
                        enterMultiSelect(target.id)
                    }
                }
                true
            }

            h.itemView.setOnClickListener(openDetail)
            h.container.setOnClickListener(openDetail)
            h.img.setOnClickListener(openDetail)

            h.itemView.setOnLongClickListener(openEdit)
            h.container.setOnLongClickListener(openEdit)
            h.img.setOnLongClickListener(openEdit)

            // Decode to the real cell size. The old fixed 512 target was ~1.5x
            // the actual cell, which both wasted pixels and made the thumbnail
            // cache thrash.
            val fileName = it.fileName
            val mime = it.mime
            val rot = it.rotation
            h.pending = gridDecodeExecutor.submit {
                val bmp = try {
                    vault.thumb(fileName, mime, side, extraRotation = rot)
                } catch (_: OutOfMemoryError) {
                    android.util.Log.w("KuraGrid", "OOM decoding grid thumb for $fileName")
                    null
                } catch (_: Throwable) { null }

                if (Thread.currentThread().isInterrupted) return@submit
                if (VaultLock.sessionId != bindSession) return@submit
                mainHandler.post {
                    if (h.boundFileName != fileName || h.boundSession != bindSession) return@post
                    if (h.img.tag != fileName) return@post
                    if (bmp != null) {
                        h.img.setImageBitmap(bmp)
                    } else {
                        h.img.setImageResource(android.R.drawable.ic_menu_report_image)
                    }
                }
            }
        }
    }
}
