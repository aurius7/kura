package aurius.kura

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Item(
    val id: Long, val fileName: String, val mime: String,
    val width: Int, val height: Int, val durationMs: Int,
    val dateAdded: Long, val favorite: Boolean, val tags: List<String> = emptyList(),
    val rotation: Int = 0
)

/**
 * Private SQLite database stored in app-private sandbox storage (`allowBackup="false"`).
 * Protected at rest by Android Credential-Encrypted (CE) storage with File-Based Encryption (FBE)
 * and strict Linux user/group permissions (0600).
 *
 * Media payloads are additionally encrypted with AES-256-GCM HKDF in app-private storage.
 *
 * The backing helper is dynamically resolved from [VaultLock.isDecoy] on every access instead
 * of being bound once in the constructor. Binding it once meant a BooruDb created
 * under the real vault kept its vault.db handle after a decoy unlock, so a
 * decoy-PIN session still read (and could modify) the real vault.
 */
class BooruDb(private val appCtx: Context) {

    private class Helper(ctx: Context, name: String) : SQLiteOpenHelper(ctx, name, null, 1) {
        /**
         * WAL lets the grid keep reading while an import/restore is writing.
         * Without it every bulk insert blocked all readers for its duration.
         *
         * Applied in `onConfigure` and guarded, because an exception here would
         * propagate out of `getDatabase()` into whichever activity first touched
         * the database. This previously lived in the constructor unguarded, which
         * crashed the app on the first launch after a fresh install - the one
         * moment the database file does not exist yet and WAL actually has to be
         * set up.
         */
        override fun onConfigure(db: SQLiteDatabase) {
            super.onConfigure(db)
            try {
                setWriteAheadLoggingEnabled(true)
            } catch (_: Throwable) {
                // Journal mode is an optimisation, never a correctness
                // requirement; fall back to the default and carry on.
            }
        }

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE items(id INTEGER PRIMARY KEY AUTOINCREMENT, file_name TEXT UNIQUE, mime TEXT, width INT, height INT, duration_ms INT, date_added INT, favorite INT DEFAULT 0, hash TEXT, rotation INT DEFAULT 0)")
            db.execSQL("CREATE TABLE tags(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT UNIQUE, count INT DEFAULT 0)")
            db.execSQL("CREATE TABLE item_tags(item_id INT, tag_id INT, PRIMARY KEY(item_id, tag_id))")
            db.execSQL("CREATE INDEX idx_it_item ON item_tags(item_id)")
            db.execSQL("CREATE INDEX idx_it_tag ON item_tags(tag_id)")
            db.execSQL("CREATE INDEX idx_items_date ON items(date_added DESC)")
            db.execSQL("CREATE INDEX idx_items_fav ON items(favorite)")
            db.execSQL("CREATE INDEX idx_items_hash ON items(hash)")
            // Tag garbage collection runs `DELETE FROM tags WHERE count<=0` after
            // every edit; without this it full-scanned the tags table each time.
            db.execSQL("CREATE INDEX idx_tags_count ON tags(count)")
        }

        /**
         * Additive migration for databases created by older versions.
         *
         * The previous implementation issued `ALTER TABLE ... ADD COLUMN` and
         * `CREATE INDEX` on *every* open and swallowed the duplicate-column
         * exception, so each open paid for up to two failed statements.
         * `PRAGMA table_info` makes each check a cheap read instead.
         */
        override fun onOpen(db: SQLiteDatabase) {
            super.onOpen(db)
            if (!columnExists(db, "items", "hash")) {
                try { db.execSQL("ALTER TABLE items ADD COLUMN hash TEXT") } catch (_: Exception) {}
            }
            if (!columnExists(db, "items", "rotation")) {
                try { db.execSQL("ALTER TABLE items ADD COLUMN rotation INT DEFAULT 0") } catch (_: Exception) {}
            }
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_items_hash ON items(hash)") } catch (_: Exception) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_tags_count ON tags(count)") } catch (_: Exception) {}
        }

        private fun columnExists(db: SQLiteDatabase, table: String, column: String): Boolean {
            return try {
                db.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    val nameIdx = c.getColumnIndex("name")
                    if (nameIdx < 0) return false
                    while (c.moveToNext()) if (c.getString(nameIdx) == column) return true
                    false
                }
            } catch (_: Exception) { false }
        }

        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {
            onOpen(db)
        }
    }

    @Volatile
    private var helper: Helper? = null
    @Volatile
    private var helperIsDecoy: Boolean? = null

    @Synchronized
    private fun h(): Helper {
        val wantDecoy = VaultLock.isDecoy
        val existing = helper
        if (existing != null && helperIsDecoy == wantDecoy) return existing
        // Vault session changed (lock, or a decoy unlock). Drop the old handle
        // before opening the new file so no query can land on the stale DB.
        try { existing?.close() } catch (_: Exception) {}
        val created = Helper(appCtx, if (wantDecoy) "vault_decoy.db" else "vault.db")
        helper = created
        helperIsDecoy = wantDecoy
        return created
    }

    private val readableDatabase: SQLiteDatabase get() = h().readableDatabase
    private val writableDatabase: SQLiteDatabase get() = h().writableDatabase

    /**
     * Runs [block] inside a single transaction.
     *
     * Bulk operations (the pre-restore ghost sweep) previously issued one write
     * per row, each its own implicit transaction and fsync.
     */
    fun runInTransaction(block: () -> Unit) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            try { db.endTransaction() } catch (_: Exception) {}
        }
    }

    @Synchronized
    fun close() {
        try { helper?.close() } catch (_: Exception) {}
        helper = null
        helperIsDecoy = null
    }

    fun insertItem(fileName: String, mime: String, w: Int, h: Int, dur: Int, tags: List<String>, hash: String = "", rotation: Int = 0): Long {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("file_name", fileName); put("mime", mime)
            put("width", w); put("height", h); put("duration_ms", dur)
            put("date_added", System.currentTimeMillis()); put("favorite", 0)
            if (hash.isNotEmpty()) put("hash", hash)
            put("rotation", rotation)
        }
        val id = db.insertOrThrow("items", null, cv)
        setTags(id, tags)
        return id
    }

    fun findByHash(hash: String): Item? {
        if (hash.isEmpty()) return null
        return try {
            readableDatabase.rawQuery(
                "SELECT id, file_name, mime, width, height, duration_ms, date_added, favorite, rotation FROM items WHERE hash=? LIMIT 1",
                arrayOf(hash)
            ).use { c ->
                if (c.moveToFirst()) {
                    row(c, tagsFor(c.getLong(0)))
                } else null
            }
        } catch (_: Exception) { null }
    }

    fun setTags(itemId: Long, tags: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // decrement old
            db.rawQuery("SELECT t.id FROM tags t JOIN item_tags jt ON jt.tag_id=t.id WHERE jt.item_id=?", arrayOf(itemId.toString())).use { c ->
                while (c.moveToNext()) {
                    val tid = c.getLong(0)
                    db.execSQL("UPDATE tags SET count=count-1 WHERE id=?", arrayOf(tid))
                }
            }
            db.delete("item_tags", "item_id=?", arrayOf(itemId.toString()))
            db.execSQL("DELETE FROM tags WHERE count<=0")

            val distinctTags = tags.mapNotNull { Tags.normalize(it) }.distinct()
            for (name in distinctTags) {
                var tid: Long? = null
                db.rawQuery("SELECT id FROM tags WHERE name=?", arrayOf(name)).use { c ->
                    if (c.moveToFirst()) tid = c.getLong(0)
                }
                if (tid == null) {
                    val cv = ContentValues().apply { put("name", name); put("count", 1) }
                    tid = db.insertOrThrow("tags", null, cv)
                } else {
                    db.execSQL("UPDATE tags SET count=count+1 WHERE id=?", arrayOf(tid))
                }
                db.execSQL("INSERT OR IGNORE INTO item_tags(item_id, tag_id) VALUES(?,?)", arrayOf(itemId, tid))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun addTags(itemId: Long, newTags: List<String>) {
        if (newTags.isEmpty()) return
        val current = tagsFor(itemId)
        val combined = (current + newTags).distinct()
        setTags(itemId, combined)
    }

    fun tagsFor(itemId: Long): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery(
            "SELECT t.name FROM tags t JOIN item_tags jt ON jt.tag_id=t.id WHERE jt.item_id=? ORDER BY t.name",
            arrayOf(itemId.toString())
        ).use { c -> while (c.moveToNext()) out.add(c.getString(0)) }
        return out
    }

    /**
     * All tags for every item, keyed by item id, in a single query.
     *
     * Backup export previously called `tagsFor` once per item, so a 50k-item
     * vault issued 50k queries before the first byte of the archive was written.
     */
    fun allTagsByItemId(): Map<Long, List<String>> {
        val out = HashMap<Long, MutableList<String>>()
        readableDatabase.rawQuery(
            "SELECT jt.item_id, t.name FROM item_tags jt JOIN tags t ON t.id=jt.tag_id ORDER BY jt.item_id, t.name",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.getOrPut(c.getLong(0)) { mutableListOf() }.add(c.getString(1))
            }
        }
        return out
    }

    fun tagsWithCountFor(itemId: Long): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery(
            "SELECT t.name, t.count FROM tags t JOIN item_tags jt ON jt.tag_id=t.id WHERE jt.item_id=? ORDER BY t.count DESC, t.name ASC",
            arrayOf(itemId.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
        }
        return out
    }

    /**
     * Powerful booru search:
     * - AND search for positive tags
     * - NOT search for negative tags (e.g. "-cat")
     * - Favorites toggle
     * - Configurable sorting (date_desc, date_asc, random)
     */
    fun search(query: List<String>, favOnly: Boolean, sortOrder: String = "date_desc", category: String = "all", limit: Int = 2000): List<Item> {
        val db = readableDatabase
        val out = mutableListOf<Item>()

        val include = mutableListOf<String>()
        val exclude = mutableListOf<String>()
        for (q in query) {
            if (q.startsWith("-") && q.length > 1) {
                exclude.add(q.substring(1))
            } else {
                include.add(q)
            }
        }

        val favClause = if (favOnly) "AND i.favorite=1" else ""
        // `ORDER BY RANDOM()` forces a full table scan plus an unindexed sort on
        // every load, and the Flow tab is the default landing view. The rows are
        // shuffled in Kotlin instead, which lets the `date_added` index serve
        // the fetch and costs only the sort of an already-limited result set.
        val shuffle = category.lowercase() in listOf("flow", "reels") || sortOrder == "random"
        val orderClause = if (shuffle) {
            "ORDER BY i.date_added DESC"
        } else {
            when (sortOrder) {
                "date_asc" -> "ORDER BY i.date_added ASC"
                else -> "ORDER BY i.date_added DESC"
            }
        }

        val whereClauses = mutableListOf("1=1")
        val args = mutableListOf<String>()

        when (category.lowercase()) {
            "flow", "reels" -> { /* Include all images, videos, and gifs */ }
            "photos" -> whereClauses.add("(i.mime LIKE 'image/%' AND i.mime != 'image/gif' AND i.mime NOT LIKE '%gif%')")
            "videos" -> whereClauses.add("i.mime LIKE 'video/%'")
            "gifs" -> whereClauses.add("(i.mime = 'image/gif' OR i.mime LIKE '%gif%')")
        }

        if (include.isNotEmpty()) {
            for (inc in include) {
                val vars = Tags.variants(inc)
                val placeholders = vars.joinToString(",") { "?" }
                whereClauses.add("EXISTS (SELECT 1 FROM item_tags jt JOIN tags t ON t.id=jt.tag_id WHERE jt.item_id=i.id AND t.name IN ($placeholders))")
                args.addAll(vars)
            }
        }

        if (exclude.isNotEmpty()) {
            for (exc in exclude) {
                val vars = Tags.variants(exc)
                val placeholders = vars.joinToString(",") { "?" }
                whereClauses.add("NOT EXISTS (SELECT 1 FROM item_tags jt JOIN tags t ON t.id=jt.tag_id WHERE jt.item_id=i.id AND t.name IN ($placeholders))")
                args.addAll(vars)
            }
        }

        val sql = "SELECT i.id, i.file_name, i.mime, i.width, i.height, i.duration_ms, i.date_added, i.favorite, i.rotation FROM items i " +
                "WHERE ${whereClauses.joinToString(" AND ")} $favClause " +
                "$orderClause LIMIT ?"
        args.add(limit.toString())

        db.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out.add(row(c, emptyList()))
            }
        }
        if (shuffle) out.shuffle()
        return out
    }

    fun allItems(): List<Item> {
        val out = mutableListOf<Item>()
        readableDatabase.rawQuery(
            "SELECT id, file_name, mime, width, height, duration_ms, date_added, favorite, rotation FROM items ORDER BY id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(row(c, emptyList()))
            }
        }
        return out
    }

    fun get(itemId: Long): Item? {
        val item = readableDatabase.rawQuery(
            "SELECT id, file_name, mime, width, height, duration_ms, date_added, favorite, rotation FROM items WHERE id=?",
            arrayOf(itemId.toString())
        ).use { c ->
            if (!c.moveToFirst()) return null
            row(c, emptyList())
        }
        return item.copy(tags = tagsFor(itemId))
    }

    fun setFavorite(itemId: Long, fav: Boolean) {
        writableDatabase.execSQL("UPDATE items SET favorite=? WHERE id=?", arrayOf(if (fav) 1 else 0, itemId))
    }

    fun delete(itemId: Long): String? {
        var name: String? = null
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("SELECT file_name FROM items WHERE id=?", arrayOf(itemId.toString())).use { c ->
                if (c.moveToFirst()) name = c.getString(0)
            }
            db.rawQuery("SELECT tag_id FROM item_tags WHERE item_id=?", arrayOf(itemId.toString())).use { c ->
                while (c.moveToNext()) {
                    db.execSQL("UPDATE tags SET count=count-1 WHERE id=?", arrayOf(c.getLong(0)))
                }
            }
            db.delete("item_tags", "item_id=?", arrayOf(itemId.toString()))
            db.delete("items", "id=?", arrayOf(itemId.toString()))
            db.execSQL("DELETE FROM tags WHERE count<=0")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return name
    }

    /**
     * Candidate tags for the suggestion row.
     *
     * Matching is deliberately broader than the query itself: a tag counts as a
     * candidate when the term matches the start of the name, the start of the
     * name after a category prefix (`c:hatsune_miku` for `hatsu`), or anywhere
     * inside the name (`miku` for `hatsune_miku`). Booru sites behave this way,
     * and prefix-only matching hid the tag someone was typing.
     *
     * The SQL only selects candidates; [Tags.rankMatches] orders them, so exact
     * matches surface ahead of popular-but-partial ones.
     */
    fun suggestTags(term: String, limit: Int = 15): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        val clean = term.trim().lowercase()
            .removePrefix("-")
            .replace("%", "")
            .replace("\\", "")
            .replace("_", "\\_")
        if (clean.isEmpty()) return out
        val db = readableDatabase
        // Over-fetch, because ranking happens after the query and an infix match
        // can pull in many rows that the ranking then discards.
        val pool = (limit * 6).coerceIn(60, 300)
        db.rawQuery(
            "SELECT name, count FROM tags WHERE " +
                "(instr(name, ':') > 0 AND substr(name, instr(name, ':') + 1) LIKE ? ESCAPE '\\') " +
                "OR name LIKE ? ESCAPE '\\' " +
                "OR name LIKE ? ESCAPE '\\' " +
                "ORDER BY count DESC LIMIT ?",
            arrayOf("$clean%", "$clean%", "%$clean%", pool.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
        }
        return Tags.rankMatches(clean, out, limit)
    }

    fun allTags(limit: Int = 50): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        readableDatabase.rawQuery("SELECT name, count FROM tags ORDER BY count DESC LIMIT ?", arrayOf(limit.toString())).use { c ->
            while (c.moveToNext()) out.add(c.getString(0) to c.getInt(1))
        }
        return out
    }

    fun count(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM items", null).use { c ->
            return if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun rotateItem(itemId: Long, deltaDegrees: Int = 90): Int {
        val item = get(itemId) ?: return 0
        val newRot = ((item.rotation + deltaDegrees) % 360 + 360) % 360
        val swap = (deltaDegrees % 180 != 0)
        val newW = if (swap) item.height else item.width
        val newH = if (swap) item.width else item.height
        val cv = ContentValues().apply {
            put("rotation", newRot)
            put("width", newW)
            put("height", newH)
        }
        writableDatabase.update("items", cv, "id = ?", arrayOf(itemId.toString()))
        return newRot
    }

    private fun row(c: android.database.Cursor, tags: List<String>): Item {
        val rotIdx = c.getColumnIndex("rotation")
        val rot = if (rotIdx >= 0) c.getInt(rotIdx) else 0
        return Item(
            c.getLong(0), c.getString(1), c.getString(2), c.getInt(3),
            c.getInt(4), c.getInt(5), c.getLong(6), c.getInt(7) == 1, tags,
            rot
        )
    }
}
