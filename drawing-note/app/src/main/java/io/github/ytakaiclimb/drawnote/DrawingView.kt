package io.github.ytakaiclimb.drawnote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.DragEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class Tool { PEN, BRUSH, BLUR, ERASER_STROKE, ERASER_AREA, LINE, SHAPE, TEXT, BUCKET }

/**
 * 無限に広がるノートのキャンバス。
 * 1本指で描画、2本指でピンチズーム・スクロール。
 */
class DrawingView(context: Context) : View(context) {

    interface Listener {
        fun onHistoryChanged()
        fun onTextRequest(worldX: Float, worldY: Float, existing: TextElement?)
    }

    var listener: Listener? = null

    val elements = ArrayList<Element>()
    private val undoStack = ArrayList<Action>()
    private val redoStack = ArrayList<Action>()

    var tool = Tool.PEN
        set(v) {
            cancelCurrent()
            field = v
            invalidate()
        }
    var shapeKind = ShapeKind.RECT
    var color = Color.BLACK
    /** スライダーの値（1〜60）。ツールごとに意味を変換して使う。 */
    var sizeValue = 6

    var paper = PaperType.GRID
        set(v) {
            field = v
            invalidate()
        }

    var scale = 1f; private set
    var tx = 0f; private set
    var ty = 0f; private set

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    // ---- 表示用のペイント ----
    private val paperPaint = Paint().apply { strokeWidth = 1f }
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0x99000000.toInt()
    }
    private val dropPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
    }
    private val badgeBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC222222.toInt() }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }

    // ---- 変換 ----
    fun toWorldX(sx: Float) = (sx - tx) / scale
    fun toWorldY(sy: Float) = (sy - ty) / scale

    private fun visibleWorld(): RectF = RectF(toWorldX(0f), toWorldY(0f), toWorldX(width.toFloat()), toWorldY(height.toFloat()))

    /** ペン類の画面上の太さ(px)。 */
    fun strokeScreenPx(t: Tool = tool): Float = when (t) {
        Tool.ERASER_AREA, Tool.ERASER_STROKE -> sizeValue * density * 1.2f
        Tool.BLUR -> sizeValue * density * 0.8f
        else -> sizeValue * density * 0.5f
    }

    fun textScreenPx(): Float = (10 + sizeValue) * density

    // =========================================================
    // 描画
    // =========================================================

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        drawPaper(canvas, paper, scale, tx, ty, width.toFloat(), height.toFloat())

        // 「こすって消す」消しゴムは CLEAR で合成するため、用紙とは別のレイヤーに描く
        val save = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        canvas.translate(tx, ty)
        canvas.scale(scale, scale)
        drawElements(canvas, visibleWorld())
        current?.draw(canvas)
        canvas.restoreToCount(save)

        if (showCursor) {
            val r = when (tool) {
                Tool.ERASER_STROKE -> eraserHitRadiusWorld() * scale
                else -> strokeScreenPx() / 2f
            }
            canvas.drawCircle(cursorX, cursorY, max(r, 2f), cursorPaint)
        }

        if (dropX >= 0f) {
            val r = 18f * density
            dropPaint.color = dropColor
            canvas.drawCircle(dropX, dropY, r, dropPaint)
            canvas.drawLine(dropX - r * 1.5f, dropY, dropX - r * 0.5f, dropY, dropPaint)
            canvas.drawLine(dropX + r * 0.5f, dropY, dropX + r * 1.5f, dropY, dropPaint)
            canvas.drawLine(dropX, dropY - r * 1.5f, dropX, dropY - r * 0.5f, dropPaint)
            canvas.drawLine(dropX, dropY + r * 0.5f, dropX, dropY + r * 1.5f, dropPaint)
        }

        val now = SystemClock.uptimeMillis()
        if (now < zoomBadgeUntil) {
            val label = "${(scale * 100).toInt()}%"
            val w = badgeText.measureText(label) + 24f * density
            val h = 28f * density
            val cx = width / 2f
            val top = 12f * density
            canvas.drawRoundRect(cx - w / 2, top, cx + w / 2, top + h, h / 2, h / 2, badgeBg)
            canvas.drawText(label, cx, top + h / 2 - (badgeText.ascent() + badgeText.descent()) / 2, badgeText)
            postInvalidateDelayed(zoomBadgeUntil - now)
        }
    }

    private fun drawElements(c: Canvas, visible: RectF?) {
        for (e in elements) {
            if (visible == null || RectF.intersects(e.bounds, visible)) e.draw(c)
        }
    }

    /** 用紙の罫線・方眼。ワールド座標で固定されていて、ズームに追従する。 */
    private fun drawPaper(c: Canvas, type: PaperType, s: Float, ox: Float, oy: Float, w: Float, h: Float) {
        if (type == PaperType.BLANK) return
        var step = if (type == PaperType.LINED) 40f else 32f
        // ズームアウトしすぎて線が詰まったら間引く
        while (step * s < 10f * density) step *= if (type == PaperType.GRID) 5f else 2f
        val px = step * s
        val wy0 = (0f - oy) / s
        val k0 = floor(wy0 / step).toInt()
        val k1 = ceil(((h - oy) / s) / step).toInt()
        if (type == PaperType.LINED) {
            paperPaint.color = 0xFFA9C7E8.toInt()
            paperPaint.strokeWidth = max(1f, density * 0.8f)
            for (k in k0..k1) {
                val y = k * px + oy
                c.drawLine(0f, y, w, y, paperPaint)
            }
            return
        }
        val j0 = floor(((0f - ox) / s) / step).toInt()
        val j1 = ceil(((w - ox) / s) / step).toInt()
        for (pass in 0..1) {
            // 1周目: 細い線、2周目: 5マスごとの太い線
            val major = pass == 1
            paperPaint.color = if (major) 0xFFC3CEDC.toInt() else 0xFFE3E8EF.toInt()
            paperPaint.strokeWidth = if (major) max(1f, density) else max(1f, density * 0.6f)
            for (k in k0..k1) {
                if ((Math.floorMod(k, 5) == 0) != major) continue
                val y = k * px + oy
                c.drawLine(0f, y, w, y, paperPaint)
            }
            for (j in j0..j1) {
                if ((Math.floorMod(j, 5) == 0) != major) continue
                val x = j * px + ox
                c.drawLine(x, 0f, x, h, paperPaint)
            }
        }
    }

    // =========================================================
    // タッチ操作
    // =========================================================

    private enum class Mode { NONE, DRAW, GESTURE, IGNORE }

    private var mode = Mode.NONE
    private var current: Element? = null
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastT = 0L
    private var moved = false
    private var brushW = 0f
    private val erased = ArrayList<Pair<Int, Element>>()

    private var showCursor = false
    private var cursorX = 0f
    private var cursorY = 0f

    // ピンチ用
    private var gStartDist = 1f
    private var gStartMidX = 0f
    private var gStartMidY = 0f
    private var gStartScale = 1f
    private var gStartTx = 0f
    private var gStartTy = 0f
    private var zoomBadgeUntil = 0L

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = Mode.DRAW
                beginTool(e)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (mode == Mode.DRAW) {
                    // 2本目の指が置かれた: 書き始めたばかりなら誤入力として捨て、しっかり書いた線は残す
                    val keep = moved && SystemClock.uptimeMillis() - downTime > 500 &&
                        tool in setOf(Tool.PEN, Tool.BRUSH, Tool.BLUR, Tool.ERASER_AREA, Tool.LINE, Tool.SHAPE)
                    if (keep) finishTool(lastX, lastY) else cancelCurrent()
                }
                if (e.pointerCount >= 2) {
                    mode = Mode.GESTURE
                    beginGesture(e, -1)
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.DRAW -> moveTool(e)
                Mode.GESTURE -> updateGesture(e)
                else -> {}
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.GESTURE) {
                if (e.pointerCount > 2) beginGesture(e, e.actionIndex) else mode = Mode.IGNORE
            }
            MotionEvent.ACTION_UP -> {
                if (mode == Mode.DRAW) finishTool(e.x, e.y)
                mode = Mode.NONE
                showCursor = false
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelCurrent()
                mode = Mode.NONE
                showCursor = false
                invalidate()
            }
        }
        return true
    }

    private fun beginGesture(e: MotionEvent, skip: Int) {
        val idx = (0 until e.pointerCount).filter { it != skip }.take(2)
        if (idx.size < 2) return
        val (a, b) = idx
        gStartMidX = (e.getX(a) + e.getX(b)) / 2f
        gStartMidY = (e.getY(a) + e.getY(b)) / 2f
        gStartDist = max(1f, hypot(e.getX(a) - e.getX(b), e.getY(a) - e.getY(b)))
        gStartScale = scale
        gStartTx = tx
        gStartTy = ty
    }

    private fun updateGesture(e: MotionEvent) {
        if (e.pointerCount < 2) return
        val midX = (e.getX(0) + e.getX(1)) / 2f
        val midY = (e.getY(0) + e.getY(1)) / 2f
        val dist = max(1f, hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)))
        val ns = (gStartScale * dist / gStartDist).coerceIn(MIN_SCALE, MAX_SCALE)
        // ピンチを始めた位置のワールド座標が、指の中点に付いてくるようにする
        val wx = (gStartMidX - gStartTx) / gStartScale
        val wy = (gStartMidY - gStartTy) / gStartScale
        scale = ns
        tx = midX - wx * ns
        ty = midY - wy * ns
        zoomBadgeUntil = SystemClock.uptimeMillis() + 900
        invalidate()
    }

    private fun beginTool(e: MotionEvent) {
        downTime = SystemClock.uptimeMillis()
        downX = e.x; downY = e.y
        lastX = e.x; lastY = e.y
        lastT = e.eventTime
        moved = false
        cursorX = e.x; cursorY = e.y
        val wx = toWorldX(e.x)
        val wy = toWorldY(e.y)
        val w = strokeScreenPx() / scale
        when (tool) {
            Tool.PEN -> current = StrokeElement(StrokeKind.PEN, color, w).also { it.add(wx, wy) }
            Tool.BLUR -> current = StrokeElement(StrokeKind.BLUR, color, w).also { it.add(wx, wy) }
            Tool.ERASER_AREA -> {
                current = StrokeElement(StrokeKind.ERASE, Color.BLACK, w).also { it.add(wx, wy) }
                showCursor = true
            }
            Tool.BRUSH -> {
                val base = w * 1.6f
                brushW = base * 0.35f
                current = StrokeElement(StrokeKind.BRUSH, color, base).also { it.add(wx, wy, brushW) }
            }
            Tool.ERASER_STROKE -> {
                erased.clear()
                showCursor = true
                eraseAt(wx, wy)
            }
            Tool.LINE -> current = ShapeElement(ShapeKind.LINE, color, w, wx, wy, wx, wy)
            Tool.SHAPE -> current = ShapeElement(shapeKind, color, w, wx, wy, wx, wy)
            Tool.TEXT, Tool.BUCKET -> {}
        }
        invalidate()
    }

    private fun moveTool(e: MotionEvent) {
        for (h in 0 until e.historySize) {
            handleMove(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalEventTime(h), e.getHistoricalPressure(h), e.getToolType(0))
        }
        handleMove(e.x, e.y, e.eventTime, e.pressure, e.getToolType(0))
        cursorX = e.x; cursorY = e.y
        invalidate()
    }

    private fun handleMove(x: Float, y: Float, t: Long, pressure: Float, toolType: Int) {
        if (!moved && hypot(x - downX, y - downY) > touchSlop) moved = true
        val dist = hypot(x - lastX, y - lastY)
        val wx = toWorldX(x)
        val wy = toWorldY(y)
        when (tool) {
            Tool.PEN, Tool.BLUR, Tool.ERASER_AREA -> if (dist >= 1.5f) {
                (current as? StrokeElement)?.add(wx, wy)
                lastX = x; lastY = y; lastT = t
            }
            Tool.BRUSH -> if (dist >= 1.5f) {
                val s = current as? StrokeElement ?: return
                val dt = max(1L, t - lastT)
                val v = dist / dt // px/ms
                var target = s.width * (1.25f - 0.3f * v).coerceIn(0.35f, 1.25f)
                if (toolType == MotionEvent.TOOL_TYPE_STYLUS) target *= (0.3f + pressure).coerceIn(0.3f, 1.4f)
                brushW += (target - brushW) * 0.35f
                s.add(wx, wy, brushW)
                lastX = x; lastY = y; lastT = t
            }
            Tool.ERASER_STROKE -> {
                // 速く動かしても取りこぼさないよう、線分上を細かくたどる
                val stepPx = max(2f, eraserHitRadiusWorld() * scale / 2f)
                val n = max(1, (dist / stepPx).toInt())
                for (i in 1..n) {
                    val f = i / n.toFloat()
                    eraseAt(toWorldX(lastX + (x - lastX) * f), toWorldY(lastY + (y - lastY) * f))
                }
                lastX = x; lastY = y; lastT = t
            }
            Tool.LINE, Tool.SHAPE -> (current as? ShapeElement)?.let {
                it.x2 = wx; it.y2 = wy
            }
            Tool.TEXT, Tool.BUCKET -> {}
        }
    }

    private fun finishTool(x: Float, y: Float) {
        when (tool) {
            Tool.PEN, Tool.BLUR, Tool.ERASER_AREA -> current?.let { push(Action.Add(it)) }
            Tool.BRUSH -> (current as? StrokeElement)?.let { s ->
                // 払い: 最後の数点を細くする
                val k = min(4, s.size - 1)
                for (j in 0 until k) {
                    val i = s.size - 1 - j
                    s.setWidthAt(i, s.ws[i] * (j + 1f) / (k + 1f))
                }
                push(Action.Add(s))
            }
            Tool.ERASER_STROKE -> if (erased.isNotEmpty()) {
                push(Action.Remove(ArrayList(erased)), alreadyApplied = true)
                erased.clear()
            }
            Tool.LINE, Tool.SHAPE -> (current as? ShapeElement)?.let {
                if (it.isMeaningful(3f * density / scale)) push(Action.Add(it))
            }
            Tool.TEXT -> if (!moved) {
                val wx = toWorldX(x)
                val wy = toWorldY(y)
                val hit = elements.lastOrNull { it is TextElement && it.hit(wx, wy, 8f * density / scale) } as TextElement?
                listener?.onTextRequest(wx, wy, hit)
            }
            Tool.BUCKET -> if (!moved) fillAt(x, y, color)
        }
        current = null
        invalidate()
    }

    private fun cancelCurrent() {
        current = null
        if (erased.isNotEmpty()) {
            for ((i, e) in erased.asReversed()) elements.add(i, e)
            erased.clear()
        }
        showCursor = false
        invalidate()
    }

    private fun eraserHitRadiusWorld(): Float = max(strokeScreenPx(Tool.ERASER_STROKE) / 2f, 6f * density) / scale

    private fun eraseAt(wx: Float, wy: Float) {
        val r = eraserHitRadiusWorld()
        for (i in elements.indices.reversed()) {
            val e = elements[i]
            if (e.hit(wx, wy, r)) {
                elements.removeAt(i)
                erased.add(i to e)
            }
        }
    }

    // =========================================================
    // バケツ（パレットからのドラッグ&ドロップ）
    // =========================================================

    private var dropX = -1f
    private var dropY = -1f
    private var dropColor = Color.BLACK

    init {
        setOnDragListener { _, ev ->
            val c = ev.localState as? Int
            when (ev.action) {
                DragEvent.ACTION_DRAG_STARTED -> c != null
                DragEvent.ACTION_DRAG_ENTERED, DragEvent.ACTION_DRAG_LOCATION -> {
                    if (c != null) {
                        dropX = ev.x; dropY = ev.y; dropColor = c
                        invalidate()
                    }
                    true
                }
                DragEvent.ACTION_DROP -> {
                    dropX = -1f
                    if (c != null) fillAt(ev.x, ev.y, c)
                    invalidate()
                    true
                }
                DragEvent.ACTION_DRAG_EXITED, DragEvent.ACTION_DRAG_ENDED -> {
                    dropX = -1f
                    invalidate()
                    true
                }
                else -> true
            }
        }
    }

    /**
     * 画面座標 (sx, sy) を含む閉じた領域を塗りつぶす。
     * 今見えている範囲をラスタライズして領域を求め、その形のビットマップを要素として追加する。
     */
    fun fillAt(sx: Float, sy: Float, fillColor: Int): Boolean {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return false
        val ix = sx.toInt()
        val iy = sy.toInt()
        if (ix !in 0 until w || iy !in 0 until h) return false

        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.translate(tx, ty)
        c.scale(scale, scale)
        drawElements(c, visibleWorld())
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()

        val opaqueFill = fillColor or 0xFF000000.toInt()
        val seed = overWhite(px[iy * w + ix])
        if (colorDist(seed, opaqueFill) < 6) return false

        val mask = ByteArray(w * h)
        fun match(i: Int) = mask[i].toInt() == 0 && colorDist(overWhite(px[i]), seed) <= FILL_TOLERANCE

        // スキャンライン塗りつぶし
        var stack = IntArray(1024)
        var sp = 0
        fun push(i: Int) {
            if (sp == stack.size) stack = stack.copyOf(sp * 2)
            stack[sp++] = i
        }
        push(iy * w + ix)
        while (sp > 0) {
            val p = stack[--sp]
            if (!match(p)) continue
            val y = p / w
            var lx = p % w
            var rx = lx
            while (lx > 0 && match(y * w + lx - 1)) lx--
            while (rx < w - 1 && match(y * w + rx + 1)) rx++
            for (x in lx..rx) mask[y * w + x] = 1
            for (ny in intArrayOf(y - 1, y + 1)) {
                if (ny < 0 || ny >= h) continue
                var prev = false
                for (x in lx..rx) {
                    val m = match(ny * w + x)
                    if (m && !prev) push(ny * w + x)
                    prev = m
                }
            }
        }

        // 線のフチ（アンチエイリアスの半透明部分）まで少し広げて、隙間が白く残らないようにする
        repeat(2) { pass ->
            val v = (pass + 2).toByte()
            val maxPrev = pass + 1
            fun prevPass(j: Int): Boolean = mask[j].toInt() in 1..maxPrev
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (mask[i].toInt() != 0 || (px[i] ushr 24) >= 250) continue
                    if ((x > 0 && prevPass(i - 1)) || (x < w - 1 && prevPass(i + 1)) ||
                        (y > 0 && prevPass(i - w)) || (y < h - 1 && prevPass(i + w))
                    ) mask[i] = v
                }
            }
        }

        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            for (x in 0 until w) {
                if (mask[y * w + x].toInt() != 0) {
                    if (x < minX) minX = x
                    if (x > maxX) maxX = x
                    if (y < minY) minY = y
                    if (y > maxY) maxY = y
                }
            }
        }
        if (maxX < 0) return false
        val bw = maxX - minX + 1
        val bh = maxY - minY + 1
        val out = IntArray(bw * bh)
        for (y in 0 until bh) {
            val row = (y + minY) * w
            for (x in 0 until bw) {
                if (mask[row + x + minX].toInt() != 0) out[y * bw + x] = opaqueFill
            }
        }
        val fillBmp = Bitmap.createBitmap(out, bw, bh, Bitmap.Config.ARGB_8888)
        val rect = RectF(toWorldX(minX.toFloat()), toWorldY(minY.toFloat()), toWorldX((maxX + 1).toFloat()), toWorldY((maxY + 1).toFloat()))
        push(Action.Add(FillElement(fillBmp, rect)))
        return true
    }

    private fun overWhite(c: Int): Int {
        val a = c ushr 24
        if (a == 255) return c
        fun ch(v: Int) = (v * a + 255 * (255 - a)) / 255
        return (0xFF shl 24) or (ch((c shr 16) and 0xFF) shl 16) or (ch((c shr 8) and 0xFF) shl 8) or ch(c and 0xFF)
    }

    private fun colorDist(a: Int, b: Int): Int = max(
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)),
        max(abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)), abs((a and 0xFF) - (b and 0xFF))),
    )

    // =========================================================
    // 戻る・進む
    // =========================================================

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    private fun apply(a: Action) {
        when (a) {
            is Action.Add -> elements.add(a.element)
            is Action.Remove -> for ((i, _) in a.items) elements.removeAt(i)
            is Action.Replace -> elements[a.index] = a.new
            is Action.Clear -> elements.clear()
        }
    }

    private fun revert(a: Action) {
        when (a) {
            is Action.Add -> elements.removeAt(elements.lastIndexOf(a.element))
            is Action.Remove -> for ((i, e) in a.items.asReversed()) elements.add(i, e)
            is Action.Replace -> elements[a.index] = a.old
            is Action.Clear -> elements.addAll(a.items)
        }
    }

    private fun push(a: Action, alreadyApplied: Boolean = false) {
        if (!alreadyApplied) apply(a)
        undoStack.add(a)
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        redoStack.clear()
        invalidate()
        listener?.onHistoryChanged()
    }

    fun undo() {
        cancelCurrent()
        val a = undoStack.removeLastOrNull() ?: return
        revert(a)
        redoStack.add(a)
        invalidate()
        listener?.onHistoryChanged()
    }

    fun redo() {
        cancelCurrent()
        val a = redoStack.removeLastOrNull() ?: return
        apply(a)
        undoStack.add(a)
        invalidate()
        listener?.onHistoryChanged()
    }

    fun clearAll() {
        if (elements.isEmpty()) return
        push(Action.Clear(ArrayList(elements)))
    }

    /** テキストの追加・編集。空文字で既存テキストを削除する。 */
    fun commitText(wx: Float, wy: Float, existing: TextElement?, text: String) {
        if (existing == null) {
            if (text.isBlank()) return
            push(Action.Add(TextElement(wx, wy, text, color, textScreenPx() / scale)))
            return
        }
        val idx = elements.indexOf(existing)
        if (idx < 0) return
        if (text.isBlank()) {
            push(Action.Remove(listOf(idx to existing)))
        } else if (text != existing.text) {
            push(Action.Replace(idx, existing, TextElement(existing.x, existing.y, text, existing.color, existing.size)))
        }
    }

    // =========================================================
    // 表示位置・読み込み・書き出し
    // =========================================================

    private fun contentBounds(): RectF? {
        var r: RectF? = null
        for (e in elements) {
            if (e is StrokeElement && e.kind == StrokeKind.ERASE) continue
            if (r == null) r = RectF(e.bounds) else r.union(e.bounds)
        }
        return r
    }

    /** 描いた内容全体が収まるように表示する。何もなければ100%に戻す。 */
    fun fitToContent() {
        val b = contentBounds()
        if (b == null || width == 0) {
            scale = 1f; tx = 0f; ty = 0f
        } else {
            val pad = 24f * density
            val s = min((width - pad * 2) / max(1f, b.width()), (height - pad * 2) / max(1f, b.height()))
                .coerceIn(MIN_SCALE, 2f)
            scale = s
            tx = width / 2f - b.centerX() * s
            ty = height / 2f - b.centerY() * s
        }
        zoomBadgeUntil = SystemClock.uptimeMillis() + 900
        invalidate()
    }

    fun load(data: NoteData) {
        cancelCurrent()
        elements.clear()
        elements.addAll(data.elements)
        undoStack.clear()
        redoStack.clear()
        paper = data.paper
        scale = data.scale.coerceIn(MIN_SCALE, MAX_SCALE)
        tx = data.tx
        ty = data.ty
        invalidate()
        listener?.onHistoryChanged()
    }

    /** 描いた範囲を用紙ごとPNG用のビットマップにする。 */
    fun renderImage(): Bitmap? {
        val b = contentBounds() ?: return null
        b.inset(-40f, -40f)
        val s = min(2f, MAX_EXPORT / max(b.width(), b.height()))
        val w = ceil(b.width() * s).toInt().coerceAtLeast(1)
        val h = ceil(b.height() * s).toInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val ox = -b.left * s
        val oy = -b.top * s
        drawPaper(c, paper, s, ox, oy, w.toFloat(), h.toFloat())
        val save = c.saveLayer(0f, 0f, w.toFloat(), h.toFloat(), null)
        c.translate(ox, oy)
        c.scale(s, s)
        drawElements(c, null)
        c.restoreToCount(save)
        return bmp
    }

    companion object {
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 20f
        private const val MAX_HISTORY = 300
        private const val FILL_TOLERANCE = 48
        private const val MAX_EXPORT = 4096f
    }
}
