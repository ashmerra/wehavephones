package com.wehavephones.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Repair font filenames inherited from a prefix whose Wine fonts live outside C:\\windows\\Fonts. */
public final class WineFontPaths {
    private static final String STARTUP_REG = "drive_c/windows/wehavephones-font-paths.reg";
    private static final String WINNT = "[Software\\\\Microsoft\\\\Windows NT\\\\CurrentVersion\\\\Fonts]";
    private static final String WIN9X = "[Software\\\\Microsoft\\\\Windows\\\\CurrentVersion\\\\Fonts]";
    private static final Pattern VALUE = Pattern.compile("(\"(?:[^\"\\\\]|\\\\.)*\"=\")([^\"\\\\/]+)(\".*)");
    private static final Pattern STRING_VALUE = Pattern.compile("(\"(?:[^\"\\\\]|\\\\.)*\"=\")(.*)(\")");

    private WineFontPaths() {}

    /** Called while the prefix is stopped, before Wine loads its registry. */
    public static int repair(File imageFs, File wineDir, File prefix) throws IOException {
        File registry = new File(prefix, "system.reg");
        File sourceDir = new File(wineDir, "share/wine/fonts");
        File[] sources = sourceDir.listFiles(File::isFile);
        if (!registry.isFile()) return 0;
        if (sources == null) sources = new File[0];

        File prefixFonts = new File(prefix, "drive_c/windows/Fonts");
        Map<String, String> paths = new HashMap<>();
        File[] installed = prefixFonts.listFiles();
        for (File source : sources) {
            String filename = source.getName();
            // Existing custom fonts in the prefix take precedence, including differently cased names.
            boolean present = false;
            if (installed != null) {
                for (File font : installed) {
                    if (font.isFile() && font.getName().equalsIgnoreCase(filename)) {
                        present = true;
                        break;
                    }
                }
            }
            if (present) continue;
            String relative = imageFs.toPath().toAbsolutePath().normalize().relativize(
                    source.toPath().toAbsolutePath().normalize()).toString();
            if (relative.startsWith("..")) continue;
            paths.put(filename.toLowerCase(Locale.ROOT), "Z:\\" + relative.replace(File.separatorChar, '\\'));
        }

        String before = new String(Files.readAllBytes(registry.toPath()), StandardCharsets.UTF_8);
        String after = repairRegistry(before, paths);
        // Battle.net's pages read fonts through DirectWrite, which only sees registered files.
        String cjk = WineFontRegistry.repair(after, path -> WineFontRegistry.exists(path, prefixFonts, imageFs));
        if (cjk != null) after = cjk;
        // wineboot imports wine.inf on the first launch and can restore bare NT font
        // names. Import this small patch in the guest after wineboot has completed.
        String startup = startupRegistry(after, paths);
        File startupFile = new File(prefix, STARTUP_REG);
        if (startup.isEmpty()) Files.deleteIfExists(startupFile.toPath());
        else {
            Files.createDirectories(startupFile.getParentFile().toPath());
            Files.write(startupFile.toPath(), ("\ufeff" + startup).getBytes(StandardCharsets.UTF_16LE));
        }
        if (before.equals(after)) return 0;
        File staged = File.createTempFile("font-paths-", ".reg", prefix);
        try {
            Files.write(staged.toPath(), after.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(staged.toPath(), registry.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(staged.toPath(), registry.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally {
            Files.deleteIfExists(staged.toPath());
        }
        return changedValues(before, after);
    }

    /** Preserve the batch's working directory and arguments while repairing first-boot font keys. */
    public static String startupBatch(File prefix, String filename, String arguments) throws IOException {
        if (!new File(prefix, STARTUP_REG).isFile()) return filename;
        File batch = new File(prefix, "drive_c/windows/temp/wehavephones-font-startup.bat");
        Files.createDirectories(batch.getParentFile().toPath());
        String content = "@echo off\r\n"
                + "reg.exe import \"C:\\windows\\wehavephones-font-paths.reg\" >NUL\r\n"
                + "if errorlevel 1 exit /b 1\r\n"
                + "call \"" + filename + "\"" + arguments + "\r\n";
        Files.write(batch.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return "C:\\windows\\temp\\wehavephones-font-startup.bat";
    }

    private static String startupRegistry(String registry, Map<String, String> paths) {
        StringBuilder result = new StringBuilder("Windows Registry Editor Version 5.00\r\n");
        String section = null;
        boolean wroteSection = false;
        int count = 0;
        for (String line : registry.split("\\r?\\n")) {
            if (line.startsWith("[")) {
                section = line.startsWith(WINNT) ? WINNT : line.startsWith(WIN9X) ? WIN9X : null;
                wroteSection = false;
            }
            if (section == null) continue;
            Matcher value = STRING_VALUE.matcher(line);
            if (!value.matches()) continue;
            String path = value.group(2).replace("\\\\", "\\");
            String name = value.group(1);
            boolean cjk = section.equals(WINNT) && WineFontRegistry.manages(name.substring(1, name.length() - 3));
            if (!paths.containsValue(path) && !cjk) continue;
            if (!wroteSection) {
                result.append("\r\n[HKEY_LOCAL_MACHINE\\")
                        .append(section.substring(1, section.length() - 1).replace("\\\\", "\\"))
                        .append("]\r\n");
                wroteSection = true;
            }
            result.append(line).append("\r\n");
            count++;
        }
        return count == 0 ? "" : result.toString();
    }

    static String repairRegistry(String registry, Map<String, String> paths) {
        StringBuilder result = new StringBuilder(registry.length());
        boolean fonts = false;
        for (String line : registry.split("(?<=\n)", -1)) {
            if (line.startsWith("[")) fonts = line.startsWith(WINNT) || line.startsWith(WIN9X);
            if (fonts) {
                String ending = line.endsWith("\r\n") ? "\r\n" : line.endsWith("\n") ? "\n" : "";
                String content = line.substring(0, line.length() - ending.length());
                Matcher value = VALUE.matcher(content);
                if (value.matches()) {
                    String path = paths.get(value.group(2).toLowerCase(Locale.ROOT));
                    if (path != null) {
                        line = value.group(1) + path.replace("\\", "\\\\").replace("\"", "\\\"")
                                + value.group(3) + ending;
                    }
                }
            }
            result.append(line);
        }
        return result.toString();
    }

    /** Lines of {@code after} not in {@code before}: edited or added values. */
    private static int changedValues(String before, String after) {
        HashSet<String> oldLines = new HashSet<>(java.util.Arrays.asList(before.split("\n", -1)));
        int changed = 0;
        for (String line : after.split("\n", -1)) if (!oldLines.contains(line)) changed++;
        return changed;
    }
}
