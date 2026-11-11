package dev.volt.player;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

public class ChartView extends View {
    static final int[] PALETTE = {0xFF6C3BFF, 0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444, 0xFFEC4899, 0xFF14B8A6, 0xFF8B5CF6};
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final float dp;
    JSONObject spec = new JSONObject();

    public ChartView(Context c) { super(c); dp = c.getResources().getDisplayMetrics().density; }

    void set(JSONObject s) { spec = s; invalidate(); }

    int color(JSONObject series, int i) {
        return MainActivity.color(series == null ? "" : series.optString("color", ""), PALETTE[i % PALETTE.length]);
    }

    JSONArray series() {
        JSONArray s = spec.optJSONArray("series");
        if (s != null) return s;
        JSONArray one = new JSONArray();
        try { one.put(new JSONObject().put("values", spec.optJSONArray("data") == null ? new JSONArray() : spec.optJSONArray("data"))); } catch (Exception ignored) {}
        return one;
    }

    static String fmt(double v) {
        if (Math.abs(v) >= 1e6) return String.format("%.1fM", v / 1e6);
        if (Math.abs(v) >= 1e3) return String.format("%.1fk", v / 1e3);
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format("%.1f", v);
    }

    @Override protected void onDraw(Canvas c) {
        String kind = spec.optString("kind", "line");
        JSONArray ser = series();
        float w = getWidth(), h = getHeight();
        int textColor = MainActivity.color(spec.optString("textColor", ""), 0xFF6B7280);
        if (kind.equals("pie") || kind.equals("donut")) {
            JSONObject s0 = ser.optJSONObject(0);
            JSONArray vals = s0 == null ? new JSONArray() : s0.optJSONArray("values");
            JSONArray colors = spec.optJSONArray("colors");
            if (vals == null) return;
            double total = 0;
            for (int i = 0; i < vals.length(); i++) total += Math.max(0, vals.optDouble(i, 0));
            if (total <= 0) return;
            float r = Math.min(w, h) / 2f - 4 * dp;
            RectF box = new RectF(w / 2 - r, h / 2 - r, w / 2 + r, h / 2 + r);
            float start = -90;
            p.setStyle(Paint.Style.FILL);
            for (int i = 0; i < vals.length(); i++) {
                float sweep = (float) (Math.max(0, vals.optDouble(i, 0)) / total * 360);
                p.setColor(colors != null ? MainActivity.color(colors.optString(i), PALETTE[i % PALETTE.length]) : PALETTE[i % PALETTE.length]);
                c.drawArc(box, start, sweep, true, p);
                start += sweep;
            }
            if (kind.equals("donut")) {
                p.setColor(MainActivity.color(spec.optString("hole", ""), 0xFFFFFFFF));
                c.drawCircle(w / 2, h / 2, r * 0.6f, p);
            }
            return;
        }
        double lo = 0, hi = 0;
        int n = 0;
        for (int s = 0; s < ser.length(); s++) {
            JSONArray v = ser.optJSONObject(s) == null ? null : ser.optJSONObject(s).optJSONArray("values");
            if (v == null) continue;
            n = Math.max(n, v.length());
            for (int i = 0; i < v.length(); i++) { lo = Math.min(lo, v.optDouble(i, 0)); hi = Math.max(hi, v.optDouble(i, 0)); }
        }
        if (n == 0) return;
        if (hi == lo) hi = lo + 1;
        float L = 40 * dp, R = 8 * dp, T = 8 * dp, B = 22 * dp;
        float pw = w - L - R, ph = h - T - B;
        p.setTextSize(11 * dp);
        p.setStrokeWidth(1);
        for (int g = 0; g <= 4; g++) {
            double val = lo + (hi - lo) * g / 4;
            float y = T + ph - (float) ((val - lo) / (hi - lo)) * ph;
            p.setColor(0x14000000); p.setStyle(Paint.Style.STROKE);
            c.drawLine(L, y, w - R, y, p);
            p.setColor(textColor); p.setStyle(Paint.Style.FILL); p.setTextAlign(Paint.Align.RIGHT);
            c.drawText(fmt(val), L - 6 * dp, y + 4 * dp, p);
        }
        JSONArray labels = spec.optJSONArray("labels");
        float slot = pw / n;
        if (labels != null) {
            p.setTextAlign(Paint.Align.CENTER); p.setColor(textColor);
            int every = Math.max(1, (int) Math.ceil(n / (pw / (48 * dp))));
            for (int i = 0; i < Math.min(n, labels.length()); i += every)
                c.drawText(labels.optString(i), L + slot * i + slot / 2, h - 6 * dp, p);
        }
        float zeroY = T + ph - (float) ((0 - lo) / (hi - lo)) * ph;
        for (int s = 0; s < ser.length(); s++) {
            JSONObject so = ser.optJSONObject(s);
            JSONArray v = so == null ? null : so.optJSONArray("values");
            if (v == null) continue;
            p.setColor(color(so, s));
            if (kind.equals("bar")) {
                float bw = slot * 0.8f / ser.length();
                p.setStyle(Paint.Style.FILL);
                for (int i = 0; i < v.length(); i++) {
                    float x = L + slot * i + slot * 0.1f + bw * s;
                    float y = T + ph - (float) ((v.optDouble(i, 0) - lo) / (hi - lo)) * ph;
                    c.drawRoundRect(new RectF(x, Math.min(y, zeroY), x + bw - 2 * dp, Math.max(y, zeroY)), 3 * dp, 3 * dp, p);
                }
            } else {
                Path path = new Path();
                for (int i = 0; i < v.length(); i++) {
                    float x = L + slot * i + slot / 2, y = T + ph - (float) ((v.optDouble(i, 0) - lo) / (hi - lo)) * ph;
                    if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
                }
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2.5f * dp); p.setStrokeJoin(Paint.Join.ROUND);
                c.drawPath(path, p);
                p.setStyle(Paint.Style.FILL);
                if (n <= 40) for (int i = 0; i < v.length(); i++)
                    c.drawCircle(L + slot * i + slot / 2, T + ph - (float) ((v.optDouble(i, 0) - lo) / (hi - lo)) * ph, 3 * dp, p);
            }
        }
    }
}
