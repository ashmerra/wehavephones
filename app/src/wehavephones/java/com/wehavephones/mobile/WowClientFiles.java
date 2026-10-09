package com.wehavephones.mobile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * WoW client files: CVars in {@code WTF/Config.wtf} (see {@link WowFirstBoot})
 * and the markers in {@code Logs/} that show how far the client got. Plain
 * Java so the host self-test can exercise it.
 */
public final class WowClientFiles {
    /** Logs/gx.log: the D3D12 device exists; the login screen is built after it. */
    public static final String DEVICE_READY_MARKER = "Device Create Successful";

    private WowClientFiles() {}

    /** Value of {@code SET <cvar> "<value>"}, or null when the CVar is not set. */
    public static String cvarValue(String config, String cvar) {
        for (String line : config.split("\r?\n")) {
            if (!cvar.equalsIgnoreCase(cvarName(line))) continue;
            int open = line.indexOf('"');
            int close = line.lastIndexOf('"');
            return open >= 0 && close > open ? line.substring(open + 1, close) : "";
        }
        return null;
    }

    private static String cvarName(String line) {
        String trimmed = line.trim();
        if (!trimmed.regionMatches(true, 0, "SET ", 0, 4)) return null;
        String rest = trimmed.substring(4).trim();
        int space = rest.indexOf(' ');
        return space > 0 ? rest.substring(0, space) : rest;
    }

    /** True when the first {@code maxBytes} of {@code file} contain {@code marker}. */
    public static boolean fileContains(File file, String marker, int maxBytes) {
        if (!file.isFile()) return false;
        byte[] buffer = new byte[(int)Math.min(maxBytes, file.length())];
        int read = 0;
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while (read < buffer.length && (count = input.read(buffer, read, buffer.length - read)) > 0) read += count;
        }
        catch (IOException e) {
            return false;
        }
        return new String(buffer, 0, read, StandardCharsets.ISO_8859_1).contains(marker);
    }
}
