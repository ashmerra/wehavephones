package com.wehavephones.mobile;

import android.app.Activity;
import android.util.Base64;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.container.Shortcut;
import com.wehavephones.core.FileUtils;
import com.wehavephones.core.HttpUtils;
import com.wehavephones.core.WineInfo;
import com.wehavephones.core.WineUtils;
import com.wehavephones.fexcore.FEXCorePreset;
import com.wehavephones.winhandler.WinHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Runs an addon manager's own Windows app (CurseForge or WowUp) in the game's
 * container. It manages addons for the WoW installs itself, so no API key is
 * needed. The app is downloaded from its publisher on first use and is never
 * bundled. See {@link AddonApp} for what the container needs.
 */
public final class AddonAppLauncher {
    private static final int FEED_MAX_BYTES = 64 * 1024;
    private static final long FEED_CACHE_MS = 10 * 60 * 1000L;
    private static final Map<AddonApp, AddonApp.Release> cachedReleases = new EnumMap<>(AddonApp.class);
    private static final Map<AddonApp, Long> cachedAt = new EnumMap<>(AddonApp.class);

    public interface Listener {
        void onStatus(String message, boolean error);
    }

    /** What the Addon apps screen shows: whether the app is here, its version and the latest release. */
    public static final class Status {
        public final boolean installed;
        /** The version this launcher installed, or null when unknown. */
        public final String installedVersion;
        /** The feed's release, or null until the feed answers or when it cannot be reached. */
        public final AddonApp.Release latest;

        Status(boolean installed, String installedVersion, AddonApp.Release latest) {
            this.installed = installed;
            this.installedVersion = installedVersion;
            this.latest = latest;
        }

        /** Open installs this release first. */
        public boolean updateReady() {
            return installed && AddonApp.needsUpdate(installedVersion, latest);
        }
    }

    private AddonAppLauncher() {}

    public static File stageDir(Container container, AddonApp app) {
        return new File(WeHavePhonesPaths.driveC(container), app.stageDir());
    }

    /** The installed app in the first prefix user that has it. */
    public static File findApp(Container container, AddonApp app) {
        File[] users = new File(WeHavePhonesPaths.driveC(container), "users").listFiles();
        if (users == null) return null;
        for (File user : users) {
            File candidate = new File(user, "AppData/Local/" + app.appDir() + "/" + app.exe);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    /**
     * Reports the app's local state at once, then again with the feed's release
     * for the download size. A release seen in the last few minutes is reused.
     */
    public static void loadStatus(final Activity activity, final AddonApp app, final Consumer<Status> callback) {
        Executors.newSingleThreadExecutor().execute(() -> {
            Container container = WeHavePhonesContainers.resolve(activity);
            boolean installed = container != null && findApp(container, app) != null;
            File versionFile = container != null ? new File(stageDir(container, app), AddonApp.VERSION_FILE) : null;
            String read = versionFile != null && versionFile.isFile() ? FileUtils.readString(versionFile) : null;
            String version = read != null && !read.trim().isEmpty() ? read.trim() : null;
            AddonApp.Release cached = cachedRelease(app);
            activity.runOnUiThread(() -> callback.accept(new Status(installed, version, cached)));
            if (cached != null) return;
            AddonApp.Release latest = fetchLatest(app);
            if (latest != null) activity.runOnUiThread(() -> callback.accept(new Status(installed, version, latest)));
        });
    }

    private static AddonApp.Release cachedRelease(AddonApp app) {
        synchronized (cachedReleases) {
            Long at = cachedAt.get(app);
            if (at == null || System.currentTimeMillis() - at > FEED_CACHE_MS) return null;
            return cachedReleases.get(app);
        }
    }

    public static void open(final Activity activity, final AddonApp app, final Listener listener) {
        final String name = app.displayName;
        if (WowImportService.isRunning()) {
            listener.onStatus(activity.getString(R.string.wehavephones_import_wait), true);
            return;
        }
        if (ProcessScanner.findWowProcess() != null) {
            listener.onStatus(activity.getString(R.string.wehavephones_addon_app_close_game, name), true);
            return;
        }
        if (OneButtonLauncher.isRunning() || RuntimeStageController.isRunning()) {
            listener.onStatus(activity.getString(R.string.wehavephones_launch_in_progress), true);
            return;
        }
        listener.onStatus(activity.getString(R.string.wehavephones_addon_app_starting, name), false);
        Executors.newSingleThreadExecutor().execute(() -> {
            Container container = WeHavePhonesContainers.resolve(activity);
            if (container == null) {
                post(activity, listener, activity.getString(R.string.wehavephones_stage_setup_required), true);
                return;
            }
            File stage = stageDir(container, app);
            if (!stageHybridDlls(activity, container, stage) || !stagePatchScript(app, stage)) {
                post(activity, listener, activity.getString(R.string.wehavephones_addon_app_dlls_failed, name), true);
                return;
            }
            boolean installed = findApp(container, app) != null;
            AddonApp.Release latest = fetchLatest(app);
            File versionFile = new File(stage, AddonApp.VERSION_FILE);
            String installedVersion = versionFile.isFile() ? FileUtils.readString(versionFile) : null;
            if (installed && !AddonApp.needsUpdate(installedVersion, latest)) {
                start(activity, container, app, null, listener);
                return;
            }
            if (latest == null) {
                // An installed app still opens when the update server is unreachable.
                if (installed) start(activity, container, app, null, listener);
                else post(activity, listener, activity.getString(R.string.wehavephones_addon_app_feed_failed, name), true);
                return;
            }
            File installer = new File(stage, app.installerName());
            if (installer.isFile() && matches(installer, latest)) {
                start(activity, container, app, latest.version, listener);
                return;
            }
            installer.delete();
            post(activity, listener, activity.getString(installed ? R.string.wehavephones_addon_app_updating
                    : R.string.wehavephones_addon_app_downloading, name, latest.version, latest.size / (1024 * 1024)), false);
            activity.runOnUiThread(() -> HttpUtils.download(activity, latest.url, installer, (success) ->
                    Executors.newSingleThreadExecutor().execute(() -> {
                        if (!success || !matches(installer, latest)) {
                            installer.delete();
                            post(activity, listener, activity.getString(R.string.wehavephones_addon_app_download_failed, name), true);
                            return;
                        }
                        start(activity, container, app, latest.version, listener);
                    })));
        });
    }

    private static boolean stagePatchScript(AddonApp app, File stage) {
        if (app.patchScript == null) return true;
        byte[] script = AddonApp.WOWUP_CONTAINER_FIXES_JS.getBytes(StandardCharsets.UTF_8);
        return FileUtils.write(new File(stage, app.patchScript), script);
    }

    /** Copies Wine's ARM64X DirectX DLLs where the batch file picks them up. */
    private static boolean stageHybridDlls(Activity activity, Container container, File stage) {
        // The same choice XServerDisplayActivity makes for the session.
        WineInfo wine = WineInfo.fromIdentifier(activity, container.getWineVersion());
        if (!wine.isArm64EC()) {
            WineInfo arm64ec = WineUtils.getFirstArm64ECWineInfo(activity);
            if (arm64ec != null) wine = arm64ec;
        }
        if (wine.path == null) return false;
        File source = new File(wine.path, "lib/wine/" + (wine.isArm64EC() ? "aarch64-windows" : "x86_64-windows"));
        if (!stage.isDirectory() && !stage.mkdirs()) return false;
        for (String dll : AddonApp.HYBRID_DLLS) {
            File from = new File(source, dll);
            File to = new File(stage, dll);
            if (!from.isFile()) return false;
            if (to.isFile() && to.length() == from.length()) continue;
            if (!FileUtils.copy(from, to)) return false;
        }
        return true;
    }

    /** Presets the app to exit when closed, for every prefix user. */
    private static void configureSettings(Container container, AddonApp app) {
        File[] users = new File(WeHavePhonesPaths.driveC(container), "users").listFiles();
        if (users == null) return;
        for (File user : users) {
            if (!new File(user, "AppData").isDirectory() || user.getName().equalsIgnoreCase("Public")) continue;
            File settings = new File(user, "AppData/Roaming/" + app.settingsFile);
            File directory = settings.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) continue;
            String current = settings.isFile() ? FileUtils.readString(settings) : null;
            String updated = app.configureSettings(current);
            if (updated != null && !updated.equals(current)) {
                FileUtils.write(settings, updated.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static AddonApp.Release fetchLatest(AddonApp app) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(app.feedUrl).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) return null;
            try (InputStream in = connection.getInputStream()) {
                byte[] buffer = new byte[FEED_MAX_BYTES];
                int total = 0;
                int read;
                while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
                    total += read;
                }
                AddonApp.Release release = app.parseFeed(new String(buffer, 0, total, StandardCharsets.UTF_8));
                if (release != null) {
                    synchronized (cachedReleases) {
                        cachedReleases.put(app, release);
                        cachedAt.put(app, System.currentTimeMillis());
                    }
                }
                return release;
            }
        }
        catch (Exception e) {
            return null;
        }
        finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Size and SHA-512 from the feed, the same check the app's own updater makes. */
    private static boolean matches(File file, AddonApp.Release release) {
        if (file.length() != release.size) return false;
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-512");
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) digest.update(buffer, 0, read);
            return Base64.encodeToString(digest.digest(), Base64.NO_WRAP).equals(release.sha512);
        }
        catch (Exception e) {
            return false;
        }
    }

    private static void start(Activity activity, Container container, AddonApp app, String installVersion,
            Listener listener) {
        configureSettings(container, app);
        String batGuest = "C:\\" + app.batName();
        if (!FileUtils.write(new File(WeHavePhonesPaths.driveC(container), app.batName()),
                app.bat(installVersion).getBytes(StandardCharsets.UTF_8))) {
            post(activity, listener, activity.getString(R.string.wehavephones_launch_write_failed, app.batName()), true);
            return;
        }
        String started = activity.getString(installVersion != null
                ? R.string.wehavephones_addon_app_installing : R.string.wehavephones_addon_app_opened, app.displayName);

        // A running session (Battle.net, for example) gets the app in-session.
        XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
        WinHandler winHandler = session != null && session.isSessionActive() ? session.getWinHandler() : null;
        if (winHandler != null) {
            winHandler.exec(WeHavePhonesLaunchPlan.winHandlerCommand(batGuest));
            activity.runOnUiThread(() -> {
                WeHavePhonesSessionLauncher.launch(activity, container, null);
                listener.onStatus(started, false);
            });
            return;
        }

        // A fresh session gets the same verified runtime as Open Battle.net.
        WeHavePhonesRuntimeApplier.Result result = WeHavePhonesRuntimeApplier.apply(activity, WeHavePhonesRuntimeApplier.Stage.BATTLE_NET);
        if (!result.success) {
            post(activity, listener, activity.getString(R.string.wehavephones_stage_apply_failed) + ": " + result.errors, true);
            return;
        }
        File shortcut = ensureShortcut(container, app, batGuest);
        activity.runOnUiThread(() -> {
            if (shortcut != null && WeHavePhonesSessionLauncher.launch(activity, container, shortcut)) {
                listener.onStatus(started, false);
            }
            else {
                listener.onStatus(activity.getString(R.string.wehavephones_stage_launch_failed), true);
            }
        });
    }

    /** Its own shortcut keeps the app's screen size apart from the game's. */
    private static File ensureShortcut(Container container, AddonApp app, String batGuest) {
        File file = WeHavePhonesShortcuts.ensureShortcut(container, app.displayName, batGuest);
        if (file == null) return null;
        Shortcut shortcut = new Shortcut(container, file);
        // x64 Chromium and CurseForge's agent need the stricter memory ordering preset.
        shortcut.putExtra("fexcorePreset", FEXCorePreset.STABILITY);
        shortcut.putExtra("screenSize", AddonApp.DESKTOP_SIZE);
        shortcut.saveData();
        return file;
    }

    private static void post(Activity activity, Listener listener, String message, boolean error) {
        activity.runOnUiThread(() -> listener.onStatus(message, error));
    }
}
