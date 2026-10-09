package com.wehavephones.mobile.addons.scan;

import com.wehavephones.mobile.addons.model.TocData;
import com.wehavephones.mobile.WowFlavor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * WoW TOC parser and multi-TOC selector. Pure Java: no Android APIs.
 */
public final class TocParser {
    private TocParser() {}

    public static TocData parse(File tocFile, String folderName) throws IOException {
        String text = readText(tocFile);
        TocData data = parseText(text, folderName);
        data.tocFileName = tocFile.getName();
        return data;
    }

    public static TocData parseText(String text, String folderName) {
        TocData data = new TocData();
        data.baseName = folderName != null ? folderName : "";
        if (text == null) return data;

        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String rawLine : lines) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("\uFEFF")) line = line.substring(1).trim();
            if (!line.startsWith("#")) continue;
            String body = line;
            while (body.startsWith("#")) body = body.substring(1);
            body = body.trim();
            int separator = body.indexOf(':');
            if (separator <= 0) continue;
            String key = body.substring(0, separator).trim().toLowerCase(Locale.US);
            String value = body.substring(separator + 1).trim();
            if (key.isEmpty()) continue;
            applyField(data, key, value);
        }
        return data;
    }

    private static void applyField(TocData data, String key, String value) {
        if (key.equals("interface")) {
            for (String part : value.split("[,\\s]+")) {
                String token = part.trim();
                if (!token.isEmpty()) data.interfaces.add(token);
            }
        }
        else if (key.equals("version")) {
            if (data.version.isEmpty()) data.version = value;
        }
        else if (key.equals("title")) {
            data.title = value;
        }
        else if (key.equals("author")) {
            data.author = value;
        }
        else if (key.equals("notes")) {
            data.notes = value;
        }
        else if (key.equals("x-website") || key.equals("website")) {
            if (data.website.isEmpty()) data.website = value;
        }
        else if (key.equals("x-curse-project-id")) {
            data.curseId = value;
        }
        else if (key.equals("x-wago-id")) {
            data.wagoId = value;
        }
        else if (key.equals("x-wowi-id")) {
            data.wowiId = value;
        }
        else if (key.equals("x-tukui-projectid")) {
            data.tukuiId = value;
        }
        else if (key.equals("x-tukui-projectfolders")) {
            for (String part : value.split(",")) {
                String token = part.trim();
                if (!token.isEmpty()) data.tukuiFolders.add(token);
            }
        }
        else if (key.equals("optiondeps") || key.equals("optionaldeps") || key.equals("optionaldependencies")) {
            addCommaSeparated(data.optionalDeps, value);
        }
        else if (key.startsWith("dep") || key.startsWith("requireddep")) {
            addCommaSeparated(data.dependencies, value);
        }
        else if (key.equals("loadondemand")) {
            data.loadOnDemand = isTrue(value);
        }
        else if (key.equals("defaultstate")) {
            data.defaultState = value;
        }
        else if (key.equals("allowloadgametype")) {
            addCommaSeparated(data.allowLoadGameType, value);
        }
    }

    private static void addCommaSeparated(List<String> target, String value) {
        for (String part : value.split(",")) {
            String token = part.trim();
            if (!token.isEmpty() && !target.contains(token)) target.add(token);
        }
    }

    private static boolean isTrue(String value) {
        String token = value.trim().toLowerCase(Locale.US);
        return token.equals("1") || token.equals("true") || token.equals("yes");
    }

    private static String readText(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 2 * 1024 * 1024) break;
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Returns the .toc file names inside {@code folder} that are valid for the
     * folder name, best candidate first for the requested flavor.
     */
    public static List<File> selectTocs(File folder, WowFlavor flavor) {
        ArrayList<File> candidates = new ArrayList<>();
        File[] children = folder.listFiles();
        if (children == null) return candidates;
        String folderName = folder.getName();
        if (folderName.toLowerCase(Locale.US).endsWith(".disabled")) {
            folderName = folderName.substring(0, folderName.length() - ".disabled".length());
        }
        for (File child : children) {
            if (!child.isFile()) continue;
            String fileName = child.getName();
            if (!fileName.toLowerCase(Locale.US).endsWith(".toc")) continue;
            String tocBase = fileName.substring(0, fileName.length() - 4);
            if (!baseMatchesFolder(tocBase, folderName)) continue;
            candidates.add(child);
        }
        final String base = folderName;
        Collections.sort(candidates, new Comparator<File>() {
            @Override
            public int compare(File first, File second) {
                return Integer.compare(priority(first, base, flavor), priority(second, base, flavor));
            }
        });
        return candidates;
    }

    public static File chooseBestToc(File folder, WowFlavor flavor) {
        List<File> candidates = selectTocs(folder, flavor);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static int priority(File tocFile, String folderBase, WowFlavor flavor) {
        String name = tocFile.getName();
        String tocBase = name.substring(0, name.length() - 4);
        if (flavor != null) {
            String[] suffixes = flavor.preferredSuffixes;
            for (int i = 0; i < suffixes.length; i++) {
                String suffix = suffixes[i];
                if (matchesSuffix(tocBase, folderBase, suffix)) return i;
            }
        }
        // The un-suffixed TOC is the flavor-agnostic fallback; TOCs carrying a
        // different expansion's suffix rank below it and are only used when
        // nothing better exists.
        if (tocBase.equalsIgnoreCase(folderBase)) return 900;
        return 2000;
    }

    /**
     * A TOC is valid when its base name equals the folder name after removing a
     * known flavor suffix.
     */
    public static boolean baseMatchesFolder(String tocBase, String folderBase) {
        if (tocBase.equalsIgnoreCase(folderBase)) return true;
        for (String suffix : WowFlavor.KNOWN_SUFFIXES) {
            if (matchesSuffix(tocBase, folderBase, suffix)) return true;
        }
        return false;
    }

    private static boolean matchesSuffix(String tocBase, String folderBase, String suffix) {
        return tocBase.equalsIgnoreCase(folderBase + "_" + suffix)
                || tocBase.equalsIgnoreCase(folderBase + "-" + suffix);
    }

    public static boolean hasTocFor(File folder) {
        File[] children = folder.listFiles();
        if (children == null) return false;
        String folderName = folder.getName();
        if (folderName.toLowerCase(Locale.US).endsWith(".disabled")) {
            folderName = folderName.substring(0, folderName.length() - ".disabled".length());
        }
        for (File child : children) {
            if (!child.isFile()) continue;
            String name = child.getName();
            if (!name.toLowerCase(Locale.US).endsWith(".toc")) continue;
            if (baseMatchesFolder(name.substring(0, name.length() - 4), folderName)) return true;
        }
        return false;
    }

    /** Folder-name version suffix fallback: MyAddon-1.2.3 -> 1.2.3. */
    public static String versionFromName(String name) {
        if (name == null) return "";
        String cleaned = name;
        if (cleaned.toLowerCase(Locale.US).endsWith(".zip")) {
            cleaned = cleaned.substring(0, cleaned.length() - 4);
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[-_](v?\\d[\\w.\\-]*)$").matcher(cleaned);
        if (matcher.find()) return matcher.group(1);
        return "";
    }

    public static List<String> knownSuffixes() {
        return Arrays.asList(WowFlavor.KNOWN_SUFFIXES.toArray(new String[0]));
    }
}
