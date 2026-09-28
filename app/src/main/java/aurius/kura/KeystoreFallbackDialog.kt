package aurius.kura

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The one inescapable warning that the hardware keystore could not be opened and
 * security settings are being stored unencrypted. Shown on the lock screen so a
 * user who never opens Settings still learns about the degraded state; the flag
 * is one-shot per session via [Prefs.consumeSecureFallbackWarning].
 */
object KeystoreFallbackDialog {

    /**
     * Styled to match the app's alarm dialogs: an un-dismissable black card with
     * a single OK. Only [onProceed] (invoked when the user taps OK) may continue.
     */
    fun show(context: Context, prefs: Prefs, onProceed: () -> Unit) {
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density + 0.5f).toInt()

        val rootCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = dp(22).toFloat()
                setStroke(dp(1), 0xFF1C1C1C.toInt())
            }
            setPadding(dp(20), dp(20), dp(20), dp(14))
        }

        rootCard.addView(TextView(context).apply {
            text = "Keystore unavailable"
            textSize = 18f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.01f
        })

        rootCard.addView(TextView(context).apply {
            text = "Security settings are stored unencrypted"
            textSize = 12.5f
            setTextColor(0xFF8A8A8A.toInt())
            setPadding(0, dp(5), 0, 0)
        })

        rootCard.addView(View(context).apply { setBackgroundColor(0xFF1C1C1C.toInt()) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(14)
                bottomMargin = dp(6)
            })

        rootCard.addView(TextView(context).apply {
            text = "The hardware keystore could not be opened, so security settings " +
                "(including your PIN hash) are being stored unencrypted.\n\n" +
                "Re-enrolling your screen lock, then reinstalling the app, restores " +
                "encrypted storage."
            textSize = 13f
            setTextColor(0xFFBDBDBD.toInt())
            setLineSpacing(0f, 1.25f)
            setPadding(dp(4), dp(2), dp(4), dp(2))
        })

        val okBtn = TextView(context).apply {
            text = "OK"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                setColor(prefs.accentColor())
                cornerRadius = dp(24).toFloat()
            }
            isClickable = true
            isFocusable = true
        }

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            setPadding(0, dp(10), 0, 0)
        }
        btnRow.addView(okBtn, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        rootCard.addView(btnRow)

        val dialog = AlertDialog.Builder(context)
            .setView(rootCard)
            .setCancelable(false)
            .create()

        okBtn.setOnClickListener {
            ThemeUtils.vibrateClick(it)
            dialog.dismiss()
            onProceed()
        }
        dialog.show()

        val dialogWidth = (context.resources.displayMetrics.widthPixels * 0.90f).toInt().coerceAtMost(dp(440))
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.75f)
            setLayout(dialogWidth, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}