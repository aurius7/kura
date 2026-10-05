package aurius.kura

import org.junit.Assert.*
import org.junit.Test

class TagSuggestTest {

    private fun names(vararg pairs: Pair<String, Int>) = pairs.map { it.first to it.second }

    private fun rank(term: String, vararg pairs: Pair<String, Int>) =
        Tags.rankMatches(term, pairs.toList(), 15).map { it.first }

    @Test
    fun testWordInsideTagIsSuggestable() {
        // Typing the tail of a tag must still reach it, the way booru sites do.
        assertEquals(
            listOf("c:hatsune_miku"),
            rank("miku", "c:hatsune_miku" to 4)
        )
        assertEquals(
            listOf("c:hatsune_miku"),
            rank("tsune", "c:hatsune_miku" to 4)
        )
    }

    @Test
    fun testPartialPrefixIsSuggestable() {
        assertEquals(
            listOf("c:hatsune_miku"),
            rank("hatsu", "c:hatsune_miku" to 4)
        )
    }

    @Test
    fun testExactMatchRanksFirst() {
        assertEquals(
            listOf("miku", "c:miku_v4", "c:hatsune_miku"),
            rank(
                "miku",
                "c:hatsune_miku" to 400,
                "c:miku_v4" to 900,
                "miku" to 1
            )
        )
    }

    @Test
    fun testPrefixOutranksWordStartOutranksInfix() {
        assertEquals(
            listOf("miku_append", "hatsune_miku_append", "hatsune_miku"),
            rank(
                "miku",
                "hatsune_miku_append" to 500,
                "hatsune_miku" to 300,
                "miku_append" to 100
            )
        )
    }

    @Test
    fun testPopularityBreaksTiesWithinATier() {
        assertEquals(
            listOf("c:miku", "miku_hatsune"),
            rank(
                "miku",
                "miku_hatsune" to 2,
                "c:miku" to 50
            )
        )
    }

    @Test
    fun testCategoryPrefixMatchesTermAfterColon() {
        assertEquals(
            listOf("c:hatsune_miku", "character:hatsune_miku", "c:divine_hatsune"),
            rank(
                "hatsu",
                "character:hatsune_miku" to 8,
                "c:hatsune_miku" to 9,
                "c:divine_hatsune" to 40
            )
        )
    }

    @Test
    fun testNegatedTermStillSuggests() {
        // "-miku" excludes a tag; the suggestions for it are still "miku" tags.
        assertEquals(
            listOf("c:hatsune_miku"),
            rank("-miku", "c:hatsune_miku" to 4)
        )
    }

    @Test
    fun testCaseIsIgnored() {
        assertEquals(
            listOf("c:hatsune_miku"),
            rank("MIKU", "c:hatsune_miku" to 4)
        )
    }

    @Test
    fun testUnrelatedCandidatesAreDropped() {
        assertEquals(
            emptyList<String>(),
            rank("miku", "c:rongamehua" to 20, "s:eva" to 30)
        )
    }

    @Test
    fun testEmptyTermAndLimitYieldNothing() {
        assertTrue(Tags.rankMatches("", listOf("c:miku" to 1), 15).isEmpty())
        assertTrue(Tags.rankMatches("  ", listOf("c:miku" to 1), 15).isEmpty())
        assertTrue(Tags.rankMatches("miku", listOf("c:miku" to 1), 0).isEmpty())
    }

    @Test
    fun testLimitTruncates() {
        assertEquals(
            names("c:miku" to 1, "c:miku_2" to 3),
            Tags.rankMatches("miku", names("c:miku_1" to 2, "c:miku" to 1, "c:miku_2" to 3), 2)
        )
    }

    @Test
    fun testOrderIsStableForEqualCount() {
        assertEquals(
            listOf("miku_hatsune", "miku_v4", "c:hatsune_miku"),
            rank("miku", "miku_v4" to 7, "miku_hatsune" to 7, "c:hatsune_miku" to 7)
        )
    }
}

class TagGlobTest {

    @Test
    fun testWildcardDetection() {
        assertTrue(Tags.isGlob("small*"))
        assertTrue(Tags.isGlob("*ass"))
        assertTrue(Tags.isGlob("ta*1"))
        assertFalse(Tags.isGlob("miku"))
        assertFalse(Tags.isGlob(""))
    }

    @Test
    fun testGlobBecomesLikePattern() {
        assertEquals("ta%1", Tags.globToLike("ta*1"))
        assertEquals("%ass", Tags.globToLike("*ass"))
        assertEquals("small%", Tags.globToLike("small*"))
        assertEquals("hats%\\_miku", Tags.globToLike("hats*_miku"))
    }

    @Test
    fun testGlobEscapesLikeWildcards() {
        // A tag can hold % and _, so they have to be literal in the pattern or
        // "100%" would match every tag in the vault.
        assertEquals("100\\%\\_cotton", Tags.globToLike("100%_cotton"))
        assertEquals("a\\%b", Tags.globToLike("a%b"))
    }

    @Test
    fun testGlobIsLowercased() {
        assertEquals("hats%", Tags.globToLike("Hats*"))
    }

    @Test
    fun testGlobSuggestionsAreOrderedByPopularity() {
        // rule34 orders its autocomplete purely by post count.
        val cands = listOf("small_breasts" to 12, "smaller_dom" to 900, "small" to 40)
        assertEquals(
            listOf("smaller_dom", "small", "small_breasts"),
            Tags.rankMatches("small*", cands, 10).map { it.first }
        )
    }

    @Test
    fun testGlobRankingRespectsLimit() {
        val cands = listOf("a" to 1, "b" to 2, "c" to 3)
        assertEquals(listOf("c" to 3, "b" to 2), Tags.rankMatches("*", cands, 2))
    }
}

class TagSuggestFilterTest {

    @Test
    fun testTheTagBeingTypedIsStillOffered() {
        // Completing "lucy" into the stored "c:lucy" is the point of the row.
        assertTrue(Tags.shouldSuggest("lucy", "c:lucy", listOf("lucy")))
        assertTrue(Tags.shouldSuggest("lucy", "lucy", listOf("lucy")))
        assertTrue(Tags.shouldSuggest("hatsune_miku", "c:hatsune_miku", listOf("hatsune_miku")))
    }

    @Test
    fun testExactCategorySpellingOfTheTermIsOffered() {
        assertTrue(Tags.shouldSuggest("c:lucy", "c:lucy", listOf("c:lucy")))
        assertTrue(Tags.shouldSuggest("character:lucy", "character:lucy", listOf("character:lucy")))
    }

    @Test
    fun testOtherCommittedTagsAreNotOfferedAgain() {
        assertFalse(Tags.shouldSuggest("lucy", "c:miku", listOf("miku")))
        assertFalse(Tags.shouldSuggest("lucy", "miku", listOf("miku")))
        assertFalse(Tags.shouldSuggest("", "c:miku", listOf("miku")))
    }

    @Test
    fun testCommittedTagStaysHiddenOnceTheTokenIsFinished() {
        // Trailing space means the token is done, so lucy is a duplicate now.
        assertFalse(Tags.shouldSuggest("", "c:lucy", listOf("lucy")))
    }

    @Test
    fun testNegatedTermStillOffersItsTag() {
        assertTrue(Tags.shouldSuggest("-lucy", "c:lucy", listOf("-lucy")))
    }

    @Test
    fun testUnrelatedTagIsOffered() {
        assertTrue(Tags.shouldSuggest("lucy", "c:lucy_horn", listOf("lucy")))
        assertTrue(Tags.shouldSuggest("lu", "c:lucy", listOf("lu")))
    }
}

class UpdateCheckerTest {

    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    private val good = """
        {"versionName":"1.0.3","versionCode":17,
         "flavors":{
           "offline":{"apkUrl":"https://example.invalid/kura.apk","sha256":"$a","sizeBytes":2961972},
           "online":{"apkUrl":"https://example.invalid/kura-online.apk","sha256":"$b","sizeBytes":2964686}},
         "notesUrl":"https://example.invalid/notes"}
    """.trimIndent()

    @Test
    fun testParsesAWellFormedRelease() {
        val r = UpdateChecker.parseRelease(good)!!
        assertEquals("1.0.3", r.versionName)
        assertEquals(17, r.versionCode)
        assertEquals(2, r.flavors.size)
        assertEquals("https://example.invalid/kura.apk", r.flavors["offline"]!!.apkUrl)
        assertEquals(2964686L, r.flavors["online"]!!.sizeBytes)
    }

    @Test
    fun testEachFlavorIsOfferedItsOwnBuild() {
        val r = UpdateChecker.parseRelease(good)!!
        val mine = r.forCurrentFlavor()
        assertNotNull("this build must find its own entry", mine)
        assertEquals(64, mine!!.sha256.length)
        // The whole point: an offline build must never be handed the networked APK.
        assertNotEquals(r.flavors["offline"]!!.sha256, r.flavors["online"]!!.sha256)
    }

    @Test
    fun testRejectsIncompleteOrNonsense() {
        assertNull(UpdateChecker.parseRelease(""))
        assertNull(UpdateChecker.parseRelease("not json"))
        assertNull(UpdateChecker.parseRelease("{}"))
        assertNull(UpdateChecker.parseRelease("""{"versionCode":17}"""))
        assertNull(UpdateChecker.parseRelease("""{"versionCode":17,"flavors":{}}"""))
    }

    @Test
    fun testRejectsAnEntryWithoutAUsableDigest() {
        assertNull(
            UpdateChecker.parseRelease(
                """{"versionCode":17,"flavors":{"offline":{"apkUrl":"x","sha256":"abc"}}}"""
            )
        )
    }

    @Test
    fun testOnlyStrictlyNewerReleasesAreOffered() {
        assertTrue(UpdateChecker.isNewer(16, 17))
        assertFalse(UpdateChecker.isNewer(17, 17))
        assertFalse(UpdateChecker.isNewer(17, 16))
    }

    @Test
    fun testCertComparisonIgnoresCaseAndSeparators() {
        val k = "ad9059713b4d8d998c025f231023f18875699c7ef34b767bba6e5e6f114a1f3f"
        assertTrue(UpdateChecker.sameCert(k, k.uppercase()))
        assertTrue(UpdateChecker.sameCert(k, k.chunked(2).joinToString(":")))
        assertTrue(UpdateChecker.sameCert(k, " ${k.uppercase().chunked(2).joinToString(": ")} "))
        assertFalse(UpdateChecker.sameCert(k, "b".repeat(64)))
    }

    @Test
    fun testDigestIsComputedOverContent() {
        // Known SHA-256 of "abc".
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            UpdateChecker.sha256Hex("abc".toByteArray())
        )
    }
}
