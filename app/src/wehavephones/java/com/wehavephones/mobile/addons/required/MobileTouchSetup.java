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
    /** Original values of {@link #CONTROLLER_OFF}, one {@code name=value} or bare absent {@code name} per line. */
    public static final String CVARS = "WTF/.wehavephones-touch-cvars";
    /**
     * Turned off in Config.wtf before launch, so the game starts in mouse mode.
     * Forever's gamepad UI is chosen at startup, too early for the addon to change it.
     */
    public static final String[][] CONTROLLER_OFF = {{"GamePadEnable", "0"}, {"InputDeviceInterfaceStyle", "0"}};
    private MobileTouchSetup() {}

    public static void prepare(File product, boolean enabled, int interfaceVersion, byte[] script) throws IOException {
        prepare(product, enabled, 1.5f, interfaceVersion, script);
    }

    /** {@code scale} is the app-wide touch interface size, applied to every character at login. */
    public static void prepare(File product, boolean enabled, float scale, int interfaceVersion, byte[] script) throws IOException {
        File state = new File(product, STATE);
        File folder = new File(product, "Interface/AddOns/" + FOLDER);
        // Default-off installs never acquire a touch addon or have their addons changed.
        File cvars = new File(product, CVARS);
        if (!enabled && !state.isFile() && !folder.isDirectory() && !cvars.isFile()) return;
        prepareControllerCvars(product, enabled);
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
                + "## Version: 1.6.5\n## SavedVariablesPerCharacter: WeHavePhonesTouchDB\n\nMode.lua\nTouch.lua\n";
        write(new File(folder, FOLDER + ".toc"), toc.getBytes(StandardCharsets.UTF_8));
        write(new File(folder, "Touch.lua"), script);
        String size = String.format(java.util.Locale.US, "%.2f", scale == 1.25f || scale == 1.75f ? scale : 1.5f);
        write(new File(folder, "Mode.lua"), ("local _, addon = ...\naddon.enabled = " + enabled
                + "\naddon.scale = " + size + "\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Journals the player's controller CVars once, turns them off, and restores them on opt-out. */
    private static void prepareControllerCvars(File product, boolean enabled) throws IOException {
        File config = new File(product, "WTF/Config.wtf"), cvars = new File(product, CVARS);
        String existing = config.isFile() ? new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8) : "";
        String updated = existing;
        if (enabled) {
            if (!cvars.isFile()) {
                StringBuilder journal = new StringBuilder();
                for (String[] cvar : CONTROLLER_OFF) {
                    String value = cvarValue(existing, cvar[0]);
                    journal.append(cvar[0]).append(value != null ? "=" + value : "").append('\n');
                }
                write(cvars, journal.toString().getBytes(StandardCharsets.UTF_8));
            }
            for (String[] cvar : CONTROLLER_OFF) updated = setCvar(updated, cvar[0], cvar[1]);
        } else if (cvars.isFile()) {
            for (String line : Files.readAllLines(cvars.toPath(), StandardCharsets.UTF_8)) {
                if (line.isEmpty()) continue;
                int equals = line.indexOf('=');
                String name = equals < 0 ? line : line.substring(0, equals);
                if (!isControllerCvar(name) || (equals >= 0 && line.indexOf('"') >= 0)) throw new IOException("Invalid touch mode CVar entry");
                updated = setCvar(updated, name, equals < 0 ? null : line.substring(equals + 1));
            }
        }
        if (!updated.equals(existing)) write(config, updated.getBytes(StandardCharsets.UTF_8));
        if (!enabled) Files.deleteIfExists(cvars.toPath());
    }

    private static boolean isControllerCvar(String name) {
        for (String[] cvar : CONTROLLER_OFF) if (cvar[0].equals(name)) return true;
        return false;
    }

    /** Replaces every {@code SET name} line with one at the end, or removes it when {@code value} is null. */
    static String setCvar(String config, String name, String value) {
        String newline = config.contains("\r\n") ? "\r\n" : "\n";
        StringBuilder builder = new StringBuilder();
        if (!config.isEmpty()) {
            for (String line : config.split("\r\n|\r|\n")) {
                if (name.equalsIgnoreCase(cvarName(line))) continue;
                builder.append(line).append(newline);
            }
        }
        if (value != null) builder.append("SET ").append(name).append(" \"").append(value).append('"').append(newline);
        return builder.toString();
    }

    static String cvarValue(String config, String name) {
        for (String line : config.split("\r\n|\r|\n")) {
            if (!name.equalsIgnoreCase(cvarName(line))) continue;
            int open = line.indexOf('"'), close = line.lastIndexOf('"');
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
