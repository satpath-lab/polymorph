package dev.polymorph.app;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.HashSet;
import java.util.Set;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;

/** TLS certificate pinning + AES-256-GCM encryption at rest with a hardware-backed Android Keystore key. */
public final class PolySecure {
    static final Set<String> PINS = new HashSet<>();
    static boolean loaded;
    static JSONObject features = new JSONObject();
    static boolean encrypt = true;

    public static boolean on(String feature, boolean def) { return features.optBoolean(feature, def); }
    static final String ALIAS = "poly-data";

    static synchronized void init(Context c) {
        if (loaded) return;
        loaded = true;
        try {
            InputStream in = c.getAssets().open("poly.json");
            JSONObject cfg = new JSONObject(MainActivity.readAll(in));
            JSONObject f = cfg.optJSONObject("features");
            if (f != null) features = f;
            encrypt = features.optBoolean("encryptStorage", true);
            JSONArray pins = cfg.optJSONArray("pins");
            if (pins != null) for (int i = 0; i < pins.length(); i++) PINS.add(pins.getString(i));
        } catch (Exception ignored) {}
    }

    static String pinOf(Certificate cert) throws Exception {
        return "sha256/" + Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()), Base64.NO_WRAP);
    }

    public static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection && !PINS.isEmpty()) {
            final HostnameVerifier def = HttpsURLConnection.getDefaultHostnameVerifier();
            ((HttpsURLConnection) c).setHostnameVerifier((host, session) -> {
                if (!def.verify(host, session)) return false;
                try {
                    for (Certificate cert : session.getPeerCertificates()) if (PINS.contains(pinOf(cert))) return true;
                } catch (Exception ignored) {}
                return false;
            });
        }
        return c;
    }

    static SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
        KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build());
        return kg.generateKey();
    }

    public static String enc(String plain) {
        if (plain == null) return null;
        if (!encrypt) return plain;
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = c.getIV(), ct = c.doFinal(plain.getBytes("UTF-8"));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length); System.arraycopy(ct, 0, all, iv.length, ct.length);
            return "v1:" + Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Exception e) { return plain; }
    }

    public static String dec(String stored, String fallback) {
        if (stored == null) return fallback;
        if (!stored.startsWith("v1:")) return stored;
        try {
            byte[] all = Base64.decode(stored.substring(3), Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), "UTF-8");
        } catch (Exception e) { return fallback; }
    }
}
