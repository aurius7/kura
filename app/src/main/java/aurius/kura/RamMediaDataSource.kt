package aurius.kura

import android.media.MediaDataSource
import java.util.Arrays

/**
 * In-memory [MediaDataSource] for MediaPlayer and MediaMetadataRetriever.
 * Allows zero-disk streaming of decrypted video data directly from RAM.
 * Upon [close], overwrites the buffer with zeros to prevent memory forensic extraction.
 */
class RamMediaDataSource(private val data: ByteArray) : MediaDataSource() {
    private var isClosed = false

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (isClosed || position >= data.size) return -1
        val remaining = data.size - position
        val toRead = remaining.coerceAtMost(size.toLong()).toInt()
        System.arraycopy(data, position.toInt(), buffer, offset, toRead)
        return toRead
    }

    override fun getSize(): Long = if (isClosed) 0L else data.size.toLong()

    override fun close() {
        if (!isClosed) {
            isClosed = true
            Arrays.fill(data, 0.toByte())
        }
    }
}
