package com.meme.stickermachine

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import kotlin.math.max
import kotlin.math.min

/**
 * Tách nền "thông minh": ML Kit Subject Segmentation cho bản đồ độ tin cậy (0..1),
 * rồi tự hậu xử lý để KHÔNG cắt mất thân/tay/chân của nhân vật meme.
 *
 * cut(0)  : cắt chuẩn (ngưỡng 0.5, vá lỗ, bỏ đốm rác).
 * cut(n>0): "xoá lại" – mỗi lần nới ngưỡng thấp hơn nhưng chỉ nhận vùng NỐI LIỀN
 *           với phần chắc chắn (hysteresis), rồi đóng khe hở -> lấy lại phần thân bị cắt lỗi.
 */
class BgRemover {

    private val segmenter = SubjectSegmentation.getClient(
        SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build()
    )
    private var src: Bitmap? = null
    private var conf: FloatArray? = null

    fun prepare(bmp: Bitmap, onDone: (Boolean) -> Unit) {
        src = bmp
        conf = null
        segmenter.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { r ->
                val fb = r.foregroundConfidenceMask
                if (fb == null) { onDone(false); return@addOnSuccessListener }
                val a = FloatArray(bmp.width * bmp.height)
                fb.rewind(); fb.get(a)
                conf = a
                onDone(true)
            }
            .addOnFailureListener { onDone(false) }
    }

    /** Gọi trên thread nền. */
    fun cut(attempt: Int): Bitmap {
        val bmp = src!!
        val c = conf!!
        val w = bmp.width
        val h = bmp.height
        val n = w * h

        var m: BooleanArray
        if (attempt == 0) {
            m = BooleanArray(n) { c[it] >= 0.5f }
        } else {
            val strong = BooleanArray(n) { c[it] >= 0.5f }
            val low = max(0.05f, 0.5f - 0.13f * attempt)
            m = hysteresis(strong, c, low, w, h)
            val r = min(1 + attempt, 6)
            m = morph(morph(m, w, h, r, true), w, h, r, false) // đóng khe hở
        }
        fillHoles(m, w, h, 0.02f + 0.015f * attempt)
        dropIslands(m, w, h)

        // Viền mềm + ghép alpha
        val px = IntArray(n)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0
            for (dy in -1..1) {
                val yy = y + dy
                if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx
                    if (xx < 0 || xx >= w) continue
                    if (m[yy * w + xx]) s++
                }
            }
            val i = y * w + x
            val a = if (m[i]) (if (s == 9) 255 else 160 + s * 95 / 9) else s * 140 / 9
            if (a > 0) {
                px[i] = (a shl 24) or (px[i] and 0xFFFFFF)
                if (x < minX) minX = x; if (x > maxX) maxX = x
                if (y < minY) minY = y; if (y > maxY) maxY = y
            } else px[i] = 0
        }
        val full = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        if (maxX < 0) return full
        val x0 = max(0, minX - 6); val y0 = max(0, minY - 6)
        val x1 = min(w - 1, maxX + 6); val y1 = min(h - 1, maxY + 6)
        return Bitmap.createBitmap(full, x0, y0, x1 - x0 + 1, y1 - y0 + 1)
    }

    // ---------- hậu xử lý mask ----------

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    private fun hysteresis(strong: BooleanArray, c: FloatArray, low: Float, w: Int, h: Int): BooleanArray {
        val out = strong.copyOf()
        val q = IntArray(w * h)
        var head = 0; var tail = 0
        for (i in out.indices) if (out[i]) q[tail++] = i
        while (head < tail) {
            val p = q[head++]
            val x = p % w; val y = p / w
            for (d in 0 until 4) {
                val nx = x + DX[d]; val ny = y + DY[d]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val nb = ny * w + nx
                if (!out[nb] && c[nb] >= low) { out[nb] = true; q[tail++] = nb }
            }
        }
        return out
    }

    private fun morph(m: BooleanArray, w: Int, h: Int, r: Int, dilate: Boolean): BooleanArray {
        val t = BooleanArray(m.size)
        val o = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var v = !dilate
            for (k in max(0, x - r)..min(w - 1, x + r)) if (m[y * w + k] == dilate) { v = dilate; break }
            t[y * w + x] = v
        }
        for (y in 0 until h) for (x in 0 until w) {
            var v = !dilate
            for (k in max(0, y - r)..min(h - 1, y + r)) if (t[k * w + x] == dilate) { v = dilate; break }
            o[y * w + x] = v
        }
        return o
    }

    private fun components(
        m: BooleanArray, value: Boolean, w: Int, h: Int,
        cb: (q: IntArray, size: Int, border: Boolean) -> Unit
    ) {
        val n = w * h
        val seen = BooleanArray(n)
        val q = IntArray(n)
        for (s in 0 until n) {
            if (seen[s] || m[s] != value) continue
            var head = 0; var tail = 0; var border = false
            q[tail++] = s; seen[s] = true
            while (head < tail) {
                val p = q[head++]
                val x = p % w; val y = p / w
                if (x == 0 || y == 0 || x == w - 1 || y == h - 1) border = true
                for (d in 0 until 4) {
                    val nx = x + DX[d]; val ny = y + DY[d]
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val nb = ny * w + nx
                    if (!seen[nb] && m[nb] == value) { seen[nb] = true; q[tail++] = nb }
                }
            }
            cb(q, tail, border)
        }
    }

    /** Vá các lỗ nền nằm lọt trong nhân vật (không chạm viền ảnh, nhỏ hơn maxFrac diện tích). */
    private fun fillHoles(m: BooleanArray, w: Int, h: Int, maxFrac: Float) {
        val limit = (w * h * maxFrac).toInt()
        components(m, false, w, h) { q, size, border ->
            if (!border && size <= limit) for (i in 0 until size) m[q[i]] = true
        }
    }

    /** Bỏ đốm rác, giữ vùng chính và các mảng lớn (tay, chữ, đạo cụ). */
    private fun dropIslands(m: BooleanArray, w: Int, h: Int) {
        var biggest = 0
        components(m, true, w, h) { _, size, _ -> if (size > biggest) biggest = size }
        val keep = (biggest * 0.02f).toInt()
        components(m, true, w, h) { q, size, _ ->
            if (size < keep) for (i in 0 until size) m[q[i]] = false
        }
    }
}
