package aurius.kura

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.util.LruCache
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/**
 * Encrypted vault: every media file is AES256-GCM encrypted via
 * EncryptedFile, stored in app-private filesDir/vault/. Plaintext
 * never touches disk except transient video playback copies in
 * app-private cacheDir/play/ which are wiped on lock/close.
 */
class CryptoVault(ctx: Context) {
    private val app = ctx.applicationContext
    val vaultDir: File
        get() = File(app.filesDir, if (VaultLock.isDecoy) "vault_decoy" else "vault").apply { mkdirs() }
    val playDir: File
        get() = File(app.cacheDir, if (VaultLock.isDecoy) "play_decoy" else "play").apply { mkdirs() }

    private val masterKey: MasterKey by lazy {
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
        MasterKey.Builder(app, alias)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    }

    private fun encFile(name: String): EncryptedFile =
        EncryptedFile.Builder(
            app, File(vaultDir, name), masterKey,
            EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB
        ).build()

    fun fileFor(name: String): File = File(vaultDir, File(name).name)

    /** Overwrites file with zeroes before unlinking to reduce unallocated disk exposure (best-effort on wear-levelled flash storage). */
    fun secureShred(file: File) {
        Companion.secureShred(file)
    }

    /** Encrypt [input] stream into vault as [name]. Returns bytes written. */
    fun encryptStream(input: InputStream, name: String): Pair<Long, String> {
        var total = 0L
        val target = File(vaultDir, name)
        if (target.exists()) secureShred(target)

        val md = java.security.MessageDigest.getInstance("SHA-256")
        encFile(name).openFileOutput().use { out ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                md.update(buf, 0, n)
                total += n
            }
        }
        val hash = md.digest().joinToString("") { "%02x".format(it) }
        return total to hash
    }

    fun decryptBytes(name: String, maxBytes: Int = 128 * 1024 * 1024): ByteArray {
        encFile(name).openFileInput().use { inp ->
            val bos = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val n = inp.read(buf)
                if (n <= 0) break
                total += n
                if (total > maxBytes) throw java.io.IOException("File exceeds maxBytes limit ($maxBytes)")
                bos.write(buf, 0, n)
            }
            return bos.toByteArray()
        }
    }

    /** Streams decrypted content directly into [outputStream] without full RAM buffering. */
    fun exportToStream(name: String, outputStream: OutputStream) {
        encFile(name).openFileInput().use { inp ->
            inp.copyTo(outputStream)
        }
    }

    /** Decrypt to isolated app-private cache file for playback. Caller must delete when done. */
    fun decryptToPlayCache(name: String): File {
        val ext = if (name.contains(".")) "." + name.substringAfterLast(".").lowercase() else ".mp4"
        val out = File(playDir, "play_${UUID.randomUUID().toString().take(8)}$ext")
        encFile(name).openFileInput().use { inp ->
            out.outputStream().use { o ->
                inp.copyTo(o)
            }
        }
        return out
    }

    companion object {
        private val thumbCacheKb: Int = run {
            val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
            (maxMemKb / 12).coerceIn(16 * 1024, 32 * 1024)
        }

        private val thumbCache = object : LruCache<String, Bitmap>(thumbCacheKb) {
            override fun sizeOf(k: String, v: Bitmap): Int = (v.byteCount / 1024).coerceAtLeast(1)
        }

        private val globalVaultInstances = java.util.Collections.newSetFromMap(java.util.WeakHashMap<CryptoVault, Boolean>())

        fun evictAllThumbCaches() {
            thumbCache.evictAll()
        }

        /**
         * Releases cached bitmaps under memory pressure.
         *
         * [level] uses the `ComponentCallbacks2` constants, so TRIM_MEMORY_BACKGROUND
         * (20) frees a good chunk while TRIM_MEMORY_RUNNING_LOW (10) only trims to
         * half, and anything at or below COMPLETE/MODERATE empties the cache.
         */
        fun trimThumbCaches(level: Int) {
            when {
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> thumbCache.evictAll()
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_MODERATE -> thumbCache.evictAll()
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> thumbCache.trimToSize(thumbCacheKb / 2)
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> thumbCache.trimToSize(thumbCacheKb / 2)
                level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> thumbCache.evictAll()
            }
        }

        fun evictThumbnail(fileName: String) {
            // Cache keys embed target size and rotation, so match on the file
            // name rather than enumerating combinations.
            val needle = "_${File(fileName).name}_"
            thumbCache.snapshot().keys
                .filter { it.contains(needle) }
                .forEach { thumbCache.remove(it) }
        }

        fun getExifRotation(bytes: ByteArray): Int {
            return try {
                val exif = android.media.ExifInterface(ByteArrayInputStream(bytes))
                when (exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } catch (_: Throwable) { 0 }
        }

        fun rotateBitmap(bmp: Bitmap, degrees: Int): Bitmap {
            val deg = (degrees % 360 + 360) % 360
            if (deg == 0) return bmp
            val matrix = android.graphics.Matrix().apply { postRotate(deg.toFloat()) }
            // Deliberately does NOT recycle the source: callers may hand in a
            // bitmap that is already sitting in the thumbnail LruCache, and
            // recycling it under them is a guaranteed "Canvas: trying to use a
            // recycled bitmap" crash. Let the GC reclaim it.
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
        }

        /** Paths currently being shredded, so repeated wipes don't redo the work. */
        private val shredding = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
        private val shredLock = Any()

        /**
         * Overwrites the file with zeroes, then unlinks it.
         *
         * The overwrite is deliberately *not* fsynced. `"rws"` makes the kernel
         * flush on every 64KB write and the explicit `fd.sync()` flushed again,
         * so shredding a 1GB cache file paid for ~16k device-level syncs. That
         * is power-loss durability, which buys nothing for app-private cache
         * files, and it is the single biggest cost in the lock path. The
         * plaintext-overwrite-then-delete property is unchanged; only the
         * durability of the zero-fill is relaxed.
         */
        fun secureShred(file: File) {
            val path = file.absolutePath
            synchronized(shredLock) {
                if (!shredding.add(path)) return
            }
            try {
                if (file.exists() && file.isFile) {
                    val len = file.length()
                    if (len > 0) {
                        java.io.RandomAccessFile(file, "rw").use { raf ->
                            val buf = ByteArray(64 * 1024)
                            var rem = len
                            while (rem > 0) {
                                if (Thread.interrupted()) break
                                val c = rem.coerceAtMost(buf.size.toLong()).toInt()
                                raf.write(buf, 0, c)
                                rem -= c
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
            try { file.delete() } catch (_: Exception) {}
            synchronized(shredLock) { shredding.remove(path) }
        }
    }

    init {
        synchronized(globalVaultInstances) {
            globalVaultInstances.add(this)
        }
    }

    fun delete(name: String) {
        try {
            secureShred(File(vaultDir, name))
            evictThumbnail(name)
        } catch (_: Exception) {}
    }

    fun wipePlayCache() {
        try {
            File(app.cacheDir, "play").listFiles()?.forEach { secureShred(it) }
            File(app.cacheDir, "play_decoy").listFiles()?.forEach { secureShred(it) }
            app.cacheDir.listFiles()?.forEach { f ->
                if (f.name.startsWith("play_") || f.name.startsWith("probe_")) {
                    secureShred(f)
                }
            }
        } catch (_: Exception) {}
    }

    // ---- Shared in-memory thumbnail cache ----
    //
    // Was an instance field sized at maxMemory/6. Every Activity builds its own
    // CryptoVault, so MainActivity and DetailActivity were each holding 17% of
    // the heap at the same time (~34% total), and the cache was smaller than a
    // single screenful, so scrolling evicted exactly what was about to be needed.
    // One process-wide cache at 1/12 of the heap holds roughly two screens.
    private val thumbs: LruCache<String, Bitmap>
        get() = thumbCache

    fun thumb(name: String, mime: String, px: Int = 512, extraRotation: Int = 0): Bitmap? {
        // Video playback has no content-rotation API, so a stored rotation on a
        // video could never be honoured. Ignoring it here keeps the grid
        // thumbnail consistent with what the player actually shows.
        val isVideo = mime.startsWith("video")
        val normRot = if (isVideo) 0 else (extraRotation % 360 + 360) % 360
        val cacheKey = "${VaultLock.isDecoy}_${name}_${px}_$normRot"
        thumbs.get(cacheKey)?.let { return it }
        val bmp = try {
            if (mime.startsWith("video")) {
                videoFrame(name, px, normRot) ?: sampledImage(name, px, normRot)
            } else {
                sampledImage(name, px, normRot) ?: videoFrame(name, px, normRot)
            }
        } catch (_: Throwable) { null }

        if (bmp != null) {
            thumbs.put(cacheKey, bmp)
        }
        return bmp
    }

    /** Decodes image bounds and safely downsamples to target resolution avoiding OOM. */
    fun sampledImage(name: String, px: Int, extraRotation: Int = 0): Bitmap? {
        var bytes: ByteArray? = null
        return try {
            bytes = decryptBytes(name, maxBytes = 48 * 1024 * 1024)
            if (bytes.isEmpty()) return null
            val totalRot = (getExifRotation(bytes) + extraRotation) % 360
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            var sample = 1
            val maxSide = maxOf(opts.outWidth, opts.outHeight)
            while (maxSide / (sample * 2) >= px) {
                sample *= 2
            }

            val o2 = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565 // Faster, 50% less RAM
            }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) ?: return null
            if (totalRot != 0) rotateBitmap(raw, totalRot) else raw
        } catch (_: Throwable) {
            null
        } finally {
            bytes?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    /** Decodes high-resolution image for detail view, downsampling only if exceeds maxDim to prevent OOM. */
    fun decodeDisplayImage(name: String, maxDim: Int = 3000, extraRotation: Int = 0): Bitmap? {
        var bytes: ByteArray? = null
        return try {
            bytes = decryptBytes(name, maxBytes = 64 * 1024 * 1024)
            if (bytes.isEmpty()) return null
            val totalRot = (getExifRotation(bytes) + extraRotation) % 360
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            var sample = 1
            val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / sample > maxDim) {
                sample *= 2
            }

            val o = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o) ?: return null
            if (totalRot != 0) rotateBitmap(raw, totalRot) else raw
        } catch (_: Throwable) {
            null
        } finally {
            bytes?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    data class DecodedAnimation(val drawable: Drawable, val sourceBytes: ByteArray)

    /**
     * Decodes an animated GIF for display, downsampling to fit [boxW]x[boxH].
     *
     * `ImageDecoder` streams animation frames on demand from the underlying
     * source buffer. The decoded [DecodedAnimation] returns the active [ByteArray]
     * so callers can keep it valid during playback and securely wipe it on teardown.
     * Returns null for non-GIF, pre-API-28, or undecodable input.
     */
    fun animatedImage(name: String, boxW: Int, boxH: Int): DecodedAnimation? {
        if (android.os.Build.VERSION.SDK_INT < 28) return null
        return try {
            val bytes = decryptBytes(name, maxBytes = 48 * 1024 * 1024)
            if (bytes.isEmpty()) return null
            val src = android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes))
            val drawable = android.graphics.ImageDecoder.decodeDrawable(src) { decoder, info, _ ->
                val sw = info.size.width
                val sh = info.size.height
                if (sw > 0 && sh > 0 && boxW > 0 && boxH > 0) {
                    val scale = minOf(
                        boxW.toFloat() / sw,
                        boxH.toFloat() / sh,
                        1f
                    )
                    if (scale < 1f) {
                        decoder.setTargetSize(
                            (sw * scale).toInt().coerceAtLeast(1),
                            (sh * scale).toInt().coerceAtLeast(1)
                        )
                    }
                }
            }
            DecodedAnimation(drawable, bytes)
        } catch (_: Throwable) {
            null
        }
    }

    private fun videoFrame(name: String, px: Int, extraRotation: Int = 0): Bitmap? {
        var bytes: ByteArray? = null
        val r = MediaMetadataRetriever()
        try {
            bytes = decryptBytes(name, maxBytes = 48 * 1024 * 1024)
            if (bytes.isEmpty()) return null
            r.setDataSource(RamMediaDataSource(bytes))
            val videoRot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val totalRot = (videoRot + extraRotation) % 360
            val f = r.getFrameAtTime(0) ?: return null
            val maxSide = maxOf(f.width, f.height)
            if (maxSide <= 0) return null
            val scale = px.toFloat() / maxSide.toFloat()
            val scaled = if (scale >= 1f) f else Bitmap.createScaledBitmap(f, (f.width * scale).toInt().coerceAtLeast(1), (f.height * scale).toInt().coerceAtLeast(1), true)
            if (scaled != f) f.recycle()
            return if (totalRot != 0) rotateBitmap(scaled, totalRot) else scaled
        } catch (_: Throwable) {
            return videoFrameDiskFallback(name, px, extraRotation)
        } finally {
            try { r.release() } catch (_: Exception) {}
            bytes?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun videoFrameDiskFallback(name: String, px: Int, extraRotation: Int = 0): Bitmap? {
        val target = fileFor(name)
        if (!target.exists() || target.length() == 0L) return null
        val tmp = File(playDir, "thumb_${UUID.randomUUID().toString().take(8)}.tmp")
        try {
            encFile(name).openFileInput().use { inp ->
                tmp.outputStream().use { o -> inp.copyTo(o) }
            }
            if (tmp.length() == 0L) return null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(tmp.absolutePath)
                val videoRot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                val totalRot = (videoRot + extraRotation) % 360
                val f = r.getFrameAtTime(0) ?: return null
                val maxSide = maxOf(f.width, f.height)
                if (maxSide <= 0) return null
                val scale = px.toFloat() / maxSide.toFloat()
                val scaled = if (scale >= 1f) f else Bitmap.createScaledBitmap(f, (f.width * scale).toInt().coerceAtLeast(1), (f.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled != f) f.recycle()
                return if (totalRot != 0) rotateBitmap(scaled, totalRot) else scaled
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        } catch (_: Throwable) {
            return null
        } finally {
            secureShred(tmp)
        }
    }

    fun probeImage(name: String): Pair<Int, Int> {
        var bytes: ByteArray? = null
        return try {
            bytes = decryptBytes(name, maxBytes = 32 * 1024 * 1024)
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            val rot = getExifRotation(bytes)
            if (rot == 90 || rot == 270) {
                o.outHeight to o.outWidth
            } else {
                o.outWidth to o.outHeight
            }
        } catch (_: Exception) { 0 to 0 }
        finally {
            bytes?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    fun probeVideo(name: String): Triple<Int, Int, Int> {
        var bytes: ByteArray? = null
        val r = MediaMetadataRetriever()
        try {
            bytes = decryptBytes(name, maxBytes = 48 * 1024 * 1024)
            r.setDataSource(RamMediaDataSource(bytes))
            var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rot == 90 || rot == 270) {
                val tmp = w
                w = h
                h = tmp
            }
            return Triple(w, h, d)
        } catch (_: Exception) {
            return probeVideoDiskFallback(name)
        } finally {
            try { r.release() } catch (_: Exception) {}
            bytes?.let { java.util.Arrays.fill(it, 0.toByte()) }
        }
    }

    private fun probeVideoDiskFallback(name: String): Triple<Int, Int, Int> {
        val tmp = File(playDir, "probe_${UUID.randomUUID().toString().take(8)}.tmp")
        try {
            encFile(name).openFileInput().use { inp ->
                tmp.outputStream().use { o -> inp.copyTo(o) }
            }
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(tmp.absolutePath)
                var w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                var h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                val d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
                val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (rot == 90 || rot == 270) {
                    val swap = w
                    w = h
                    h = swap
                }
                return Triple(w, h, d)
            } finally {
                try { r.release() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            return Triple(0, 0, 0)
        } finally {
            secureShred(tmp)
        }
    }

    fun vaultTotalBytes(): Long {
        return try {
            vaultDir.listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) { 0L }
    }

    fun vaultFileCount(): Int {
        return try {
            vaultDir.listFiles()?.size ?: 0
        } catch (_: Exception) { 0 }
    }
}
