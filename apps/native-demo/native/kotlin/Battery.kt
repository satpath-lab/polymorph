package com.example.nativedemo

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import dev.volt.player.VoltPlugin
import org.json.JSONObject

class Battery : VoltPlugin {
    override fun call(host: VoltPlugin.Host, method: String, args: JSONObject, result: VoltPlugin.Result) {
        val ctx = host.activity()
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = (status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val tempTenths = status?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        result.ok(JSONObject()
            .put("percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", plugged)
            .put("celsius", tempTenths / 10.0))
    }
}
