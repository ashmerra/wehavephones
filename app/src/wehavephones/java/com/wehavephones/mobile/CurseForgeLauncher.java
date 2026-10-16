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
import java.util.concurrent.Executors;

/**
 * Runs CurseForge's own Windows app in the game's container. It manages
 * addons for the WoW install itself, so no CurseForge API key is needed.
 * The app is downloaded from CurseForge's update server on first use and is
 * never bundled. See {@link CurseForgeSetup} for what the container needs.
 */
public final class CurseForgeLauncher {
    public static final String BAT_NAME = "wehavephones-curseforge.bat";
    public static final String BAT_GUEST = "C:\\" + BAT_NAME;
    public static final String SHORTCUT_NAME = "CurseForge";
    /** A desktop app needs more room than the low game default. */
    public static final String SCREEN_SIZE = WeHavePhonesConfig.BNET_SETUP_SCREEN_SIZE;
    private static final int FEED_MAX_BYTES = 64 * 1024;

    public interface Listener {
        void onStatus(String message, boolean error);
    }

    private CurseForgeLauncher() {}

    public static File stageDir(Container container) {
        return new File(WeHavePhonesPaths.driveC(container), CurseForgeSetup.STAGE_DIR);
    }

    /** The installed app in the first prefix user that has it. */
    public static File findApp(Container container) {
        File[] users = new File(WeHavePhonesPaths.driveC(container), "users").listFiles();
        if (users == null) return null;
        for (File user : users) {
            File candidate = new File(user, "AppData/Local/" + CurseForgeSetup.APP_DIR + "/" + CurseForgeSetup.APP_EXE);
            if (candidate.isFile()) return candidate;
        }
        return null;
    }

    public static void open(final Activity activity, final Listener listener) {
        if (WowImportService.isRunning()) {
            listener.onStatus(activity.getString(R.string.wehavephones_import_wait), true);
            return;
        }
        if (ProcessScanner.findWowProcess() != null) {
            listener.onStatus(activity.getString(R.string.wehavephones_curseforge_close_game), true);
            return;
        }
        if (OneButtonLauncher.isRunning() || RuntimeStageController.isRunning()) {
            listener.onStatus(activity.getString(R.string.wehavephones_launch_in_progress), true);
            return;
        }
        listener.onStatus(activity.getString(R.string.wehavephones_curseforge_starting), false);
        Executors.newSingleThreadExecutor().execute(() -> {
            Container container = WeHavePhonesContainers.resolve(activity);
            if (container == null) {
                post(activity, listener, activity.getString(R.string.wehavephones_stage_setup_required), true);
                return;
            }
            File stage = stageDir(container);
            if (!stageHybridDlls(activity, container, stage)) {
                post(activity, listener, activity.getString(R.string.wehavephones_curseforge_dlls_failed), true);
                return;
            }
            boolean installed = findApp(container) != null;
            CurseForgeSetup.Release latest = fetchLatest();
            File versionFile = new File(stage, CurseForgeSetup.VERSION_FILE);
            String installedVersion = versionFile.isFile() ? FileUtils.readString(versionFile) : null;
            if (installed && !CurseForgeSetup.needsUpdate(installedVersion, latest)) {
                start(activity, container, null, listener);
                return;
            }
            if (latest == null) {
                // An installed app still opens when the update server is unreachable.
                if (installed) start(activity, container, null, listener);
                else post(activity, listener, activity.getString(R.string.wehavephones_curseforge_feed_failed), true);
                return;
            }
            File installer = new File(stage, CurseForgeSetup.INSTALLER_NAME);
            if (installer.isFile() && matches(installer, latest)) {
                start(activity, container, latest.version, listener);
                return;
            }
            installer.delete();
            post(activity, listener, activity.getString(installed ? R.string.wehavephones_curseforge_updating
                    : R.string.wehavephones_curseforge_downloading, latest.version, latest.size / (1024 * 1024)), false);
            activity.runOnUiThread(() -> HttpUtils.download(activity, latest.url, installer, (success) ->
                    Executors.newSingleThreadExecutor().execute(() -> {
                        if (!success || !matches(installer, latest)) {
                            installer.delete();
                            post(activity, listener, activity.getString(R.string.wehavephones_curseforge_download_failed), true);
                            return;
                        }
                        start(activity, container, latest.version, listener);
                    })));
        });
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
        for (String dll : CurseForgeSetup.HYBRID_DLLS) {
            File from = new File(source, dll);
            File to = new File(stage, dll);
            if (!from.isFile()) return false;
            if (to.isFile() && to.length() == from.length()) continue;
            if (!FileUtils.copy(from, to)) return false;
        }
        return true;
    }

    /** Presets CurseForge to exit when closed, for every prefix user. */
    private static void configureSettings(Container container) {
        File[] users = new File(WeHavePhonesPaths.driveC(container), "users").listFiles();
        if (users == null) return;
        for (File user : users) {
            if (!new File(user, "AppData").isDirectory() || user.getName().equalsIgnoreCase("Public")) continue;
            File settings = new File(user, "AppData/Roaming/" + CurseForgeSetup.SETTINGS_FILE);
            File directory = settings.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) continue;
            String current = settings.isFile() ? FileUtils.readString(settings) : null;
            String updated = CurseForgeSetup.configureSettings(current);
            if (updated != null && !updated.equals(current)) {
                FileUtils.write(settings, updated.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static CurseForgeSetup.Release fetchLatest() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(CurseForgeSetup.FEED_URL).openConnection();
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
                return CurseForgeSetup.parseFeed(new String(buffer, 0, total, StandardCharsets.UTF_8));
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
    private static boolean matches(File file, CurseForgeSetup.Release release) {
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

    private static void start(Activity activity, Container container, String installVersion, Listener listener) {
        configureSettings(container);
        if (!FileUtils.write(new File(WeHavePhonesPaths.driveC(container), BAT_NAME),
                CurseForgeSetup.bat(installVersion).getBytes(StandardCharsets.UTF_8))) {
            post(activity, listener, activity.getString(R.string.wehavephones_launch_write_failed, BAT_NAME), true);
            return;
        }
        String started = activity.getString(installVersion != null
                ? R.string.wehavephones_curseforge_installing : R.string.wehavephones_curseforge_opened);

        // A running session (Battle.net, for example) gets the app in-session.
        XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
        WinHandler winHandler = session != null && session.isSessionActive() ? session.getWinHandler() : null;
        if (winHandler != null) {
            winHandler.exec(WeHavePhonesLaunchPlan.winHandlerCommand(BAT_GUEST));
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
        File shortcut = ensureShortcut(container);
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
    private static File ensureShortcut(Container container) {
        File file = WeHavePhonesShortcuts.ensureShortcut(container, SHORTCUT_NAME, BAT_GUEST);
        if (file == null) return null;
        Shortcut shortcut = new Shortcut(container, file);
        // The x64 mods agent and Chromium need the stricter memory ordering preset.
        shortcut.putExtra("fexcorePreset", FEXCorePreset.STABILITY);
        shortcut.putExtra("screenSize", SCREEN_SIZE);
        shortcut.saveData();
        return file;
    }

    private static void post(Activity activity, Listener listener, String message, boolean error) {
        activity.runOnUiThread(() -> listener.onStatus(message, error));
    }
}
