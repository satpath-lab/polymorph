package dev.polymorph.sdk;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ScriptSource {
    static final String[] MAINS = {"main.lua", "main.js", "main.py", "main.rb", "main.php"};

    final String kind, value, key;
    String main;
    Map<String, String> headers;

    private ScriptSource(String kind, String value, String key) { this.kind = kind; this.value = value; this.key = key; }

    public static ScriptSource asset(String assetDir) { return new ScriptSource("asset", assetDir.replaceAll("/+$", ""), null); }
    public static ScriptSource dir(File dir) { return new ScriptSource("dir", dir.getAbsolutePath(), null); }
    public static ScriptSource code(String fileName, String code) { ScriptSource s = new ScriptSource("code", code, fileName); s.main = fileName; return s; }
    public static ScriptSource url(String zipUrl, String publicKeyBase64) { return new ScriptSource("url", zipUrl, publicKeyBase64); }

    public ScriptSource main(String fileName) { this.main = fileName; return this; }
    public ScriptSource headers(Map<String, String> h) { this.headers = h; return this; }
    public boolean isRemote() { return kind.equals("url"); }

    String id() { return kind + "-" + hash((kind + "\n" + value + "\n" + key).getBytes()); }

    void toIntent(Intent i) {
        i.putExtra("poly.kind", kind).putExtra("poly.value", value).putExtra("poly.key", key).putExtra("poly.main", main);
    }

    public static ScriptSource fromIntent(Intent i) {
        String k = i.getStringExtra("poly.kind");
        if (k == null) return null;
        ScriptSource s = new ScriptSource(k, i.getStringExtra("poly.value"), i.getStringExtra("poly.key"));
        s.main = i.getStringExtra("poly.main");
        return s;
    }

    File root(Context c) { File f = new File(new File(c.getFilesDir(), "poly-src"), id()); f.mkdirs(); return f; }

    File resolve(Context c) throws Exception {
        File r = root(c);
        switch (kind) {
            case "dir": return new File(value);
            case "code": {
                File f = new File(r, key);
                write(f, value.getBytes("UTF-8"));
                return r;
            }
            case "asset": {
                long stamp = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
                File d = new File(r, "v" + stamp);
                if (!new File(d, ".ok").exists()) {
                    delete(r); r.mkdirs();
                    copyAssets(c.getAssets(), value, d);
                    new File(d, ".ok").createNewFile();
                }
                return d;
            }
            default: {
                String cur = prefs(c).getString(id() + ":dir", null);
                File d = cur == null ? null : new File(r, cur);
                if (d != null && mainIn(d) != null) return d;
                fetch(c);
                return new File(r, prefs(c).getString(id() + ":dir", "-"));
            }
        }
    }

    String mainFile(File dir) throws Exception {
        if (main != null) return main;
        String m = mainIn(dir);
        if (m == null) throw new Exception("no main.lua / main.js / main.py / main.rb / main.php in " + dir);
        return m;
    }

    static String mainIn(File d) {
        if (d == null || !d.isDirectory()) return null;
        for (String m : MAINS) if (new File(d, m).exists()) return m;
        return null;
    }

    public synchronized boolean fetch(Context c) throws Exception {
        if (!isRemote()) return false;
        SharedPreferences p = prefs(c);
        String id = id();
        File r = root(c);
        boolean have = mainIn(new File(r, p.getString(id + ":dir", "-"))) != null;
        HttpURLConnection h = open(value);
        String etag = have ? p.getString(id + ":etag", null) : null;
        if (etag != null) h.setRequestProperty("If-None-Match", etag);
        int code = h.getResponseCode();
        if (code == 304) return false;
        if (code >= 400) throw new Exception("HTTP " + code + " for " + value);
        byte[] zip = read(h.getInputStream());
        if (key != null && !key.isEmpty()) {
            HttpURLConnection hs = open(value + ".sig");
            if (hs.getResponseCode() >= 400) throw new SecurityException("signature missing for " + value);
            byte[] sig = Base64.decode(new String(read(hs.getInputStream()), "UTF-8").trim(), Base64.DEFAULT);
            PublicKey pk = KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(key, Base64.DEFAULT)));
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(pk); s.update(zip);
            if (!s.verify(sig)) throw new SecurityException("script bundle signature is invalid; update rejected");
        }
        String name = "r" + hash(zip);
        if (name.equals(p.getString(id + ":dir", null))) return false;
        File d = new File(r, name);
        unzip(zip, d);
        if (main == null && mainIn(d) == null) { delete(d); throw new Exception("bundle has no main script"); }
        String prev = p.getString(id + ":dir", null);
        p.edit().putString(id + ":dir", name).putString(id + ":etag", h.getHeaderField("ETag")).apply();
        if (prev != null) delete(new File(r, prev));
        return true;
    }

    HttpURLConnection open(String u) throws Exception {
        HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
        h.setConnectTimeout(10000); h.setReadTimeout(30000);
        if (headers != null) for (Map.Entry<String, String> e : headers.entrySet()) h.setRequestProperty(e.getKey(), e.getValue());
        return h;
    }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("poly-src", Context.MODE_PRIVATE); }

    static String hash(byte[] b) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
            return Base64.encodeToString(d, Base64.NO_WRAP | Base64.URL_SAFE | Base64.NO_PADDING).substring(0, 12).replace('-', '_');
        } catch (Exception e) { return String.valueOf(java.util.Arrays.hashCode(b)); }
    }

    static byte[] read(InputStream in) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
        in.close();
        return bo.toByteArray();
    }

    static void write(File f, byte[] b) throws Exception {
        f.getParentFile().mkdirs();
        try (OutputStream os = new FileOutputStream(f)) { os.write(b); }
    }

    static void copyAssets(AssetManager am, String path, File to) throws Exception {
        String[] kids = am.list(path);
        if (kids == null || kids.length == 0) {
            try (InputStream in = am.open(path)) { write(to, read(in)); }
            return;
        }
        to.mkdirs();
        for (String k : kids) copyAssets(am, path + "/" + k, new File(to, k));
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
                ByteArrayOutputStream bo = new ByteArrayOutputStream(); byte[] b = new byte[65536]; int n;
                while ((n = z.read(b)) > 0) bo.write(b, 0, n);
                write(f, bo.toByteArray());
            }
        }
    }

    static void delete(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
