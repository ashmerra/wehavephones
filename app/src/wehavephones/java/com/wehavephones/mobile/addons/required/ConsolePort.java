package com.wehavephones.mobile.addons.required;

import com.wehavephones.mobile.WowFlavor;
import com.wehavephones.mobile.addons.install.AddonInstaller;
import com.wehavephones.mobile.addons.install.DiscoveredAddon;
import com.wehavephones.mobile.addons.install.FileOps;
import com.wehavephones.mobile.addons.install.SafeZipExtractor;
import com.wehavephones.mobile.addons.install.ZipImporter;
import com.wehavephones.mobile.addons.install.ZipSecurityException;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.TocData;
import com.wehavephones.mobile.addons.scan.TocParser;
import com.wehavephones.mobile.addons.scan.VersionComparator;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ConsolePort (github.com/seblindfors/ConsolePort) is the gamepad addon suite
 * every WoW client except Forever (native controller support) needs on
 * Android, so the app keeps it installed in every such product
 * (docs/WEHAVEPHONES_ADDONS.md §9). One release archive serves every flavor; its
 * TOC lists all interface numbers. Updates always come from GitHub releases,
 * which need no API key, even though the TOC names a CurseForge project.
 *
 * <p>Pure Java (host-testable).
 */
public final class ConsolePort {
    public static final String NAME = "ConsolePort";
    public static final String GITHUB_REPO = "seblindfors/ConsolePort";
    public static final String CURSEFORGE_ID = "91376";
    public static final String WAGO_ID = "LvNAYjNo";
    /** The core folder; the other modules are {@code ConsolePort_*}. */
    public static final String CORE_FOLDER = "ConsolePort";

    private static final String DISABLED_SUFFIX = ".disabled";

    private ConsolePort() {}

    /** Forever supports controllers natively; every other game (and unknown ones) gets ConsolePort. */
    public static boolean requiredFor(WowFlavor flavor) {
        return flavor == null || flavor.family != WowFlavor.Family.FOREVER;
    }

    /** {@code ConsolePort} or a {@code ConsolePort_<module>} folder, with or without {@code .disabled}. */
    public static boolean isConsolePortFolder(String folderName) {
        if (folderName == null) return false;
        String name = folderName.toLowerCase(Locale.US);
        if (name.endsWith(DISABLED_SUFFIX)) name = name.substring(0, name.length() - DISABLED_SUFFIX.length());
        return name.equals("consoleport") || name.startsWith("consoleport_");
    }

    public static boolean isConsolePort(TocData toc, String folderName) {
        if (toc != null && (CURSEFORGE_ID.equals(trim(toc.curseId)) || WAGO_ID.equals(trim(toc.wagoId)))) {
            return true;
        }
        return isConsolePortFolder(folderName);
    }

    public static boolean isConsolePort(AddonRecord record) {
        if (record == null) return false;
        if (ProviderType.GITHUB.id.equals(record.providerId) && GITHUB_REPO.equalsIgnoreCase(record.externalId)) {
            return true;
        }
        for (String folder : record.folderNames) {
            if (isConsolePortFolder(folder)) return true;
        }
        return false;
    }

    public static TocData.ProviderBinding binding() {
        return new TocData.ProviderBinding(ProviderType.GITHUB, GITHUB_REPO);
    }

    /** Installed core version, or "" when ConsolePort is missing or its version is unreadable. */
    public static String installedVersion(File addonsDir) {
        File core = coreFolder(addonsDir);
        if (core == null) return "";
        File toc = FileOps.findCaseInsensitive(core, CORE_FOLDER + ".toc");
        if (toc == null) toc = TocParser.chooseBestToc(core, WowFlavor.UNKNOWN);
        if (toc == null) return "";
        try {
            return TocParser.parse(toc, CORE_FOLDER).versionOrUnknown();
        }
        catch (IOException e) {
            return "";
        }
    }

    /** True when the user disabled the core folder; installs then keep the whole suite disabled. */
    public static boolean isDisabled(File addonsDir) {
        return addonsDir != null
                && FileOps.findCaseInsensitive(addonsDir, CORE_FOLDER) == null
                && FileOps.findCaseInsensitive(addonsDir, CORE_FOLDER + DISABLED_SUFFIX) != null;
    }

    /** Missing, unreadable or older than the available package. */
    public static boolean needsInstall(String installedVersion, String availableVersion) {
        if (VersionComparator.isUnknown(installedVersion)) return true;
        return VersionComparator.isNewer(availableVersion, installedVersion);
    }

    /** ConsolePort folders currently in the AddOns directory, without the {@code .disabled} suffix. */
    public static List<String> installedFolders(File addonsDir) {
        ArrayList<String> folders = new ArrayList<>();
        for (String name : FileOps.listDirectoryNames(addonsDir)) {
            if (!isConsolePortFolder(name)) continue;
            String logical = name.toLowerCase(Locale.US).endsWith(DISABLED_SUFFIX)
                    ? name.substring(0, name.length() - DISABLED_SUFFIX.length()) : name;
            if (!folders.contains(logical)) folders.add(logical);
        }
        return folders;
    }

    /**
     * Installs or updates the suite from a release archive with the atomic
     * installer: modules dropped by the new release are removed, and a
     * disabled suite stays disabled. Returns the installed folder names.
     */
    public static List<String> install(File archive, File addonsDir, WowFlavor flavor)
            throws IOException, ZipSecurityException {
        if (addonsDir == null) throw new IOException("No AddOns directory");
        if (!addonsDir.isDirectory() && !addonsDir.mkdirs()) {
            throw new IOException("Cannot create " + addonsDir);
        }
        AddonInstaller.recoverInterrupted(addonsDir);
        File staging = new File(addonsDir, ".wehavephones-staging-" + FileOps.uuid());
        try {
            SafeZipExtractor.extract(archive, staging, null);
            ArrayList<DiscoveredAddon> suite = new ArrayList<>();
            boolean hasCore = false;
            for (DiscoveredAddon addon : ZipImporter.discoverAddons(staging, flavor)) {
                if (!isConsolePortFolder(addon.folderName)) continue;
                suite.add(addon);
                if (addon.folderName.equalsIgnoreCase(CORE_FOLDER)) hasCore = true;
            }
            if (!hasCore) throw new IOException("Archive does not contain the ConsolePort addon");
            AddonInstaller.Result result = AddonInstaller.install(new AddonInstaller.Request(
                    addonsDir, staging, suite, installedFolders(addonsDir), isDisabled(addonsDir)));
            AddonInstaller.commit(result);
            return result.installedFolders;
        }
        finally {
            FileOps.deleteRecursively(staging);
        }
    }

    private static File coreFolder(File addonsDir) {
        if (addonsDir == null || !addonsDir.isDirectory()) return null;
        File core = FileOps.findCaseInsensitive(addonsDir, CORE_FOLDER);
        if (core == null) core = FileOps.findCaseInsensitive(addonsDir, CORE_FOLDER + DISABLED_SUFFIX);
        return core != null && core.isDirectory() ? core : null;
    }

    private static String trim(String value) {
        return value != null ? value.trim() : "";
    }
}
