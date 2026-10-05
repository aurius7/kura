package aurius.kura

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Exercises the production update path against the live GitHub release, using the
 * real functions the app calls: readSmall, parseRelease, forCurrentFlavor,
 * download, sha256OfFile, sameCert, isNewer.
 *
 * Needs the network, so it only runs when KURA_LIVENET=1 is set.
 */
class LiveUpdateSmokeTest {

    private fun live() = assumeTrue("set KURA_LIVENET=1", System.getenv("KURA_LIVENET") == "1")

    @Test
    fun testLiveReleaseMetadataIsReadableAndNewer() {
        live()
        val body = UpdateChecker.readSmall(UpdateChecker.UPDATE_JSON_URL)
        assertNotNull("update.json must be reachable at the stable URL", body)
        println("live: fetched ${body!!.length} bytes from ${UpdateChecker.UPDATE_JSON_URL}")

        val release = UpdateChecker.parseRelease(body)
        assertNotNull("published update.json must parse", release)
        println("live: parsed versionName=${release!!.versionName} versionCode=${release.versionCode}")

        assertTrue("running app is 16, release must be newer", UpdateChecker.isNewer(16, release.versionCode))
        assertFalse("an up-to-date app must not be nagged", UpdateChecker.isNewer(release.versionCode, release.versionCode))

        val asset = release.forCurrentFlavor()
        assertNotNull("release must carry a build for this flavor", asset)
        println("live: this flavor (online=${BuildConfig.NETWORK_UPDATES}) gets ${asset!!.apkUrl}")
        assertEquals(64, asset.sha256.length)
        assertTrue(asset.apkUrl.endsWith(".apk"))
    }

    @Test
    fun testLiveApkDownloadsAndPassesTheRealHashCheck() {
        live()
        val release = UpdateChecker.parseRelease(UpdateChecker.readSmall(UpdateChecker.UPDATE_JSON_URL)!!)!!
        val asset = release.forCurrentFlavor()!!
        val dest = File.createTempFile("kura-live", ".apk")

        assertTrue("asset must download", UpdateChecker.download(asset.apkUrl, dest))
        println("live: downloaded ${dest.length()} bytes, metadata says ${asset.sizeBytes}")

        assertFalse("the .part file must be renamed, not left behind", File(dest.absolutePath + ".part").exists())

        val digest = UpdateChecker.sha256OfFile(dest)
        assertNotNull("digest must be computable", digest)
        println("live: file sha256   = $digest")
        println("live: metadata sha256= ${asset.sha256}")
        assertTrue("the app's own hash check must accept the real release", UpdateChecker.sameCert(digest!!, asset.sha256))

        // A single flipped byte must fail the same check.
        val tampered = File.createTempFile("kura-tampered", ".apk")
        dest.copyTo(tampered, overwrite = true)
        val bytes = tampered.readBytes()
        bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
        tampered.writeBytes(bytes)
        assertFalse("tampered APK must fail the hash check", UpdateChecker.sameCert(UpdateChecker.sha256OfFile(tampered)!!, asset.sha256))

        dest.delete()
        tampered.delete()
    }

    @Test
    fun testBothFlavorsAreReachableAndDistinct() {
        live()
        val release = UpdateChecker.parseRelease(UpdateChecker.readSmall(UpdateChecker.UPDATE_JSON_URL)!!)!!
        val offline = release.flavors[UpdateChecker.FLAVOR_OFFLINE]!!
        val online = release.flavors[UpdateChecker.FLAVOR_ONLINE]!!
        assertFalse("the two builds must not be the same file", offline.sha256 == online.sha256)

        for ((name, asset) in listOf("offline" to offline, "online" to online)) {
            val dest = File.createTempFile("kura-$name", ".apk")
            assertTrue("$name must download", UpdateChecker.download(asset.apkUrl, dest))
            assertTrue("$name must match its published digest", UpdateChecker.sameCert(UpdateChecker.sha256OfFile(dest)!!, asset.sha256))
            println("live: $name ok, ${dest.length()} bytes, ${asset.sha256.take(16)}...")
            dest.delete()
        }
    }
}
