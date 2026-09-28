package aurius.kura

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/** Shows the last crash trace on screen so bugs can be reported without adb. */
class CrashActivity : AppCompatActivity() {
    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        if (Prefs(this).flagSecure) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
        val trace = intent.getStringExtra("trace")
            ?: CrashGuard.lastCrash(this)
            ?: "Unknown error (no trace saved)."
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF121212.toInt())
            setPadding(40, 40, 40, 40)
        }
        col.addView(TextView(this).apply {
            text = "kura hit an error"; textSize = 22f; setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = "Send this text to the dev (or keep it for the next fix):"
            setTextColor(Color.GRAY); textSize = 14f; setPadding(0, 8, 0, 16)
        })
        val body = TextView(this).apply {
            text = trace; setTextColor(0xFFFFAB91.toInt()); textSize = 12f
            setTextIsSelectable(true)
        }
        val sc = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            addView(body)
        }
        col.addView(sc)
        col.addView(TextView(this).apply {
            text = "✕  CLOSE"; gravity = Gravity.CENTER; textSize = 17f
            setTextColor(Color.WHITE); setBackgroundColor(0xFFE91E63.toInt())
            setPadding(0, 28, 0, 28)
            setOnClickListener {
                CrashGuard.clearCrash(this@CrashActivity)
                finish()
            }
        })
        setContentView(col)
    }

    override fun onDestroy() {
        CrashGuard.clearCrash(this)
        super.onDestroy()
    }
}
