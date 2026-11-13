package dev.volt.player;

import android.Manifest;
import android.app.ActionBar;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Matrix;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.ExifInterface;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.media.MediaPlayer;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.MediaController;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.AdapterView;
import android.widget.VideoView;
import java.io.IOException;
import java.util.Calendar;
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
import dev.volt.engine.VoltEngine;
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
    static volatile MainActivity alive;
    String server = "", app = "", session, token = "", channel = "dev", source = "bundled";
    VoltEngine engine;
    dev.volt.engine.ScriptSource sdkSource;
    Object state;
    File appDir;
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
    static final int RC_CAM = 101, RC_PICK = 102, RC_CONTACT = 103, RP_LOC = 201, RP_NOTIF = 202;
    String pendCam, pendPick, pendContact, pendLoc, lastRoute;
    File camFile;
    boolean goingBack;
    final List<JSONObject> notifQueue = new ArrayList<>();
    final Map<String, VoltPlugin> plugins = new HashMap<>();
    Map<String, String> pluginClasses;
    final Map<Integer, VoltPlugin.PermissionCallback> permCbs = new HashMap<>();
    final Map<Integer, VoltPlugin.ActivityCallback> actCbs = new HashMap<>();
    int nextReq = 1000;
    boolean rootScroll = true;
    final Map<String, ListView> lists = new HashMap<>();
    final Set<String> usedLists = new HashSet<>();
    final Map<String, View> keep = new HashMap<>();
    final Map<String, String> keepSig = new HashMap<>();
    final Set<String> usedKeep = new HashSet<>();
    final Set<String> animated = new HashSet<>();
    final Map<String, Typeface> fonts = new HashMap<>();
    final Set<String> fontLoading = new HashSet<>();
    JSONObject lastScreen;
    AlertDialog dialog;
    String dialogKey;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        prefs = getSharedPreferences("volt", MODE_PRIVATE);
        VoltSecure.init(this);
        host = new FrameLayout(this);
        host.setBackgroundColor(0xFFF4F5F7);
        setContentView(host);
        session = prefs.getString("sid", null);
        if (session == null) { session = UUID.randomUUID().toString(); prefs.edit().putString("sid", session).apply(); }
        JSONObject cfg = VoltApp.cfg(this);
        server = cfg.optString("dev"); app = cfg.optString("app"); token = cfg.optString("token"); source = cfg.optString("source", "bundled");
        sdkSource = dev.volt.engine.ScriptSource.fromIntent(getIntent());
        if (sdkSource != null) {
            String sid = getIntent().getStringExtra("volt.id");
            app = sid != null ? sid : "screen-" + Integer.toHexString((getIntent().getStringExtra("volt.kind") + getIntent().getStringExtra("volt.value")).hashCode());
            source = "sdk";
        }
        locked = !app.isEmpty();
        if (!locked) { source = "dev"; server = prefs.getString("server", server); app = prefs.getString("app", ""); }
        VoltEngine.setHandler(hostHandler);
        alive = this;
        if (app.isEmpty()) showConnect(); else start();
    }

    @Override protected void onStart() { super.onStart(); alive = this; if (started && !watching && "dev".equals(source)) startWatch(); }
    @Override protected void onStop() { super.onStop(); watching = false; }
    @Override protected void onDestroy() {
        super.onDestroy(); watching = false; if (alive == this) alive = null;
        net.execute(() -> { if (engine != null) engine.close(); engine = null; });
        net.shutdown(); imgPool.shutdownNow();
    }

    void start() {
        started = true; hasContent = false; ver = -1;
        prefs.edit().putString("bg:app", app).putString("bg:source", source).apply();
        setTitle(app);
        host.removeAllViews();
        String cached = !VoltSecure.on("cacheScreens", true) ? null : VoltSecure.dec(prefs.getString("ui:" + app, null), null);
        boolean shown = false;
        if (cached != null) try { render(new JSONObject(cached)); shown = true; } catch (Exception ignored) {}
        if (!shown) host.addView(new ProgressBar(this), new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
        state = VoltApp.loadState(this, app);
        net.execute(() -> {
            try {
                if ("dev".equals(source)) syncDev();
                else if (sdkSource != null && sdkSource.isRemote()) {
                    try { sdkSource.fetch(this); } catch (Exception e) { android.util.Log.w("Volt", "script update failed: " + e); }
                }
                else if ("remote".equals(source) && remoteDue()) {
                    try { VoltApp.updateRemote(this); } catch (Exception e) { android.util.Log.w("Volt", "script update failed: " + e); }
                }
                openEngine();
                main.post(() -> send(null));
            } catch (final Exception e) { main.post(() -> showError(e)); }
        });
        if ("dev".equals(source)) startWatch();
        handleTap(getIntent());
    }

    boolean remoteDue() {
        String check = VoltApp.cfg(this).optString("check", "launch");
        if (VoltApp.prefs(this).getString("remote:dir", null) == null || check.equals("launch")) return true;
        try { return System.currentTimeMillis() - VoltApp.prefs(this).getLong("remote:at", 0) > Long.parseLong(check) * 60000L; } catch (Exception e) { return true; }
    }

    void openEngine() throws Exception {
        if (engine != null) { engine.close(); engine = null; }
        dev.volt.engine.ScriptSource src = sdkSource;
        if (src == null) {
            File d = VoltApp.scripts(this, source);
            String m = VoltApp.mainFile(d);
            if (m == null) throw new Exception("No main.lua / main.js / main.py / main.rb / main.php found");
            src = dev.volt.engine.ScriptSource.dir(d).main(m);
        }
        engine = dev.volt.engine.Volt.engine(this).source(src).handler(hostHandler).open();
        appDir = engine.dir();
    }

    void syncDev() throws Exception {
        byte[] z = VoltApp.get(server + "/api/bundle/" + app + "?ch=dev", token, null);
        VoltApp.unzip(z, new File(VoltApp.root(this), "dev"));
    }

    void reload(final boolean sync) {
        net.execute(() -> {
            try { if (sync) syncDev(); openEngine(); main.post(() -> send(null)); }
            catch (final Exception e) { main.post(() -> showError(e)); }
        });
    }

    void startWatch() {
        if (watching) return;
        watching = true;
        final String watchApp = app;
        new Thread(() -> {
            while (watching && watchApp.equals(app)) {
                try {
                    JSONObject r = http("GET", "/api/watch/" + watchApp + "?ch=dev&v=" + ver, null, 35000);
                    long v = r.optLong("v", ver);
                    if (v != ver && watching) { boolean first = ver < 0; ver = v; if (!first) reload(true); }
                } catch (Exception e) {
                    try { Thread.sleep(2000); } catch (InterruptedException ie) { return; }
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
            e.put("store", store());
            e.put("device", device());
        } catch (Exception ignored) {}
        return e;
    }

    void send(final JSONObject ev) {
        final String a = app;
        net.execute(() -> {
            try {
                if (engine == null) return;
                long t0 = System.nanoTime();
                JSONObject r = engine.step(state, ev);
                Object st = r.opt("state");
                final JSONObject fx = VoltApp.split(st);
                state = st;
                VoltApp.saveState(this, a, st);
                final JSONObject res = new JSONObject().put("ui", r.opt("ui")).put("fx", fx);
                if ("dev".equals(source)) android.util.Log.d("Volt", "step " + (System.nanoTime() - t0) / 1000 + "us");
                main.post(() -> { if (a.equals(app)) apply(res); });
            } catch (final VoltEngine.ScriptError e) {
                main.post(() -> showScriptError(e.getMessage()));
            } catch (final Exception e) {
                main.post(() -> showError(e));
            }
        });
    }

    void showScriptError(String msg) {
        try {
            JSONObject screen = new JSONObject().put("title", "Script error").put("accent", "#B91C1C").put("bg", "#FEF2F2")
                .put("body", new JSONObject().put("type", "column").put("padding", 20).put("gap", 12).put("children", new JSONArray()
                    .put(new JSONObject().put("type", "text").put("text", "⚠ Your script failed").put("size", 20).put("bold", true).put("color", "#B91C1C"))
                    .put(new JSONObject().put("type", "text").put("text", msg).put("mono", true).put("size", 12).put("color", "#7F1D1D").put("selectable", true))
                    .put(new JSONObject().put("type", "text").put("text", "dev".equals(source) ? "Fix the code and save. The app reloads by itself." : "Use the menu to reload or reset.").put("color", "#6B7280"))));
            render(screen);
        } catch (Exception ignored) {}
    }

    JSONObject http(String method, String path, String body, int timeout) throws Exception {
        HttpURLConnection c = VoltSecure.open(server + path);
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
        JSONObject ui = r.optJSONObject("ui");
        if (ui != null && !VoltSecure.on("cacheScreens", true)) render(ui);
        else if (ui != null) { render(ui); prefs.edit().putString("ui:" + app, VoltSecure.enc(ui.toString())).apply(); }
        JSONObject fx = r.optJSONObject("fx");
        if (fx == null) return;
        if (fx.has("toast")) Toast.makeText(this, fx.optString("toast"), Toast.LENGTH_SHORT).show();
        if (fx.has("open")) try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(fx.optString("open")))); } catch (Exception ignored) {}
        if (fx.has("vibrate")) {
            Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            long ms = fx.optLong("vibrate", 30);
            if (vb != null) { if (Build.VERSION.SDK_INT >= 26) vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); else vb.vibrate(ms); }
        }
        nativeFx(fx);
    }

    void render(JSONObject screen) {
        setTitle(screen.optString("title", app));
        if (screen.optBoolean("secure") || VoltSecure.on("blockScreenshots", false)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);
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
        if (old != null && "shell".equals(old.getTag())) old = ((ViewGroup) old).getChildAt(0);
        if (old instanceof ScrollView) scrollY = old.getScrollY();
        if (lastRoute != null && !screen.optString("route", "").equals(lastRoute)) scrollY = 0;
        usedInputs.clear(); usedLists.clear(); usedKeep.clear();
        lastScreen = screen;
        rootScroll = screen.optBoolean("scroll", true);
        JSONObject body = screen.optJSONObject("body");
        View content;
        try { content = body == null ? new View(this) : build(body, LinearLayout.VERTICAL, "start"); }
        catch (Throwable t) {
            TextView tv = new TextView(this);
            tv.setText("⚠ Could not render this screen\n\n" + t);
            tv.setTextColor(0xFFB91C1C); tv.setPadding(px(20), px(20), px(20), px(20));
            content = tv;
        }
        Iterator<String> it = inputViews.keySet().iterator();
        while (it.hasNext()) { String k = it.next(); if (!usedInputs.contains(k)) { it.remove(); inputs.remove(k); lastServerVal.remove(k); } }
        lists.keySet().retainAll(usedLists);
        for (String k : new ArrayList<>(keep.keySet())) if (!usedKeep.contains(k)) {
            View gone = keep.remove(k); keepSig.remove(k);
            if (gone instanceof WebView) ((WebView) gone).destroy();
            if (gone instanceof VideoView) ((VideoView) gone).stopPlayback();
        }
        host.removeAllViews();
        if (screen.optBoolean("scroll", true)) {
            ScrollView sv = new ScrollView(this);
            sv.setFillViewport(true);
            sv.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
            sv.setFocusableInTouchMode(true);
            sv.addView(content, new ScrollView.LayoutParams(-1, -2));
            host.addView(sv, new FrameLayout.LayoutParams(-1, -1));
            final int y = scrollY;
            sv.post(() -> { sv.requestFocus(); sv.scrollTo(0, y); });
        } else host.addView(content, new FrameLayout.LayoutParams(-1, -1));
        JSONObject tabs = screen.optJSONObject("tabs");
        if (tabs != null) {
            View main0 = host.getChildAt(0);
            host.removeView(main0);
            LinearLayout shell = new LinearLayout(this); shell.setOrientation(LinearLayout.VERTICAL); shell.setTag("shell");
            shell.addView(main0, new LinearLayout.LayoutParams(-1, 0, 1));
            shell.addView(tabBar(tabs), new LinearLayout.LayoutParams(-1, -2));
            host.addView(shell, new FrameLayout.LayoutParams(-1, -1));
        }
        showDialog(screen.optJSONObject("dialog"));
        String route = screen.optString("route", "");
        if (lastRoute != null && !route.equals(lastRoute)) animated.clear();
        if (lastRoute != null && !route.equals(lastRoute)) {
            View top = host.getChildAt(0);
            float w = getResources().getDisplayMetrics().widthPixels * 0.25f;
            top.setTranslationX(goingBack ? -w : w); top.setAlpha(0f);
            top.animate().translationX(0).alpha(1f).setDuration(200).start();
        }
        lastRoute = route; goingBack = false;
        hasContent = true;
    }

    static void merge(JSONObject into, JSONObject from) {
        if (from == null) return;
        Iterator<String> it = from.keys();
        while (it.hasNext()) { String k = it.next(); try { into.put(k, from.get(k)); } catch (Exception ignored) {} }
    }

    JSONObject styled(JSONObject n) {
        if (lastScreen == null) return n;
        JSONObject styles = lastScreen.optJSONObject("styles"), theme = lastScreen.optJSONObject("theme");
        if (theme == null && (styles == null || !n.has("style"))) return n;
        JSONObject out = new JSONObject();
        String type = n.optString("type", "column");
        if (theme != null) { merge(out, theme.optJSONObject("all")); merge(out, theme.optJSONObject(type)); }
        if (styles != null && n.has("style")) {
            Object st = n.opt("style");
            JSONArray names = st instanceof JSONArray ? (JSONArray) st : new JSONArray().put(String.valueOf(st));
            for (int i = 0; i < names.length(); i++) merge(out, styles.optJSONObject(names.optString(i)));
        }
        merge(out, n);
        out.remove("style");
        return out;
    }

    GradientDrawable shape(JSONObject n, int defColor, double defRadius) {
        GradientDrawable gd = new GradientDrawable();
        JSONArray grad = n.optJSONArray("gradient");
        if (grad != null && grad.length() >= 2) {
            int[] cs = new int[grad.length()];
            for (int i = 0; i < cs.length; i++) cs[i] = color(grad.optString(i), 0);
            gd.setColors(cs);
            int a = ((n.optInt("angle", 90) % 360) + 360) % 360;
            gd.setOrientation(a < 45 || a >= 315 ? GradientDrawable.Orientation.LEFT_RIGHT : a < 135 ? GradientDrawable.Orientation.TOP_BOTTOM
                    : a < 225 ? GradientDrawable.Orientation.RIGHT_LEFT : GradientDrawable.Orientation.BOTTOM_TOP);
        } else gd.setColor(color(n.optString("bg", ""), defColor));
        JSONArray r = n.optJSONArray("radius");
        if (r != null && r.length() == 4) {
            float a = px(r.optDouble(0)), b = px(r.optDouble(1)), c = px(r.optDouble(2)), d = px(r.optDouble(3));
            gd.setCornerRadii(new float[]{a, a, b, b, c, c, d, d});
        } else gd.setCornerRadius(px(n.optDouble("radius", defRadius)));
        if (n.has("border")) gd.setStroke(Math.max(1, px(n.optDouble("borderWidth", 1))), color(n.optString("border"), 0xFFD9DCE3));
        return gd;
    }

    View stack(JSONObject n) {
        FrameLayout fl = new FrameLayout(this);
        JSONArray ch = n.optJSONArray("children");
        if (ch != null) for (int i = 0; i < ch.length(); i++) {
            JSONObject c = ch.optJSONObject(i); if (c == null) continue;
            c = styled(c);
            View cv = build(c, LinearLayout.VERTICAL, "start");
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dim(c.opt("width"), c.has("position") ? -2 : -1), dim(c.opt("height"), -2));
            JSONObject pos = c.optJSONObject("position");
            int g = 0;
            if (pos != null) {
                if (pos.has("top")) { g |= Gravity.TOP; lp.topMargin = px(pos.optDouble("top")); }
                if (pos.has("bottom")) { g |= Gravity.BOTTOM; lp.bottomMargin = px(pos.optDouble("bottom")); }
                if (pos.has("left")) { g |= Gravity.START; lp.leftMargin = px(pos.optDouble("left")); }
                if (pos.has("right")) { g |= Gravity.END; lp.rightMargin = px(pos.optDouble("right")); }
                if (pos.optBoolean("center")) g = Gravity.CENTER;
            }
            lp.gravity = g == 0 ? Gravity.TOP | Gravity.START : g;
            fl.addView(cv, lp);
        }
        return fl;
    }

    View build(JSONObject n, int parentOrient, String parentAlign) {
        n = styled(n);
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
                String justify = n.optString("justify", "");
                if (justify.equals("center")) ll.setGravity(ll.getGravity() | (row ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL));
                if (justify.equals("end")) ll.setGravity(ll.getGravity() | (row ? Gravity.END : Gravity.BOTTOM));
                boolean spread = justify.equals("between") || justify.equals("around") || justify.equals("evenly");
                JSONArray ch = n.optJSONArray("children");
                int idx = 0;
                if (spread && !justify.equals("between")) ll.addView(new View(this), new LinearLayout.LayoutParams(row ? 0 : -1, row ? -1 : 0, justify.equals("around") ? 0.5f : 1));
                if (ch != null) for (int i = 0; i < ch.length(); i++) {
                    JSONObject c = ch.optJSONObject(i);
                    if (c == null) continue;
                    c = styled(c);
                    View cv = build(c, ll.getOrientation(), align);
                    LinearLayout.LayoutParams lp = params(c, ll.getOrientation(), align);
                    if (idx++ > 0) {
                        if (spread) ll.addView(new View(this), new LinearLayout.LayoutParams(row ? 0 : -1, row ? -1 : 0, 1));
                        if (row) lp.leftMargin += gap; else lp.topMargin += gap;
                    }
                    ll.addView(cv, lp);
                }
                if (spread && !justify.equals("between")) ll.addView(new View(this), new LinearLayout.LayoutParams(row ? 0 : -1, row ? -1 : 0, justify.equals("around") ? 0.5f : 1));
                if (type.equals("card")) {
                    if (!n.has("bg") && !n.has("gradient")) ll.setBackground(shape(n, 0xFFFFFFFF, 16));
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
                bt.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), shape(n, 0xFF6C3BFF, 12), null));
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
                GradientDrawable gd = shape(n, 0xFFFFFFFF, 12);
                if (!n.has("border")) gd.setStroke(px(1), 0xFFD9DCE3);
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
                final String url = src.startsWith("http") ? src : src.startsWith("file://") ? src.substring(7) : src.startsWith("/") ? src : appDir == null ? "" : new File(new File(appDir, "assets"), src).getPath();
                Bitmap cached = images.get(url);
                if (cached != null) iv.setImageBitmap(cached);
                else if (!src.isEmpty()) imgPool.execute(() -> {
                    try {
                        Bitmap bm0;
                        if (url.startsWith("http")) {
                            HttpURLConnection c = VoltSecure.open(url);
                            c.setConnectTimeout(6000); c.setReadTimeout(15000);
                            bm0 = BitmapFactory.decodeStream(c.getInputStream());
                        } else bm0 = BitmapFactory.decodeFile(url);
                        final Bitmap bm = bm0;
                        if (bm != null) { images.put(url, bm); main.post(() -> iv.setImageBitmap(bm)); }
                    } catch (Exception ignored) {}
                });
                v = iv; break;
            }
            case "list": {
                final JSONArray items = n.optJSONArray("items") == null ? new JSONArray() : n.optJSONArray("items");
                if (rootScroll || id.isEmpty()) {
                    JSONObject col = new JSONObject();
                    try { col.put("type", "column").put("gap", n.optDouble("gap", 8)).put("children", items); } catch (Exception ignored) {}
                    v = build(col, parentOrient, parentAlign); break;
                }
                ListView lv = lists.get(id);
                if (lv == null) {
                    lv = new ListView(this);
                    lv.setDivider(new ColorDrawable(0));
                    lv.setSelector(new ColorDrawable(0));
                    lv.setClipToPadding(false);
                    lv.setAdapter(new VList());
                    final VList ad0 = (VList) lv.getAdapter();
                    lv.setOnScrollListener(new AbsListView.OnScrollListener() {
                        public void onScrollStateChanged(AbsListView view, int state) {}
                        public void onScroll(AbsListView view, int first, int visible, int total) {
                            if (total > 0 && first + visible >= total && ad0.endFired != total && !ad0.onEnd.isEmpty()) { ad0.endFired = total; send(event("tap", ad0.onEnd, null)); }
                        }
                    });
                    lists.put(id, lv);
                } else if (lv.getParent() != null) ((ViewGroup) lv.getParent()).removeView(lv);
                usedLists.add(id);
                lv.setDividerHeight(px(n.optDouble("gap", 8)));
                VList ad = (VList) lv.getAdapter();
                ad.items = items; ad.onEnd = n.optString("onEnd", "");
                ad.notifyDataSetChanged();
                v = lv; break;
            }
            case "select": {
                final Spinner sp = new Spinner(this);
                final JSONArray opts = n.optJSONArray("options") == null ? new JSONArray() : n.optJSONArray("options");
                final List<String> labels = new ArrayList<>(), values = new ArrayList<>();
                for (int i = 0; i < opts.length(); i++) {
                    JSONObject o = opts.optJSONObject(i);
                    values.add(o != null ? o.optString("value") : opts.optString(i));
                    labels.add(o != null ? o.optString("label", o.optString("value")) : opts.optString(i));
                }
                sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));
                final String cur = n.optString("value", "");
                int sel = values.indexOf(cur); if (sel >= 0) sp.setSelection(sel, false);
                sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                    public void onItemSelected(AdapterView<?> a, View vv, int pos, long l) { if (!values.get(pos).equals(cur)) send(event("change", id, values.get(pos))); }
                    public void onNothingSelected(AdapterView<?> a) {}
                });
                GradientDrawable gd = new GradientDrawable(); gd.setColor(0xFFFFFFFF); gd.setCornerRadius(px(12)); gd.setStroke(px(1), 0xFFD9DCE3);
                sp.setBackground(gd); sp.setPadding(px(8), px(10), px(8), px(10));
                v = sp; break;
            }
            case "slider": {
                final double min = n.optDouble("min", 0), max = n.optDouble("max", 100), step = Math.max(1e-9, n.optDouble("step", 1));
                SeekBar sb = new SeekBar(this);
                sb.setMax((int) Math.round((max - min) / step));
                sb.setProgress((int) Math.round((n.optDouble("value", min) - min) / step));
                if (n.has("color")) { int c0 = color(n.optString("color"), 0xFF6C3BFF); sb.setProgressTintList(ColorStateList.valueOf(c0)); sb.setThumbTintList(ColorStateList.valueOf(c0)); }
                sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    public void onProgressChanged(SeekBar b, int pr, boolean user) {}
                    public void onStartTrackingTouch(SeekBar b) {}
                    public void onStopTrackingTouch(SeekBar b) {
                        double val = min + b.getProgress() * step;
                        send(event("change", id, val == Math.rint(val) ? (Object) (long) val : (Object) val));
                    }
                });
                sb.setPadding(px(12), px(12), px(12), px(12));
                v = sb; break;
            }
            case "checkbox": {
                CheckBox cb = new CheckBox(this);
                cb.setText(n.optString("text", "")); cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 16));
                cb.setTextColor(color(n.optString("color", ""), 0xFF1F2328));
                cb.setChecked(n.optBoolean("value"));
                cb.setOnCheckedChangeListener((b, on) -> send(event("change", id, on)));
                v = cb; break;
            }
            case "radio": {
                RadioGroup rg = new RadioGroup(this);
                rg.setOrientation(n.optBoolean("horizontal") ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
                JSONArray opts = n.optJSONArray("options") == null ? new JSONArray() : n.optJSONArray("options");
                final List<String> values = new ArrayList<>();
                String cur = n.optString("value", "");
                for (int i = 0; i < opts.length(); i++) {
                    JSONObject o = opts.optJSONObject(i);
                    String val = o != null ? o.optString("value") : opts.optString(i);
                    RadioButton rb = new RadioButton(this);
                    rb.setId(1000 + i); rb.setText(o != null ? o.optString("label", val) : val);
                    rb.setTextColor(color(n.optString("color", ""), 0xFF1F2328)); rb.setTextSize(TypedValue.COMPLEX_UNIT_SP, (float) n.optDouble("size", 16));
                    rg.addView(rb); values.add(val);
                    if (val.equals(cur)) rb.setChecked(true);
                }
                rg.setOnCheckedChangeListener((g, cid) -> { int i = cid - 1000; if (i >= 0 && i < values.size()) send(event("change", id, values.get(i))); });
                v = rg; break;
            }
            case "grid": {
                int cols = Math.max(1, n.optInt("columns", 2));
                double gap = n.optDouble("gap", 8);
                JSONArray ch = n.optJSONArray("children") == null ? new JSONArray() : n.optJSONArray("children");
                JSONArray rows = new JSONArray();
                try {
                    for (int i = 0; i < ch.length(); i += cols) {
                        JSONArray cells = new JSONArray();
                        for (int j = i; j < i + cols; j++) {
                            JSONObject cell = j < ch.length() ? new JSONObject(ch.optJSONObject(j) == null ? "{}" : ch.optJSONObject(j).toString()) : new JSONObject().put("type", "spacer");
                            cell.put("flex", 1);
                            cells.put(cell);
                        }
                        rows.put(new JSONObject().put("type", "row").put("gap", gap).put("align", "start").put("children", cells));
                    }
                    v = build(new JSONObject().put("type", "column").put("gap", gap).put("children", rows), parentOrient, parentAlign);
                } catch (Exception e) { v = new View(this); }
                break;
            }
            case "chart": {
                ChartView cv = new ChartView(this); cv.set(n); v = cv; break;
            }
            case "canvas": {
                CanvasView cv = new CanvasView(this); cv.set(n.optJSONArray("ops"));
                if (!id.isEmpty()) cv.onTap = (x, y) -> { try { send(event("tap", id, new JSONObject().put("x", x).put("y", y))); } catch (Exception ignored) {} };
                common(cv, n, null);
                return cv;
            }
            case "web": {
                String sig = n.optString("url") + "|" + n.optString("html");
                String key = "web:" + (id.isEmpty() ? sig.hashCode() : id);
                WebView wv = (WebView) keep.get(key);
                if (wv == null) {
                    wv = new WebView(this);
                    WebSettings ws = wv.getSettings(); ws.setJavaScriptEnabled(true); ws.setDomStorageEnabled(true); ws.setMediaPlaybackRequiresUserGesture(false);
                    wv.setWebViewClient(new WebViewClient());
                    keep.put(key, wv);
                } else if (wv.getParent() != null) ((ViewGroup) wv.getParent()).removeView(wv);
                usedKeep.add(key);
                if (!sig.equals(keepSig.get(key))) {
                    keepSig.put(key, sig);
                    String assetBase = appDir == null ? "about:blank" : "file://" + new File(appDir, "assets").getPath() + "/";
                    if (n.has("html")) { wv.getSettings().setAllowFileAccess(true); wv.loadDataWithBaseURL(n.optString("base", assetBase), n.optString("html"), "text/html", "utf-8", null); }
                    else {
                        String u = n.optString("url");
                        if (!u.startsWith("http")) wv.getSettings().setAllowFileAccess(true);
                        wv.loadUrl(u.startsWith("http") || u.startsWith("file:") ? u : assetBase + u);
                    }
                }
                v = wv; break;
            }
            case "video": {
                String src = n.optString("src", "");
                final String url = src.startsWith("http") || src.startsWith("file:") ? src : src.startsWith("/") ? "file://" + src : appDir == null ? "" : "file://" + new File(new File(appDir, "assets"), src).getPath();
                String key = "video:" + (id.isEmpty() ? url.hashCode() : id);
                VideoView vv = (VideoView) keep.get(key);
                if (vv == null) {
                    vv = new VideoView(this);
                    final VideoView fvv = vv;
                    if (n.optBoolean("controls", true)) { MediaController mc = new MediaController(this); mc.setAnchorView(vv); vv.setMediaController(mc); }
                    final boolean loop = n.optBoolean("loop"), auto = n.optBoolean("autoplay"), muted = n.optBoolean("muted");
                    final String vid = id;
                    vv.setOnErrorListener((mp, what, extra) -> {
                        TextView msg = new TextView(this); msg.setText("Video unavailable"); msg.setTextColor(0xFFFFFFFF); msg.setGravity(Gravity.CENTER);
                        if (fvv.getParent() instanceof ViewGroup) { fvv.setBackgroundColor(0xFF111111); }
                        Toast.makeText(this, "Video unavailable", Toast.LENGTH_SHORT).show();
                        if (!vid.isEmpty()) send(event("error", vid, what));
                        return true;
                    });
                    vv.setOnPreparedListener(mp -> { mp.setLooping(loop); if (muted) mp.setVolume(0, 0); if (auto) fvv.start(); else fvv.seekTo(1); });
                    keep.put(key, vv);
                } else if (vv.getParent() != null) ((ViewGroup) vv.getParent()).removeView(vv);
                usedKeep.add(key);
                if (!url.equals(keepSig.get(key))) { keepSig.put(key, url); vv.setVideoURI(Uri.parse(url)); }
                v = vv; break;
            }
            case "native": {
                String key = "native:" + (id.isEmpty() ? n.optString("plugin") : id);
                JSONObject props = n.optJSONObject("props") == null ? new JSONObject() : n.optJSONObject("props");
                View nv = keep.get(key);
                try {
                    VoltViewPlugin vp = (VoltViewPlugin) pluginAny(n.optString("plugin"));
                    if (nv == null) { nv = vp.create(pluginHost, props); keep.put(key, nv); }
                    else { if (nv.getParent() != null) ((ViewGroup) nv.getParent()).removeView(nv); vp.update(nv, props); }
                    usedKeep.add(key);
                    v = nv;
                } catch (Throwable t) {
                    TextView tv = new TextView(this); tv.setText("[native view " + n.optString("plugin") + ": " + t.getMessage() + "]"); tv.setTextColor(0xFFB91C1C);
                    v = tv;
                }
                break;
            }
            case "stack": v = stack(n); break;
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
        if (!t.equals("button") && !t.equals("input") && !t.equals("image") && (n.has("bg") || n.has("radius") || n.has("gradient") || n.has("border")))
            v.setBackground(shape(n, 0, t.equals("card") ? 16 : 0));
        if (n.has("paddingTop") || n.has("paddingBottom") || n.has("paddingLeft") || n.has("paddingRight") || n.has("paddingH") || n.has("paddingV")) {
            int l = v.getPaddingLeft(), tp = v.getPaddingTop(), r = v.getPaddingRight(), b = v.getPaddingBottom();
            if (n.has("paddingH")) l = r = px(n.optDouble("paddingH"));
            if (n.has("paddingV")) tp = b = px(n.optDouble("paddingV"));
            if (n.has("paddingLeft")) l = px(n.optDouble("paddingLeft"));
            if (n.has("paddingRight")) r = px(n.optDouble("paddingRight"));
            if (n.has("paddingTop")) tp = px(n.optDouble("paddingTop"));
            if (n.has("paddingBottom")) b = px(n.optDouble("paddingBottom"));
            v.setPadding(l, tp, r, b);
        }
        if (n.has("minWidth")) v.setMinimumWidth(px(n.optDouble("minWidth")));
        if (n.has("minHeight")) v.setMinimumHeight(px(n.optDouble("minHeight")));
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (n.has("maxWidth")) tv.setMaxWidth(px(n.optDouble("maxWidth")));
            if (n.has("letterSpacing")) tv.setLetterSpacing((float) n.optDouble("letterSpacing"));
            if (n.has("lineHeight")) tv.setLineSpacing(0, (float) n.optDouble("lineHeight"));
            if (n.optBoolean("underline")) tv.setPaintFlags(tv.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
            if (n.optBoolean("strike")) tv.setPaintFlags(tv.getPaintFlags() | android.graphics.Paint.STRIKE_THRU_TEXT_FLAG);
            if (n.optBoolean("uppercase")) tv.setAllCaps(true);
            if (n.has("weight") && Build.VERSION.SDK_INT >= 28 && !n.has("font"))
                tv.setTypeface(Typeface.create(n.optBoolean("mono") ? Typeface.MONOSPACE : Typeface.DEFAULT, Math.max(1, Math.min(1000, n.optInt("weight", 400))), n.optBoolean("italic")));
            if (n.has("textShadow")) tv.setShadowLayer(px(2), 0, px(1), color(n.optString("textShadow"), 0x55000000));
        }
        if (n.has("opacity")) v.setAlpha((float) n.optDouble("opacity", 1));
        if (n.has("rotate")) v.setRotation((float) n.optDouble("rotate", 0));
        if (n.has("scale")) { v.setScaleX((float) n.optDouble("scale", 1)); v.setScaleY((float) n.optDouble("scale", 1)); }
        if (n.has("elevation") && !t.equals("card")) v.setElevation(px(n.optDouble("elevation", 0)));
        if (n.has("border") && v.getBackground() instanceof GradientDrawable) ((GradientDrawable) v.getBackground()).setStroke(px(n.optDouble("borderWidth", 1)), color(n.optString("border"), 0xFFD9DCE3));
        if (n.has("font") && v instanceof TextView) applyFont((TextView) v, n.optString("font"), n.optBoolean("bold"));
        JSONObject an = n.optJSONObject("animate");
        String akey = n.optString("id", n.optString("key", ""));
        if (an != null && !akey.isEmpty() && animated.add(akey)) {
            JSONObject from = an.optJSONObject("from") == null ? an : an.optJSONObject("from");
            final float a1 = v.getAlpha(), s1 = v.getScaleX(), r1 = v.getRotation();
            if (from.has("alpha")) v.setAlpha((float) from.optDouble("alpha"));
            if (from.has("scale")) { v.setScaleX((float) from.optDouble("scale")); v.setScaleY((float) from.optDouble("scale")); }
            if (from.has("rotate")) v.setRotation((float) from.optDouble("rotate"));
            if (from.has("x")) v.setTranslationX(px(from.optDouble("x")));
            if (from.has("y")) v.setTranslationY(px(from.optDouble("y")));
            v.animate().alpha(a1).scaleX(s1).scaleY(s1).rotation(r1).translationX(0).translationY(0)
                    .setStartDelay(an.optLong("delay", 0)).setDuration(an.optLong("duration", 300))
                    .setInterpolator(new android.view.animation.DecelerateInterpolator()).start();
        }
        if (n.has("visible") && !n.optBoolean("visible", true)) v.setVisibility(View.GONE);
        if (tapId != null && !tapId.isEmpty() && !(v instanceof android.widget.AdapterView)) {
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
        if (t.equals("chart") || t.equals("canvas") || t.equals("web") || t.equals("video")) h = px(t.equals("chart") ? 200 : t.equals("video") ? 200 : 240);
        if (t.equals("spacer")) { int s = px(n.optDouble("size", 0)); if (col) h = s; else w = s; }
        boolean autoFlex = t.equals("list") && !rootScroll && col && !n.has("height") && !n.has("flex");
        w = dim(n.opt("width"), w); h = dim(n.opt("height"), h);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h);
        if (n.has("flex") || autoFlex) { lp.weight = (float) n.optDouble("flex", 1); if (col) lp.height = 0; else lp.width = 0; }
        if (n.has("margin")) {
            JSONArray ma = n.optJSONArray("margin");
            if (ma != null && ma.length() == 4) lp.setMargins(px(ma.optDouble(3)), px(ma.optDouble(0)), px(ma.optDouble(1)), px(ma.optDouble(2)));
            else if (ma != null && ma.length() == 2) lp.setMargins(px(ma.optDouble(1)), px(ma.optDouble(0)), px(ma.optDouble(1)), px(ma.optDouble(0)));
            else { int m = px(n.optDouble("margin", 0)); lp.setMargins(m, m, m, m); }
        }
        if (n.has("marginTop")) lp.topMargin = px(n.optDouble("marginTop"));
        if (n.has("marginBottom")) lp.bottomMargin = px(n.optDouble("marginBottom"));
        if (n.has("marginLeft")) lp.leftMargin = px(n.optDouble("marginLeft"));
        if (n.has("marginRight")) lp.rightMargin = px(n.optDouble("marginRight"));
        if (n.has("alignSelf")) { String as = n.optString("alignSelf"); lp.gravity = as.equals("center") ? (col ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL) : as.equals("end") ? (col ? Gravity.END : Gravity.BOTTOM) : (col ? Gravity.START : Gravity.TOP); }
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
        if (hasContent && engine != null) { Toast.makeText(this, String.valueOf(e.getMessage()), Toast.LENGTH_SHORT).show(); return; }
        LinearLayout ll = form("dev".equals(source) ? "Can't reach the dev server" : "Couldn't start the app");
        TextView t = new TextView(this); t.setText(("dev".equals(source) ? server + "\n\n" : "") + e); t.setTextColor(0xFF6B7280);
        ll.addView(t);
        Button retry = primary("Retry"); retry.setOnClickListener(x -> start()); ll.addView(retry, wide());
        if (!locked) { Button sw = primary("Change server / app"); sw.setOnClickListener(x -> showConnect()); ll.addView(sw, wide()); }
        setForm(ll);
    }

    void showConnect() {
        watching = false; started = false; hasContent = false;
        setTitle("VoltForge");
        LinearLayout ll = form("⚡ VoltForge");
        TextView sub = new TextView(this); sub.setText("Connect to your computer running `volt.py serve`. Code is downloaded and runs on this phone."); sub.setTextColor(0xFF6B7280); ll.addView(sub);
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
                                app = a.optString("name"); source = "dev"; state = null;
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
        if (backId != null && started) { goingBack = true; send(event("tap", backId, null)); } else super.onBackPressed();
    }

    @Override public boolean onCreateOptionsMenu(Menu m) {
        m.add(0, 1, 0, "Reload"); m.add(0, 2, 0, "Reset state");
        if (!locked) m.add(0, 3, 0, "Switch app");
        return true;
    }

    @Override public boolean onOptionsItemSelected(MenuItem i) {
        if (i.getItemId() == 1 && started) reload("dev".equals(source));
        else if (i.getItemId() == 2 && started) net.execute(() -> {
            state = null; VoltApp.saveState(this, app, null);
            main.post(() -> { inputViews.clear(); inputs.clear(); lastServerVal.clear(); send(null); });
        });
        else if (i.getItemId() == 3) showConnect();
        return true;
    }

    // ---------------------------------------------------------------- native features
    void nativeFx(JSONObject fx) {
        try {
            if (fx.has("camera")) camera(fx.optString("camera"));
            if (fx.has("pick")) {
                Object p = fx.opt("pick");
                JSONObject o = p instanceof JSONObject ? (JSONObject) p : new JSONObject().put("id", String.valueOf(p));
                pick(o.optString("id", "pick"), o.optString("type", "image"));
            }
            if (fx.has("location")) location(fx.optString("location"));
            if (fx.has("notify")) {
                Object n = fx.opt("notify");
                JSONArray arr = n instanceof JSONArray ? (JSONArray) n : new JSONArray().put(n instanceof JSONObject ? n : new JSONObject().put("text", String.valueOf(n)));
                for (int i = 0; i < arr.length(); i++) if (arr.optJSONObject(i) != null) notifyFx(arr.optJSONObject(i));
            }
            if (fx.has("share")) {
                Intent s = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, fx.optString("share"));
                startActivity(Intent.createChooser(s, null));
            }
            if (fx.has("clipboard")) {
                ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("text", fx.optString("clipboard")));
                if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
            }
            if (fx.has("contact")) {
                pendContact = fx.optString("contact");
                startActivityForResult(new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI), RC_CONTACT);
            }
            if (fx.has("pickDate")) {
                JSONObject o = fx.opt("pickDate") instanceof JSONObject ? fx.optJSONObject("pickDate") : new JSONObject().put("id", fx.optString("pickDate"));
                final String pid = o.optString("id", "date");
                Calendar cal = Calendar.getInstance();
                String[] pv = o.optString("value", "").split("-");
                int y = cal.get(Calendar.YEAR), mo = cal.get(Calendar.MONTH), d = cal.get(Calendar.DAY_OF_MONTH);
                if (pv.length == 3) try { y = Integer.parseInt(pv[0]); mo = Integer.parseInt(pv[1]) - 1; d = Integer.parseInt(pv[2]); } catch (Exception ignored) {}
                DatePickerDialog dp0 = new DatePickerDialog(this, (dv, yy, mm, dd) -> result(pid, String.format(Locale.US, "%04d-%02d-%02d", yy, mm + 1, dd)), y, mo, d);
                dp0.setOnCancelListener(x -> result(pid, err("cancelled")));
                dp0.show();
            }
            if (fx.has("pickTime")) {
                JSONObject o = fx.opt("pickTime") instanceof JSONObject ? fx.optJSONObject("pickTime") : new JSONObject().put("id", fx.optString("pickTime"));
                final String pid = o.optString("id", "time");
                Calendar cal = Calendar.getInstance();
                String[] pv = o.optString("value", "").split(":");
                int hh = cal.get(Calendar.HOUR_OF_DAY), mi = cal.get(Calendar.MINUTE);
                if (pv.length == 2) try { hh = Integer.parseInt(pv[0]); mi = Integer.parseInt(pv[1]); } catch (Exception ignored) {}
                TimePickerDialog tp = new TimePickerDialog(this, (tv, h2, m2) -> result(pid, String.format(Locale.US, "%02d:%02d", h2, m2)), hh, mi, true);
                tp.setOnCancelListener(x -> result(pid, err("cancelled")));
                tp.show();
            }
            if (fx.has("native")) {
                Object nv = fx.opt("native");
                JSONArray arr = nv instanceof JSONArray ? (JSONArray) nv : new JSONArray().put(nv);
                for (int i = 0; i < arr.length(); i++) if (arr.optJSONObject(i) != null) callPlugin(arr.optJSONObject(i));
            }
            if (fx.has("background")) {
                JSONObject bg = fx.optJSONObject("background");
                if (!VoltSecure.on("backgroundJobs", true)) Toast.makeText(this, "Background jobs are disabled for this app", Toast.LENGTH_SHORT).show();
                else if (bg != null && bg.optBoolean("on", true)) { prefs.edit().putBoolean("bg:on", true).apply(); VoltJob.schedule(this, bg.optInt("every", 15)); }
                else { prefs.edit().putBoolean("bg:on", false).apply(); VoltJob.cancel(this); }
            }
            if (fx.has("finish")) {
                Object fv = fx.opt("finish");
                Intent data = new Intent().putExtra("volt.result", fv == null || fv == JSONObject.NULL ? null : fv.toString());
                setResult(RESULT_OK, data);
                finish();
                return;
            }
            if (fx.has("store") && fx.optJSONObject("store") != null) VoltApp.putStore(this, app, fx.optJSONObject("store"));
        } catch (Exception e) {
            Toast.makeText(this, "Native action failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    JSONObject store() { return VoltApp.store(this, app); }

    JSONObject device() {
        JSONObject d = new JSONObject();
        try {
            d.put("platform", "android").put("sdk", Build.VERSION.SDK_INT).put("model", Build.MODEL).put("brand", Build.MANUFACTURER)
             .put("lang", Locale.getDefault().toLanguageTag())
             .put("dark", (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES)
             .put("width", Math.round(getResources().getDisplayMetrics().widthPixels / dp)).put("height", Math.round(getResources().getDisplayMetrics().heightPixels / dp));
        } catch (Exception ignored) {}
        return d;
    }

    static JSONObject err(String msg) {
        try { return new JSONObject().put("error", msg == null ? "failed" : msg); } catch (Exception e) { return new JSONObject(); }
    }

    void result(final String id, final Object value) {
        if (id == null) return;
        main.post(() -> send(event("result", id, value)));
    }

    void camera(String id) {
        try {
            File dir = new File(getCacheDir(), "cam"); dir.mkdirs();
            String name = "c" + System.currentTimeMillis() + ".jpg";
            camFile = new File(dir, name);
            Uri uri = Uri.parse("content://" + getPackageName() + ".files/" + name);
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.setClipData(ClipData.newRawUri("", uri));
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            pendCam = id;
            startActivityForResult(i, RC_CAM);
        } catch (Exception e) { pendCam = null; result(id, err("camera unavailable")); }
    }

    void pick(String id, String type) {
        try {
            Intent i = new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE);
            i.setType(type.equals("image") ? "image/*" : type.equals("any") || type.isEmpty() ? "*/*" : type);
            pendPick = id;
            startActivityForResult(i, RC_PICK);
        } catch (Exception e) { pendPick = null; result(id, err("picker unavailable")); }
    }

    @Override protected void onActivityResult(int rc, int res, Intent data) {
        super.onActivityResult(rc, res, data);
        VoltPlugin.ActivityCallback acb = actCbs.remove(rc);
        if (acb != null) { try { acb.done(res, data); } catch (Exception e) { Toast.makeText(this, e.toString(), Toast.LENGTH_SHORT).show(); } return; }
        if (rc == RC_CAM) {
            final String id = pendCam; pendCam = null;
            final File f = camFile;
            if (res != RESULT_OK || f == null || !f.exists() || f.length() == 0) { result(id, err("cancelled")); return; }
            net.execute(() -> {
                try { byte[] raw = readBytes(new FileInputStream(f)); f.delete(); upload(id, scaleJpeg(raw), "image/jpeg", "photo.jpg"); }
                catch (Exception e) { result(id, err(e.getMessage())); }
            });
        } else if (rc == RC_PICK) {
            final String id = pendPick; pendPick = null;
            if (res != RESULT_OK || data == null || data.getData() == null) { result(id, err("cancelled")); return; }
            final Uri u = data.getData();
            net.execute(() -> {
                try {
                    String type = getContentResolver().getType(u); if (type == null) type = "application/octet-stream";
                    String name = "file";
                    Cursor c = getContentResolver().query(u, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                    if (c != null) { if (c.moveToFirst()) name = c.getString(0); c.close(); }
                    byte[] raw = readBytes(getContentResolver().openInputStream(u));
                    if (type.startsWith("image/") && !type.equals("image/gif")) { raw = scaleJpeg(raw); type = "image/jpeg"; }
                    upload(id, raw, type, name);
                } catch (Exception e) { result(id, err(e.getMessage())); }
            });
        } else if (rc == RC_CONTACT) {
            String id = pendContact; pendContact = null;
            if (res != RESULT_OK || data == null || data.getData() == null) { result(id, err("cancelled")); return; }
            try {
                Cursor c = getContentResolver().query(data.getData(), new String[]{ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER}, null, null, null);
                JSONObject o = new JSONObject();
                if (c != null) { if (c.moveToFirst()) o.put("name", c.getString(0)).put("phone", c.getString(1)); c.close(); }
                result(id, o.length() > 0 ? o : err("no data"));
            } catch (Exception e) { result(id, err(e.getMessage())); }
        }
    }

    static byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static byte[] scaleJpeg(byte[] raw) {
        BitmapFactory.Options o = new BitmapFactory.Options(); o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(raw, 0, raw.length, o);
        if (o.outWidth <= 0) return raw;
        int s = 1; while (Math.max(o.outWidth, o.outHeight) / s > 1600) s *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options(); o2.inSampleSize = s;
        Bitmap b = BitmapFactory.decodeByteArray(raw, 0, raw.length, o2);
        if (b == null) return raw;
        int rot = 0;
        try {
            int or = new ExifInterface(new ByteArrayInputStream(raw)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            rot = or == ExifInterface.ORIENTATION_ROTATE_90 ? 90 : or == ExifInterface.ORIENTATION_ROTATE_180 ? 180 : or == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {}
        if (rot != 0) { Matrix m = new Matrix(); m.postRotate(rot); b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true); }
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 85, bo);
        return bo.toByteArray();
    }

    void upload(String id, byte[] bytes, String type, String name) throws Exception {
        File dir = new File(getFilesDir(), "media"); dir.mkdirs();
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.')).replaceAll("[^A-Za-z0-9.]", "") : type.equals("image/jpeg") ? ".jpg" : "";
        File f = new File(dir, UUID.randomUUID().toString().replace("-", "") + ext);
        try (OutputStream os = new java.io.FileOutputStream(f)) { os.write(bytes); }
        result(id, new JSONObject().put("url", "file://" + f.getAbsolutePath()).put("path", f.getAbsolutePath()).put("type", type).put("size", bytes.length).put("name", name));
    }

    boolean granted(String p) { return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED; }

    void location(String id) {
        pendLoc = id;
        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, RP_LOC);
            return;
        }
        getLocation();
    }

    static JSONObject loc(Location l) {
        try { return new JSONObject().put("lat", l.getLatitude()).put("lng", l.getLongitude()).put("accuracy", l.getAccuracy()).put("time", l.getTime()); }
        catch (Exception e) { return err("bad location"); }
    }

    void getLocation() {
        final String id = pendLoc; pendLoc = null;
        final LocationManager lm = (LocationManager) getSystemService(LOCATION_SERVICE);
        Location best = null;
        for (String p : lm.getProviders(true)) {
            try { Location l = lm.getLastKnownLocation(p); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } catch (SecurityException ignored) {}
        }
        if (best != null && System.currentTimeMillis() - best.getTime() < 120000) { result(id, loc(best)); return; }
        String prov = lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ? LocationManager.GPS_PROVIDER
                : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : null;
        final Location fallback = best;
        if (prov == null) { result(id, fallback != null ? loc(fallback) : err("location is turned off")); return; }
        final boolean[] done = {false};
        final LocationListener L = new LocationListener() {
            public void onLocationChanged(Location l) { if (done[0]) return; done[0] = true; lm.removeUpdates(this); result(id, loc(l)); }
            public void onStatusChanged(String p, int s, Bundle b) {}
            public void onProviderEnabled(String p) {}
            public void onProviderDisabled(String p) {}
        };
        try { lm.requestLocationUpdates(prov, 0, 0, L, Looper.getMainLooper()); }
        catch (SecurityException e) { result(id, err("denied")); return; }
        main.postDelayed(() -> { if (!done[0]) { done[0] = true; lm.removeUpdates(L); result(id, fallback != null ? loc(fallback) : err("timeout")); } }, 15000);
    }

    void notifyFx(JSONObject n) {
        if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS)) {
            notifQueue.add(n);
            if (notifQueue.size() == 1) requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, RP_NOTIF);
            return;
        }
        postNotif(n);
    }

    void postNotif(JSONObject n) {
        String title = n.optString("title", String.valueOf(getTitle())), text = n.optString("text", ""), tag = n.optString("tag", "");
        double at = n.optDouble("at", 0);
        if (at > 0) VoltAlarm.schedule(this, title, text, tag, (long) (at * 1000));
        else VoltAlarm.post(this, title, text, tag);
    }

    @Override public void onRequestPermissionsResult(int rc, String[] perms, int[] res) {
        boolean ok = false;
        for (int r : res) ok |= r == PackageManager.PERMISSION_GRANTED;
        VoltPlugin.PermissionCallback pcb = permCbs.remove(rc);
        if (pcb != null) { boolean all = res.length > 0; for (int r : res) all &= r == PackageManager.PERMISSION_GRANTED; pcb.done(all); return; }
        if (rc == RP_LOC) { if (ok) getLocation(); else { result(pendLoc, err("denied")); pendLoc = null; } }
        else if (rc == RP_NOTIF) {
            if (ok) for (JSONObject n : notifQueue) postNotif(n);
            else Toast.makeText(this, "Notifications are blocked", Toast.LENGTH_SHORT).show();
            notifQueue.clear();
        }
    }

    @Override protected void onNewIntent(Intent i) { super.onNewIntent(i); setIntent(i); handleTap(i); }

    void handleTap(Intent i) {
        if (i == null || !started) return;
        String tag = i.getStringExtra("volt_tap");
        if (tag == null) return;
        i.removeExtra("volt_tap");
        send(event("notification", tag, null));
    }

    // ---------------------------------------------------------------- plugins
    class VList extends BaseAdapter {
        JSONArray items = new JSONArray(); String onEnd = ""; int endFired = -1;
        public int getCount() { return items.length(); }
        public Object getItem(int i) { return items.opt(i); }
        public long getItemId(int i) { return i; }
        public View getView(int i, View convert, ViewGroup parent) {
            JSONObject it = items.optJSONObject(i);
            View v = it == null ? new View(MainActivity.this) : build(it, LinearLayout.VERTICAL, "start");
            v.setLayoutParams(new AbsListView.LayoutParams(-1, -2));
            return v;
        }
    }

    final VoltPlugin.Host pluginHost = new VoltPlugin.Host() {
        public Activity activity() { return MainActivity.this; }
        public String app() { return app; }
        public void requestPermissions(String[] p, VoltPlugin.PermissionCallback cb) {
            boolean all = true;
            for (String x : p) all &= granted(x);
            if (all) { cb.done(true); return; }
            int rc = nextReq++; permCbs.put(rc, cb); MainActivity.this.requestPermissions(p, rc);
        }
        public void startForResult(Intent i, VoltPlugin.ActivityCallback cb) { int rc = nextReq++; actCbs.put(rc, cb); startActivityForResult(i, rc); }
        public void emit(final String id, final Object value) { main.post(() -> send(event("native", id, value))); }
        public void runOnUi(Runnable r) { main.post(r); }
        public void runInBackground(Runnable r) { imgPool.execute(r); }
    };

    VoltPlugin plugin(String name) throws Exception {
        loadPluginMap();
        VoltPlugin p = plugins.get(name);
        if (p != null) return p;
        String cls = pluginClasses.get(name);
        if (cls == null) throw new Exception("plugin '" + name + "' is not in this APK (rebuild it after adding native code)");
        p = (VoltPlugin) Class.forName(cls).getDeclaredConstructor().newInstance();
        plugins.put(name, p);
        return p;
    }

    void callPlugin(JSONObject req) {
        final String id = req.optString("id", req.optString("plugin") + "." + req.optString("method"));
        final boolean[] done = {false};
        VoltPlugin.Result res = new VoltPlugin.Result() {
            public void ok(Object value) { if (done[0]) return; done[0] = true; result(id, value == null ? JSONObject.NULL : value); }
            public void error(String m) { if (done[0]) return; done[0] = true; result(id, err(m)); }
        };
        try {
            JSONObject args = req.optJSONObject("args");
            plugin(req.optString("plugin")).call(pluginHost, req.optString("method"), args == null ? new JSONObject() : args, res);
        } catch (Throwable t) { res.error(t.getClass().getSimpleName() + ": " + t.getMessage()); }
    }

    // ---------------------------------------------------------------- tabs, dialogs, fonts
    View tabBar(JSONObject tabs) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setBackgroundColor(color(tabs.optString("bg", ""), 0xFFFFFFFF));
        bar.setElevation(px(8));
        bar.setPadding(0, px(6), 0, px(6));
        String selected = tabs.optString("selected", "");
        int accent = color(tabs.optString("color", lastScreen == null ? "" : lastScreen.optString("accent", "")), 0xFF6C3BFF);
        JSONArray items = tabs.optJSONArray("items") == null ? new JSONArray() : tabs.optJSONArray("items");
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i); if (it == null) continue;
            final String tid = it.optString("id");
            boolean on = tid.equals(selected);
            LinearLayout cell = new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setGravity(Gravity.CENTER);
            TextView ic = new TextView(this); ic.setText(it.optString("icon", "•")); ic.setTextSize(20); ic.setGravity(Gravity.CENTER);
            ic.setAlpha(on ? 1f : 0.55f);
            TextView lb = new TextView(this); lb.setText(it.optString("label", tid)); lb.setTextSize(12); lb.setGravity(Gravity.CENTER);
            lb.setTextColor(on ? accent : 0xFF6B7280); if (on) lb.setTypeface(Typeface.DEFAULT_BOLD);
            cell.addView(ic); cell.addView(lb);
            cell.setClickable(true);
            cell.setForeground(new RippleDrawable(ColorStateList.valueOf(0x22000000), null, null));
            cell.setOnClickListener(x -> send(event("tap", tid, null)));
            bar.addView(cell, new LinearLayout.LayoutParams(0, -2, 1));
        }
        return bar;
    }

    void showDialog(JSONObject d) {
        String key = d == null ? null : d.toString();
        if (key != null && key.equals(dialogKey) && dialog != null && dialog.isShowing()) return;
        if (dialog != null) { dialog.setOnDismissListener(null); dialog.dismiss(); dialog = null; }
        dialogKey = key;
        if (d == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(this);
        if (d.has("title")) b.setTitle(d.optString("title"));
        if (d.has("text")) b.setMessage(d.optString("text"));
        JSONArray bt = d.optJSONArray("buttons") == null ? new JSONArray() : d.optJSONArray("buttons");
        if (bt.length() > 0) { final String id0 = bt.optJSONObject(0).optString("id"); b.setPositiveButton(bt.optJSONObject(0).optString("text", "OK"), (x, w) -> send(event("tap", id0, null))); }
        if (bt.length() > 1) { final String id1 = bt.optJSONObject(1).optString("id"); b.setNegativeButton(bt.optJSONObject(1).optString("text", "Cancel"), (x, w) -> send(event("tap", id1, null))); }
        if (bt.length() > 2) { final String id2 = bt.optJSONObject(2).optString("id"); b.setNeutralButton(bt.optJSONObject(2).optString("text"), (x, w) -> send(event("tap", id2, null))); }
        final String cancelId = d.optString("cancel", "");
        b.setCancelable(!cancelId.isEmpty());
        dialog = b.create();
        if (!cancelId.isEmpty()) dialog.setOnCancelListener(x -> send(event("tap", cancelId, null)));
        dialog.show();
    }

    void applyFont(final TextView tv, final String name, boolean bold) {
        Typeface tf = fonts.get(name);
        if (tf != null) { tv.setTypeface(tf, bold ? Typeface.BOLD : Typeface.NORMAL); return; }
        if (!fontLoading.add(name)) return;
        imgPool.execute(() -> {
            try {
                String file = name.contains(".") ? name : name + ".ttf";
                File f = new File(new File(new File(appDir, "assets"), "fonts"), file);
                if (!f.exists() && !file.endsWith(".otf")) f = new File(f.getParentFile(), name + ".otf");
                final Typeface loaded = Typeface.createFromFile(f);
                main.post(() -> { fonts.put(name, loaded); if (lastScreen != null) render(lastScreen); });
            } catch (Exception e) { fontLoading.remove(name); }
        });
    }

    void loadPluginMap() {
        if (pluginClasses != null) return;
        pluginClasses = new HashMap<>();
        try {
            JSONArray arr = new JSONArray(readAll(getAssets().open("plugins.json")));
            for (int i = 0; i < arr.length(); i++) pluginClasses.put(arr.getJSONObject(i).getString("name"), arr.getJSONObject(i).getString("class"));
        } catch (Exception ignored) {}
    }

    Object pluginAny(String name) throws Exception {
        loadPluginMap();
        Object o = viewPlugins.get(name);
        if (o != null) return o;
        String cls = pluginClasses.get(name);
        if (cls == null) throw new Exception("plugin '" + name + "' is not in this APK");
        o = Class.forName(cls).getDeclaredConstructor().newInstance();
        viewPlugins.put(name, o);
        return o;
    }
    final Map<String, Object> viewPlugins = new HashMap<>();

    // ---------------------------------------------------------------- direct Android calls from scripts
    final VoltEngine.Handler hostHandler = new VoltEngine.Handler() {
        public void log(String text) {}
        public Object call(String plugin, String method, JSONObject args) throws Exception { return hostCall(plugin, method, args); }
    };

    Object onMain(final java.util.concurrent.Callable<Object> c) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) return c.call();
        final Object[] box = new Object[2];
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        main.post(() -> { try { box[0] = c.call(); } catch (Throwable t) { box[1] = t; } l.countDown(); });
        l.await();
        if (box[1] != null) throw new Exception(((Throwable) box[1]).getMessage());
        return box[0];
    }

    Object hostCall(String plugin, String method, final JSONObject args) throws Exception {
        switch (plugin) {
            case "toast": return onMain(() -> { Toast.makeText(this, args.optString("text", method), Toast.LENGTH_SHORT).show(); return null; });
            case "device": return device();
            case "storage":
                if (method.equals("get")) return store().opt(args.optString("key"));
                if (method.equals("all")) return store();
                if (method.equals("set")) { VoltApp.putStore(this, app, new JSONObject().put(args.optString("key"), args.opt("value") == null ? JSONObject.NULL : args.opt("value"))); return true; }
                if (method.equals("remove")) { VoltApp.putStore(this, app, new JSONObject().put(args.optString("key"), JSONObject.NULL)); return true; }
                break;
            case "clipboard":
                return onMain(() -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    if (method.equals("set")) { cm.setPrimaryClip(ClipData.newPlainText("text", args.optString("text"))); return true; }
                    ClipData d = cm.getPrimaryClip();
                    return d == null || d.getItemCount() == 0 ? JSONObject.NULL : String.valueOf(d.getItemAt(0).coerceToText(this));
                });
            case "vibrate": {
                Vibrator vb = (Vibrator) getSystemService(VIBRATOR_SERVICE);
                long ms = args.optLong("ms", 30);
                if (vb != null) { if (Build.VERSION.SDK_INT >= 26) vb.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)); else vb.vibrate(ms); }
                return true;
            }
            case "http": return httpCall(method, args);
            case "files": return filesCall(method, args);
            case "open": return onMain(() -> { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(args.optString("url", method)))); return true; });
            case "app":
                if (method.equals("info")) return new JSONObject().put("app", app).put("source", source).put("dir", appDir == null ? "" : appDir.getPath()).put("lang", engine == null ? "" : engine.lang);
                if (method.equals("update")) {
                    boolean changed = sdkSource != null ? sdkSource.isRemote() && sdkSource.fetch(this) : "remote".equals(source) && VoltApp.updateRemote(this);
                    if (changed) reload(false);
                    return changed;
                }
                break;
        }
        return pluginSync(plugin, method, args);
    }

    Object httpCall(String method, JSONObject a) throws Exception {
        String url = a.optString("url", method);
        HttpURLConnection c = VoltSecure.open(url);
        c.setRequestMethod(a.optString("method", "GET").toUpperCase(Locale.US));
        c.setConnectTimeout(a.optInt("timeout", 15000)); c.setReadTimeout(a.optInt("timeout", 15000));
        JSONObject hd = a.optJSONObject("headers");
        if (hd != null) { Iterator<String> it = hd.keys(); while (it.hasNext()) { String k = it.next(); c.setRequestProperty(k, hd.optString(k)); } }
        Object body = a.opt("body");
        if (body != null && body != JSONObject.NULL) {
            byte[] b = (body instanceof String ? (String) body : body.toString()).getBytes("UTF-8");
            if (!(body instanceof String) && (hd == null || !hd.has("Content-Type"))) c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true); c.setFixedLengthStreamingMode(b.length);
            try (OutputStream os = c.getOutputStream()) { os.write(b); }
        }
        int code = c.getResponseCode();
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        String text = in == null ? "" : readAll(in);
        JSONObject headers = new JSONObject();
        for (Map.Entry<String, List<String>> e : c.getHeaderFields().entrySet()) if (e.getKey() != null) headers.put(e.getKey().toLowerCase(Locale.US), android.text.TextUtils.join(", ", e.getValue()));
        JSONObject r = new JSONObject().put("status", code).put("ok", code < 400).put("body", text).put("headers", headers);
        if (a.optBoolean("json")) try { r.put("json", new org.json.JSONTokener(text).nextValue()); } catch (Exception ignored) {}
        return r;
    }

    Object filesCall(String method, JSONObject a) throws Exception {
        File base = new File(getFilesDir(), "user"); base.mkdirs();
        String rel = a.optString("path", "");
        File f = new File(base, rel);
        if (!f.getCanonicalPath().startsWith(base.getCanonicalPath())) throw new SecurityException("path outside app storage");
        switch (method) {
            case "read": return f.exists() ? new String(readBytes(new FileInputStream(f)), "UTF-8") : JSONObject.NULL;
            case "write": { f.getParentFile().mkdirs(); try (OutputStream os = new java.io.FileOutputStream(f, a.optBoolean("append"))) { os.write(a.optString("text").getBytes("UTF-8")); } return true; }
            case "delete": VoltApp.delete(f); return true;
            case "exists": return f.exists();
            case "list": { JSONArray out = new JSONArray(); String[] k = f.list(); if (k != null) for (String x : k) out.put(x); return out; }
            case "path": return f.getAbsolutePath();
        }
        throw new Exception("files." + method + " is not supported");
    }

    Object pluginSync(String name, String method, JSONObject args) throws Exception {
        final VoltPlugin p = (VoltPlugin) onMain(() -> plugin(name));
        final Object[] box = new Object[2];
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        final VoltPlugin.Result res = new VoltPlugin.Result() {
            public void ok(Object value) { if (l.getCount() == 0) return; box[0] = value; l.countDown(); }
            public void error(String m) { if (l.getCount() == 0) return; box[1] = m == null ? "failed" : m; l.countDown(); }
        };
        main.post(() -> { try { p.call(pluginHost, method, args, res); } catch (Throwable t) { res.error(t.getClass().getSimpleName() + ": " + t.getMessage()); } });
        if (!l.await(120, java.util.concurrent.TimeUnit.SECONDS)) throw new Exception(name + "." + method + " timed out");
        if (box[1] != null) throw new Exception(String.valueOf(box[1]));
        return box[0];
    }
}
