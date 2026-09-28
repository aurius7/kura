package aurius.kura

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Interactive spotlight and coachmark tour overlay.
 * Highlights UI elements on screen with a cutout and glowing neon ring,
 * displaying a positioned tooltip balloon pointing directly at the target.
 */
class SpotlightTourView(
    context: Context,
    private val prefs: Prefs,
    private val steps: List<Step>,
    private val onFinish: () -> Unit
) : FrameLayout(context) {

    data class Step(
        val target: View,
        val title: String,
        val description: String
    )

    private var currentStepIndex = 0
    private val dimPaint = Paint().apply {
        color = Color.parseColor("#E0000000") // 88% dark backdrop
    }
    private val clearPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = (2.5f * resources.displayMetrics.density)
        color = prefs.accentColor()
    }

    private val targetRect = RectF()
    private val cardContainer: LinearLayout

    private val stepBadge: TextView
    private val titleText: TextView
    private val descText: TextView
    private val arrowUp: TextView
    private val arrowDown: TextView
    private val dotsText: TextView
    private val skipBtn: TextView
    private val nextBtn: TextView

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_HARDWARE, null)
        isClickable = true
        isFocusable = true

        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // Root container for arrow + tooltip card
        cardContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        arrowUp = TextView(context).apply {
            text = "▲"
            textSize = 18f
            setTextColor(prefs.accentColor())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        cardContainer.addView(arrowUp)

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFF1E1E1E.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0x44FFFFFF.toInt())
            }
            setPadding(dp(20), dp(18), dp(20), dp(18))
        }

        // Header: Badge + Title
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        stepBadge = TextView(context).apply {
            textSize = 10f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(prefs.accentColor())
            background = GradientDrawable().apply {
                setColor(0x22FFFFFF.toInt())
                cornerRadius = dp(8).toFloat()
            }
            setPadding(dp(8), dp(3), dp(8), dp(3))
        }
        header.addView(stepBadge)

        val closeBtn = TextView(context).apply {
            text = "✕"
            textSize = 15f
            setTextColor(Color.GRAY)
            setPadding(dp(10), 0, dp(4), 0)
            setOnClickListener { dismissTour() }
        }
        header.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f))
        header.addView(closeBtn)
        card.addView(header)

        titleText = TextView(context).apply {
            textSize = 16f
            setTextColor(prefs.textColor())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(6))
        }
        card.addView(titleText)

        descText = TextView(context).apply {
            textSize = 13f
            setTextColor(0xFFCCCCCC.toInt())
            setLineSpacing(0f, 1.25f)
            setPadding(0, 0, 0, dp(16))
        }
        card.addView(descText)

        // Footer: Skip | Dots | Next
        val footer = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        skipBtn = TextView(context).apply {
            text = "Skip"
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(dp(8), dp(8), dp(12), dp(8))
            setOnClickListener { dismissTour() }
        }
        footer.addView(skipBtn)

        dotsText = TextView(context).apply {
            textSize = 12f
            setTextColor(prefs.accentColor())
            gravity = Gravity.CENTER
        }
        footer.addView(dotsText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        nextBtn = TextView(context).apply {
            text = "Next →"
            textSize = 13f
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(ThemeUtils.buttonTextColor(prefs, true))
            background = ThemeUtils.buttonBackground(prefs, true, dp(14).toFloat())
            setPadding(dp(18), dp(8), dp(18), dp(8))
            setOnClickListener { nextStep() }
        }
        footer.addView(nextBtn)

        card.addView(footer)
        cardContainer.addView(card, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        arrowDown = TextView(context).apply {
            text = "▼"
            textSize = 18f
            setTextColor(prefs.accentColor())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        cardContainer.addView(arrowDown)

        addView(cardContainer, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        post { updateLayoutForCurrentStep() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            // Tap inside highlighted target advances to next step
            if (targetRect.contains(event.x, event.y)) {
                nextStep()
                return true
            }
        }
        return true // Consume touch so user doesn't accidentally trigger background buttons during tour
    }

    private fun nextStep() {
        if (currentStepIndex < steps.size - 1) {
            currentStepIndex++
            updateLayoutForCurrentStep()
        } else {
            dismissTour()
        }
    }

    private fun dismissTour() {
        animate()
            .alpha(0f)
            .setDuration(250)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    (parent as? ViewGroup)?.removeView(this@SpotlightTourView)
                    onFinish()
                }
            })
            .start()
    }

    private val scrollListener = android.view.ViewTreeObserver.OnScrollChangedListener {
        if (currentStepIndex in steps.indices) {
            updatePositionsForCurrentStep()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(scrollListener)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        viewTreeObserver.removeOnScrollChangedListener(scrollListener)
    }

    private fun updateLayoutForCurrentStep() {
        if (currentStepIndex !in steps.indices) {
            dismissTour()
            return
        }

        val step = steps[currentStepIndex]
        val target = step.target

        ensureTargetVisible(target) {
            updatePositionsForCurrentStep()
        }
    }

    private fun ensureTargetVisible(target: View, onReady: () -> Unit) {
        var parentView: android.view.ViewParent? = target.parent
        var scrollView: android.widget.ScrollView? = null
        while (parentView != null) {
            if (parentView is android.widget.ScrollView) {
                scrollView = parentView
                break
            }
            parentView = parentView.parent
        }

        if (scrollView != null) {
            val targetRectInScroll = Rect()
            target.getDrawingRect(targetRectInScroll)
            scrollView.offsetDescendantRectToMyCoords(target, targetRectInScroll)

            val svHeight = scrollView.height
            if (svHeight > 0) {
                val currentScrollY = scrollView.scrollY
                val visibleTop = currentScrollY
                val visibleBottom = currentScrollY + svHeight

                // Check if target is comfortably inside visible viewport with margin
                val isTargetComfortablyVisible = targetRectInScroll.top >= visibleTop + 40 &&
                        targetRectInScroll.bottom <= visibleBottom - 40

                if (!isTargetComfortablyVisible) {
                    val desiredScrollY = (targetRectInScroll.top - svHeight / 4).coerceAtLeast(0)
                    val maxScroll = (scrollView.getChildAt(0)?.height ?: 0) - svHeight
                    val clampedScrollY = desiredScrollY.coerceIn(0, maxOf(0, maxScroll))

                    if (Math.abs(currentScrollY - clampedScrollY) > 20) {
                        scrollView.smoothScrollTo(0, clampedScrollY)
                        postDelayed({ onReady() }, 280)
                        return
                    }
                }
            }
        }
        onReady()
    }

    private fun updatePositionsForCurrentStep() {
        if (currentStepIndex !in steps.indices) return

        val step = steps[currentStepIndex]
        val target = step.target
        val density = resources.displayMetrics.density
        val dp = { v: Int -> (v * density).toInt() }

        // Compute target view position relative to this overlay
        val targetLocation = IntArray(2)
        target.getLocationInWindow(targetLocation)

        val overlayLocation = IntArray(2)
        getLocationInWindow(overlayLocation)

        val left = (targetLocation[0] - overlayLocation[0]).toFloat()
        val top = (targetLocation[1] - overlayLocation[1]).toFloat()
        val pad = dp(6).toFloat()

        targetRect.set(
            left - pad,
            top - pad,
            left + target.width + pad,
            top + target.height + pad
        )

        // Populate card texts
        stepBadge.text = "STEP ${currentStepIndex + 1} OF ${steps.size}"
        titleText.text = step.title
        descText.text = step.description
        nextBtn.text = if (currentStepIndex == steps.size - 1) "Got It! ✓" else "Next →"

        val dots = buildString {
            for (i in steps.indices) {
                if (i > 0) append(" ")
                append(if (i == currentStepIndex) "●" else "○")
            }
        }
        dotsText.text = dots

        // Position the card container
        val screenHeight = height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels
        val horizontalMargin = dp(24)
        val lp = cardContainer.layoutParams as LayoutParams
        lp.leftMargin = horizontalMargin
        lp.rightMargin = horizontalMargin

        val targetH = targetRect.height()
        val spaceAbove = targetRect.top
        val spaceBelow = screenHeight - targetRect.bottom

        // If target covers more than 48% of the screen height (e.g. Media Viewer),
        // placing the card outside the target pushes it completely off-screen!
        // Instead, float the card near the bottom of the screen inside the spotlight area.
        if (targetH > screenHeight * 0.48f) {
            arrowUp.visibility = View.GONE
            arrowDown.visibility = View.GONE
            lp.gravity = Gravity.BOTTOM
            lp.bottomMargin = dp(36)
            lp.topMargin = 0
        } else if (spaceBelow >= dp(180) || spaceBelow >= spaceAbove) {
            // Place card below target with upward pointing arrow
            arrowUp.visibility = View.VISIBLE
            arrowDown.visibility = View.GONE
            arrowUp.translationX = (targetRect.centerX() - (width / 2f)).coerceIn(-width * 0.35f, width * 0.35f)
            lp.gravity = Gravity.TOP
            lp.topMargin = (targetRect.bottom + dp(6)).toInt().coerceIn(dp(16), (screenHeight - dp(180)).toInt())
            lp.bottomMargin = 0
        } else {
            // Place card above target with downward pointing arrow
            arrowUp.visibility = View.GONE
            arrowDown.visibility = View.VISIBLE
            arrowDown.translationX = (targetRect.centerX() - (width / 2f)).coerceIn(-width * 0.35f, width * 0.35f)
            lp.gravity = Gravity.BOTTOM
            lp.bottomMargin = (screenHeight - targetRect.top + dp(6)).toInt().coerceIn(dp(16), (screenHeight - dp(180)).toInt())
            lp.topMargin = 0
        }
        cardContainer.layoutParams = lp

        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 1. Draw dark dimming mask over screen
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)

        // 2. Cut out clear spotlight window over target view
        if (!targetRect.isEmpty) {
            val r = (14f * resources.displayMetrics.density)
            canvas.drawRoundRect(targetRect, r, r, clearPaint)
            // 3. Draw glowing neon accent border around target
            canvas.drawRoundRect(targetRect, r, r, strokePaint)
        }
    }

    companion object {
        fun show(
            parentFrame: FrameLayout,
            prefs: Prefs,
            steps: List<Step>,
            onComplete: () -> Unit
        ) {
            val tour = SpotlightTourView(parentFrame.context, prefs, steps, onComplete)
            tour.alpha = 0f
            parentFrame.addView(tour, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
            tour.animate().alpha(1f).setDuration(250).start()
        }
    }
}
