package com.wehavephones.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

/**
 * Points the prefix's font list at CJK fonts that exist. Wine's DirectWrite,
 * which Battle.net's web pages (CEF) use, reads only
 * {@code HKLM\Software\Microsoft\Windows NT\CurrentVersion\Fonts}; it never
 * scans {@code C:\windows\Fonts}. The Proton prefix registers Proton's own
 * stand-ins ({@code msyh.ttf}, {@code simsun.ttc}, {@code malgun.ttf}) that
 * our Wine layer doesn't ship, while the container pattern copies real fonts
 * unregistered, so Chinese and Korean page text drew as empty boxes. GDI apps
 * (the installer, Qt) scan the folder and were never affected.
 * Text-level edit of {@code system.reg}, run by {@link WineFontPaths} while
 * the prefix is stopped; pure java.io (host self-tested).
 */
public final class WineFontRegistry {
    static final String FONTS_KEY = "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\Fonts]";

    /** Value name, then candidate files in preference order (C:\windows\Fonts names or Z:\ paths). */
    static final String[][] FONTS = {
            {"Microsoft YaHei (TrueType)", "msyh.ttc"},
            {"SimHei (TrueType)", "SimHei.ttf"},
            {"Source Han Sans CN (TrueType)", "SourceHanSansCN-Regular.otf"},
            {"PMingLiU (TrueType)", "PMingLiU.ttf"},
            {"SimSun (TrueType)", "simsun.ttc", "Z:\\usr\\share\\fonts\\truetype\\ms\\simsun.ttc"},
            {"NSimSun (TrueType)", "simsun.ttc", "Z:\\usr\\share\\fonts\\truetype\\ms\\simsun.ttc"},
            {"Malgun Gothic (TrueType)", "malgun.ttf", "Z:\\usr\\share\\fonts\\truetype\\ms\\malgun.ttf"},
    };

    /** Whether {@link #repair} manages this value name. */
    public static boolean manages(String valueName) {
        for (String[] font : FONTS) if (font[0].equals(valueName)) return true;
        return false;
    }

    public interface FileCheck {
        /** Whether a registry font path (a bare C:\windows\Fonts name or a full path) exists. */
        boolean exists(String path);
    }

    private WineFontRegistry() {}

    /**
     * Returns {@code text} with each listed value pointing at its first
     * existing candidate, or null when nothing changes (values that already
     * point at an existing file, or have no existing candidate, are kept).
     */
    public static String repair(String text, FileCheck check) {
        int header = findHeader(text);
        if (header < 0) return null;
        int bodyStart = text.indexOf('\n', header);
        if (bodyStart < 0) return null;
        bodyStart++;
        int bodyEnd = text.indexOf("\n\n", bodyStart);
        if (bodyEnd < 0) bodyEnd = text.length();
        String newline = text.contains("\r\n") ? "\r\n" : "\n";

        ArrayList<String> lines = new ArrayList<>();
        for (String line : text.substring(bodyStart, bodyEnd).split("\n", -1)) lines.add(line);
        boolean changed = false;
        for (String[] font : FONTS) {
            String prefix = "\"" + font[0] + "\"=";
            int index = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).startsWith(prefix)) { index = i; break; }
            }
            String current = index >= 0 ? unquote(lines.get(index).substring(prefix.length())) : null;
            if (current != null && check.exists(current)) continue;
            String target = null;
            for (int i = 1; i < font.length && target == null; i++) {
                if (check.exists(font[i])) target = font[i];
            }
            if (target == null || target.equals(current)) continue;
            String line = prefix + "\"" + target.replace("\\", "\\\\") + "\"";
            if (index >= 0) lines.set(index, line);
            else lines.add(line);
            changed = true;
        }
        if (!changed) return null;
        String body = String.join("\n", lines);
        if (!newline.equals("\n")) body = body.replace("\r\n", "\n").replace("\n", newline);
        return text.substring(0, bodyStart) + body + text.substring(bodyEnd);
    }

    /** Wine looks file names up case-insensitively; Z: is the image root. Other drives are left alone. */
    public static boolean exists(String path, File windowsFonts, File imageFs) {
        if (path.indexOf('\\') < 0) return existsIgnoreCase(windowsFonts, path);
        if (path.length() > 3 && Character.toUpperCase(path.charAt(0)) == 'Z' && path.startsWith(":\\", 1)) {
            return new File(imageFs, path.substring(3).replace('\\', '/')).isFile();
        }
        return true;
    }

    private static boolean existsIgnoreCase(File dir, String name) {
        if (new File(dir, name).isFile()) return true;
        String[] children = dir.list();
        if (children == null) return false;
        for (String child : children) {
            if (child.equalsIgnoreCase(name) && new File(dir, child).isFile()) return true;
        }
        return false;
    }

    private static int findHeader(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        String key = FONTS_KEY.toLowerCase(Locale.ROOT);
        int from = 0;
        while (true) {
            int index = lower.indexOf(key, from);
            if (index < 0) return -1;
            if (index == 0 || text.charAt(index - 1) == '\n') return index;
            from = index + 1;
        }
    }

    private static String unquote(String value) {
        String trimmed = value.trim();
        if (trimmed.length() < 2 || trimmed.charAt(0) != '"' || !trimmed.endsWith("\"")) return null;
        return trimmed.substring(1, trimmed.length() - 1).replace("\\\\", "\\");
    }
}
