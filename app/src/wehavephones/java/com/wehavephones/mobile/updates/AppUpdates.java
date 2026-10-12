package com.wehavephones.mobile.updates;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageInstaller;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.wehavephones.BuildConfig;
import com.wehavephones.mobile.addons.net.Downloader;
import com.wehavephones.mobile.addons.net.Http;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** Process-owned work: a stopped/recreated screen does not own the download. */
public final class AppUpdates {
    public enum Phase { IDLE, CHECKING, AVAILABLE, DOWNLOADING, READY, ERROR }
    public interface Listener { void onUpdateStateChanged(); }
    private static AppUpdates instance;
    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private final Context context;
    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Set<Listener> listeners = new HashSet<>();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private volatile Phase phase = Phase.IDLE;
    private volatile AppRelease release;
    private volatile String error = "";
    private volatile int progress;
    private boolean installing;

    public static synchronized AppUpdates get(Context context) {
        if (instance == null) instance = new AppUpdates(context.getApplicationContext());
        return instance;
    }

    private AppUpdates(Context context) {
        this.context = context;
        preferences = context.getSharedPreferences("wehavephones_app_updates", Context.MODE_PRIVATE);
        if (isSupported()) {
            // Process death can interrupt the APK copy. Committed sessions belong to Android.
            try {
                PackageInstaller installer = context.getPackageManager().getPackageInstaller();
                for (PackageInstaller.SessionInfo session : installer.getMySessions()) {
                    if (context.getPackageName().equals(session.getAppPackageName()) && !session.isSealed()) {
                        installer.abandonSession(session.getSessionId());
                    }
                }
            } catch (RuntimeException e) { Log.w("WeHavePhonesUpdates", "Could not clean up interrupted install", e); }
        }
        String cached = preferences.getString("releases", "");
        if (isSupported() && !cached.isEmpty()) {
            try {
                release = AppRelease.findUpdate(cached, BuildConfig.VERSION_NAME);
                if (release != null) phase = Phase.AVAILABLE;
            } catch (IOException ignored) { }
        }
        // Keep a complete candidate for retries; only clean up our own stale download.
        File apk = apkFile();
        if (release == null || !release.version.equals(preferences.getString("apk_version", ""))) {
            apk.delete();
        }
    }

    public boolean isSupported() { return !BuildConfig.DEBUG && AppRelease.PACKAGE.equals(context.getPackageName()); }
    public boolean isAutomatic() { return preferences.getBoolean("automatic", true); }
    public void setAutomatic(boolean enabled) { preferences.edit().putBoolean("automatic", enabled).apply(); }
    public Phase phase() { return phase; }
    public AppRelease release() { return release; }
    public String error() { return error; }
    public int progress() { return progress; }
    public File apkFile() { return new File(context.getCacheDir(), "app-updates/update.apk"); }
    public void addListener(Listener listener) { listeners.add(listener); }
    public void removeListener(Listener listener) { listeners.remove(listener); }
    public boolean isBusy() { return installing || phase == Phase.CHECKING || phase == Phase.DOWNLOADING; }
    public boolean beginInstall() {
        if (!isSupported() || isBusy() || release == null) return false;
        installing = true;
        return true;
    }
    public void finishInstall() { installing = false; }

    /** Called on the main thread. Failed automatic checks also back off for a day. */
    public void check(boolean manual) {
        if (!isSupported() || isBusy()) return;
        long now = System.currentTimeMillis();
        long last = preferences.getLong("last_check", 0);
        if (!manual && (!isAutomatic() || (now >= last && now - last < DAY_MS))) return;
        preferences.edit().putLong("last_check", now).apply();
        publish(Phase.CHECKING);
        worker.execute(() -> {
            try {
                Http.Response response = Http.get(AppRelease.API,
                        Collections.singletonMap("Accept", "application/vnd.github+json"), null, null, Http.MAX_JSON_BYTES);
                if (!response.isSuccess()) throw new IOException("GitHub returned HTTP " + response.status);
                AppRelease candidate = AppRelease.findUpdate(response.text(), BuildConfig.VERSION_NAME);
                preferences.edit().putString("releases", response.text()).apply();
                release = candidate;
                main.post(() -> publish(candidate == null ? Phase.IDLE : Phase.AVAILABLE));
            } catch (Exception e) { fail(e); }
        });
    }

    public boolean isCancellationRequested() { return cancelled.get(); }
    public void cancelDownload() { cancelled.set(true); notifyListeners(); }

    /** Downloads are retried from scratch after interruption, complete APKs are reused after verification. */
    public void download() {
        final AppRelease candidate = release;
        if (!isSupported() || candidate == null || isBusy()) return;
        cancelled.set(false);
        progress = 0;
        publish(Phase.DOWNLOADING);
        worker.execute(() -> {
            File apk = apkFile();
            try {
                File directory = apk.getParentFile();
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create download directory");
                String expected = candidate.sha256;
                if (expected.isEmpty()) {
                    Http.Response sums = Http.get(candidate.checksumUrl, null, null, null, 64 * 1024);
                    if (!sums.isSuccess()) throw new IOException("Cannot download APK checksum: HTTP " + sums.status);
                    expected = candidate.checksumFrom(sums.text());
                }
                boolean reuse = candidate.version.equals(preferences.getString("apk_version", "")) && apk.isFile();
                if (reuse) {
                    try { candidate.requireIntegrity(apk.length(), Downloader.hash(apk, "SHA-256"), expected); }
                    catch (IOException e) { reuse = false; }
                }
                if (!reuse) {
                    apk.delete();
                    // Leave room for installer staging and extracted native libraries too.
                    long requiredSpace = candidate.size * 3 + 256L * 1024 * 1024;
                    if (directory.getUsableSpace() < requiredSpace) {
                        throw new IOException("Not enough free storage. Free at least "
                                + ((requiredSpace + 999999) / 1000000) + " MB and retry");
                    }
                    Downloader.Result downloaded = Downloader.download(candidate.downloadUrl, null, null, null,
                            apk, candidate.size, new Downloader.Listener() {
                                private int lastPercent = -1;
                                @Override public void onProgress(long read, long total) {
                                    int percent = (int)(read * 100 / candidate.size);
                                    if (percent != lastPercent) {
                                        lastPercent = percent;
                                        progress = percent;
                                        main.post(AppUpdates.this::notifyListeners);
                                    }
                                }
                                @Override public boolean isCancelled() { return cancelled.get(); }
                            });
                    candidate.requireIntegrity(downloaded.bytes, downloaded.sha256, expected);
                }
                if (cancelled.get()) {
                    apk.delete();
                    main.post(() -> publish(Phase.AVAILABLE));
                    return;
                }
                verifyApk(candidate, apk);
                preferences.edit().putString("apk_version", candidate.version).putString("apk_sha256", expected).apply();
                progress = 100;
                main.post(() -> publish(Phase.READY));
            } catch (Exception e) {
                apk.delete();
                if (cancelled.get()) main.post(() -> publish(Phase.AVAILABLE));
                else fail(e);
            }
        });
    }

    /** Repeat integrity and Android metadata checks just before staging an install. */
    public File verifiedApk() throws IOException, PackageManager.NameNotFoundException {
        if (!isSupported() || release == null) throw new IOException("No release update is ready");
        File apk = apkFile();
        release.requireIntegrity(apk.length(), Downloader.hash(apk, "SHA-256"), preferences.getString("apk_sha256", ""));
        verifyApk(release, apk);
        return apk;
    }

    private void verifyApk(AppRelease candidate, File apk) throws IOException, PackageManager.NameNotFoundException {
        PackageManager packages = context.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
        PackageInfo installed = packages.getPackageInfo(context.getPackageName(), flags);
        PackageInfo archive = packages.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        if (archive == null || archive.applicationInfo == null) throw new IOException("Cannot read the downloaded APK");
        if ((archive.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            throw new IOException("Refusing a debug APK");
        }
        if (archive.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT) throw new IOException("This update needs a newer Android version");
        candidate.requireCompatible(archive.packageName, versionCode(archive), archive.versionName,
                versionCode(installed), signers(installed), signers(archive));
    }

    private static long versionCode(PackageInfo info) { return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode; }
    private static Set<String> signers(PackageInfo info) {
        Signature[] signatures = Build.VERSION.SDK_INT >= 28
                ? (info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()) : info.signatures;
        Set<String> result = new HashSet<>();
        if (signatures != null) for (Signature signature : signatures) result.add(signature.toCharsString());
        return result;
    }

    private void fail(Exception exception) {
        Log.w("WeHavePhonesUpdates", "Update failed", exception);
        error = exception.getMessage() == null ? "Update failed" : exception.getMessage();
        main.post(() -> publish(Phase.ERROR));
    }

    private void publish(Phase next) { phase = next; notifyListeners(); }
    private void notifyListeners() { for (Listener listener : new HashSet<>(listeners)) listener.onUpdateStateChanged(); }
}
