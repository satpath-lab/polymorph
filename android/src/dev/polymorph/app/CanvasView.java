package dev.polymorph.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

/** Draws a list of ops in dp units: rect, circle, line, path, text, arc. Taps report {x, y} in dp. */
public class CanvasView extends View {
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final float dp;
    JSONArray ops = new JSONArray();
    interface Tap { void at(float x, float y); }
    Tap onTap;

    public CanvasView(Context c) { super(c); dp = c.getResources().getDisplayMetrics().density; }

    void set(JSONArray o) { ops = o == null ? new JSONArray() : o; invalidate(); }

    float d(JSONObject o, String k) { return (float) o.optDouble(k, 0) * dp; }

    @Override protected void onDraw(Canvas c) {
        for (int i = 0; i < ops.length(); i++) {
            JSONObject o = ops.optJSONObject(i);
            if (o == null) continue;
            p.reset(); p.setAntiAlias(true);
            p.setColor(MainActivity.color(o.optString("color", ""), 0xFF1F2328));
            boolean stroke = o.has("stroke") || o.optString("op").equals("line");
            p.setStyle(stroke && !o.optBoolean("fill") ? Paint.Style.STROKE : Paint.Style.FILL);
            p.setStrokeWidth((float) o.optDouble("width", o.optDouble("stroke", 2)) * dp);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setAlpha((int) (255 * o.optDouble("opacity", 1)));
            switch (o.optString("op")) {
                case "rect": c.drawRoundRect(new RectF(d(o, "x"), d(o, "y"), d(o, "x") + d(o, "w"), d(o, "y") + d(o, "h")), d(o, "radius"), d(o, "radius"), p); break;
                case "circle": c.drawCircle(d(o, "x"), d(o, "y"), d(o, "r"), p); break;
                case "line": c.drawLine(d(o, "x1"), d(o, "y1"), d(o, "x2"), d(o, "y2"), p); break;
                case "arc": c.drawArc(new RectF(d(o, "x") - d(o, "r"), d(o, "y") - d(o, "r"), d(o, "x") + d(o, "r"), d(o, "y") + d(o, "r")),
                        (float) o.optDouble("start", 0), (float) o.optDouble("sweep", 90), !stroke, p); break;
                case "text":
                    p.setStyle(Paint.Style.FILL); p.setTextSize((float) o.optDouble("size", 14) * dp);
                    p.setFakeBoldText(o.optBoolean("bold"));
                    p.setTextAlign("center".equals(o.optString("align")) ? Paint.Align.CENTER : "end".equals(o.optString("align")) ? Paint.Align.RIGHT : Paint.Align.LEFT);
                    c.drawText(o.optString("text"), d(o, "x"), d(o, "y"), p); break;
                case "path": {
                    JSONArray pts = o.optJSONArray("points");
                    if (pts == null || pts.length() == 0) break;
                    Path path = new Path();
                    for (int j = 0; j < pts.length(); j++) {
                        JSONArray pt = pts.optJSONArray(j); if (pt == null) continue;
                        float x = (float) pt.optDouble(0) * dp, y = (float) pt.optDouble(1) * dp;
                        if (j == 0) path.moveTo(x, y); else path.lineTo(x, y);
                    }
                    if (o.optBoolean("close")) path.close();
                    c.drawPath(path, p); break;
                }
            }
        }
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (onTap == null) return false;
        if (e.getAction() == MotionEvent.ACTION_UP) onTap.at(e.getX() / dp, e.getY() / dp);
        return true;
    }
}
