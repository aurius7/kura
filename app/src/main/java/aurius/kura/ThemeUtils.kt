package aurius.kura

import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable

object ThemeUtils {

    fun backdrop(prefs: Prefs): Drawable {
        return GradientDrawable().apply { setColor(prefs.bgColor()) }
    }

    fun cardBackground(prefs: Prefs, radius: Float = 14f): GradientDrawable {
        return GradientDrawable().apply {
            setColor(prefs.cardColor())
            cornerRadius = radius
            setStroke(1, prefs.cardBorderColor())
        }
    }

    fun surfaceGlass(prefs: Prefs, radius: Float = 18f, strokeWidth: Int = 1): GradientDrawable {
        return GradientDrawable().apply {
            setColor(prefs.surfaceColor())
            cornerRadius = radius
            if (strokeWidth > 0) {
                setStroke(strokeWidth, prefs.cardBorderColor())
            }
        }
    }

    fun buttonBackground(prefs: Prefs, selected: Boolean, radius: Float = 14f): GradientDrawable {
        return GradientDrawable().apply {
            if (selected) {
                setColor(prefs.accentColor())
                cornerRadius = radius
            } else {
                setColor(prefs.surfaceColor())
                cornerRadius = radius
                setStroke(1, prefs.cardBorderColor())
            }
        }
    }

    fun isLightFill(prefs: Prefs): Boolean {
        if (prefs.isLightTheme()) {
            // On light backgrounds near-white accents are remapped to dark neutrals
            // (see Prefs.accentColor), so only the genuinely light tints stay light.
            return prefs.accent == "orange" || prefs.accent == "green"
        }
        return prefs.monochromeMode || prefs.accent in listOf("orange", "green", "white")
    }

    /** Picks the variant of a decorative color that stays legible on the current theme. */
    fun readableOnTheme(prefs: Prefs, onDark: Int, onLight: Int): Int =
        if (prefs.isLightTheme()) onLight else onDark

    fun onLightFillColor(): Int = 0xFF111111.toInt()

    fun buttonTextColor(prefs: Prefs, selected: Boolean): Int {
        if (selected) {
            return if (isLightFill(prefs)) onLightFillColor() else Color.WHITE
        }
        return prefs.textColor()
    }

    fun fabBackground(prefs: Prefs): GradientDrawable {
        return GradientDrawable().apply {
            setColor(prefs.accentColor())
            cornerRadius = 100f
        }
    }

    fun importBarBackground(prefs: Prefs): GradientDrawable {
        return GradientDrawable().apply {
            setColor(prefs.accentColor())
            cornerRadius = 28f
        }
    }

    fun vibrateClick(view: android.view.View) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
            } else {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
        } catch (_: Exception) {}
    }

    fun vibrateTick(view: android.view.View) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
            } else {
                view.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            }
        } catch (_: Exception) {}
    }
}
