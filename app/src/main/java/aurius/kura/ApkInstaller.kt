package aurius.kura

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File

/**
 * Hands a verified APK to Android's installer.
 *
 * Android has no silent install for an ordinary app: this can only put the
 * system prompt in front of the user, which is why the update feature is called
 * a check rather than an auto-update. If the user has not yet allowed this app
 * to install packages, they are sent to that setting first, because the install
 * intent fails silently without it.
 */
object ApkInstaller {

    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** Opens the per-app "install unknown apps" screen, when there is one. */
    fun openInstallPermissionSettings(ctx: Context): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            true
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }

    /** Opens the system installer for [file]. Returns false if it could not start. */
    fun install(activity: Activity, file: File): Boolean = try {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.updates", file)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        true
    } catch (_: Exception) {
        Toast.makeText(activity, "Could not open the installer", Toast.LENGTH_LONG).show()
        false
    }

    /**
     * Opens a release page in whatever browser the user has, rather than in a
     * WebView. The app itself still makes no request; the browser does, on its
     * own terms and with its own permissions.
     */
    fun openExternal(ctx: Context, url: String) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            Toast.makeText(ctx, "No browser available", Toast.LENGTH_LONG).show()
        }
    }

    const val RELEASE_PAGE = "https://github.com/aurius7/kura/releases/latest"
}
