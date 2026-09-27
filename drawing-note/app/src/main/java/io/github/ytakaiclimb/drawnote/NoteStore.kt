package io.github.ytakaiclimb.drawnote

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class NoteData(
    val paper: PaperType,
    val scale: Float,
    val tx: Float,
    val ty: Float,
    val elements: List<Element>,
)

class NoteMeta(val id: String, var name: String, var updated: Long)

/** ノートを端末内（アプリ専用領域）に JSON で保存する。 */
class NoteStore(context: Context) {
    private val dir = File(context.filesDir, "notes").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")

    fun list(): MutableList<NoteMeta> {
        if (!indexFile.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(indexFile.readText())
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                NoteMeta(o.getString("id"), o.getString("name"), o.getLong("updated"))
            }.sortedByDescending { it.updated }.toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeIndex(list: List<NoteMeta>) {
        val arr = JSONArray()
        for (m in list) arr.put(JSONObject().put("id", m.id).put("name", m.name).put("updated", m.updated))
        writeAtomic(indexFile, arr.toString())
    }

    @Synchronized
    fun create(name: String, paper: PaperType): NoteMeta {
        val meta = NoteMeta(UUID.randomUUID().toString(), name, System.currentTimeMillis())
        writeAtomic(File(dir, "${meta.id}.json"), encode(NoteData(paper, 1f, 0f, 0f, emptyList())))
        writeIndex(list() + meta)
        return meta
    }

    @Synchronized
    fun rename(id: String, name: String) {
        val l = list()
        l.find { it.id == id }?.name = name
        writeIndex(l)
    }

    @Synchronized
    fun delete(id: String) {
        File(dir, "$id.json").delete()
        writeIndex(list().filter { it.id != id })
    }

    fun load(id: String): NoteData? {
        val f = File(dir, "$id.json")
        if (!f.exists()) return null
        return try {
            decode(f.readText())
        } catch (e: Exception) {
            null
        }
    }

    /** JSON 文字列を書き込む（呼び出し側でバックグラウンド実行してよい）。 */
    @Synchronized
    fun saveEncoded(id: String, json: String) {
        writeAtomic(File(dir, "$id.json"), json)
        val l = list()
        l.find { it.id == id }?.updated = System.currentTimeMillis()
        writeIndex(l)
    }

    private fun writeAtomic(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(f)
    }

    companion object {
        fun encode(d: NoteData): String {
            val arr = JSONArray()
            for (e in d.elements) arr.put(encodeElement(e))
            return JSONObject()
                .put("v", 1)
                .put("paper", d.paper.name)
                .put("scale", d.scale.toDouble())
                .put("tx", d.tx.toDouble())
                .put("ty", d.ty.toDouble())
                .put("elements", arr)
                .toString()
        }

        private fun floats(vararg v: Float) = JSONArray().also { a -> v.forEach { a.put(it.toDouble()) } }

        private fun encodeElement(e: Element): JSONObject = when (e) {
            is StrokeElement -> {
                val p = JSONArray()
                for (i in 0 until e.size) {
                    p.put(round2(e.xs[i])); p.put(round2(e.ys[i])); p.put(round2(e.ws[i]))
                }
                JSONObject().put("t", "stroke").put("k", e.kind.name).put("c", e.color).put("w", e.width.toDouble()).put("p", p)
            }
            is ShapeElement -> JSONObject().put("t", "shape").put("k", e.kind.name).put("c", e.color)
                .put("w", e.width.toDouble()).put("p", floats(e.x1, e.y1, e.x2, e.y2))
            is TextElement -> JSONObject().put("t", "text").put("c", e.color).put("s", e.size.toDouble())
                .put("p", floats(e.x, e.y)).put("v", e.text)
            is FillElement -> {
                val enc = e.encoded ?: ByteArrayOutputStream().use { out ->
                    e.bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
                }.also { e.encoded = it }
                val b = e.bounds
                JSONObject().put("t", "fill").put("p", floats(b.left, b.top, b.right, b.bottom)).put("b", enc)
            }
        }

        private fun round2(f: Float): Double = Math.round(f * 100.0) / 100.0

        fun decode(s: String): NoteData {
            val o = JSONObject(s)
            val arr = o.getJSONArray("elements")
            val list = ArrayList<Element>(arr.length())
            for (i in 0 until arr.length()) {
                decodeElement(arr.getJSONObject(i))?.let { list.add(it) }
            }
            return NoteData(
                runCatching { PaperType.valueOf(o.getString("paper")) }.getOrDefault(PaperType.GRID),
                o.optDouble("scale", 1.0).toFloat(),
                o.optDouble("tx", 0.0).toFloat(),
                o.optDouble("ty", 0.0).toFloat(),
                list,
            )
        }

        private fun decodeElement(o: JSONObject): Element? {
            val p = o.getJSONArray("p")
            fun f(i: Int) = p.getDouble(i).toFloat()
            return when (o.getString("t")) {
                "stroke" -> StrokeElement(StrokeKind.valueOf(o.getString("k")), o.getInt("c"), o.getDouble("w").toFloat()).also {
                    var i = 0
                    while (i + 2 < p.length()) {
                        it.add(f(i), f(i + 1), f(i + 2))
                        i += 3
                    }
                }.takeIf { it.size > 0 }
                "shape" -> ShapeElement(ShapeKind.valueOf(o.getString("k")), o.getInt("c"), o.getDouble("w").toFloat(), f(0), f(1), f(2), f(3))
                "text" -> TextElement(f(0), f(1), o.getString("v"), o.getInt("c"), o.getDouble("s").toFloat())
                "fill" -> {
                    val enc = o.getString("b")
                    val bytes = Base64.decode(enc, Base64.NO_WRAP)
                    val opts = BitmapFactory.Options().apply { inPremultiplied = true }
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
                    FillElement(bmp, RectF(f(0), f(1), f(2), f(3))).also { it.encoded = enc }
                }
                else -> null
            }
        }
    }
}
