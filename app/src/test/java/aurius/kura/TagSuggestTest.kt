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
