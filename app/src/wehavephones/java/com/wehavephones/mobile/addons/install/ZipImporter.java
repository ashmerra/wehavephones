package com.wehavephones.mobile.addons.install;

import com.wehavephones.mobile.addons.model.TocData;
import com.wehavephones.mobile.WowFlavor;
import com.wehavephones.mobile.addons.scan.TocParser;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * Discovers installable addon folders inside an extracted archive/staging
 * directory. Pure Java (host-testable).
 */
public final class ZipImporter {
    private static final int MAX_DEPTH = 5;
    private static final int MAX_SCAN_DIRECTORIES = 5000;

    private ZipImporter() {}

    public static List<DiscoveredAddon> discoverAddons(File staging, WowFlavor flavor) {
        ArrayList<DiscoveredAddon> discovered = new ArrayList<>();
        HashSet<String> seen = new HashSet<>();
        walk(staging, staging, flavor, 0, discovered, seen, new int[]{0});
        return discovered;
    }

    private static void walk(File staging, File current, WowFlavor flavor, int depth,
                             List<DiscoveredAddon> discovered, HashSet<String> seen, int[] scanned) {
        if (depth > MAX_DEPTH || scanned[0] > MAX_SCAN_DIRECTORIES) return;
        File[] children = current.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (!child.isDirectory()) continue;
            if (scanned[0]++ > MAX_SCAN_DIRECTORIES) return;
            if (!isAddonDirectoryName(child.getName())) {
                if (isWrapperName(child.getName())) {
                    walk(staging, child, flavor, depth + 1, discovered, seen, scanned);
                }
                continue;
            }
            if (TocParser.hasTocFor(child)) {
                File tocFile = TocParser.chooseBestToc(child, flavor);
                TocData toc = null;
                if (tocFile != null) {
                    try {
                        toc = TocParser.parse(tocFile, child.getName());
                    }
                    catch (IOException ignored) {
                        toc = null;
                    }
                }
                String key = child.getName().toLowerCase(Locale.US);
                if (seen.add(key)) {
                    discovered.add(new DiscoveredAddon(child, child.getName(), toc,
                            TocParser.versionFromName(child.getName())));
                }
                continue;
            }
            walk(staging, child, flavor, depth + 1, discovered, seen, scanned);
        }
    }

    private static boolean isWrapperName(String name) {
        return name != null && (name.equalsIgnoreCase("Interface") || name.equalsIgnoreCase("AddOns"));
    }

    /** Rejects folder names that are unsafe on Windows/Wine/Android filesystems. */
    public static boolean isAddonDirectoryName(String name) {
        if (name == null || name.isEmpty() || name.length() > 128) return false;
        if (name.equals(".") || name.equals("..")) return false;
        if (name.equalsIgnoreCase("Interface") || name.equalsIgnoreCase("AddOns")) return false;
        if (name.startsWith(".")) return false;
        if (name.endsWith(".") || name.endsWith(" ")) return false;
        for (int i = 0; i < name.length(); i++) {
            char character = name.charAt(i);
            if (character == '/' || character == '\\' || character == '<' || character == '>'
                    || character == ':' || character == '"' || character == '|'
                    || character == '?' || character == '*' || character < 0x20) {
                return false;
            }
        }
        return true;
    }

    public static List<String> folderNames(List<DiscoveredAddon> addons) {
        ArrayList<String> names = new ArrayList<>();
        for (DiscoveredAddon addon : addons) {
            if (!names.contains(addon.folderName)) names.add(addon.folderName);
        }
        return names;
    }
}
