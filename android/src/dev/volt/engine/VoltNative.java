package dev.volt.engine;

import android.content.Context;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

final class VoltNative {
    static final Map<String, VoltNative> byLang = new HashMap<>();
    final Method open, call, close;

    private VoltNative(Class<?> c) throws Exception {
        open = c.getMethod("nOpen", String.class, String.class, String.class);
        call = c.getMethod("nCall", long.class, String.class, byte[].class);
        close = c.getMethod("nClose", long.class);
    }

    static synchronized VoltNative get(Context ctx, String lang) throws Exception {
        VoltNative n = byLang.get(lang);
        if (n != null) return n;
        File dex = new File(ctx.getCodeCacheDir(), "volt-stub-" + ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).lastUpdateTime + ".dex");
        if (!dex.exists()) {
            File[] old = ctx.getCodeCacheDir().listFiles();
            if (old != null) for (File f : old) if (f.getName().startsWith("volt-stub-")) f.delete();
            VoltEngine.extract(ctx, "volt/stub.dex", ctx.getCodeCacheDir()).renameTo(dex);
            dex.setReadOnly();
        }
        String lib = "volt_" + lang;
        String found = ((dalvik.system.BaseDexClassLoader) ctx.getClassLoader()).findLibrary(lib);
        if (found == null) throw new Exception("the " + lang + " engine (lib" + lib + ".so) is not in this app");
        String search = found.substring(0, found.lastIndexOf('/'));
        ClassLoader cl = new dalvik.system.DexClassLoader(dex.getPath(), ctx.getCodeCacheDir().getPath(), search, Object.class.getClassLoader());
        Class<?> c = cl.loadClass("dev.volt.engine.VoltEngine");
        c.getField("sink").set(null, (java.util.function.Function<byte[], byte[]>) VoltEngine::host);
        try { c.getMethod("load", String.class).invoke(null, lib); }
        catch (InvocationTargetException e) { throw new Exception("could not load " + lib + ": " + e.getCause()); }
        n = new VoltNative(c);
        byLang.put(lang, n);
        return n;
    }

    long open(String d, String m, String h) throws Exception {
        try { return (Long) open.invoke(null, d, m, h); }
        catch (InvocationTargetException e) { Throwable t = e.getCause(); throw t instanceof Exception ? (Exception) t : new Exception(t); }
    }

    byte[] call(long h, String fn, byte[] a) {
        try { return (byte[]) call.invoke(null, h, fn, a); }
        catch (InvocationTargetException e) { Throwable t = e.getCause(); throw t instanceof RuntimeException ? (RuntimeException) t : new RuntimeException(t); }
        catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }

    void close(long h) { try { close.invoke(null, h); } catch (Exception ignored) {} }
}
