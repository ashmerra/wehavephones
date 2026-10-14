package com.wehavephones.mobile;

import java.io.File;

/**
 * Settings > Start Input Bridge: starts the user's own copy of
 * Input Bridge's Windows helper (ib.exe) in the session. WeHavePhones does not
 * include Input Bridge: the user installs its Android app, which copies the
 * helper to {@code Download/InputBridge/Installer} ("Copy installer to root
 * folder" with the default Download root). ib.exe then sends the overlay's
 * keys and mouse to the game over 127.0.0.1.
 *
 * <p>Not related to {@link GlueInputBridge}. Pure Java (host-testable).
 */
public final class InputBridgeCompanion {
    public static final String INSTALLER_DIR = "InputBridge/Installer";
    public static final String EXE_NAME = "ib.exe";

    private InputBridgeCompanion() {}

    /** Where Input Bridge copies ib.exe under the public Download folder. */
    public static File hostExe(File downloadDir) {
        return new File(new File(downloadDir, INSTALLER_DIR), EXE_NAME);
    }

    /**
     * Guest path of {@code hostFile} through the container's drive mappings
     * ({@code [letter, host path]}, as Container.drivesIterator yields them),
     * or null when no drive contains it. Paths with a space are refused as
     * well: WinHandler.exec splits its command at the first space.
     */
    public static String guestPath(File hostFile, Iterable<String[]> drives) {
        String host = hostFile.getPath();
        String best = null;
        int bestLength = -1;
        for (String[] drive : drives) {
            String root = trimSlashes(drive[1]);
            if (root.isEmpty() || root.length() <= bestLength || !host.startsWith(root + "/")) continue;
            best = drive[0].toUpperCase() + ":\\" + host.substring(root.length() + 1).replace('/', '\\');
            bestLength = root.length();
        }
        return best != null && best.indexOf(' ') < 0 ? best : null;
    }

    private static String trimSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') end--;
        return path.substring(0, end);
    }
}
