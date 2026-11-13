package com.example.nativedemo;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import org.json.JSONObject;

import dev.polymorph.app.PolyPlugin;

/** Streams accelerometer readings to the script as {type:"native", id:"motion"} events, ~4 per second. */
public class Sensors implements PolyPlugin, SensorEventListener {
    private SensorManager sm;
    private Host host;
    private long last;

    @Override public void call(Host host, String method, JSONObject args, Result result) {
        this.host = host;
        sm = (SensorManager) host.activity().getSystemService(Context.SENSOR_SERVICE);
        if (method.equals("start")) {
            Sensor acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (acc == null) { result.error("no accelerometer"); return; }
            sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_UI);
            result.ok("started");
        } else {
            sm.unregisterListener(this);
            result.ok("stopped");
        }
    }

    @Override public void onSensorChanged(SensorEvent e) {
        long now = System.currentTimeMillis();
        if (now - last < 250) return;
        last = now;
        try {
            host.emit("motion", new JSONObject().put("x", Math.round(e.values[0] * 100) / 100.0)
                    .put("y", Math.round(e.values[1] * 100) / 100.0).put("z", Math.round(e.values[2] * 100) / 100.0));
        } catch (Exception ignored) {}
    }

    @Override public void onAccuracyChanged(Sensor s, int a) {}
}
