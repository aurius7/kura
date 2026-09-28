package aurius.kura

import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Streaming password-authenticated encryption for portable `.kura` backups.
 * Uses PBKDF2-HMAC-SHA256 (100,000 iterations) with a 16-byte random salt,
 * and chunked 64KB AES-256-GCM with sequential AAD chunk counters.
 *
 * Wrong passwords trigger [javax.crypto.AEADBadTagException] on chunk 0 in milliseconds.
 * Memory overhead is strictly bounded to 64KB regardless of backup size.
 */
object BackupCrypto {
    val MAGIC_KURA_02 = "KURA_ENC_02\n".toByteArray(Charsets.US_ASCII)
    val MAGIC_KURA_01 = "KURA_ENC_01\n".toByteArray(Charsets.US_ASCII)
    val MAGIC_VBOORU = "VBOORU_ENC_01\n".toByteArray(Charsets.US_ASCII)
    val MAGIC = MAGIC_KURA_02
    private const val ITERATIONS = 100_000
    private const val CHUNK_SIZE = 64 * 1024 // 64 KB plaintext chunks

    fun hasMagic(headerBytes: ByteArray): Boolean {
        if (headerBytes.size < 8) return false
        val s = String(headerBytes, 0, minOf(headerBytes.size, 32), Charsets.US_ASCII)
        return s.startsWith("KURA_ENC_") || s.startsWith("KURO_ENC_") || s.startsWith("VBOORU_ENC_")
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase, salt, ITERATIONS, 256)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    fun createCipherOutputStream(cipherOutput: OutputStream, passphrase: CharArray): OutputStream {
        return ChunkedGcmOutputStream(cipherOutput, passphrase)
    }

    private class ChunkedGcmOutputStream(
        private val underlying: OutputStream,
        passphrase: CharArray
    ) : OutputStream() {
        private val key: SecretKeySpec
        private val dos = java.io.DataOutputStream(underlying)
        private val buffer = ByteArray(CHUNK_SIZE)
        private var bufCount = 0
        private var chunkIndex = 0L
        private val random = SecureRandom()
        private var closed = false

        init {
            underlying.write(MAGIC)
            val salt = ByteArray(16)
            random.nextBytes(salt)
            underlying.write(salt)
            key = deriveKey(passphrase, salt)
        }

        override fun write(b: Int) {
            if (closed) throw java.io.IOException("Stream closed")
            buffer[bufCount++] = b.toByte()
            if (bufCount == CHUNK_SIZE) flushChunk()
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (closed) throw java.io.IOException("Stream closed")
            var currOff = off
            var rem = len
            while (rem > 0) {
                val space = CHUNK_SIZE - bufCount
                val toCopy = Math.min(space, rem)
                System.arraycopy(b, currOff, buffer, bufCount, toCopy)
                bufCount += toCopy
                currOff += toCopy
                rem -= toCopy
                if (bufCount == CHUNK_SIZE) flushChunk()
            }
        }

        private fun flushChunk() {
            if (bufCount == 0) return
            val iv = ByteArray(12)
            random.nextBytes(iv)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val aad = ByteBuffer.allocate(8).putLong(chunkIndex++).array()
            cipher.updateAAD(aad)
            val cipherBytes = cipher.doFinal(buffer, 0, bufCount)

            dos.writeInt(cipherBytes.size)
            dos.write(iv)
            dos.write(cipherBytes)
            bufCount = 0
        }

        override fun flush() {
            if (closed) return
            dos.flush()
        }

        override fun close() {
            if (closed) return
            closed = true
            try {
                if (bufCount > 0) flushChunk()
                dos.writeInt(0)
                dos.flush()
            } finally {
                java.util.Arrays.fill(buffer, 0.toByte())
                dos.close()
            }
        }
    }

    /**
     * Encrypts [plainInput] into [cipherOutput] using chunked AES-256-GCM.
     */
    fun encryptStream(plainInput: InputStream, cipherOutput: OutputStream, passphrase: CharArray) {
        createCipherOutputStream(cipherOutput, passphrase).use { out ->
            plainInput.copyTo(out)
        }
    }

    /**
     * Decrypts [cipherInput] into [plainOutput] using chunked AES-256-GCM.
     * Supports both KURA_ENC_02 (with AAD sequence counter) and legacy KURA_ENC_01 / VBOORU_ENC_01.
     * Throws [java.security.GeneralSecurityException] if passphrase is wrong or file corrupted.
     */
    fun decryptStream(cipherInput: InputStream, plainOutput: OutputStream, passphrase: CharArray) {
        val dis = java.io.DataInputStream(cipherInput)
        val headerBos = java.io.ByteArrayOutputStream()
        while (headerBos.size() < 32) {
            val b = dis.read()
            if (b == -1) break
            if (b == '\n'.code) break
            if (b != '\r'.code) {
                headerBos.write(b)
            }
        }
        val headerStr = headerBos.toString(Charsets.US_ASCII.name()).trim()
        val isKura02 = headerStr.startsWith("KURA_ENC_02")
        val isKura = headerStr.startsWith("KURA_ENC_") || headerStr.startsWith("KURO_ENC_")
        val isVbooru = headerStr.startsWith("VBOORU_ENC_")

        if (!isKura && !isVbooru) {
            throw java.io.IOException("Not a valid encrypted vault archive ($headerStr)")
        }

        val salt = ByteArray(16)
        dis.readFully(salt)

        val key = deriveKey(passphrase, salt)
        var chunkIndex = 0L

        while (true) {
            val cipherLen = dis.readInt()
            if (cipherLen == 0) break // EOF marker
            if (cipherLen < 0 || cipherLen > 10 * 1024 * 1024) {
                throw java.io.IOException("Corrupted archive: invalid chunk size ($cipherLen)")
            }

            val iv = ByteArray(12)
            dis.readFully(iv)

            val cipherBytes = ByteArray(cipherLen)
            dis.readFully(cipherBytes)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            if (isKura02) {
                val aad = ByteBuffer.allocate(8).putLong(chunkIndex++).array()
                cipher.updateAAD(aad)
            }
            val plainBytes = cipher.doFinal(cipherBytes)

            plainOutput.write(plainBytes)
        }
        plainOutput.flush()
    }
}
