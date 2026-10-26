package com.wehavephones.mobile;

import java.io.File;
import java.util.Map;

/**
 * A World of Warcraft folder that Battle.net installed outside
 * {@code C:\Program Files (x86)}, for example on a drive mapped to an SD card.
 * Battle.net records the folder in product.db as a Windows path, and the
 * container's drive letters map it to an Android folder.
 *
 * Pure Java (host self-tested in BattleNetGamesSelfTest).
 */
public final class WowInstallLocation {
    /** Windows path with backslashes and no trailing separator, for example {@code F:\Games\World of Warcraft}. */
    public final String guestPath;
    public final File hostDir;

    private WowInstallLocation(String guestPath, File hostDir) {
        this.guestPath = guestPath;
        this.hostDir = hostDir;
    }

    /**
     * The first WoW product in {@code db} whose recorded folder exists on a
     * mapped drive, or null. {@code drives} maps upper-case drive letters to
     * Android folders.
     */
    public static WowInstallLocation find(BattleNetProductDb db, Map<Character, File> drives) {
        if (db == null || drives == null) return null;
        for (BattleNetProductDb.Install install : db.gameInstalls()) {
            String guestPath = normalize(install.installPath);
            File hostDir = toHost(guestPath, drives);
            if (hostDir != null && hostDir.isDirectory()) return new WowInstallLocation(guestPath, hostDir);
        }
        return null;
    }

    /** {@code D:/Games/World of Warcraft/} becomes {@code D:\Games\World of Warcraft}. Returns "" for non-drive paths. */
    static String normalize(String path) {
        if (path == null) return "";
        String guestPath = path.trim().replace('/', '\\');
        while (guestPath.length() > 3 && guestPath.endsWith("\\")) guestPath = guestPath.substring(0, guestPath.length() - 1);
        if (guestPath.length() < 2 || guestPath.charAt(1) != ':' || !Character.isLetter(guestPath.charAt(0))) return "";
        return Character.toUpperCase(guestPath.charAt(0)) + guestPath.substring(1);
    }

    /** Android folder for a normalized Windows path, or null when its drive is not mapped. */
    static File toHost(String guestPath, Map<Character, File> drives) {
        if (guestPath == null || guestPath.isEmpty()) return null;
        File root = drives.get(guestPath.charAt(0));
        if (root == null) return null;
        String relative = guestPath.substring(2).replace('\\', '/');
        while (relative.startsWith("/")) relative = relative.substring(1);
        return relative.isEmpty() ? root : new File(root, relative);
    }
}
