package io.github.ytakaiclimb.drawnote

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), DrawingView.Listener {

    private lateinit var dv: DrawingView
    private lateinit var store: NoteStore
    private var noteId = ""

    private lateinit var undoBtn: ImageButton
    private lateinit var redoBtn: ImageButton
    private lateinit var titleView: TextView
    private lateinit var sizeRow: View
    private lateinit var sizeBar: SeekBar
    private lateinit var sizeLabel: TextView
    private lateinit var sizePreview: SizePreview
    private lateinit var shapeRow: View
    private lateinit var bucketHint: View
    private lateinit var paletteRow: LinearLayout

    private val toolViews = HashMap<Tool, LinearLayout>()
    private val toolIcons = HashMap<Tool, IconDrawable>()
    private val shapeViews = HashMap<ShapeKind, ImageButton>()
    private val shapeIcons = HashMap<ShapeKind, IconDrawable>()
    private val swatches = ArrayList<SwatchView>()

    /** ツールごとに太さを覚えておく。 */
    private val sizes = hashMapOf(
        Tool.PEN to 6, Tool.BRUSH to 10, Tool.BLUR to 16,
        Tool.ERASER_STROKE to 16, Tool.ERASER_AREA to 24,
        Tool.LINE to 6, Tool.SHAPE to 6, Tool.TEXT to 14, Tool.BUCKET to 6,
    )

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
    private fun dp(v: Int) = dp(v.toFloat()).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = NoteStore(this)
        dv = DrawingView(this).also { it.listener = this }
        setContentView(buildUi())

        prefs.getString("customColors", "")!!.split(',').filter { it.isNotBlank() }.forEach {
            it.toIntOrNull()?.let { c -> addSwatch(c, persist = false) }
        }
        selectColor(prefs.getInt("color", PALETTE[0]))
        selectTool(runCatching { Tool.valueOf(prefs.getString("tool", "PEN")!!) }.getOrDefault(Tool.PEN))
        selectShape(runCatching { ShapeKind.valueOf(prefs.getString("shape", "RECT")!!) }.getOrDefault(ShapeKind.RECT))

        val notes = store.list()
        val last = prefs.getString("lastNote", null)
        val target = notes.find { it.id == last } ?: notes.firstOrNull()
        if (target != null) openNote(target.id) else createNote(PaperType.GRID)
    }

    override fun onPause() {
        super.onPause()
        saveNow()
        prefs.edit()
            .putInt("color", dv.color)
            .putString("tool", dv.tool.name)
            .putString("shape", dv.shapeKind.name)
            .apply()
    }

    // =========================================================
    // 画面の組み立て
    // =========================================================

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BAR_BG)
        }

        // ---- 上部バー ----
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        top.addView(iconButton(Icon.NOTES, "ノート一覧") { showNotes() })
        titleView = TextView(this).apply {
            setTextColor(0xFF37404D.toInt())
            textSize = 15f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { showNotes() }
        }
        top.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        undoBtn = iconButton(Icon.UNDO, "戻る") { dv.undo() }
        redoBtn = iconButton(Icon.REDO, "進む") { dv.redo() }
        top.addView(undoBtn)
        top.addView(redoBtn)
        top.addView(iconButton(Icon.PAPER, "用紙") { showPaperPicker() })
        top.addView(iconButton(Icon.FIT, "全体を表示") { dv.fitToContent() })
        top.addView(iconButton(Icon.EXPORT, "画像として保存") { exportImage() })
        top.addView(iconButton(Icon.TRASH, "すべて消す") { confirmClear() })
        root.addView(top, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))

        // ---- キャンバス ----
        val canvasFrame = FrameLayout(this)
        canvasFrame.addView(dv)
        root.addView(canvasFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 下部パネル ----
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.WHITE)
            elevation = dp(8f)
            setPadding(0, dp(4), 0, dp(4))
        }

        // 太さ
        sizePreview = SizePreview(this)
        sizeLabel = TextView(this).apply {
            setTextColor(0xFF5A6472.toInt())
            textSize = 12f
            minWidth = dp(64)
        }
        sizeBar = SeekBar(this).apply {
            max = 59
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    sizes[dv.tool] = p + 1
                    dv.sizeValue = p + 1
                    updateSizeUi()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(8), 0)
            addView(sizeLabel)
            addView(sizeBar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(sizePreview, LinearLayout.LayoutParams(dp(40), dp(40)))
        }

        bucketHint = TextView(this).apply {
            text = "パレットの色をキャンバスへドラッグ&ドロップ（またはタップした場所を塗りつぶし）"
            setTextColor(0xFF5A6472.toInt())
            textSize = 12f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(40)
        }

        // 図形の種類
        val shapeLine = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }
        for ((kind, icon, label) in listOf(
            Triple(ShapeKind.RECT, Icon.RECT, "四角"),
            Triple(ShapeKind.OVAL, Icon.OVAL, "円"),
            Triple(ShapeKind.TRIANGLE, Icon.TRIANGLE, "三角"),
            Triple(ShapeKind.ARROW, Icon.ARROW, "矢印"),
        )) {
            val d = IconDrawable(icon)
            shapeIcons[kind] = d
            val b = ImageButton(this).apply {
                setImageDrawable(d)
                contentDescription = label
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(8), dp(8), dp(8), dp(8))
                setOnClickListener { selectShape(kind) }
            }
            shapeViews[kind] = b
            shapeLine.addView(b, LinearLayout.LayoutParams(dp(48), dp(40)).apply { marginEnd = dp(4) })
        }
        shapeRow = shapeLine

        panel.addView(shapeRow)
        panel.addView(sizeRow)
        panel.addView(bucketHint)

        // ツール
        val tools = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), 0, dp(4), 0)
        }
        for ((tool, icon, label) in listOf(
            Triple(Tool.PEN, Icon.PEN, "ペン"),
            Triple(Tool.BRUSH, Icon.BRUSH, "ブラシ"),
            Triple(Tool.BLUR, Icon.BLUR, "ぼかし"),
            Triple(Tool.ERASER_STROKE, Icon.ERASER_STROKE, "1線消し"),
            Triple(Tool.ERASER_AREA, Icon.ERASER_AREA, "消しゴム"),
            Triple(Tool.LINE, Icon.LINE, "直線"),
            Triple(Tool.SHAPE, Icon.SHAPE, "図形"),
            Triple(Tool.TEXT, Icon.TEXT, "テキスト"),
            Triple(Tool.BUCKET, Icon.BUCKET, "バケツ"),
        )) {
            tools.addView(toolButton(tool, icon, label), LinearLayout.LayoutParams(dp(58), dp(56)))
        }
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tools)
        })

        // パレット
        paletteRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), 0, dp(6), 0)
        }
        for (c in PALETTE) addSwatch(c, persist = false)
        paletteRow.addView(ImageButton(this).apply {
            setImageDrawable(IconDrawable(Icon.PLUS))
            contentDescription = "色を追加"
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(1), 0x55000000)
                setColor(Color.WHITE)
            }
            setPadding(dp(8), dp(8), dp(8), dp(8))
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setOnClickListener { showColorPicker() }
        }, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6); marginEnd = dp(6) })
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(paletteRow)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        root.addView(panel)
        return root
    }

    private fun iconButton(icon: Icon, desc: String, onClick: () -> Unit) = ImageButton(this).apply {
        setImageDrawable(IconDrawable(icon))
        contentDescription = desc
        tooltipText = desc
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackgroundBorderless))
        background = a.getDrawable(0)
        a.recycle()
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        setOnClickListener { onClick() }
    }

    private fun toolButton(tool: Tool, icon: Icon, label: String): View {
        val d = IconDrawable(icon)
        toolIcons[tool] = d
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            contentDescription = label
            setOnClickListener { selectTool(tool) }
            addView(ImageView(this@MainActivity).apply { setImageDrawable(d) }, LinearLayout.LayoutParams(dp(26), dp(26)))
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 10f
                setTextColor(0xFF37404D.toInt())
                gravity = Gravity.CENTER
                maxLines = 1
            })
        }
        toolViews[tool] = box
        return FrameLayout(this).apply {
            setPadding(dp(2), dp(2), dp(2), dp(2))
            addView(box, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    private fun selectedBg() = GradientDrawable().apply {
        cornerRadius = dp(10f)
        setColor(ACCENT_BG)
    }

    private fun selectTool(tool: Tool) {
        dv.tool = tool
        for ((t, v) in toolViews) {
            val on = t == tool
            v.background = if (on) selectedBg() else null
            toolIcons[t]?.iconColor = if (on) ACCENT else 0xFF37404D.toInt()
            toolIcons[t]?.invalidateSelf()
            ((v.getChildAt(1)) as TextView).setTextColor(if (on) ACCENT else 0xFF37404D.toInt())
        }
        val value = sizes[tool] ?: 6
        dv.sizeValue = value
        sizeBar.progress = value - 1
        shapeRow.visibility = if (tool == Tool.SHAPE) View.VISIBLE else View.GONE
        sizeRow.visibility = if (tool == Tool.BUCKET) View.GONE else View.VISIBLE
        bucketHint.visibility = if (tool == Tool.BUCKET) View.VISIBLE else View.GONE
        updateSizeUi()
    }

    private fun selectShape(kind: ShapeKind) {
        dv.shapeKind = kind
        for ((k, b) in shapeViews) {
            val on = k == kind
            b.background = if (on) selectedBg() else null
            shapeIcons[k]?.iconColor = if (on) ACCENT else 0xFF37404D.toInt()
            shapeIcons[k]?.invalidateSelf()
        }
    }

    private fun updateSizeUi() {
        val t = dv.tool
        sizeLabel.text = if (t == Tool.TEXT) "文字 ${dv.sizeValue}" else "太さ ${dv.sizeValue}"
        sizePreview.color = if (t == Tool.ERASER_AREA || t == Tool.ERASER_STROKE) 0xFFB0B8C4.toInt() else dv.color
        sizePreview.soft = t == Tool.BLUR
        sizePreview.diameter = if (t == Tool.TEXT) dv.textScreenPx() * 0.6f else if (t == Tool.BRUSH) dv.strokeScreenPx() * 1.6f else dv.strokeScreenPx()
    }

    // =========================================================
    // パレット
    // =========================================================

    private fun addSwatch(c: Int, persist: Boolean) {
        val sw = SwatchView(this, c) { selectColor(it) }
        swatches.add(sw)
        // 「＋」ボタンがあればその手前に入れる
        val index = if (paletteRow.childCount > PALETTE.size) paletteRow.childCount - 1 else paletteRow.childCount
        paletteRow.addView(sw, index, LinearLayout.LayoutParams(dp(42), dp(46)))
        if (persist) {
            val custom = swatches.drop(PALETTE.size).joinToString(",") { it.color.toString() }
            prefs.edit().putString("customColors", custom).apply()
        }
    }

    private fun selectColor(c: Int) {
        dv.color = c
        for (s in swatches) s.isChosen = s.color == c
        updateSizeUi()
        // 消しゴム中に色を選んだらペンに戻す
        if (dv.tool == Tool.ERASER_AREA || dv.tool == Tool.ERASER_STROKE) selectTool(Tool.PEN)
    }

    private fun showColorPicker() {
        val hsv = FloatArray(3)
        Color.colorToHSV(dv.color, hsv)
        val preview = View(this)
        fun refresh() = preview.setBackgroundColor(Color.HSVToColor(hsv))
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), 0)
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        }
        for ((i, label, max) in listOf(Triple(0, "色相", 360), Triple(1, "彩度", 100), Triple(2, "明るさ", 100))) {
            box.addView(TextView(this).apply {
                text = label
                textSize = 12f
                setPadding(0, dp(12), 0, 0)
            })
            box.addView(SeekBar(this).apply {
                this.max = max
                progress = if (i == 0) hsv[0].toInt() else (hsv[i] * 100).toInt()
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        hsv[i] = if (i == 0) p.toFloat() else p / 100f
                        refresh()
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            })
        }
        refresh()
        AlertDialog.Builder(this)
            .setTitle("色を追加")
            .setView(box)
            .setPositiveButton("追加") { _, _ ->
                val c = Color.HSVToColor(hsv)
                if (swatches.none { it.color == c }) addSwatch(c, persist = true)
                selectColor(c)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    // =========================================================
    // テキスト
    // =========================================================

    override fun onTextRequest(worldX: Float, worldY: Float, existing: TextElement?) {
        val et = EditText(this).apply {
            setText(existing?.text ?: "")
            hint = "テキストを入力"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
            setSelection(text.length)
        }
        val box = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(et)
        }
        val b = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "テキストを追加" else "テキストを編集")
            .setView(box)
            .setPositiveButton("OK") { _, _ -> dv.commitText(worldX, worldY, existing, et.text.toString()) }
            .setNegativeButton("キャンセル", null)
        if (existing != null) b.setNeutralButton("削除") { _, _ -> dv.commitText(worldX, worldY, existing, "") }
        val dialog = b.create()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        dialog.show()
        et.requestFocus()
    }

    override fun onHistoryChanged() {
        undoBtn.isEnabled = dv.canUndo
        undoBtn.alpha = if (dv.canUndo) 1f else 0.3f
        redoBtn.isEnabled = dv.canRedo
        redoBtn.alpha = if (dv.canRedo) 1f else 0.3f
    }

    // =========================================================
    // ノート管理
    // =========================================================

    private fun saveNow() {
        if (noteId.isEmpty()) return
        val json = NoteStore.encode(NoteData(dv.paper, dv.scale, dv.tx, dv.ty, ArrayList(dv.elements)))
        val id = noteId
        thread { store.saveEncoded(id, json) }
    }

    private fun openNote(id: String) {
        val data = store.load(id) ?: NoteData(PaperType.GRID, 1f, 0f, 0f, emptyList())
        noteId = id
        dv.load(data)
        titleView.text = store.list().find { it.id == id }?.name ?: ""
        prefs.edit().putString("lastNote", id).apply()
    }

    private fun createNote(paper: PaperType) {
        saveNow()
        val name = "ノート " + SimpleDateFormat("M/d HH:mm", Locale.JAPAN).format(Date())
        val meta = store.create(name, paper)
        openNote(meta.id)
    }

    private fun showNotes() {
        saveNow()
        val notes = store.list()
        val fmt = SimpleDateFormat("yyyy/M/d HH:mm", Locale.JAPAN)
        val labels = listOf("＋ 新しいノート") + notes.map {
            (if (it.id == noteId) "● " else "　") + it.name + "\n　　更新 " + fmt.format(Date(it.updated))
        }
        val lv = ListView(this)
        lv.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        val dialog = AlertDialog.Builder(this)
            .setTitle("ノート（長押しで名前変更・削除）")
            .setView(lv)
            .setNegativeButton("閉じる", null)
            .create()
        lv.setOnItemClickListener { _, _, pos, _ ->
            dialog.dismiss()
            if (pos == 0) showNewNote() else if (notes[pos - 1].id != noteId) {
                openNote(notes[pos - 1].id)
            }
        }
        lv.setOnItemLongClickListener { _, _, pos, _ ->
            if (pos > 0) {
                dialog.dismiss()
                showNoteMenu(notes[pos - 1])
            }
            true
        }
        dialog.show()
    }

    private fun showNewNote() {
        AlertDialog.Builder(this)
            .setTitle("用紙を選んで新規作成")
            .setItems(PAPER_LABELS) { _, which -> createNote(PaperType.entries[which]) }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showNoteMenu(meta: NoteMeta) {
        AlertDialog.Builder(this)
            .setTitle(meta.name)
            .setItems(arrayOf("名前を変更", "削除")) { _, which ->
                if (which == 0) renameNote(meta) else deleteNote(meta)
            }
            .show()
    }

    private fun renameNote(meta: NoteMeta) {
        val et = EditText(this).apply {
            setText(meta.name)
            setSingleLine()
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("名前を変更")
            .setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(et) })
            .setPositiveButton("OK") { _, _ ->
                val n = et.text.toString().trim()
                if (n.isNotEmpty()) {
                    store.rename(meta.id, n)
                    if (meta.id == noteId) titleView.text = n
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun deleteNote(meta: NoteMeta) {
        AlertDialog.Builder(this)
            .setTitle("「${meta.name}」を削除しますか？")
            .setMessage("この操作は取り消せません。")
            .setPositiveButton("削除") { _, _ ->
                store.delete(meta.id)
                if (meta.id == noteId) {
                    noteId = ""
                    val rest = store.list()
                    if (rest.isNotEmpty()) openNote(rest[0].id) else createNote(PaperType.GRID)
                }
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun showPaperPicker() {
        AlertDialog.Builder(this)
            .setTitle("用紙")
            .setSingleChoiceItems(PAPER_LABELS, dv.paper.ordinal) { d, which ->
                dv.paper = PaperType.entries[which]
                d.dismiss()
                saveNow()
            }
            .show()
    }

    private fun confirmClear() {
        if (dv.elements.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("ページをすべて消しますか？")
            .setMessage("「戻る」で元に戻せます。")
            .setPositiveButton("消す") { _, _ -> dv.clearAll() }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    private fun exportImage() {
        val bmp = dv.renderImage()
        if (bmp == null) {
            Toast.makeText(this, "まだ何も描かれていません", Toast.LENGTH_SHORT).show()
            return
        }
        val name = "note_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".png"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DrawingNote")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        thread {
            val ok = try {
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                true
            } catch (e: Exception) {
                false
            } finally {
                bmp.recycle()
            }
            runOnUiThread {
                Toast.makeText(
                    this,
                    if (ok) "画像を保存しました（ピクチャ/DrawingNote）" else "画像を保存できませんでした",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    companion object {
        private const val ACCENT = 0xFF1E6FD9.toInt()
        private const val ACCENT_BG = 0xFFDCE8FB.toInt()
        private const val BAR_BG = 0xFFF3F4F7.toInt()
        private val PAPER_LABELS = arrayOf("白紙", "ラインノート", "グリッド")
        private val PALETTE = intArrayOf(
            0xFF212121.toInt(), 0xFF757575.toInt(), 0xFFFFFFFF.toInt(), 0xFFE53935.toInt(),
            0xFFFB8C00.toInt(), 0xFFFDD835.toInt(), 0xFF7CB342.toInt(), 0xFF2E7D32.toInt(),
            0xFF00ACC1.toInt(), 0xFF1E88E5.toInt(), 0xFF283593.toInt(), 0xFF8E24AA.toInt(),
            0xFFEC407A.toInt(), 0xFF6D4C41.toInt(), 0xFFFFCCBC.toInt(), 0xFFB3E5FC.toInt(),
        )
    }
}
