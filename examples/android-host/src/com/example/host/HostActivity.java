package com.example.host;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;

import dev.polymorph.sdk.ScriptSource;
import dev.polymorph.sdk.Poly;
import dev.polymorph.sdk.PolyCallback;
import dev.polymorph.sdk.PolyEngine;

public class HostActivity extends Activity {
    PolyEngine pricing;
    TextView out;
    final Handler main = new Handler(Looper.getMainLooper());

    public static class Tax {
        public double rate(String country) { return "DE".equals(country) ? 0.19 : "IN".equals(country) ? 0.18 : 0.0; }
    }

    public class Clock {
        public void every(int ms, int times, final PolyCallback cb) {
            for (int i = 1; i <= times; i++) { final int n = i; main.postDelayed(() -> cb.call(n), (long) ms * i); }
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(40, 40, 40, 40);
        TextView title = new TextView(this);
        title.setText("Host app (plain Java)");
        title.setTextSize(22); title.setTypeface(Typeface.DEFAULT_BOLD);
        ll.addView(title);
        out = new TextView(this);
        out.setTextSize(15); out.setTypeface(Typeface.MONOSPACE); out.setPadding(0, 24, 0, 24);
        ll.addView(button("1. Python: price(100, DE)", () -> pricing.invokeAsync("price", (v, e) -> log("price = " + (e != null ? e.getMessage() : v)), 100, "DE")));
        ll.addView(button("2. Python callback + events", () -> pricing.invokeAsync("start_ticks", (v, e) -> log("start_ticks -> " + (e != null ? e.getMessage() : v)))));
        ll.addView(button("3. Swap in new JS script (string)", () -> new Thread(() -> {
            try {
                pricing.load(ScriptSource.code("main.js", "function price(a, c) { return 'JS: ' + (a * (1 + android.Tax.rate(c)) * 0.9).toFixed(2) + ' (10% off)'; }"));
                log("loaded " + pricing.lang + " script");
            } catch (Exception e) { log("load failed: " + e); }
        }).start()));
        ll.addView(button("4. Load signed script from server URL", () -> new Thread(() -> {
            try {
                pricing.load(ScriptSource.url("http://10.0.2.2:8787/b/pricing-lua.zip", getString(getResources().getIdentifier("poly_key", "string", getPackageName()))));
                log("loaded " + pricing.lang + " script from URL");
            } catch (Exception e) { log("URL load failed: " + e.getMessage()); }
        }).start()));
        ll.addView(button("5. Open script screen (Lua UI)", () -> Poly.openScreen(this, ScriptSource.asset("scripts/checkout"), 7)));
        ll.addView(out);
        ScrollView sv = new ScrollView(this); sv.addView(ll);
        setContentView(sv);

        Poly.expose("Tax", new Tax());
        Poly.function("hostVersion", args -> "host 1.0");
        Poly.engine(this)
            .source(ScriptSource.asset("scripts/pricing"))
            .expose("Clock", new Clock())
            .on("tick", (name, data) -> log("event tick: " + data))
            .on("error", (name, data) -> log("script error: " + data))
            .openAsync((engine, err) -> {
                if (err != null) { log("engine failed: " + err); return; }
                pricing = (PolyEngine) engine;
                log("engine ready: " + pricing.lang);
            });
    }

    Button button(String text, Runnable r) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(v -> r.run()); return b;
    }

    void log(final String s) { main.post(() -> out.append(s + "\n")); }

    @Override protected void onActivityResult(int rc, int res, Intent data) {
        if (rc == 7) log("screen result: " + (data == null ? "cancelled" : data.getStringExtra("poly.result")));
    }
}
