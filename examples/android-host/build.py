#!/usr/bin/env python3
"""Builds the example host app against voltengine.aar without Gradle (Android Studio users just add the .aar)."""
import os, sys, glob, shutil, tempfile, zipfile
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", ".."))
import volt

sdk = volt.sdk_paths()
aar = volt.build_aar(["lua", "js", "python"])
out = os.path.join(volt.BUILD, "android-host.apk")
with tempfile.TemporaryDirectory() as t:
    zipfile.ZipFile(aar).extractall(os.path.join(t, "aar"))
    res = os.path.join(t, "res"); shutil.copytree(os.path.join(t, "aar", "res"), res)
    os.makedirs(os.path.join(res, "values"), exist_ok=True)
    open(os.path.join(res, "values", "volt_key.xml"), "w").write('<resources><string name="volt_key">%s</string></resources>' % volt.bundle_pubkey())
    assets = os.path.join(t, "assets"); shutil.copytree(os.path.join(t, "aar", "assets"), assets)
    shutil.copytree(os.path.join(HERE, "assets"), assets, dirs_exist_ok=True)
    volt.sh([sdk["aapt2"], "compile", "--dir", res, "-o", os.path.join(t, "res.zip")])
    un = os.path.join(t, "un.apk")
    volt.sh([sdk["aapt2"], "link", "-o", un, "-I", sdk["jar"], "--manifest", os.path.join(HERE, "AndroidManifest.xml"), "--min-sdk-version", "24",
             "--target-sdk-version", "35", "--version-code", "1", "--version-name", "1.0", "-A", assets, os.path.join(t, "res.zip")])
    cls = os.path.join(t, "classes"); jar = os.path.join(t, "aar", "classes.jar")
    srcs = glob.glob(os.path.join(HERE, "src", "**", "*.java"), recursive=True)
    volt.sh(["javac", "-nowarn", "-source", "8", "-target", "8", "-Xlint:-options", "-bootclasspath", sdk["jar"] + os.pathsep + sdk["lambda"], "-cp", jar, "-d", cls] + srcs)
    dex = os.path.join(t, "dex"); os.makedirs(dex)
    volt.sh([sdk["d8"], "--release", "--min-api", "24", "--lib", sdk["jar"], "--output", dex, jar] + glob.glob(os.path.join(cls, "**", "*.class"), recursive=True))
    with zipfile.ZipFile(un, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(os.path.join(dex, "classes.dex"), "classes.dex")
        for so in glob.glob(os.path.join(t, "aar", "jni", "*", "*.so")): z.write(so, "lib/%s/%s" % (so.split(os.sep)[-2], os.path.basename(so)))
    al = os.path.join(t, "al.apk"); volt.sh([sdk["zipalign"], "-p", "-f", "4", un, al])
    ks, pw = volt.keystore()
    volt.sh([sdk["apksigner"], "sign", "--ks", ks, "--ks-pass", "pass:" + pw, "--key-pass", "pass:" + pw, "--out", out, al])
print("built", out)
