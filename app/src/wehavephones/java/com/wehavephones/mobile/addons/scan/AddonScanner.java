package com.wehavephones.mobile.addons.scan;

import com.wehavephones.mobile.addons.model.AddonFolderInfo;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.TocData;
import com.wehavephones.mobile.addons.required.ConsolePort;
import com.wehavephones.mobile.WowFlavor;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Scans an Interface/AddOns directory, parses TOC files and groups physical
 * folders into logical addons. Pure Java (host-testable).
 */
public final class AddonScanner {
    private AddonScanner() {}

    /**
     * A folder written this long after we recorded its install counts as an
     * external rewrite. The gap absorbs filesystem timestamp rounding and the
     * small delay between moving an archive into place and recording it.
     */
    private static final long EXTERNAL_WRITE_SLACK_MS = 5000L;

    public static final class ScanResult {
        public final ArrayList<AddonRecord> records = new ArrayList<>();
        public final ArrayList<String> unrecognizedFolders = new ArrayList<>();
        public final ArrayList<AddonFolderInfo> folders = new ArrayList<>();
    }

    public static ScanResult scan(File addonsDir, WowFlavor flavor, List<AddonRecord> existing) {
        ScanResult result = new ScanResult();
        if (addonsDir == null || !addonsDir.isDirectory()) return result;

        ArrayList<AddonFolderInfo> folders = new ArrayList<>();
        File[] children = addonsDir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (!child.isDirectory()) continue;
                if (isManagedOrHidden(child.getName())) continue;
                String folderName = child.getName();
                boolean disabled = folderName.toLowerCase(Locale.US).endsWith(".disabled");
                String logicalName = disabled
                        ? folderName.substring(0, folderName.length() - ".disabled".length())
                        : folderName;
                File tocFile = TocParser.chooseBestToc(child, flavor);
                if (tocFile == null) {
                    result.unrecognizedFolders.add(folderName);
                    continue;
                }
                TocData toc;
                try {
                    toc = TocParser.parse(tocFile, logicalName);
                }
                catch (IOException e) {
                    result.unrecognizedFolders.add(folderName);
                    continue;
                }
                if (toc.versionOrUnknown().isEmpty()) {
                    String fallback = TocParser.versionFromName(logicalName);
                    if (!fallback.isEmpty()) toc.version = fallback;
                }
                AddonFolderInfo info = new AddonFolderInfo(folderName, logicalName, disabled, toc, child);
                folders.add(info);
                result.folders.add(info);
            }
        }

        LinkedHashMap<String, ArrayList<AddonFolderInfo>> groups = new LinkedHashMap<>();
        for (AddonFolderInfo folder : folders) {
            String key = groupKey(folder, groups);
            ArrayList<AddonFolderInfo> group = groups.get(key);
            if (group == null) {
                group = new ArrayList<>();
                groups.put(key, group);
            }
            group.add(folder);
        }

        List<AddonRecord> previous = existing != null ? existing : Collections.<AddonRecord>emptyList();
        for (ArrayList<AddonFolderInfo> group : groups.values()) {
            result.records.add(buildRecord(group, flavor, previous));
        }

        // Unrecognized folders become local records so the UI can ignore/delete them.
        for (String folderName : result.unrecognizedFolders) {
            AddonRecord record = new AddonRecord();
            record.flavorDir = flavor.directoryName;
            record.name = folderName;
            record.folderNames.add(folderName);
            record.providerId = ProviderType.LOCAL.id;
            record.unrecognized = true;
            record.enabled = true;
            record.setStatus(InstallStatus.UNKNOWN);
            AddonRecord match = findByFolder(previous, folderName);
            if (match != null) {
                record.id = match.id;
                record.ignored = match.ignored;
            }
            result.records.add(record);
        }
        return result;
    }

    private static String groupKey(AddonFolderInfo folder, LinkedHashMap<String, ArrayList<AddonFolderInfo>> groups) {
        String binding = folder.bindingKey();
        if (binding != null) return binding;
        for (ArrayList<AddonFolderInfo> group : groups.values()) {
            AddonFolderInfo first = group.get(0);
            if (heuristicMatch(first, folder)) return "heuristic:" + first.logicalName.toLowerCase(Locale.US);
        }
        return "folder:" + folder.logicalName.toLowerCase(Locale.US);
    }

    private static boolean heuristicMatch(AddonFolderInfo first, AddonFolderInfo second) {
        if (first.toc == null || second.toc == null) return false;
        String title = first.toc.title;
        if (title == null || title.length() < 3) return false;
        if (!title.equalsIgnoreCase(second.toc.title)) return false;
        String a = first.logicalName.toLowerCase(Locale.US);
        String b = second.logicalName.toLowerCase(Locale.US);
        return a.startsWith(b) || b.startsWith(a);
    }

    private static AddonRecord buildRecord(ArrayList<AddonFolderInfo> group, WowFlavor flavor,
                                           List<AddonRecord> previous) {
        AddonFolderInfo primary = group.get(0);
        TocData toc = primary.toc;
        AddonRecord record = new AddonRecord();
        record.flavorDir = flavor.directoryName;
        record.toc = toc;
        record.name = toc.displayName();
        if (record.name == null || record.name.isEmpty()) record.name = primary.logicalName;
        boolean allDisabled = true;
        for (AddonFolderInfo folder : group) {
            record.folderNames.add(folder.folderName);
            if (!folder.disabled) allDisabled = false;
        }
        record.enabled = !allDisabled;
        record.heuristic = group.size() > 1 && group.get(0).bindingKey() == null;

        TocData.ProviderBinding binding = toc.identity();
        boolean consolePort = ConsolePort.isConsolePort(toc, primary.logicalName);
        if (consolePort) binding = ConsolePort.binding();
        if (binding != null) {
            record.providerId = binding.provider.id;
            record.externalId = binding.externalId;
        }

        AddonRecord match = findMatch(previous, record, group);
        if (match != null) {
            record.id = match.id;
            record.enabled = !allDisabled;
            record.ignored = match.ignored;
            // An external manager (CurseForge, WowUp) that rewrites the folder
            // after we installed it takes over the addon: adopt the catalog its
            // TOC declares rather than the source we recorded. Without a TOC
            // catalog, or without a known install time, the stored source stands.
            long modifiedAt = latestModified(group);
            boolean rewritten = binding != null && match.installedAt > 0
                    && modifiedAt > match.installedAt + EXTERNAL_WRITE_SLACK_MS;
            if (match.hasProviderBinding() && !rewritten) {
                record.providerId = match.providerId;
                record.externalId = match.externalId;
                record.providerVersionId = match.providerVersionId;
                record.channel = match.channel;
                record.latestVersion = match.latestVersion;
                record.latestReleaseId = match.latestReleaseId;
                record.latestReleaseDate = match.latestReleaseDate;
                record.latestUrl = match.latestUrl;
                record.latestChangelog = match.latestChangelog;
                record.status = match.status;
                record.lastCheckedAt = match.lastCheckedAt;
                record.lastError = match.lastError;
                record.installedFrom = match.installedFrom;
                record.archiveSha256 = match.archiveSha256;
            }
            record.installedAt = rewritten ? modifiedAt : match.installedAt;
        }
        if (consolePort && !ProviderType.GITHUB.id.equals(record.providerId)) {
            // Older index entries were bound to the TOC's CurseForge project (needs an API key).
            record.providerId = binding.provider.id;
            record.externalId = binding.externalId;
            record.latestVersion = "";
            record.latestReleaseId = "";
        }

        if (allDisabled) {
            record.setStatus(InstallStatus.DISABLED);
        }
        else if (!record.hasProviderBinding()) {
            record.setStatus(InstallStatus.LOCAL_ONLY);
        }
        else if (record.latestVersion == null || record.latestVersion.isEmpty()) {
            record.setStatus(InstallStatus.UNKNOWN);
        }
        else if (VersionComparator.isNewer(record.latestVersion, record.installedVersion())) {
            record.setStatus(InstallStatus.UPDATE);
        }
        else if (VersionComparator.isSame(record.installedVersion(), record.latestVersion)) {
            record.setStatus(InstallStatus.UP_TO_DATE);
        }
        else if (match != null && keepsCheckedStatus(match, record)) {
            // The comparator cannot order these versions (a release tag against a TOC
            // version, for example), so the last update check's verdict stands.
            record.status = match.status;
        }
        else {
            record.setStatus(InstallStatus.UNKNOWN);
        }
        return record;
    }

    private static AddonRecord findMatch(List<AddonRecord> previous, AddonRecord scanned,
                                         ArrayList<AddonFolderInfo> group) {
        if (scanned.hasProviderBinding()) {
            for (AddonRecord record : previous) {
                if (record.providerId.equals(scanned.providerId)
                        && record.externalId.equals(scanned.externalId)) {
                    return record;
                }
            }
        }
        for (AddonFolderInfo folder : group) {
            AddonRecord byFolder = findByFolder(previous, folder.logicalName);
            if (byFolder != null) return byFolder;
            AddonRecord byPhysical = findByFolder(previous, folder.folderName);
            if (byPhysical != null) return byPhysical;
        }
        return null;
    }

    /** The newest modification time across the group's folders, or 0 when unknown. */
    private static long latestModified(ArrayList<AddonFolderInfo> group) {
        long latest = 0;
        for (AddonFolderInfo folder : group) {
            if (folder.directory != null) latest = Math.max(latest, folder.directory.lastModified());
        }
        return latest;
    }

    private static AddonRecord findByFolder(List<AddonRecord> records, String folderName) {
        if (folderName == null) return null;
        for (AddonRecord record : records) {
            for (String folder : record.folderNames) {
                if (folder.equalsIgnoreCase(folderName)) return record;
            }
        }
        return null;
    }

    public static boolean isManagedOrHidden(String folderName) {
        if (folderName == null || folderName.isEmpty()) return true;
        if (folderName.startsWith(".")) return true;
        String lower = folderName.toLowerCase(Locale.US);
        return lower.endsWith("-bak") || lower.endsWith(".wehavephones-tmp");
    }

    /** The update checker's verdict holds while the installed version is the one it judged. */
    private static boolean keepsCheckedStatus(AddonRecord previous, AddonRecord current) {
        InstallStatus checked = previous.installStatus();
        return (checked == InstallStatus.UP_TO_DATE || checked == InstallStatus.UPDATE)
                && !current.installedVersion().isEmpty()
                && current.installedVersion().equals(previous.installedVersion());
    }
}
