package aurius.kura

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Repairs the launcher after an update.
 *
 * Icon selection is persisted by the platform as component enabled/disabled state, and
 * that state survives an app upgrade. A build that renames or removes an
 * [android.content.pm.PackageManager] activity-alias therefore leaves the device with
 * the previous icon disabled and nothing enabled in its place: the installed app has
 * no launchable component, so the recovery in [AppIconManager.syncLauncher] — which
 * only runs from inside the app — can never execute. The app is stuck uninstalled-but-
 * unopenable until it is removed.
 *
 * [Intent.ACTION_MY_PACKAGE_REPLACED] is delivered by the system right after the
 * update and does not need a launcher icon to be received, which makes it the one
 * moment the app can fix this by itself. Without it, anyone upgrading across a rename
 * has to uninstall, and uninstalling destroys the keystore-backed vault.
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        AppIconManager.syncLauncher(context.applicationContext)
    }
}
