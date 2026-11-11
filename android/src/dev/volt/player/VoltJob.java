package dev.volt.player;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Iterator;

public class VoltJob extends JobService {
    static final int JOB_ID = 4711;

    @Override public boolean onStartJob(final JobParameters p) {
        new Thread(() -> {
            try { run(getApplicationContext()); } catch (Exception ignored) {}
            jobFinished(p, false);
        }, "volt-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return true; }

    static void schedule(Context c, int minutes) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, VoltJob.class))
                .setPeriodic(Math.max(15, minutes) * 60000L)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true).build());
    }

    static void cancel(Context c) {
        ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(JOB_ID);
    }

    static void run(Context c) throws Exception {
        SharedPreferences sp = c.getSharedPreferences("volt", Context.MODE_PRIVATE);
        String app = sp.getString("bg:app", "");
        if (app.isEmpty()) return;
        if (sp.getBoolean("bg:on", false)) {
            JSONObject ev = new JSONObject().put("type", "background").put("id", "background").put("inputs", new JSONObject())
                    .put("store", new JSONObject(sp.getString("store:" + app, "{}")))
                    .put("device", new JSONObject().put("platform", "android").put("background", true));
            JSONObject body = new JSONObject().put("session", sp.getString("sid", "")).put("event", ev);
            JSONObject r = http(sp, "POST", "/api/run/" + app + "?ch=" + sp.getString("bg:channel", "prod"), body.toString());
            JSONObject fx = r.optJSONObject("fx");
            if (fx != null) {
                Object n = fx.opt("notify");
                if (n != null) {
                    JSONArray arr = n instanceof JSONArray ? (JSONArray) n : new JSONArray().put(n instanceof JSONObject ? n : new JSONObject().put("text", String.valueOf(n)));
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject m = arr.optJSONObject(i); if (m == null) continue;
                        if (m.optDouble("at", 0) > 0) VoltAlarm.schedule(c, m.optString("title", app), m.optString("text"), m.optString("tag"), (long) (m.optDouble("at") * 1000));
                        else VoltAlarm.post(c, m.optString("title", app), m.optString("text"), m.optString("tag"));
                    }
                }
                JSONObject up = fx.optJSONObject("store");
                if (up != null) {
                    JSONObject cur = new JSONObject(sp.getString("store:" + app, "{}"));
                    Iterator<String> it = up.keys();
                    while (it.hasNext()) { String k = it.next(); if (up.isNull(k)) cur.remove(k); else cur.put(k, up.get(k)); }
                    sp.edit().putString("store:" + app, cur.toString()).apply();
                }
            }
        }
        inbox(c);
    }

    static void inbox(Context c) throws Exception {
        SharedPreferences sp = c.getSharedPreferences("volt", Context.MODE_PRIVATE);
        String app = sp.getString("bg:app", ""), ch = sp.getString("bg:channel", "prod");
        if (app.isEmpty()) return;
        String key = "inbox:" + app + ":" + ch;
        long since = sp.getLong(key, 0);
        JSONObject r = http(sp, "GET", "/api/inbox/" + app + "?ch=" + ch + "&session=" + sp.getString("sid", "") + "&since=" + since, null);
        JSONArray msgs = r.optJSONArray("messages");
        if (msgs != null) for (int i = 0; i < msgs.length(); i++) {
            JSONObject m = msgs.getJSONObject(i);
            VoltAlarm.post(c, m.optString("title", app), m.optString("text"), m.optString("tag"));
        }
        sp.edit().putLong(key, r.optLong("cursor", since)).apply();
    }

    static JSONObject http(SharedPreferences sp, String method, String path, String body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(sp.getString("bg:server", "") + path).openConnection();
        c.setRequestMethod(method); c.setConnectTimeout(8000); c.setReadTimeout(20000);
        String tok = sp.getString("bg:token", "");
        if (!tok.isEmpty()) c.setRequestProperty("X-Volt-Token", tok);
        if (body != null) {
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
            byte[] b = body.getBytes("UTF-8"); c.setFixedLengthStreamingMode(b.length);
            OutputStream os = c.getOutputStream(); os.write(b); os.close();
        }
        InputStream in = c.getResponseCode() < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return new JSONObject(bo.toString("UTF-8"));
    }
}
