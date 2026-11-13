package com.example.nativedemo;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

import dev.polymorph.app.PolyPlugin;

/** Scans for Bluetooth LE devices for args.seconds (default 4) and returns [{name, address, rssi}]. */
public class Bluetooth implements PolyPlugin {
    @Override public void call(final Host host, String method, final JSONObject args, final Result result) {
        String[] perms = Build.VERSION.SDK_INT >= 31
                ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
        host.requestPermissions(perms, granted -> {
            if (!granted) { result.error("denied"); return; }
            BluetoothManager bm = (BluetoothManager) host.activity().getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
            if (ad == null) { result.error("this device has no Bluetooth"); return; }
            if (!ad.isEnabled()) { result.error("Bluetooth is turned off"); return; }
            final BluetoothLeScanner sc = ad.getBluetoothLeScanner();
            final Map<String, JSONObject> found = new LinkedHashMap<>();
            final ScanCallback cb = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult r) {
                    try {
                        String name = r.getDevice().getName();
                        found.put(r.getDevice().getAddress(), new JSONObject().put("name", name == null ? "(unnamed)" : name)
                                .put("address", r.getDevice().getAddress()).put("rssi", r.getRssi()));
                    } catch (Exception ignored) {}
                }
            };
            try { sc.startScan(cb); } catch (SecurityException e) { result.error("denied"); return; }
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try { sc.stopScan(cb); } catch (SecurityException ignored) {}
                result.ok(new JSONArray(found.values()));
            }, args.optInt("seconds", 4) * 1000L);
        });
    }
}
