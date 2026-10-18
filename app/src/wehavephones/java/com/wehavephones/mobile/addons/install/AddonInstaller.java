package com.wehavephones.mobile.addons.install;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Atomic addon install/update/remove pipeline modeled on WowUp's:
 * backup -> swap -> rollback on failure -> delete backup on success.
 * Pure Java except for commons-compress-free FileOps (host-testable).
 */
public final class AddonInstaller {
    /** Serializes AddOns mutations between the addon manager and the ConsolePort updater. */
    public static final Object LOCK = new Object();

    /**
     * Staging folders of imports waiting for the player's choice. Every
     * {@link #recoverInterrupted} caller keeps them, so neither a rescan nor a
     * ConsolePort install deletes an import before it is confirmed.
     */
    private static final Set<File> HELD_STAGING = ConcurrentHashMap.newKeySet();

    private AddonInstaller() {}

    public static final class Request {
        public final File addonsDir;
        public final File stagingRoot;
        public final List<DiscoveredAddon> discovered;
        public final List<String> previousFolders;
        public final boolean keepDisabled;

        public Request(File addonsDir, File stagingRoot, List<DiscoveredAddon> discovered,
                       List<String> previousFolders, boolean keepDisabled) {
            this.addonsDir = addonsDir;
            this.stagingRoot = stagingRoot;
            this.discovered = discovered;
            this.previousFolders = previousFolders;
            this.keepDisabled = keepDisabled;
        }
    }

    public static final class Result {
        public final ArrayList<String> installedFolders = new ArrayList<>();
        public final File backupDir;
        public final File stagingRoot;

        Result(File backupDir, File stagingRoot) {
            this.backupDir = backupDir;
            this.stagingRoot = stagingRoot;
        }
    }

    public static Result install(Request request) throws IOException {
        if (request.addonsDir == null || !request.addonsDir.isDirectory()) {
            throw new IOException("AddOns directory is missing");
        }
        if (request.discovered == null || request.discovered.isEmpty()) {
            throw new IOException("Archive contains no installable addon folder");
        }
        HashSet<String> seen = new HashSet<>();
        for (DiscoveredAddon addon : request.discovered) {
            String key = addon.folderName.toLowerCase(Locale.US);
            if (!seen.add(key)) {
                throw new IOException("Conflicting addon folders in archive: " + addon.folderName);
            }
        }

        File backupDir = new File(request.addonsDir, ".wehavephones-backup-" + FileOps.uuid());
        if (!backupDir.mkdirs()) throw new IOException("Cannot create backup directory");

        ArrayList<String> targets = new ArrayList<>();
        if (request.previousFolders != null) {
            for (String folder : request.previousFolders) {
                if (folder != null && !folder.isEmpty() && !targets.contains(folder)) targets.add(folder);
            }
        }
        for (DiscoveredAddon addon : request.discovered) {
            if (!targets.contains(addon.folderName)) targets.add(addon.folderName);
        }

        Result result = new Result(backupDir, request.stagingRoot);
        try {
            for (String name : targets) {
                for (File existing : existingVariants(request.addonsDir, name)) {
                    FileOps.copyRecursively(existing, new File(backupDir, existing.getName()));
                }
            }
            for (String name : targets) {
                for (File existing : existingVariants(request.addonsDir, name)) {
                    FileOps.deleteRecursively(existing);
                }
            }
            for (DiscoveredAddon addon : request.discovered) {
                File target = new File(request.addonsDir, addon.folderName);
                FileOps.move(addon.directory, target);
                result.installedFolders.add(addon.folderName);
            }
            if (request.keepDisabled) {
                setEnabled(request.addonsDir, result.installedFolders, false);
            }
            return result;
        }
        catch (Exception e) {
            rollback(request.addonsDir, result);
            if (e instanceof IOException) throw (IOException)e;
            throw new IOException("Install failed: " + e.getMessage(), e);
        }
    }

    /** Both the active folder and its .disabled variant, when they exist. */
    private static List<File> existingVariants(File addonsDir, String name) {
        ArrayList<File> variants = new ArrayList<>();
        File active = FileOps.findCaseInsensitive(addonsDir, name);
        if (active != null) variants.add(active);
        File disabled = FileOps.findCaseInsensitive(addonsDir, name + ".disabled");
        if (disabled != null && (active == null || !disabled.getPath().equals(active.getPath()))) {
            variants.add(disabled);
        }
        return variants;
    }

    /** Restores the backup and removes partially installed folders. */
    public static void rollback(File addonsDir, Result result) {
        if (result == null) return;
        for (String folder : result.installedFolders) {
            FileOps.deleteRecursively(new File(addonsDir, folder));
            FileOps.deleteRecursively(new File(addonsDir, folder + ".disabled"));
        }
        if (result.backupDir != null && result.backupDir.isDirectory()) {
            File[] entries = result.backupDir.listFiles();
            if (entries != null) {
                for (File entry : entries) {
                    try {
                        FileOps.move(entry, new File(addonsDir, entry.getName()));
                    }
                    catch (IOException ignored) {}
                }
            }
            FileOps.deleteRecursively(result.backupDir);
        }
        if (result.stagingRoot != null) FileOps.deleteRecursively(result.stagingRoot);
    }

    /** Deletes the rollback backup and staging after a successful install. */
    public static void commit(Result result) {
        if (result == null) return;
        FileOps.deleteRecursively(result.backupDir);
        if (result.stagingRoot != null) FileOps.deleteRecursively(result.stagingRoot);
    }

    /**
     * Renames folders to <name>.disabled (or back) for the enable/disable convention.
     * The given names may be either form: the scanner stores the physical folder
     * name, so a disabled record arrives as "Addon.disabled".
     */
    public static void setEnabled(File addonsDir, List<String> folders, boolean enabled) throws IOException {
        for (String folder : folders) {
            String name = activeName(folder);
            if (name.isEmpty()) continue;
            if (enabled) {
                if (FileOps.findCaseInsensitive(addonsDir, name) != null) {
                    File disabledVariant = FileOps.findCaseInsensitive(addonsDir, name + ".disabled");
                    if (disabledVariant != null && !disabledVariant.getName().equalsIgnoreCase(name)) {
                        FileOps.deleteRecursively(disabledVariant);
                    }
                    continue;
                }
                File disabled = FileOps.findCaseInsensitive(addonsDir, name + ".disabled");
                if (disabled == null) continue;
                if (!disabled.renameTo(new File(addonsDir, name))) {
                    throw new IOException("Could not enable addon folder: " + name);
                }
            }
            else {
                File active = FileOps.findCaseInsensitive(addonsDir, name);
                if (active == null) continue;
                if (active.getName().toLowerCase(Locale.US).endsWith(".disabled")) continue;
                File disabledVariant = FileOps.findCaseInsensitive(addonsDir, name + ".disabled");
                if (disabledVariant != null && !disabledVariant.equals(active)) {
                    FileOps.deleteRecursively(disabledVariant);
                }
                if (!active.renameTo(new File(addonsDir, name + ".disabled"))) {
                    throw new IOException("Could not disable addon folder: " + name);
                }
            }
        }
    }

    /** The enabled folder name, whether the caller passed "Addon" or "Addon.disabled". */
    private static String activeName(String folder) {
        if (folder == null) return "";
        String suffix = ".disabled";
        return folder.toLowerCase(Locale.US).endsWith(suffix)
                ? folder.substring(0, folder.length() - suffix.length()) : folder;
    }

    /**
     * Moves the given folders (active or .disabled variants) into a hidden
     * backup directory and returns it. The caller deletes it after the next
     * successful operation.
     */
    public static File removeToBackup(File addonsDir, List<String> folders) throws IOException {
        File backupDir = new File(addonsDir, ".wehavephones-deleted-" + FileOps.uuid());
        if (!backupDir.mkdirs()) throw new IOException("Cannot create delete backup directory");
        boolean movedAny = false;
        for (String name : folders) {
            File active = FileOps.findCaseInsensitive(addonsDir, name);
            File disabled = FileOps.findCaseInsensitive(addonsDir, name + ".disabled");
            for (File candidate : new File[]{active, disabled}) {
                if (candidate == null || !candidate.exists()) continue;
                FileOps.move(candidate, new File(backupDir, candidate.getName()));
                movedAny = true;
            }
        }
        if (!movedAny) FileOps.deleteRecursively(backupDir);
        return backupDir;
    }

    /** Keeps {@code staging} through {@link #recoverInterrupted} until it is released or discarded. */
    public static void holdStaging(File staging) {
        if (staging != null) HELD_STAGING.add(staging.getAbsoluteFile());
    }

    /** Lets the next {@link #recoverInterrupted} delete {@code staging} again. */
    public static void releaseStaging(File staging) {
        if (staging != null) HELD_STAGING.remove(staging.getAbsoluteFile());
    }

    /** Deletes a held staging folder and releases it. */
    public static void discardStaging(File staging) {
        if (staging == null) return;
        FileOps.deleteRecursively(staging);
        releaseStaging(staging);
    }

    /**
     * Crash recovery: deletes stale staging dirs and restores backups whose
     * target folder is missing (a crash mid-swap). Held staging dirs are kept.
     */
    public static void recoverInterrupted(File addonsDir) {
        if (addonsDir == null || !addonsDir.isDirectory()) return;
        File[] children = addonsDir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            String name = child.getName();
            if (FileOps.isStagingDir(name)) {
                if (HELD_STAGING.contains(child.getAbsoluteFile())) continue;
                FileOps.deleteRecursively(child);
            }
            else if (FileOps.isBackupDir(name)) {
                File[] entries = child.listFiles();
                if (entries != null) {
                    for (File entry : entries) {
                        if (FileOps.findCaseInsensitive(addonsDir, entry.getName()) == null) {
                            try {
                                FileOps.move(entry, new File(addonsDir, entry.getName()));
                            }
                            catch (IOException ignored) {}
                        }
                    }
                }
                FileOps.deleteRecursively(child);
            }
        }
    }
}
