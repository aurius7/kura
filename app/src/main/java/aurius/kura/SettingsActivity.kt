package aurius.kura

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Settings & Customization: themes, icon switching, privacy, security hardening, and bulk export/import. */
class SettingsActivity : BaseVaultActivity() {
    private lateinit var vault: CryptoVault
    private lateinit var db: BooruDb
    private val bg = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private var pendingBackupPassphrase: CharArray? = null
        private var restoreMode: Int = 2 // 0 = Force Encrypted, 1 = Force Plaintext ZIP, 2 = Auto-Detect
        private const val SUPPORT_URL = "https://buymeacoffee.com/theauriusk"

        // Survives the activity being torn down by recreate(): every settings
        // toggle rebuilds the screen, and without these the rebuild would both
        // re-lock the vault (fixed in VaultLock) and snap the scroll back to the
        // top even when the user was editing a row halfway down the page.
        private var pendingScrollY = 0
        private var restoreScrollOnCreate = false
    }

    private var settingsScroller: android.widget.ScrollView? = null
    private var restoreSettingsScrollY = 0

    /**
     * Captures the scroll offset of the current screen before it is destroyed, so
     * the recreated activity can jump back to the same spot instead of resetting
     * to the top. Every setting change funnels through this.
     */
    override fun recreate() {
        pendingScrollY = settingsScroller?.scrollY ?: 0
        restoreScrollOnCreate = true
        super.recreate()
    }

    private fun safePost(action: () -> Unit) {
        mainHandler.post {
            if (!isFinishing && !isDestroyed) {
                action()
            }
        }
    }

    private fun deletePartialDocument(uri: Uri) {
        try {
            android.provider.DocumentsContract.deleteDocument(contentResolver, uri)
        } catch (_: Exception) {
            try {
                contentResolver.delete(uri, null, null)
            } catch (_: Exception) {}
        }
    }

    /**
     * Streams the entire vault into a ZIP archive at [uri], optionally wrapped
     * in passphrase-encrypted chunked AES-256-GCM.
     *
     * Replaces two near-identical launcher bodies that each carried three
     * problems on large vaults:
     *
     *  1. The manifest was accumulated as one in-memory `JSONArray` and then
     *     serialised with `toString(2)` + `toByteArray()` - three copies of a
     *     document that grows with the vault, i.e. an OOM on a large library.
     *     It is now emitted incrementally straight into the zip entry. The
     *     on-disk format is unchanged, so archives still restore in older builds.
     *  2. Tags were fetched with one `tagsFor` query per item (N+1), so 50k
     *     items meant 50k queries before the first byte was written. Replaced
     *     with a single grouped query.
     *  3. A failed entry was logged and skipped, so an archive containing a
     *     truncated file could still be reported as a success - silent data loss
     *     to be discovered at restore time. Any entry failure now aborts the
     *     export and deletes the partial document.
     */
    private fun writeArchive(uri: Uri, startSession: Long, pass: CharArray?, title: String) {
        var exportedCount = 0
        var failed: String? = null
        try {
            val allItems = db.allItems()
            VaultProgress.start(title, total = allItems.size, canCancel = true)
            contentResolver.openOutputStream(uri)?.use { rawOut ->
                val sink: OutputStream = if (pass != null) {
                    BackupCrypto.createCipherOutputStream(rawOut, pass)
                } else {
                    rawOut
                }
                ZipOutputStream(BufferedOutputStream(sink)).use { zipOut ->
                    // Manifest first, streamed one object at a time.
                    val tagsByItem = db.allTagsByItemId()
                    zipOut.putNextEntry(ZipEntry("manifest.json"))
                    zipOut.write('['.code)
                    var firstEntry = true
                    for ((idx, it) in allItems.withIndex()) {
                        if (VaultProgress.isCancelled || VaultLock.sessionId != startSession) break
                        VaultProgress.update(idx + 1, allItems.size, "$title: ${idx + 1}/${allItems.size}")
                        if (!firstEntry) zipOut.write(','.code)
                        firstEntry = false
                        val obj = JSONObject()
                        obj.put("file_name", it.fileName)
                        obj.put("mime", it.mime)
                        obj.put("favorite", it.favorite)
                        val tArr = JSONArray()
                        tagsByItem[it.id]?.forEach { t -> tArr.put(t) }
                        obj.put("tags", tArr)
                        zipOut.write(obj.toString().toByteArray(Charsets.UTF_8))
                    }
                    zipOut.write(']'.code)
                    zipOut.closeEntry()

                    // Media entries. A failure here is fatal to the export.
                    for ((idx, it) in allItems.withIndex()) {
                        if (failed != null) break
                        if (VaultProgress.isCancelled || VaultLock.sessionId != startSession) break
                        if (!vault.fileFor(it.fileName).exists()) continue
                        VaultProgress.update(idx + 1, allItems.size, "$title: ${idx + 1}/${allItems.size}")
                        try {
                            zipOut.putNextEntry(ZipEntry(it.fileName))
                            vault.exportToStream(it.fileName, zipOut)
                            zipOut.closeEntry()
                            exportedCount++
                        } catch (e: Exception) {
                            failed = it.fileName
                            android.util.Log.e("KuraExport", "Error writing entry ${it.fileName}", e)
                        }
                    }
                }
            } ?: throw java.io.IOException("Cannot open output stream")

            when {
                VaultProgress.isCancelled -> {
                    deletePartialDocument(uri)
                    safePost { toast("$title cancelled (partial file removed)") }
                }
                VaultLock.sessionId != startSession -> {
                    deletePartialDocument(uri)
                    safePost { toast("Vault locked or session changed. Export aborted.") }
                }
                failed != null -> {
                    deletePartialDocument(uri)
                    safePost { toast("Export failed on '$failed' - incomplete archive removed") }
                }
                exportedCount == 0 && allItems.isNotEmpty() -> {
                    deletePartialDocument(uri)
                    safePost { toast("No media files could be exported. Archive removed.") }
                }
                else -> {
                    safePost { toast("Successfully created $title ($exportedCount of ${allItems.size} files)!") }
                }
            }
        } catch (e: Exception) {
            deletePartialDocument(uri)
            safePost { toast("$title failed: ${e.message}") }
        } finally {
            VaultProgress.finish()
        }
    }

    // 1a. Export all media to PLAINTEXT ZIP archive with tag manifest
    private val exportZipLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        VaultLock.isPickingMedia = false
        if (uri == null) return@registerForActivityResult
        val startSession = VaultLock.sessionId
        toast("Starting ZIP export in background...")
        bg.execute { writeArchive(uri, startSession, null, "ZIP Archive") }
    }

    // 1b. Export all media to PASSWORD-PROTECTED ENCRYPTED .kura archive
    private val exportEncryptedLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        VaultLock.isPickingMedia = false
        val pass = pendingBackupPassphrase
        pendingBackupPassphrase = null
        if (uri == null) return@registerForActivityResult
        if (pass == null) {
            toast("Backup cancelled: passphrase lost")
            return@registerForActivityResult
        }
        val startSession = VaultLock.sessionId
        toast("Encrypting and exporting vault backup...")
        bg.execute {
            try {
                writeArchive(uri, startSession, pass, "Encrypted Backup")
            } finally {
                java.util.Arrays.fill(pass, ' ')
            }
        }
    }

    /** A downloaded APK the user points Kura at, for the offline update path. */
    private val pickApkLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        toast("Checking the file...")
        bg.execute {
            val staged = UpdateChecker.copyToStaging(this, uri)
            val verdict = staged?.let {
                UpdateChecker.verify(
                    this, it,
                    installedVersionCode(),
                    expectedSha = pendingExpectedSha,
                    expectedCert = UpdateChecker.ownCertHex(this)
                )
            } ?: UpdateChecker.Verdict.Unreadable
            mainHandler.post { onApkChecked(verdict) }
        }
    }

    /** Checksum the next picked file is expected to match, when one is known. */
    private var pendingExpectedSha: String? = null

    private fun installedVersionCode(): Int =
        try { packageManager.getPackageInfo(packageName, 0).versionCode } catch (_: Exception) { 0 }

    private fun installedVersionName(): String =
        try { packageManager.getPackageInfo(packageName, 0).versionName ?: "" } catch (_: Exception) { "" }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    private fun dpF(v: Float): Float = v * resources.displayMetrics.density

    private fun showBlackDialog(
        title: String,
        subtitle: String? = null,
        positiveBtnText: String? = null,
        onPositive: (() -> Unit)? = null,
        negativeBtnText: String? = "Cancel",
        onNegative: (() -> Unit)? = null,
        populate: (LinearLayout, AlertDialog) -> Unit
    ): AlertDialog {
        val rootCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF1C1C1C.toInt())
            }
            setPadding(dp(20), dp(20), dp(20), dp(14))
        }

        val titleTv = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.01f
        }
        rootCard.addView(titleTv)

        if (subtitle != null) {
            val subTv = TextView(this).apply {
                text = subtitle
                textSize = 12.5f
                setTextColor(0xFF8A8A8A.toInt())
                setPadding(0, dp(5), 0, 0)
            }
            rootCard.addView(subTv)
        }

        val div = View(this).apply {
            setBackgroundColor(0xFF1C1C1C.toInt())
        }
        rootCard.addView(div, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(1)
        ).apply {
            topMargin = dp(14)
            bottomMargin = dp(6)
        })

        val contentCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val scroll = object : ScrollView(this) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val maxH = (resources.displayMetrics.heightPixels * 0.58f).toInt()
                val newHSpec = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
                super.onMeasure(widthMeasureSpec, newHSpec)
            }
        }.apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            setBackgroundColor(Color.BLACK)
            addView(contentCol)
        }
        rootCard.addView(scroll, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val dialog = AlertDialog.Builder(this)
            .setView(rootCard)
            .create()

        populate(contentCol, dialog)

        if (positiveBtnText != null || negativeBtnText != null) {
            val btnRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, dp(10), 0, 0)
            }

            if (negativeBtnText != null) {
                val negBtn = TextView(this).apply {
                    text = negativeBtnText
                    textSize = 13.5f
                    setTextColor(0xFF9E9E9E.toInt())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(18), dp(10), dp(18), dp(10))
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        ThemeUtils.vibrateClick(this)
                        onNegative?.invoke()
                        dialog.dismiss()
                    }
                }
                btnRow.addView(negBtn)
            }

            if (positiveBtnText != null) {
                val posBtn = TextView(this).apply {
                    text = positiveBtnText
                    textSize = 13.5f
                    setTextColor(Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setPadding(dp(22), dp(10), dp(22), dp(10))
                    background = GradientDrawable().apply {
                        setColor(prefs.accentColor())
                        cornerRadius = dp(24).toFloat()
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        ThemeUtils.vibrateClick(this)
                        onPositive?.invoke()
                        dialog.dismiss()
                    }
                }
                btnRow.addView(posBtn, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { leftMargin = dp(10) })
            }

            rootCard.addView(btnRow)
        }

        dialog.show()

        // Window background/layout have to be applied after show(), otherwise the
        // platform overwrites them with the default grey Material dialog surface.
        val dialogWidth = (resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtMost(dp(440))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.75f)
            setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        return dialog
    }

    private fun dialogSectionLabel(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 11f
            setTextColor(0xFF6E6E6E.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
            setPadding(dp(14), dp(14), dp(14), dp(6))
        })
    }

    private fun dialogBodyText(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFFBDBDBD.toInt())
            setLineSpacing(0f, 1.25f)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        })
    }

    /** Black themed replacement for `AlertDialog.setItems`, matching the custom pickers. */
    private fun showChoiceDialog(
        title: String,
        options: List<Pair<String, String?>>,
        subtitle: String? = null,
        onPick: (Int) -> Unit
    ) {
        showBlackDialog(title = title, subtitle = subtitle) { container, dialog ->
            options.forEachIndexed { index, (label, desc) ->
                addDialogOptionRow(
                    parent = container,
                    title = label,
                    subtitle = desc
                ) {
                    dialog.dismiss()
                    onPick(index)
                }
            }
        }
    }

    private fun addDialogOptionRow(
        parent: LinearLayout,
        title: String,
        subtitle: String? = null,
        selected: Boolean = false,
        leadingView: View? = null,
        trailingBadge: String? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (selected) {
                val accentRgb = prefs.accentColor() and 0x00FFFFFF
                GradientDrawable().apply {
                    setColor(0x26000000.toInt() or accentRgb)
                    cornerRadius = dp(14).toFloat()
                    setStroke(dp(1), 0x8A000000.toInt() or accentRgb)
                }
            } else {
                RippleDrawable(
                    android.content.res.ColorStateList.valueOf(0x22FFFFFF),
                    null,
                    null
                )
            }
            setPadding(dp(14), dp(11), dp(14), dp(11))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                onClick()
            }
        }

        if (leadingView != null) {
            row.addView(leadingView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(14) })
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(if (selected) prefs.accentColor() else Color.WHITE)
            setTypeface(null, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        textCol.addView(titleTv)

        if (subtitle != null) {
            val subTv = TextView(this).apply {
                text = subtitle
                textSize = 11.5f
                setTextColor(0xFF8A8A8A.toInt())
                setPadding(0, dp(3), 0, 0)
            }
            textCol.addView(subTv)
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val badgeTv = TextView(this).apply {
            if (trailingBadge != null) {
                text = trailingBadge
                textSize = 10.5f
                setTextColor(if (trailingBadge == "VISIBLE") prefs.accentColor() else 0xFF6E6E6E.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(9), dp(4), dp(9), dp(4))
                background = GradientDrawable().apply {
                    setColor(0x14FFFFFF)
                    cornerRadius = dp(7).toFloat()
                }
            } else {
                text = if (selected) "✓" else ""
                textSize = 15f
                setTextColor(prefs.accentColor())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(dp(6), 0, dp(6), 0)
            }
        }
        row.addView(badgeTv, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { leftMargin = dp(10) })

        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(2) })
        return row
    }

    private fun confirmPlaintextBackup(then: () -> Unit) {
        showBlackDialog(
            title = "Export Unencrypted Media?",
            subtitle = "The ZIP is written in PLAINTEXT so it can be restored on any device. Anyone who gets the file can view every photo, video and tag inside it.",
            positiveBtnText = "Export Plaintext",
            onPositive = { then() },
            negativeBtnText = "Cancel"
        ) { container, _ ->
            val warningCard = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply {
                    setColor(0xFF221100.toInt())
                    cornerRadius = dp(12).toFloat()
                    setStroke(dp(1), 0xFFFF9800.toInt())
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            warningCard.addView(TextView(this).apply {
                text = "⚠ SECURITY NOTICE"
                textSize = 13f
                setTextColor(0xFFFFB74D.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            warningCard.addView(TextView(this).apply {
                text = "Exported files will NOT be protected by your vault PIN or hardware encryption. Continue?"
                textSize = 12f
                setTextColor(0xFFE0E0E0.toInt())
                setPadding(0, dp(4), 0, 0)
            })
            container.addView(warningCard)
        }
    }

    private fun promptBackupPassphrase(onConfirmed: (CharArray) -> Unit) {
        lateinit var pass1: EditText
        lateinit var pass2: EditText

        showBlackDialog(
            title = "Encrypt Vault Backup",
            subtitle = "Enter a password to encrypt this backup with AES-256-GCM. You will need this password to restore your vault.",
            positiveBtnText = "Create Backup",
            onPositive = {
                val p1 = pass1.text.toString()
                val p2 = pass2.text.toString()
                if (p1.length < 4) {
                    toast("Password must be at least 4 characters")
                } else if (p1 != p2) {
                    toast("Passwords do not match")
                } else {
                    onConfirmed(p1.toCharArray())
                }
            },
            negativeBtnText = "Cancel"
        ) { container, _ ->
            pass1 = EditText(this).apply {
                hint = "Backup Password"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF777777.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF141414.toInt())
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), 0xFF2A2A2A.toInt())
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            pass2 = EditText(this).apply {
                hint = "Repeat Password"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF777777.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF141414.toInt())
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), 0xFF2A2A2A.toInt())
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            container.addView(pass1, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
            container.addView(pass2, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
    }

    private fun promptRestorePassphrase(onConfirmed: (CharArray) -> Unit) {
        lateinit var passInput: EditText
        showBlackDialog(
            title = "Decrypt Backup Archive",
            subtitle = "Enter the password used when creating this backup:",
            positiveBtnText = "Restore",
            onPositive = {
                val p = passInput.text.toString()
                if (p.isEmpty()) {
                    toast("Password cannot be empty")
                } else {
                    onConfirmed(p.toCharArray())
                }
            },
            negativeBtnText = "Cancel"
        ) { container, _ ->
            passInput = EditText(this).apply {
                hint = "Backup Password"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                setTextColor(Color.WHITE)
                setHintTextColor(0xFF777777.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFF141414.toInt())
                    cornerRadius = dp(10).toFloat()
                    setStroke(dp(1), 0xFF2A2A2A.toInt())
                }
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            container.addView(passInput, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
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

    private data class RestoredMeta(val mime: String?, val favorite: Boolean, val tags: List<String>)

    // 2. Restore/import all media from .kura, .vbooru, or ZIP backup archive
    private val importZipLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        VaultLock.isPickingMedia = false
        if (uri == null) return@registerForActivityResult
        if (!VaultLock.isUnlocked) {
            toast("Vault is locked")
            return@registerForActivityResult
        }
        val expectedSession = VaultLock.sessionId
        bg.execute {
            try {
                if (!VaultLock.isUnlocked || VaultLock.sessionId != expectedSession) return@execute
                val displayName = getDisplayName(uri).lowercase()
                val header = ByteArray(64)
                var bytesRead = 0
                contentResolver.openInputStream(uri)?.use { stream ->
                    var total = 0
                    while (total < header.size) {
                        val n = stream.read(header, total, header.size - total)
                        if (n <= 0) break
                        total += n
                    }
                    bytesRead = total
                }

                if (bytesRead == 0) {
                    safePost { toast("Selected backup file is empty (0 bytes)") }
                    return@execute
                }

                val isZip = bytesRead >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                val isEncExt = displayName.endsWith(".kura") || displayName.endsWith(".kuro") ||
                    displayName.endsWith(".vbooru") || displayName.endsWith(".enc")
                val hasMagic = BackupCrypto.hasMagic(header.copyOf(bytesRead))

                // If user forced encrypted mode but file is standard ZIP without magic
                if (restoreMode == 0 && isZip && !hasMagic) {
                    safePost {
                        showBlackDialog(
                            title = "Plaintext Archive Detected",
                            subtitle = "The selected file is a standard ZIP archive and does not require a password.",
                            positiveBtnText = "Restore",
                            onPositive = {
                                if (!VaultLock.isUnlocked || VaultLock.sessionId != expectedSession) {
                                    toast("Vault locked or session changed")
                                    return@showBlackDialog
                                }
                                bg.execute {
                                    try {
                                        contentResolver.openInputStream(uri)?.use { rawIn ->
                                            processZipStream(rawIn, expectedSession)
                                        }
                                    } catch (e: Exception) {
                                        safePost { toast("Restore failed: ${e.message}") }
                                    }
                                }
                            }
                        ) { container, _ -> }
                    }
                    return@execute
                }

                val isEncrypted = (restoreMode == 0) ||
                    hasMagic ||
                    (isEncExt && !isZip) ||
                    (!isZip && bytesRead > 0 && restoreMode != 1)

                if (isEncrypted) {
                    safePost {
                        promptRestorePassphrase { pass ->
                            if (!VaultLock.isUnlocked || VaultLock.sessionId != expectedSession) {
                                toast("Vault locked or session changed")
                                java.util.Arrays.fill(pass, ' ')
                                return@promptRestorePassphrase
                            }
                            toast("Decrypting and importing backup in background...")
                            bg.execute bgDecrypt@{
                                if (!VaultLock.isUnlocked || VaultLock.sessionId != expectedSession) {
                                    java.util.Arrays.fill(pass, ' ')
                                    return@bgDecrypt
                                }
                                val tempZip = File(cacheDir, "decrypted_restore_tmp_${UUID.randomUUID().toString().take(8)}.zip")
                                try {
                                    contentResolver.openInputStream(uri)?.use { cipherIn ->
                                        tempZip.outputStream().use { plainOut ->
                                            BackupCrypto.decryptStream(cipherIn, plainOut, pass)
                                        }
                                    } ?: throw java.io.IOException("Cannot open input stream")

                                    if (!VaultLock.isUnlocked || VaultLock.sessionId != expectedSession) {
                                        throw java.io.IOException("Vault locked during decryption")
                                    }

                                    tempZip.inputStream().use { decryptedIn ->
                                        processZipStream(decryptedIn, expectedSession)
                                    }
                                } catch (e: Exception) {
                                    val isAuthError = e is javax.crypto.AEADBadTagException ||
                                        e.cause is javax.crypto.AEADBadTagException ||
                                        e.message?.contains("Tag mismatch", ignoreCase = true) == true ||
                                        e.message?.contains("mac check in GCM failed", ignoreCase = true) == true
                                    val msg = if (isAuthError) "Wrong password or corrupted archive" else "Restore failed: ${e.message}"
                                    safePost { toast(msg) }
                                } finally {
                                    vault.secureShred(tempZip)
                                    java.util.Arrays.fill(pass, ' ')
                                }
                            }
                        }
                    }
                } else {
                    safePost { toast("Importing files from ZIP in background...") }
                    contentResolver.openInputStream(uri)?.use { rawIn ->
                        processZipStream(rawIn, expectedSession)
                    }
                }
            } catch (e: Exception) {
                safePost { toast("Failed to read backup: ${e.message}") }
            }
        }
    }

    /**
     * Restores a backup archive, streaming each entry straight into the vault.
     *
     * The previous implementation ran two passes: pass 1 extracted *every*
     * entry to `cacheDir` as plaintext, then pass 2 encrypted each temp file
     * into the vault and shredded it. On a large backup that meant the archive
     * had to fit twice (plaintext staging + encrypted vault) in app storage,
     * `cacheDir` could fill up and fail the restore with ENOSPC partway through,
     * and every photo sat unencrypted on disk in between - in a vault app.
     *
     * This does it in one pass: each entry is encrypted into the vault as it is
     * read, so peak extra disk is one file and no plaintext is ever written.
     * The zip-bomb guards are kept, and `manifest.json` is still handled up
     * front because the format writes it as the first entry.
     *
     * Atomicity comes from the fact that a restore only ever *adds*: every
     * entry is stored under a fresh UUID name, and a hash collision merges tags
     * into the existing row rather than replacing the file. [RestoreJournal]
     * records that work, so an archive that turns out to be truncated, cancelled
     * by the user, or cut short by a lock is rolled back in full via
     * [rollbackRestore] rather than leaving half a backup behind. Rolling back
     * costs no extra disk, which is why this did not reintroduce the two-pass
     * plaintext staging the previous version used.
     *
     * A per-entry failure is still tolerated (that entry is dropped, the rest of
     * the archive continues), matching the original behaviour.
     */
    private fun processZipStream(rawIn: java.io.InputStream, expectedSession: Long) {
        var count = 0
        var aborted = false
        val journal = RestoreJournal()
        if (!VaultLock.isUnlocked || VaultLock.sessionId != expectedSession) return
        val manifestMap = mutableMapOf<String, RestoredMeta>()
        val pendingEntries = mutableListOf<String>()
        var totalExtractedBytes = 0L
        val maxTotalBytes = 15L * 1024 * 1024 * 1024 // 15 GB total
        val maxEntryBytes = 1024L * 1024 * 1024 // 1 GB per file

        try {
            VaultProgress.start("Restoring Backup", total = -1, canCancel = true)

            // 0. Pre-clean 0-byte ghost rows left by earlier corrupted restores.
            // Wrapped in one transaction: previously this was N separate writes,
            // so a large vault crawled before the first file was restored.
            db.runInTransaction {
                val allExisting = db.allItems()
                for (it in allExisting) {
                    if (VaultProgress.isCancelled || !VaultLock.isUnlocked || VaultLock.sessionId != expectedSession) return@runInTransaction
                    val f = vault.fileFor(it.fileName)
                    if (!f.exists() || f.length() == 0L) {
                        db.delete(it.id)
                        vault.delete(it.fileName)
                    }
                }
            }

            ZipInputStream(BufferedInputStream(rawIn)).use { zipIn ->
                val buf = ByteArray(64 * 1024)
                var scanned = 0
                while (true) {
                    if (VaultProgress.isCancelled || !VaultLock.isUnlocked || VaultLock.sessionId != expectedSession) {
                        aborted = true
                        break
                    }
                    val entry = try {
                        zipIn.nextEntry ?: break
                    } catch (e: Exception) {
                        android.util.Log.e("KuraRestore", "Zip entry read failed", e)
                        aborted = true
                        break
                    }
                    val entryName = entry.name
                    if (entry.isDirectory ||
                        entryName.contains("__MACOSX") ||
                        entryName.startsWith(".") ||
                        File(entryName).name.startsWith("._") ||
                        File(entryName).name.equals(".DS_Store", ignoreCase = true) ||
                        File(entryName).name.equals("Thumbs.db", ignoreCase = true)) {
                        zipIn.closeEntry()
                        continue
                    }

                    val name = File(entryName).name

                    if (name == "manifest.json") {
                        parseManifestEntry(zipIn, manifestMap)
                        zipIn.closeEntry()
                        continue
                    }

                    pendingEntries.add(name)
                    scanned++
                    VaultProgress.update(scanned, -1, "Restoring ($scanned files)")

                    // Encrypt straight into the vault. Nothing plaintext is
                    // written to disk at any point during a restore.
                    val meta = manifestMap[name]
                    val mime = meta?.mime ?: guessMime(name)
                    val fav = meta?.favorite ?: false
                    val tags = meta?.tags ?: emptyList()
                    val ext = extFor(mime, name)
                    val vaultName = UUID.randomUUID().toString() + ext
                    var entryBytes = 0L
                    var truncated = false
                    try {
                        val limited = object : java.io.FilterInputStream(zipIn) {
                            override fun read(): Int {
                                if (entryBytes >= maxEntryBytes) { truncated = true; return -1 }
                                val b = super.read()
                                if (b >= 0) entryBytes++
                                return b
                            }
                            override fun read(b: ByteArray, off: Int, len: Int): Int {
                                if (entryBytes >= maxEntryBytes) { truncated = true; return -1 }
                                val room = minOf(len.toLong(), maxEntryBytes - entryBytes).toInt()
                                if (room <= 0) { truncated = true; return -1 }
                                val n = super.read(b, off, room)
                                if (n > 0) entryBytes += n
                                if (n > 0 && entryBytes >= maxEntryBytes) truncated = true
                                return n
                            }
                        }
                        val (bytesWritten, hash) = vault.encryptStream(limited, vaultName)
                        if (truncated || totalExtractedBytes + bytesWritten > maxTotalBytes) {
                            vault.delete(vaultName)
                            throw java.io.IOException("Restore archive exceeds size limit (zip bomb protection)")
                        }
                        totalExtractedBytes += bytesWritten
                        if (bytesWritten <= 0L) {
                            vault.delete(vaultName)
                            zipIn.closeEntry()
                            continue
                        }

                        val existing = db.findByHash(hash)
                        if (existing != null) {
                            val exFile = vault.fileFor(existing.fileName)
                            if (exFile.exists() && exFile.length() > 0L) {
                                vault.delete(vaultName)
                                // Merging tags/favorite touches a row that predates
                                // this restore, so capture its prior state first.
                                if (tags.isNotEmpty() || fav) {
                                    val prior = db.get(existing.id)
                                    if (prior != null) {
                                        journal.merge(RestoreMerge(prior.id, prior.tags, prior.favorite))
                                    }
                                }
                                if (tags.isNotEmpty()) db.addTags(existing.id, tags)
                                if (fav) db.setFavorite(existing.id, true)
                                count++
                            } else {
                                // Ghost row: the pre-clean pass above already
                                // schedules these for removal and the file is
                                // missing or zero-length, so there is nothing on
                                // disk worth preserving. Recorded so an aborted
                                // restore can put the row back.
                                journal.ghost(
                                    RestoreGhost(
                                        existing.fileName, existing.mime, existing.width,
                                        existing.height, existing.durationMs, existing.tags,
                                        existing.rotation
                                    )
                                )
                                db.delete(existing.id)
                                val nid = db.insertItem(vaultName, mime, 0, 0, 0, tags, hash)
                                journal.createdRow(nid)
                                journal.createdFile(vaultName)
                                if (fav) db.setFavorite(nid, true)
                                count++
                            }
                        } else {
                            val (w, h, d) = if (mime.startsWith("video")) {
                                val p = vault.probeVideo(vaultName)
                                if (p.first > 0 && p.second > 0) p
                                else { val ip = vault.probeImage(vaultName); Triple(ip.first, ip.second, p.third) }
                            } else {
                                val (iw, ih) = vault.probeImage(vaultName)
                                if (iw > 0 && ih > 0) Triple(iw, ih, 0) else vault.probeVideo(vaultName)
                            }
                            val id = db.insertItem(vaultName, mime, w, h, d, tags, hash)
                            journal.createdRow(id)
                            journal.createdFile(vaultName)
                            if (fav) db.setFavorite(id, true)
                            count++
                        }
                    } catch (e: Exception) {
                        vault.delete(vaultName)
                        android.util.Log.e("KuraRestore", "Failed to restore entry $name", e)
                    } finally {
                        // Drain whatever is left of the entry so the zip stream
                        // stays aligned for the next one.
                        try { while (zipIn.read(buf) > 0) { } } catch (_: Exception) {}
                    }
                    zipIn.closeEntry()
                }
            }

            if (aborted) {
                rollbackRestore(journal)
                count = 0
            }

            safePost {
                if (aborted) {
                    when {
                        VaultProgress.isCancelled -> toast("Restore cancelled. No changes were kept.")
                        !VaultLock.isUnlocked || VaultLock.sessionId != expectedSession ->
                            toast("Vault session changed. Restore aborted, no changes were kept.")
                        else -> toast("Archive corrupted or unreadable. Restore rolled back, no changes were kept.")
                    }
                } else if (count > 0) {
                    toast("Successfully restored $count files from backup!")
                } else {
                    toast("No files restored: archive empty")
                }
                recreate()
            }
        } catch (e: Exception) {
            rollbackRestore(journal)
            safePost {
                toast("Restore failed, no changes were kept: ${e.message}")
                recreate()
            }
        } finally {
            VaultProgress.finish()
        }
    }

    /**
     * Undoes a partially applied restore per [RestoreJournal.undoPlan]: drops
     * the rows and encrypted files it created, restores rows it replaced, and
     * reverts tag/favorite merges it made to rows that predate it.
     */
    private fun rollbackRestore(journal: RestoreJournal) {
        if (journal.isEmpty) return
        try {
            for (undo in journal.undoPlan()) {
                when (undo) {
                    is RestoreUndo.DropRow -> {
                        try { db.delete(undo.id) } catch (_: Exception) {}
                    }
                    is RestoreUndo.ShredFile -> {
                        try { vault.delete(undo.name) } catch (_: Exception) {}
                    }
                    is RestoreUndo.ReinsertGhost -> {
                        val g = undo.ghost
                        try {
                            db.insertItem(
                                g.fileName, g.mime, g.width, g.height,
                                g.durationMs, g.tags, "", g.rotation
                            )
                        } catch (_: Exception) {}
                    }
                    is RestoreUndo.RevertMerge -> {
                        val m = undo.merge
                        try {
                            db.setTags(m.id, m.priorTags)
                            db.setFavorite(m.id, m.priorFavorite)
                        } catch (_: Exception) {}
                    }
                }
            }
            android.util.Log.i("KuraRestore", "Rolled back partial restore (${journal.undoPlan().size} steps)")
        } catch (e: Exception) {
            android.util.Log.e("KuraRestore", "Rollback failed", e)
        }
    }

    /**
     * Parses a `manifest.json` zip entry into [out].
     *
     * The cap was raised to 64MB because a 50k-item manifest with tags
     * comfortably exceeds the old 10MB limit, and exceeding it silently
     * dropped *all* tags for the restore rather than failing loudly.
     */
    private fun parseManifestEntry(zipIn: ZipInputStream, out: MutableMap<String, RestoredMeta>) {
        val baos = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        var total = 0L
        val maxManifest = 64L * 1024 * 1024
        while (true) {
            val n = zipIn.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxManifest) break
            baos.write(buf, 0, n)
        }
        try {
            val arr = JSONTokener(baos.toString(Charsets.UTF_8.name())).nextValue() as? JSONArray ?: return
            for (i in 0 until arr.length()) {
                try {
                    val obj = arr.getJSONObject(i)
                    val fn = obj.getString("file_name")
                    val m = if (obj.has("mime")) obj.getString("mime").takeIf { it.isNotEmpty() } else null
                    val fav = obj.optBoolean("favorite", false)
                    val tArr = obj.optJSONArray("tags")
                    val tList = mutableListOf<String>()
                    if (tArr != null) for (j in 0 until tArr.length()) tList.add(tArr.getString(j))
                    out[fn] = RestoredMeta(m, fav, tList)
                } catch (e: Exception) {
                    android.util.Log.w("KuraRestore", "Skipping bad manifest entry $i", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("KuraRestore", "Failed to parse manifest.json", e)
        }
    }

    // 3. Export all decrypted files to a device folder
    private val exportFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        VaultLock.isPickingMedia = false
        if (uri == null) return@registerForActivityResult
        val startSession = VaultLock.sessionId
        toast("Exporting files to selected folder...")
        bg.execute {
            try {
                val targetDir = DocumentFile.fromTreeUri(this, uri)
                    ?: throw java.io.IOException("Cannot access target folder")
                val allItems = db.allItems()
                var count = 0
                for (it in allItems) {
                    if (VaultProgress.isCancelled || VaultLock.sessionId != startSession) break
                    if (!vault.fileFor(it.fileName).exists()) continue
                    try {
                        val mime = if (it.mime.isNotEmpty()) it.mime else guessMime(it.fileName)
                        val doc = targetDir.createFile(mime, it.fileName)
                            ?: targetDir.findFile(it.fileName)
                            ?: continue
                        contentResolver.openOutputStream(doc.uri)?.use { out ->
                            vault.exportToStream(it.fileName, out)
                        }
                        count++
                    } catch (e: Exception) {
                        android.util.Log.e("KuraExport", "Error exporting ${it.fileName} to folder", e)
                    }
                }
                safePost {
                    if (VaultLock.sessionId != startSession) {
                        toast("Vault locked or session changed. Export aborted ($count files exported).")
                    } else {
                        toast("Successfully exported $count of ${allItems.size} files to folder!")
                    }
                }
            } catch (e: Exception) {
                safePost { toast("Folder export failed: ${e.message}") }
            }
        }
    }

    // 4. Batch import all images and videos from a device folder
    private val importFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        VaultLock.isPickingMedia = false
        if (uri == null) return@registerForActivityResult
        val startSession = VaultLock.sessionId
        toast("Importing files from selected folder...")
        bg.execute {
            try {
                val sourceDir = DocumentFile.fromTreeUri(this, uri)
                    ?: throw java.io.IOException("Cannot access source folder")
                val files = sourceDir.listFiles().filter {
                    val m = it.type ?: guessMime(it.name ?: "")
                    m.startsWith("image/") || m.startsWith("video/")
                }
                var count = 0
                var duplicatesSkipped = 0
                for (doc in files) {
                    if (VaultProgress.isCancelled || VaultLock.sessionId != startSession) break
                    try {
                        val originalName = doc.name ?: "media"
                        val mime = doc.type ?: guessMime(originalName)
                        val ext = extFor(mime, originalName)
                        val vaultName = UUID.randomUUID().toString() + ext

                        val (bytesWritten, hash) = contentResolver.openInputStream(doc.uri)?.use { inp ->
                            vault.encryptStream(inp, vaultName)
                        } ?: continue

                        if (bytesWritten <= 0L) {
                            vault.delete(vaultName)
                            continue
                        }

                        val existing = db.findByHash(hash)
                        if (existing != null) {
                            vault.delete(vaultName)
                            duplicatesSkipped++
                            if (prefs.deleteOriginalOnImport) {
                                try { doc.delete() } catch (_: Exception) {}
                            }
                            continue
                        }

                        val (w, h, d) = if (mime.startsWith("video")) {
                            vault.probeVideo(vaultName)
                        } else {
                            val (iw, ih) = vault.probeImage(vaultName)
                            Triple(iw, ih, 0)
                        }

                        db.insertItem(vaultName, mime, w, h, d, emptyList(), hash)
                        count++
                        if (prefs.deleteOriginalOnImport) {
                            try { doc.delete() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}
                }
                safePost {
                    if (VaultLock.sessionId != startSession) {
                        toast("Vault locked or session changed. Import aborted ($count files imported).")
                    } else {
                        val msg = if (duplicatesSkipped > 0) {
                            "Imported $count files ($duplicatesSkipped duplicates skipped)"
                        } else {
                            "Successfully imported $count files from folder into vault!"
                        }
                        toast(msg)
                    }
                    recreate()
                }
            } catch (e: Exception) {
                safePost { toast("Folder import failed: ${e.message}") }
            }
        }
    }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        vault = CryptoVault(this)
        db = BooruDb(this)
        // Touching secure prefs is what actually opens (or fails to open) the
        // keystore-backed store. The degraded-keystore warning is shown on the
        // lock screen before the vault ever opens, so it is not repeated here.
        prefs.hasPin()
        restoreSettingsScrollY = if (restoreScrollOnCreate) pendingScrollY else 0
        restoreScrollOnCreate = false
        build()
    }

    private fun section(parent: LinearLayout, t: String) {
        parent.addView(TextView(this).apply {
            text = t
            textSize = 14f
            setTextColor(if (prefs.monochromeMode) 0xFFE0E0E0.toInt() else prefs.accentColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(8, 20, 8, 8)
        })
    }

    private fun card(parent: LinearLayout): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeUtils.cardBackground(prefs, 16f)
            setPadding(20, 16, 20, 16)
        }
        parent.addView(c, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 })
        return c
    }

    private fun settingRow(
        parent: LinearLayout,
        title: String,
        subtitle: String? = null,
        value: String? = null,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setPadding(18, 12, 18, 12)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                onClick()
            }
        }

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleTv = TextView(this).apply {
            text = title
            textSize = 13f
            setTextColor(prefs.textColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        textCol.addView(titleTv)

        if (subtitle != null) {
            val subTv = TextView(this).apply {
                text = subtitle
                textSize = 11f
                setTextColor(prefs.textColorSecondary())
                setPadding(0, 2, 0, 0)
            }
            textCol.addView(subTv)
        }
        row.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        if (value != null) {
            val valTv = TextView(this).apply {
                text = value
                textSize = 12f
                setTextColor(if (value in listOf("ON", "ACTIVE", "VISIBLE", "AUTOPLAY")) prefs.accentColor() else prefs.textColorSecondary())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(12, 0, 0, 0)
            }
            row.addView(valTv)
        }

        parent.addView(row, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 8 })
        return row
    }

    private fun addSupportCard(parent: LinearLayout) {
        val accentRgb = prefs.accentColor() and 0x00FFFFFF
        val supportCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    0x30000000.toInt() or accentRgb,
                    0x14000000.toInt() or accentRgb
                )
            ).apply {
                cornerRadius = dpF(20f)
                setStroke(dp(1), 0x66000000 or accentRgb)
            }
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        headerRow.addView(TextView(this).apply {
            text = "☕"
            textSize = 34f
        }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { rightMargin = dp(14) })

        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        textCol.addView(TextView(this).apply {
            text = "Support Kura"
            textSize = 17f
            setTextColor(prefs.textColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = "Kura stays free with no ads, no tracking, and no accounts. If it earned its keep, a tip helps keep updates coming."
            textSize = 12.5f
            setTextColor(prefs.textColorSecondary())
            setLineSpacing(0f, 1.15f)
            setPadding(0, dp(4), 0, 0)
        })
        headerRow.addView(textCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        supportCard.addView(headerRow)

        val cta = TextView(this).apply {
            text = "Buy Me a Coffee"
            textSize = 14f
            setTextColor(ThemeUtils.buttonTextColor(prefs, true))
            setTypeface(null, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER
            background = ThemeUtils.buttonBackground(prefs, true, 999f)
            setPadding(dp(16), dp(13), dp(16), dp(13))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(SUPPORT_URL)))
                } catch (e: ActivityNotFoundException) {
                    toast("No browser available to open the support page.")
                }
            }
        }
        supportCard.addView(cta, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        parent.addView(supportCard, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = 12 })
    }

    private fun showIconPicker() {
        showBlackDialog(
            title = "App Launcher Icon",
            subtitle = "Choose app disguise icon and name"
        ) { container, dialog ->
            for (icon in AppIconManager.ICONS) {
                val isSelected = icon.key == prefs.appIcon
                val iconImg = ImageView(this).apply {
                    setImageResource(icon.drawableRes)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    background = GradientDrawable().apply {
                        setColor(0xFF1E1E1E.toInt())
                        cornerRadius = dp(10).toFloat()
                        if (isSelected) setStroke(dp(1.5f), prefs.accentColor())
                    }
                }
                val iconBox = FrameLayout(this).apply {
                    addView(iconImg, FrameLayout.LayoutParams(dp(44), dp(44), Gravity.CENTER))
                }

                val sub = when (icon.key) {
                    "kura" -> "Kanji cipher storehouse"
                    "bust" -> "Neon bust contour"
                    "curves" -> "Neon curves silhouette"
                    "contour" -> "Garter line contours"
                    "swimwear" -> "Swimwear outline"
                    "hourglass" -> "Hourglass figure"
                    "sakura" -> "Japanese cherry blossom"
                    "kitsune" -> "Cyber fox spirit"
                    "katana" -> "Neon blade & hilt"
                    "crescent" -> "Nocturnal crescent moon"
                    "discreet" -> "Stealth security lock"
                    else -> "Launcher disguise"
                }

                addDialogOptionRow(
                    parent = container,
                    title = icon.label,
                    subtitle = sub,
                    selected = isSelected,
                    leadingView = iconBox
                ) {
                    AppIconManager.setAppIcon(this, icon.key)
                    toast("App icon changed to ${icon.label}")
                    dialog.dismiss()
                    recreate()
                }
            }
        }
    }

    private fun themePaletteLabel(): String = when (prefs.themeMode) {
        "amoled" -> "AMOLED Black"
        "dark" -> "Classic Dark"
        "sakura" -> "Sakura Night"
        "midnight" -> "Midnight Blue"
        "light" -> "Clean Light"
        else -> "Classic Dark"
    }

    private fun accentLabel(): String = when (prefs.accent) {
        "pink" -> "Neon Pink"
        "purple" -> "Electric Purple"
        "blue" -> "Cyber Blue"
        "green" -> "Matrix Green"
        "orange" -> "Amber Orange"
        "red" -> "Crimson Red"
        else -> "Neon Pink"
    }

    /** Single row summarizing palette + accent so the settings list stays short. */
    private fun themeSummary(): String = when {
        prefs.monochromeMode -> "Monochrome Silver"
        prefs.themeMode == "light" -> "Clean Light • ${accentLabel()}"
        else -> "${themePaletteLabel()} • ${accentLabel()}"
    }

    private fun showThemePicker() {
        val palettes = listOf(
            Triple("amoled", "AMOLED Black", "Pure #000000 pitch black for OLED"),
            Triple("dark", "Classic Dark", "Deep dark grey #121212 palette"),
            Triple("sakura", "Sakura Night", "Dark cherry blossom with subtle pink tint"),
            Triple("midnight", "Midnight Blue", "Deep nocturnal navy blue #0B0E1A"),
            Triple("light", "Clean Light", "Bright clean light aesthetic #FAF7F8")
        )
        val accents = listOf(
            "pink" to 0xFFE91E63.toInt(),
            "purple" to 0xFFAB47BC.toInt(),
            "blue" to 0xFF42A5F5.toInt(),
            "green" to 0xFF66BB6A.toInt(),
            "orange" to 0xFFFFA726.toInt(),
            "red" to 0xFFEF5350.toInt()
        )

        var pendingTheme = prefs.themeMode
        var pendingAccent = prefs.accent
        var pendingMono = prefs.monochromeMode

        showBlackDialog(
            title = "Theme",
            subtitle = "Background palette and accent color",
            positiveBtnText = "Apply",
            onPositive = {
                prefs.themeMode = pendingTheme
                prefs.accent = pendingAccent
                prefs.monochromeMode = pendingMono
                recreate()
            }
        ) { container, _ ->
            val paletteBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            val accentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            fun renderAll() {
                paletteBox.removeAllViews()
                accentBox.removeAllViews()

                for ((key, label, desc) in palettes) {
                    val swatchBg = when (key) {
                        "amoled" -> 0xFF000000.toInt()
                        "dark" -> 0xFF121212.toInt()
                        "sakura" -> 0xFF150F13.toInt()
                        "midnight" -> 0xFF0B0E1A.toInt()
                        else -> 0xFFFAF7F8.toInt()
                    }
                    val swatchBorder = when (key) {
                        "amoled" -> 0xFF3A3A3A.toInt()
                        "sakura" -> 0xFFE91E63.toInt()
                        "midnight" -> 0xFF42A5F5.toInt()
                        "light" -> 0xFF666666.toInt()
                        else -> 0xFF3A3A3A.toInt()
                    }
                    val swatch = View(this).apply {
                        background = GradientDrawable().apply {
                            setColor(swatchBg)
                            cornerRadius = dp(9).toFloat()
                            setStroke(dp(1), swatchBorder)
                        }
                    }
                    val swatchBox = FrameLayout(this).apply {
                        addView(swatch, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                    }

                    addDialogOptionRow(
                        parent = paletteBox,
                        title = label,
                        subtitle = desc,
                        selected = !pendingMono && pendingTheme == key,
                        leadingView = swatchBox
                    ) {
                        pendingTheme = key
                        pendingMono = false
                        renderAll()
                    }
                }

                val monoSwatch = View(this).apply {
                    background = GradientDrawable().apply {
                        setColor(0xFFD8D8D8.toInt())
                        cornerRadius = dp(9).toFloat()
                        setStroke(dp(1), 0xFF6E6E6E.toInt())
                    }
                }
                val monoBox = FrameLayout(this).apply {
                    addView(monoSwatch, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.CENTER))
                }
                addDialogOptionRow(
                    parent = paletteBox,
                    title = "Monochrome",
                    subtitle = "Silver-white, zero color tint",
                    selected = pendingMono,
                    leadingView = monoBox
                ) {
                    pendingMono = !pendingMono
                    renderAll()
                }

                for ((key, colorVal) in accents) {
                    val dot = View(this).apply {
                        background = GradientDrawable().apply {
                            setColor(colorVal)
                            shape = GradientDrawable.OVAL
                        }
                    }
                    val dotBox = FrameLayout(this).apply {
                        addView(dot, FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER))
                    }

                    addDialogOptionRow(
                        parent = accentBox,
                        title = accentName(key),
                        subtitle = if (pendingMono) "Selecting turns monochrome off" else null,
                        selected = !pendingMono && pendingAccent == key,
                        leadingView = dotBox
                    ) {
                        pendingAccent = key
                        pendingMono = false
                        renderAll()
                    }
                }
            }

            dialogSectionLabel(container, "PALETTE")
            container.addView(paletteBox)
            dialogSectionLabel(container, "ACCENT")
            container.addView(accentBox)
            renderAll()
        }
    }

    private fun accentName(key: String): String = when (key) {
        "pink" -> "Neon Pink"
        "purple" -> "Electric Purple"
        "blue" -> "Cyber Blue"
        "green" -> "Matrix Green"
        "orange" -> "Amber Orange"
        "red" -> "Crimson Red"
        else -> key.replaceFirstChar { it.uppercase() }
    }

    private fun showColumnsPicker() {
        val columnOpts = listOf(
            Triple(2, "2 Columns", "Large media thumbnails (spacious)"),
            Triple(3, "3 Columns", "Standard balanced grid (recommended)"),
            Triple(4, "4 Columns", "Compact high-density view")
        )
        showBlackDialog(
            title = "Grid Columns",
            subtitle = "Number of columns in homescreen media gallery"
        ) { container, dialog ->
            for ((cols, label, desc) in columnOpts) {
                val isSelected = prefs.columns == cols
                val preview = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    val tile = dp(8)
                    val gap = dp(2)
                    for (c in 0 until cols) {
                        addView(View(this@SettingsActivity).apply {
                            background = GradientDrawable().apply {
                                setColor(if (isSelected) prefs.accentColor() else 0xFF3A3A3A.toInt())
                                cornerRadius = dp(2).toFloat()
                            }
                        }, LinearLayout.LayoutParams(tile, dp(18)).apply {
                            if (c > 0) leftMargin = gap
                        })
                    }
                }
                val box = FrameLayout(this).apply {
                    addView(preview, FrameLayout.LayoutParams(dp(40), dp(30), Gravity.CENTER))
                }

                addDialogOptionRow(
                    parent = container,
                    title = label,
                    subtitle = desc,
                    selected = isSelected,
                    leadingView = box
                ) {
                    prefs.columns = cols
                    dialog.dismiss()
                    recreate()
                }
            }
        }
    }

    private fun showImportPlacementPicker() {
        val options = listOf(
            Triple("top", "On Top (+)", "Small '+' button in top header bar"),
            Triple("bottom_circle", "Bottom Floating Circle (+)", "Floating action button in bottom right corner"),
            Triple("bottom_bar", "Full Bottom Bar (+ IMPORT)", "Wide action bar at bottom of screen"),
            Triple("hidden", "Hidden", "Hide button from homescreen (import via menu)")
        )
        showBlackDialog(
            title = "Import Button Position",
            subtitle = "Choose where the import media button appears"
        ) { container, dialog ->
            for ((key, label, desc) in options) {
                val isSelected = prefs.importButtonPlacement == key
                addDialogOptionRow(
                    parent = container,
                    title = label,
                    subtitle = desc,
                    selected = isSelected
                ) {
                    prefs.importButtonPlacement = key
                    dialog.dismiss()
                    recreate()
                }
            }
        }
    }

    private fun showTimeoutPicker() {
        val timeouts = listOf(
            Triple(0, "Immediate", "Locks as soon as the app is backgrounded"),
            Triple(30, "30 seconds", "Brief grace period before locking"),
            Triple(60, "1 minute", "Standard 60-second background delay"),
            Triple(300, "5 minutes", "Extended 5-minute inactivity session"),
            Triple(-1, "Never", "Stay unlocked until manually locked")
        )
        showBlackDialog(
            title = "Auto-Lock Timeout",
            subtitle = "Lock vault after background inactivity delay"
        ) { container, dialog ->
            for ((sec, label, desc) in timeouts) {
                val isSelected = prefs.autoLockTimeout == sec
                addDialogOptionRow(
                    parent = container,
                    title = label,
                    subtitle = desc,
                    selected = isSelected
                ) {
                    prefs.autoLockTimeout = sec
                    toast("Auto-lock set to $label")
                    dialog.dismiss()
                    recreate()
                }
            }
        }
    }

    private fun showTabsPicker() {
        val tabEntries = listOf(
            "all" to "All",
            "flow" to "Flow",
            "photos" to "Photos",
            "videos" to "Videos",
            "gifs" to "GIFs"
        )
        val curChecked = mutableMapOf(
            "all" to prefs.showCategoryAll,
            "flow" to prefs.showCategoryFlow,
            "photos" to prefs.showCategoryPhotos,
            "videos" to prefs.showCategoryVideos,
            "gifs" to prefs.showCategoryGifs
        )

        showBlackDialog(
            title = "Active Category Tabs",
            subtitle = "Toggle which media categories appear on homescreen",
            positiveBtnText = "Save",
            onPositive = {
                if (curChecked.values.none { it }) curChecked["all"] = true
                prefs.showCategoryAll = curChecked["all"] == true
                prefs.showCategoryFlow = curChecked["flow"] == true
                prefs.showCategoryPhotos = curChecked["photos"] == true
                prefs.showCategoryVideos = curChecked["videos"] == true
                prefs.showCategoryGifs = curChecked["gifs"] == true
                recreate()
            }
        ) { container, _ ->
            val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

            fun renderTabs() {
                listBox.removeAllViews()
                for ((key, label) in tabEntries) {
                    val isChecked = curChecked[key] == true
                    addDialogOptionRow(
                        parent = listBox,
                        title = label,
                        subtitle = "$label media filter tab",
                        selected = isChecked
                    ) {
                        val activeCount = curChecked.values.count { it }
                        if (isChecked && activeCount <= 1) {
                            toast("At least one tab must stay active")
                            return@addDialogOptionRow
                        }
                        curChecked[key] = !isChecked
                        renderTabs()
                    }
                }
            }

            renderTabs()
            container.addView(listBox)
        }
    }

    /**
     * The Updates card.
     *
     * What it can do depends on the flavor, and it says so rather than showing a
     * toggle that quietly does nothing. The offline build cannot reach the
     * network, so its automatic check is inert and the only way in is pointing
     * Kura at an APK that is already on the device. Both flavors can install a
     * verified APK, and neither can install it silently.
     */
    private fun addUpdatesCard(parent: LinearLayout) {
        val online = BuildConfig.NETWORK_UPDATES

        settingRow(
            parent,
            "📡 Check for updates automatically",
            if (online) "Once a day when Kura opens. The install is still your confirmation."
            else "Unavailable: this build declares no network permission.",
            if (online && prefs.autoCheckUpdates) "ON" else "OFF"
        ) {
            if (!online) {
                toast("This build cannot use the network. Install from a file below.")
                return@settingRow
            }
            prefs.autoCheckUpdates = !prefs.autoCheckUpdates
            recreate()
        }

        settingRow(
            parent,
            "🔍 Check now",
            if (online) "Ask the release page for a newer version"
            else "Ask the release page for a newer version, then install it from a file",
            "Scan"
        ) {
            if (online) manualUpdateCheck() else openReleasePage()
        }

        settingRow(
            parent,
            "📦 Install from a file",
            "Pick a downloaded Kura APK. The version and signing key are checked before the installer opens.",
            "Choose"
        ) {
            if (!ApkInstaller.canInstall(this)) {
                toast("Allow Kura to install apps first")
                ApkInstaller.openInstallPermissionSettings(this)
                return@settingRow
            }
            pendingExpectedSha = null
            try {
                pickApkLauncher.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream"))
            } catch (_: Exception) {
                toast("No file picker available")
            }
        }

        settingRow(
            parent,
            "📝 Installed version",
            "v${installedVersionName()}  ·  versionCode ${installedVersionCode()}",
            "Copy key"
        ) {
            val cert = UpdateChecker.ownCertHex(this)
            if (cert == null) {
                toast("Could not read the signing key")
            } else {
                copyToClipboard("Kura signing key SHA-256", cert)
                toast("Signing key copied")
            }
        }
    }

    /** Manual check. Only the online flavor can do anything here. */
    private fun manualUpdateCheck() {
        if (!BuildConfig.NETWORK_UPDATES) {
            openReleasePage()
            return
        }
        toast("Checking for updates...")
        bg.execute {
            val body = UpdateChecker.readSmall(UpdateChecker.UPDATE_JSON_URL)
            val release = body?.let { UpdateChecker.parseRelease(it) }
            val current = installedVersionCode()
            val offer = release
                ?.takeIf { UpdateChecker.isNewer(current, it.versionCode) }
                ?.takeIf { it.forCurrentFlavor() != null }
            mainHandler.post {
                prefs.lastUpdateCheck = System.currentTimeMillis()
                if (release == null) {
                    showUpdateProblem("Could not read the update information. Check the network and try again.")
                } else if (offer == null) {
                    showUpdateProblem("Kura ${installedVersionName()} is the latest version.")
                } else {
                    offerDownload(offer)
                }
            }
        }
    }

    private fun openReleasePage() = ApkInstaller.openExternal(this, ApkInstaller.RELEASE_PAGE)

    /**
     * Downloads a published release, verifies it, then offers the installer.
     * The checksum comes from the same update.json, so a truncated or swapped
     * download is refused before Android is asked to install anything.
     */
    private fun offerDownload(release: ReleaseInfo) {
        val asset = release.forCurrentFlavor() ?: return
        toast("Downloading Kura ${release.versionName}...")
        bg.execute {
            val staged = File(UpdateChecker.stagingDir(this), "kura-${release.versionCode}.apk")
            val ok = UpdateChecker.download(asset.apkUrl, staged)
            val verdict = if (!ok) UpdateChecker.Verdict.Unreadable else UpdateChecker.verify(
                this, staged, installedVersionCode(), asset.sha256, UpdateChecker.ownCertHex(this)
            )
            mainHandler.post { onApkChecked(verdict, release) }
        }
    }

    private fun onApkChecked(verdict: UpdateChecker.Verdict, release: ReleaseInfo? = null) {
        when (verdict) {
            is UpdateChecker.Verdict.Ok -> {
                val name = if (verdict.versionName.isNotBlank()) verdict.versionName else release?.versionName.orEmpty()
                showBlackDialog(
                    title = "Install Kura ${if (name.isNotBlank()) name else "update"}?",
                    subtitle = "The file was checked against the published checksum and signing key.",
                    positiveBtnText = "Install",
                    onPositive = { confirmInstall(verdict.file) },
                    negativeBtnText = "Not now"
                ) { _, _ -> }
            }
            UpdateChecker.Verdict.NotNewer ->
                showUpdateProblem("That file is not newer than the version you are running.")
            UpdateChecker.Verdict.BadHash ->
                showUpdateProblem("That file does not match the published checksum, so it was refused. It may be truncated or altered.")
            UpdateChecker.Verdict.WrongSigner ->
                showUpdateProblem("That file is signed with a different key, so it is not an update of this app.")
            UpdateChecker.Verdict.Unreadable ->
                showUpdateProblem("That file could not be read as a Kura APK.")
        }
    }

    private fun confirmInstall(file: File) {
        if (!ApkInstaller.canInstall(this)) {
            toast("Allow Kura to install apps, then try again")
            ApkInstaller.openInstallPermissionSettings(this)
            return
        }
        ApkInstaller.install(this, file)
    }

    private fun showUpdateProblem(message: String) {
        showBlackDialog(
            title = "No update installed",
            subtitle = message,
            negativeBtnText = "Close"
        ) { _, _ -> }
    }

    private fun showSecurityPolicyDialog() {
        showBlackDialog(
            title = "Security & Architecture",
            subtitle = "Offline-first cryptography & hardening details",
            negativeBtnText = "Close"
        ) { container, _ ->
            val items = listOf(
                "100% Offline Architecture" to "Zero network permissions in AndroidManifest. No analytics, tracking, or remote connections.",
                "AES-256-GCM Encryption" to "Hardware-backed keystore keys with authenticated Galois/Counter Mode encryption at rest.",
                "CE Protected Sandbox" to "Credential-Encrypted app sandbox with chmod 0600 file permissions.",
                "Memory Zeroization" to "Sensitive plaintext byte arrays and passphrases in RAM are overwritten with zeros immediately after decode.",
                "Multi-Pass File Shredding" to "Deleted records are overwritten with pseudo-random garbage before unlinking to prevent flash recovery.",
                "Coercion Decoy Vault" to "An independent second PIN opens a completely isolated decoy vault.",
                "Brute-Force Lockout" to "Keypad digit scrambling and mandatory 24-hour lockout after 3 consecutive failed attempts.",
                "Optional Support Link" to "Kura holds no network permission and never makes requests. Tapping Support opens an external browser of your choice, which then loads the donation page."
            )
            for ((heading, detail) in items) {
                val box = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                }
                box.addView(TextView(this).apply {
                    text = heading
                    textSize = 13.5f
                    setTextColor(Color.WHITE)
                    setTypeface(null, android.graphics.Typeface.BOLD)
                })
                box.addView(TextView(this).apply {
                    text = detail
                    textSize = 12f
                    setTextColor(0xFF8A8A8A.toInt())
                    setLineSpacing(0f, 1.15f)
                    setPadding(0, dp(3), 0, 0)
                })
                container.addView(box, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                container.addView(View(this).apply {
                    setBackgroundColor(0xFF1A1A1A.toInt())
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                ).apply { bottomMargin = dp(8) })
            }
        }
    }

    private fun build() {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = ThemeUtils.backdrop(prefs)
            setPadding(28, 28, 28, 60)
        }

        col.addView(TextView(this).apply {
            text = "kura settings"
            textSize = 24f
            setTextColor(prefs.textColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = "Offline • AES-256-GCM Vault • Fully Customizable"
            textSize = 12f
            setTextColor(prefs.textColorSecondary())
            setPadding(0, 4, 0, 12)
        })

        // 1. Appearance Card
        section(col, "Appearance")
        val appCard = card(col)

        settingRow(appCard, "Theme", "Background palette, accent & monochrome", themeSummary()) {
            showThemePicker()
        }

        val curIconOpt = AppIconManager.ICONS.firstOrNull { it.key == prefs.appIcon } ?: AppIconManager.ICONS[0]
        settingRow(appCard, "App Launcher Icon", "Launcher icon and disguise name", curIconOpt.label) {
            showIconPicker()
        }

        settingRow(appCard, "Edge-to-Edge Display", "Draw content behind system bars", if (prefs.immersiveMode) "ON" else "OFF") {
            prefs.immersiveMode = !prefs.immersiveMode
            recreate()
        }

        settingRow(appCard, "Notification Bar", "Status bar visibility", if (prefs.hideStatusBar) "HIDDEN" else "VISIBLE") {
            prefs.hideStatusBar = !prefs.hideStatusBar
            recreate()
        }

        // 2. Homescreen & Gallery Layout Card
        section(col, "Homescreen & Gallery")
        val layoutCard = card(col)

        settingRow(layoutCard, "Grid Columns", "Thumbnail density in the gallery", "${prefs.columns} columns") {
            showColumnsPicker()
        }

        val importLabel = when (prefs.importButtonPlacement) {
            "top" -> "On Top (+)"
            "bottom_circle" -> "Bottom Circle (+)"
            "bottom_bar" -> "Bottom Bar (+ IMPORT)"
            else -> "Hidden"
        }
        settingRow(layoutCard, "Import Button Position", "Choose where the import button appears", importLabel) {
            showImportPlacementPicker()
        }

        settingRow(layoutCard, "Search Bar", "Top tag search input", if (prefs.showSearchBar) "VISIBLE" else "HIDDEN") {
            prefs.showSearchBar = !prefs.showSearchBar
            recreate()
        }

        settingRow(layoutCard, "Tag Suggestions Bar", "Live instant suggestions row", if (prefs.showTagSuggestions) "VISIBLE" else "HIDDEN") {
            prefs.showTagSuggestions = !prefs.showTagSuggestions
            recreate()
        }

        settingRow(layoutCard, "Favorites (★) Filter", "Quick toggle for favorited items", if (prefs.showFavoritesFilter) "ON" else "OFF") {
            prefs.showFavoritesFilter = !prefs.showFavoritesFilter
            recreate()
        }

        settingRow(layoutCard, "Sort Filter", "Sort order toggle (Date, Random)", if (prefs.showSortFilter) "ON" else "OFF") {
            prefs.showSortFilter = !prefs.showSortFilter
            recreate()
        }

        settingRow(layoutCard, "Category Filter Bar", "Top media type tabs", if (prefs.showCategoryFilter) "VISIBLE" else "HIDDEN") {
            prefs.showCategoryFilter = !prefs.showCategoryFilter
            recreate()
        }

        if (prefs.showCategoryFilter) {
            val activeTabs = mutableListOf<String>()
            if (prefs.showCategoryAll) activeTabs.add("All")
            if (prefs.showCategoryFlow) activeTabs.add("Flow")
            if (prefs.showCategoryPhotos) activeTabs.add("Photos")
            if (prefs.showCategoryVideos) activeTabs.add("Videos")
            if (prefs.showCategoryGifs) activeTabs.add("GIFs")
            settingRow(layoutCard, "Customize Active Tabs", "Choose which category tabs appear", activeTabs.joinToString(", ")) {
                showTabsPicker()
            }
        }

        settingRow(layoutCard, "Grid Badges", "Show video duration and GIF badges on thumbnails", if (prefs.showGridBadges) "VISIBLE" else "HIDDEN") {
            prefs.showGridBadges = !prefs.showGridBadges
            recreate()
        }

        settingRow(layoutCard, "Media Details Bar", "Show file resolution, type, and size in detail view", if (prefs.showMediaDetails) "VISIBLE" else "HIDDEN") {
            prefs.showMediaDetails = !prefs.showMediaDetails
            recreate()
        }

        // 3. Security & Privacy Card
        section(col, "Security & Privacy")
        val secCard = card(col)

        settingRow(secCard, "Change Vault PIN", "Update master encryption PIN", "Change") {
            prefs.setPinPrompt(this) { recreate() }
        }

        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
        val bioOk = BiometricManager.from(this).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        settingRow(secCard, "Biometric Unlock", "Unlock with fingerprint or face", if (prefs.biometricEnabled) "ON" else "OFF") {
            if (!bioOk) { toast("Biometric hardware not available or enrolled"); return@settingRow }
            prefs.biometricEnabled = !prefs.biometricEnabled
            recreate()
        }

        val timeoutLabel = when (prefs.autoLockTimeout) {
            0 -> "Immediate"
            30 -> "30 sec"
            60 -> "1 min"
            300 -> "5 min"
            else -> "Never"
        }
        settingRow(secCard, "Auto-Lock Timeout", "Lock vault after background delay", timeoutLabel) {
            showTimeoutPicker()
        }

        val hasDecoy = prefs.hasDecoyPin()
        if (!VaultLock.isDecoy) {
            settingRow(secCard, "Decoy Vault (Coercion Defense)", "Second PIN opens empty dummy vault", if (hasDecoy) "ACTIVE" else "NOT SET") {
                if (hasDecoy) {
                    showChoiceDialog(
                        title = "Decoy PIN Management",
                        options = listOf(
                            "Change Decoy PIN" to "Set a new PIN that opens the empty vault",
                            "Remove Decoy PIN" to "Delete the decoy vault and its PIN"
                        )
                    ) { which ->
                        if (which == 0) {
                            prefs.setDecoyPinPrompt(this) { recreate() }
                        } else {
                            prefs.verifyCurrentPinPrompt(this, "Authorize Decoy Removal", "Confirm master PIN to remove decoy:") {
                                prefs.clearDecoyPin()
                                toast("Decoy PIN removed")
                                recreate()
                            }
                        }
                    }
                } else {
                    prefs.setDecoyPinPrompt(this) { recreate() }
                }
            }
        }

        settingRow(secCard, "Screen Privacy (FLAG_SECURE)", "Block screenshots and app switcher previews", if (prefs.flagSecure) "ON" else "OFF") {
            prefs.verifyCurrentPinPrompt(this, "Screen Privacy Security", "Confirm PIN to change screen privacy:") {
                prefs.flagSecure = !prefs.flagSecure
                recreate()
            }
        }

        settingRow(secCard, "Flip-to-Panic Lock", "Lock instantly when phone is turned face down", if (prefs.flipToPanic) "ON" else "OFF") {
            prefs.flipToPanic = !prefs.flipToPanic
            recreate()
        }

        settingRow(secCard, "Scramble PIN Keypad", "Randomize number pad digits against shoulder surfing", if (prefs.scramblePinKeypad) "ON" else "OFF") {
            prefs.scramblePinKeypad = !prefs.scramblePinKeypad
            recreate()
        }

        settingRow(secCard, "Delete Original on Import", "Remove unencrypted source file after encrypted import", if (prefs.deleteOriginalOnImport) "ON" else "OFF") {
            prefs.deleteOriginalOnImport = !prefs.deleteOriginalOnImport
            recreate()
        }

        val intruderCount = prefs.getIntruderLogs().size
        settingRow(secCard, "Intruder Attempt Log", "Records failed PIN entry timestamps", "$intruderCount logged") {
            showIntruderLogDialog()
        }

        // 4. Playback, Backup & Storage Card
        section(col, "Playback, Backup & Storage")
        val storageCard = card(col)

        settingRow(storageCard, "Detail Video Autoplay", "Automatically start playing video in detail view", if (prefs.autoplayDetailVideos) "ON" else "OFF") {
            prefs.autoplayDetailVideos = !prefs.autoplayDetailVideos
            recreate()
        }

        settingRow(storageCard, "Start Videos Muted", "Mute audio when video starts", if (prefs.alwaysStartMuted) "ON" else "OFF") {
            prefs.alwaysStartMuted = !prefs.alwaysStartMuted
            recreate()
        }

        settingRow(storageCard, "Skip Duplicates on Import", "Hash-check files during import", if (prefs.skipDuplicatesOnImport) "ON" else "OFF") {
            prefs.skipDuplicatesOnImport = !prefs.skipDuplicatesOnImport
            recreate()
        }

        val totalBytes = vault.vaultTotalBytes()
        val fileCount = vault.vaultFileCount()
        val mb = totalBytes / (1024.0 * 1024.0)
        storageCard.addView(TextView(this).apply {
            text = "Storage used: %.2f MB across %d encrypted files".format(mb, fileCount)
            textSize = 12f
            setTextColor(prefs.textColorSecondary())
            setPadding(4, 6, 4, 10)
        })

        val backupRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }
        val backupBtn = Button(this).apply {
            text = "Backup Vault"
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setTextColor(prefs.textColor())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                showChoiceDialog(
                    title = "Backup Vault",
                    subtitle = "Choose the archive format to write",
                    options = listOf(
                        "Encrypted Backup" to ".kura — AES-256-GCM, requires a passphrase",
                        "Plaintext ZIP Archive" to ".zip — readable by anyone, no encryption"
                    )
                ) { which ->
                    val dateStr = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    if (which == 0) {
                        promptBackupPassphrase { pass ->
                            pendingBackupPassphrase = pass
                            VaultLock.isPickingMedia = true
                            exportEncryptedLauncher.launch("kura_backup_$dateStr.kura")
                        }
                    } else {
                        confirmPlaintextBackup {
                            prefs.verifyCurrentPinPrompt(this@SettingsActivity, "Authorize Plaintext Export", "Confirm vault PIN to export unencrypted media:") {
                                VaultLock.isPickingMedia = true
                                exportZipLauncher.launch("kura_backup_$dateStr.zip")
                            }
                        }
                    }
                }
            }
        }
        val restoreBtn = Button(this).apply {
            text = "Restore Backup"
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setTextColor(prefs.textColor())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                showChoiceDialog(
                    title = "Restore Backup",
                    subtitle = "Pick the file you are restoring from",
                    options = listOf(
                        "Encrypted Backup" to ".kura, .kuro or .vbooru archive",
                        "Plaintext ZIP Archive" to ".zip archive written by a plaintext export",
                        "Auto-Detect" to "Detect the format from the file itself"
                    )
                ) { which ->
                    restoreMode = which
                    VaultLock.isPickingMedia = true
                    importZipLauncher.launch(arrayOf("*/*"))
                }
            }
        }
        backupRow.addView(backupBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 6 })
        backupRow.addView(restoreBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        storageCard.addView(backupRow)

        val folderMigRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 8)
        }
        val exportFolderBtn = Button(this).apply {
            text = "Export Folder"
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setTextColor(prefs.textColorSecondary())
            textSize = 12f
            setOnClickListener {
                confirmPlaintextBackup {
                    prefs.verifyCurrentPinPrompt(this@SettingsActivity, "Authorize Folder Export", "Confirm vault PIN to export unencrypted media to a folder:") {
                        VaultLock.isPickingMedia = true
                        exportFolderLauncher.launch(null)
                    }
                }
            }
        }
        val importFolderBtn = Button(this).apply {
            text = "Import Folder"
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setTextColor(prefs.textColorSecondary())
            textSize = 12f
            setOnClickListener {
                VaultLock.isPickingMedia = true
                importFolderLauncher.launch(null)
            }
        }
        folderMigRow.addView(exportFolderBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 6 })
        folderMigRow.addView(importFolderBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        storageCard.addView(folderMigRow)

        val maintRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val wipeCacheBtn = Button(this).apply {
            text = "Wipe Cache"
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setTextColor(prefs.textColorSecondary())
            textSize = 12f
            setOnClickListener {
                BaseVaultActivity.runMaintenance {
                    vault.wipePlayCache()
                    safePost {
                        toast("Playback cache cleaned and shredded")
                        recreate()
                    }
                }
            }
        }
        val cleanBrokenBtn = Button(this).apply {
            text = "Clean Broken"
            background = ThemeUtils.surfaceGlass(prefs, 12f, 1)
            setTextColor(prefs.textColorSecondary())
            textSize = 12f
            setOnClickListener {
                prefs.verifyCurrentPinPrompt(this@SettingsActivity, "Authorize Vault Clean", "Confirm vault PIN to clean broken records:") {
                    showBlackDialog(
                        title = "Clean Broken Media?",
                        subtitle = "Scan vault and remove 0-byte or corrupted records.",
                        positiveBtnText = "Clean",
                        onPositive = {
                            toast("Scanning and cleaning empty records...")
                            bg.execute {
                                var removedCount = 0
                                val all = db.allItems()
                                for (item in all) {
                                    val f = vault.fileFor(item.fileName)
                                    if (!f.exists() || f.length() == 0L) {
                                        db.delete(item.id)
                                        vault.delete(item.fileName)
                                        removedCount++
                                    }
                                }
                                safePost {
                                    toast("Cleaned $removedCount broken/empty item(s).")
                                    recreate()
                                }
                            }
                        }
                    ) { container, _ -> }
                }
            }
        }
        maintRow.addView(wipeCacheBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 6 })
        maintRow.addView(cleanBrokenBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        storageCard.addView(maintRow)

        // 5. Help & Guides Card
        section(col, "Help & Guides")
        val helpCard = card(col)

        settingRow(helpCard, "🏷️ Booru Tagging & Color Guide", "Prefixes (c:, a:, s:, m:), colors & search tips", "Open Guide") {
            Tags.showGuideDialog(this, prefs)
        }

        settingRow(helpCard, "🔄 Replay Interactive Coachmark Tour", "Re-launch step-by-step guides on main & detail views", "Replay") {
            prefs.tutorialCompleted = false
            prefs.mediaClickTutorialCompleted = false
            prefs.detailTutorialCompleted = false
            toast("Interactive tutorial will replay on next screen.")
            finish()
        }

        // 5b. Updates
        section(col, "Updates")
        val updCard = card(col)
        addUpdatesCard(updCard)

        settingRow(helpCard, "🛡️ Security Architecture & Privacy Policy", "Zero-permission offline design & AES-256-GCM encryption", "View Policy") {
            showSecurityPolicyDialog()
        }

        // 6. Support
        section(col, "Support")
        addSupportCard(col)

        val sc = ScrollView(this).apply {
            background = ThemeUtils.backdrop(prefs)
            addView(col)
        }
        settingsScroller = sc
        if (restoreSettingsScrollY > 0) {
            val restoreY = restoreSettingsScrollY
            sc.post { sc.scrollTo(0, restoreY) }
            restoreSettingsScrollY = 0
        }

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            addView(sc, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            val pBar = VaultProgressBar(this@SettingsActivity)
            val pBarLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
            addView(pBar, pBarLp)
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val effectiveEdgeToEdge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM || prefs.edgeToEdge
            val topInset = if (effectiveEdgeToEdge && !prefs.hideStatusBar) sysBars.top else 0
            val bottomInset = if (effectiveEdgeToEdge) sysBars.bottom else 0
            col.setPadding(28, topInset + 28, 28, 72 + bottomInset)
            insets
        }

        setContentView(root)
    }

    private fun showIntruderLogDialog() {
        val logs = prefs.getIntruderLogs()
        val text = if (logs.isEmpty()) {
            "No unauthorized attempts recorded. All clear!"
        } else {
            "Unauthorized failed attempts:\n\n" + logs.joinToString("\n") { "• $it" }
        }

        showBlackDialog(
            title = "Intruder Attempt Log",
            subtitle = if (logs.isEmpty()) null else "${logs.size} recorded attempt(s)",
            positiveBtnText = "Close",
            onPositive = {},
            negativeBtnText = if (logs.isNotEmpty()) "Clear History" else null,
            onNegative = {
                prefs.clearIntruderLogs()
                toast("Intruder log cleared")
                recreate()
            }
        ) { container, _ ->
            dialogBodyText(container, text)
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

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun copyToClipboard(label: String, value: String) {
        getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            ?.let { it as android.content.ClipboardManager }
            ?.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || isDestroyed || !VaultLock.isUnlocked) return
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        bg.shutdownNow()
        if (::db.isInitialized) {
            db.close()
        }
    }
}
