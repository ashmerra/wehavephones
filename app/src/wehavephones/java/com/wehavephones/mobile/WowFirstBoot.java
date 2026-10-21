package com.wehavephones.mobile;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;

/** First-load settings verified against Blizzard's 12.1.0.69933 Glue UI. */
public final class WowFirstBoot {
    /** Spinner order of R.array.wehavephones_regions. */
    public static final String[] REGIONS = {"us", "eu", "kr", "tw", "cn"};
    /** Blizzard runs China on its own Battle.net app, accounts and game client. */
    public static final String CHINA = "cn";
    /**
     * Forever's "Enable Gamepad UI (Alpha)" option sets this CVar to
     * Enum.InputDeviceInterfaceType.Gamepad (Blizzard_SettingsDefinitions_Shared
     * Gamepad.lua, 1.60.1). Turning it on needs a controller, so it is seeded once.
     */
    public static final String GAMEPAD_UI_CVAR = "InputDeviceInterfaceStyle";
    public static final String GAMEPAD_UI = "1";
    /** Written after the seed, so a player who turns the gamepad UI off keeps it off. */
    public static final String GAMEPAD_UI_MARKER = "WTF/.wehavephones-gamepad-ui-seeded";

    private WowFirstBoot() {}

    public static boolean validRegion(String region) {
        for (String value : REGIONS) if (value.equals(region)) return true;
        return false;
    }

    public static boolean isChina(String region) {
        return CHINA.equals(region);
    }

    /** The language seeded on first boot: China's client is Simplified Chinese. */
    public static String defaultLocale(String region) {
        return isChina(region) ? "zhCN" : "enUS";
    }

    public static String configure(String existing, String region, boolean retail, String screenSize) {
        return configure(existing, region, retail, screenSize, false);
    }

    public static String configure(String existing, String region, boolean retail,
            String screenSize, boolean overrideRegion) {
        return configure(existing, region, retail, screenSize, overrideRegion, null);
    }

    /**
     * {@code apiArg} is the device profile's graphics API flag ("-d3d11"); it
     * is written as GxApi because Battle.net's Play button starts the game
     * without our command line. Null leaves the game's own choice alone.
     */
    public static String configure(String existing, String region, boolean retail,
            String screenSize, boolean overrideRegion, String apiArg) {
        return configure(existing, region, retail, screenSize, overrideRegion, apiArg, false);
    }

    /** {@code gamepadUi} turns on Forever's gamepad UI ({@link #GAMEPAD_UI_CVAR}). */
    public static String configure(String existing, String region, boolean retail,
            String screenSize, boolean overrideRegion, String apiArg, boolean gamepadUi) {
        if (!validRegion(region)) throw new IllegalArgumentException("Invalid WoW region");
        LinkedHashMap<String, String> settings = new LinkedHashMap<>();
        // The native language/region dialogs run before D3D initializes. Seed
        // all three, preserving choices a player already made in the game.
        if (overrideRegion) settings.put("portal", region);
        else addMissing(settings, existing, "portal", region);
        addMissing(settings, existing, "textLocale", defaultLocale(region));
        addMissing(settings, existing, "audioLocale", defaultLocale(region));
        // Skip the first-boot photosensitivity warning and intro cinematic.
        addMissing(settings, existing, "showPhotosensitivityWarning", retail ? "11" : "4");
        addMissing(settings, existing, "playIntroMovie", retail ? "15" : "14");
        for (java.util.Map.Entry<String, String> entry : MemoryTransforms.resolutionCvars(screenSize).entrySet()) {
            addMissing(settings, existing, entry.getKey(), entry.getValue());
        }
        String gxApi = gxApi(apiArg);
        if (gxApi != null && !gxApi.equalsIgnoreCase(WowClientFiles.cvarValue(existing, "GxApi"))) settings.put("GxApi", gxApi);
        if (gamepadUi && !GAMEPAD_UI.equals(WowClientFiles.cvarValue(existing, GAMEPAD_UI_CVAR))) {
            settings.put(GAMEPAD_UI_CVAR, GAMEPAD_UI);
        }
        return MemoryTransforms.editConfigWtf(existing, settings);
    }

    /** GxApi value for a WoW API flag ("-d3d11" -> "D3D11"), or null for no or another flag. */
    public static String gxApi(String apiArg) {
        if ("-d3d11".equalsIgnoreCase(apiArg)) return "D3D11";
        if ("-d3d12".equalsIgnoreCase(apiArg)) return "D3D12";
        return null;
    }

    private static void addMissing(LinkedHashMap<String, String> settings, String existing, String name, String value) {
        String current = WowClientFiles.cvarValue(existing, name);
        if (current == null || current.isEmpty()) settings.put(name, value);
    }

    public static boolean prepare(File productDir, String region, boolean retail, String screenSize) throws IOException {
        return prepare(productDir, region, retail, screenSize, false);
    }

    public static boolean prepare(File productDir, String region, boolean retail,
            String screenSize, boolean overrideRegion) throws IOException {
        return prepare(productDir, region, retail, screenSize, overrideRegion, null);
    }

    public static boolean prepare(File productDir, String region, boolean retail,
            String screenSize, boolean overrideRegion, String apiArg) throws IOException {
        return prepare(productDir, region, retail, screenSize, overrideRegion, apiArg, false);
    }

    /** {@code forever} seeds the gamepad UI once per install (see {@link #GAMEPAD_UI_MARKER}). */
    public static boolean prepare(File productDir, String region, boolean retail,
            String screenSize, boolean overrideRegion, String apiArg, boolean forever) throws IOException {
        File config = new File(productDir, "WTF/Config.wtf");
        File gamepadUiMarker = new File(productDir, GAMEPAD_UI_MARKER);
        boolean seedGamepadUi = forever && !gamepadUiMarker.exists();
        String existing = config.isFile() ? new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8) : "";
        String updated = configure(existing, region, retail, screenSize, overrideRegion, apiArg, seedGamepadUi);
        boolean changed = !updated.equals(existing);
        if (changed) write(config, updated);
        if (seedGamepadUi) Files.write(gamepadUiMarker.toPath(), new byte[0]);
        return changed;
    }

    private static void write(File config, String updated) throws IOException {
        if (config.isFile()) {
            File backup = new File(config.getPath() + ".wehavephones-firstboot-backup");
            if (!backup.exists()) Files.copy(config.toPath(), backup.toPath());
        }
        Files.createDirectories(config.getParentFile().toPath());
        java.nio.file.Path temporary = Files.createTempFile(config.getParentFile().toPath(), "wehavephones-firstboot-", ".tmp");
        try {
            Files.write(temporary, updated.getBytes(StandardCharsets.UTF_8));
            if (!updated.equals(new String(Files.readAllBytes(temporary), StandardCharsets.UTF_8))) {
                throw new IOException("WoW first-boot settings failed read-back verification");
            }
            try {
                Files.move(temporary, config.toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temporary, config.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        finally { Files.deleteIfExists(temporary); }
    }
}
