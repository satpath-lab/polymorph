package dev.volt.player;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    String server = "", app = "", session, token = "", channel = "dev";
    boolean locked, started, hasContent;
    volatile boolean watching;
    volatile long ver = -1;
    final Handler main = new Handler(Looper.getMainLooper());
    final ExecutorService net = Executors.newSingleThreadExecutor();
    final ExecutorService imgPool = Executors.newFixedThreadPool(3);
    final LruCache<String, Bitmap> images = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    final Map<String, String> inputs = new HashMap<>();
    final Map<String, EditText> inputViews = new HashMap<>();
    final Map<String, String> lastServerVal = new HashMap<>();
    final Set<String> usedInputs = new HashSet<>();
    FrameLayout host;
    SharedPreferences prefs;
    String backId;
    float dp;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("volt", MODE_PRIVATE);
        host = new FrameLayout(this);
        host.setBackgroundColor(0xFFF4F5F7);
        setContentView(host);
        session = prefs.getString("sid", null);
        if (session == null) { session = UUID.randomUUID().toString(); prefs.edit().putString("sid", session).apply(); }
        try {
            JSONObject cfg = new JSONObject(readAll(getAssets().open("volt.json")));
            server = cfg.optString("server"); app = cfg.optString("app"); token = cfg.optString("token"); channel = cfg.optString("channel", "dev");
        } catch (Exception ignored) {}
        locked = !app.isEmpty();
        if (!locked) { channel = "dev"; server = prefs.getString("server", server); app = prefs.getString("app", ""); }
        if (app.isEmpty()) showConnect(); else start();
    }

    @Override protected void onStart() { super.onStart(); if (started && !watching) { send(null); startWatch(); } }
    @Override protected void onStop() { super.onStop(); watching = false; }
    @Override protected void onDestroy() { super.onDestroy(); watching = false; net.shutdownNow(); imgPool.shutdownNow(); }

    void start() {
        started = true; hasContent = false; ver = -1;
        setTitle(app);
        host.removeAllViews();
        ProgressBar pb = new ProgressBar(this);
        host.addView(pb, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        send(null);
        startWatch();
    }

    void startWatch() {
        if (watching) return;
        watching = true;
        final String watchApp = app;
        new Thread(() -> {
            while (watching && watchApp.equals(app)) {
                try {
                    if (ver < 0) { Thread.sleep(150); continue; }
                    JSONObject r = http("GET", "/api/watch/" + watchApp + "?ch=" + channel + "&v=" + ver, null, 35000);
                    long v = r.optLong("v", ver);
                    if (v != ver && watching) { ver = v; send(null); }
                } catch (Exception e) {
                    try { Thread.sleep(1500); } catch (InterruptedException ie) { return; }
                }
            }
        }, "volt-watch").start();
    }

    JSONObject event(String type, String id, Object value) {
        JSONObject e = new JSONObject();
        try {
            e.put("type", type); e.put("id", id == null ? "" : id);
            if (value != null) e.put("value", value);
            JSONObject in = new JSONObject();
            for (Map.Entry<String, String> en : inputs.entrySet()) { in.put(en.getKey(), en.getValue()); lastServerVal.put(en.getKey(), en.getValue()); }
            e.put("inputs", in);
        } catch (Exception ignored) {}
        return e;
    }

    void send(final JSONObject ev) {
        final String a = app;
        net.execute(() -> {
            try {
                JSONObject body = new JSONObject();
                body.put("session", session);
                body.put("event", ev == null ? JSONObject.NULL : ev);
                final JSONObject r = http("POST", "/api/run/" + a + "?ch=" + channel, body.toString(), 20000);
                main.post(() -> { if (a.equals(app)) apply(r); });
            } catch (final Exception e) {
                main.post(() -> showError(e));
            }
        });
    }

    JSONObject http(String method, String path, String body, int timeout) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(server + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(6000);
        c.setReadTimeout(timeout);
        if (!token.isEmpty()) c.setRequestProperty("X-Volt-Token", token);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.getBytes("UTF-8");
            c.setFixedLengthStreamingMode(bytes.length);
            OutputStream os = c.getOutputStream(); os.write(bytes); os.close();
        }
        int code = c.getResponseCode();
        String s = readAll(code < 400 ? c.getInputStream() : c.getErrorStream());
        if (code >= 400) throw new Exception("HTTP " + code + " " + s);
        return s.trim().startsWith("[") ? new JSONObject().put("list", new JSONArray(s)) : new JSONObject(s);
    }

    static String readAll(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[16384]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toString("UTF-8");
    }

    void apply(JSONObject r) {
        ver = r.optLong("v", ver);
        JSONObject ui = r.optJSONObject("ui");
        if (ui != null) render(ui);
        JSONObject fx = r.optJSONObject("fx");
        if (fx == null) return;
        if (fx.has("toast")) Toast.makeText(this, fx.optString("toast"), Toast.LENGTH_SHORT).show();
        if (fx.has("open")) try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fx.optString("open")))); } catch (Exception ignored) {}
        if (fx.has("vibrate")) {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            long ms = fx.optLong("vibrate", 30);
            if (vb != null) { if (Build.VERSION.SDK_INT >= 26) vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); else vb.vibrate(ms); }
        }
    }

    void render(JSONObject screen) {
        setTitle(screen.optString("title", app));
        backId = screen.has("back") ? screen.optString("back") : null;
        int accent = color(screen.optString("accent", ""), 0);
        ActionBar bar = getActionBar();
        if (bar != null) {
            if (screen.optBoolean("hideBar", false)) bar.hide(); else bar.show();
            if (accent != 0) bar.setBackgroundDrawable(new ColorDrawable(accent));
        }
        if (accent != 0) getWindow().setStatusBarColor(darker(accent));
        host.setBackgroundColor(color(screen.optString("bg", ""), 0xFFF4F5F7));
        int scrollY = 0;
        View old = host.getChildCount() > 0 ? host.getChildAt(0) : null;
        if (old instanceof ScrollView) scrollY = old.getScrollY();
        usedInputs.clear();
        JSONObject body = screen.optJSONObject("body");
        View content = body == null ? new View(this) : build(body, LinearLayout.VERTICAL, "start");
        Iterator<String> it = inputViews.keySet().iterator();
        while (it.hasNext()) { String k = it.next(); if (!usedInputs.contains(k)) { it.remove(); inputs.remove(k); lastServerVal.remove(k); } }
        host.removeAllViews();
        if (screen.optBoolean("scroll", true)) {
            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            sv.addView(content, new ScrollView.LayoutParams(-1, -2));
            host.addView(sv, new FrameLayout.LayoutParams(-1, -1));
            final int y = scrollY;
            if (y > 0) sv.post(() -> sv.scrollTo(0, y));
        } else host.addView(content, new FrameLayout.LayoutParams(-1, -1));
        hasContent = true;
    }

    View build(JSONObject n, int parentOrient, String parentAlign) {
        String type = n.optString("type", "column");
        final String id = n.optString("id", "");
        View v;
        switch (type) {
            case "column": case "row": case "card": case "hscroll": {
                LinearLayout ll = new LinearLayout(this);
                boolean row = type.equals("row") || type.equals("hscroll");
                ll.setOrientation(row ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                String align = n.optString("align", "start");
                int g = align.equals("center") ? Gravity.CENTER : align.equals("end") ? Gravity.END : Gravity.START;
                ll.setGravity(row ? (g == Gravity.CENTER ? Gravity.CENTER : (g | Gravity.CENTER_VERTICAL)) : (g == Gravity.CENTER ? Gravity.CENTER_HORIZONTAL : g));
                int gap = px(n.optDouble("gap", type.equals("card") ? 6 : 0));
                JSONArray ch = n.optJSONArray("children");
                int idx = 0;
                if (ch != null) for (int i = 0; i < ch.length(); i++) {
                    JSONObject c = ch.optJSONObject(i);
                    if (c == null) continue;
                    View cv = build(c, ll.getOrientation(), align);
                    LinearLayout.LayoutParams lp = params(c, ll.getOrientation(), align);
                    if (idx++ > 0) { if (row) lp.leftMargin += gap; else lp.topMargin += gap; }
                    ll.addView(cv, lp);
                }
                if (type.equals("card")) {
                    if (!n.has("bg")) bg(ll, 0xFFFFFFFF, n.optDouble("radius", 16));
                    if (!n.has("padding")) ll.setPadding(px(16), px(16), px(16), px(16));
                    ll.setElevation(px(n.optDouble("elevation", 2)));
                }
                if (type.equals("hscroll")) {
                    HorizontalScrollView hs = new HorizontalScrollView(this);
                    hs.setHorizontalScrollBarEnabled(false);
                    hs.addView(ll);
                    common(ll, n, id); return hs;
                }
                v = ll; break;
            }
            case "text": {
                TextView t = new TextView(this);
                t.setText(n.optString("text", ""));
                t.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 16));
                t.setTextColor(color(n.optString("color", ""), 0xFF1F2328));
                int style = (n.optBoolean("bold") ? Typeface.BOLD : 0) | (n.optBoolean("italic") ? Typeface.ITALIC : 0);
                t.setTypeface(n.optBoolean("mono") ? Typeface.MONOSPACE : Typeface.DEFAULT, style);
                String al = n.optString("align", "");
                if (al.equals("center")) t.setGravity(Gravity.CENTER_HORIZONTAL); else if (al.equals("end")) t.setGravity(Gravity.END);
                if (n.has("lines")) { t.setMaxLines(n.optInt("lines")); t.setEllipsize(android.text.TextUtils.TruncateAt.END); }
                if (n.optBoolean("selectable")) t.setTextIsSelectable(true);
                v = t; break;
            }
            case "button": {
                Button bt = new Button(this);
                bt.setText(n.optString("text", ""));
                bt.setAllCaps(false);
                bt.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 16));
                bt.setTextColor(color(n.optString("color", ""), 0xFFFFFFFF));
                bt.setTypeface(Typeface.DEFAULT_BOLD);
                bt.setStateListAnimator(null);
                bt.setMinHeight(px(48)); bt.setMinimumHeight(px(48)); bt.setMinWidth(0); bt.setMinimumWidth(0);
                bt.setPadding(px(14), px(10), px(14), px(10));
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(color(n.optString("bg", ""), 0xFF6C3BFF));
                gd.setCornerRadius(px(n.optDouble("radius", 12)));
                bt.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), gd, null));
                bt.setEnabled(n.optBoolean("enabled", true));
                bt.setOnClickListener(x -> send(event("tap", id, null)));
                common(bt, n, null);
                return bt;
            }
            case "input": {
                EditText et = id.isEmpty() ? null : inputViews.get(id);
                boolean fresh = et == null;
                if (fresh) {
                    et = new EditText(this);
                    final EditText fe = et;
                    if (!id.isEmpty()) {
                        inputViews.put(id, et);
                        et.addTextChangedListener(new TextWatcher() {
                            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                            public void onTextChanged(CharSequence s, int a, int b, int c) {}
                            public void afterTextChanged(Editable s) { inputs.put(id, s.toString()); }
                        });
                    }
                    et.setOnEditorActionListener((tv, action, ke) -> { send(event("submit", id, fe.getText().toString())); return true; });
                } else if (et.getParent() != null) ((ViewGroup) et.getParent()).removeView(et);
                usedInputs.add(id);
                et.setHint(n.optString("hint", ""));
                et.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 16));
                et.setTextColor(color(n.optString("color", ""), 0xFF1F2328));
                String kind = n.optString("kind", "text");
                int it = InputType.TYPE_CLASS_TEXT;
                if (kind.equals("number")) it = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED;
                else if (kind.equals("email")) it |= InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS;
                else if (kind.equals("password") || n.optBoolean("password")) it |= InputType.TYPE_TEXT_VARIATION_PASSWORD;
                else if (kind.equals("phone")) it = InputType.TYPE_CLASS_PHONE;
                if (n.optBoolean("multiline")) { it |= InputType.TYPE_TEXT_FLAG_MULTI_LINE; et.setMinLines(3); et.setGravity(Gravity.TOP); }
                else { et.setSingleLine(true); et.setImeOptions(EditorInfo.IME_ACTION_DONE); }
                if (et.getInputType() != it) et.setInputType(it);
                if (n.has("value")) {
                    String sv = n.optString("value", "");
                    String last = lastServerVal.get(id);
                    if (fresh || last == null || !sv.equals(last)) {
                        if (!sv.equals(et.getText().toString())) { et.setText(sv); et.setSelection(sv.length()); }
                        lastServerVal.put(id, sv);
                    }
                }
                if (fresh && !id.isEmpty()) inputs.put(id, et.getText().toString());
                GradientDrawable gd = new GradientDrawable();
                gd.setColor(color(n.optString("bg", ""), 0xFFFFFFFF));
                gd.setCornerRadius(px(n.optDouble("radius", 12)));
                gd.setStroke(px(1), color(n.optString("border", ""), 0xFFD9DCE3));
                et.setBackground(gd);
                et.setPadding(px(14), px(12), px(14), px(12));
                common(et, n, null);
                return et;
            }
            case "switch": {
                Switch sw = new Switch(this);
                sw.setText(n.optString("text", ""));
                sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 16));
                sw.setTextColor(color(n.optString("color", ""), 0xFF1F2328));
                sw.setChecked(n.optBoolean("value"));
                sw.setOnCheckedChangeListener((b, on) -> send(event("change", id, on)));
                sw.setPadding(0, px(6), 0, px(6));
                common(sw, n, null);
                return sw;
            }
            case "image": {
                final ImageView iv = new ImageView(this);
                iv.setScaleType("contain".equals(n.optString("fit")) ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
                iv.setClipToOutline(true);
                bg(iv, color(n.optString("bg", ""), 0xFFE5E7EB), n.optDouble("radius", 12));
                String src = n.optString("src", "");
                final String url = src.startsWith("http") ? src : server + (src.startsWith("/") ? "" : "/apps/" + app + "/assets/") + src + (src.startsWith("/") ? "" : "?ch=" + channel);
                Bitmap cached = images.get(url);
                if (cached != null) iv.setImageBitmap(cached);
                else if (!src.isEmpty()) imgPool.execute(() -> {
                    try {
                        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
                        c.setConnectTimeout(6000); c.setReadTimeout(15000);
                        final Bitmap bm = BitmapFactory.decodeStream(c.getInputStream());
                        if (bm != null) { images.put(url, bm); main.post(() -> iv.setImageBitmap(bm)); }
                    } catch (Exception ignored) {}
                });
                v = iv; break;
            }
            case "spacer": v = new View(this); break;
            case "divider": { v = new View(this); v.setBackgroundColor(color(n.optString("color", ""), 0x1F000000)); break; }
            case "progress": {
                ProgressBar pb;
                if (n.has("value")) {
                    pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
                    pb.setMax(1000); pb.setProgress((int) Math.round(Math.max(0, Math.min(1, n.optDouble("value", 0))) * 1000));
                } else pb = new ProgressBar(this);
                if (n.has("color")) pb.setProgressTintList(ColorStateList.valueOf(color(n.optString("color"), 0xFF6C3BFF)));
                v = pb; break;
            }
            default: {
                TextView t = new TextView(this);
                t.setText("[unknown type: " + type + "]"); t.setTextColor(0xFFB91C1C);
                v = t;
            }
        }
        common(v, n, id);
        return v;
    }

    void common(View v, JSONObject n, String tapId) {
        if (n.has("padding")) {
            JSONArray pa = n.optJSONArray("padding");
            if (pa != null && pa.length() == 2) v.setPadding(px(pa.optDouble(1)), px(pa.optDouble(0)), px(pa.optDouble(1)), px(pa.optDouble(0)));
            else if (pa != null && pa.length() == 4) v.setPadding(px(pa.optDouble(3)), px(pa.optDouble(0)), px(pa.optDouble(1)), px(pa.optDouble(2)));
            else { int p = px(n.optDouble("padding", 0)); v.setPadding(p, p, p, p); }
        }
        String t = n.optString("type");
        if (!t.equals("button") && !t.equals("input") && !t.equals("image") && (n.has("bg") || n.has("radius")))
            bg(v, color(n.optString("bg", ""), 0), n.optDouble("radius", t.equals("card") ? 16 : 0));
        if (n.has("opacity")) v.setAlpha((float) n.optDouble("opacity", 1));
        if (n.has("visible") && !n.optBoolean("visible", true)) v.setVisibility(View.GONE);
        if (tapId != null && !tapId.isEmpty()) {
            final String id = tapId;
            v.setClickable(true);
            v.setOnClickListener(x -> send(event("tap", id, null)));
            if (v.getBackground() != null) v.setForeground(new RippleDrawable(ColorStateList.valueOf(0x22000000), null, v.getBackground()));
        }
    }

    LinearLayout.LayoutParams params(JSONObject n, int orient, String parentAlign) {
        String t = n.optString("type");
        boolean col = orient == LinearLayout.VERTICAL;
        int def = col && parentAlign.equals("start") ? -1 : -2;
        int w = col ? def : -2, h = -2;
        if (t.equals("divider")) { if (col) { w = -1; h = Math.max(1, px(1)); } else { w = Math.max(1, px(1)); h = -1; } }
        if (t.equals("image")) { h = px(180); if (!col) w = px(120); }
        if (t.equals("spacer")) { int s = px(n.optDouble("size", 0)); if (col) h = s; else w = s; }
        w = dim(n.opt("width"), w); h = dim(n.opt("height"), h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        if (n.has("flex")) { lp.weight = (float) n.optDouble("flex", 1); if (col) lp.height = 0; else lp.width = 0; }
        if (n.has("margin")) { int m = px(n.optDouble("margin", 0)); lp.setMargins(m, m, m, m); }
        if (!col) lp.gravity = Gravity.CENTER_VERTICAL;
        return lp;
    }

    int dim(Object o, int def) {
        if (o == null) return def;
        if ("fill".equals(o)) return -1;
        if ("wrap".equals(o)) return -2;
        if (o instanceof Number) return px(((Number) o).doubleValue());
        return def;
    }

    void bg(View v, int c, double radius) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(c); gd.setCornerRadius(px(radius));
        v.setBackground(gd);
    }

    int px(double d) { return (int) Math.round(d * dp); }

    static int color(String s, int def) {
        if (s == null || s.isEmpty()) return def;
        try {
            if (s.startsWith("#") && s.length() == 4) s = "#" + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2) + s.charAt(3) + s.charAt(3);
            return Color.parseColor(s);
        } catch (Exception e) { return def; }
    }

    static int darker(int c) {
        float[] hsv = new float[3]; Color.colorToHSV(c, hsv); hsv[2] *= 0.8f; return Color.HSVToColor(hsv);
    }

    void showError(Exception e) {
        if (hasContent) { Toast.makeText(this, "Offline: " + e.getMessage(), Toast.LENGTH_SHORT).show(); return; }
        LinearLayout ll = form("Can't reach server");
        TextView t = new TextView(this); t.setText(server + "\n\n" + e.getMessage()); t.setTextColor(0xFF6B7280);
        ll.addView(t);
        Button retry = primary("Retry"); retry.setOnClickListener(x -> start()); ll.addView(retry, wide());
        if (!locked) { Button sw = primary("Change server / app"); sw.setOnClickListener(x -> showConnect()); ll.addView(sw, wide()); }
        setForm(ll);
    }

    void showConnect() {
        watching = false; started = false; hasContent = false;
        setTitle("VoltForge");
        LinearLayout ll = form("⚡ VoltForge");
        TextView sub = new TextView(this); sub.setText("Connect to your VoltForge server"); sub.setTextColor(0xFF6B7280); ll.addView(sub);
        final EditText url = new EditText(this);
        url.setSingleLine(true); url.setHint("http://192.168.1.10:8787");
        url.setText(server.isEmpty() ? "http://10.0.2.2:8787" : server);
        url.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        ll.addView(url, wide());
        final LinearLayout list = new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        Button load = primary("Load apps");
        load.setOnClickListener(x -> {
            server = url.getText().toString().trim().replaceAll("/+$", "");
            list.removeAllViews();
            net.execute(() -> {
                try {
                    final JSONArray arr = http("GET", "/api/apps", null, 8000).getJSONArray("list");
                    main.post(() -> {
                        for (int i = 0; i < arr.length(); i++) {
                            final JSONObject a = arr.optJSONObject(i);
                            Button b = primary(a.optString("name") + "   ·   " + a.optString("lang"));
                            b.setOnClickListener(y -> {
                                app = a.optString("name");
                                prefs.edit().putString("server", server).putString("app", app).apply();
                                inputViews.clear(); inputs.clear(); lastServerVal.clear();
                                start();
                            });
                            list.addView(b, wide());
                        }
                        if (arr.length() == 0) { TextView t = new TextView(this); t.setText("No apps on this server yet."); list.addView(t); }
                    });
                } catch (final Exception e) {
                    main.post(() -> { TextView t = new TextView(this); t.setText("Error: " + e.getMessage()); t.setTextColor(0xFFB91C1C); list.addView(t); });
                }
            });
        });
        ll.addView(load, wide());
        ll.addView(list);
        setForm(ll);
        if (!server.isEmpty()) load.performClick();
    }

    LinearLayout form(String title) {
        LinearLayout ll = new LinearLayout(this); ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(px(24), px(32), px(24), px(24));
        TextView t = new TextView(this); t.setText(title); t.setTextSize(28); t.setTypeface(Typeface.DEFAULT_BOLD); t.setTextColor(0xFF1F2328);
        ll.addView(t);
        return ll;
    }

    Button primary(String text) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextColor(0xFFFFFFFF); b.setStateListAnimator(null);
        GradientDrawable gd = new GradientDrawable(); gd.setColor(0xFF6C3BFF); gd.setCornerRadius(px(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), gd, null));
        return b;
    }

    LinearLayout.LayoutParams wide() { LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.topMargin = px(12); return lp; }

    void setForm(View v) {
        host.removeAllViews(); host.setBackgroundColor(0xFFF4F5F7);
        ScrollView sv = new ScrollView(this); sv.addView(v); host.addView(sv, new FrameLayout.LayoutParams(-1, -1));
    }

    @Override public void onBackPressed() {
        if (backId != null && started) send(event("tap", backId, null)); else super.onBackPressed();
    }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Reload"); m.add(0, 2, 0, "Reset state");
        if (!locked) m.add(0, 3, 0, "Switch app");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == 1 && started) send(null);
        else if (i.getItemId() == 2 && started) net.execute(() -> {
            try { http("POST", "/api/reset/" + app + "?ch=" + channel, new JSONObject().put("session", session).toString(), 8000); } catch (Exception ignored) {}
            main.post(() -> { inputViews.clear(); inputs.clear(); lastServerVal.clear(); send(null); });
        });
        else if (i.getItemId() == 3) showConnect();
        return true;
    }
}
