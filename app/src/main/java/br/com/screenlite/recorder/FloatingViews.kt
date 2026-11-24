package br.com.screenlite.recorder

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Botões desenhados em código (sem imagens): gravar/parar, pasta, engrenagem e X. */
class IconButton(context: Context, private val kind: Int) : View(context) {
    companion object {
        const val REC = 0
        const val FOLDER = 1
        const val GEAR = 2
        const val CLOSE = 3
    }

    var recording = false
        set(value) { field = value; invalidate() }

    private val d = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val s = minOf(w, h); val cx = w / 2f; val cy = h / 2f
        when (kind) {
            REC -> {
                if (!recording) {
                    fill.color = 0xFFEF3340.toInt()
                    c.drawCircle(cx, cy, s * 0.32f, fill)
                    line.color = Color.WHITE; line.strokeWidth = 2.2f * d
                    c.drawCircle(cx, cy, s * 0.44f, line)
                } else {
                    line.color = 0xFFD8D8DB.toInt(); line.strokeWidth = 2.2f * d
                    c.drawCircle(cx, cy, s * 0.44f, line)
                    fill.color = 0xFFE8590C.toInt()
                    val r = s * 0.17f
                    c.drawRoundRect(RectF(cx - r, cy - r, cx + r, cy + r), s * 0.04f, s * 0.04f, fill)
                }
            }
            FOLDER -> {
                line.color = 0xFFD9D9DC.toInt(); line.strokeWidth = 1.7f * d
                val l = cx - s * 0.26f; val r = cx + s * 0.26f
                val t = cy - s * 0.18f; val b = cy + s * 0.22f
                val p = Path()
                p.moveTo(l, t + s * 0.08f); p.lineTo(l, t); p.lineTo(cx - s * 0.04f, t)
                p.lineTo(cx + s * 0.02f, t + s * 0.08f); p.lineTo(r, t + s * 0.08f)
                p.lineTo(r, b); p.lineTo(l, b); p.close()
                c.drawPath(p, line)
                c.drawLine(l, t + s * 0.16f, r, t + s * 0.16f, line)
            }
            GEAR -> {
                line.color = 0xFFD9D9DC.toInt(); line.strokeWidth = 1.7f * d
                val radius = s * 0.30f
                val p = Path()
                for (i in 0..5) {
                    val a = Math.toRadians(30.0 + 60.0 * i)
                    val x = cx + radius * cos(a).toFloat()
                    val y = cy + radius * sin(a).toFloat()
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                c.drawPath(p, line)
                c.drawCircle(cx, cy, s * 0.09f, line)
            }
            CLOSE -> {
                line.color = 0xFFD9D9DC.toInt(); line.strokeWidth = 1.9f * d
                val k = s * 0.20f
                c.drawLine(cx - k, cy - k, cx + k, cy + k, line)
                c.drawLine(cx - k, cy + k, cx + k, cy - k, line)
            }
        }
    }
}

/** Painel flutuante que pode ser arrastado pela tela. */
class DragPill(
    context: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams
) : LinearLayout(context) {
    private var downX = 0f
    private var downY = 0f
    private var startX = 0
    private var startY = 0
    private var dragging = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private fun begin(ev: MotionEvent) {
        downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; dragging = false
    }

    private fun moveTo(ev: MotionEvent) {
        val dm = resources.displayMetrics
        val maxX = (dm.widthPixels - width).coerceAtLeast(0)
        val maxY = (dm.heightPixels - height).coerceAtLeast(0)
        lp.x = (startX + (ev.rawX - downX).toInt()).coerceIn(0, maxX)
        lp.y = (startY + (ev.rawY - downY).toInt()).coerceIn(0, maxY)
        try { wm.updateViewLayout(this, lp) } catch (_: Exception) { }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE ->
                if (!dragging && (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)) dragging = true
        }
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && (abs(ev.rawX - downX) > slop || abs(ev.rawY - downY) > slop)) dragging = true
                if (dragging) moveTo(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }
}
