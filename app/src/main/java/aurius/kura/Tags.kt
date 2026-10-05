package aurius.kura

import android.app.AlertDialog
import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

enum class TagCategory {
    GENERAL, CHARACTER, ARTIST, COPYRIGHT, META
}

/** Booru-style tag normalization: lowercase, spaces -> underscores, and category prefix support. */
object Tags {
    fun normalize(raw: String): String? {
        val trimmed = raw.trim().lowercase()
        // Fix accidental spaces after category colon e.g. "c: miku" -> "c:miku"
        val fixedColon = trimmed.replace(Regex("([a-z0-9]+):\\s+"), "$1:")
        val t = fixedColon.replace(Regex("\\s+"), "_")
        if (t.isEmpty()) return null
        if (t.length > 64) return null
        if (t.contains("*") || t.contains("%") || t.contains("/") || t.contains("\\")) return null
        if (!t.matches(Regex("[a-z0-9_\\-().+!':]+"))) return null
        return t
    }

    fun parseQuery(q: String): List<String> =
        q.trim().lowercase().split(Regex("\\s+"))
            .mapNotNull { normalize(it) }.distinct()

    fun parseList(s: String): List<String> {
        val tokens = if (s.contains(",")) s.split(",") else s.split(Regex("\\s+"))
        return tokens.mapNotNull { normalize(it) }.distinct()
    }

    fun category(tagName: String): TagCategory {
        val lower = tagName.lowercase()
        return when {
            lower.startsWith("c:") || lower.startsWith("char:") || lower.startsWith("character:") -> TagCategory.CHARACTER
            lower.startsWith("a:") || lower.startsWith("art:") || lower.startsWith("artist:") -> TagCategory.ARTIST
            lower.startsWith("s:") || lower.startsWith("series:") || lower.startsWith("copy:") || lower.startsWith("copyright:") -> TagCategory.COPYRIGHT
            lower.startsWith("m:") || lower.startsWith("meta:") -> TagCategory.META
            else -> TagCategory.GENERAL
        }
    }

    fun color(tagName: String, prefs: Prefs): Int {
        if (prefs.monochromeMode) {
            return ThemeUtils.readableOnTheme(prefs, 0xFFEEEEEE.toInt(), 0xFF3A3A3A.toInt())
        }
        return when (category(tagName)) {
            TagCategory.CHARACTER -> ThemeUtils.readableOnTheme(prefs, 0xFF80D8FF.toInt(), 0xFF0277BD.toInt()) // Crisp Cyan / Sky Blue
            TagCategory.ARTIST -> ThemeUtils.readableOnTheme(prefs, 0xFFFF8A80.toInt(), 0xFFC62828.toInt())    // Coral Red / Pink
            TagCategory.COPYRIGHT -> ThemeUtils.readableOnTheme(prefs, 0xFFB388FF.toInt(), 0xFF6A1B9A.toInt()) // Lavender Purple
            TagCategory.META -> ThemeUtils.readableOnTheme(prefs, 0xFFFFD180.toInt(), 0xFFB26A00.toInt())      // Warm Amber / Orange
            TagCategory.GENERAL -> ThemeUtils.readableOnTheme(prefs, 0xFFE0E0E0.toInt(), 0xFF424242.toInt())   // Clean Neutral (not pink!)
        }
    }

    /**
     * User-facing tag name with category prefix stripped.
     * e.g. "c:miku" -> "miku", "a:wlop" -> "wlop", "s:eva" -> "eva"
     * The color represents the category, keeping the UI clean.
     */
    fun displayName(tagName: String): String {
        val colonIdx = tagName.indexOf(':')
        if (colonIdx > 0) {
            val prefix = tagName.substring(0, colonIdx).lowercase()
            if (prefix in listOf("c", "char", "character", "a", "art", "artist", "s", "series", "copy", "copyright", "m", "meta")) {
                val base = tagName.substring(colonIdx + 1)
                if (base.isNotEmpty()) return base
            }
        }
        return tagName
    }

    /**
     * Produces all database tag variants for a query term so searches
     * work seamlessly across 1-letter, short, full, and unprefixed forms.
     */
    fun variants(term: String): List<String> {
        val lower = term.lowercase()
        val colonIdx = lower.indexOf(':')
        if (colonIdx > 0) {
            val prefix = lower.substring(0, colonIdx)
            val base = lower.substring(colonIdx + 1)
            if (base.isEmpty()) return listOf(lower)
            return when (prefix) {
                "c", "char", "character" -> listOf("c:$base", "char:$base", "character:$base")
                "a", "art", "artist" -> listOf("a:$base", "art:$base", "artist:$base")
                "s", "series", "copy", "copyright" -> listOf("s:$base", "series:$base", "copy:$base", "copyright:$base")
                "m", "meta" -> listOf("m:$base", "meta:$base")
                else -> listOf(lower)
            }
        } else {
            return listOf(
                lower,
                "c:$lower", "char:$lower", "character:$lower",
                "a:$lower", "art:$lower", "artist:$lower",
                "s:$lower", "series:$lower", "copy:$lower", "copyright:$lower",
                "m:$lower", "meta:$lower"
            )
        }
    }

      /**
       * Ranks tag suggestions for a partially typed term.
       *
       * Search-as-you-type should feel like a booru site's tag autocomplete: the
       * tag someone is typing has to be reachable from any part of its name, not
       * only the front, and the closest match has to come first. Ranking is by
       * how the term lands in the tag, then by how many items use the tag, then
       * alphabetically so the order is stable.
       *
       * Match tiers, best first:
       *  1. the whole tag, e.g. `miku` for `miku`
       *  2. the start of the tag, e.g. `hatsu` for `hatsune_miku`
       *  3. the start of a word inside the tag, e.g. `miku` for `hatsune_miku`
       *  4. anywhere in the tag, e.g. `tsune` for `hatsune_miku`
       *
       * Candidates that do not contain the term at all are dropped, so this is
       * safe to call with a wider candidate pool than the caller intends to show.
       * Pure and unit-tested; the database only supplies the candidates.
       */
      fun rankMatches(term: String, candidates: List<Pair<String, Int>>, limit: Int): List<Pair<String, Int>> {
          val q = term.trim().lowercase().removePrefix("-")
          if (q.isEmpty() || limit <= 0) return emptyList()

          fun tier(name: String): Int {
              val shown = displayName(name).lowercase()
              return when {
                  shown == q || name.lowercase() == q -> 0
                  shown.startsWith(q) -> 1
                  // Underscore is what normalize() uses for spaces, so it is
                  // also where words inside a tag begin.
                  shown.split('_').any { it.startsWith(q) } -> 2
                  shown.contains(q) -> 3
                  else -> -1
              }
          }

          return candidates
              .mapNotNull { (name, count) -> val t = tier(name); if (t < 0) null else Triple(name, count, t) }
              .sortedWith(
                  compareBy<Triple<String, Int, Int>> { it.third }
                      .thenByDescending { it.second }
                      .thenBy { it.first }
              )
              .take(limit)
              .map { it.first to it.second }
      }

      /**
       * Interactive guide dialog explaining the Booru tag system,
       * category prefixes, color codes, and search tips.
       */

    fun showGuideDialog(context: Context, prefs: Prefs) {
        val density = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            background = ThemeUtils.cardBackground(prefs, dp(16).toFloat())
        }

        val scroll = ScrollView(context).apply {
            addView(root)
        }

        fun addHeader(text: String) {
            root.addView(TextView(context).apply {
                this.text = text
                textSize = 15f
                setTextColor(prefs.accentColor())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, dp(14), 0, dp(6))
            })
        }

        fun addTagItem(prefix: String, name: String, sampleTag: String, desc: String, examples: String) {
            val itemColor = color(sampleTag, prefs)
            val itemLayout = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                background = ThemeUtils.surfaceGlass(prefs, dp(12).toFloat(), 1)
                setPadding(dp(14), dp(10), dp(14), dp(10))
            }

            val titleRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val prefixTv = TextView(context).apply {
                text = "$prefix "
                textSize = 14f
                setTextColor(itemColor)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            titleRow.addView(prefixTv)

            val nameTv = TextView(context).apply {
                text = name
                textSize = 13f
                setTextColor(prefs.textColor())
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            titleRow.addView(nameTv)
            itemLayout.addView(titleRow)

            val descTv = TextView(context).apply {
                text = desc
                textSize = 12f
                setTextColor(prefs.textColorSecondary())
                setPadding(0, dp(2), 0, dp(2))
            }
            itemLayout.addView(descTv)

            val exTv = TextView(context).apply {
                text = "Type: $examples\n(Displays as colored tag without prefix)"
                textSize = 11f
                setTextColor(itemColor)
            }
            itemLayout.addView(exTv)

            root.addView(itemLayout, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) })
        }

        fun addTip(text: String) {
            root.addView(TextView(context).apply {
                this.text = text
                textSize = 12f
                setTextColor(prefs.textColorSecondary())
                setPadding(dp(4), dp(2), dp(4), dp(4))
            })
        }

        addHeader("Booru Tagging & Color System")
        root.addView(TextView(context).apply {
            text = "Prefixes categorize tags with colors. Once saved, the prefix is hidden and the tag displays cleanly in its category color:"
            textSize = 12f
            setTextColor(prefs.textColorSecondary())
            setPadding(dp(2), 0, dp(2), dp(10))
        })

        addTagItem(
            "🔵 c: or char:",
            "Character (Blue)",
            "c:miku",
            "Characters, waifus, or fictional figures.",
            "c:miku, char:rem, character:asuka"
        )
        addTagItem(
            "🔴 a: or art:",
            "Artist (Coral)",
            "a:wlop",
            "Illustrators, animators, or artists.",
            "a:wlop, art:shunya, artist:kantoku"
        )
        addTagItem(
            "🟣 s: or series:",
            "Series & Copyright (Purple)",
            "s:eva",
            "Anime, games, franchises, or origins.",
            "s:vocaloid, series:evangelion, copy:fate"
        )
        addTagItem(
            "🟠 m: or meta:",
            "Meta (Orange)",
            "m:highres",
            "Technical details, qualities, or sources.",
            "m:highres, meta:4k, m:wallpaper"
        )
        addTagItem(
            "⚪ (none)",
            "General (Neutral Gray)",
            "solo",
            "Actions, attributes, clothing, expressions.",
            "1girl, solo, smile, blue_hair"
        )

        addHeader("Search Tips")
        addTip("• Smart search: Searching 'miku' automatically matches c:miku, char:miku, and plain miku.")
        addTip("• Exclude tags: Type -tag (e.g. '-video -miku') to exclude matching items.")
        addTip("• Multiple tags: Separate with spaces to search for items matching ALL tags.")
        addTip("• Instant Autocomplete: Suggestions appear as you type with their category colors.")

        AlertDialog.Builder(context)
            .setTitle("Tagging & Color Guide")
            .setView(scroll)
            .setPositiveButton("Got it", null)
            .show()
    }
}
