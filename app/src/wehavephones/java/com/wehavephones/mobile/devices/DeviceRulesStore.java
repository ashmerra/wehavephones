package com.wehavephones.mobile.devices;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.wehavephones.BuildConfig;

import org.json.JSONException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Supplies the effective device rules: the copy bundled in the APK, or a newer remote copy that
 * was downloaded, verified against the embedded public key and cached. A cached copy is checked
 * again on every load, so a damaged or tampered cache falls back to the bundled rules.
 */
public final class DeviceRulesStore {
    static final String TAG = "WeHavePhonesDevices";
    public static final String RULES_URL =
        "https://raw.githubusercontent.com/ashmerra/wehavephones/main/app/src/wehavephones/assets/wehavephones/device-rules.json";
    public static final String SIGNATURE_URL = RULES_URL + ".sig";
    public static final String BUNDLED_ASSET = "wehavephones/device-rules.json";
    public static final String SOURCE_BUNDLED = "bundled";
    public static final String SOURCE_REMOTE = "remote";

    public static final long REFRESH_INTERVAL_MS = 6L * 60 * 60 * 1000;
    // After a network failure the next session may try again sooner.
    static final long RETRY_AFTER_ERROR_MS = 30L * 60 * 1000;
    static final int CONNECT_TIMEOUT_MS = 10000;
    static final int READ_TIMEOUT_MS = 10000;
    static final int MAX_RULES_BYTES = 256 * 1024;
    static final int MAX_SIGNATURE_BYTES = 4 * 1024;
    private static final int MAX_REDIRECTS = 3;

    static final String PREFERENCES = "wehavephones_devices";
    private static final String KEY_LAST_CHECK = "rules_last_check_ms";

    private static final Object LOCK = new Object();
    private static final AtomicBoolean REFRESHING = new AtomicBoolean();

    private DeviceRulesStore() {}

    public static final class Loaded {
        public final DeviceRules rules;
        /** {@link #SOURCE_BUNDLED} or {@link #SOURCE_REMOTE}. */
        public final String source;

        Loaded(DeviceRules rules, String source) {
            this.rules = rules;
            this.source = source;
        }
    }

    public static final class RefreshResult {
        public static final String UPDATED = "updated";
        public static final String CURRENT = "current";
        public static final String REJECTED = "rejected";
        public static final String ERROR = "error";
        public static final String SKIPPED = "skipped";

        public final String status;
        public final String message;
        /** Version of the rules in effect after the refresh. */
        public final int rulesVersion;

        RefreshResult(String status, String message, int rulesVersion) {
            this.status = status;
            this.message = message;
            this.rulesVersion = rulesVersion;
        }

        public boolean isUpdated() {
            return UPDATED.equals(status);
        }

        @Override
        public String toString() {
            return status + ": " + message;
        }
    }

    /** Fetches a URL's body, at most maxBytes; throws when it is larger or the request fails. */
    public interface Fetcher {
        byte[] fetch(String url, int maxBytes) throws IOException;
    }

    public static File cacheDir(Context context) {
        return new File(new File(context.getFilesDir(), "wehavephones"), "device-rules");
    }

    /** Effective rules: the verified remote cache when it is newer than the bundled copy, else bundled. */
    public static Loaded load(Context context) {
        return load(context, embeddedKey());
    }

    static Loaded load(Context context, PublicKey key) {
        synchronized (LOCK) {
            DeviceRules bundled = loadBundled(context);
            DeviceRules cached = loadCached(context, key, bundled.getRulesVersion());
            if (cached != null) return new Loaded(cached, SOURCE_REMOTE);
            return new Loaded(bundled, SOURCE_BUNDLED);
        }
    }

    /** Checks for newer rules in the background, at most every six hours; never blocks the caller. */
    public static void refreshAsync(final Context context) {
        final Context app = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        if (!REFRESHING.compareAndSet(false, true)) return;
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    backgroundRefresh(app);
                }
                catch (Throwable e) {
                    Log.w(TAG, "device rules refresh failed: " + e);
                }
                finally {
                    REFRESHING.set(false);
                }
            }
        }, "WeHavePhonesDevices-refresh");
        thread.setDaemon(true);
        thread.start();
    }

    private static void backgroundRefresh(Context context) {
        long now = System.currentTimeMillis();
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        long last = preferences.getLong(KEY_LAST_CHECK, 0);
        if (last <= now && now - last < REFRESH_INTERVAL_MS) {
            Log.i(TAG, "device rules checked recently; next check later");
        }
        else {
            RefreshResult result = refresh(context, new HttpFetcher(), embeddedKey(), BuildConfig.VERSION_CODE, now);
            Log.i(TAG, "device rules refresh: " + result);
        }
        prefetchForThisDevice(context);
    }

    /** Checks now regardless of the rate limit; blocking, so call it off the UI thread. */
    public static RefreshResult refreshNow(Context context) {
        RefreshResult result = refresh(context, new HttpFetcher(), embeddedKey(), BuildConfig.VERSION_CODE,
            System.currentTimeMillis());
        Log.i(TAG, "device rules refresh: " + result);
        prefetchForThisDevice(context);
        return result;
    }

    private static void prefetchForThisDevice(Context context) {
        try {
            Loaded loaded = load(context);
            DeviceRules.Match match = loaded.rules.match(DeviceIdentity.detect(context));
            if (match.profile != null) DeviceDriverStore.prefetchNow(context, match.profile);
        }
        catch (RuntimeException e) {
            Log.w(TAG, "driver prefetch failed: " + e);
        }
    }

    /**
     * One refresh attempt. The remote file is accepted only with a valid signature, a supported
     * schema, a minAppVersionCode this build meets, and a rulesVersion above the bundled copy and
     * not below the cached one.
     */
    static RefreshResult refresh(Context context, Fetcher fetcher, PublicKey key, int appVersionCode, long now) {
        SharedPreferences preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        DeviceRules bundled;
        int cachedVersion;
        synchronized (LOCK) {
            bundled = loadBundled(context);
            DeviceRules cached = loadCached(context, key, bundled.getRulesVersion());
            cachedVersion = cached != null ? cached.getRulesVersion() : 0;
        }
        int effective = Math.max(bundled.getRulesVersion(), cachedVersion);

        byte[] rulesBytes;
        String signature;
        try {
            rulesBytes = fetcher.fetch(RULES_URL, MAX_RULES_BYTES);
            signature = new String(fetcher.fetch(SIGNATURE_URL, MAX_SIGNATURE_BYTES), StandardCharsets.US_ASCII);
        }
        catch (IOException e) {
            preferences.edit().putLong(KEY_LAST_CHECK, now - REFRESH_INTERVAL_MS + RETRY_AFTER_ERROR_MS).apply();
            return new RefreshResult(RefreshResult.ERROR, "download failed: " + e.getMessage(), effective);
        }
        preferences.edit().putLong(KEY_LAST_CHECK, now).apply();

        if (!verifySignature(rulesBytes, signature, key)) {
            return new RefreshResult(RefreshResult.REJECTED, "signature does not verify", effective);
        }
        DeviceRules candidate;
        try {
            candidate = DeviceRules.parse(new String(rulesBytes, StandardCharsets.UTF_8));
        }
        catch (JSONException e) {
            return new RefreshResult(RefreshResult.REJECTED, "invalid rules: " + e.getMessage(), effective);
        }
        if (candidate.getMinAppVersionCode() > appVersionCode) {
            return new RefreshResult(RefreshResult.REJECTED,
                "rules need app version " + candidate.getMinAppVersionCode() + ", this is " + appVersionCode, effective);
        }
        if (candidate.getRulesVersion() <= bundled.getRulesVersion()) {
            return new RefreshResult(RefreshResult.REJECTED,
                "rulesVersion " + candidate.getRulesVersion() + " is not newer than bundled " + bundled.getRulesVersion(), effective);
        }
        if (candidate.getRulesVersion() < cachedVersion) {
            return new RefreshResult(RefreshResult.REJECTED,
                "rulesVersion " + candidate.getRulesVersion() + " is older than cached " + cachedVersion, effective);
        }
        if (candidate.getRulesVersion() == cachedVersion) {
            return new RefreshResult(RefreshResult.CURRENT, "rules v" + cachedVersion + " are current", effective);
        }
        try {
            synchronized (LOCK) {
                saveCache(context, rulesBytes, signature.trim());
            }
        }
        catch (IOException e) {
            return new RefreshResult(RefreshResult.ERROR, "could not cache the rules: " + e.getMessage(), effective);
        }
        return new RefreshResult(RefreshResult.UPDATED, "rules v" + candidate.getRulesVersion() + " installed", candidate.getRulesVersion());
    }

    static boolean verifySignature(byte[] data, String signatureBase64, PublicKey key) {
        try {
            byte[] der = Base64.getMimeDecoder().decode(signatureBase64.trim());
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(key);
            signature.update(data);
            return signature.verify(der);
        }
        catch (Exception e) {
            return false;
        }
    }

    static PublicKey embeddedKey() {
        return publicKey(DeviceRulesKey.PUBLIC_KEY_BASE64);
    }

    static PublicKey publicKey(String base64) {
        try {
            byte[] encoded = Base64.getDecoder().decode(base64);
            return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(encoded));
        }
        catch (Exception e) {
            throw new IllegalStateException("invalid device rules public key", e);
        }
    }

    private static DeviceRules loadBundled(Context context) {
        try (InputStream in = context.getAssets().open(BUNDLED_ASSET)) {
            return DeviceRules.parse(new String(readAll(in, MAX_RULES_BYTES), StandardCharsets.UTF_8));
        }
        catch (IOException | JSONException e) {
            throw new IllegalStateException("bundled device rules are unreadable: " + e.getMessage(), e);
        }
    }

    private static DeviceRules loadCached(Context context, PublicKey key, int bundledVersion) {
        File dir = cacheDir(context);
        File rulesFile = new File(dir, "device-rules.json");
        File signatureFile = new File(dir, "device-rules.json.sig");
        if (!rulesFile.isFile() || !signatureFile.isFile()) return null;
        try {
            byte[] rulesBytes = readFile(rulesFile, MAX_RULES_BYTES);
            String signature = new String(readFile(signatureFile, MAX_SIGNATURE_BYTES), StandardCharsets.US_ASCII);
            if (!verifySignature(rulesBytes, signature, key)) {
                Log.w(TAG, "cached device rules fail signature check; using bundled");
                return null;
            }
            DeviceRules rules = DeviceRules.parse(new String(rulesBytes, StandardCharsets.UTF_8));
            if (rules.getMinAppVersionCode() > BuildConfig.VERSION_CODE || rules.getRulesVersion() <= bundledVersion) return null;
            return rules;
        }
        catch (IOException | JSONException e) {
            Log.w(TAG, "cached device rules unusable: " + e.getMessage());
            return null;
        }
    }

    private static void saveCache(Context context, byte[] rules, String signature) throws IOException {
        File dir = cacheDir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create " + dir);
        writeAtomically(new File(dir, "device-rules.json.sig"), signature.getBytes(StandardCharsets.US_ASCII));
        writeAtomically(new File(dir, "device-rules.json"), rules);
    }

    private static void writeAtomically(File target, byte[] data) throws IOException {
        File part = new File(target.getPath() + ".part");
        try (OutputStream out = new FileOutputStream(part)) {
            out.write(data);
        }
        if (target.exists() && !target.delete()) throw new IOException("Could not replace " + target);
        if (!part.renameTo(target)) throw new IOException("Could not write " + target);
    }

    private static byte[] readFile(File file, int maxBytes) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in, maxBytes);
        }
    }

    static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            if (out.size() > maxBytes) throw new IOException("response larger than " + maxBytes + " bytes");
        }
        return out.toByteArray();
    }

    private static final class HttpFetcher implements Fetcher {
        @Override
        public byte[] fetch(String url, int maxBytes) throws IOException {
            String current = url;
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                if (!current.startsWith("https://")) throw new IOException("refusing non-https url");
                HttpURLConnection connection = (HttpURLConnection)new URL(current).openConnection();
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("User-Agent", "WeHavePhones/" + BuildConfig.VERSION_NAME);
                try {
                    int code = connection.getResponseCode();
                    if (code == 301 || code == 302 || code == 307 || code == 308) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IOException("redirect without location");
                        current = new URL(new URL(current), location).toString();
                        continue;
                    }
                    if (code != HttpURLConnection.HTTP_OK) throw new IOException("HTTP " + code);
                    long length = connection.getContentLengthLong();
                    if (length > maxBytes) throw new IOException("response larger than " + maxBytes + " bytes");
                    try (InputStream in = connection.getInputStream()) {
                        return readAll(in, maxBytes);
                    }
                }
                finally {
                    connection.disconnect();
                }
            }
            throw new IOException("too many redirects");
        }
    }
}
