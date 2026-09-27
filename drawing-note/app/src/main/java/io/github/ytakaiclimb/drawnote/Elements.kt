package io.github.ytakaiclimb.drawnote

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class StrokeKind { PEN, BRUSH, BLUR, ERASE }
enum class ShapeKind { LINE, RECT, OVAL, TRIANGLE, ARROW }
enum class PaperType { BLANK, LINED, GRID }

/** ノート上の描画要素。座標はすべてワールド座標（ズーム・スクロールに依存しない）。 */
sealed class Element {
    abstract val bounds: RectF
    abstract fun draw(c: Canvas)

    /** (x, y) を中心とした半径 r の円がこの要素に触れているか（1線消し用）。 */
    open fun hit(x: Float, y: Float, r: Float): Boolean = false
}

/** 点と線分の距離。 */
internal fun distToSegment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
    val dx = bx - ax
    val dy = by - ay
    val l2 = dx * dx + dy * dy
    val t = if (l2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

private fun polylineHit(xs: FloatArray, ys: FloatArray, n: Int, x: Float, y: Float, tol: Float): Boolean {
    if (n == 1) return hypot(x - xs[0], y - ys[0]) <= tol
    for (i in 1 until n) {
        if (distToSegment(x, y, xs[i - 1], ys[i - 1], xs[i], ys[i]) <= tol) return true
    }
    return false
}

/** ペン・ブラシ・ぼかし・消しゴム（こすって消す）のストローク。 */
class StrokeElement(val kind: StrokeKind, val color: Int, val width: Float) : Element() {
    var xs = FloatArray(32); private set
    var ys = FloatArray(32); private set
    /** 点ごとの太さ（ブラシの強弱に使う）。 */
    var ws = FloatArray(32); private set
    var size = 0; private set

    private var minX = Float.MAX_VALUE
    private var minY = Float.MAX_VALUE
    private var maxX = -Float.MAX_VALUE
    private var maxY = -Float.MAX_VALUE
    private var maxW = 0f
    private val boundsRect = RectF()
    private var path: Path? = null

    private val paint: Paint by lazy {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = this@StrokeElement.color
            when (kind) {
                StrokeKind.BRUSH -> style = Paint.Style.FILL
                else -> {
                    style = Paint.Style.STROKE
                    strokeWidth = width
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                }
            }
            if (kind == StrokeKind.BLUR) {
                maskFilter = BlurMaskFilter(max(1f, width * 0.6f), BlurMaskFilter.Blur.NORMAL)
            }
            if (kind == StrokeKind.ERASE) {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }
        }
    }

    fun add(x: Float, y: Float, w: Float = width) {
        if (size == xs.size) {
            xs = xs.copyOf(size * 2)
            ys = ys.copyOf(size * 2)
            ws = ws.copyOf(size * 2)
        }
        xs[size] = x
        ys[size] = y
        ws[size] = w
        size++
        minX = min(minX, x); minY = min(minY, y)
        maxX = max(maxX, x); maxY = max(maxY, y)
        maxW = max(maxW, w)
        path = null
    }

    fun setWidthAt(i: Int, w: Float) {
        ws[i] = w
        path = null
    }

    override val bounds: RectF
        get() {
            // ぼかしははみ出す分を多めに取る
            val pad = (if (kind == StrokeKind.BLUR) maxW * 1.6f else maxW / 2f) + 1f
            boundsRect.set(minX - pad, minY - pad, maxX + pad, maxY + pad)
            return boundsRect
        }

    override fun draw(c: Canvas) {
        if (size == 0) return
        val p = path ?: (if (kind == StrokeKind.BRUSH) buildBrushPath() else buildLinePath()).also { path = it }
        c.drawPath(p, paint)
        if (kind == StrokeKind.BRUSH) {
            c.drawCircle(xs[0], ys[0], ws[0] / 2f, paint)
            c.drawCircle(xs[size - 1], ys[size - 1], ws[size - 1] / 2f, paint)
        }
    }

    private fun buildLinePath(): Path {
        val p = Path()
        p.moveTo(xs[0], ys[0])
        if (size == 1) {
            p.lineTo(xs[0] + 0.01f, ys[0])
            return p
        }
        for (i in 1 until size) {
            val mx = (xs[i - 1] + xs[i]) / 2f
            val my = (ys[i - 1] + ys[i]) / 2f
            if (i == 1) p.lineTo(mx, my) else p.quadTo(xs[i - 1], ys[i - 1], mx, my)
        }
        p.lineTo(xs[size - 1], ys[size - 1])
        return p
    }

    /** 点ごとの太さを持つ輪郭を塗りつぶしで作る。 */
    private fun buildBrushPath(): Path {
        val p = Path()
        val n = size
        if (n < 2) {
            p.addCircle(xs[0], ys[0], ws[0] / 2f, Path.Direction.CW)
            return p
        }
        val lx = FloatArray(n); val ly = FloatArray(n)
        val rx = FloatArray(n); val ry = FloatArray(n)
        for (i in 0 until n) {
            val a = max(0, i - 1)
            val b = min(n - 1, i + 1)
            var dx = xs[b] - xs[a]
            var dy = ys[b] - ys[a]
            val len = hypot(dx, dy)
            if (len < 1e-4f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
            val h = ws[i] / 2f
            lx[i] = xs[i] - dy * h; ly[i] = ys[i] + dx * h
            rx[i] = xs[i] + dy * h; ry[i] = ys[i] - dx * h
        }
        p.moveTo(lx[0], ly[0])
        for (i in 1 until n) {
            p.quadTo(lx[i - 1], ly[i - 1], (lx[i - 1] + lx[i]) / 2f, (ly[i - 1] + ly[i]) / 2f)
        }
        p.lineTo(lx[n - 1], ly[n - 1])
        p.lineTo(rx[n - 1], ry[n - 1])
        for (i in n - 2 downTo 0) {
            p.quadTo(rx[i + 1], ry[i + 1], (rx[i + 1] + rx[i]) / 2f, (ry[i + 1] + ry[i]) / 2f)
        }
        p.lineTo(rx[0], ry[0])
        p.close()
        return p
    }

    override fun hit(x: Float, y: Float, r: Float): Boolean {
        if (kind == StrokeKind.ERASE) return false
        val b = bounds
        if (x < b.left - r || x > b.right + r || y < b.top - r || y > b.bottom + r) return false
        return polylineHit(xs, ys, size, x, y, r + maxW / 2f)
    }
}

/** 直線・四角・円・三角・矢印。 */
class ShapeElement(
    val kind: ShapeKind,
    val color: Int,
    val width: Float,
    var x1: Float, var y1: Float, var x2: Float, var y2: Float,
) : Element() {
    private val boundsRect = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@ShapeElement.color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    override val bounds: RectF
        get() {
            val pad = width / 2f + if (kind == ShapeKind.ARROW) headLength() else 0f
            boundsRect.set(min(x1, x2) - pad, min(y1, y2) - pad, max(x1, x2) + pad, max(y1, y2) + pad)
            return boundsRect
        }

    private fun headLength(): Float = max(width * 4f, min(hypot(x2 - x1, y2 - y1) * 0.25f, width * 10f))

    /** 輪郭を折れ線で返す（描画と当たり判定で共用）。 */
    private fun outline(): Pair<FloatArray, FloatArray> {
        val l = min(x1, x2); val r = max(x1, x2)
        val t = min(y1, y2); val b = max(y1, y2)
        return when (kind) {
            ShapeKind.LINE -> floatArrayOf(x1, x2) to floatArrayOf(y1, y2)
            ShapeKind.RECT -> floatArrayOf(l, r, r, l, l) to floatArrayOf(t, t, b, b, t)
            ShapeKind.TRIANGLE -> floatArrayOf((l + r) / 2f, r, l, (l + r) / 2f) to floatArrayOf(t, b, b, t)
            ShapeKind.OVAL -> {
                val n = 48
                val cx = (l + r) / 2f; val cy = (t + b) / 2f
                val ax = (r - l) / 2f; val ay = (b - t) / 2f
                val xs = FloatArray(n + 1); val ys = FloatArray(n + 1)
                for (i in 0..n) {
                    val a = Math.PI * 2 * i / n
                    xs[i] = cx + (ax * cos(a)).toFloat()
                    ys[i] = cy + (ay * sin(a)).toFloat()
                }
                xs to ys
            }
            ShapeKind.ARROW -> {
                val ang = atan2(y2 - y1, x2 - x1)
                val hl = headLength()
                val a1 = ang + Math.PI * 5 / 6
                val a2 = ang - Math.PI * 5 / 6
                floatArrayOf(x1, x2, x2 + (hl * cos(a1)).toFloat(), x2, x2 + (hl * cos(a2)).toFloat()) to
                    floatArrayOf(y1, y2, y2 + (hl * sin(a1)).toFloat(), y2, y2 + (hl * sin(a2)).toFloat())
            }
        }
    }

    override fun draw(c: Canvas) {
        when (kind) {
            ShapeKind.LINE -> c.drawLine(x1, y1, x2, y2, paint)
            ShapeKind.RECT -> c.drawRect(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), paint)
            ShapeKind.OVAL -> c.drawOval(min(x1, x2), min(y1, y2), max(x1, x2), max(y1, y2), paint)
            else -> {
                val (xs, ys) = outline()
                val p = Path()
                p.moveTo(xs[0], ys[0])
                for (i in 1 until xs.size) p.lineTo(xs[i], ys[i])
                if (kind == ShapeKind.TRIANGLE) p.close()
                c.drawPath(p, paint)
            }
        }
    }

    override fun hit(x: Float, y: Float, r: Float): Boolean {
        val (xs, ys) = outline()
        return polylineHit(xs, ys, xs.size, x, y, r + width / 2f)
    }

    /** 画面上で意味のある大きさがあるか。 */
    fun isMeaningful(minWorld: Float): Boolean = abs(x2 - x1) >= minWorld || abs(y2 - y1) >= minWorld
}

/** テキスト。(x, y) は左上。 */
class TextElement(val x: Float, val y: Float, val text: String, val color: Int, val size: Float) : Element() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = this@TextElement.color
        textSize = size
    }
    private val lines = text.split('\n')
    private val lineHeight = size * 1.25f
    override val bounds: RectF = RectF(
        x, y,
        x + (lines.maxOfOrNull { paint.measureText(it) } ?: 0f),
        y + lines.size * lineHeight,
    )

    override fun draw(c: Canvas) {
        val ascent = paint.fontMetrics.ascent
        for ((i, l) in lines.withIndex()) {
            c.drawText(l, x, y - ascent + i * lineHeight, paint)
        }
    }

    override fun hit(x: Float, y: Float, r: Float): Boolean =
        x >= bounds.left - r && x <= bounds.right + r && y >= bounds.top - r && y <= bounds.bottom + r
}

/** バケツ塗りつぶしの結果。塗った領域だけを切り出したビットマップを持つ。 */
class FillElement(val bitmap: Bitmap, override val bounds: RectF) : Element() {
    /** 保存用にエンコード済みPNG（Base64）をキャッシュする。 */
    var encoded: String? = null

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas) {
        c.drawBitmap(bitmap, null, bounds, paint)
    }

    override fun hit(x: Float, y: Float, r: Float): Boolean {
        if (!bounds.contains(x, y)) return false
        val px = ((x - bounds.left) / bounds.width() * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
        val py = ((y - bounds.top) / bounds.height() * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
        return Color.alpha(bitmap.getPixel(px, py)) > 0
    }
}

/** 戻る・進むのための操作記録。 */
sealed class Action {
    class Add(val element: Element) : Action()
    /** 取り除いた順に (その時点でのインデックス, 要素)。 */
    class Remove(val items: List<Pair<Int, Element>>) : Action()
    class Replace(val index: Int, val old: Element, val new: Element) : Action()
    class Clear(val items: List<Element>) : Action()
}
