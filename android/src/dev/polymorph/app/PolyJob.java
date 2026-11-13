package dev.polymorph.app;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;

import dev.polymorph.sdk.PolyEngine;

public class PolyJob extends JobService {
    static final int JOB_ID = 4711;

    @Override public boolean onStartJob(final JobParameters p) {
        new Thread(() -> {
            try { run(getApplicationContext()); } catch (Exception ignored) {}
            jobFinished(p, false);
        }, "poly-job").start();
        return true;
    }

    @Override public boolean onStopJob(JobParameters p) { return true; }

    static void schedule(Context c, int minutes) {
        JobScheduler js = (JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE);
        js.schedule(new JobInfo.Builder(JOB_ID, new ComponentName(c, PolyJob.class))
                .setPeriodic(Math.max(15, minutes) * 60000L)
                                .setPersisted(true).build());
    }

    static void cancel(Context c) {
        ((JobScheduler) c.getSystemService(Context.JOB_SCHEDULER_SERVICE)).cancel(JOB_ID);
    }

    static void run(Context c) throws Exception {
        PolySecure.init(c);
        SharedPreferences sp = c.getSharedPreferences("poly", Context.MODE_PRIVATE);
        final String app = sp.getString("bg:app", "");
        if (app.isEmpty() || !sp.getBoolean("bg:on", false)) return;
        String source = sp.getString("bg:source", "bundled");
        if ("remote".equals(source)) try { PolyApp.updateRemote(c); } catch (Exception ignored) {}
        final JSONObject ev = new JSONObject().put("type", "background").put("id", "background").put("inputs", new JSONObject())
                .put("store", PolyApp.store(c, app)).put("device", new JSONObject().put("platform", "android").put("background", true));
        final MainActivity act = MainActivity.alive;
        if (act != null && app.equals(act.app)) { act.main.post(() -> act.send(ev)); return; }
        File dir = PolyApp.scripts(c, source);
        String main = PolyApp.mainFile(dir);
        if (main == null) return;
        try (PolyEngine e = PolyEngine.open(c, dir, main)) {
            JSONObject r = e.step(PolyApp.loadState(c, app), ev);
            Object st = r.opt("state");
            JSONObject fx = PolyApp.split(st);
            PolyApp.saveState(c, app, st);
            Object n = fx.opt("notify");
            if (n != null) {
                JSONArray arr = n instanceof JSONArray ? (JSONArray) n : new JSONArray().put(n instanceof JSONObject ? n : new JSONObject().put("text", String.valueOf(n)));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject m = arr.optJSONObject(i); if (m == null) continue;
                    if (m.optDouble("at", 0) > 0) PolyAlarm.schedule(c, m.optString("title", app), m.optString("text"), m.optString("tag"), (long) (m.optDouble("at") * 1000));
                    else PolyAlarm.post(c, m.optString("title", app), m.optString("text"), m.optString("tag"));
                }
            }
            JSONObject up = fx.optJSONObject("store");
            if (up != null) PolyApp.putStore(c, app, up);
        }
    }
}
