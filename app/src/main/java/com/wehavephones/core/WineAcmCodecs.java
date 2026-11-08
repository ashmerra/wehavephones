package com.wehavephones.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Turns off the audio codec (ACM) drivers that {@code C:\windows\system.ini}
 * registers under {@code [drivers32]} ({@code msacm.imaadpcm} and the like).
 * <p>
 * Battle.net asks winmm about 130 output formats while it starts. For the
 * ones the device refuses, winmm tries about 20 ACM conversions, and each
 * opens every listed codec. Wine first loads a codec by its alias name,
 * which is never a file, so every open searches the whole DLL path and
 * reads directories such as syswow64. On the S23 that held Battle.net's
 * main thread for 6.5 to 7 seconds of every start. The listed codecs only
 * decode compressed formats (ADPCM, G.711, GSM, MP3), never the PCM
 * conversions these queries need, which msacm32's own converter handles.
 * <p>
 * Lines are commented with {@link #MARK}, so {@link #restore} can put them
 * back. Wine re-adds them when it updates the prefix, which this repair
 * undoes on the next start. Text-level edit while the prefix is stopped.
 */
public final class WineAcmCodecs {
    static final String MARK = ";wehavephones-off ";
    private static final String SYSTEM_INI = "drive_c/windows/system.ini";

    private WineAcmCodecs() {}

    /** Disables (or, with {@code keep}, restores) the codecs. Returns the number of lines changed. */
    public static int apply(File prefix, boolean keep) throws IOException {
        File ini = new File(prefix, SYSTEM_INI);
        if (!ini.isFile()) return 0;
        String before = new String(Files.readAllBytes(ini.toPath()), StandardCharsets.ISO_8859_1);
        String after = keep ? restore(before) : disable(before);
        if (after == null) return 0;
        File temp = new File(ini.getParentFile(), ini.getName() + ".wehavephones-tmp");
        Files.write(temp.toPath(), after.getBytes(StandardCharsets.ISO_8859_1));
        try {
            Files.move(temp.toPath(), ini.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        }
        catch (AtomicMoveNotSupportedException e) {
            Files.move(temp.toPath(), ini.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        return changedLines(before, after);
    }

    /** Comments out active {@code msacm.*} lines in {@code [drivers32]}, or returns null when none are active. */
    static String disable(String text) {
        String[] lines = text.split("\n", -1);
        Set<String> disabled = new HashSet<>();
        String section = "";
        for (String line : lines) {
            if (line.startsWith("[")) section = sectionName(line);
            else if (section.equals("drivers32") && line.startsWith(MARK)) disabled.add(line);
        }
        StringBuilder out = new StringBuilder(text.length() + 64);
        boolean changed = false;
        section = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("[")) section = sectionName(line);
            else if (section.equals("drivers32") && isCodec(line)) {
                changed = true;
                // Wine re-adds a codec after a prefix update. Keep one marked copy.
                if (disabled.contains(MARK + line)) continue;
                line = MARK + line;
                disabled.add(line);
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return changed ? out.toString() : null;
    }

    /** Uncomments the lines {@link #disable} marked, dropping marked copies of active lines. Null when unchanged. */
    static String restore(String text) {
        String[] lines = text.split("\n", -1);
        Set<String> active = new HashSet<>();
        String section = "";
        for (String line : lines) {
            if (line.startsWith("[")) section = sectionName(line);
            else if (section.equals("drivers32") && isCodec(line)) active.add(line);
        }
        StringBuilder out = new StringBuilder(text.length());
        boolean changed = false;
        section = "";
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.startsWith("[")) section = sectionName(line);
            else if (section.equals("drivers32") && line.startsWith(MARK)) {
                changed = true;
                line = line.substring(MARK.length());
                if (!active.add(line)) continue;
            }
            out.append(line);
            if (i < lines.length - 1) out.append('\n');
        }
        return changed ? out.toString() : null;
    }

    private static boolean isCodec(String line) {
        return line.regionMatches(true, 0, "msacm.", 0, 6) && line.indexOf('=') > 6;
    }

    private static String sectionName(String line) {
        int end = line.indexOf(']');
        return (end > 0 ? line.substring(1, end) : line.substring(1)).trim().toLowerCase(Locale.ROOT);
    }

    private static int changedLines(String before, String after) {
        Set<String> old = new HashSet<>(java.util.Arrays.asList(before.split("\n", -1)));
        int changed = 0;
        for (String line : after.split("\n", -1)) if (!old.contains(line)) changed++;
        return changed;
    }
}
