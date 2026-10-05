package aurius.kura

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest

import javax.net.ssl.SSLException
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
 * Handing the networked APK to someone who installed the standard build would
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
        val names = flavorsObj.keys()
        while (names.hasNext()) {
            val key = names.next()
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

    const val FLAVOR_STANDARD = "standard"
    const val FLAVOR_NETWORK = "network"

    /**
     * Keys published before the rename. v1.0.3 and earlier look themselves up by
     * their own name, so an update.json without these is invisible to an app
     * that has not updated yet -- the one release where that matters most is the
     * release that would have told them to. Kept until nothing pre-rename is
     * worth keeping updatable.
     */
    val LEGACY_FLAVORS = listOf("offline", "online")

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
     * What a fetch produced. Failures carry a reason instead of a null, because
     * a null turned "the release page could not be read" into a guess about the
     * user's network, and this code has to be debuggable from a phone with no
     * terminal attached to it.
     */
    sealed class Fetch {
        data class Text(val body: String) : Fetch()
        data class Bytes(val file: File, val count: Long) : Fetch()
        data class Failed(val detail: String, val hint: String) : Fetch()
    }

    private const val MAX_HOPS = 5

    /**
     * Opens [url], following redirects by hand.
     *
     * Automatic following is switched off on purpose. GitHub answers every
     * release asset with a 302 to a CDN host, so the redirect chain is the
     * normal case rather than an edge case, and doing it here means the chain is
     * ours to inspect and report instead of the platform's to fail silently.
     * Relative Location headers are resolved against the current URL.
     */
    private fun openFollowing(url: String, timeoutMs: Int, accept: String): HttpURLConnection {
        var current = url
        repeat(MAX_HOPS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = false
                requestMethod = "GET"
                setRequestProperty("Accept", accept)
                setRequestProperty("User-Agent", "Kura/$TAG")
            }
            val code = conn.responseCode
            if (code !in 300..399) return conn
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            if (location.isNullOrBlank()) throw IOException("HTTP $code with no Location header")
            current = URL(URL(current), location).toString()
        }
        throw IOException("more than $MAX_HOPS redirects")
    }

    /** Turns an exception into something a user can act on. */
    private fun failed(url: String, e: Exception): Fetch.Failed {
        val detail = "${e.javaClass.simpleName}: ${e.message ?: "no message"}"
        val hint = when (e) {
            is SSLException ->
                "The TLS handshake failed. Either the connection was intercepted or this " +
                    "device's TLS is too old or misconfigured for the release host."
            is UnknownHostException ->
                "The name did not resolve, so this build could not reach the release host."
            is SocketTimeoutException ->
                "The connection timed out before the server answered."
            is FileNotFoundException ->
                "The server has no file at that address. The release may not be published yet."
            else ->
                "The connection to the release host failed."
        }
        Log.w(TAG, "GET $url failed: $detail")
        return Fetch.Failed(detail, hint)
    }

    /**
     * Fetches [url] into the staging directory. Used for the check itself and
     * for the download; both go through here so there is one place that talks to
     * the network at all.
     *
     * Only ever called when [BuildConfig.NETWORK_UPDATES] is true, which is only
     * true for the flavor that declares INTERNET.
     */
    fun download(url: String, dest: File, timeoutMs: Int = 20_000): Fetch {
        var conn: HttpURLConnection? = null
        return try {
            conn = openFollowing(url, timeoutMs, "application/octet-stream, */*")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $url returned $code")
                return Fetch.Failed("HTTP $code from $url", "The server refused the request.")
            }
            val tmp = File(dest.absolutePath + ".part")
            tmp.parentFile?.mkdirs()
            conn.inputStream.use { ins -> tmp.outputStream().use { out -> ins.copyTo(out, 64 * 1024) } }
            // Rename only once the whole body landed, so an interrupted download
            // can never be mistaken for a complete file.
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return Fetch.Failed("could not move the download into place", "The file could not be written to storage.")
            }
            Fetch.Bytes(dest, dest.length())
        } catch (e: Exception) {
            File(dest.absolutePath + ".part").delete()
            failed(url, e)
        } finally {
            conn?.disconnect()
        }
    }

    fun readSmall(url: String, timeoutMs: Int = 15_000): Fetch {
        var conn: HttpURLConnection? = null
        return try {
            conn = openFollowing(url, timeoutMs, "application/json, text/plain")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $url returned $code")
                return Fetch.Failed("HTTP $code from $url", "The server refused the request.")
            }
            Fetch.Text(conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) })
        } catch (e: Exception) {
            failed(url, e)
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
        data object BadSize : Verdict()
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
        expectedCert: String?,
        expectedSize: Long? = null
    ): Verdict {
        if (!file.exists() || file.length() == 0L) return Verdict.Unreadable
        val code = versionCodeOfApk(ctx, file) ?: return Verdict.Unreadable
        if (!isNewer(installedCode, code)) return Verdict.NotNewer
        // Compared before the digest, because it is the cheap rejection: a
        // transfer cut short fails here instead of after hashing 3 MB.
        if (expectedSize != null && file.length() != expectedSize) return Verdict.BadSize
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
    flavors[if (BuildConfig.NETWORK_UPDATES) UpdateChecker.FLAVOR_NETWORK else UpdateChecker.FLAVOR_STANDARD]
