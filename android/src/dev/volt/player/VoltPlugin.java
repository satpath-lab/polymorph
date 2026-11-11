package dev.volt.player;

import android.app.Activity;
import android.content.Intent;

import org.json.JSONObject;

/**
 * Native extension point. Put a class implementing this in apps/<app>/native/java or native/kotlin,
 * then call it from any script with: _native = {plugin: "ClassName", method: "...", args: {...}, id: "resultId"}
 */
public interface VoltPlugin {
    void call(Host host, String method, JSONObject args, Result result) throws Exception;

    interface Result {
        void ok(Object value);
        void error(String message);
    }

    interface Host {
        Activity activity();
        String app();
        void requestPermissions(String[] permissions, PermissionCallback cb);
        void startForResult(Intent intent, ActivityCallback cb);
        void emit(String id, Object value);
        void runOnUi(Runnable r);
        void runInBackground(Runnable r);
    }

    interface PermissionCallback { void done(boolean granted); }

    interface ActivityCallback { void done(int resultCode, Intent data); }
}
