package aurius.kura

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Global progress dispatcher for long-running vault operations:
 * batch import, restore, export, and broken media cleaner.
 */
object VaultProgress {

    interface Listener {
        fun onProgressUpdate(title: String, current: Int, total: Int, canCancel: Boolean)
        fun onProgressDismiss()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile var isRunning: Boolean = false
        private set
    @Volatile var isCancelled: Boolean = false
        private set
    @Volatile var currentTitle: String = ""
        private set
    @Volatile var currentCount: Int = 0
        private set
    @Volatile var totalCount: Int = -1
        private set
    @Volatile var cancellable: Boolean = true
        private set
    private var cancelCallback: (() -> Unit)? = null

    fun addListener(l: Listener) {
        listeners.add(l)
        if (isRunning) {
            mainHandler.post {
                l.onProgressUpdate(currentTitle, currentCount, totalCount, cancellable)
            }
        }
    }

    fun removeListener(l: Listener) {
        listeners.remove(l)
    }

    fun start(title: String, total: Int = -1, canCancel: Boolean = true, onCancel: (() -> Unit)? = null) {
        isRunning = true
        isCancelled = false
        currentTitle = title
        currentCount = 0
        totalCount = total
        cancellable = canCancel
        cancelCallback = onCancel
        notifyUpdate()
    }

    fun update(current: Int, total: Int = totalCount, title: String = currentTitle) {
        currentCount = current
        totalCount = total
        currentTitle = title
        notifyUpdate()
    }

    fun cancel() {
        if (!isRunning || isCancelled) return
        isCancelled = true
        currentTitle = "Cancelling..."
        try {
            cancelCallback?.invoke()
        } catch (_: Exception) {}
        notifyUpdate()
    }

    fun finish() {
        isRunning = false
        isCancelled = false
        cancelCallback = null
        mainHandler.post {
            for (l in listeners) {
                l.onProgressDismiss()
            }
        }
    }

    private fun notifyUpdate() {
        val t = currentTitle
        val c = currentCount
        val tot = totalCount
        val can = cancellable && !isCancelled
        mainHandler.post {
            for (l in listeners) {
                l.onProgressUpdate(t, c, tot, can)
            }
        }
    }
}
