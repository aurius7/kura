package aurius.kura

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView

/**
 * Interactive ImageView supporting smooth pinch-to-zoom, pan,
 * and double-tap zoom.
 * Defaults to FIT_CENTER with adjustViewBounds so the image is
 * always immediately visible and perfectly proportioned.
 */
class ZoomImageView(context: Context) : AppCompatImageView(context) {

    private val imgMatrix = Matrix()
    private var isZoomed = false
    private var currentScale = 1f
    private val maxScale = 5f

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            val targetScale = currentScale * factor

            if (!isZoomed && factor > 1.05f) {
                initMatrixFromFitCenter()
            }

            if (isZoomed && targetScale in 0.9f..maxScale) {
                currentScale = targetScale
                imgMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
                clampMatrix()
                imageMatrix = imgMatrix

                if (currentScale <= 1.0f) {
                    resetToFitCenter()
                }
            }
            return true
        }
    })

    var onSingleTap: (() -> Unit)? = null
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null

    val isCurrentlyZoomed: Boolean
        get() = isZoomed && currentScale > 1.05f

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            onSingleTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) {
                resetToFitCenter()
            } else {
                initMatrixFromFitCenter()
                currentScale = 2.5f
                imgMatrix.postScale(2.5f, 2.5f, e.x, e.y)
                clampMatrix()
                imageMatrix = imgMatrix
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (isZoomed && currentScale > 1.05f) {
                imgMatrix.postTranslate(-distanceX, -distanceY)
                clampMatrix()
                imageMatrix = imgMatrix
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (!isZoomed || currentScale <= 1.05f) {
                if (e1 != null) {
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (Math.abs(dx) > Math.abs(dy) * 1.2f && Math.abs(dx) > 100) {
                        if (dx < 0) {
                            onSwipeLeft?.invoke()
                        } else {
                            onSwipeRight?.invoke()
                        }
                        return true
                    }
                }
            }
            return false
        }
    })

    init {
        scaleType = ScaleType.FIT_CENTER
        adjustViewBounds = true
    }

    private fun initMatrixFromFitCenter() {
        imgMatrix.set(imageMatrix)
        scaleType = ScaleType.MATRIX
        imageMatrix = imgMatrix
        isZoomed = true
        currentScale = 1.0f
    }

    fun resetToFitCenter() {
        isZoomed = false
        currentScale = 1.0f
        scaleType = ScaleType.FIT_CENTER
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isZoomed) {
            scaleType = ScaleType.FIT_CENTER
        }
    }

    private fun clampMatrix() {
        val d = drawable ?: return
        val rect = RectF(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        imgMatrix.mapRect(rect)

        val vw = width.toFloat()
        val vh = height.toFloat()

        var deltaX = 0f
        var deltaY = 0f

        if (rect.width() <= vw) {
            deltaX = (vw - rect.width()) / 2f - rect.left
        } else if (rect.left > 0f) {
            deltaX = -rect.left
        } else if (rect.right < vw) {
            deltaX = vw - rect.right
        }

        if (rect.height() <= vh) {
            deltaY = (vh - rect.height()) / 2f - rect.top
        } else if (rect.top > 0f) {
            deltaY = -rect.top
        } else if (rect.bottom < vh) {
            deltaY = vh - rect.bottom
        }

        imgMatrix.postTranslate(deltaX, deltaY)
    }

    private var touchDownX = 0f
    private var touchDownY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                if (isZoomed && currentScale > 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (isZoomed && currentScale > 1.05f) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                } else {
                    val dx = Math.abs(event.x - touchDownX)
                    val dy = Math.abs(event.y - touchDownY)
                    if (dx > touchSlop && dx > dy * 1.3f) {
                        parent?.requestDisallowInterceptTouchEvent(true)
                    } else if (dy > touchSlop && dy > dx * 1.3f) {
                        parent?.requestDisallowInterceptTouchEvent(false)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
