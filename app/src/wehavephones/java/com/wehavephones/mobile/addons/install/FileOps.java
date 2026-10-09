package com.wehavephones.mobile.addons.install;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Locale;

/** Small pure-Java file helpers used by the install pipeline (host-testable). */
public final class FileOps {
    private FileOps() {}

    public static void copyRecursively(File source, File target) throws IOException {
        if (source.isDirectory()) {
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("Cannot create directory: " + target);
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyRecursively(child, new File(target, child.getName()));
                }
            }
            return;
        }
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent);
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
        }
    }

    public static boolean deleteRecursively(File file) {
        if (file == null || !file.exists()) return true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        return file.delete();
    }

    /** Rename within the same filesystem, falling back to copy + delete. */
    public static void move(File source, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent);
        }
        if (target.exists() && !deleteRecursively(target)) {
            throw new IOException("Cannot replace: " + target);
        }
        if (source.renameTo(target)) return;
        copyRecursively(source, target);
        if (!deleteRecursively(source)) {
            throw new IOException("Cannot delete after copy: " + source);
        }
    }

    /** Finds an existing child with the same name ignoring case. */
    public static File findCaseInsensitive(File directory, String name) {
        if (directory == null || name == null) return null;
        File exact = new File(directory, name);
        if (exact.exists()) return exact;
        File[] children = directory.listFiles();
        if (children == null) return null;
        for (File child : children) {
            if (child.getName().equalsIgnoreCase(name)) return child;
        }
        return null;
    }

    public static ArrayList<String> listDirectoryNames(File directory) {
        ArrayList<String> names = new ArrayList<>();
        File[] children = directory.listFiles();
        if (children == null) return names;
        for (File child : children) {
            if (child.isDirectory()) names.add(child.getName());
        }
        return names;
    }

    public static String uuid() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    public static boolean isBackupDir(String name) {
        return name != null && name.toLowerCase(Locale.US).startsWith(".wehavephones-backup-");
    }

    public static boolean isStagingDir(String name) {
        return name != null && name.toLowerCase(Locale.US).startsWith(".wehavephones-staging-");
    }

    public static boolean isDeletedDir(String name) {
        return name != null && name.toLowerCase(Locale.US).startsWith(".wehavephones-deleted-");
    }
}
