package com.wehavephones.mobile.addons.required;

import android.content.Context;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.wehavephones.mobile.WeHavePhonesConfig;
import com.wehavephones.mobile.WeHavePhonesContainers;
import com.wehavephones.mobile.WeHavePhonesPaths;
import com.wehavephones.mobile.WowFlavor;
import com.wehavephones.mobile.WowInstallationScanner;
import com.wehavephones.mobile.WowProduct;
import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.install.AddonInstaller;
import com.wehavephones.mobile.addons.install.FileOps;
import com.wehavephones.mobile.addons.model.Channel;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.RemoteFile;
import com.wehavephones.mobile.addons.net.Downloader;
import com.wehavephones.mobile.addons.provider.AddonSecrets;
import com.wehavephones.mobile.addons.provider.GitHubProvider;
import com.wehavephones.mobile.addons.provider.ResolvedDownload;
import com.wehavephones.mobile.addons.scan.VersionComparator;
import com.wehavephones.mobile.addons.util.GameGuard;
import com.wehavephones.container.Container;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipFile;

/**
 * Keeps ConsolePort installed and current in every non-Forever WoW product.
 *
 * <p>Archive sources, newest wins: the release bundled in the APK
 * ({@code assets/wehavephones/addons/}, pinned by {@code consoleport.json} and
 * fetched by {@code scripts/fetch-consoleport.sh}) and releases downloaded
 * from GitHub by {@link #checkForUpdate}. The chosen archive is cached in
 * {@code files/wehavephones/required-addons/}. {@link #ensureInstalled} installs
 * or upgrades the suite before every Play and at app start; it never
 * downgrades, keeps a user-disabled suite disabled, and leaves the AddOns
 * directory alone while the game runs.
 */
public final class ConsolePortUpdater {
    public static final String TAG = "WeHavePhonesConsolePort";

    private static final String ASSET_MANIFEST = "wehavephones/addons/consoleport.json";
    private static final String ASSET_ARCHIVE = "wehavephones/addons/ConsolePort.zip";
    private static final String ASSET_SETUP_DIR = "wehavephones/addons/" + ControllerSetupAddon.FOLDER + "/";
    private static final String STATE_FILE = "consoleport.json";
    private static final String KEY_LAST_CHECK = "wehavephones_consoleport_last_check";
    private static final long CHECK_INTERVAL_MS = 6L * 60L * 60L * 1000L;
    private static final long MAX_ARCHIVE_BYTES = 128L * 1024L * 1024L;

    /** Guards the archive cache and the install pass; AddOns writes also take {@link AddonInstaller#LOCK}. */
    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor((runnable) -> {
        Thread thread = new Thread(runnable, "wehavephones-consoleport");
        thread.setDaemon(true);
        return thread;
    });

    public interface Listener {
        void onInstalling(WowProduct product, String version);
    }

    /** The archive installs use: the newest of the bundled and downloaded releases. */
    public static final class Package {
        public final String version;
        public final File archive;
        /** "bundled" or "github". */
        public final String source;

        Package(String version, File archive, String source) {
            this.version = version;
            this.archive = archive;
            this.source = source;
        }
    }

    public static final class Report {
        public String version = "";
        public int installed;
        public int upToDate;
        /** Forever installs, which use native controller support. */
        public int skipped;
        /** Products whose WeHavePhonesControllerSetup copy was installed or refreshed. */
        public int setupUpdated;
        /** Why installs that were needed did not run (game open, no archive); empty otherwise. */
        public String blocked = "";
        public final ArrayList<String> errors = new ArrayList<>();

        public String summary() {
            StringBuilder text = new StringBuilder("ConsolePort ")
                    .append(version.isEmpty() ? "?" : version)
                    .append(": ").append(installed).append(" installed, ")
                    .append(upToDate).append(" up to date, ")
                    .append(skipped).append(" Forever skipped");
            if (setupUpdated > 0) text.append(", controller setup updated in ").append(setupUpdated);
            if (!blocked.isEmpty()) text.append("; ").append(blocked);
            for (String error : errors) text.append("; ").append(error);
            return text.toString();
        }
    }

    private ConsolePortUpdater() {}

    /** App start: throttled GitHub check, then install/upgrade everywhere. */
    public static void syncInBackground(Context context) {
        final Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                checkForUpdate(app, false);
            }
            catch (Exception e) {
                Log.w(TAG, "update check failed: " + e.getMessage());
            }
            try {
                Log.i(TAG, ensureInstalled(app, WeHavePhonesContainers.resolve(app), null).summary());
            }
            catch (Throwable throwable) {
                Log.e(TAG, "install pass failed", throwable);
            }
        });
    }

    /** Settings button: forced GitHub check, then install/upgrade everywhere. */
    public static void checkNow(Context context, final AddonManager.Callback<Report> callback) {
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        EXECUTOR.execute(() -> {
            String checkError = null;
            try {
                checkForUpdate(app, true);
            }
            catch (Exception e) {
                checkError = "Update check failed: " + message(e);
                Log.w(TAG, checkError);
            }
            try {
                final Report report = ensureInstalled(app, WeHavePhonesContainers.resolve(app), null);
                if (checkError != null) report.errors.add(0, checkError);
                Log.i(TAG, report.summary());
                main.post(() -> callback.onSuccess(report));
            }
            catch (Throwable throwable) {
                Log.e(TAG, "install pass failed", throwable);
                main.post(() -> callback.onError(message(throwable)));
            }
        });
    }

    /**
     * Installs or upgrades ConsolePort in every non-Forever product of the
     * container. Local only (no network) unless no archive exists at all.
     */
    public static Report ensureInstalled(Context context, Container container, Listener listener) {
        Report report = new Report();
        if (container == null) {
            report.blocked = "no container";
            return report;
        }
        ArrayList<WowProduct> targets = new ArrayList<>();
        for (WowProduct product : WowInstallationScanner.scan(container)) {
            if (product.directory == null) continue;
            if (ConsolePort.requiredFor(product.getFlavor())) targets.add(product);
            else report.skipped++;
        }
        if (targets.isEmpty()) return report;

        synchronized (LOCK) {
            Package pkg = null;
            try {
                pkg = bestPackage(context);
            }
            catch (IOException e) {
                report.errors.add("bundled archive: " + message(e));
            }
            if (pkg == null) {
                // APK built without the bundled archive: fall back to GitHub once.
                try {
                    checkForUpdate(context, true);
                    pkg = bestPackage(context);
                }
                catch (Exception e) {
                    report.errors.add("download: " + message(e));
                }
            }
            if (pkg == null) {
                report.blocked = "no ConsolePort archive available";
                return report;
            }
            report.version = pkg.version;

            boolean gameRunning = GameGuard.isGameRunning();
            Map<String, byte[]> setupFiles = gameRunning ? null : bundledSetupAddon(context, report);
            for (WowProduct product : targets) {
                File addons = new File(product.directory, "Interface/AddOns");
                String installed = ConsolePort.installedVersion(addons);
                if (!ConsolePort.needsInstall(installed, pkg.version)) {
                    report.upToDate++;
                    syncSetupAddon(addons, setupFiles, product, report);
                    continue;
                }
                if (gameRunning) {
                    report.blocked = "close World of Warcraft to install it";
                    continue;
                }
                if (listener != null) listener.onInstalling(product, pkg.version);
                try {
                    synchronized (AddonInstaller.LOCK) {
                        ConsolePort.install(pkg.archive, addons, product.getFlavor());
                    }
                    report.installed++;
                    Log.i(TAG, "installed " + pkg.version + " (" + pkg.source + ") into " + product.directoryName
                            + (installed.isEmpty() ? "" : ", was " + installed));
                }
                catch (Exception e) {
                    report.errors.add(product.getDisplayName() + ": " + message(e));
                    Log.w(TAG, "install into " + product.directoryName + " failed", e);
                }
                syncSetupAddon(addons, setupFiles, product, report);
            }
        }
        return report;
    }

    /** Null (and an error in the report) when the APK lacks the companion addon. */
    private static Map<String, byte[]> bundledSetupAddon(Context context, Report report) {
        LinkedHashMap<String, byte[]> files = new LinkedHashMap<>();
        for (String name : ControllerSetupAddon.FILES) {
            try (InputStream input = context.getAssets().open(ASSET_SETUP_DIR + name)) {
                files.put(name, readAll(input));
            }
            catch (IOException e) {
                report.errors.add("bundled controller setup: " + message(e));
                return null;
            }
        }
        return files;
    }

    /** Keeps WeHavePhonesControllerSetup beside ConsolePort; null files means the game is running. */
    private static void syncSetupAddon(File addons, Map<String, byte[]> files, WowProduct product, Report report) {
        if (files == null || !addons.isDirectory()) return;
        try {
            boolean changed;
            synchronized (AddonInstaller.LOCK) {
                changed = ControllerSetupAddon.sync(addons, files);
            }
            if (changed) {
                report.setupUpdated++;
                Log.i(TAG, "installed " + ControllerSetupAddon.FOLDER + " into " + product.directoryName);
            }
        }
        catch (IOException e) {
            report.errors.add(product.getDisplayName() + " controller setup: " + message(e));
            Log.w(TAG, ControllerSetupAddon.FOLDER + " into " + product.directoryName + " failed", e);
        }
    }

    /**
     * Downloads the latest GitHub release into the cache when it is newer
     * than every local archive. Throttled to one query per 6 hours unless
     * forced; returns the newest known version, or null when throttled.
     */
    public static String checkForUpdate(Context context, boolean force) throws Exception {
        long now = System.currentTimeMillis();
        if (!force && now - lastCheckAt(context) < CHECK_INTERVAL_MS) return null;

        GitHubProvider provider = new GitHubProvider(AddonSecrets.get(context, ProviderType.GITHUB));
        RemoteFile latest = provider.getLatestFile(ConsolePort.GITHUB_REPO, WowFlavor.RETAIL, Channel.STABLE, "");
        WeHavePhonesConfig.prefs(context).edit().putLong(KEY_LAST_CHECK, now).apply();
        if (latest == null || latest.downloadUrl == null || latest.downloadUrl.isEmpty()) {
            throw new IOException("latest ConsolePort release has no zip asset");
        }
        synchronized (LOCK) {
            Package current = bestPackage(context);
            if (current != null && !VersionComparator.isNewer(latest.version, current.version)) {
                return current.version;
            }
        }

        File dir = cacheDir(context);
        File download = new File(dir, "download-" + FileOps.uuid() + ".zip");
        try {
            ResolvedDownload resolved = provider.resolveDownload(latest);
            Downloader.download(resolved.url, resolved.headers, resolved.secretHeaders, resolved.secretHosts,
                    download, MAX_ARCHIVE_BYTES, null);
            requireConsolePortArchive(download);
            String sha256 = Downloader.hash(download, "SHA-256");
            synchronized (LOCK) {
                File target = new File(dir, archiveName(latest.version));
                FileOps.deleteRecursively(target);
                if (!download.renameTo(target)) throw new IOException("Cannot store " + target.getName());
                saveState(dir, latest.version, target, sha256, "github");
                pruneArchives(dir, target);
            }
            Log.i(TAG, "downloaded ConsolePort " + latest.version);
            return latest.version;
        }
        finally {
            FileOps.deleteRecursively(download);
        }
    }

    /** Cached archive, refreshed from the APK when the bundled release is newer. */
    public static Package bestPackage(Context context) throws IOException {
        synchronized (LOCK) {
            File dir = cacheDir(context);
            Package cached = null;
            JSONObject state = readJson(new File(dir, STATE_FILE));
            if (state != null) {
                File archive = new File(dir, state.optString("file", ""));
                String version = state.optString("version", "");
                if (!version.isEmpty() && archive.isFile()) {
                    cached = new Package(version, archive, state.optString("source", "github"));
                }
            }
            JSONObject bundled = bundledManifest(context);
            if (bundled == null) return cached;
            String bundledVersion = bundled.optString("version", "");
            if (cached != null && !VersionComparator.isNewer(bundledVersion, cached.version)) return cached;
            if (!hasAsset(context.getAssets(), ASSET_ARCHIVE)) return cached;

            File target = new File(dir, archiveName(bundledVersion));
            File tmp = new File(dir, target.getName() + ".tmp");
            try {
                copyAsset(context.getAssets(), ASSET_ARCHIVE, tmp);
                String sha256 = Downloader.hash(tmp, "SHA-256");
                String expected = bundled.optString("sha256", "");
                if (!expected.isEmpty() && !expected.equalsIgnoreCase(sha256)) {
                    throw new IOException("bundled ConsolePort archive failed the SHA-256 check");
                }
                FileOps.deleteRecursively(target);
                if (!tmp.renameTo(target)) throw new IOException("Cannot store " + target.getName());
                saveState(dir, bundledVersion, target, sha256, "bundled");
                pruneArchives(dir, target);
                return new Package(bundledVersion, target, "bundled");
            }
            finally {
                FileOps.deleteRecursively(tmp);
            }
        }
    }

    /** Version the next install would use, or "" before the first sync. */
    public static String availableVersion(Context context) {
        synchronized (LOCK) {
            JSONObject state = readJson(new File(cacheDir(context), STATE_FILE));
            if (state != null && !state.optString("version", "").isEmpty()) return state.optString("version", "");
            JSONObject bundled = bundledManifest(context);
            return bundled != null ? bundled.optString("version", "") : "";
        }
    }

    public static long lastCheckAt(Context context) {
        return WeHavePhonesConfig.prefs(context).getLong(KEY_LAST_CHECK, 0);
    }

    // ---------------------------------------------------------------- internals

    private static File cacheDir(Context context) {
        File dir = new File(WeHavePhonesPaths.wehavephonesDir(context), "required-addons");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    private static String archiveName(String version) {
        return "ConsolePort-" + version.replaceAll("[^A-Za-z0-9._-]", "_") + ".zip";
    }

    private static void requireConsolePortArchive(File archive) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            if (zip.getEntry(ConsolePort.CORE_FOLDER + "/" + ConsolePort.CORE_FOLDER + ".toc") == null) {
                throw new IOException("downloaded archive is not a ConsolePort release");
            }
        }
    }

    private static void saveState(File dir, String version, File archive, String sha256, String source)
            throws IOException {
        JSONObject state = new JSONObject();
        try {
            state.put("version", version);
            state.put("file", archive.getName());
            state.put("sha256", sha256);
            state.put("source", source);
            state.put("storedAt", System.currentTimeMillis());
        }
        catch (Exception e) {
            throw new IOException("Cannot encode ConsolePort state", e);
        }
        File tmp = new File(dir, STATE_FILE + ".tmp");
        try (OutputStream output = new FileOutputStream(tmp)) {
            output.write(state.toString().getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        if (!tmp.renameTo(new File(dir, STATE_FILE))) throw new IOException("Cannot save ConsolePort state");
    }

    private static void pruneArchives(File dir, File keep) {
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.getName().endsWith(".zip") && !child.equals(keep)) FileOps.deleteRecursively(child);
        }
    }

    private static JSONObject bundledManifest(Context context) {
        try (InputStream input = context.getAssets().open(ASSET_MANIFEST)) {
            return new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
        }
        catch (Exception e) {
            return null;
        }
    }

    private static JSONObject readJson(File file) {
        if (!file.isFile()) return null;
        try (InputStream input = new FileInputStream(file)) {
            return new JSONObject(new String(readAll(input), StandardCharsets.UTF_8));
        }
        catch (Exception e) {
            return null;
        }
    }

    private static boolean hasAsset(AssetManager assets, String path) {
        try (InputStream ignored = assets.open(path)) {
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    private static void copyAsset(AssetManager assets, String path, File target) throws IOException {
        try (InputStream input = assets.open(path); OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    private static byte[] readAll(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        return output.toByteArray();
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}
