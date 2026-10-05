package aurius.kura

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

abstract class BaseVaultActivity : AppCompatActivity(), SensorEventListener {
    protected val prefs: Prefs by lazy { Prefs(this) }
    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null

    /** Vault session this instance belongs to; -1 until first resume. */
    private var boundSession: Long = -1L

    override fun onCreate(s: Bundle?) {
        CrashGuard.install(this)
        if (prefs.isLightTheme()) {
            setTheme(R.style.Theme_Kura_Light)
        }
        if (prefs.flagSecure) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sweepStaleTempFiles(this)
        AppIconManager.syncLauncher(this)
        super.onCreate(s)
        applyWindowFeatures()
    }

    override fun onStart() {
        super.onStart()
        VaultLock.onActivityStarted()
    }

    /**
     * Intent that opens the lock screen. Prefers the base `.LockActivity` component,
     * but falls back to the launcher alias when that component is disabled, so a
     * stale component state can never turn the lock screen into a crash.
     */
    protected fun lockActivityIntent(): Intent {
        val pkg = packageName
        val pm = packageManager
        val base = ComponentName(pkg, "$pkg.LockActivity")
        try {
            if (pm.getComponentEnabledSetting(base) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(base, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
        } catch (_: Exception) {}

        val activeAlias = AppIconManager.getActiveAlias(this)
        return try {
            Intent(this, LockActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        } catch (_: Exception) {
            Intent().setClassName(pkg, "$pkg.$activeAlias").apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
        }
    }

    override fun onResume() {
        super.onResume()

        VaultLock.isPickingMedia = false

        // This instance was built under a different vault session (a lock or a
        // decoy unlock happened since). Its db handle, vault path and any decrypted
        // views belong to a session that no longer exists, so tear it down instead
        // of resuming: otherwise entering the decoy PIN still shows the real vault.
        //
        // A lock that fired while we were backgrounded lands here, because
        // arming the lock on the way out is what VaultLock does now. Finishing
        // without showing anything would drop the user on an empty task, so hand
        // off to the lock screen instead.
        if (boundSession != -1L && boundSession != VaultLock.sessionId) {
            VaultLock.lock()
            wipePlayCacheAsync(this)
            showLockScreenAndFinish()
            return
        }
        if (boundSession == -1L) boundSession = VaultLock.sessionId

        applyWindowFeatures()
        if (prefs.flipToPanic && accelerometer != null) {
            sensorManager?.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI)
        }
        if (VaultLock.shouldLock(prefs)) {
            VaultLock.lock()
            wipePlayCacheAsync(this)
            showLockScreenAndFinish()
            return
        }
    }

    /**
     * Clears the task and puts the lock screen in front of it.
     *
     * The alias fallback exists because a stale PackageManager component state
     * can leave the real lock activity disabled; without it, refusing to lock is
     * the only way to avoid a crash, which is the worst possible outcome for a
     * vault.
     */
    private fun showLockScreenAndFinish() {
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

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(this)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Cached bitmaps are pure cache, so they are the first thing to go when
        // the system is short on memory.
        CryptoVault.trimThumbCaches(level)
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        super.onLowMemory()
        CryptoVault.evictAllThumbCaches()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !prefs.flipToPanic) return
        if (event.sensor.type == Sensor.TYPE_ACCELEROMETER) {
            val x = event.values[0]
            val y = event.values[1]
            val z = event.values[2]
            // Screen facing down (z is negative gravity ~ -9.8 m/s^2)
            if (z < -7.0f && Math.abs(x) < 5.5f && Math.abs(y) < 5.5f) {
                panicLock()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    fun panicLock() {
        sensorManager?.unregisterListener(this)
        VaultLock.lock()
            wipePlayCacheAsync(this)
        val home = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(home)
        finishAffinity()
    }

    protected fun applyWindowFeatures() {
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

            // 2. Edge-to-edge system bars. On Android 15+ (API 35) edge-to-edge is
            // enforced for every app and cannot be opted out, so it is effectively
            // always on there; on older devices it follows the user preference.
            // The status bar can still be hidden afterwards (hideStatusBar), which
            // collapses the reported top inset to zero.
            val edgeToEdge = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM || prefs.edgeToEdge
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

            // 3. Notification / Status bar hide/show safely on attached decorView
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

    override fun onStop() {
        super.onStop()
        VaultLock.onActivityStopped(prefs)
    }

    companion object {
        /**
         * Single background thread for best-effort cache hygiene (shredding play
         * caches and stale temp files).
         *
         * This work is unbounded I/O — a decrypted play cache can be gigabytes
         * and has no size cap — so it must never touch the main thread. A single
         * thread also keeps it serialised, which matters because the same path
         * can be scheduled by several activities at once.
         */
        private val maintenanceExecutor: java.util.concurrent.ExecutorService by lazy {
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "kura-maintenance").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
            }
        }

        /** Queues [block] on the maintenance thread. Returns immediately. */
        fun runMaintenance(block: () -> Unit) {
            try {
                maintenanceExecutor.execute {
                    try { block() } catch (_: Throwable) {}
                }
            } catch (_: Throwable) {}
        }

        /**
         * Queues cache hygiene on the maintenance thread.
         *
         * Was called straight from `onCreate`, which meant opening the app after
         * watching one large video meant zero-filling that whole file on the UI
         * thread before a single view was created.
         */
        fun sweepStaleTempFiles(ctx: Context) {
            runMaintenance {
                try {
                    val app = ctx.applicationContext
                    CryptoVault(app).wipePlayCache()
                    app.cacheDir.listFiles()?.forEach { f ->
                        if (f.name == "play" || f.name == "play_decoy" || f.name.startsWith("play_") || f.name.startsWith("probe_") || f.name.startsWith("zip_restore_tmp") || f.name.startsWith("restore_") || f.name.endsWith(".tmp")) {
                            try {
                                if (f.isDirectory) {
                                    f.listFiles()?.forEach { CryptoVault.secureShred(it) }
                                    f.delete()
                                } else {
                                    CryptoVault.secureShred(f)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        /** Fire-and-forget play-cache wipe; safe to call from the main thread. */
        fun wipePlayCacheAsync(ctx: Context) {
            runMaintenance {
                try { CryptoVault(ctx.applicationContext).wipePlayCache() } catch (_: Exception) {}
            }
        }
    }
}
