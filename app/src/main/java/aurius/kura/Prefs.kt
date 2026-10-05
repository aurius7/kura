package aurius.kura

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import android.util.Log
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** App settings + PIN lock state. PIN hash lives in EncryptedSharedPreferences. */
class Prefs(ctx: Context) {
    private val app = ctx.applicationContext
    private val ui: SharedPreferences = run {
        val sp = app.getSharedPreferences("kura_ui", Context.MODE_PRIVATE)
        val legacy = app.getSharedPreferences("vbooru_ui", Context.MODE_PRIVATE)
        if (sp.all.isEmpty() && legacy.all.isNotEmpty()) {
            val ed = sp.edit()
            for ((k, v) in legacy.all) {
                when (v) {
                    is Boolean -> ed.putBoolean(k, v)
                    is Int -> ed.putInt(k, v)
                    is Long -> ed.putLong(k, v)
                    is Float -> ed.putFloat(k, v)
                    is String -> ed.putString(k, v)
                }
            }
            ed.apply()
        }
        sp
    }

    private val secure: SharedPreferences by lazy {
        val secState = app.getSharedPreferences("kura_secstate", Context.MODE_PRIVATE)
        try {
            val ks = try {
                val k = java.security.KeyStore.getInstance("AndroidKeyStore")
                k.load(null)
                k
            } catch (_: Exception) { null }
            val alias = if (ks != null && ks.containsAlias("vbooru_master") && !ks.containsAlias("kura_master")) {
                "vbooru_master"
            } else {
                "kura_master"
            }
            val prefName = if (alias == "vbooru_master") "vbooru_secure_prefs" else "kura_secure_prefs"
            val mk = MasterKey.Builder(app, alias)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            val sp = EncryptedSharedPreferences.create(
                app, prefName, mk,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            secState.edit().putBoolean("keystore_degraded", false).apply()
            sp
        } catch (_: Exception) {
            // Keystore unavailable (corrupted keyset, or a device where it never
            // enrolled). Falling back silently would store the PIN hash in the
            // clear, so record it and tell the user rather than degrade quietly.
            secureFallbackActive = true
            secState.edit().putBoolean("keystore_degraded", true).apply()
            Log.e("kura/Prefs", "Keystore unavailable; secure prefs degraded to plaintext", Throwable())
            app.getSharedPreferences("kura_secure_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    /** Current degraded state, persisted so PIN changes stay blocked all session. */
    private fun secureDegraded(): Boolean =
        app.getSharedPreferences("kura_secstate", Context.MODE_PRIVATE)
            .getBoolean("keystore_degraded", false)

    /** True when encrypted prefs could not be opened, so settings are stored in the clear. */
    @Volatile
    var secureFallbackActive: Boolean = false
        private set

    /** One-shot check so the UI can warn the user exactly once per fallback session. */
    fun consumeSecureFallbackWarning(): Boolean {
        if (!secureFallbackActive) return false
        secureFallbackActive = false
        return true
    }

    // ---- Theme & UI customization ----
    var themeMode: String
        get() {
            val m = ui.getString("theme_mode", "dark") ?: "dark"
            return if (m == "glass") "dark" else m
        }
        set(v) = ui.edit().putString("theme_mode", if (v == "glass") "dark" else v).apply()

    var accent: String
        get() = ui.getString("accent", "pink") ?: "pink"
        set(v) = ui.edit().putString("accent", v).apply()

    var columns: Int
        get() = ui.getInt("columns", 3).coerceIn(2, 4)
        set(v) = ui.edit().putInt("columns", v.coerceIn(2, 4)).apply()

    var favoritesOnly: Boolean
        get() = ui.getBoolean("fav_only", false)
        set(v) = ui.edit().putBoolean("fav_only", v).apply()

    var sortOrder: String
        get() = ui.getString("sort_order", "date_desc") ?: "date_desc"
        set(v) = ui.edit().putString("sort_order", v).apply()

    var flagSecure: Boolean
        get() = ui.getBoolean("flag_secure", true)
        set(v) = ui.edit().putBoolean("flag_secure", v).apply()

    var autoLockTimeout: Int
        get() = ui.getInt("auto_lock_timeout", 300) // 300s (5min) default, 0 = immediate, -1 = never
        set(v) = ui.edit().putInt("auto_lock_timeout", v).apply()

    // ---- Homescreen Customization Toggles ----
    var showSearchBar: Boolean
        get() = ui.getBoolean("show_search_bar", true)
        set(v) = ui.edit().putBoolean("show_search_bar", v).apply()

    var showTagSuggestions: Boolean
        get() = ui.getBoolean("show_tag_sugg", true)
        set(v) = ui.edit().putBoolean("show_tag_sugg", v).apply()

    // ---- Updates ----

    /**
     * Whether to look for a new version when Kura opens.
     *
     * Only the online flavor can act on this; the offline build keeps the
     * setting visible so the two builds read the same, and the automatic check
     * simply does nothing there.
     */
    var autoCheckUpdates: Boolean
        get() = ui.getBoolean("auto_check_updates", true)
        set(v) = ui.edit().putBoolean("auto_check_updates", v).apply()

    /** Epoch millis of the last automatic check, so it runs at most daily. */
    var lastUpdateCheck: Long
        get() = ui.getLong("last_update_check", 0L)
        set(v) = ui.edit().putLong("last_update_check", v).apply()

    var appIcon: String
        get() = ui.getString("app_icon", "kura") ?: "kura"
        set(v) = ui.edit().putString("app_icon", v).apply()

    var importButtonPlacement: String
        get() = ui.getString("import_btn_placement", "top") ?: "top"
        set(v) = ui.edit().putString("import_btn_placement", v).apply()

    var showImportButton: Boolean
        get() = importButtonPlacement != "hidden"
        set(v) { if (!v) importButtonPlacement = "hidden" else if (importButtonPlacement == "hidden") importButtonPlacement = "top" }

    var importButtonCircle: Boolean
        get() = importButtonPlacement == "bottom_circle"
        set(v) { if (v) importButtonPlacement = "bottom_circle" }

    var showFavoritesFilter: Boolean
        get() = ui.getBoolean("show_fav_filter", true)
        set(v) = ui.edit().putBoolean("show_fav_filter", v).apply()

    var showSortFilter: Boolean
        get() = ui.getBoolean("show_sort_filter", true)
        set(v) = ui.edit().putBoolean("show_sort_filter", v).apply()

    var headerCollapsed: Boolean
        get() = ui.getBoolean("header_collapsed", false)
        set(v) = ui.edit().putBoolean("header_collapsed", v).apply()

    var showCategoryFilter: Boolean
        get() = ui.getBoolean("show_category_filter", true)
        set(v) = ui.edit().putBoolean("show_category_filter", v).apply()

    var showCategoryAll: Boolean
        get() = ui.getBoolean("cat_show_all", true)
        set(v) = ui.edit().putBoolean("cat_show_all", v).apply()

    var showCategoryFlow: Boolean
        get() = ui.getBoolean("cat_show_flow", true)
        set(v) = ui.edit().putBoolean("cat_show_flow", v).apply()

    var showCategoryPhotos: Boolean
        get() = ui.getBoolean("cat_show_photos", true)
        set(v) = ui.edit().putBoolean("cat_show_photos", v).apply()

    var showCategoryVideos: Boolean
        get() = ui.getBoolean("cat_show_videos", true)
        set(v) = ui.edit().putBoolean("cat_show_videos", v).apply()

    var showCategoryGifs: Boolean
        get() = ui.getBoolean("cat_show_gifs", true)
        set(v) = ui.edit().putBoolean("cat_show_gifs", v).apply()

    var tutorialCompleted: Boolean
        get() = ui.getBoolean(if (VaultLock.isDecoy) "tutorial_completed_decoy" else "tutorial_completed", false)
        set(v) = ui.edit().putBoolean(if (VaultLock.isDecoy) "tutorial_completed_decoy" else "tutorial_completed", v).apply()

    var mediaClickTutorialCompleted: Boolean
        get() = ui.getBoolean(if (VaultLock.isDecoy) "media_click_tutorial_completed_decoy" else "media_click_tutorial_completed", false)
        set(v) = ui.edit().putBoolean(if (VaultLock.isDecoy) "media_click_tutorial_completed_decoy" else "media_click_tutorial_completed", v).apply()

    var detailTutorialCompleted: Boolean
        get() = ui.getBoolean(if (VaultLock.isDecoy) "detail_tutorial_completed_decoy" else "detail_tutorial_completed", false)
        set(v) = ui.edit().putBoolean(if (VaultLock.isDecoy) "detail_tutorial_completed_decoy" else "detail_tutorial_completed", v).apply()

    fun isCategoryEnabled(key: String): Boolean {
        return when (key.lowercase()) {
            "all" -> showCategoryAll
            "flow", "reels" -> showCategoryFlow
            "photos" -> showCategoryPhotos
            "videos" -> showCategoryVideos
            "gifs" -> showCategoryGifs
            else -> true
        }
    }

    fun enabledCategories(): List<Pair<String, String>> {
        val list = mutableListOf<Pair<String, String>>()
        if (showCategoryAll) list.add("all" to "All")
        if (showCategoryFlow) list.add("flow" to "Flow")
        if (showCategoryPhotos) list.add("photos" to "Photos")
        if (showCategoryVideos) list.add("videos" to "Videos")
        if (showCategoryGifs) list.add("gifs" to "GIFs")
        if (list.isEmpty()) list.add("all" to "All")
        return list
    }

    var categoryFilter: String
        get() = ui.getString("category_filter", "all") ?: "all" // "all", "photos", "videos", "gifs"
        set(v) = ui.edit().putString("category_filter", v).apply()

    var immersiveMode: Boolean
        get() = ui.getBoolean("immersive_mode", true)
        set(v) = ui.edit().putBoolean("immersive_mode", v).apply()

    var hideStatusBar: Boolean
        get() = ui.getBoolean("hide_status_bar", false)
        set(v) = ui.edit().putBoolean("hide_status_bar", v).apply()

    // True when the window draws behind the system bars, so we must pad content manually.
    val edgeToEdge: Boolean
        get() = immersiveMode || hideStatusBar

    // ---- Minimalist Customization & Aesthetics ----
    var monochromeMode: Boolean
        get() = ui.getBoolean("monochrome_mode", false)
        set(v) = ui.edit().putBoolean("monochrome_mode", v).apply()

    var showMediaDetails: Boolean
        get() = ui.getBoolean("show_media_details", true)
        set(v) = ui.edit().putBoolean("show_media_details", v).apply()

    var showGridBadges: Boolean
        get() = ui.getBoolean("show_grid_badges", true)
        set(v) = ui.edit().putBoolean("show_grid_badges", v).apply()

    var detailTagStyle: String
        get() = ui.getString("detail_tag_style", "gelbooru") ?: "gelbooru"
        set(v) = ui.edit().putString("detail_tag_style", v).apply()

    var autoplayDetailVideos: Boolean
        get() = ui.getBoolean("autoplay_detail_videos", true)
        set(v) = ui.edit().putBoolean("autoplay_detail_videos", v).apply()

    // ---- Privacy & Security Hardening Toggles ----
    var flipToPanic: Boolean
        get() = ui.getBoolean("flip_to_panic", true)
        set(v) = ui.edit().putBoolean("flip_to_panic", v).apply()

    var deleteOriginalOnImport: Boolean
        get() = ui.getBoolean("del_original_import", false)
        set(v) = ui.edit().putBoolean("del_original_import", v).apply()

    var scramblePinKeypad: Boolean
        get() = ui.getBoolean("scramble_pin_keypad", false)
        set(v) = ui.edit().putBoolean("scramble_pin_keypad", v).apply()

    // ---- Media Player & Import Preferences ----
    var appVolume: Float
        get() = ui.getFloat("app_volume", 0.8f).coerceIn(0f, 1f)
        set(v) = ui.edit().putFloat("app_volume", v.coerceIn(0f, 1f)).apply()

    var alwaysStartMuted: Boolean
        get() = ui.getBoolean("always_start_muted", false)
        set(v) = ui.edit().putBoolean("always_start_muted", v).apply()

    var skipDuplicatesOnImport: Boolean
        get() = ui.getBoolean("skip_duplicates_import", true)
        set(v) = ui.edit().putBoolean("skip_duplicates_import", v).apply()

    // ---- Color Palette ----
    fun isGlass(): Boolean = false

    fun accentColor(): Int {
        if (monochromeMode) return if (isLightTheme()) 0xFF3A3A3A.toInt() else 0xFFEEEEEE.toInt()
        return when (accent) {
            "purple" -> 0xFFAB47BC.toInt()
            "blue" -> 0xFF42A5F5.toInt()
            "green" -> 0xFF66BB6A.toInt()
            "orange" -> 0xFFFFA726.toInt()
            "red" -> 0xFFEF5350.toInt()
            "white" -> if (isLightTheme()) 0xFF5A5A5A.toInt() else 0xFFEEEEEE.toInt()
            else -> 0xFFE91E63.toInt() // pink
        }
    }

    fun bgColor(): Int = when (themeMode) {
        "amoled" -> 0xFF000000.toInt()
        "sakura" -> 0xFF150F13.toInt()
        "midnight" -> 0xFF0B0E1A.toInt()
        "light" -> 0xFFFAF7F8.toInt()
        else -> 0xFF121212.toInt()
    }

    fun cardColor(): Int = when (themeMode) {
        "amoled" -> 0xFF0A0A0A.toInt()
        "sakura" -> 0xFF221820.toInt()
        "midnight" -> 0xFF151A2B.toInt()
        "light" -> 0xFFFFFFFF.toInt()
        else -> 0xFF1E1E1E.toInt()
    }

    fun cardBorderColor(): Int = when (themeMode) {
        "amoled" -> 0xFF181818.toInt()
        "sakura" -> 0xFF33242E.toInt()
        "midnight" -> 0xFF232A40.toInt()
        "light" -> 0xFFE4DCE0.toInt()
        else -> 0xFF2A2A2A.toInt()
    }

    fun surfaceColor(): Int = when (themeMode) {
        "amoled" -> 0xFF141414.toInt()
        "sakura" -> 0xFF2A1D26.toInt()
        "midnight" -> 0xFF1C2236.toInt()
        "light" -> 0xFFF2ECEF.toInt()
        else -> 0xFF222222.toInt()
    }

    fun isLightTheme(): Boolean = themeMode == "light"

    fun textColor(): Int = if (isLightTheme()) 0xFF1A1418.toInt() else 0xFFFFFFFF.toInt()

    fun textColorSecondary(): Int = if (isLightTheme()) 0xFF6E6266.toInt() else 0xFF9E9E9E.toInt()

    // ---- PIN lock, Decoy Vault, & Rate Limiting ----
    fun hasPin(): Boolean = secure.contains("pin_salt") && secure.contains("pin_hash")

    fun hasDecoyPin(): Boolean = secure.contains("decoy_pin_salt") && secure.contains("decoy_pin_hash")

    fun getRemainingLockoutSeconds(): Long {
        val lockoutUntil = secure.getLong("lockout_until", 0L)
        val now = System.currentTimeMillis()
        return if (lockoutUntil > now) (lockoutUntil - now + 999) / 1000 else 0L
    }

    fun formatLockoutRemaining(seconds: Long): String {
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return when {
            hours > 0 -> "${hours}h ${mins}m ${secs}s"
            mins > 0 -> "${mins}m ${secs}s"
            else -> "${secs}s"
        }
    }

    /** 3 failed attempts locks the app for 24 hours (86,400,000 ms). Also records intruder log. */
    fun recordFailedPinAttempt() {
        val attempts = secure.getInt("pin_fails", 0) + 1
        val edit = secure.edit().putInt("pin_fails", attempts)
        if (attempts >= 3) {
            edit.putLong("lockout_until", System.currentTimeMillis() + 86_400_000L) // 24 hours lockout
        }

        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
        val nowStr = sdf.format(java.util.Date())
        val existingLogs = secure.getString("intruder_log_list", "") ?: ""
        val logList = if (existingLogs.isEmpty()) mutableListOf() else existingLogs.split(";;").toMutableList()
        logList.add(0, nowStr)
        while (logList.size > 50) logList.removeAt(logList.lastIndex)

        edit.putString("intruder_log_list", logList.joinToString(";;"))
        edit.putInt("intruder_unnotified_count", secure.getInt("intruder_unnotified_count", 0) + 1)
        edit.putString("intruder_last_timestamp", nowStr)
        edit.apply()
    }

    fun recordSuccessfulPin() {
        secure.edit().putInt("pin_fails", 0).putLong("lockout_until", 0L).apply()
    }

    fun recordSuccessfulDecoyPin() {
        // Unlocking decoy vault does NOT reset real vault brute-force lockout
        secure.edit().putInt("decoy_pin_fails", 0).apply()
    }

    fun getIntruderLogs(): List<String> {
        val str = secure.getString("intruder_log_list", "") ?: ""
        return if (str.isEmpty()) emptyList() else str.split(";;")
    }

    fun clearIntruderLogs() {
        secure.edit()
            .remove("intruder_log_list")
            .remove("intruder_unnotified_count")
            .remove("intruder_last_timestamp")
            .apply()
    }

    fun consumeIntruderNotice(): String? {
        val unnotified = secure.getInt("intruder_unnotified_count", 0)
        if (unnotified <= 0) return null
        val lastTime = secure.getString("intruder_last_timestamp", "Recently") ?: "Recently"
        secure.edit().putInt("intruder_unnotified_count", 0).apply()
        return "$unnotified failed unlock attempt(s) detected since your last login.\nLast attempt: $lastTime"
    }

    fun setPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        secure.edit()
            .putString("pin_salt", salt.toHex())
            .putString("pin_hash", hash.toHex())
            .putInt("pin_hash_algo", PIN_ALGO_PBKDF2)
            .putInt("pin_fails", 0)
            .putLong("lockout_until", 0L)
            .apply()
    }

    fun checkPin(pin: String): Boolean {
        if (getRemainingLockoutSeconds() > 0) return false
        val salt = secure.getString("pin_salt", null)?.fromHex() ?: return false
        val expect = secure.getString("pin_hash", null)?.fromHex() ?: return false
        val algo = secure.getInt("pin_hash_algo", PIN_ALGO_LEGACY)
        val ok = if (algo == PIN_ALGO_PBKDF2) {
            constantTimeEquals(hash(pin, salt), expect)
        } else {
            constantTimeEquals(legacyHash(pin, salt), expect)
        }
        // Verified against a legacy hash: re-derive with the strong KDF so the
        // stored secret is upgraded in place, without making the user reset a PIN
        // they already know.
        if (ok && algo == PIN_ALGO_LEGACY) {
            secure.edit()
                .putString("pin_hash", hash(pin, salt).toHex())
                .putInt("pin_hash_algo", PIN_ALGO_PBKDF2)
                .apply()
        }
        return ok
    }

    fun setDecoyPin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = hash(pin, salt)
        secure.edit()
            .putString("decoy_pin_salt", salt.toHex())
            .putString("decoy_pin_hash", hash.toHex())
            .putInt("decoy_pin_hash_algo", PIN_ALGO_PBKDF2)
            .apply()
    }

    fun clearDecoyPin() {
        secure.edit()
            .remove("decoy_pin_salt")
            .remove("decoy_pin_hash")
            .apply()
    }

    fun checkDecoyPin(pin: String): Boolean {
        if (getRemainingLockoutSeconds() > 0) return false
        val salt = secure.getString("decoy_pin_salt", null)?.fromHex() ?: return false
        val expect = secure.getString("decoy_pin_hash", null)?.fromHex() ?: return false
        val algo = secure.getInt("decoy_pin_hash_algo", PIN_ALGO_LEGACY)
        val ok = if (algo == PIN_ALGO_PBKDF2) {
            constantTimeEquals(hash(pin, salt), expect)
        } else {
            constantTimeEquals(legacyHash(pin, salt), expect)
        }
        if (ok && algo == PIN_ALGO_LEGACY) {
            secure.edit()
                .putString("decoy_pin_hash", hash(pin, salt).toHex())
                .putInt("decoy_pin_hash_algo", PIN_ALGO_PBKDF2)
                .apply()
        }
        return ok
    }

    var biometricEnabled: Boolean
        get() = secure.getBoolean("bio_enabled", true)
        set(v) = secure.edit().putBoolean("bio_enabled", v).apply()

    fun verifyCurrentPinPrompt(
        ctx: Context,
        title: String = "Verify Vault PIN",
        message: String = "Enter your current PIN to authorize this change:",
        onSuccess: () -> Unit
    ) {
        if (!hasPin()) {
            onSuccess()
            return
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }
        val infoText = TextView(ctx).apply {
            text = message
            setTextColor(textColorSecondary())
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(infoText)

        val input = EditText(ctx).apply {
            hint = "Current PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(textColor())
            setHintTextColor(Color.GRAY)
            setBackgroundColor(surfaceColor())
            setPadding(24, 20, 24, 20)
        }
        layout.addView(input)

        AlertDialog.Builder(ctx)
            .setTitle(title)
            .setView(layout)
            .setPositiveButton("Verify") { _, _ ->
                val entered = input.text.toString().trim()
                if (checkPin(entered)) {
                    onSuccess()
                } else {
                    recordFailedPinAttempt()
                    Toast.makeText(ctx, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Change Real PIN dialog used from Settings. Requires current PIN if set. */
    fun setPinPrompt(ctx: Context, done: () -> Unit) {
        // hasPin() forces the keystore-backed store open, refreshing the degraded
        // marker before we block anything. First-time PIN setup (no PIN yet) is
        // still allowed so a degraded device is not bricked; changing an existing
        // PIN is blocked because the new hash would be stored in the clear.
        val pinExists = hasPin()
        if (pinExists && secureDegraded()) {
            showPinChangeBlocked(ctx)
            return
        }
        if (pinExists) {
            verifyCurrentPinPrompt(ctx, "Verify Current PIN", "Enter your current PIN before setting a new one:") {
                showNewPinDialog(ctx, done)
            }
        } else {
            showNewPinDialog(ctx, done)
        }
    }

    private fun showNewPinDialog(ctx: Context, done: () -> Unit) {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }
        val infoText = TextView(ctx).apply {
            text = "Enter 4+ digits to secure your vault:"
            setTextColor(textColorSecondary())
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(infoText)

        val input = EditText(ctx).apply {
            hint = "New PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(textColor())
            setHintTextColor(Color.GRAY)
            setBackgroundColor(surfaceColor())
            setPadding(24, 20, 24, 20)
        }
        layout.addView(input)

        AlertDialog.Builder(ctx)
            .setTitle("Set Vault PIN")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (v.length < 4) {
                    Toast.makeText(ctx, "PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                } else if (hasDecoyPin() && checkDecoyPin(v)) {
                    Toast.makeText(ctx, "PIN cannot be identical to Decoy PIN", Toast.LENGTH_LONG).show()
                } else {
                    setPin(v)
                    Toast.makeText(ctx, "PIN successfully updated", Toast.LENGTH_SHORT).show()
                    done()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Set or change Decoy PIN dialog used from Settings. Requires Vault PIN if set. */
    fun setDecoyPinPrompt(ctx: Context, done: () -> Unit) {
        // A decoy PIN is a secondary credential whose hash would be stored
        // unencrypted while degraded, so block both setup and change.
        hasPin()
        if (secureDegraded()) {
            showPinChangeBlocked(ctx)
            return
        }
        if (hasPin()) {
            verifyCurrentPinPrompt(ctx, "Authorize Decoy PIN", "Enter master vault PIN to configure decoy:") {
                showNewDecoyPinDialog(ctx, done)
            }
        } else {
            showNewDecoyPinDialog(ctx, done)
        }
    }

    /** Shown instead of PIN setup while the keystore is degraded and would store the new hash in the clear. */
    private fun showPinChangeBlocked(ctx: Context) {
        AlertDialog.Builder(ctx)
            .setTitle("PIN changes disabled")
            .setMessage(
                "The hardware keystore could not be opened, so your PIN hash is currently " +
                    "stored unencrypted.\n\n" +
                    "Changing your PIN now would only create another unencrypted hash, so PIN " +
                    "changes are blocked until the keystore is fixed.\n\n" +
                    "Re-enroll your screen lock, then reinstall the app, to restore encrypted storage."
            )
            .setPositiveButton("OK", null)
            .setCancelable(false)
            .show()
    }

    private fun showNewDecoyPinDialog(ctx: Context, done: () -> Unit) {
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
        }
        val infoText = TextView(ctx).apply {
            text = "Enter a separate PIN for Coercion Defense.\nUnlocking with this PIN reveals a clean decoy vault."
            setTextColor(textColorSecondary())
            textSize = 13f
            setPadding(0, 0, 0, 16)
        }
        layout.addView(infoText)

        val input = EditText(ctx).apply {
            hint = "Decoy PIN (4+ digits)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(textColor())
            setHintTextColor(Color.GRAY)
            setBackgroundColor(surfaceColor())
            setPadding(24, 20, 24, 20)
        }
        layout.addView(input)

        AlertDialog.Builder(ctx)
            .setTitle("Set Decoy PIN")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val v = input.text.toString().trim()
                if (v.length < 4) {
                    Toast.makeText(ctx, "Decoy PIN must be at least 4 digits", Toast.LENGTH_SHORT).show()
                } else if (hasPin() && checkPin(v)) {
                    Toast.makeText(ctx, "Decoy PIN cannot be identical to real PIN", Toast.LENGTH_LONG).show()
                } else {
                    setDecoyPin(v)
                    Toast.makeText(ctx, "Decoy PIN configured successfully", Toast.LENGTH_SHORT).show()
                    done()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * PBKDF2-HMAC-SHA256 over the PIN. A single SHA-256 of a 4-digit PIN is not
     * a meaningful barrier: there are only 10,000 candidates, so the whole space
     * can be checked in milliseconds once the hash is readable. That is exactly
     * the situation the keystore fallback below creates, so the default scheme
     * has to be slow enough to matter.
     */
    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val key = spec.generateSecret(PBEKeySpec(pin.toCharArray(), salt, PIN_KDF_ITERATIONS, 256))
        return key.encoded
    }

    /** Pre-KDF scheme, kept only to verify and upgrade PINs set by older builds. */
    private fun legacyHash(pin: String, salt: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        return md.digest(pin.toByteArray())
    }

    /** Comparison whose running time does not depend on where the first mismatch is. */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private companion object {
        const val PIN_KDF_ITERATIONS = 120_000
        const val PIN_ALGO_LEGACY = 0
        const val PIN_ALGO_PBKDF2 = 1
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun String.fromHex(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) out[i] = substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }
}
