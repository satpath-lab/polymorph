package com.example.showcase

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import dev.volt.player.VoltPlugin
import dev.volt.player.VoltViewPlugin
import org.json.JSONObject

class Gauge : VoltViewPlugin {
    class GaugeView(c: Context) : View(c) {
        var value = 0f
        private val dp = c.resources.displayMetrics.density
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = 14 * dp }
        private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 28 * dp; isFakeBoldText = true; color = 0xFF1E1B4B.toInt() }
        override fun onDraw(c: Canvas) {
            val r = minOf(width, height * 2) / 2f - 16 * dp
            val box = RectF(width / 2f - r, height - r - 8 * dp, width / 2f + r, height + r - 8 * dp)
            p.color = 0xFFE0E7FF.toInt(); c.drawArc(box, 180f, 180f, false, p)
            p.color = 0xFF4F46E5.toInt(); c.drawArc(box, 180f, 180f * value / 100f, false, p)
            c.drawText("${value.toInt()}%", width / 2f, height - 16 * dp, t)
        }
    }

    override fun create(host: VoltPlugin.Host, props: JSONObject): View =
        GaugeView(host.activity()).apply { layoutParams = ViewGroup.LayoutParams(-1, (140 * resources.displayMetrics.density).toInt()); value = props.optDouble("value", 0.0).toFloat() }

    override fun update(view: View, props: JSONObject) {
        (view as GaugeView).value = props.optDouble("value", 0.0).toFloat(); view.invalidate()
    }
}
