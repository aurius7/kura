package aurius.kura

import org.junit.Assert.*
import org.junit.Test

class TagsTest {

    @Test
    fun testTagNormalization() {
        assertEquals("c:miku", Tags.normalize("c:miku"))
        assertEquals("c:miku", Tags.normalize("c: miku"))
        assertEquals("char:hatsune_miku", Tags.normalize("char: Hatsune Miku"))
        assertEquals("a:wlop", Tags.normalize("a: wlop"))
        assertEquals("s:neon_genesis_evangelion", Tags.normalize("s: Neon Genesis Evangelion"))
        assertEquals("1girl", Tags.normalize("  1GIRL  "))
        assertNull(Tags.normalize(""))
        assertNull(Tags.normalize("invalid*char"))
    }

    @Test
    fun testTagCategories() {
        // Character (1-letter and full)
        assertEquals(TagCategory.CHARACTER, Tags.category("c:miku"))
        assertEquals(TagCategory.CHARACTER, Tags.category("char:miku"))
        assertEquals(TagCategory.CHARACTER, Tags.category("character:miku"))

        // Artist (1-letter and full)
        assertEquals(TagCategory.ARTIST, Tags.category("a:wlop"))
        assertEquals(TagCategory.ARTIST, Tags.category("art:wlop"))
        assertEquals(TagCategory.ARTIST, Tags.category("artist:wlop"))

        // Copyright / Series (1-letter and full)
        assertEquals(TagCategory.COPYRIGHT, Tags.category("s:eva"))
        assertEquals(TagCategory.COPYRIGHT, Tags.category("series:eva"))
        assertEquals(TagCategory.COPYRIGHT, Tags.category("copy:eva"))
        assertEquals(TagCategory.COPYRIGHT, Tags.category("copyright:eva"))

        // Meta (1-letter and full)
        assertEquals(TagCategory.META, Tags.category("m:highres"))
        assertEquals(TagCategory.META, Tags.category("meta:highres"))

        // General
        assertEquals(TagCategory.GENERAL, Tags.category("1girl"))
        assertEquals(TagCategory.GENERAL, Tags.category("smile"))
        assertEquals(TagCategory.GENERAL, Tags.category("blue_hair"))
    }

    @Test
    fun testSearchVariants() {
        val unprefixedVariants = Tags.variants("miku")
        assertTrue(unprefixedVariants.contains("miku"))
        assertTrue(unprefixedVariants.contains("c:miku"))
        assertTrue(unprefixedVariants.contains("char:miku"))
        assertTrue(unprefixedVariants.contains("character:miku"))
        assertTrue(unprefixedVariants.contains("a:miku"))

        val charVariants = Tags.variants("c:miku")
        assertEquals(listOf("c:miku", "char:miku", "character:miku"), charVariants)

        val fullCharVariants = Tags.variants("character:miku")
        assertEquals(listOf("c:miku", "char:miku", "character:miku"), fullCharVariants)

        val artistVariants = Tags.variants("a:wlop")
        assertEquals(listOf("a:wlop", "art:wlop", "artist:wlop"), artistVariants)

        val seriesVariants = Tags.variants("s:fate")
        assertEquals(listOf("s:fate", "series:fate", "copy:fate", "copyright:fate"), seriesVariants)

        val metaVariants = Tags.variants("m:highres")
        assertEquals(listOf("m:highres", "meta:highres"), metaVariants)
    }

    @Test
    fun testParseList() {
        val parsed = Tags.parseList("c:miku, a:wlop, s:vocaloid, solo, highres")
        assertEquals(listOf("c:miku", "a:wlop", "s:vocaloid", "solo", "highres"), parsed)
    }

    @Test
    fun testDisplayName() {
        assertEquals("miku", Tags.displayName("c:miku"))
        assertEquals("miku", Tags.displayName("char:miku"))
        assertEquals("miku", Tags.displayName("character:miku"))
        assertEquals("wlop", Tags.displayName("a:wlop"))
        assertEquals("wlop", Tags.displayName("art:wlop"))
        assertEquals("vocaloid", Tags.displayName("s:vocaloid"))
        assertEquals("highres", Tags.displayName("m:highres"))
        assertEquals("1girl", Tags.displayName("1girl"))
        assertEquals("solo", Tags.displayName("solo"))
    }
}
