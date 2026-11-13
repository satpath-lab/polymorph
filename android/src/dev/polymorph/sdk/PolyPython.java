package dev.polymorph.sdk;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class PolyPython {
    static boolean ready;

    static synchronized void prepare(Context ctx) throws Exception {
        if (ready) return;
        File home = new File(ctx.getFilesDir(), "python");
        PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
        String stamp = String.valueOf(pi.lastUpdateTime);
        File mark = new File(home, ".stamp");
        String abi = Build.SUPPORTED_ABIS[0];
        if (!mark.exists() || !stamp.equals(new String(java.nio.file.Files.readAllBytes(mark.toPath())))) {
            deleteTree(home);
            home.mkdirs();
            try (ZipInputStream z = new ZipInputStream(ctx.getAssets().open("poly/python.zip"))) {
                ZipEntry e; byte[] b = new byte[65536];
                while ((e = z.getNextEntry()) != null) {
                    String name = e.getName();
                    if (name.startsWith("dynload/")) {
                        if (!name.startsWith("dynload/" + abi + "/")) continue;
                        name = "lib/python3.14/lib-dynload/" + name.substring(("dynload/" + abi + "/").length());
                    }
                    File f = new File(home, name);
                    if (!f.getCanonicalPath().startsWith(home.getCanonicalPath())) continue;
                    if (e.isDirectory()) { f.mkdirs(); continue; }
                    f.getParentFile().mkdirs();
                    try (OutputStream os = new FileOutputStream(f)) { int n; while ((n = z.read(b)) > 0) os.write(b, 0, n); }
                }
            }
            try (OutputStream os = new FileOutputStream(mark)) { os.write(stamp.getBytes()); }
        }
        android.system.Os.setenv("VOLT_PYHOME", home.getAbsolutePath(), true);
        android.system.Os.setenv("HOME", ctx.getFilesDir().getAbsolutePath(), true);
        android.system.Os.setenv("TMPDIR", ctx.getCacheDir().getAbsolutePath(), true);
        ready = true;
    }

    static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }
}
