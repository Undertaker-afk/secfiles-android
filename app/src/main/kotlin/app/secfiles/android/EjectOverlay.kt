package app.secfiles.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * A small grey X on the right edge of the screen, drawn over every other app while a volume is unlocked.
 * One tap locks everything: caches are flushed, plaintext buffers and keys are zeroed, and the drive
 * disappears from other apps. Needs the "display over other apps" permission.
 */
class EjectOverlay(private val context: Context, private val onEject: () -> Unit) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null

    fun show() {
        if (view != null || !Settings.canDrawOverlays(context)) return
        val density = context.resources.displayMetrics.density
        val size = (52 * density).toInt()
        val v = XButton(context, density).apply {
            isClickable = true
            contentDescription = "Eject: lock all volumes now"
            setOnClickListener { onEject() }
        }
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = (4 * density).toInt()
        }
        runCatching { wm.addView(v, lp); view = v }
    }

    fun hide() {
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

    private class XButton(context: Context, private val density: Float) : View(context) {
        private val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC7A7F87.toInt() }
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFE6E8EB.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 3 * density
            strokeCap = Paint.Cap.ROUND
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            canvas.drawCircle(cx, cy, width / 2f - 2 * density, disc)
            val r = width * 0.17f
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, stroke)
            canvas.drawLine(cx - r, cy + r, cx + r, cy - r, stroke)
        }
    }
}
