package com.wehavephones.mobile.addons.required;

import com.wehavephones.mobile.addons.install.FileOps;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * WeHavePhonesControllerSetup, the app's own companion addon to ConsolePort
 * ({@code assets/wehavephones/addons/WeHavePhonesControllerSetup/}). On the first
 * login without an active ConsolePort device, it picks the preset that
 * matches the connected controller and applies the setup guide's
 * recommended layout, so a fresh install is playable instead of empty
 * (docs/WEHAVEPHONES_ADDONS.md §9).
 *
 * <p>The folder name must not start with {@code ConsolePort_}: suite
 * updates remove every such folder the new release does not ship. The
 * TOC's {@code Interface} line is copied from the installed ConsolePort
 * so both load on the same clients.
 *
 * <p>Pure Java (host-testable).
 */
public final class ControllerSetupAddon {
    public static final String FOLDER = "WeHavePhonesControllerSetup";
    public static final String TOC = FOLDER + ".toc";
    /** Asset files, relative to the addon folder. */
    public static final String[] FILES = {TOC, "Setup.lua"};

    private static final String DISABLED_SUFFIX = ".disabled";
    private static final String INTERFACE_PREFIX = "## interface:";

    private ControllerSetupAddon() {}

    /** The companion folder, with or without {@code .disabled}. */
    public static boolean isFolder(String folderName) {
        if (folderName == null) return false;
        String name = folderName.toLowerCase(Locale.US);
        if (name.endsWith(DISABLED_SUFFIX)) name = name.substring(0, name.length() - DISABLED_SUFFIX.length());
        return name.equals(FOLDER.toLowerCase(Locale.US));
    }

    /**
     * Brings the installed copy in line with the bundled files; returns true
     * when anything was written. A user-disabled copy is left alone.
     *
     * @param bundled file name (one of {@link #FILES}) to bundled content
     */
    public static boolean sync(File addonsDir, Map<String, byte[]> bundled) throws IOException {
        if (addonsDir == null || !addonsDir.isDirectory()) throw new IOException("No AddOns directory");
        if (FileOps.findCaseInsensitive(addonsDir, FOLDER + DISABLED_SUFFIX) != null) return false;

        Map<String, byte[]> expected = new LinkedHashMap<>(bundled);
        byte[] toc = expected.get(TOC);
        if (toc == null) throw new IOException("Bundled " + TOC + " is missing");
        String consolePortInterface = interfaceLine(consolePortToc(addonsDir));
        if (consolePortInterface != null) expected.put(TOC, withInterfaceLine(toc, consolePortInterface));

        File folder = FileOps.findCaseInsensitive(addonsDir, FOLDER);
        if (folder == null) folder = new File(addonsDir, FOLDER);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create " + folder);

        boolean changed = false;
        for (Map.Entry<String, byte[]> entry : expected.entrySet()) {
            File target = new File(folder, entry.getKey());
            if (target.isFile() && Arrays.equals(read(target), entry.getValue())) continue;
            File tmp = new File(folder, entry.getKey() + ".tmp");
            try (OutputStream output = new FileOutputStream(tmp)) {
                output.write(entry.getValue());
            }
            if (!tmp.renameTo(target)) {
                FileOps.deleteRecursively(tmp);
                throw new IOException("Cannot write " + target);
            }
            changed = true;
        }
        return changed;
    }

    /** The raw {@code ## Interface:} line of a TOC, or null. */
    static String interfaceLine(File toc) {
        if (toc == null || !toc.isFile()) return null;
        try {
            for (String line : new String(read(toc), StandardCharsets.UTF_8).split("\r?\n")) {
                String trimmed = line.trim();
                if (trimmed.toLowerCase(Locale.US).startsWith(INTERFACE_PREFIX)) return trimmed;
            }
        }
        catch (IOException ignored) {}
        return null;
    }

    static byte[] withInterfaceLine(byte[] toc, String interfaceLine) {
        String[] lines = new String(toc, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().toLowerCase(Locale.US).startsWith(INTERFACE_PREFIX)) {
                lines[i] = interfaceLine + (lines[i].endsWith("\r") ? "\r" : "");
            }
        }
        return String.join("\n", lines).getBytes(StandardCharsets.UTF_8);
    }

    private static File consolePortToc(File addonsDir) {
        File core = FileOps.findCaseInsensitive(addonsDir, ConsolePort.CORE_FOLDER);
        if (core == null) core = FileOps.findCaseInsensitive(addonsDir, ConsolePort.CORE_FOLDER + DISABLED_SUFFIX);
        return core != null ? FileOps.findCaseInsensitive(core, ConsolePort.CORE_FOLDER + ".toc") : null;
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }
}
