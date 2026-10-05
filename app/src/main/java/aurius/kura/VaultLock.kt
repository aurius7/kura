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
    private var backgroundPrefs: Prefs? = null
    private var activeActivities: Int = 0
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Fires once the app has genuinely been backgrounded, and arms the lock
     * itself instead of waiting for the next resume to ask.
     *
     * Previously the timeout was only ever evaluated in `shouldLock`, which
     * `BaseVaultActivity.onResume` calls. That meant a backgrounded vault stayed
     * unlocked for as long as the user stayed away, and if the return trip took
     * a path that skipped the check the vault simply came back unlocked. Locking
     * on the way out means the unlocked state cannot outlive the trip, and the
     * resume check below is a second net rather than the only one.
     */
    private val backgroundRunnable = Runnable {
        if (activeActivities <= 0 && !isPickingMedia) {
            lastBackgroundTime = System.currentTimeMillis()
            armBackgroundLock(backgroundPrefs)
        }
    }

    /**
     * Schedules the lock for `backgrounded + autoLockTimeout`. A timeout of 0
     * ("immediate") locks as soon as this runs; a negative timeout ("never")
     * arms nothing.
     */
    private fun armBackgroundLock(prefs: Prefs?) {
        handler.removeCallbacks(lockRunnable)
        val p = prefs ?: return
        if (!p.hasPin()) return
        val timeoutSec = p.autoLockTimeout
        if (timeoutSec < 0) return
        android.util.Log.i("KuraLock", "Backgrounded: arming auto-lock in ${timeoutSec}s")
        handler.postDelayed(lockRunnable, timeoutSec * 1000L)
    }

    /**
     * How long to wait before treating "every activity stopped" as a real
     * backgrounding.
     *
     * This grace period exists only to stop an in-app transition (activity A
     * stopping as activity B starts) from counting as backgrounding. It has to
     * be short: the stamp is the only record that the app was backgrounded, and
     * if the user returns first, [onActivityStarted] cancels the pending runnable
     * and the backgrounding is forgotten entirely -- `shouldLock` then returns
     * false no matter how short the timeout is.
     *
     * At 1500ms that made "immediate" lock unreliable: pressing Home and
     * reopening the app within a second and a half always resumed straight into
     * the unlocked vault. 300ms is still far longer than an in-app transition
     * takes to dispatch (same main-looper frame, ~16ms) while keeping the
     * window in which a quick return is missed down from 1.5s to 0.3s.
     *
     * onActivityStarted() clears the stamp set by onActivityStopped(), so the
     * 300ms window is what separates a forgiven in-app transition (recreate,
     * quick Home-and-return) from a real backgrounding that arms auto-lock.
     */
    private const val BACKGROUND_GRACE_MS = 300L

    private val lockRunnable = Runnable {
        android.util.Log.i("KuraLock", "Auto-lock timeout elapsed while backgrounded; locking")
        lock()
    }

    fun onActivityStarted() {
        handler.removeCallbacks(backgroundRunnable)
        handler.removeCallbacks(lockRunnable)
        // An activity coming back to the foreground means the app is no longer
        // backgrounded, so forget the background stamp entirely. Without this,
        // an in-app transition (e.g. Settings calling recreate() after a toggle)
        // leaves lastBackgroundTime stamped from the stopping activity, and for
        // an "immediate" auto-lock timeout that makes shouldLock() true the
        // instant the recreated activity resumes - kicking the user back to the
        // lock screen after every setting change. Clearing here restores the
        // forgiven behaviour documented on backgroundRunnable: if the app returns
        // before the grace runnable arms the lock, shouldLock returns false no
        // matter how short the timeout is. A genuine background still arms the
        // lock via the grace runnable, so "immediate" keeps locking on real
        // Home-and-stay.
        lastBackgroundTime = 0L
        backgroundPrefs = null
        activeActivities++
    }

    fun onActivityStopped(prefs: Prefs) {
        activeActivities = (activeActivities - 1).coerceAtLeast(0)
        if (activeActivities == 0 && !isPickingMedia) {
            handler.removeCallbacks(backgroundRunnable)
            handler.removeCallbacks(lockRunnable)
            // Stamp synchronously rather than inside the grace callback. If the
            // app is later killed while backgrounded there is no resume path left
            // to notice the backgrounding, and the recents snapshot of an
            // unlocked vault is the thing we least want to leave behind.
            lastBackgroundTime = System.currentTimeMillis()
            backgroundPrefs = prefs
            handler.postDelayed(backgroundRunnable, BACKGROUND_GRACE_MS)
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
        backgroundPrefs = null
        sessionId++
        CryptoVault.evictAllThumbCaches()
        handler.removeCallbacks(backgroundRunnable)
        handler.removeCallbacks(lockRunnable)
    }

    fun lock() {
        isUnlocked = false
        isDecoy = false
        isPickingMedia = false
        lastBackgroundTime = 0L
        backgroundPrefs = null
        sessionId++
        CryptoVault.evictAllThumbCaches()
        handler.removeCallbacks(backgroundRunnable)
        handler.removeCallbacks(lockRunnable)
    }
}
