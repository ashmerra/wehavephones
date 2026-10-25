package com.wehavephones.mobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Pure, Android-free transform layer for the memory trims and the game
 * resolution applied at every session start.
 *
 * <p>All values here are composed on top of the runtime profile that is active
 * at session start; the bundled {@code wehavephones-runtime-profiles.json} is never
 * rewritten. This class is also compiled by the host self-test
 * ({@code scripts/test-wehavephones-memory.sh}), so it must not import any Android
 * or org.json classes.</p>
 */
public final class MemoryTransforms {
    public static final String ENV_DXVK_HUD = "DXVK_HUD";
    public static final String ENV_MESA_SHADER_CACHE_MAX_SIZE = "MESA_SHADER_CACHE_MAX_SIZE";
    public static final String ENV_WRAPPER_MAX_IMAGE_COUNT = "WRAPPER_MAX_IMAGE_COUNT";
    public static final String ENV_DXVK_CONFIG_FILE = "DXVK_CONFIG_FILE";
    public static final String ENV_DXVK_CONFIG = "DXVK_CONFIG";

    /** DXVK config the removed low-memory mode generated; cleaned up when found. */
    public static final String LEGACY_DXVK_CONFIG_GUEST_PATH = "C:/wehavephones-dxvk.conf";
    public static final String LEGACY_DXVK_CONFIG_FILE_NAME = "wehavephones-dxvk.conf";

    /** Games start at this resolution; Settings can raise it. */
    public static final String DEFAULT_SCREEN_SIZE = "1024x576";
    public static final String[] SCREEN_SIZES = {"1024x576", "1280x720", "1600x900", "1920x1080"};
    /**
     * Heights of the sizes shaped like the device's screen, so the game fills
     * a wider or squarer screen than 16:9 without black bars (#26, #27).
     */
    public static final int[] FITTED_HEIGHTS = {576, 720, 900};
    public static final String MESA_CACHE = "256MB";
    public static final String WRAPPER_IMAGE_COUNT = "2";
    /**
     * DXVK 2.3 suballocates GPU memory from 256 MB chunks. In Forever on the
     * S23 they held about 370 MB more than the game used. 32 MB chunks cut
     * that by about 180 MB, and 16 MB gained little more.
     */
    public static final String DXVK_CHUNK_OPTION = "dxvk.maxChunkSize";
    public static final String DXVK_CHUNK_MB = "32";

    /** Keys always removed; {@link #envOverrides()} replaces the rest. */
    public static final String[] REMOVED_ENV_KEYS = {
            ENV_DXVK_HUD
    };

    private MemoryTransforms() {}

    /** Environment values added on top of the profile env. */
    public static LinkedHashMap<String, String> envOverrides() {
        LinkedHashMap<String, String> overrides = new LinkedHashMap<>();
        overrides.put(ENV_WRAPPER_MAX_IMAGE_COUNT, WRAPPER_IMAGE_COUNT);
        overrides.put(ENV_MESA_SHADER_CACHE_MAX_SIZE, MESA_CACHE);
        return overrides;
    }

    /**
     * Compose the effective container env: remove the debug-only DXVK HUD and
     * the old low-memory DXVK config, then replace the override keys and set
     * the DXVK chunk size inside {@code DXVK_CONFIG} (any other token or DXVK
     * option, including a profile's own {@code DXVK_CONFIG_FILE}, is
     * preserved). The result is byte-stable when applied repeatedly.
     */
    public static String composeEnv(String envVars) {
        String result = envVars != null ? envVars : "";
        for (String key : REMOVED_ENV_KEYS) result = removeEnvVar(result, key);
        if (LEGACY_DXVK_CONFIG_GUEST_PATH.equals(parseEnv(result).get(ENV_DXVK_CONFIG_FILE))) {
            result = removeEnvVar(result, ENV_DXVK_CONFIG_FILE);
        }
        for (Map.Entry<String, String> entry : envOverrides().entrySet()) {
            String updated = setEnvVar(result, entry.getKey(), entry.getValue());
            if (updated != null) result = updated;
        }
        String dxvkConfig = withDxvkOption(parseEnv(result).get(ENV_DXVK_CONFIG), DXVK_CHUNK_OPTION, DXVK_CHUNK_MB);
        String updated = setEnvVar(result, ENV_DXVK_CONFIG, dxvkConfig);
        return updated != null ? updated : result;
    }

    /**
     * {@code config} in {@code DXVK_CONFIG}'s {@code a=1;b=2} form with
     * {@code option} set to {@code value} last. Other options keep their order.
     */
    public static String withDxvkOption(String config, String option, String value) {
        StringBuilder builder = new StringBuilder();
        if (config != null) {
            for (String entry : config.split(";")) {
                int index = entry.indexOf('=');
                if (entry.trim().isEmpty() || (index >= 0 ? entry.substring(0, index) : entry).trim().equals(option)) continue;
                if (builder.length() > 0) builder.append(';');
                builder.append(entry);
            }
        }
        if (builder.length() > 0) builder.append(';');
        return builder.append(option).append('=').append(value).toString();
    }

    /** True when {@code envVars} carries the DXVK chunk size in {@code DXVK_CONFIG}. */
    public static boolean hasDxvkChunkSize(String envVars) {
        String config = parseEnv(envVars).get(ENV_DXVK_CONFIG);
        return config != null && Arrays.asList(config.split(";")).contains(DXVK_CHUNK_OPTION + "=" + DXVK_CHUNK_MB);
    }

    /** A {@code WIDTHxHEIGHT} screen size, or {@link #DEFAULT_SCREEN_SIZE} when unset or malformed. */
    public static String screenSizeOrDefault(String screenSize) {
        return screenSize != null && screenSize.matches("[1-9][0-9]{2,4}x[1-9][0-9]{2,4}") ? screenSize : DEFAULT_SCREEN_SIZE;
    }

    /**
     * The device's own display size as a landscape {@code WIDTHxHEIGHT} (long
     * side first, since the game always runs in landscape), or null when the
     * size is unknown or outside what {@link #screenSizeOrDefault} accepts.
     */
    public static String nativeScreenSize(int width, int height) {
        String size = Math.max(width, height) + "x" + Math.min(width, height);
        return size.equals(screenSizeOrDefault(size)) ? size : null;
    }

    /**
     * Sizes with {@code nativeSize}'s aspect ratio at each of
     * {@link #FITTED_HEIGHTS} below the native height, widths rounded to an
     * even number. Empty when the native size is unknown or within 2% of
     * 16:9, which {@link #SCREEN_SIZES} already fits.
     */
    public static String[] fittedScreenSizes(String nativeSize) {
        ArrayList<String> sizes = new ArrayList<>();
        if (nativeSize == null || !nativeSize.equals(screenSizeOrDefault(nativeSize))) return new String[0];
        String[] parts = nativeSize.split("x");
        int nativeWidth = Integer.parseInt(parts[0]);
        int nativeHeight = Integer.parseInt(parts[1]);
        double aspect = (double)nativeWidth / nativeHeight;
        if (Math.abs(aspect / (16.0 / 9.0) - 1.0) < 0.02) return new String[0];
        for (int height : FITTED_HEIGHTS) {
            if (height >= nativeHeight) break;
            String size = Math.round(height * aspect / 2.0) * 2 + "x" + height;
            if (!Arrays.asList(SCREEN_SIZES).contains(size)) sizes.add(size);
        }
        return sizes.toArray(new String[0]);
    }

    /**
     * The fixed {@link #SCREEN_SIZES}, then the {@link #fittedScreenSizes},
     * then the native size last, when known and not already listed.
     */
    public static String[] screenSizeOptions(String nativeSize) {
        ArrayList<String> options = new ArrayList<>(Arrays.asList(SCREEN_SIZES));
        options.addAll(Arrays.asList(fittedScreenSizes(nativeSize)));
        if (nativeSize != null && !options.contains(nativeSize)) options.add(nativeSize);
        return options.toArray(new String[0]);
    }

    /**
     * WoW CVars that keep the game window the size of the X screen, so a
     * windowed client neither overflows a smaller screen nor sits in a corner
     * of a larger one. {@code GxMaximize} is WoW's own "Windowed (Fullscreen)"
     * default; a client left un-maximized shows a bordered window instead.
     */
    public static LinkedHashMap<String, String> resolutionCvars(String screenSize) {
        LinkedHashMap<String, String> settings = new LinkedHashMap<>();
        settings.put("GxMaximize", "1");
        settings.put("GxWindowedResolution", screenSize);
        return settings;
    }

    /** Parse "KEY=value KEY2=value2" into a map; invalid tokens are ignored. */
    public static LinkedHashMap<String, String> parseEnv(String envVars) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (envVars == null) return values;
        for (String token : envVars.split("\\s+")) {
            if (token.isEmpty()) continue;
            int index = token.indexOf('=');
            if (index <= 0) continue;
            values.put(token.substring(0, index), token.substring(index + 1));
        }
        return values;
    }

    /**
     * Replace or append {@code KEY=value} in a whitespace-separated env string.
     * Returns null when the key or value cannot be represented safely.
     */
    public static String setEnvVar(String envVars, String key, String value) {
        if (envVars == null) envVars = "";
        if (key == null || key.isEmpty() || !key.matches("[A-Za-z_][A-Za-z0-9_]*")) return null;
        if (value == null || value.matches(".*[\\s].*")) return null;

        StringBuilder builder = new StringBuilder();
        String prefix = key + "=";
        for (String token : envVars.split("\\s+")) {
            if (token.isEmpty() || token.startsWith(prefix)) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(token);
        }
        if (builder.length() > 0) builder.append(' ');
        builder.append(prefix).append(value);
        return builder.toString();
    }

    /** Remove every {@code KEY=...} token for {@code key}. */
    public static String removeEnvVar(String envVars, String key) {
        if (envVars == null) return "";
        StringBuilder builder = new StringBuilder();
        String prefix = key + "=";
        for (String token : envVars.split("\\s+")) {
            if (token.isEmpty() || token.startsWith(prefix)) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(token);
        }
        return builder.toString();
    }

    /**
     * Round-trip-safe Config.wtf edit: every managed key is removed and
     * re-appended in the map's order, the file's newline style is preserved and
     * unrelated lines keep their position. Applied twice it is idempotent.
     */
    public static String editConfigWtf(String original, Map<String, String> settings) {
        if (original == null) original = "";
        String newline = original.contains("\r\n") ? "\r\n" : "\n";
        Set<String> managed = lowercaseKeys(settings.keySet());

        ArrayList<String> lines = new ArrayList<>();
        if (!original.isEmpty()) {
            for (String line : original.split("\r\n|\r|\n")) {
                if (managed.contains(wtfKey(line))) continue;
                lines.add(line);
            }
        }
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            lines.add("SET " + entry.getKey() + " \"" + entry.getValue() + "\"");
        }

        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) builder.append(newline);
            builder.append(lines.get(i));
        }
        if (builder.length() > 0) builder.append(newline);
        return builder.toString();
    }

    /** True when every managed key is present with exactly the wanted value. */
    public static boolean configWtfContains(String content, Map<String, String> settings) {
        LinkedHashMap<String, String> actual = parseWtfSettings(content);
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String value = actual.get(entry.getKey().toLowerCase(Locale.US));
            if (value == null || !value.equals(entry.getValue())) return false;
        }
        return true;
    }

    /** Parse {@code SET key "value"} lines into a lowercase-key map. */
    public static LinkedHashMap<String, String> parseWtfSettings(String content) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (content == null) return values;
        for (String line : content.split("\r\n|\r|\n")) {
            String trimmed = line.trim();
            if (!trimmed.toLowerCase(Locale.US).startsWith("set ")) continue;
            String rest = trimmed.substring(4).trim();
            int separator = 0;
            while (separator < rest.length() && !Character.isWhitespace(rest.charAt(separator))) separator++;
            if (separator == 0) continue;
            String key = rest.substring(0, separator).toLowerCase(Locale.US);
            String value = rest.substring(separator).trim();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }

    private static Set<String> lowercaseKeys(Set<String> keys) {
        Set<String> result = new TreeSet<>();
        for (String key : keys) result.add(key.toLowerCase(Locale.US));
        return result;
    }

    private static String wtfKey(String line) {
        String trimmed = line != null ? line.trim() : "";
        String lower = trimmed.toLowerCase(Locale.US);
        if (!lower.startsWith("set ")) return "";
        String rest = trimmed.substring(4).trim();
        if (rest.isEmpty()) return "";
        int end = 0;
        while (end < rest.length() && !Character.isWhitespace(rest.charAt(end)) && rest.charAt(end) != '"') end++;
        return rest.substring(0, end).toLowerCase(Locale.US);
    }
}
