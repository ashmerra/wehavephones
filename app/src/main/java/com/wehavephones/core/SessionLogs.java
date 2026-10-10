package com.wehavephones.core;

import com.wehavephones.xenvironment.ImageFs;

import java.io.File;
import java.util.Arrays;

public final class SessionLogs {
    public static final String CONTAINER_LOG_DIR = "/storage/emulated/0/Download/WeHavePhones";
    private static final String HOST_LOG_DIR = "/storage/emulated/0/Download/WeHavePhones";
    // Rotated wine-<yyyyMMdd-HHmmss>.log files kept besides the live wine.log.
    public static final int ROTATED_WINE_LOGS_KEPT = 10;

    private SessionLogs() {}

    public static File getLogDir(ImageFs imageFs) {
        return chooseLogDir(new File(HOST_LOG_DIR),
                new File(imageFs.getRootDir().getParentFile(), "wehavephones/logs"));
    }

    static File chooseLogDir(File publicDir, File privateDir) {
        // Secure Folder cannot write the primary user's shared Downloads.
        // Fall back to this app instance's private files instead.
        if ((publicDir.isDirectory() || publicDir.mkdirs()) && publicDir.canWrite()) {
            return publicDir;
        }
        return privateDir;
    }

    public static File prepareLogDir(ImageFs imageFs) {
        File logDir = getLogDir(imageFs);
        logDir.mkdirs();
        return logDir;
    }

    /** Delete all but the newest {@code keep} rotated wine logs; their names sort by time. */
    public static void pruneRotatedWineLogs(File logDir, int keep) {
        File[] rotated = logDir.listFiles((dir, name) -> name.matches("wine-\\d{8}-\\d{6}\\.log"));
        if (rotated == null || rotated.length <= keep) return;
        Arrays.sort(rotated, (a, b) -> b.getName().compareTo(a.getName()));
        for (int i = keep; i < rotated.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            rotated[i].delete();
        }
    }

    public static File getGuestLogFile(ImageFs imageFs) {
        return new File(getLogDir(imageFs), "guest.log");
    }
}
