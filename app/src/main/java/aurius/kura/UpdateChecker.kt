package aurius.kura

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executor

/**
 * What a release advertises about itself.
 *
 * Kura publishes this as `update.json` beside the APK rather than asking GitHub
 * for the latest release: one small file, no API rate limit for a user on a
 * metered connection, and a real `versionCode` to compare against. The GitHub
 * releases API does not carry a versionCode, so anything built on it has to
 * parse a version string and hope the two stay in step.
 */
data class ReleaseInfo(
    val versionName: String,
    val versionCode: Int,
    val flavors: Map<String, ReleaseAsset>,
    val notesUrl: String = ""
)

/**
 * One downloadable build.
 *
 * A release publishes an entry per flavor and a build only ever accepts its own.
 * Handing the networked APK to someone who installed the offline build would
 * silently add a network permission to their app, which is the one thing that
 * build exists to avoid.
 */
data class ReleaseAsset(
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long
)

/**
 * Update checking, verification, and handing an APK to the system installer.
 *
 * Two rules shape everything here. Kura never installs anything it has not
 * checked: the SHA-256 has to match the value published next to the APK, and the
 * APK's signing certificate has to be the same one the running app is signed
 * with, which Android would enforce anyway but which is checked first so a
 * mismatch is a clear message rather than a failed install. And Kura cannot
 * install silently at all -- Android requires a user to confirm every install --
 * so the most this can do is verify the file and open the system prompt.
 */
object UpdateChecker {

    private const val TAG = "KuraUpdate"
    /**
     * GitHub resolves `releases/latest/download/<asset>` to the newest release's
     * copy of that asset, so this URL never has to be edited at release time and
     * cannot drift out of step with the tag.
     */
    const val UPDATE_JSON_URL =
        "https://github.com/aurius7/kura/releases/latest/download/update.json"
    const val STAGING_DIR = "updates"

    /** Pure so it can be tested without a device. */
    fun parseRelease(json: String): ReleaseInfo? = try {
        val o = org.json.JSONObject(json)
        val code = o.getInt("versionCode")
        val flavorsObj = o.getJSONObject("flavors")
        val flavors = mutableMapOf<String, ReleaseAsset>()
        for (key in listOf(FLAVOR_OFFLINE, FLAVOR_ONLINE)) {
            if (!flavorsObj.has(key)) continue
            val f = flavorsObj.getJSONObject(key)
            val apk = f.optString("apkUrl", "")
            val sha = f.optString("sha256", "").lowercase()
            if (apk.isNotBlank() && sha.length == 64) {
                flavors[key] = ReleaseAsset(apk, sha, f.optLong("sizeBytes", 0L))
            }
        }
        if (code <= 0 || flavors.isEmpty()) {
            Log.w(TAG, "update.json is missing required fields")
            null
        } else {
            ReleaseInfo(
                versionName = o.optString("versionName", ""),
                versionCode = code,
                flavors = flavors,
                notesUrl = o.optString("notesUrl", "")
            )
        }
    } catch (_: Exception) {
        Log.w(TAG, "update.json could not be read")
        null
    }

    const val FLAVOR_OFFLINE = "offline"
    const val FLAVOR_ONLINE = "online"

    /**
     * A release is offered only when it is genuinely newer. Equal is not newer:
     * re-offering the running build would nag forever.
     */
    fun isNewer(installedCode: Int, releaseCode: Int): Boolean = releaseCode > installedCode

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /**
     * Compares two certificate fingerprints, ignoring case and separators so a
     * value pasted from a terminal matches one derived from the APK.
     */
    fun sameCert(hexA: String, hexB: String): Boolean =
        hexA.lowercase().replace(":", "").replace(" ", "") ==
            hexB.lowercase().replace(":", "").replace(" ", "")

    /** SHA-256 of a staged APK, streamed so a large file is not held in memory. */
    fun sha256OfFile(f: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    fun stagingDir(ctx: Context): File = File(ctx.cacheDir, STAGING_DIR).apply { mkdirs() }

    fun ownCertHex(ctx: Context): String? {
        val pm = ctx.packageManager
        return try {
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNATURES).signatures
            }
            sigs?.firstOrNull()?.let { sha256Hex(it.toByteArray()) }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The signing certificate of an APK that is not installed, read straight from
     * the archive. Used to check a downloaded update before offering it.
     */
    fun certHexOfApk(ctx: Context, file: File): String? {
        val pm = ctx.packageManager
        return try {
            val info = pm.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES)
                ?: return null
            val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                info.signatures
            }
            sigs?.firstOrNull()?.let { sha256Hex(it.toByteArray()) }
        } catch (_: Exception) {
            null
        }
    }

    fun versionCodeOfApk(ctx: Context, file: File): Int? = try {
        ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.versionCode
    } catch (_: Exception) {
        null
    }

    /**
     * Fetches [url] into the staging directory. Used for the check itself and
     * for the download; both go through here so there is one place that talks to
     * the network at all.
     *
     * Only ever called when [BuildConfig.NETWORK_UPDATES] is true, which is only
     * true for the flavor that declares INTERNET.
     */
    fun download(url: String, dest: File, timeoutMs: Int = 20_000): Boolean {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json, application/octet-stream")
            }
            if (conn.responseCode !in 200..299) {
                Log.w(TAG, "GET $url returned ${conn.responseCode}")
                return false
            }
            val tmp = File(dest.absolutePath + ".part")
            conn.inputStream.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out, 64 * 1024) } }
            // Rename only once the whole body landed, so an interrupted download
            // can never be mistaken for a complete file.
            if (dest.exists()) dest.delete()
            tmp.renameTo(dest)
        } catch (_: Exception) {
            Log.w(TAG, "GET $url failed")
            false
        } finally {
            conn?.disconnect()
        }
    }

    fun readSmall(url: String, timeoutMs: Int = 10_000): String? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Copies a user-picked content:// URI into the staging directory. */
    fun copyToStaging(ctx: Context, uri: Uri): File? = try {
        val dest = File(stagingDir(ctx), "picked.apk")
        ctx.contentResolver.openInputStream(uri)?.use { ins: InputStream ->
            dest.outputStream().use { out -> ins.copyTo(out, 64 * 1024) }
        }
        dest.takeIf { it.length() > 0 }
    } catch (_: Exception) {
        null
    }

    fun clearStaging(ctx: Context) {
        stagingDir(ctx).listFiles()?.forEach { it.delete() }
    }

    /**
     * Result of checking a candidate APK, so the caller can explain a refusal
     * instead of only offering "install failed".
     */
    sealed class Verdict {
        data class Ok(val file: File, val versionName: String) : Verdict()
        data object NotNewer : Verdict()
        data object BadHash : Verdict()
        data object WrongSigner : Verdict()
        data object Unreadable : Verdict()
    }

    /**
     * The three checks that stand between a file and an install prompt:
     * newer than what is running, the published checksum, and the same signer.
     * Order matters -- the cheap comparisons run before the file digest.
     */
    fun verify(
        ctx: Context,
        file: File,
        installedCode: Int,
        expectedSha: String?,
        expectedCert: String?
    ): Verdict {
        if (!file.exists() || file.length() == 0L) return Verdict.Unreadable
        val code = versionCodeOfApk(ctx, file) ?: return Verdict.Unreadable
        if (!isNewer(installedCode, code)) return Verdict.NotNewer
        if (expectedSha != null && !sameCert(sha256OfFile(file) ?: return Verdict.Unreadable, expectedSha)) {
            return Verdict.BadHash
        }
        if (expectedCert != null) {
            val cert = certHexOfApk(ctx, file) ?: return Verdict.Unreadable
            if (!sameCert(cert, expectedCert)) return Verdict.WrongSigner
        }
        val versionName = try {
            ctx.packageManager.getPackageArchiveInfo(file.absolutePath, 0)?.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
        return Verdict.Ok(file, versionName)
    }

    /** Runs [work] off the main thread and delivers the result on it. */
    fun <T> inBackground(executor: Executor, main: android.os.Handler, work: () -> T, done: (T) -> Unit) {
        executor.execute {
            val r = work()
            main.post { done(r) }
        }
    }
}

/**
 * The build published for the flavor that is running, or null when the release
 * does not carry one.
 *
 * Top level so the activities can call `release.forCurrentFlavor()` without
 * importing from inside the object.
 */
fun ReleaseInfo.forCurrentFlavor(): ReleaseAsset? =
    flavors[if (BuildConfig.NETWORK_UPDATES) UpdateChecker.FLAVOR_ONLINE else UpdateChecker.FLAVOR_OFFLINE]
