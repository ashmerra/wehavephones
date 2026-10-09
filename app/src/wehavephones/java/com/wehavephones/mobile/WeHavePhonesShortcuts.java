package com.wehavephones.mobile;

import com.wehavephones.container.Container;
import com.wehavephones.container.Shortcut;
import com.wehavephones.core.FileUtils;
import com.wehavephones.fexcore.FEXCorePreset;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class WeHavePhonesShortcuts {
    public static final String BN_TARGET = "C:\\bnet.bat";
    public static final String WOW_TARGET = "C:\\wow.bat";
    public static final String SETUP_TARGET = "C:\\wehavephones-bnet-setup.bat";

    private WeHavePhonesShortcuts() {}

    public static File findBnetShortcut(Container container) {
        return findShortcut(container, "bnet.bat", "Battle.net");
    }

    public static File findWowShortcut(Container container) {
        return findShortcut(container, "wow.bat", "AAWoW_Retail", "World of Warcraft");
    }

    public static File findSetupShortcut(Container container) {
        return findShortcut(container, "wehavephones-bnet-setup.bat", "WeHavePhones Installer");
    }

    public static File ensureBnetShortcut(Container container) {
        return ensureStableLauncherShortcut(container, "Battle.net", BN_TARGET);
    }

    public static File ensureWowShortcut(Container container) {
        return ensureShortcut(container, "AAWoW_Retail", WOW_TARGET);
    }

    public static File ensureSetupShortcut(Container container) {
        return ensureStableLauncherShortcut(container, "WeHavePhones Installer", SETUP_TARGET);
    }

    private static File ensureStableLauncherShortcut(Container container, String name, String target) {
        File file = ensureShortcut(container, name, target);
        if (file == null) return null;
        // Use this preset's stricter x86 memory ordering for Battle.net's
        // concurrent startup. Scope it to the launcher and its installer; game
        // shortcuts continue to use the player's container settings.
        Shortcut shortcut = new Shortcut(container, file);
        if (!FEXCorePreset.STABILITY.equals(shortcut.getExtra("fexcorePreset"))) {
            shortcut.putExtra("fexcorePreset", FEXCorePreset.STABILITY);
            shortcut.saveData();
        }
        return FEXCorePreset.STABILITY.equals(new Shortcut(container, file).getExtra("fexcorePreset"))
                ? file : null;
    }

    public static File ensureShortcut(Container container, String name, String target) {
        File existing = findShortcut(container, target.toLowerCase(Locale.ROOT), name);
        String exec = "wine " + target.replace('\\', '/');
        // Repair anything that does not run the target: Shortcut unescapes Exec, so "C:\x.bat"
        // would become "C:x.bat" and crash XServerDisplayActivity.getWineStartCommand, and
        // winemenubuilder overwrites Battle.net.desktop with the installer's Battle.net.lnk
        // (which starts Battle.net with --in-process-gpu instead of bnet.bat's flags).
        String existingContent = existing != null ? FileUtils.readString(existing) : null;
        if (existingContent != null && existingContent.toLowerCase(Locale.ROOT).contains(exec.toLowerCase(Locale.ROOT))) return existing;

        File directory = container.getDesktopDir();
        if (!directory.isDirectory() && !directory.mkdirs()) return null;
        File file = existing != null ? existing : new File(directory, sanitize(name) + ".desktop");
        String content = "[Desktop Entry]\nExec=" + exec + "\n";
        if (!FileUtils.write(file, content.getBytes(StandardCharsets.UTF_8))) return null;
        return file;
    }

    public static File findShortcut(Container container, String... hints) {
        File directory = container.getDesktopDir();
        File[] files = directory.listFiles();
        if (files == null) return null;
        for (File file : files) {
            if (!file.isFile() || !file.getName().endsWith(".desktop")) continue;
            String basename = FileUtils.getBasename(file.getName()).toLowerCase(Locale.ROOT);
            for (String hint : hints) {
                String expected = hint.toLowerCase(Locale.ROOT);
                if (basename.equals(expected)) return file;
            }
            String content = FileUtils.readString(file);
            if (content == null) continue;
            String normalized = content.toLowerCase(Locale.ROOT).replace('/', '\\');
            for (String hint : hints) {
                String expected = hint.toLowerCase(Locale.ROOT);
                if (expected.endsWith(".bat") && normalized.contains(expected)) return file;
            }
        }
        return null;
    }

    private static String sanitize(String name) {
        return name.replaceAll("[^A-Za-z0-9._ -]", "_").trim();
    }
}
