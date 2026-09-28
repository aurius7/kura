package aurius.kura

import android.content.Context
import android.content.Intent
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Shows the real crash reason on-screen (separate :crash process survives
 * the main process dying). Without this, a startup crash just looks like
 * "the app closes" with no explanation.
 */
object CrashGuard {
    fun install(ctx: Context) {
        val cur = Thread.getDefaultUncaughtExceptionHandler()
        if (cur is Guarded) return
        Thread.setDefaultUncaughtExceptionHandler(Guarded(ctx.applicationContext, cur))
    }

    private class Guarded(
        val app: Context,
        val prev: Thread.UncaughtExceptionHandler?
    ) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val rawTrace = "${e.javaClass.name}: ${e.message}\n" + sw.toString().take(6000)
                val trace = sanitizeTrace(app, rawTrace)
                try {
                    app.openFileOutput("crash.txt", Context.MODE_PRIVATE).use {
                        it.write(trace.toByteArray())
                    }
                } catch (_: Exception) {}
                app.startActivity(Intent(app, CrashActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    putExtra("trace", trace)
                })
                Thread.sleep(800) // let the :crash process start before we die
            } catch (_: Exception) {}
            if (prev != null) prev.uncaughtException(t, e)
            else Process.killProcess(Process.myPid())
        }

        private fun sanitizeTrace(ctx: Context, raw: String): String {
            var s = raw
            try {
                val filesPath = ctx.filesDir?.absolutePath
                if (!filesPath.isNullOrEmpty()) s = s.replace(filesPath, "[FILES]")
                val cachePath = ctx.cacheDir?.absolutePath
                if (!cachePath.isNullOrEmpty()) s = s.replace(cachePath, "[CACHE]")
            } catch (_: Exception) {}
            return s.replace(Regex("/data/(user/\\d+|data)/[a-zA-Z0-9._-]+"), "[APP_DATA]")
        }
    }

    fun lastCrash(ctx: Context): String? = try {
        ctx.openFileInput("crash.txt").bufferedReader().readText()
    } catch (_: Exception) { null }

    fun clearCrash(ctx: Context) { try { ctx.deleteFile("crash.txt") } catch (_: Exception) {} }
}
