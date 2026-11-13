package dev.volt.player;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class VoltApp {
    static final String[] EFFECTS = {"toast", "open", "vibrate", "camera", "pick", "location", "notify", "share", "clipboard", "contact", "store", "native", "background", "pickDate", "pickTime", "finish"};
    static final String[] MAINS = {"main.lua", "main.js", "main.py", "main.rb", "main.php"};
    private static JSONObject cfg;

    static synchronized JSONObject cfg(Context c) {
        if (cfg != null) return cfg;
        try { cfg = new JSONObject(MainActivity.readAll(c.getAssets().open("volt.json"))); } catch (Exception e) { cfg = new JSONObject(); }
        return cfg;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("volt", Context.MODE_PRIVATE); }

    static File root(Context c) { File f = new File(c.getFilesDir(), "app"); f.mkdirs(); return f; }

    static File scripts(Context c, String source) throws Exception {
        if ("dev".equals(source)) {
            File d = new File(root(c), "dev");
            if (mainFile(d) != null) return d;
            throw new Exception("No code synced from the dev server yet");
        }
        if ("remote".equals(source)) {
            String cur = prefs(c).getString("remote:dir", null);
            if (cur != null) { File d = new File(root(c), cur); if (mainFile(d) != null) return d; }
        }
        return bundled(c);
    }

    static File bundled(Context c) throws Exception {
        long stamp = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        File d = new File(root(c), "b" + stamp);
        if (new File(d, ".ok").exists()) return d;
        File[] old = root(c).listFiles();
        if (old != null) for (File o : old) if (o.getName().startsWith("b")) delete(o);
        copyAssets(c.getAssets(), "app", d);
        new File(d, ".ok").createNewFile();
        return d;
    }

    static void copyAssets(AssetManager am, String path, File to) throws Exception {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            to.getParentFile().mkdirs();
            try (InputStream in = am.open(path); OutputStream os = new FileOutputStream(to)) { copy(in, os); }
            return;
        }
        to.mkdirs();
        for (String k : kids) copyAssets(am, path + "/" + k, new File(to, k));
    }

    static void copy(InputStream in, OutputStream os) throws Exception { byte[] b = new byte[65536]; int n; while ((n = in.read(b)) > 0) os.write(b, 0, n); }

    static String mainFile(File d) {
        if (d == null || !d.isDirectory()) return null;
        for (String m : MAINS) if (new File(d, m).exists()) return m;
        return null;
    }

    static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }

    static void unzip(byte[] zip, File dir) throws Exception {
        delete(dir); dir.mkdirs();
        String base = dir.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = z.getNextEntry()) != null) {
                File f = new File(dir, e.getName());
                if (!f.getCanonicalPath().startsWith(base)) throw new SecurityException("bad path in bundle: " + e.getName());
                if (e.isDirectory()) { f.mkdirs(); continue; }
                f.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(f)) { copy(z, os); }
            }
        }
    }

    static byte[] get(String url, String token, String[] etag) throws Exception {
        HttpURLConnection h = VoltSecure.open(url);
        h.setConnectTimeout(8000); h.setReadTimeout(30000);
        if (token != null && !token.isEmpty()) h.setRequestProperty("X-Volt-Token", token);
        if (etag != null && etag[0] != null) h.setRequestProperty("If-None-Match", etag[0]);
        int code = h.getResponseCode();
        if (code == 304) return null;
        if (code >= 400) throw new Exception("HTTP " + code + " for " + url);
        if (etag != null) etag[0] = h.getHeaderField("ETag");
        return MainActivity.readBytes(h.getInputStream());
    }

    static synchronized boolean updateRemote(Context c) throws Exception {
        JSONObject k = cfg(c);
        String url = k.optString("url");
        if (url.isEmpty()) return false;
        SharedPreferences p = prefs(c);
        String[] etag = {p.getString("remote:etag", null)};
        if (mainFile(new File(root(c), p.getString("remote:dir", "-"))) == null) etag[0] = null;
        byte[] zip = get(url, null, etag);
        if (zip == null) return false;
        String key = k.optString("publicKey");
        if (!key.isEmpty()) {
            byte[] sig = get(url + ".sig", null, null);
            PublicKey pk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk); s.update(zip);
            if (!s.verify(Base64.decode(new String(sig, "UTF-8").trim(), Base64.DEFAULT))) throw new SecurityException("script bundle signature is invalid; update rejected");
        }
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        String name = "r" + Base64.encodeToString(md.digest(zip), Base64.NO_WRAP | Base64.URL_SAFE).substring(0, 12).replace("-", "_");
        File d = new File(root(c), name);
        unzip(zip, d);
        if (mainFile(d) == null) { delete(d); throw new Exception("bundle has no main script"); }
        String prev = p.getString("remote:dir", null);
        p.edit().putString("remote:dir", name).putString("remote:etag", etag[0]).putLong("remote:at", System.currentTimeMillis()).apply();
        if (prev != null && !prev.equals(name)) delete(new File(root(c), prev));
        return true;
    }

    static Object loadState(Context c, String app) {
        String s = VoltSecure.dec(prefs(c).getString("state:" + app, null), null);
        if (s == null) return null;
        try { return new JSONObject(s); } catch (Exception e) { try { return new JSONArray(s); } catch (Exception e2) { return null; } }
    }

    static void saveState(Context c, String app, Object st) {
        prefs(c).edit().putString("state:" + app, st == null ? null : VoltSecure.enc(st.toString())).apply();
    }

    static JSONObject split(Object st) {
        JSONObject fx = new JSONObject();
        if (!(st instanceof JSONObject)) return fx;
        JSONObject o = (JSONObject) st;
        for (String e : EFFECTS) {
            if (o.has("_" + e)) { try { fx.put(e, o.get("_" + e)); } catch (Exception ignored) {} o.remove("_" + e); }
        }
        return fx;
    }

    static JSONObject store(Context c, String app) {
        try { return new JSONObject(VoltSecure.dec(prefs(c).getString("store:" + app, null), "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    static void putStore(Context c, String app, JSONObject up) throws Exception {
        JSONObject cur = store(c, app);
        Iterator<String> it = up.keys();
        while (it.hasNext()) { String k = it.next(); if (up.isNull(k)) cur.remove(k); else cur.put(k, up.get(k)); }
        prefs(c).edit().putString("store:" + app, VoltSecure.enc(cur.toString())).apply();
    }
}
