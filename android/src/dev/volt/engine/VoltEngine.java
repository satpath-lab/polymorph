package dev.volt.engine;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public final class VoltEngine implements Closeable {
    public interface Handler {
        Object call(String plugin, String method, JSONObject args) throws Exception;
        void log(String text);
    }

    public static class ScriptError extends Exception { public ScriptError(String m) { super(m); } }

    static volatile Handler handler;
    static final Set<String> loaded = new HashSet<>();
    public static void setHandler(Handler h) { handler = h; }

    public final String lang;
    private long h;

    private VoltEngine(String lang, long h) { this.lang = lang; this.h = h; }

    public static String langOf(String main) {
        String ext = main.substring(main.lastIndexOf('.') + 1);
        switch (ext) {
            case "lua": return "lua";
            case "js": return "js";
            case "py": return "python";
            case "rb": return "ruby";
            case "php": return "php";
        }
        throw new IllegalArgumentException("unsupported script: " + main);
    }

    static String ext(String lang) { return lang.equals("python") ? "py" : lang.equals("ruby") ? "rb" : lang; }

    public static synchronized VoltEngine open(Context ctx, File dir, String main) throws Exception {
        String lang = langOf(main);
        if (lang.equals("python")) VoltPython.prepare(ctx);
        if (lang.equals("php")) android.system.Os.setenv("VOLT_TMP", ctx.getCacheDir().getPath(), true);
        if (loaded.add(lang)) System.loadLibrary("volt_" + lang);
        File harness = extract(ctx, "volt/harness." + ext(lang), new File(ctx.getFilesDir(), "volt"));
        return new VoltEngine(lang, nOpen(dir.getAbsolutePath(), new File(dir, main).getAbsolutePath(), harness.getAbsolutePath()));
    }

    public synchronized String call(String fn, String jsonArg) {
        if (h == 0) throw new IllegalStateException("engine closed");
        return new String(nCall(h, fn, jsonArg.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    public JSONObject step(Object state, JSONObject event) throws Exception {
        JSONObject req = new JSONObject();
        req.put("state", state == null ? JSONObject.NULL : state);
        req.put("event", event == null ? JSONObject.NULL : event);
        JSONObject r = new JSONObject(call("step", req.toString()));
        if (r.has("error")) throw new ScriptError(r.optString("error"));
        return r;
    }

    public Object invoke(String fn, Object... args) throws Exception {
        JSONArray a = new JSONArray();
        for (Object o : args) a.put(o == null ? JSONObject.NULL : o);
        JSONObject r = new JSONObject(call(fn, a.toString()));
        if (r.has("error")) throw new ScriptError(r.optString("error"));
        return r.opt("value");
    }

    @Override public synchronized void close() { if (h != 0) { nClose(h); h = 0; } }

    static byte[] host(byte[] req) {
        JSONObject out = new JSONObject();
        try {
            JSONObject r = new JSONObject(new String(req, StandardCharsets.UTF_8));
            Handler hd = handler;
            if ("log".equals(r.optString("op"))) {
                Log.i("VoltScript", r.optString("text"));
                if (hd != null) hd.log(r.optString("text"));
            } else if (hd == null) out.put("error", "no Android host attached");
            else {
                JSONObject args = r.optJSONObject("args");
                Object v = hd.call(r.optString("plugin"), r.optString("method"), args == null ? new JSONObject() : args);
                out.put("value", v == null ? JSONObject.NULL : v);
            }
        } catch (Throwable t) {
            try { out.put("error", t.getMessage() == null ? t.toString() : t.getMessage()); } catch (Exception ignored) {}
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    static File extract(Context ctx, String asset, File dir) throws Exception {
        File f = new File(dir, asset.substring(asset.lastIndexOf('/') + 1));
        dir.mkdirs();
        try (InputStream in = ctx.getAssets().open(asset); OutputStream os = new FileOutputStream(f)) {
            byte[] b = new byte[16384]; int n;
            while ((n = in.read(b)) > 0) os.write(b, 0, n);
        }
        return f;
    }

    static native long nOpen(String dir, String main, String harness);
    static native byte[] nCall(long h, String fn, byte[] arg);
    static native void nClose(long h);
}
