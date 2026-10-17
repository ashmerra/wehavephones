package com.wehavephones.mobile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Catalog of every World of Warcraft game Battle.net installs under
 * {@code World of Warcraft\_<product>_}: identity (family, release channel,
 * display names), game executables, and the addon-provider mappings.
 *
 * <p>Detection prefers the install directory, except that a client whose
 * {@code .build.info} version belongs to World of Warcraft: Forever (1.60+)
 * is always Forever: the Forever client is product code {@code wowf} in
 * {@code _forever_} (offered as the October 2026 Forever Stress Test and
 * reused for the live game launching 2026-11-04), and the beta is product
 * code {@code wow_classic_beta} in {@code _classic_beta_} (WowB.exe, 1.60.1).
 * That beta directory is shared with the Classic progression beta, so a
 * Classic-line build there is still the Classic beta. Any other directory
 * with a Forever build is caught by the version.
 *
 * <p>Pure Java on purpose: the host JVM self-tests compile this class.
 */
public enum WowFlavor {
    RETAIL("_retail_", "wow", "Retail", Family.RETAIL, Release.LIVE, 517, "retail",
            Arrays.asList("Standard", "Mainline"),
            new String[]{"Standard", "Mainline"}, 120100),
    RETAIL_PTR("_ptr_", "wowt", "Retail PTR", Family.RETAIL, Release.PTR, 517, "retail",
            Arrays.asList("Mainline"),
            new String[]{"Mainline", "Standard"}, 120100),
    RETAIL_XPTR("_xptr_", "wowxptr", "Retail XPTR", Family.RETAIL, Release.PTR, 517, "retail",
            Arrays.asList("Mainline"),
            new String[]{"Mainline", "Standard"}, 120007),
    RETAIL_BETA("_beta_", "wow_beta", "Retail Beta", Family.RETAIL, Release.BETA, 517, "retail",
            Arrays.asList("Mainline"),
            new String[]{"Mainline", "Standard"}, 120001),
    // CurseForge 88568 and the Forever suffix order are unverified.
    // The Forever product codes (wowf, wow_classic_beta) and directories
    // (_forever_, _classic_beta_) come from the Battle.net launcher's
    // ProductManager/InstallState data.
    FOREVER("_forever_", "wowf", "Forever Stress Test", Family.FOREVER, Release.LIVE, 88568, "forever",
            Arrays.asList("Forever", "Camelot"),
            new String[]{"Forever", "Camelot", "Mainline"}, 16001),
    FOREVER_PTR("_forever_ptr_", "wow_forever_ptr", "Forever PTR", Family.FOREVER, Release.PTR, 88568, "forever",
            Arrays.asList("Forever", "Camelot"),
            new String[]{"Forever", "Camelot", "Mainline"}, 16001),
    // The Forever beta is the launcher's wow_classic_beta (productCode
    // WoWF_wow_classic_beta), shipping in _classic_beta_.
    FOREVER_BETA("_classic_beta_", "wow_classic_beta", "Forever Beta", Family.FOREVER, Release.BETA, 88568, "forever",
            Arrays.asList("Forever", "Camelot"),
            new String[]{"Forever", "Camelot", "Mainline"}, 16001),
    CLASSIC("_classic_", "wow_classic", "MoP Classic", Family.CLASSIC, Release.LIVE, 79434, "mop",
            Arrays.asList("Mists", "Classic"),
            new String[]{"Mists", "Classic"}, 50504),
    CLASSIC_PTR("_classic_ptr_", "wow_classic_ptr", "Classic PTR", Family.CLASSIC, Release.PTR, 79434, "mop",
            Arrays.asList("Mists", "Classic"),
            new String[]{"Mists", "Classic"}, 50504),
    // The Classic progression beta shares _classic_beta_ with the Forever beta;
    // it has no directory of its own, so it is resolved by version only
    // (see detect). Keeps its MoP addon mappings.
    CLASSIC_BETA("", "", "Classic Beta", Family.CLASSIC, Release.BETA, 79434, "mop",
            Arrays.asList("Mists", "Classic"),
            new String[]{"Mists", "Classic"}, 50500),
    TITAN("_classic_titan_", "wow_classic_titan", "Wrath / Titan", Family.TITAN, Release.LIVE, 81212, "titan",
            Arrays.asList("Wrath", "Classic"),
            new String[]{"Wrath", "WOTLKC", "Classic"}, 38002),
    ANNIVERSARY("_anniversary_", "wow_anniversary", "Anniversary (TBC)", Family.ANNIVERSARY, Release.LIVE, 73246, "bc",
            Arrays.asList("TBC", "BCC", "Classic"),
            new String[]{"TBC", "BCC", "Classic"}, 20506),
    CLASSIC_ERA("_classic_era_", "wow_classic_era", "Classic Era", Family.CLASSIC_ERA, Release.LIVE, 67408, "classic",
            Arrays.asList("Vanilla", "Classic"),
            new String[]{"Vanilla", "Classic"}, 11509),
    CLASSIC_ERA_PTR("_classic_era_ptr_", "wow_classic_era_ptr", "Classic Era PTR", Family.CLASSIC_ERA, Release.PTR, 67408, "classic",
            Arrays.asList("Vanilla", "Classic"),
            new String[]{"Vanilla", "Classic"}, 11509),
    CLASSIC_ERA_BETA("_classic_era_beta_", "wow_classic_era_beta", "Classic Era Beta", Family.CLASSIC_ERA, Release.BETA, 67408, "classic",
            Arrays.asList("Vanilla", "Classic"),
            new String[]{"Vanilla", "Classic"}, 11509),
    UNKNOWN("", "", "Unknown", null, Release.LIVE, 0, "", Collections.<String>emptyList(),
            new String[0], 0);

    /** Game line, shown as the big title; accent colors are WoW class/item-quality colors (ARGB). */
    public enum Family {
        RETAIL("World of Warcraft", 0xFFFF8000),
        FOREVER("World of Warcraft: Forever", 0xFFA335EE),
        CLASSIC("World of Warcraft Classic", 0xFF00FF98),
        TITAN("Titan-Reforged Classic", 0xFF3FC7EB),
        ANNIVERSARY("Classic Anniversary", 0xFFAAD372),
        CLASSIC_ERA("Classic Era", 0xFFC69B6D);

        public final String title;
        public final int accentColor;

        Family(String title, int accentColor) {
            this.title = title;
            this.accentColor = accentColor;
        }

        /**
         * Game line implied by a client version (major.minor). Only used when
         * the directory is not in the catalog, plus the Forever override.
         */
        public static Family forVersion(String version) {
            int[] parts = versionParts(version);
            if (parts == null) return null;
            int major = parts[0];
            int minor = parts[1];
            if (major == 1) return minor >= 60 ? FOREVER : CLASSIC_ERA;
            if (major == 2) return ANNIVERSARY;
            if (major == 3 && minor >= 80) return TITAN;
            if (major >= 3 && major <= 9) return CLASSIC;
            return major >= 10 ? RETAIL : null;
        }
    }

    /** Battle.net release channel of an install ("Game Version" menu). */
    public enum Release {
        LIVE(""),
        PTR("PTR"),
        BETA("Beta");

        public final String label;

        Release(String label) {
            this.label = label;
        }

        static Release forDirectory(String directoryName) {
            String key = directoryName != null ? directoryName.toLowerCase(Locale.US) : "";
            if (key.contains("beta")) return BETA;
            if (key.contains("ptr")) return PTR;
            return LIVE;
        }
    }

    /** Suffixes recognized on TOC file names (without the leading underscore). */
    public static final List<String> KNOWN_SUFFIXES = Collections.unmodifiableList(Arrays.asList(
            "Standard", "Mainline", "Classic", "Vanilla", "TBC", "BCC", "Wrath",
            "WOTLKC", "Cata", "Mists", "Camelot", "Forever", "Plunderstorm",
            "WoWLabs", "WoWHack"));

    /** Known game executables at a product root, native ARM64 builds first. */
    public static final List<String> EXECUTABLES = Collections.unmodifiableList(Arrays.asList(
            "Wow-ARM64.exe", "WowT-ARM64.exe", "WowB-ARM64.exe",
            "WowClassic-arm64.exe", "WowClassicT-arm64.exe", "WowClassicB-arm64.exe",
            "Wow.exe", "WowT.exe", "WowB.exe", "WowClassic.exe", "WowClassicT.exe", "WowClassicB.exe"));

    private static final Pattern GAME_PRODUCT_CODE = Pattern.compile("wow(t|f|xptr|_[a-z0-9_]+)?");

    /** Any Wow*.exe game client, including builds not listed above; excludes the voice helper and WowUp. */
    private static final Pattern GAME_EXECUTABLE = Pattern.compile("(?i)wow[a-z]*(-arm64)?\\.exe");

    public final String directoryName;
    /** Battle.net (TACT) product code, as in product.db, .build.info and .flavor.info. */
    public final String productCode;
    public final String displayName;
    public final Family family;
    public final Release release;
    public final int curseForgeGameVersionTypeId;
    public final String wagoKey;
    public final List<String> patchFlavors;
    public final String[] preferredSuffixes;
    public final int fallbackInterface;

    WowFlavor(String directoryName, String productCode, String displayName, Family family, Release release,
              int curseForgeGameVersionTypeId, String wagoKey, List<String> patchFlavors,
              String[] preferredSuffixes, int fallbackInterface) {
        this.directoryName = directoryName;
        this.productCode = productCode;
        this.displayName = displayName;
        this.family = family;
        this.release = release;
        this.curseForgeGameVersionTypeId = curseForgeGameVersionTypeId;
        this.wagoKey = wagoKey;
        this.patchFlavors = patchFlavors;
        this.preferredSuffixes = preferredSuffixes;
        this.fallbackInterface = fallbackInterface;
    }

    public static WowFlavor forDirectory(String directoryName) {
        if (directoryName == null) return UNKNOWN;
        String key = directoryName.trim().toLowerCase(Locale.US);
        for (WowFlavor flavor : values()) {
            if (!flavor.directoryName.isEmpty() && flavor.directoryName.equals(key)) return flavor;
        }
        return UNKNOWN;
    }

    public static WowFlavor forProductCode(String productCode) {
        if (productCode == null) return UNKNOWN;
        String key = productCode.trim().toLowerCase(Locale.US);
        for (WowFlavor flavor : values()) {
            if (!flavor.productCode.isEmpty() && flavor.productCode.equals(key)) return flavor;
        }
        return UNKNOWN;
    }

    /**
     * True for a World of Warcraft game product code ({@code wow}, {@code wowt},
     * {@code wowf}, {@code wowxptr}, {@code wow_<anything>}); false for the
     * Agent, Battle.net itself, other games and internal WoW builds such as
     * {@code wowdev}.
     */
    public static boolean isGameProductCode(String productCode) {
        return productCode != null && GAME_PRODUCT_CODE.matcher(productCode.trim().toLowerCase(Locale.US)).matches();
    }

    /** Like {@link #detect(String, String)}, using the product code when the directory is not in the catalog. */
    public static WowFlavor detect(String directoryName, String productCode, String version) {
        WowFlavor byDirectory = forDirectory(directoryName);
        if (!byDirectory.isKnown()) {
            WowFlavor byCode = forProductCode(productCode);
            if (byCode.isKnown()) return detect(byCode.directoryName, version);
        }
        return detect(directoryName, version);
    }

    /**
     * Flavor of an install from its directory and {@code .build.info} version.
     * A Forever version wins over the directory name; otherwise a known
     * directory wins; unknown directories are classified by version.
     */
    public static WowFlavor detect(String directoryName, String version) {
        WowFlavor byDirectory = forDirectory(directoryName);
        Family byVersion = Family.forVersion(version);
        if (byVersion == Family.FOREVER && byDirectory.family != Family.FOREVER) {
            return of(Family.FOREVER, byDirectory.isKnown() ? byDirectory.release : Release.forDirectory(directoryName));
        }
        // The Forever beta shares _classic_beta_ with the Classic progression
        // beta: a Classic-line build there is the Classic beta, not Forever.
        if (byDirectory == FOREVER_BETA && byVersion == Family.CLASSIC) {
            return of(Family.CLASSIC, Release.BETA);
        }
        if (byDirectory.isKnown() || byVersion == null) return byDirectory;
        return of(byVersion, Release.forDirectory(directoryName));
    }

    /** Flavor for a family and channel, falling back to the family's live game. */
    public static WowFlavor of(Family family, Release release) {
        WowFlavor live = UNKNOWN;
        for (WowFlavor flavor : values()) {
            if (flavor.family != family) continue;
            if (flavor.release == release) return flavor;
            if (flavor.release == Release.LIVE && live == UNKNOWN) live = flavor;
        }
        return live;
    }

    public boolean isKnown() {
        return this != UNKNOWN;
    }

    /** PTR/beta variants inherit their parent flavor's provider mappings. */
    public WowFlavor baseFlavor() {
        return release == Release.LIVE || family == null ? this : of(family, Release.LIVE);
    }

    public boolean supportsWago() {
        return !wagoKey.isEmpty();
    }

    public String displayName() {
        return displayName;
    }

    // ---- presentation ------------------------------------------------------------------

    /** Big title for an install, e.g. "World of Warcraft Classic". */
    public static String gameTitle(WowFlavor flavor, String directoryName) {
        if (flavor != null && flavor.family != null) return flavor.family.title;
        return prettyDirectory(directoryName);
    }

    /**
     * Subtitle: expansion or era from the client version plus the channel,
     * e.g. "Midnight", "Mists of Pandaria · PTR", "Beta".
     */
    public static String edition(WowFlavor flavor, String version) {
        String era = eraForVersion(version);
        if (flavor != null && flavor.family != null && flavor.family.title.contains(era)) era = "";
        String channel = flavor != null ? flavor.release.label : "";
        if (era.isEmpty()) return channel;
        return channel.isEmpty() ? era : era + " · " + channel;
    }

    /** Expansion/era for a client version; empty when unknown. */
    public static String eraForVersion(String version) {
        int[] parts = versionParts(version);
        if (parts == null) return "";
        int major = parts[0];
        int minor = parts[1];
        switch (major) {
            case 1: return minor >= 60 ? "Forever" : "Vanilla";
            case 2: return "The Burning Crusade";
            case 3: return minor >= 80 ? "Titan-Reforged" : "Wrath of the Lich King";
            case 4: return "Cataclysm";
            case 5: return "Mists of Pandaria";
            case 6: return "Warlords of Draenor";
            case 7: return "Legion";
            case 8: return "Battle for Azeroth";
            case 9: return "Shadowlands";
            case 10: return "Dragonflight";
            case 11: return "The War Within";
            case 12: return "Midnight";
            case 13: return "The Last Titan";
            default: return "";
        }
    }

    /** "_classic_era_ptr_" -> "Classic Era Ptr" for directories not in the catalog. */
    public static String prettyDirectory(String directoryName) {
        if (directoryName == null) return "";
        String cleaned = directoryName.replaceAll("^_+|_+$", "").replace('_', ' ');
        StringBuilder builder = new StringBuilder();
        for (String part : cleaned.split(" ")) {
            if (part.isEmpty()) continue;
            if (builder.length() > 0) builder.append(' ');
            builder.append(Character.toUpperCase(part.charAt(0))).append(part.substring(1));
        }
        return builder.length() > 0 ? builder.toString() : directoryName;
    }

    // ---- executables -------------------------------------------------------------------

    /** True for a WoW game client file name (basename, any case). */
    public static boolean isGameExecutable(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase(Locale.US);
        // WowUp (an addon manager) matches the pattern but is not the game.
        return GAME_EXECUTABLE.matcher(lower).matches() && !lower.startsWith("wowvoiceproxy")
                && !lower.equals("wowup.exe");
    }

    /**
     * Best game executable among the file names at a product root: the known
     * list in order (ARM64 first), then any other Wow*.exe, ARM64 first.
     * Returns "" when there is none.
     */
    public static String pickExecutable(List<String> fileNames) {
        if (fileNames == null) return "";
        for (String known : EXECUTABLES) {
            for (String name : fileNames) {
                if (known.equalsIgnoreCase(name)) return name;
            }
        }
        String fallback = "";
        for (String name : fileNames) {
            if (!isGameExecutable(name)) continue;
            if (name.toLowerCase(Locale.US).contains("-arm64")) return name;
            if (fallback.isEmpty()) fallback = name;
        }
        return fallback;
    }

    // ---- versions ----------------------------------------------------------------------

    /** major, minor of a version such as "12.1.0.69933"; null when unparseable. */
    static int[] versionParts(String version) {
        if (version == null) return null;
        String[] parts = version.trim().replaceFirst("^[vV]", "").split("\\.");
        if (parts.length < 2) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        }
        catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Computes the interface number from a client version such as 12.1.0.63206.
     * Returns 0 when the version cannot be interpreted.
     */
    public static int interfaceFromVersion(String version) {
        if (version == null) return 0;
        String cleaned = version.trim();
        if (cleaned.startsWith("v") || cleaned.startsWith("V")) cleaned = cleaned.substring(1);
        String[] parts = cleaned.split("[^0-9]+");
        ArrayList<Integer> numbers = new ArrayList<>();
        for (String part : parts) {
            if (part.isEmpty()) continue;
            try {
                numbers.add(Integer.parseInt(part));
            }
            catch (NumberFormatException ignored) {
                break;
            }
            if (numbers.size() == 4) break;
        }
        if (numbers.size() < 3) return 0;
        int major = numbers.get(0);
        int minor = numbers.get(1);
        int patch = numbers.get(2);
        if (major <= 0 || minor > 99 || patch > 99) return 0;
        return major * 10000 + minor * 100 + patch;
    }
}
