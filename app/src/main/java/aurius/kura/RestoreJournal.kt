package aurius.kura

/**
 * A row that predated a restore and whose tags/favorite were merged into.
 */
data class RestoreMerge(val id: Long, val priorTags: List<String>, val priorFavorite: Boolean)

/**
 * A row that predated a restore and was deleted because its file was missing
 * or zero-length, captured so it can be put back.
 */
data class RestoreGhost(
    val fileName: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val durationMs: Int,
    val tags: List<String>,
    val rotation: Int
)

/** One step of an undo, tagged so the caller knows which vault operation to run. */
sealed class RestoreUndo {
    data class DropRow(val id: Long) : RestoreUndo()
    data class ShredFile(val name: String) : RestoreUndo()
    data class ReinsertGhost(val ghost: RestoreGhost) : RestoreUndo()
    data class RevertMerge(val merge: RestoreMerge) : RestoreUndo()
}

/**
 * Everything one restore pass created or modified, so that an abort can be
 * undone.
 *
 * A restore only ever *adds*: every entry is stored under a fresh UUID name and
 * a hash collision merges tags into the existing row rather than replacing the
 * file. That is what makes rollback possible without staging anything on disk --
 * the undo list is just row ids and file names, so it costs no extra space.
 *
 * Kept free of Android and database types so the ordering rules in [undoPlan]
 * are unit-testable on the JVM.
 */
class RestoreJournal {
    private val rows = mutableListOf<Long>()
    private val files = mutableListOf<String>()
    private val ghostRows = mutableListOf<RestoreGhost>()
    private val mergedRows = mutableListOf<RestoreMerge>()

    val isEmpty: Boolean
        get() = rows.isEmpty() && files.isEmpty() && ghostRows.isEmpty() && mergedRows.isEmpty()

    fun createdRow(id: Long) { rows.add(id) }
    fun createdFile(name: String) { files.add(name) }
    fun ghost(g: RestoreGhost) { ghostRows.add(g) }
    fun merge(m: RestoreMerge) { mergedRows.add(m) }

    /**
     * The undo steps, in the order they must be applied.
     *
     * Newest first, and all drops happen before any reinsertion: a restored
     * ghost must never collide with a row that is still scheduled for deletion,
     * and no file may be shredded while a reinserted row still points at it.
     * Merges revert last because they touch rows that were never removed.
     *
     * Returns an empty list when the restore did nothing, which is the fast
     * path that avoids touching the database at all.
     */
    fun undoPlan(): List<RestoreUndo> {
        if (isEmpty) return emptyList()
        val plan = ArrayList<RestoreUndo>(rows.size + files.size + ghostRows.size + mergedRows.size)
        rows.asReversed().forEach { plan.add(RestoreUndo.DropRow(it)) }
        files.asReversed().forEach { plan.add(RestoreUndo.ShredFile(it)) }
        ghostRows.asReversed().forEach { plan.add(RestoreUndo.ReinsertGhost(it)) }
        mergedRows.asReversed().forEach { plan.add(RestoreUndo.RevertMerge(it)) }
        return plan
    }
}
