package com.wehavephones.mobile;

import android.app.Activity;
import android.os.Environment;

import com.wehavephones.container.Container;
import com.wehavephones.core.FileUtils;
import com.wehavephones.core.HttpUtils;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executors;

public final class BattleNetManager {
    public static final String OFFICIAL_INSTALLER_URL =
            "https://us.battle.net/download/getInstallerForGame?os=win&version=LIVE&gameProgram=BATTLENET_APP";
    public static final String INSTALLER_NAME = "Battle.net-Setup.exe";
    /** The global app cannot sign in to China accounts; China has its own installer. */
    public static final String CHINA_INSTALLER_URL =
            "https://downloader.battlenet.com.cn/download/getInstallerForGame?os=win&version=LIVE&gameProgram=BATTLENET_APP";
    public static final String CHINA_INSTALLER_NAME = "Battle.net-Setup-CN.exe";

    public interface Callback {
        void onReady(File installer, String guestPath, String error);
    }

    private BattleNetManager() {}

    public static boolean isInstalled(Container container) {
        return container != null && WeHavePhonesPaths.isBnetInstalled(container);
    }

    public static String installerUrl(String region) {
        return WowFirstBoot.isChina(region) ? CHINA_INSTALLER_URL : OFFICIAL_INSTALLER_URL;
    }

    /** Distinct names keep a downloaded installer from being reused for the other app. */
    public static String installerName(String region) {
        return WowFirstBoot.isChina(region) ? CHINA_INSTALLER_NAME : INSTALLER_NAME;
    }

    public static File findExistingInstaller(Activity activity, Container container, String name) {
        File publicDownload = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name);
        if (publicDownload.isFile()) return publicDownload;

        if (container != null) {
            for (String[] drive : container.drivesIterator()) {
                File candidate = new File(drive[1], name);
                if (candidate.isFile()) return candidate;
            }
            File driveC = new File(WeHavePhonesPaths.driveC(container), name);
            if (driveC.isFile()) return driveC;
        }

        File privateCopy = new File(WeHavePhonesPaths.installerDir(activity), name);
        return privateCopy.isFile() ? privateCopy : null;
    }

    /** Fetches the installer for {@code region}'s Battle.net app (China's or the global one). */
    public static void prepareInstaller(final Activity activity, final Container container, final String region,
            final Callback callback) {
        final String url = installerUrl(region);
        final String name = installerName(region);
        Executors.newSingleThreadExecutor().execute(() -> {
            File existing = findExistingInstaller(activity, container, name);
            if (existing != null) {
                finish(activity, container, existing, "existing copy", callback);
                return;
            }

            if (!verifyUrl(url)) {
                postError(activity, callback, "Official Battle.net installer URL is not reachable (HEAD " + url + ")");
                return;
            }

            final File destination = chooseDestination(activity, name);
            activity.runOnUiThread(() -> HttpUtils.download(activity, url, destination, (success) -> {
                if (success && destination.isFile() && destination.length() > 0) {
                    Executors.newSingleThreadExecutor().execute(() -> finish(activity, container, destination, "downloaded", callback));
                }
                else {
                    callback.onReady(null, null, "Battle.net installer download failed");
                }
            }));
        });
    }

    public static boolean launchInstaller(Activity activity, Container container, File installer, String guestPath, StringBuilder error) {
        if (container == null) {
            error.append("No container available");
            return false;
        }
        if (installer == null || !installer.isFile()) {
            error.append("Installer file is missing");
            return false;
        }

        String resolvedGuestPath = guestPath;
        if (resolvedGuestPath == null || resolvedGuestPath.isEmpty()) {
            resolvedGuestPath = toGuestPath(container, installer);
        }

        // Prepare the automatic child launch, which does not run bnet.bat.
        boolean freshInstall = !isInstalled(container);
        String bnetFlags = "";
        if (freshInstall) {
            WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.loadOrNull(activity);
            WeHavePhonesRuntimeProfiles.Profile profile = profiles != null ? profiles.getSelectedProfile(activity) : null;
            if (profile == null) {
                error.append("Could not load Battle.net first login runtime settings");
                return false;
            }
            bnetFlags = profile.bnetFlags;
            try {
                WeHavePhonesBnetConfig.prepareFirstLogin(WeHavePhonesPaths.bnetRoamingDir(container));
                // The installer and login window need more height than the
                // low game default. Play reapplies the player's game size.
                container.setScreenSize(WeHavePhonesConfig.BNET_SETUP_SCREEN_SIZE);
                container.saveData();
            }
            catch (Exception e) {
                error.append("Could not prepare Battle.net first login settings");
                return false;
            }
        }

        // Qt's cached GDI surface overwrites CEF after focus. Direct drawing avoids
        // that surface; restrict it to this new client's executable, including
        // the installer child which bypasses bnet.bat.
        String bat = WeHavePhonesLaunchPlan.installerBat(resolvedGuestPath, freshInstall, bnetFlags);
        File batFile = WeHavePhonesPaths.setupBat(container);
        if (!FileUtils.write(batFile, bat.getBytes(StandardCharsets.UTF_8))) {
            error.append("Could not write ").append(batFile.getName());
            return false;
        }

        File shortcut = WeHavePhonesShortcuts.ensureSetupShortcut(container);
        if (shortcut == null) {
            error.append("Could not create the installer shortcut");
            return false;
        }

        if (!WeHavePhonesSessionLauncher.launch(activity, container, shortcut)) {
            error.append("Could not start the installer session");
            return false;
        }
        return true;
    }

    public static boolean verifyUrl(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(url).openConnection();
            connection.setRequestMethod("HEAD");
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setRequestProperty("User-Agent", "WeHavePhonesMobile");
            connection.setRequestProperty("Accept", "*/*");
            int code = connection.getResponseCode();
            return code >= 200 && code < 400;
        }
        catch (Exception e) {
            return false;
        }
        finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void finish(Activity activity, Container container, File installer, String source, Callback callback) {
        if (container == null) {
            postError(activity, callback, "No container available for the installer");
            return;
        }
        try {
            String guestPath = toGuestPath(container, installer);
            if (guestPath.toLowerCase(Locale.US).startsWith("c:\\")) {
                File target = new File(WeHavePhonesPaths.driveC(container), installer.getName());
                if (!installer.getPath().equals(target.getPath())) FileUtils.copy(installer, target);
            }
            final String resolved = guestPath;
            activity.runOnUiThread(() -> callback.onReady(installer, resolved, null));
        }
        catch (Exception e) {
            postError(activity, callback, "Could not stage the installer: " + e.getMessage());
        }
    }

    private static String toGuestPath(Container container, File installer) {
        String path = installer.getAbsolutePath();
        for (String[] drive : container.drivesIterator()) {
            String root = new File(drive[1]).getAbsolutePath();
            if (!path.startsWith(root)) continue;
            String relative = path.substring(root.length()).replaceFirst("^/", "").replace('/', '\\');
            if (relative.isEmpty()) return drive[0] + ":\\" + installer.getName();
            return drive[0] + ":\\" + relative;
        }
        return "C:\\" + installer.getName();
    }

    private static File chooseDestination(Activity activity, String name) {
        File publicDownload = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (publicDownload.isDirectory() && publicDownload.canWrite()) {
            return new File(publicDownload, name);
        }
        File privateDir = WeHavePhonesPaths.installerDir(activity);
        if (!privateDir.isDirectory()) privateDir.mkdirs();
        return new File(privateDir, name);
    }

    private static void postError(Activity activity, final Callback callback, final String message) {
        activity.runOnUiThread(() -> callback.onReady(null, null, message));
    }
}
