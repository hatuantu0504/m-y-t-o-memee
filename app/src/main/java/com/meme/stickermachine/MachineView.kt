package com.meme.stickermachine

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Cái máy lọc: phễu (thả ảnh) -> thân máy (gear quay, rung, đèn nháy) -> khe nhả -> khay (nền caro).
 * Xoá lại: ảnh trong khay bị hút ngược vào khe (rọt rọt) -> máy chạy lại -> nhả ảnh mới.
 */
class MachineView(ctx: Context) : View(ctx) {

    enum class Phase { IDLE, FEED, PROCESS, OUT, DONE, SUCK }

    var onFunnelTap: (() -> Unit)? = null
    var onPhase: ((Phase) -> Unit)? = null
    var phase = Phase.IDLE
        private set

    private var t0 = 0L
    private var input: Bitmap? = null
    private var output: Bitmap? = null
    private var pending: Bitmap? = null

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val funnel = RectF()
    private val body = RectF()
    private val tray = RectF()
    private var slotY = 0f

    // ---------- điều khiển từ Activity ----------

    fun startFeed(bmp: Bitmap) {
        input = bmp; output = null; pending = null
        Slurp.play(1)
        setPhase(Phase.FEED)
    }

    fun startRedo() {
        if (output == null) return
        pending = null
        Slurp.play(2) // rọt rọt
        setPhase(Phase.SUCK)
    }

    fun deliver(bmp: Bitmap) { pending = bmp; invalidate() }

    fun reset() { input = null; output = null; pending = null; setPhase(Phase.IDLE) }

    private fun setPhase(p: Phase) {
        phase = p
        t0 = SystemClock.uptimeMillis()
        onPhase?.invoke(p)
        invalidate()
    }

    // ---------- bố cục ----------

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val wf = w.toFloat(); val hf = h.toFloat()
        funnel.set(wf * 0.08f, hf * 0.03f, wf * 0.92f, hf * 0.20f)
        body.set(wf * 0.10f, hf * 0.19f, wf * 0.90f, hf * 0.56f)
        slotY = body.bottom
        tray.set(wf * 0.08f, hf * 0.585f, wf * 0.92f, hf * 0.98f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP &&
            (phase == Phase.IDLE || phase == Phase.DONE) && e.y < body.top + 10 * d
        ) {
            onFunnelTap?.invoke()
        }
        return true
    }

    // ---------- vẽ ----------

    private fun easeOut(x: Float): Float { val c = x.coerceIn(0f, 1f); return 1f - (1f - c) * (1f - c) * (1f - c) }
    private fun easeIn(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c }
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    override fun onDraw(c: Canvas) {
        val now = SystemClock.uptimeMillis()
        var t = now - t0

        when (phase) {
            Phase.FEED -> if (t >= 900) { setPhase(Phase.PROCESS); t = 0 }
            Phase.PROCESS -> if (pending != null && t >= 1400) {
                output = pending; pending = null
                Slurp.play(1)
                setPhase(Phase.OUT); t = 0
            }
            Phase.OUT -> if (t >= 900) { setPhase(Phase.DONE); t = 0 }
            Phase.SUCK -> if (t >= 1100) { output = null; setPhase(Phase.PROCESS); t = 0 }
            else -> {}
        }

        c.drawColor(0xFFEFF3F8.toInt())
        drawTray(c)

        // Ảnh ra / bị hút (cắt tại khe nên chỉ thấy phần bên dưới thân máy)
        val out = output
        if (out != null && (phase == Phase.OUT || phase == Phase.DONE || phase == Phase.SUCK)) {
            val q = when (phase) {
                Phase.OUT -> easeOut(t / 900f)
                Phase.SUCK -> 1f - easeIn(t / 1100f)
                else -> 1f
            }
            val wobble = if (phase == Phase.SUCK) sin(t / 55.0).toFloat() * 7f * q else 0f
            c.save()
            c.clipRect(0f, slotY, width.toFloat(), height.toFloat())
            drawOut(c, out, q, wobble)
            c.restore()
        }

        drawFunnel(c)

        // Ảnh rơi trong phễu rồi chui vào máy
        val inp = input
        if (phase == Phase.FEED && inp != null) {
            val pr = easeIn(t / 900f)
            val fit = min(width * 0.5f / inp.width, funnel.height() * 0.8f / inp.height)
            val s = fit * (1f - 0.65f * pr)
            val cy = lerp(funnel.top + funnel.height() * 0.45f, body.centerY(), pr)
            rect.set(
                funnel.centerX() - inp.width * s / 2, cy - inp.height * s / 2,
                funnel.centerX() + inp.width * s / 2, cy + inp.height * s / 2
            )
            c.drawBitmap(inp, null, rect, bmpPaint)
        }

        drawBody(c, now, t)

        if (phase != Phase.IDLE && phase != Phase.DONE) postInvalidateOnAnimation()
    }

    private fun drawOut(c: Canvas, b: Bitmap, q: Float, wobble: Float) {
        val fit = min(tray.width() * 0.9f / b.width, tray.height() * 0.9f / b.height)
        val s = fit * (0.55f + 0.45f * q)
        val iw = b.width * s
        val ih = b.height * s
        val cx = tray.centerX()
        val cy = lerp(slotY - ih / 2f, tray.centerY(), q)
        c.save()
        c.rotate(wobble, cx, cy)
        rect.set(cx - iw / 2, cy - ih / 2, cx + iw / 2, cy + ih / 2)
        c.drawBitmap(b, null, rect, bmpPaint)
        c.restore()
    }

    private fun drawTray(c: Canvas) {
        val path = Path().apply { addRoundRect(tray, 18 * d, 18 * d, Path.Direction.CW) }
        c.save()
        c.clipPath(path)
        val sq = 14 * d
        var yi = 0
        var y = tray.top
        while (y < tray.bottom) {
            var xi = 0
            var x = tray.left
            while (x < tray.right) {
                paint.color = if ((xi + yi) % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFD9DEE6.toInt()
                c.drawRect(x, y, x + sq, y + sq, paint)
                x += sq; xi++
            }
            y += sq; yi++
        }
        c.restore()
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3 * d
        paint.color = 0xFF868E96.toInt()
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL

        if (output == null && phase != Phase.SUCK) {
            paint.color = 0xFF868E96.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 15 * d
            c.drawText("Sticker sẽ ra ở đây", tray.centerX(), tray.centerY(), paint)
        }
    }

    private fun drawFunnel(c: Canvas) {
        val cx = funnel.centerX()
        val neck = width * 0.11f
        val outer = Path().apply {
            moveTo(funnel.left, funnel.top)
            lineTo(funnel.right, funnel.top)
            lineTo(cx + neck, funnel.bottom)
            lineTo(cx - neck, funnel.bottom)
            close()
        }
        paint.color = 0xFF3B5BDB.toInt()
        c.drawPath(outer, paint)
        val inset = 7 * d
        val inner = Path().apply {
            moveTo(funnel.left + inset, funnel.top + inset)
            lineTo(funnel.right - inset, funnel.top + inset)
            lineTo(cx + neck - inset, funnel.bottom)
            lineTo(cx - neck + inset, funnel.bottom)
            close()
        }
        paint.color = 0xFFDBE4FF.toInt()
        c.drawPath(inner, paint)

        if (phase == Phase.IDLE || phase == Phase.DONE) {
            paint.color = 0xFF3B5BDB.toInt()
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 15 * d
            val label = if (phase == Phase.IDLE) "Chạm phễu để thả meme" else "Chạm phễu để thả meme khác"
            c.drawText(label, cx, funnel.top + funnel.height() * 0.5f, paint)
        }
    }

    private fun drawBody(c: Canvas, now: Long, t: Long) {
        val busy = phase == Phase.PROCESS || phase == Phase.FEED || phase == Phase.SUCK
        c.save()
        if (phase == Phase.PROCESS) c.translate(sin(now / 35.0).toFloat() * 3 * d, 0f)

        paint.color = 0xFF495057.toInt()
        c.drawRoundRect(body, 22 * d, 22 * d, paint)
        paint.color = 0xFF343A40.toInt()
        rect.set(body.left + 10 * d, body.top + 10 * d, body.right - 10 * d, body.bottom - 10 * d)
        c.drawRoundRect(rect, 16 * d, 16 * d, paint)

        // Tiêu đề
        paint.color = 0xFFFFFFFF.toInt()
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = 16 * d
        paint.isFakeBoldText = true
        c.drawText("MÁY XOÁ NỀN", body.centerX(), body.top + 38 * d, paint)
        paint.isFakeBoldText = false

        // Đèn nháy
        for (i in 0..2) {
            val on = busy && ((now / 180 + i) % 3 == 0L)
            paint.color = if (on) listOf(0xFFFF6B6B, 0xFFFFD43B, 0xFF51CF66)[i].toInt() else 0xFF5C636A.toInt()
            c.drawCircle(body.right - (28 + i * 22) * d, body.top + 32 * d, 7 * d, paint)
        }

        // Bánh răng
        val ang = if (busy) now * 0.18f else 0f
        drawGear(c, body.left + body.width() * 0.27f, body.centerY() + 6 * d, body.width() * 0.13f, ang)
        drawGear(c, body.left + body.width() * 0.50f, body.centerY() - 8 * d, body.width() * 0.085f, -ang * 1.5f)
        drawGear(c, body.left + body.width() * 0.73f, body.centerY() + 10 * d, body.width() * 0.11f, ang * 1.2f)

        // Thanh tiến trình
        rect.set(body.left + 32 * d, body.bottom - 40 * d, body.right - 32 * d, body.bottom - 30 * d)
        paint.color = 0xFF5C636A.toInt()
        c.drawRoundRect(rect, 5 * d, 5 * d, paint)
        if (busy) {
            val prog = if (phase == Phase.PROCESS) ((t % 1000) / 1000f) else 0.15f
            rect.right = rect.left + (rect.width()) * prog.coerceIn(0.05f, 1f)
            paint.color = 0xFF51CF66.toInt()
            c.drawRoundRect(rect, 5 * d, 5 * d, paint)
        }

        // Khe nhả
        rect.set(body.left + body.width() * 0.12f, slotY - 8 * d, body.right - body.width() * 0.12f, slotY + 2 * d)
        paint.color = 0xFF111417.toInt()
        c.drawRoundRect(rect, 5 * d, 5 * d, paint)
        c.restore()
    }

    private fun drawGear(c: Canvas, cx: Float, cy: Float, r: Float, angle: Float) {
        c.save()
        c.rotate(angle, cx, cy)
        paint.color = 0xFFADB5BD.toInt()
        for (i in 0 until 8) {
            c.save()
            c.rotate(i * 45f, cx, cy)
            c.drawRect(cx - r * 0.18f, cy - r * 1.28f, cx + r * 0.18f, cy - r * 0.8f, paint)
            c.restore()
        }
        c.drawCircle(cx, cy, r, paint)
        paint.color = 0xFF343A40.toInt()
        c.drawCircle(cx, cy, r * 0.35f, paint)
        c.restore()
    }
}
