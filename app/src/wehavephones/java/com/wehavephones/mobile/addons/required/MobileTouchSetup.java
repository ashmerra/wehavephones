package com.wehavephones.mobile.addons.required;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Reversible, per-install touch mode files. Call with the game closed and AddonInstaller.LOCK held. */
public final class MobileTouchSetup {
    public static final String FOLDER = "WeHavePhonesTouch";
    private static final String DISABLED = ".disabled";
    private static final String STATE = "WTF/.wehavephones-touch-consoleport";
    private MobileTouchSetup() {}

    public static void prepare(File product, boolean enabled, int interfaceVersion, byte[] script) throws IOException {
        File state = new File(product, STATE);
        File folder = new File(product, "Interface/AddOns/" + FOLDER);
        // Default-off installs never acquire a touch addon or have their addons changed.
        if (!enabled && !state.isFile() && !folder.isDirectory()) return;
        File addons = folder.getParentFile();
        Files.createDirectories(addons.toPath());
        List<String> owned = new ArrayList<>();
        if (state.isFile()) {
            for (String name : Files.readAllLines(state.toPath(), StandardCharsets.UTF_8)) {
                if (name.contains("/") || name.contains("\\") || name.endsWith(DISABLED)
                        || !isController(name)) throw new IOException("Invalid touch mode restore entry");
                owned.add(name);
            }
        }
        if (enabled) {
            File[] children = addons.listFiles();
            if (children != null) for (File child : children) {
                String name = child.getName();
                if (!child.isDirectory() || name.endsWith(DISABLED) || !isController(name)) continue;
                File disabled = new File(addons, name + DISABLED);
                if (disabled.exists()) throw new IOException("Both enabled and disabled controller folders exist: " + name);
                if (!owned.contains(name)) owned.add(name);
                // Journal the ownership before renaming, so an interrupted launch can restore it.
                write(state, String.join("\n", owned).getBytes(StandardCharsets.UTF_8));
                Files.move(child.toPath(), disabled.toPath());
            }
        } else {
            for (String name : owned) {
                File disabled = new File(addons, name + DISABLED), restored = new File(addons, name);
                if (disabled.exists()) {
                    if (restored.exists()) throw new IOException("Cannot restore controller folder: " + name);
                    Files.move(disabled.toPath(), restored.toPath());
                }
            }
            Files.deleteIfExists(state.toPath());
        }
        Files.createDirectories(folder.toPath());
        String toc = "## Interface: " + interfaceVersion + "\n## Title: WeHavePhones Touch\n"
                + "## Notes: Large paged touch actions and temporary movement bindings.\n"
                + "## Version: 1.2.1\n## SavedVariablesPerCharacter: WeHavePhonesTouchDB\n\nMode.lua\nTouch.lua\n";
        write(new File(folder, FOLDER + ".toc"), toc.getBytes(StandardCharsets.UTF_8));
        write(new File(folder, "Touch.lua"), script);
        write(new File(folder, "Mode.lua"), ("local _, addon = ...\naddon.enabled = " + enabled + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isController(String name) {
        String lower = name.toLowerCase(java.util.Locale.US);
        return lower.equals("consoleport") || lower.startsWith("consoleport_")
                || lower.equals("wehavephonescontrollersetup");
    }

    private static void write(File target, byte[] bytes) throws IOException {
        if (target.isFile() && Arrays.equals(bytes, Files.readAllBytes(target.toPath()))) return;
        Files.createDirectories(target.getParentFile().toPath());
        File tmp = new File(target.getPath() + ".tmp");
        Files.write(tmp.toPath(), bytes);
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }
}
