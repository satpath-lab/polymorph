package com.example.nativedemo;

import android.content.Context;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;

import org.json.JSONObject;

import dev.polymorph.app.PolyPlugin;

public class Torch implements PolyPlugin {
    @Override public void call(Host host, String method, JSONObject args, Result result) throws Exception {
        CameraManager cm = (CameraManager) host.activity().getSystemService(Context.CAMERA_SERVICE);
        String torchId = null;
        for (String id : cm.getCameraIdList()) {
            Boolean flash = cm.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            if (Boolean.TRUE.equals(flash)) { torchId = id; break; }
        }
        if (torchId == null) { result.error("this device has no flashlight"); return; }
        boolean on = method.equals("on") || (method.equals("set") && args.optBoolean("on"));
        cm.setTorchMode(torchId, on);
        result.ok(new JSONObject().put("on", on));
    }
}
