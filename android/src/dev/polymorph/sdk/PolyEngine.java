package dev.polymorph.sdk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class PolyEngine implements Closeable {
    public interface Handler {
        Object call(String plugin, String method, JSONObject args) throws Exception;
        void log(String text);
    }

    public static class ScriptError extends Exception { public ScriptError(String m) { super(m); } }

    public static final class Builder {
        final Context ctx;
        ScriptSource source;
        final Map<String, Object> objects = new ConcurrentHashMap<>();
        final Map<String, PolyFunction> functions = new ConcurrentHashMap<>();
        final Map<String, List<PolyListener>> listeners = new ConcurrentHashMap<>();
        Handler handler;

        Builder(Context c) { ctx = c.getApplicationContext(); }
        public Builder source(ScriptSource s) { source = s; return this; }
        public Builder expose(String name, Object target) { objects.put(name, target); return this; }
        public Builder function(String name, PolyFunction fn) { functions.put(name, fn); return this; }
        public Builder on(String event, PolyListener l) { listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(l); return this; }
        public Builder handler(Handler h) { handler = h; return this; }

        public PolyEngine open() throws Exception {
            if (source == null) throw new IllegalStateException("call source(...) first");
            PolyEngine e = new PolyEngine(ctx, this);
            try { e.run(() -> { e.load(); return null; }); } catch (Exception ex) { e.shutdown(); throw ex; }
            return e;
        }

        public void openAsync(final PolyResult cb) {
            new Thread(() -> {
                try { PolyEngine e = open(); main.post(() -> cb.done(e, null)); }
                catch (Exception ex) { main.post(() -> cb.done(null, ex)); }
            }, "poly-open").start();
        }
    }

    static volatile Handler handler;
    static final Set<String> loaded = new HashSet<>();
    static final android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
    static final ThreadLocal<PolyEngine> current = new ThreadLocal<>();
    public static void setHandler(Handler h) { handler = h; }

    public String lang;
    final Context ctx;
    ScriptSource source;
    File dir;
    String mainFile;
    private long h;
    PolyNative nat;
    final ExecutorService exec;
    volatile Thread thread;
    final Map<String, Object> objects;
    final Map<String, PolyFunction> functions;
    final Map<String, List<PolyListener>> listeners;
    Handler own;

    PolyEngine(Context ctx, Builder b) {
        this.ctx = ctx; this.source = b.source;
        objects = b.objects; functions = b.functions; listeners = b.listeners; own = b.handler;
        exec = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "poly-script"); thread = t; return t; });
    }

    public static synchronized PolyEngine open(Context ctx, File dir, String main) throws Exception {
        return new Builder(ctx).source(ScriptSource.dir(dir).main(main)).open();
    }

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

    void load() throws Exception {
        File d = source.resolve(ctx);
        String m = source.mainFile(d);
        String l = langOf(m);
        synchronized (PolyEngine.class) {
            if (l.equals("python")) PolyPython.prepare(ctx);
            if (l.equals("php")) android.system.Os.setenv("VOLT_TMP", ctx.getCacheDir().getPath(), true);
        }
        PolyNative n = PolyNative.get(ctx, l);
        File harness = extract(ctx, "poly/harness." + ext(l), new File(ctx.getFilesDir(), "poly"));
        if (h != 0) { nat.close(h); h = 0; }
        PolyEngine prev = current.get();
        current.set(this);
        try { h = n.open(d.getAbsolutePath(), new File(d, m).getAbsolutePath(), harness.getAbsolutePath()); }
        finally { current.set(prev); }
        nat = n;
        dir = d; mainFile = m; lang = l;
    }

    <T> T run(Callable<T> c) throws Exception {
        if (Thread.currentThread() == thread) return c.call();
        Future<T> f = exec.submit(c);
        try { return f.get(); }
        catch (java.util.concurrent.ExecutionException e) { Throwable t = e.getCause(); if (t instanceof Exception) throw (Exception) t; throw new Exception(t); }
    }

    void post(Runnable r) { if (!exec.isShutdown()) exec.execute(r); }

    String raw(String fn, String jsonArg) {
        if (h == 0) throw new IllegalStateException("engine closed");
        PolyEngine prev = current.get();
        current.set(this);
        try { return new String(nat.call(h, fn, jsonArg.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8); }
        finally { current.set(prev); }
    }

    public String call(final String fn, final String jsonArg) {
        try { return run(() -> raw(fn, jsonArg)); }
        catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new RuntimeException(e); }
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
        for (Object o : args) a.put(toJson(o));
        JSONObject r = new JSONObject(call(fn, a.toString()));
        if (r.has("error")) throw new ScriptError(r.optString("error"));
        return wrap(r.opt("value"));
    }

    public void invokeAsync(final String fn, final PolyResult cb, final Object... args) {
        post(() -> {
            Object v = null; Exception err = null;
            try { v = invoke(fn, args); } catch (Exception e) { err = e; }
            final Object fv = v; final Exception fe = err;
            if (cb != null) main.post(() -> cb.done(fv, fe));
        });
    }

    Object callback(long id, Object[] args, boolean sync) throws Exception {
        JSONArray a = new JSONArray().put(id);
        for (Object o : args) a.put(toJson(o));
        JSONObject r = new JSONObject(raw("__cb", a.toString()));
        if (r.has("error")) {
            if (sync) throw new ScriptError(r.optString("error"));
            emit("error", r.optString("error"));
            return null;
        }
        return wrap(r.opt("value"));
    }

    public void reload() throws Exception { run(() -> { load(); return null; }); }

    public void load(ScriptSource s) throws Exception { run(() -> { ScriptSource old = source; source = s; try { load(); } catch (Exception e) { source = old; throw e; } return null; }); }

    public boolean update() throws Exception {
        if (!source.isRemote()) return false;
        boolean changed = source.fetch(ctx);
        if (changed) reload();
        return changed;
    }

    public void updateAsync(final PolyResult cb) {
        new Thread(() -> {
            try { boolean c = update(); if (cb != null) main.post(() -> cb.done(c, null)); }
            catch (Exception e) { if (cb != null) main.post(() -> cb.done(false, e)); }
        }, "poly-update").start();
    }

    public PolyEngine expose(String name, Object target) { objects.put(name, target); return this; }
    public PolyEngine function(String name, PolyFunction fn) { functions.put(name, fn); return this; }
    public PolyEngine on(String event, PolyListener l) { listeners.computeIfAbsent(event, k -> new CopyOnWriteArrayList<>()).add(l); return this; }
    public File dir() { return dir; }

    void emit(final String name, final Object data) {
        List<PolyListener> ls = new ArrayList<>();
        if (listeners.get(name) != null) ls.addAll(listeners.get(name));
        if (listeners.get("*") != null) ls.addAll(listeners.get("*"));
        for (final PolyListener l : ls) main.post(() -> l.on(name, data));
    }

    @Override public void close() {
        try { run(() -> { if (h != 0) { nat.close(h); h = 0; } return null; }); } catch (Exception ignored) {}
        shutdown();
    }

    void shutdown() { exec.shutdown(); }

    // ---------------------------------------------------------------- script -> native
    static byte[] host(byte[] req) {
        JSONObject out = new JSONObject();
        try {
            JSONObject r = new JSONObject(new String(req, StandardCharsets.UTF_8));
            PolyEngine e = current.get();
            String op = r.optString("op");
            if (op.equals("log")) {
                Log.i("PolyScript", r.optString("text"));
                Handler hd = e != null && e.own != null ? e.own : handler;
                if (hd != null) hd.log(r.optString("text"));
                if (e != null) e.emit("log", r.optString("text"));
            } else if (op.equals("emit")) {
                if (e != null) e.emit(r.optString("name"), e.decode(r.opt("data")));
            } else {
                out.put("value", toJson(e == null ? legacy(null, r) : e.dispatch(r)));
            }
        } catch (Throwable t) {
            Throwable c = t instanceof InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            try { out.put("error", c.getMessage() == null ? c.toString() : c.getMessage()); } catch (Exception ignored) {}
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    Object dispatch(JSONObject r) throws Exception {
        String name = r.optString("plugin"), method = r.optString("method");
        Object rawArgs = r.opt("args");
        JSONArray args = rawArgs instanceof JSONArray ? (JSONArray) rawArgs : new JSONArray().put(rawArgs == null ? new JSONObject() : rawArgs);
        args = (JSONArray) decode(args);
        PolyFunction f = functions.get(name);
        if (f == null && method.isEmpty()) f = Poly.functions.get(name);
        if (f != null) return f.call(args);
        Object target = objects.get(name);
        if (target == null) target = Poly.objects.get(name);
        if (target != null) return reflect(target, method, args);
        PolyFunction gf = Poly.functions.get(name + "." + method);
        if (gf != null) return gf.call(args);
        JSONObject legacyArgs = args.length() == 1 && args.opt(0) instanceof JSONObject ? args.optJSONObject(0) : new JSONObject().put("args", args);
        Handler hd = own != null ? own : handler;
        if (hd == null) throw new Exception("nothing exposed as '" + name + "' (use Poly.expose or engine.expose)");
        return hd.call(name, method, legacyArgs);
    }

    static Object legacy(PolyEngine e, JSONObject r) throws Exception {
        if (handler == null) throw new Exception("no Android host attached");
        JSONObject a = r.optJSONObject("args");
        return handler.call(r.optString("plugin"), r.optString("method"), a == null ? new JSONObject() : a);
    }

    Object decode(Object v) throws Exception {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            if (o.length() == 1 && o.has("__volt_cb")) return new PolyCallback(this, o.getLong("__volt_cb"));
            JSONObject out = new JSONObject();
            Iterator<String> it = o.keys();
            while (it.hasNext()) { String k = it.next(); out.put(k, decode(o.get(k))); }
            return out;
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v, out = new JSONArray();
            for (int i = 0; i < a.length(); i++) out.put(decode(a.get(i)));
            return out;
        }
        return v;
    }

    // ---------------------------------------------------------------- reflection bridge
    static Object reflect(Object target, String method, JSONArray args) throws Exception {
        Class<?> cls = target instanceof Class ? (Class<?>) target : target.getClass();
        Object self = target instanceof Class ? null : target;
        Exception last = null;
        for (Method m : cls.getMethods()) {
            if (!m.getName().equals(method) || m.getParameterTypes().length != args.length()) continue;
            if (self == null && !Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] pt = m.getParameterTypes();
            Object[] conv = new Object[pt.length];
            try {
                for (int i = 0; i < pt.length; i++) conv[i] = convert(args.isNull(i) ? null : args.get(i), pt[i]);
            } catch (IllegalArgumentException e) { last = e; continue; }
            return m.invoke(self, conv);
        }
        if (last != null) throw new Exception(cls.getSimpleName() + "." + method + ": " + last.getMessage());
        throw new Exception(cls.getSimpleName() + " has no public method " + method + " taking " + args.length() + " argument(s)");
    }

    static Object convert(Object v, Class<?> t) throws Exception {
        if (v == null) { if (t.isPrimitive()) throw new IllegalArgumentException("null for " + t); return null; }
        if (t == Object.class || t.isInstance(v)) return v;
        if (v instanceof Number) {
            Number n = (Number) v;
            if (t == int.class || t == Integer.class) return n.intValue();
            if (t == long.class || t == Long.class) return n.longValue();
            if (t == double.class || t == Double.class) return n.doubleValue();
            if (t == float.class || t == Float.class) return n.floatValue();
            if (t == short.class || t == Short.class) return n.shortValue();
            if (t == byte.class || t == Byte.class) return n.byteValue();
        }
        if ((t == boolean.class || t == Boolean.class) && v instanceof Boolean) return v;
        if (t == String.class) return String.valueOf(v);
        if (t == char.class && v instanceof String && ((String) v).length() == 1) return ((String) v).charAt(0);
        if (Map.class.isAssignableFrom(t) && v instanceof JSONObject) return toMap((JSONObject) v);
        if ((List.class.isAssignableFrom(t) || t == Collection.class) && v instanceof JSONArray) return toList((JSONArray) v);
        if (t.isArray() && v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            Object arr = java.lang.reflect.Array.newInstance(t.getComponentType(), a.length());
            for (int i = 0; i < a.length(); i++) java.lang.reflect.Array.set(arr, i, convert(a.isNull(i) ? null : a.get(i), t.getComponentType()));
            return arr;
        }
        throw new IllegalArgumentException("cannot pass " + v.getClass().getSimpleName() + " as " + t.getSimpleName());
    }

    static Map<String, Object> toMap(JSONObject o) throws Exception {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        Iterator<String> it = o.keys();
        while (it.hasNext()) { String k = it.next(); m.put(k, unwrap(o.get(k))); }
        return m;
    }

    static List<Object> toList(JSONArray a) throws Exception {
        List<Object> l = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) l.add(unwrap(a.get(i)));
        return l;
    }

    static Object unwrap(Object v) throws Exception {
        if (v == JSONObject.NULL) return null;
        if (v instanceof JSONObject) return toMap((JSONObject) v);
        if (v instanceof JSONArray) return toList((JSONArray) v);
        return v;
    }

    static Object wrap(Object v) { return v == JSONObject.NULL ? null : v; }

    static Object toJson(Object v) {
        if (v == null) return JSONObject.NULL;
        if (v instanceof JSONObject || v instanceof JSONArray || v instanceof String || v instanceof Boolean || v instanceof Number) return v;
        if (v instanceof Character || v instanceof Enum) return v.toString();
        if (v instanceof Map) {
            JSONObject o = new JSONObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) try { o.put(String.valueOf(e.getKey()), toJson(e.getValue())); } catch (Exception ignored) {}
            return o;
        }
        if (v instanceof Collection) { JSONArray a = new JSONArray(); for (Object x : (Collection<?>) v) a.put(toJson(x)); return a; }
        if (v.getClass().isArray()) {
            JSONArray a = new JSONArray();
            for (int i = 0; i < java.lang.reflect.Array.getLength(v); i++) a.put(toJson(java.lang.reflect.Array.get(v, i)));
            return a;
        }
        if (v instanceof PolyCallback) return new JSONObject(java.util.Collections.singletonMap("__volt_cb", ((PolyCallback) v).id));
        return v.toString();
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

}
