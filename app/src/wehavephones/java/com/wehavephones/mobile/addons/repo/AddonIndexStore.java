package com.wehavephones.mobile.addons.repo;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Atomic JSON persistence for the addon index at
 * {@code <filesDir>/wehavephones/addons.json}. A corrupt index is preserved as
 * {@code addons.json.bad-<timestamp>} and rebuilt from a filesystem scan.
 */
public final class AddonIndexStore {
    private final File file;

    public AddonIndexStore(Context context) {
        File directory = new File(context.getFilesDir(), "wehavephones");
        if (!directory.isDirectory()) {
            //noinspection ResultOfMethodCallIgnored
            directory.mkdirs();
        }
        this.file = new File(directory, "addons.json");
    }

    public File file() {
        return file;
    }

    public synchronized AddonIndex load() {
        if (!file.isFile()) return new AddonIndex();
        String text = null;
        try {
            text = readFile(file);
        }
        catch (IOException ignored) {}
        if (text == null) return new AddonIndex();
        try {
            return AddonIndex.fromJsonString(text);
        }
        catch (Json.JsonException e) {
            File bad = new File(file.getParentFile(), "addons.json.bad-" + System.currentTimeMillis());
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(bad);
            return new AddonIndex();
        }
    }

    public synchronized void save(AddonIndex index) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        File temp = new File(file.getPath() + ".tmp");
        byte[] bytes = index.toJsonString().getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream output = new FileOutputStream(temp)) {
            output.write(bytes);
            output.flush();
            output.getFD().sync();
        }
        if (!temp.renameTo(file)) {
            if (!file.delete() || !temp.renameTo(file)) {
                throw new IOException("Cannot replace " + file);
            }
        }
    }

    private static String readFile(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int)Math.min(file.length(), 16 * 1024 * 1024)];
            int total = 0;
            while (total < buffer.length) {
                int read = input.read(buffer, total, buffer.length - total);
                if (read == -1) break;
                total += read;
            }
            return new String(buffer, 0, total, StandardCharsets.UTF_8);
        }
    }
}
