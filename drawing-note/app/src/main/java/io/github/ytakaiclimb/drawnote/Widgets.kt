package io.github.ytakaiclimb.drawnote

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.min

enum class Icon {
    PEN, BRUSH, BLUR, ERASER_STROKE, ERASER_AREA, LINE, SHAPE, TEXT, BUCKET,
    UNDO, REDO, NOTES, PAPER, FIT, EXPORT, TRASH, PLUS,
    RECT, OVAL, TRIANGLE, ARROW,
}

/** 24x24 の座標系でシンプルな線画アイコンを描く。 */
class IconDrawable(private val icon: Icon, var iconColor: Int = 0xFF37404D.toInt()) : Drawable() {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    override fun draw(c: Canvas) {
        val b = bounds
        val s = min(b.width(), b.height()) / 24f
        c.save()
        c.translate(b.exactCenterX() - 12f * s, b.exactCenterY() - 12f * s)
        c.scale(s, s)
        stroke.color = iconColor
        fill.color = iconColor
        fun path(vararg p: Float, close: Boolean = false, paint: Paint = stroke) {
            val pa = Path()
            pa.moveTo(p[0], p[1])
            var i = 2
            while (i < p.size) { pa.lineTo(p[i], p[i + 1]); i += 2 }
            if (close) pa.close()
            c.drawPath(pa, paint)
        }
        when (icon) {
            Icon.PEN -> {
                path(4f, 20f, 5.5f, 15f, 16f, 4.5f, 19.5f, 8f, 9f, 18.5f, close = true)
                c.drawLine(14f, 6.5f, 17.5f, 10f, stroke)
            }
            Icon.BRUSH -> {
                stroke.strokeWidth = 2.4f
                c.drawLine(20f, 4f, 12f, 12f, stroke)
                stroke.strokeWidth = 1.8f
                val p = Path()
                p.moveTo(12.5f, 11.5f)
                p.cubicTo(8f, 10f, 6f, 14f, 6.5f, 16f)
                p.cubicTo(6.8f, 18f, 5f, 19.5f, 3.5f, 20f)
                p.cubicTo(8f, 21f, 13.5f, 18f, 12.5f, 11.5f)
                c.drawPath(p, fill)
            }
            Icon.BLUR -> {
                fill.alpha = 45; c.drawCircle(12f, 12f, 9.5f, fill)
                fill.alpha = 90; c.drawCircle(12f, 12f, 6.5f, fill)
                fill.alpha = 255; c.drawCircle(12f, 12f, 3.5f, fill)
            }
            Icon.ERASER_STROKE -> {
                val p = Path()
                p.moveTo(3f, 17f)
                p.cubicTo(6f, 11f, 9f, 11f, 11f, 15f)
                p.cubicTo(13f, 19f, 16f, 19f, 18f, 14f)
                c.drawPath(p, stroke)
                c.drawLine(15f, 4f, 21f, 10f, stroke)
                c.drawLine(21f, 4f, 15f, 10f, stroke)
            }
            Icon.ERASER_AREA -> {
                path(3.5f, 14.5f, 11f, 7f, 19f, 15f, 12.5f, 21.5f, 9.5f, 21.5f, close = true)
                c.drawLine(7.2f, 10.8f, 15.2f, 18.8f, stroke)
                c.drawLine(12.5f, 21.5f, 21f, 21.5f, stroke)
            }
            Icon.LINE -> {
                c.drawLine(5f, 19f, 19f, 5f, stroke)
                c.drawCircle(5f, 19f, 2f, fill)
                c.drawCircle(19f, 5f, 2f, fill)
            }
            Icon.SHAPE -> {
                c.drawRect(3f, 10f, 13f, 20f, stroke)
                c.drawCircle(15.5f, 8.5f, 5.5f, stroke)
            }
            Icon.TEXT -> {
                stroke.strokeWidth = 2.2f
                c.drawLine(5f, 5f, 19f, 5f, stroke)
                c.drawLine(12f, 5f, 12f, 19.5f, stroke)
                c.drawLine(9f, 19.5f, 15f, 19.5f, stroke)
                stroke.strokeWidth = 1.8f
            }
            Icon.BUCKET -> {
                path(4f, 11f, 11f, 4f, 18f, 11f, 11f, 18f, close = true)
                c.drawLine(4f, 11f, 18f, 11f, stroke)
                val p = Path()
                p.moveTo(20f, 14f)
                p.cubicTo(18.2f, 16.5f, 18.2f, 18.5f, 20f, 18.5f)
                p.cubicTo(21.8f, 18.5f, 21.8f, 16.5f, 20f, 14f)
                c.drawPath(p, fill)
            }
            Icon.UNDO, Icon.REDO -> {
                if (icon == Icon.REDO) { c.scale(-1f, 1f, 12f, 12f) }
                val p = Path()
                p.moveTo(5f, 9f)
                p.lineTo(14.5f, 9f)
                p.cubicTo(19.5f, 9f, 19.5f, 18.5f, 14.5f, 18.5f)
                p.lineTo(8f, 18.5f)
                c.drawPath(p, stroke)
                path(8.5f, 5.5f, 5f, 9f, 8.5f, 12.5f)
            }
            Icon.NOTES -> {
                c.drawRoundRect(RectF(5f, 3f, 19f, 21f), 2f, 2f, stroke)
                c.drawLine(8.5f, 8f, 15.5f, 8f, stroke)
                c.drawLine(8.5f, 12f, 15.5f, 12f, stroke)
                c.drawLine(8.5f, 16f, 12.5f, 16f, stroke)
            }
            Icon.PAPER -> {
                c.drawRect(4f, 4f, 20f, 20f, stroke)
                stroke.strokeWidth = 1.1f
                for (v in floatArrayOf(9.33f, 14.66f)) {
                    c.drawLine(v, 4f, v, 20f, stroke)
                    c.drawLine(4f, v, 20f, v, stroke)
                }
                stroke.strokeWidth = 1.8f
            }
            Icon.FIT -> {
                path(4f, 9f, 4f, 4f, 9f, 4f)
                path(15f, 4f, 20f, 4f, 20f, 9f)
                path(20f, 15f, 20f, 20f, 15f, 20f)
                path(9f, 20f, 4f, 20f, 4f, 15f)
                c.drawRect(9f, 9f, 15f, 15f, stroke)
            }
            Icon.EXPORT -> {
                path(4f, 14f, 4f, 20f, 20f, 20f, 20f, 14f)
                c.drawLine(12f, 3.5f, 12f, 15f, stroke)
                path(7.5f, 10.5f, 12f, 15f, 16.5f, 10.5f)
            }
            Icon.TRASH -> {
                c.drawLine(4f, 6.5f, 20f, 6.5f, stroke)
                path(9f, 6.5f, 9f, 3.5f, 15f, 3.5f, 15f, 6.5f)
                path(6f, 6.5f, 7f, 20.5f, 17f, 20.5f, 18f, 6.5f)
                c.drawLine(10f, 10.5f, 10f, 16.5f, stroke)
                c.drawLine(14f, 10.5f, 14f, 16.5f, stroke)
            }
            Icon.PLUS -> {
                stroke.strokeWidth = 2.2f
                c.drawLine(12f, 5f, 12f, 19f, stroke)
                c.drawLine(5f, 12f, 19f, 12f, stroke)
                stroke.strokeWidth = 1.8f
            }
            Icon.RECT -> c.drawRect(4f, 6f, 20f, 18f, stroke)
            Icon.OVAL -> c.drawOval(3.5f, 5.5f, 20.5f, 18.5f, stroke)
            Icon.TRIANGLE -> path(12f, 4.5f, 20.5f, 19f, 3.5f, 19f, close = true)
            Icon.ARROW -> {
                c.drawLine(4f, 20f, 19f, 5f, stroke)
                path(11f, 5f, 19f, 5f, 19f, 13f)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}

/** 太さのプレビュー（現在の色の丸）。 */
class SizePreview(context: Context) : View(context) {
    var diameter = 4f
        set(v) { field = v; invalidate() }
    var color = Color.BLACK
        set(v) { field = v; invalidate() }
    var soft = false
        set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.displayMetrics.density
        color = 0x40000000
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(diameter / 2f, min(width, height) / 2f - 2f).coerceAtLeast(1f)
        p.color = color
        p.maskFilter = if (soft) android.graphics.BlurMaskFilter(r * 0.5f, android.graphics.BlurMaskFilter.Blur.NORMAL) else null
        canvas.drawCircle(width / 2f, height / 2f, if (soft) r * 0.7f else r, p)
        if (Color.luminance(color) > 0.85f) canvas.drawCircle(width / 2f, height / 2f, r, ring)
    }
}

/**
 * パレットの色見本。
 * タップで色を選び、上方向へのドラッグか長押しでバケツとして持ち出せる。
 */
@SuppressLint("ViewConstructor")
class SwatchView(context: Context, val color: Int, private val onSelect: (Int) -> Unit) : View(context) {
    var isChosen = false
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = this@SwatchView.color }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = 0x33000000
    }
    private val chosenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        color = 0xFF1E6FD9.toInt()
    }
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var scrolled = false
    private val longPress = Runnable { startColorDrag() }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - 5f * density
        canvas.drawCircle(cx, cy, r, fillPaint)
        canvas.drawCircle(cx, cy, r, borderPaint)
        if (isChosen) canvas.drawCircle(cx, cy, r + 3f * density, chosenPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY
                dragging = false
                scrolled = false
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                if (!dragging && dy < -slop && abs(dy) > abs(dx)) {
                    startColorDrag()
                } else if (abs(dx) > slop || abs(dy) > slop) {
                    scrolled = true
                    removeCallbacks(longPress)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPress)
                if (!dragging && !scrolled) {
                    onSelect(color)
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun startColorDrag() {
        removeCallbacks(longPress)
        if (dragging) return
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        startDragAndDrop(ClipData.newPlainText("color", color.toString()), BucketShadow(this, color), color, 0)
    }
}

/** ドラッグ中に指の少し上に表示する「ペンキのしずく」。 */
class BucketShadow(view: View, private val color: Int) : View.DragShadowBuilder(view) {
    private val d = view.resources.displayMetrics.density
    private val w = (44 * d).toInt()
    private val h = (84 * d).toInt()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@BucketShadow.color }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        this.color = Color.WHITE
    }
    private val outline2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        this.color = 0x66000000
    }

    override fun onProvideShadowMetrics(outShadowSize: Point, outShadowTouchPoint: Point) {
        outShadowSize.set(w, h)
        outShadowTouchPoint.set(w / 2, h - 1)
    }

    override fun onDrawShadow(canvas: Canvas) {
        val cx = w / 2f
        val r = 16f * d
        val cy = 4f * d + r * 1.6f
        val p = Path()
        p.moveTo(cx, 4f * d)
        p.cubicTo(cx + r * 0.4f, cy - r * 1.1f, cx + r, cy - r * 0.6f, cx + r, cy)
        p.cubicTo(cx + r, cy + r * 1.35f, cx - r, cy + r * 1.35f, cx - r, cy)
        p.cubicTo(cx - r, cy - r * 0.6f, cx - r * 0.4f, cy - r * 1.1f, cx, 4f * d)
        p.close()
        canvas.drawPath(p, fill)
        canvas.drawPath(p, outline2)
        canvas.drawPath(p, outline)
    }
}
