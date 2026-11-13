package dev.volt.engine;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class Volt {
    static final Map<String, Object> objects = new ConcurrentHashMap<>();
    static final Map<String, VoltFunction> functions = new ConcurrentHashMap<>();

    private Volt() {}

    public static void expose(String name, Object target) { objects.put(name, target); }

    public static void function(String name, VoltFunction fn) { functions.put(name, fn); }

    public static void remove(String name) { objects.remove(name); functions.remove(name); }

    public static VoltEngine.Builder engine(Context context) { return new VoltEngine.Builder(context); }

    public static void openScreen(Activity from, ScriptSource source, int requestCode) {
        Intent i = new Intent();
        i.setClassName(from, "dev.volt.player.VoltActivity");
        source.toIntent(i);
        if (requestCode >= 0) from.startActivityForResult(i, requestCode); else from.startActivity(i);
    }
}
