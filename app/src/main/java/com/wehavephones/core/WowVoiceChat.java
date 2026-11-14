package com.wehavephones.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Turns WoW's voice chat process off or back on for a Wine prefix.
 * <p>
 * WoW starts {@code Utils\WowVoiceProxy.exe} for Vivox voice chat even when
 * nobody joins a channel. Its main loop calls PeekMessage and Sleep(1). Wine
 * sleeps exactly 1 ms where Windows rounds up to its timer tick, so on the
 * S23 the loop woke about 1,600 times a second and used 13% of a core, plus
 * wineserver and X11 work. WoW has no setting that stops the process.
 * <p>
 * The off state is a per-program Wine override that disables
 * {@code vivoxsdk.dll}, which the proxy imports. The proxy then fails to load,
 * WoW stops restarting it after a few tries, and the game starts normally
 * with voice chat unavailable. Game files stay untouched, so Battle.net has
 * nothing to repair. Text-level edit of {@code user.reg} while the prefix is
 * stopped. Pure java.io (host self-tested).
 */
public final class WowVoiceChat {
    static final String KEY = "Software\\\\Wine\\\\AppDefaults\\\\WowVoiceProxy.exe\\\\DllOverrides";
    static final String VALUE = "\"vivoxsdk\"=\"\"";
    private static final String VALUE_NAME = "\"vivoxsdk\"=";
    private static final String USER_REG = "user.reg";
    private static final long TICKS_1601_TO_1970 = 86400L * (369 * 365 + 89) * 10000000;

    private WowVoiceChat() {}

    /**
     * Writes the override into the prefix's user.reg when {@code disabled},
     * or removes it. Returns whether the file changed.
     */
    public static boolean apply(File prefix, boolean disabled) throws IOException {
        File reg = new File(prefix, USER_REG);
        if (!reg.isFile()) return false;
        String before = new String(Files.readAllBytes(reg.toPath()), StandardCharsets.UTF_8);
        String after = disabled ? disable(before, System.currentTimeMillis()) : enable(before);
        if (after == null) return false;
        File temp = new File(reg.getParentFile(), reg.getName() + ".wehavephones-tmp");
        Files.write(temp.toPath(), after.getBytes(StandardCharsets.UTF_8));
        try {
            Files.move(temp.toPath(), reg.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), reg.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return true;
    }

    /** Returns {@code text} with the override, or null when it is already there. */
    static String disable(String text, long nowMillis) {
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        int[] section = findSection(text);
        if (section == null) {
            long ticks = nowMillis * 10000 + TICKS_1601_TO_1970;
            String block = "[" + KEY + "] " + (nowMillis / 1000) + newline
                    + String.format(Locale.ROOT, "#time=%x", ticks) + newline
                    + VALUE + newline;
            String separator = text.isEmpty() || text.endsWith(newline + newline) ? ""
                    : text.endsWith(newline) ? newline : newline + newline;
            return text + separator + block;
        }
        ArrayList<String> lines = bodyLines(text, section);
        int index = valueIndex(lines);
        if (index >= 0 && lines.get(index).trim().equals(VALUE)) return null;
        if (index >= 0) lines.set(index, VALUE);
        else lines.add(VALUE);
        return replaceBody(text, section, lines, newline);
    }

    /** Returns {@code text} without the override (and without its key once empty), or null when it has none. */
    static String enable(String text) {
        int[] section = findSection(text);
        if (section == null) return null;
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        ArrayList<String> lines = bodyLines(text, section);
        int index = valueIndex(lines);
        if (index < 0) return null;
        lines.remove(index);
        for (String line : lines) {
            if (!line.trim().isEmpty() && !line.startsWith("#")) return replaceBody(text, section, lines, newline);
        }
        // Only the key's metadata is left: drop the whole section and one blank separator line,
        // the one after it, or the one before it at the end of the file.
        int start = section[0];
        int end = section[2];
        if (text.startsWith(newline, end)) end += newline.length();
        else if (end == text.length() && start >= newline.length() * 2
                && text.startsWith(newline + newline, start - newline.length() * 2)) start -= newline.length();
        return text.substring(0, start) + text.substring(end);
    }

    /** {header start, body start, body end} of the key's section, or null when the key is absent. */
    private static int[] findSection(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String header = ("[" + KEY + "]").toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int index = lower.indexOf(header, from);
            if (index < 0) return null;
            if (index == 0 || text.charAt(index - 1) == '\n') {
                int bodyStart = text.indexOf('\n', index);
                if (bodyStart < 0) return new int[] {index, text.length(), text.length()};
                bodyStart++;
                int bodyEnd = bodyStart;
                while (bodyEnd < text.length()) {
                    int lineEnd = text.indexOf('\n', bodyEnd);
                    if (lineEnd < 0) lineEnd = text.length();
                    String line = text.substring(bodyEnd, lineEnd).trim();
                    if (line.isEmpty() || line.startsWith("[")) break;
                    bodyEnd = Math.min(text.length(), lineEnd + 1);
                }
                return new int[] {index, bodyStart, bodyEnd};
            }
            from = index + 1;
        }
    }

    private static ArrayList<String> bodyLines(String text, int[] section) {
        ArrayList<String> lines = new ArrayList<>();
        String body = text.substring(section[1], section[2]);
        for (String line : body.split("\n")) {
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            if (!line.isEmpty()) lines.add(line);
        }
        return lines;
    }

    private static int valueIndex(ArrayList<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().toLowerCase(Locale.ROOT).startsWith(VALUE_NAME)) return i;
        }
        return -1;
    }

    private static String replaceBody(String text, int[] section, ArrayList<String> lines, String newline) {
        StringBuilder body = new StringBuilder();
        for (String line : lines) body.append(line).append(newline);
        return text.substring(0, section[1]) + body + text.substring(section[2]);
    }
}
