package aurius.kura

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** PIN + biometric gate. Clean two-button launcher by default; on-demand sleek number pad. */
class LockActivity : AppCompatActivity() {
    private val prefs: Prefs by lazy { Prefs(this) }
    private lateinit var root: LinearLayout
    private lateinit var sc: ScrollView
    private lateinit var title: TextView
    private lateinit var msg: TextView

    // Initial clean mode
    private lateinit var actionsContainer: LinearLayout
    private lateinit var pinShowBtn: Button
    private lateinit var bioBtn: Button

    // On-demand PIN mode
    private lateinit var pinSection: LinearLayout
    private lateinit var pin: EditText
    private lateinit var keypadContainer: LinearLayout
    private lateinit var keypadSubmitBtn: Button
    private lateinit var keypadCancelBtn: Button
    private val keypadButtons = mutableListOf<Button>()

    private var firstPin: String? = null
    private var countdownTimer: CountDownTimer? = null

    override fun onCreate(s: Bundle?) {
        if (prefs.isLightTheme()) {
            setTheme(R.style.Theme_Kura_Light)
        }
        super.onCreate(s)
        CrashGuard.install(this)
        AppIconManager.syncLauncher(this)
        if (prefs.flagSecure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        if (VaultLock.isUnlocked) {
            open(VaultLock.isDecoy)
            return
        }
        BaseVaultActivity.wipePlayCacheAsync(this)
        build()
        applyWindowFeatures()
        refresh()
        checkLockout()
        if (prefs.hasPin() && prefs.biometricEnabled && isBioAvailable() && prefs.getRemainingLockoutSeconds() == 0L) {
            root.post { maybeBiometric(auto = true) }
        }
    }

    override fun onResume() {
        super.onResume()
        applyWindowFeatures()
        refreshKeypad()
        checkLockout()
    }

    private fun applyWindowFeatures() {
        try {
            // 1. Layout into display cutout (notch / punch-hole) so no black letterbox bar is left behind!
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val lp = window.attributes
                lp.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                window.attributes = lp
            }

            // 2. Immersive edge-to-edge system bars (active if immersiveMode OR hideStatusBar is on)
            val edgeToEdge = prefs.edgeToEdge
            WindowCompat.setDecorFitsSystemWindows(window, !edgeToEdge)

            window.statusBarColor = Color.TRANSPARENT
            if (edgeToEdge) {
                window.navigationBarColor = Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isNavigationBarContrastEnforced = false
                    window.isStatusBarContrastEnforced = false
                }
            } else {
                window.navigationBarColor = prefs.bgColor()
            }

            // 3. Directly apply/clear fullscreen flag
            if (prefs.hideStatusBar) {
                @Suppress("DEPRECATION")
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            } else {
                @Suppress("DEPRECATION")
                window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            }

            // 4. Notification / Status bar hide/show safely on attached decorView
            window.decorView.post {
                try {
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = prefs.isLightTheme()
                    controller.isAppearanceLightNavigationBars = prefs.isLightTheme()
                    if (prefs.hideStatusBar) {
                        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        controller.hide(WindowInsetsCompat.Type.statusBars())
                    } else {
                        controller.show(WindowInsetsCompat.Type.statusBars())
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownTimer?.cancel()
    }

    private fun isBioAvailable(): Boolean {
        return try {
            val mgr = BiometricManager.from(this)
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            mgr.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    private fun setKeypadEnabled(enabled: Boolean) {
        keypadButtons.forEach { it.isEnabled = enabled }
        keypadSubmitBtn.isEnabled = enabled
    }

    private fun checkLockout() {
        val remaining = prefs.getRemainingLockoutSeconds()
        if (remaining > 0) {
            pinShowBtn.isEnabled = false
            bioBtn.isEnabled = false
            setKeypadEnabled(false)
            msg.text = "Vault Locked (3 failed attempts)\nTry again in ${prefs.formatLockoutRemaining(remaining)}"
            countdownTimer?.cancel()
            countdownTimer = object : CountDownTimer(remaining * 1000, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    val sec = (millisUntilFinished + 999) / 1000
                    msg.text = "Vault Locked (3 failed attempts)\nTry again in ${prefs.formatLockoutRemaining(sec)}"
                }
                override fun onFinish() {
                    setKeypadEnabled(true)
                    pinShowBtn.isEnabled = true
                    bioBtn.isEnabled = true
                    refresh()
                    refreshKeypad()
                }
            }.start()
        } else {
            setKeypadEnabled(true)
            pinShowBtn.isEnabled = true
            bioBtn.isEnabled = true
        }
    }

    private fun refresh() {
        val bioOk = isBioAvailable()
        if (!prefs.hasPin()) {
            title.text = "Set Vault PIN"
            msg.text = "Choose a 4+ digit PIN to encrypt your vault."
            actionsContainer.visibility = View.GONE
            pinSection.visibility = View.VISIBLE
            keypadCancelBtn.visibility = View.GONE
            keypadSubmitBtn.text = "Next"
        } else {
            title.text = "kura"
            msg.text = if (bioOk && prefs.biometricEnabled) "Unlock vault with PIN or Biometrics." else "Unlock vault with PIN."
            bioBtn.visibility = if (bioOk) View.VISIBLE else View.GONE
            keypadCancelBtn.visibility = View.VISIBLE
            keypadSubmitBtn.text = "Unlock →"
            // Show initial clean mode by default
            showKeypad(false)
        }
    }

    private fun showKeypad(show: Boolean) {
        if (!prefs.hasPin()) {
            actionsContainer.visibility = View.GONE
            pinSection.visibility = View.VISIBLE
            return
        }

        if (show) {
            actionsContainer.visibility = View.GONE
            pinSection.visibility = View.VISIBLE
            msg.text = "Enter your PIN to unlock:"
            refreshKeypad()
        } else {
            pin.text.clear()
            pinSection.visibility = View.GONE
            actionsContainer.visibility = View.VISIBLE
            val bioOk = isBioAvailable()
            msg.text = if (bioOk && prefs.biometricEnabled) "Unlock vault with PIN or Biometrics." else "Unlock vault with PIN."
        }
    }

    private fun refreshKeypad() {
        if (!::keypadContainer.isInitialized) return
        keypadContainer.removeAllViews()
        keypadButtons.clear()

        val digits = if (prefs.scramblePinKeypad) {
            (0..9).shuffled()
        } else {
            listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 0)
        }

        val rows = listOf(
            listOf(digits[0].toString(), digits[1].toString(), digits[2].toString()),
            listOf(digits[3].toString(), digits[4].toString(), digits[5].toString()),
            listOf(digits[6].toString(), digits[7].toString(), digits[8].toString()),
            listOf("C", digits[9].toString(), "⌫")
        )

        val density = resources.displayMetrics.density
        val btnHeight = (54 * density).toInt()

        for (rowItems in rows) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for (item in rowItems) {
                val b = Button(this).apply {
                    text = item
                    textSize = 20f
                    setTextColor(prefs.textColor())
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    background = ThemeUtils.surfaceGlass(prefs, 18f, 1)
                    isHapticFeedbackEnabled = true
                    setOnClickListener {
                        it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        when (item) {
                            "C" -> pin.text.clear()
                            "⌫" -> {
                                val s = pin.text.toString()
                                if (s.isNotEmpty()) {
                                    pin.setText(s.substring(0, s.length - 1))
                                    pin.setSelection(pin.text.length)
                                }
                            }
                            else -> {
                                pin.append(item)
                            }
                        }
                    }
                }
                keypadButtons.add(b)
                val lp = LinearLayout.LayoutParams(0, btnHeight, 1f).apply {
                    setMargins((5 * density).toInt(), (5 * density).toInt(), (5 * density).toInt(), (5 * density).toInt())
                }
                row.addView(b, lp)
            }
            keypadContainer.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }

        val remaining = prefs.getRemainingLockoutSeconds()
        if (remaining > 0) {
            setKeypadEnabled(false)
        }
    }

    private fun build() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(40, 24, 40, 32)
        }

        title = TextView(this).apply {
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(prefs.textColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
            text = "kura"
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Offline • AES-256-GCM Encrypted Vault"
            gravity = Gravity.CENTER
            setTextColor(prefs.textColorSecondary())
            textSize = 12f
            setPadding(0, 4, 0, 0)
        }
        root.addView(subtitle)

        msg = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(prefs.textColorSecondary())
            textSize = 13f
            setPadding(0, 16, 0, 20)
        }
        root.addView(msg)

        // ---- 1. Initial Clean Two-Button Mode ----
        actionsContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 12)
        }

        pinShowBtn = Button(this).apply {
            text = "Unlock with PIN"
            background = ThemeUtils.buttonBackground(prefs, selected = true)
            setTextColor(ThemeUtils.buttonTextColor(prefs, selected = true))
            textSize = 15f
            setOnClickListener { showKeypad(true) }
        }
        actionsContainer.addView(pinShowBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 14 })

        bioBtn = Button(this).apply {
            text = "Unlock with Biometrics"
            background = ThemeUtils.buttonBackground(prefs, selected = false)
            setTextColor(prefs.textColor())
            textSize = 15f
            setOnClickListener { maybeBiometric(auto = false) }
        }
        actionsContainer.addView(bioBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(actionsContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // ---- 2. On-Demand Number Pad Section ----
        pinSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        pin = EditText(this).apply {
            hint = "••••"
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setHintTextColor(Color.GRAY)
            setTextColor(prefs.textColor())
            letterSpacing = 0.35f
            background = ThemeUtils.surfaceGlass(prefs, 18f, 1)
            setPadding(32, 20, 32, 20)
            textSize = 22f
            // Avoid soft-keyboard pop-up since on-screen keypad is present
            showSoftInputOnFocus = false
        }
        pinSection.addView(pin, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 12 })

        keypadContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
        }
        pinSection.addView(keypadContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 0)
        }

        keypadCancelBtn = Button(this).apply {
            text = "Cancel"
            background = ThemeUtils.buttonBackground(prefs, selected = false)
            setTextColor(prefs.textColorSecondary())
            textSize = 14f
            setOnClickListener { showKeypad(false) }
        }
        actionRow.addView(keypadCancelBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = 8 })

        keypadSubmitBtn = Button(this).apply {
            text = "Unlock →"
            background = ThemeUtils.buttonBackground(prefs, selected = true)
            setTextColor(ThemeUtils.buttonTextColor(prefs, selected = true))
            textSize = 14f
            setOnClickListener { submit() }
        }
        actionRow.addView(keypadSubmitBtn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = 8 })

        pinSection.addView(actionRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(pinSection, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val hint = TextView(this).apply {
            text = "Offline • No Internet Permission • Local Security"
            gravity = Gravity.CENTER
            setTextColor(prefs.textColorSecondary())
            textSize = 11f
            setPadding(0, 24, 0, 0)
        }
        root.addView(hint)

        sc = ScrollView(this).apply {
            isFillViewport = true
            background = ThemeUtils.backdrop(prefs)
            addView(root)
        }

        ViewCompat.setOnApplyWindowInsetsListener(sc) { _, insets ->
            val sysBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val topInset = if (prefs.edgeToEdge && !prefs.hideStatusBar) sysBars.top else 0
            val bottomInset = if (prefs.edgeToEdge) sysBars.bottom else 0
            root.setPadding(40, topInset + 24, 40, 24 + bottomInset)
            insets
        }

        setContentView(sc)
    }

    private fun submit() {
        if (prefs.getRemainingLockoutSeconds() > 0) {
            checkLockout()
            return
        }

        val v = pin.text.toString().trim()
        try {
            if (!prefs.hasPin()) {
                if (v.length < 4) { toast("PIN must be 4+ digits"); return }
                if (firstPin == null) {
                    firstPin = v
                    pin.text.clear()
                    msg.text = "Repeat PIN to confirm"
                    keypadSubmitBtn.text = "Confirm PIN"
                    return
                }
                if (v != firstPin) {
                    firstPin = null
                    pin.text.clear()
                    msg.text = "PINs didn't match. Try again."
                    keypadSubmitBtn.text = "Next"
                    return
                }
                prefs.setPin(v)
                if (isBioAvailable()) {
                    prefs.biometricEnabled = true
                }
                toast("PIN set successfully")
                open(decoy = false)
            } else {
                if (prefs.checkPin(v)) {
                    prefs.recordSuccessfulPin()
                    open(decoy = false)
                } else if (prefs.hasDecoyPin() && prefs.checkDecoyPin(v)) {
                    // Decoy PIN entered: open isolated clean decoy vault seamlessly!
                    prefs.recordSuccessfulDecoyPin()
                    open(decoy = true)
                } else {
                    pin.text.clear()
                    prefs.recordFailedPinAttempt()
                    val rem = prefs.getRemainingLockoutSeconds()
                    if (rem > 0) {
                        checkLockout()
                    } else {
                        refreshKeypad()
                        toast("Wrong PIN")
                    }
                }
            }
        } catch (e: Exception) {
            toast("Lock error: ${e.message}")
        }
    }

    /**
     * Every unlock tears down any vault activity still alive in the task first.
     * Those instances were created while the *other* vault session was active, so
     * letting them survive would surface the wrong vault (a decoy unlock revealing
     * the real one). CLEAR_TASK guarantees the incoming MainActivity is built fresh
     * against the session we just opened, and the play cache is wiped so no
     * decrypted frame survives into the new session.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (VaultLock.isUnlocked) {
            open(VaultLock.isDecoy)
        }
    }

    private fun open(decoy: Boolean) {
        if (!decoy && prefs.consumeSecureFallbackWarning()) {
            KeystoreFallbackDialog.show(this, prefs) { launchVault(decoy) }
            return
        }
        launchVault(decoy)
    }

    private fun launchVault(decoy: Boolean) {
        VaultLock.unlock(decoy)
        BaseVaultActivity.wipePlayCacheAsync(this)
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        intent?.let { incoming ->
            if (incoming.action == Intent.ACTION_SEND || incoming.action == Intent.ACTION_SEND_MULTIPLE) {
                launchIntent.action = incoming.action
                launchIntent.type = incoming.type
                incoming.extras?.let { launchIntent.putExtras(it) }
            }
        }
        startActivity(launchIntent)
        finishAffinity()
    }

    private fun maybeBiometric(auto: Boolean) {
        try {
            if (!prefs.hasPin()) {
                if (!auto) toast("Set a PIN first, then use biometric")
                return
            }
            if (prefs.getRemainingLockoutSeconds() > 0) {
                checkLockout()
                return
            }

            val mgr = BiometricManager.from(this)
            val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.BIOMETRIC_WEAK
            val canAuth = mgr.canAuthenticate(authenticators)
            if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
                if (!auto) {
                    when (canAuth) {
                        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
                            toast("No biometrics enrolled in device Settings")
                        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
                            toast("No biometric hardware detected on this device")
                        else ->
                            toast("Biometric unlock not available on this device")
                    }
                }
                return
            }

            val exec = ContextCompat.getMainExecutor(this)
            val prompt = BiometricPrompt(this, exec, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: BiometricPrompt.AuthenticationResult) {
                    prefs.recordSuccessfulPin()
                    open(decoy = false)
                }
                override fun onAuthenticationFailed() {}
                override fun onAuthenticationError(code: Int, err: CharSequence) {
                    if (!auto && code != BiometricPrompt.ERROR_USER_CANCELED && code != BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                        toast(err.toString())
                    }
                }
            })

            prompt.authenticate(
                BiometricPrompt.PromptInfo.Builder()
                    .setTitle("Unlock Vault")
                    .setSubtitle("Confirm biometrics to access vault")
                    .setNegativeButtonText("Use PIN")
                    .setAllowedAuthenticators(authenticators)
                    .build()
            )
        } catch (e: Exception) {
            if (!auto) toast("Biometric error: ${e.message}")
        }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()
}
