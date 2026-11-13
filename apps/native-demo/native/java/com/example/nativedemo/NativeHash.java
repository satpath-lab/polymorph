package com.example.nativedemo;

import org.json.JSONObject;

import dev.polymorph.app.PolyPlugin;

/** Bridges to C code in native/c/fasthash.c via JNI. */
public class NativeHash implements PolyPlugin {
    private static boolean loaded;
    private static String loadError;

    static {
        try { System.loadLibrary("fasthash"); loaded = true; }
        catch (UnsatisfiedLinkError e) { loadError = "C library not in this APK (install the Android NDK and rebuild)"; }
    }

    private static native long fnv1a(String text, int rounds);

    @Override public void call(Host host, String method, JSONObject args, Result result) throws Exception {
        if (!loaded) { result.error(loadError); return; }
        long t0 = System.nanoTime();
        long h = fnv1a(args.optString("text", ""), args.optInt("rounds", 1));
        result.ok(new JSONObject().put("hash", Long.toHexString(h)).put("micros", (System.nanoTime() - t0) / 1000));
    }
}
