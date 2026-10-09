package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.container.Container;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class WeHavePhonesPaths {
    private WeHavePhonesPaths() {}

    public static File imageFsDir(Context context) {
        return new File(context.getFilesDir(), "imagefs");
    }

    public static File wehavephonesDir(Context context) {
        return new File(context.getFilesDir(), "wehavephones");
    }

    public static File snapshotsDir(Context context) {
        return new File(wehavephonesDir(context), "snapshots");
    }

    public static File installerDir(Context context) {
        return new File(wehavephonesDir(context), "installers");
    }

    public static File driveC(Container container) {
        return new File(container.getRootDir(), ".wine/drive_c");
    }

    public static File bnetBat(Container container) {
        return new File(driveC(container), "bnet.bat");
    }

    public static File wowBat(Container container) {
        return new File(driveC(container), "wow.bat");
    }

    public static File setupBat(Container container) {
        return new File(driveC(container), "wehavephones-bnet-setup.bat");
    }

    public static File wowRoot(Container container) {
        return new File(driveC(container), "Program Files (x86)/World of Warcraft");
    }

    public static File wowRootFallback(Container container) {
        return new File(driveC(container), "Program Files/World of Warcraft");
    }

    public static File wowRootFor(Container container) {
        File primary = wowRoot(container);
        return primary.isDirectory() ? primary : wowRootFallback(container);
    }

    public static File bnetExe(Container container) {
        return new File(driveC(container), "Program Files (x86)/Battle.net/Battle.net.exe");
    }

    public static File bnetExeFallback(Container container) {
        return new File(driveC(container), "Program Files/Battle.net/Battle.net.exe");
    }

    public static boolean isBnetInstalled(Container container) {
        return bnetExe(container).isFile() || bnetExeFallback(container).isFile();
    }

    /** Guest directory of the installed Battle.net client. */
    public static String bnetGuestDir(Container container) {
        return bnetExeFallback(container).isFile() && !bnetExe(container).isFile()
                ? WeHavePhonesLaunchPlan.BNET_DIR : WeHavePhonesLaunchPlan.BNET_DIR_X86;
    }

    /** Guest root of the World of Warcraft install (matches {@link #wowRootFor}). */
    public static String wowGuestRoot(Container container) {
        return wowRoot(container).isDirectory() ? WeHavePhonesLaunchPlan.WOW_DIR_X86 : WeHavePhonesLaunchPlan.WOW_DIR;
    }

    /** {@code users/<user>/AppData/<kind>/Battle.net} for the first prefix user that has it. */
    private static File bnetUserDir(Container container, String kind) {
        File users = new File(driveC(container), "users");
        File[] children = users.listFiles();
        File fallback = null;
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory() || child.getName().equalsIgnoreCase("Public")) continue;
                File candidate = new File(child, "AppData/" + kind + "/Battle.net");
                if (candidate.isDirectory()) return candidate;
                if (fallback == null) fallback = candidate;
            }
        }
        return fallback != null ? fallback : new File(users, "xuser/AppData/" + kind + "/Battle.net");
    }

    /** The Battle.net Agent's product database (protobuf, see BattleNetProductDb). */
    public static File bnetProductDb(Container container) {
        return new File(driveC(container), "ProgramData/Battle.net/Agent/product.db");
    }

    public static File bnetLogsDir(Container container) {
        return new File(bnetUserDir(container, "Local"), "Logs");
    }

    public static File bnetRoamingDir(Container container) {
        return bnetUserDir(container, "Roaming");
    }

    public static File launchBat(Container container) {
        return new File(driveC(container), WeHavePhonesLaunchPlan.LAUNCH_BAT_NAME);
    }

    public static File installBat(Container container) {
        return new File(driveC(container), WeHavePhonesLaunchPlan.INSTALL_BAT_NAME);
    }

    public static File startBat(Container container) {
        return new File(driveC(container), WeHavePhonesLaunchPlan.START_BAT_NAME);
    }

    public static File launchOverrides(Context context) {
        return new File(wehavephonesDir(context), WeHavePhonesLaunchPlan.OVERRIDE_FILE_NAME);
    }

    public static File launchRecord(Context context) {
        return new File(wehavephonesDir(context), "launch-last.json");
    }

    public static File launchLog(Context context) {
        return new File(wehavephonesDir(context), "launch.log");
    }

    public static String utcStamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }
}
