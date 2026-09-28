package aurius.kura

import android.os.Handler
import android.os.Looper

object VaultLock {
    var isUnlocked: Boolean = false
    var isPickingMedia: Boolean = false
        set(value) {
            field = value
            pickingMediaStartTime = if (value) System.currentTimeMillis() else 0L
        }
    private var pickingMediaStartTime: Long = 0L
    var isDecoy: Boolean = false

    /**
     * Incremented on every lock and every unlock. Activities capture it when they
     * are created and refuse to resume once it changes: an instance built for one
     * vault session must never come back to the foreground under another, or a
     * decoy unlock would reveal the real vault through still-decrypted views.
     */
    var sessionId: Long = 0L
        private set

    private var lastBackgroundTime: Long = 0L
    private var activeActivities: Int = 0
    private val handler = Handler(Looper.getMainLooper())
    private val backgroundRunnable = Runnable {
        if (activeActivities <= 0 && !isPickingMedia) {
            lastBackgroundTime = System.currentTimeMillis()
        }
    }

    fun onActivityStarted() {
        handler.removeCallbacks(backgroundRunnable)
        activeActivities++
    }

    fun onActivityStopped() {
        activeActivities = (activeActivities - 1).coerceAtLeast(0)
        if (activeActivities == 0 && !isPickingMedia) {
            handler.removeCallbacks(backgroundRunnable)
            // 1.5s grace period ensures activity transitions never count as backgrounding
            handler.postDelayed(backgroundRunnable, 1500)
        }
    }

    fun shouldLock(prefs: Prefs): Boolean {
        if (!prefs.hasPin()) return false
        if (!isUnlocked) return true
        if (isPickingMedia) {
            // Expire if stranded for > 5 minutes
            if (System.currentTimeMillis() - pickingMediaStartTime < 5 * 60 * 1000L) {
                return false
            } else {
                isPickingMedia = false
            }
        }
        val timeoutSec = prefs.autoLockTimeout
        if (timeoutSec < 0) return false // never auto-lock while process is alive
        if (lastBackgroundTime == 0L) return false
        val elapsedSec = (System.currentTimeMillis() - lastBackgroundTime) / 1000
        return elapsedSec >= timeoutSec
    }

    fun unlock(decoy: Boolean = false) {
        isUnlocked = true
        isDecoy = decoy
        lastBackgroundTime = 0L
        sessionId++
        CryptoVault.evictAllThumbCaches()
        handler.removeCallbacks(backgroundRunnable)
    }

    fun lock() {
        isUnlocked = false
        isDecoy = false
        isPickingMedia = false
        lastBackgroundTime = 0L
        sessionId++
        CryptoVault.evictAllThumbCaches()
        handler.removeCallbacks(backgroundRunnable)
    }
}
