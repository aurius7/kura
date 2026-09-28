package aurius.kura

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for restore rollback.
 *
 * The bug these guard: a backup archive that is valid but truncated partway
 * through used to leave every entry restored before the failure point in the
 * vault, with no way to tell them apart from a complete restore. Rollback
 * depends on two things that are easy to break by accident, so both are pinned
 * here: that the journal accounts for every mutation, and that the undo order
 * is the order the vault actually needs.
 */
class RestoreJournalTest {

    private fun ghost(name: String) = RestoreGhost(name, "image/jpeg", 100, 200, 0, listOf("a"), 0)

    @Test
    fun emptyJournalProducesNoUndoSteps() {
        val j = RestoreJournal()
        assertTrue(j.isEmpty)
        assertEquals(emptyList<RestoreUndo>(), j.undoPlan())
    }

    @Test
    fun everyCreatedRowAndFileIsUndone() {
        val j = RestoreJournal()
        j.createdRow(1L)
        j.createdFile("a.jpg")
        j.createdRow(2L)
        j.createdFile("b.mp4")

        val plan = j.undoPlan()
        val droppedRows = plan.filterIsInstance<RestoreUndo.DropRow>().map { it.id }
        val shredded = plan.filterIsInstance<RestoreUndo.ShredFile>().map { it.name }

        assertEquals(listOf(2L, 1L), droppedRows)
        assertEquals(listOf("b.mp4", "a.jpg"), shredded)
    }

    @Test
    fun rowsAndFilesAreUndoneNewestFirst() {
        val j = RestoreJournal()
        j.createdRow(1L)
        j.createdFile("one")
        j.createdRow(2L)
        j.createdFile("two")

        // Steps are grouped by kind, not interleaved: rows first so nothing
        // still references a file, then files. Within each group a restore names
        // entries in commit order, so reversing means a partially applied
        // restore unwinds in the opposite order to how it was built.
        assertEquals(
            listOf(
                RestoreUndo.DropRow(2L),
                RestoreUndo.DropRow(1L),
                RestoreUndo.ShredFile("two"),
                RestoreUndo.ShredFile("one")
            ),
            j.undoPlan()
        )
    }

    @Test
    fun dropsPrecedeReinsertionsSoGhostsCannotCollide() {
        val j = RestoreJournal()
        // A ghost row is deleted and replaced during the restore, so the new row
        // must be gone before the old one comes back.
        j.ghost(ghost("old.jpg"))
        j.createdRow(10L)
        j.createdFile("new.jpg")

        val plan = j.undoPlan()
        val dropIdx = plan.indexOfFirst { it is RestoreUndo.DropRow }
        val ghostIdx = plan.indexOfFirst { it is RestoreUndo.ReinsertGhost }
        val shredIdx = plan.indexOfFirst { it is RestoreUndo.ShredFile }

        assertTrue("row must be dropped before its ghost returns", dropIdx < ghostIdx)
        assertTrue("replacement file must be shredded before the ghost returns", shredIdx < ghostIdx)
    }

    @Test
    fun tagsMergedIntoOlderRowsAreReverted() {
        val j = RestoreJournal()
        j.createdRow(5L)
        j.merge(RestoreMerge(42L, listOf("old_tag"), false))
        j.merge(RestoreMerge(43L, listOf("x", "y"), true))

        val plan = j.undoPlan()
        val reverts = plan.filterIsInstance<RestoreUndo.RevertMerge>()

        assertEquals(2, reverts.size)
        // Reversed: the last merge applied is the first undone.
        assertEquals(43L, reverts[0].merge.id)
        assertEquals(listOf("x", "y"), reverts[0].merge.priorTags)
        assertTrue(reverts[0].merge.priorFavorite)
        assertEquals(42L, reverts[1].merge.id)
        assertEquals(listOf("old_tag"), reverts[1].merge.priorTags)

        // Merges touch rows that were never removed, so they revert last.
        val lastDrop = plan.indexOfLast { it is RestoreUndo.DropRow }
        val firstRevert = plan.indexOfFirst { it is RestoreUndo.RevertMerge }
        assertTrue(firstRevert > lastDrop)
    }

    @Test
    fun everyGhostIsRestoredWithItsOriginalFields() {
        val j = RestoreJournal()
        j.ghost(ghost("orphan.mp4"))

        val reinserted = j.undoPlan().filterIsInstance<RestoreUndo.ReinsertGhost>().single().ghost
        assertEquals("orphan.mp4", reinserted.fileName)
        assertEquals("image/jpeg", reinserted.mime)
        assertEquals(100, reinserted.width)
        assertEquals(200, reinserted.height)
        assertEquals(listOf("a"), reinserted.tags)
    }

    @Test
    fun journalIsNoLongerEmptyOnceAnythingIsRecorded() {
        val j = RestoreJournal()
        j.merge(RestoreMerge(1L, emptyList(), false))
        assertTrue(!j.isEmpty)
    }
}
