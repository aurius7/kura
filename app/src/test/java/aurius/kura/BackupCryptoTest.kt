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
}
