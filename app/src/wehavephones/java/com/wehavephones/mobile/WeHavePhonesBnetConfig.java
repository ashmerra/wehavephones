package com.wehavephones.mobile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, text-level edits of {@code %APPDATA%\Battle.net\Battle.net.config}:
 * {@code Client.AutoStartMinimized} for the "start Battle.net minimized" option
 * (it is what the client logs as {@code isMinimizedOnStartup}; it only takes
 * effect with {@code --autostarted}), and {@code Client.GameLaunchWindowBehavior}
 * so Battle.net exits by itself once it has launched the game. The file also
 * holds account state, so it is never re-serialized and never logged. The
 * original values are backed up; turning off minimized restores its backup.
 * Pure java.io (host self-tested).
 */
public final class WeHavePhonesBnetConfig {
    public static final String CONFIG_NAME = "Battle.net.config";
    public static final String BACKUP_NAME = "Battle.net.config.wehavephones-backup";
    public static final String KEY = "AutoStartMinimized";
    /** Battle.net's "When I launch a game" setting; {@value #EXIT_ON_GAME_LAUNCH} is "Exit Battle.net completely". */
    public static final String GAME_LAUNCH_KEY = "GameLaunchWindowBehavior";
    public static final String EXIT_ON_GAME_LAUNCH = "2";
    /** The user's own GameLaunchWindowBehavior from before the first change ({@value #ABSENT} when unset). */
    public static final String GAME_LAUNCH_BACKUP_NAME = "Battle.net.config.GameLaunchWindowBehavior.wehavephones-original";
    private static final String ABSENT = "<absent>";

    private static final Pattern EXISTING = Pattern.compile("(\"" + KEY + "\"\\s*:\\s*\")([^\"]*)(\")");
    private static final Pattern GAME_LAUNCH = Pattern.compile("(\"" + GAME_LAUNCH_KEY + "\"\\s*:\\s*\")([^\"]*)(\")");
    /** The key with its separating comma: the following one if there is one, else the preceding one. */
    private static final Pattern GAME_LAUNCH_ENTRY = Pattern.compile(
            "(,?)\\s*\"" + GAME_LAUNCH_KEY + "\"\\s*:\\s*\"[^\"]*\"(\\s*,)?");
    private static final Pattern CLIENT_OPEN = Pattern.compile("\"Client\"\\s*:\\s*\\{");

    private WeHavePhonesBnetConfig() {}

    /** Seed browser and automatic-login preferences before the installer opens its first login. */
    public static void prepareFirstLogin(File roamingDir) throws IOException {
        File config = new File(roamingDir, CONFIG_NAME);
        // Existing settings and account state always take precedence.
        if (config.exists()) return;
        if (!roamingDir.isDirectory() && !roamingDir.mkdirs()) {
            throw new IOException("Could not create the Battle.net settings directory");
        }
        write(config, ("{\r\n    \"Client\": {\r\n"
                + "        \"HardwareAcceleration\": \"false\",\r\n"
                + "        \"AutoLogin\": \"true\"\r\n    }\r\n}\r\n")
                .getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Returns {@code text} with {@code Client.AutoStartMinimized} set to
     * {@code "true"}/{@code "false"}, or {@code null} if the structure is not
     * recognised (no top-level {@code "Client": {}}).
     */
    public static String setAutoStartMinimized(String text, boolean minimized) {
        String value = minimized ? "true" : "false";
        Matcher existing = EXISTING.matcher(text);
        if (existing.find()) {
            if (existing.group(2).equals(value)) return text;
            return text.substring(0, existing.start(2)) + value + text.substring(existing.end(2));
        }
        Matcher client = CLIENT_OPEN.matcher(text);
        if (!client.find()) return null;
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        String insertion = newline + "        \"" + KEY + "\": \"" + value + "\",";
        return text.substring(0, client.end()) + insertion + text.substring(client.end());
    }

    public static boolean isAutoStartMinimized(String text) {
        Matcher existing = EXISTING.matcher(text);
        return existing.find() && existing.group(2).equalsIgnoreCase("true");
    }

    /**
     * Ensure the key in {@code roamingDir/Battle.net.config}; backs the file
     * up into {@code backupDir} before the first change. Returns a short status
     * (never file contents).
     */
    public static String ensureMinimized(File roamingDir, File backupDir) {
        File config = new File(roamingDir, CONFIG_NAME);
        if (!config.isFile()) return "Battle.net.config not found (first start writes it)";
        try {
            byte[] raw = read(config);
            String text = decode(raw);
            String updated = setAutoStartMinimized(text, true);
            if (updated == null) return "Battle.net.config structure not recognised; left unchanged";
            if (updated.equals(text)) return "AutoStartMinimized already true";
            File backup = new File(backupDir, BACKUP_NAME);
            if (!backup.isFile()) {
                if (!backupDir.isDirectory() && !backupDir.mkdirs()) return "Could not create the config backup directory";
                write(backup, raw);
            }
            write(config, encode(updated, raw));
            return "AutoStartMinimized set to true (backup kept)";
        }
        catch (IOException e) {
            return "Battle.net.config edit failed: " + e.getMessage();
        }
    }

    /**
     * Returns {@code text} with {@code Client.GameLaunchWindowBehavior} set to
     * {@code value}, or removed when {@code value} is null; {@code null} if the
     * structure is not recognised.
     */
    public static String setGameLaunchBehavior(String text, String value) {
        Matcher existing = GAME_LAUNCH.matcher(text);
        if (value == null) {
            if (!existing.find()) return text;
            Matcher entry = GAME_LAUNCH_ENTRY.matcher(text);
            if (!entry.find()) return null;
            String keep = entry.group(2) != null ? entry.group(1) : "";
            return text.substring(0, entry.start()) + keep + text.substring(entry.end());
        }
        if (existing.find()) {
            if (existing.group(2).equals(value)) return text;
            return text.substring(0, existing.start(2)) + value + text.substring(existing.end(2));
        }
        Matcher client = CLIENT_OPEN.matcher(text);
        if (!client.find()) return null;
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        boolean emptyClient = text.substring(client.end()).trim().startsWith("}");
        String insertion = newline + "        \"" + GAME_LAUNCH_KEY + "\": \"" + value + "\"" + (emptyClient ? "" : ",");
        return text.substring(0, client.end()) + insertion + text.substring(client.end());
    }

    /** The current GameLaunchWindowBehavior value, or null when unset. */
    public static String gameLaunchBehavior(String text) {
        Matcher existing = GAME_LAUNCH.matcher(text);
        return existing.find() ? existing.group(2) : null;
    }

    /**
     * Let Battle.net close itself once it has launched the game.
     * The original value is kept in {@code backupDir}. Takes effect
     * when Battle.net next starts; a running client keeps its loaded setting.
     * Returns a short status (never file contents).
     */
    public static String applyExitOnGameLaunch(File roamingDir, File backupDir) {
        File config = new File(roamingDir, CONFIG_NAME);
        File backup = new File(backupDir, GAME_LAUNCH_BACKUP_NAME);
        if (!config.isFile()) return "Battle.net.config not found (first start writes it)";
        try {
            byte[] raw = read(config);
            String text = decode(raw);
            String current = gameLaunchBehavior(text);
            if (EXIT_ON_GAME_LAUNCH.equals(current)) return GAME_LAUNCH_KEY + " already exits on game launch";
            if (!backup.isFile()) {
                if (!backupDir.isDirectory() && !backupDir.mkdirs()) return "Could not create the config backup directory";
                write(backup, (current != null ? current : ABSENT).getBytes(StandardCharsets.UTF_8));
            }
            String updated = setGameLaunchBehavior(text, EXIT_ON_GAME_LAUNCH);
            if (updated == null) return "Battle.net.config structure not recognised; left unchanged";
            if (!updated.equals(text)) write(config, encode(updated, raw));
            return GAME_LAUNCH_KEY + " set to exit on game launch";
        }
        catch (IOException e) {
            return "Battle.net.config edit failed: " + e.getMessage();
        }
    }

    /** Restore the backup taken by {@link #ensureMinimized}, if any. */
    public static boolean restore(File roamingDir, File backupDir) {
        File backup = new File(backupDir, BACKUP_NAME);
        if (!backup.isFile()) return false;
        try {
            write(new File(roamingDir, CONFIG_NAME), read(backup));
            return backup.delete();
        }
        catch (IOException e) {
            return false;
        }
    }

    private static boolean hasBom(byte[] raw) {
        return raw.length >= 3 && (raw[0] & 0xff) == 0xEF && (raw[1] & 0xff) == 0xBB && (raw[2] & 0xff) == 0xBF;
    }

    private static String decode(byte[] raw) {
        int skip = hasBom(raw) ? 3 : 0;
        return new String(raw, skip, raw.length - skip, StandardCharsets.UTF_8);
    }

    /** {@code text} as bytes, keeping the BOM of {@code original}. */
    private static byte[] encode(String text, byte[] original) {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        if (!hasBom(original)) return body;
        byte[] output = new byte[body.length + 3];
        output[0] = (byte)0xEF;
        output[1] = (byte)0xBB;
        output[2] = (byte)0xBF;
        System.arraycopy(body, 0, output, 3, body.length);
        return output;
    }

    private static byte[] read(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[(int)file.length()];
            int total = 0;
            while (total < buffer.length) {
                int count = input.read(buffer, total, buffer.length - total);
                if (count < 0) break;
                total += count;
            }
            if (total == buffer.length) return buffer;
            byte[] trimmed = new byte[total];
            System.arraycopy(buffer, 0, trimmed, 0, total);
            return trimmed;
        }
    }

    private static void write(File file, byte[] data) throws IOException {
        File temp = new File(file.getPath() + ".tmp");
        try (OutputStream output = new FileOutputStream(temp)) {
            output.write(data);
        }
        if (!temp.renameTo(file)) {
            try (OutputStream output = new FileOutputStream(file)) {
                output.write(data);
            }
            temp.delete();
        }
    }
}
