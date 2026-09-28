package aurius.kura

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

object AppIconManager {
    data class IconOption(val key: String, val label: String, val aliasName: String, val drawableRes: Int)

    val ICONS = listOf(
        IconOption("kura", "Kura (蔵)", "IconKura", R.drawable.ic_icon_kura),
        IconOption("bust", "Bust Silhouette", "IconBust", R.drawable.ic_icon_bust),
        IconOption("curves", "Curves", "IconCurves", R.drawable.ic_icon_curves),
        IconOption("contour", "Contours", "IconContour", R.drawable.ic_icon_contour),
        IconOption("swimwear", "Swimwear", "IconSwimwear", R.drawable.ic_icon_swimwear),
        IconOption("hourglass", "Hourglass", "IconHourglass", R.drawable.ic_icon_hourglass),
        IconOption("sakura", "Sakura Bloom", "IconSakura", R.drawable.ic_icon_sakura),
        IconOption("kitsune", "Kitsune Fox", "IconKitsune", R.drawable.ic_icon_kitsune),
        IconOption("katana", "Katana Blade", "IconKatana", R.drawable.ic_icon_katana),
        IconOption("crescent", "Moon Crescent", "IconCrescent", R.drawable.ic_icon_crescent),
        IconOption("discreet", "Stealth Lock", "IconDiscreet", R.drawable.ic_icon_discreet)
    )

    /**
     * Writes [state] for [cn], but only when it is not already there. A redundant
     * write is not a no-op for the platform: it still makes the package manager
     * broadcast a component-state change, so skipping it keeps icon switching cheap
     * and avoids disturbing the app we are calling this from.
     */
    private fun applyState(pm: PackageManager, cn: ComponentName, state: Int) {
        try {
            if (pm.getComponentEnabledSetting(cn) == state) return
            pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
        } catch (_: Exception) {}
    }

    fun setAppIcon(context: Context, key: String) {
        val pm = context.packageManager
        val pkg = context.packageName

        val targetOpt = ICONS.firstOrNull { it.key == key } ?: ICONS[0]
        val targetCn = ComponentName(pkg, "$pkg.${targetOpt.aliasName}")

        // 1. Enable the newly chosen alias first to guarantee there is always an active launcher icon
        applyState(pm, targetCn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        // 2. Disable all other aliases
        for (opt in ICONS) {
            if (opt.key == targetOpt.key) continue
            applyState(
                pm,
                ComponentName(pkg, "$pkg.${opt.aliasName}"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            )
        }

        Prefs(context).appIcon = targetOpt.key
    }

    fun getActiveAlias(context: Context): String {
        val key = Prefs(context).appIcon
        return (ICONS.firstOrNull { it.key == key } ?: ICONS[0]).aliasName
    }

    /**
     * Converges the launcher onto exactly one enabled icon.
     *
     * The launcher icons are the [ICONS] activity-aliases. The base `.LockActivity`
     * has no MAIN/LAUNCHER intent-filter, so it never shows up as a second icon and
     * must stay enabled: the app starts it with an explicit intent from
     * `BaseVaultActivity.onResume` and `MainActivity`, and a disabled component
     * cannot be started explicitly (ActivityNotFoundException).
     *
     * Every alias is brought to the intended state rather than only the active one
     * being enabled, so an icon the user picked in an older build — one that has
     * since been renamed or removed — cannot leave a second stale icon behind. If the
     * stored key names an icon this build does not have, it is rewritten to the
     * default instead of being silently reinterpreted on every launch.
     */
    fun syncLauncher(context: Context) {
        val pm = context.packageManager
        val pkg = context.packageName
        applyState(
            pm,
            ComponentName(pkg, "$pkg.LockActivity"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        )

        val stored = runCatching { Prefs(context).appIcon }.getOrNull()
        val active = ICONS.firstOrNull { it.key == stored }
        val target = active ?: ICONS[0]
        if (active == null) {
            // Stale or unreadable preference. Persist the correction so the next
            // launch is a plain lookup instead of a fallback.
            runCatching { Prefs(context).appIcon = target.key }
        }

        applyState(
            pm,
            ComponentName(pkg, "$pkg.${target.aliasName}"),
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        )
        for (opt in ICONS) {
            if (opt.key == target.key) continue
            applyState(
                pm,
                ComponentName(pkg, "$pkg.${opt.aliasName}"),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            )
        }
    }
}
