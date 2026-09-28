package aurius.kura

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Sleek bottom floating progress bar with Cancel button for
 * long-running import, export, and restore tasks.
 */
class VaultProgressBar(context: Context) : FrameLayout(context), VaultProgress.Listener {

    private val titleView: TextView
    private val countView: TextView
    private val cancelBtn: TextView
    private val progressBar: ProgressBar

    init {
        visibility = View.GONE
        val d = context.resources.displayMetrics.density
        val dp = { v: Int -> (v * d).toInt() }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(0xEE1A1A1A.toInt())
                cornerRadius = 14f * d
                setStroke(dp(1), 0x33FFFFFF.toInt())
            }
            background = bg
            elevation = 16f * d
            setPadding(dp(16), dp(10), dp(16), dp(12))
        }

        // Top Row: Title, Counter, and Cancel Button
        val topRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        titleView = TextView(context).apply {
            text = "Processing..."
            setTextColor(Color.WHITE)
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            maxLines = 1
        }
        topRow.addView(titleView, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        countView = TextView(context).apply {
            text = ""
            setTextColor(0xFFB0B0B0.toInt())
            textSize = 12f
            setPadding(dp(8), 0, dp(12), 0)
        }
        topRow.addView(countView)

        cancelBtn = TextView(context).apply {
            text = "Cancel"
            setTextColor(0xFFFF5252.toInt())
            textSize = 12f
            setTypeface(null, android.graphics.Typeface.BOLD)
            val btnBg = GradientDrawable().apply {
                setColor(0x22FF5252.toInt())
                cornerRadius = 8f * d
                setStroke(dp(1), 0x55FF5252.toInt())
            }
            background = btnBg
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setOnClickListener {
                ThemeUtils.vibrateClick(this)
                VaultProgress.cancel()
            }
        }
        topRow.addView(cancelBtn)

        card.addView(topRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // Bottom Row: Sleek Progress Bar
        progressBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            progressDrawable?.let { d ->
                // Apply accent or white tint
                try {
                    d.setTint(0xFFE91E63.toInt())
                } catch (_: Exception) {}
            }
        }
        val barLp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply {
            topMargin = dp(8)
        }
        card.addView(progressBar, barLp)

        val cardLp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(16), dp(8), dp(16), dp(12))
        }
        addView(card, cardLp)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        VaultProgress.addListener(this)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        VaultProgress.removeListener(this)
    }

    override fun onProgressUpdate(title: String, current: Int, total: Int, canCancel: Boolean) {
        visibility = View.VISIBLE
        titleView.text = title
        if (total > 0) {
            countView.text = "$current / $total"
            progressBar.isIndeterminate = false
            progressBar.max = total
            progressBar.progress = current
        } else {
            countView.text = if (current > 0) "$current items" else ""
            progressBar.isIndeterminate = true
        }
        cancelBtn.visibility = if (canCancel) View.VISIBLE else View.GONE
    }

    override fun onProgressDismiss() {
        visibility = View.GONE
    }
}
