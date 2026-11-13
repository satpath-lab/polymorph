package dev.polymorph.sdk;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Poly {
    static final Map<String, Object> objects = new ConcurrentHashMap<>();
    static final Map<String, PolyFunction> functions = new ConcurrentHashMap<>();

    private Poly() {}

    public static void expose(String name, Object target) { objects.put(name, target); }

    public static void function(String name, PolyFunction fn) { functions.put(name, fn); }

    public static void remove(String name) { objects.remove(name); functions.remove(name); }

    public static PolyEngine.Builder engine(Context context) { return new PolyEngine.Builder(context); }

    public static void openScreen(Activity from, ScriptSource source, int requestCode) {
        Intent i = new Intent();
        i.setClassName(from, "dev.polymorph.app.PolyActivity");
        source.toIntent(i);
        if (requestCode >= 0) from.startActivityForResult(i, requestCode); else from.startActivity(i);
    }
}
