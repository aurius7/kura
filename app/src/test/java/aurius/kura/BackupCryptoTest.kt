package aurius.kura

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.GeneralSecurityException

class BackupCryptoTest {

    @Test
    fun testEncryptionDecryptionRoundtrip() {
        val originalData = "TopSecretBooruMediaPayload1234567890".repeat(2000).toByteArray(Charsets.UTF_8)
        val password = "StrongPassword#2026".toCharArray()

        val plainIn = ByteArrayInputStream(originalData)
        val cipherOut = ByteArrayOutputStream()

        BackupCrypto.encryptStream(plainIn, cipherOut, password)
        val cipherBytes = cipherOut.toByteArray()

        // Verify magic header is present
        assertTrue(BackupCrypto.hasMagic(cipherBytes))

        // Decrypt with correct password
        val cipherIn = ByteArrayInputStream(cipherBytes)
        val plainOut = ByteArrayOutputStream()
        BackupCrypto.decryptStream(cipherIn, plainOut, password)

        assertArrayEquals(originalData, plainOut.toByteArray())
    }

    @Test
    fun testChunkedGcmOutputStreamExplicitly() {
        val originalData = "ExplicitChunkedStreamPayload_98765".repeat(1500).toByteArray(Charsets.UTF_8)
        val password = "StreamPassword#2026".toCharArray()

        val cipherOut = ByteArrayOutputStream()
        BackupCrypto.createCipherOutputStream(cipherOut, password).use { out ->
            out.write(originalData)
        }
        val cipherBytes = cipherOut.toByteArray()
        assertTrue(BackupCrypto.hasMagic(cipherBytes))

        val plainOut = ByteArrayOutputStream()
        BackupCrypto.decryptStream(ByteArrayInputStream(cipherBytes), plainOut, password)
        assertArrayEquals(originalData, plainOut.toByteArray())
    }

    @Test
    fun testWrongPasswordFails() {
        val originalData = "ConfidentialVaultMedia".repeat(100).toByteArray(Charsets.UTF_8)
        val password = "CorrectPassword".toCharArray()
        val wrongPassword = "WrongPassword".toCharArray()

        val plainIn = ByteArrayInputStream(originalData)
        val cipherOut = ByteArrayOutputStream()
        BackupCrypto.encryptStream(plainIn, cipherOut, password)

        val cipherIn = ByteArrayInputStream(cipherOut.toByteArray())
        val plainOut = ByteArrayOutputStream()

        try {
            BackupCrypto.decryptStream(cipherIn, plainOut, wrongPassword)
            fail("Expected GeneralSecurityException on wrong password")
        } catch (e: GeneralSecurityException) {
            // Success: expected AEADBadTagException
        }
    }

    @Test
    fun testZipRestoreFlow() {
        val originalImageBytes = ByteArray(50_000) { (it % 256).toByte() }
        val baos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zos.write("[{\"file_name\":\"test.jpg\",\"favorite\":true,\"tags\":[\"tag1\"]}]".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("test.jpg"))
            zos.write(originalImageBytes)
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val zipIn = java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes))
        var readData: ByteArray? = null
        while (true) {
            val entry = zipIn.nextEntry ?: break
            if (entry.name == "manifest.json") {
                val str = zipIn.bufferedReader().readText()
            } else {
                readData = zipIn.readBytes()
            }
            zipIn.closeEntry()
        }
        assertNotNull(readData)
        assertArrayEquals(originalImageBytes, readData)
    }

    @Test
    fun testZipMultiEntryStreaming() {
        val entry1 = ByteArray(100_000) { (it % 100).toByte() }
        val entry2 = ByteArray(250_000) { (it % 200).toByte() }
        val baos = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(java.util.zip.ZipEntry("manifest.json"))
            zos.write("[{\"file_name\":\"1.jpg\"},{\"file_name\":\"2.jpg\"}]".toByteArray(Charsets.UTF_8))
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("1.jpg"))
            zos.write(entry1)
            zos.closeEntry()

            zos.putNextEntry(java.util.zip.ZipEntry("2.jpg"))
            zos.write(entry2)
            zos.closeEntry()
        }

        val zipBytes = baos.toByteArray()
        val zipIn = java.util.zip.ZipInputStream(ByteArrayInputStream(zipBytes))
        val readEntries = mutableMapOf<String, ByteArray>()
        while (true) {
            val entry = zipIn.nextEntry ?: break
            val name = entry.name
            if (name == "manifest.json") {
                val b = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                while (true) {
                    val n = zipIn.read(buf)
                    if (n <= 0) break
                    b.write(buf, 0, n)
                }
            } else {
                val b = ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = zipIn.read(buf)
                    if (n <= 0) break
                    b.write(buf, 0, n)
                }
                readEntries[name] = b.toByteArray()
            }
            zipIn.closeEntry()
        }
        assertEquals(2, readEntries.size)
        assertArrayEquals(entry1, readEntries["1.jpg"])
        assertArrayEquals(entry2, readEntries["2.jpg"])
    }

    @Test
    fun testLegacyVbooruArchiveDecrypt() {
        val originalData = "LegacyVaultData_123456789".toByteArray(Charsets.UTF_8)
        val password = "LegacyPassword".toCharArray()

        // Manually craft an archive with the 14-byte VBOORU_ENC_01\n magic header
        val cipherOut = ByteArrayOutputStream()
        cipherOut.write(BackupCrypto.MAGIC_VBOORU)

        val salt = ByteArray(16) { 0x42.toByte() }
        cipherOut.write(salt)

        // Derive key with same parameters
        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, 100_000, 256)
        val key = javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val iv = ByteArray(12) { 0x07.toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        val cipherBytes = cipher.doFinal(originalData)

        val dos = java.io.DataOutputStream(cipherOut)
        dos.writeInt(cipherBytes.size)
        dos.write(iv)
        dos.write(cipherBytes)
        dos.writeInt(0) // EOF
        dos.flush()

        val fullArchive = cipherOut.toByteArray()

        // Verify hasMagic detects it
        assertTrue(BackupCrypto.hasMagic(fullArchive))
        assertTrue(BackupCrypto.hasMagic(fullArchive.copyOf(14)))
        assertTrue(BackupCrypto.hasMagic(fullArchive.copyOf(32)))

        // Verify decryptStream successfully decrypts the legacy archive
        val cipherIn = ByteArrayInputStream(fullArchive)
        val plainOut = ByteArrayOutputStream()
        BackupCrypto.decryptStream(cipherIn, plainOut, password)

        assertArrayEquals(originalData, plainOut.toByteArray())
    }

    @Test
    fun testNewArchiveHeaderCarriesKdfIterations() {
        val plainIn = ByteArrayInputStream("HeaderKdfPayload".repeat(100).toByteArray(Charsets.UTF_8))
        val cipherOut = ByteArrayOutputStream()
        BackupCrypto.encryptStream(plainIn, cipherOut, "HeaderKdfPassword".toCharArray())
        val bytes = cipherOut.toByteArray()

        val head = String(bytes, 0, 32, Charsets.US_ASCII)
        assertTrue("new archive must be KURA_ENC_03 with a header-tagged KDF count",
            head.startsWith("KURA_ENC_03\n120000\n"))
    }

    @Test
    fun testLegacyKura02ArchiveAt100kIterationsStillOpens() {
        val originalData = "LegacyKura02Payload_xyz".toByteArray(Charsets.UTF_8)
        val password = "LegacyKura02Password".toCharArray()

        val cipherOut = ByteArrayOutputStream()
        cipherOut.write(BackupCrypto.MAGIC_KURA_02)

        val salt = ByteArray(16) { 0x51.toByte() }
        cipherOut.write(salt)

        val factory = javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = javax.crypto.spec.PBEKeySpec(password, salt, 100_000, 256)
        val key = javax.crypto.spec.SecretKeySpec(factory.generateSecret(spec).encoded, "AES")

        val iv = ByteArray(12) { 0x09.toByte() }
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, javax.crypto.spec.GCMParameterSpec(128, iv))
        cipher.updateAAD(java.nio.ByteBuffer.allocate(8).putLong(0).array())
        val cipherBytes = cipher.doFinal(originalData)

        val dos = java.io.DataOutputStream(cipherOut)
        dos.writeInt(cipherBytes.size)
        dos.write(iv)
        dos.write(cipherBytes)
        dos.writeInt(0) // EOF
        dos.flush()

        val plainOut = ByteArrayOutputStream()
        BackupCrypto.decryptStream(ByteArrayInputStream(cipherOut.toByteArray()), plainOut, password)
        assertArrayEquals(originalData, plainOut.toByteArray())
    }

    /**
     * A truncated archive must never decrypt silently. The chunked stream is
     * terminated by a trailing zero-length chunk marker, so cutting the archive
     * anywhere removes that marker and the reader either hits EOF mid-stream or
     * fails the partial chunk's GCM tag. This is the property the restore path
     * relies on: SettingsActivity decrypts to a temp file and only commits
     * entries after decryptStream returns, so a detected failure means the
     * archive is never treated as restorable.
     */
    @Test
    fun testTruncatedArchiveFailsAtEveryCutPoint() {
        val originalData = "TruncationProbePayload_abcdefghij".repeat(9000).toByteArray(Charsets.UTF_8)
        val password = "TruncationProbe#2026".toCharArray()

        val cipherOut = ByteArrayOutputStream()
        BackupCrypto.encryptStream(ByteArrayInputStream(originalData), cipherOut, password)
        val cipherBytes = cipherOut.toByteArray()

        // Sanity check: the intact archive decrypts, so any failure below is
        // caused by truncation and not by a broken test fixture.
        val good = ByteArrayOutputStream()
        BackupCrypto.decryptStream(ByteArrayInputStream(cipherBytes), good, password)
        assertArrayEquals(originalData, good.toByteArray())

        // Header, salt and chunk-boundary regions, plus a coarse sweep through
        // the ciphertext and the tail where the terminator lives.
        val cutPoints = (listOf(1, 8, 12, 20, 25, 40, 44) +
            (1 until cipherBytes.size step 997).toList() +
            listOf(cipherBytes.size - 5, cipherBytes.size - 4, cipherBytes.size - 2, cipherBytes.size - 1))
            .distinct()
            .filter { it in 1 until cipherBytes.size }

        for (cut in cutPoints) {
            val partial = cipherBytes.copyOf(cut)
            val out = ByteArrayOutputStream()
            var detected = false
            var detail = ""
            try {
                BackupCrypto.decryptStream(ByteArrayInputStream(partial), out, password)
            } catch (e: java.io.IOException) {
                // EOFException when the cut lands on a chunk boundary, or an
                // explicit "not a valid archive" when it lands in the header.
                detected = true
                detail = e.javaClass.simpleName
            } catch (e: GeneralSecurityException) {
                // AEADBadTagException when the cut lands inside a chunk.
                detected = true
                detail = e.javaClass.simpleName
            }
            assertTrue(
                "Truncation at byte $cut of ${cipherBytes.size} decrypted silently ($detail)",
                detected
            )
        }
    }

    /**
     * A single flipped bit anywhere in a chunk's ciphertext must fail the GCM
     * tag check. Guards against a future refactor weakening chunk
     * authentication or dropping the sequence-number AAD.
     */
    @Test
    fun testTamperedChunkFailsAuthentication() {
        val originalData = "TamperProbePayload_0123456789".repeat(500).toByteArray(Charsets.UTF_8)
        val password = "TamperProbe#2026".toCharArray()

        val cipherOut = ByteArrayOutputStream()
        BackupCrypto.encryptStream(ByteArrayInputStream(originalData), cipherOut, password)
        val cipherBytes = cipherOut.toByteArray()

        // magic "KURA_ENC_03\n" (12) + iterations "120000\n" (7) + salt (16)
        // + chunk length (4) + iv (12) = 51, then flip a byte inside the first
        // chunk's ciphertext.
        val pos = 12 + 7 + 16 + 4 + 12 + 32
        assertTrue("chunk ciphertext offset in range", pos < cipherBytes.size)
        val tampered = cipherBytes.copyOf()
        tampered[pos] = (tampered[pos].toInt() xor 0x01).toByte()

        val out = ByteArrayOutputStream()
        try {
            BackupCrypto.decryptStream(ByteArrayInputStream(tampered), out, password)
            fail("Expected GeneralSecurityException on tampered ciphertext")
        } catch (e: GeneralSecurityException) {
            // expected
        }
    }
}
