package com.wehavephones.core;

import com.wehavephones.xenvironment.ImageFs;

import java.io.File;

public final class SessionLogs {
    public static final String CONTAINER_LOG_DIR = "/storage/emulated/0/Download/WeHavePhones";
    private static final String HOST_LOG_DIR = "/storage/emulated/0/Download/WeHavePhones";

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

    public static File getGuestLogFile(ImageFs imageFs) {
        return new File(getLogDir(imageFs), "guest.log");
    }
}
