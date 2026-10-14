package com.wehavephones.mobile.addons.update;

import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.Channel;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.RemoteFile;
import com.wehavephones.mobile.WowFlavor;
import com.wehavephones.mobile.addons.provider.AddonProvider;
import com.wehavephones.mobile.addons.provider.ProviderException;
import com.wehavephones.mobile.addons.provider.ProviderRegistry;
import com.wehavephones.mobile.addons.scan.VersionComparator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Batch update check. A failure for one provider never fails the whole check:
 * records keep their previous status and receive a lastError.
 */
public final class UpdateChecker {
    private UpdateChecker() {}

    public interface Listener {
        void onProviderStart(ProviderType provider);

        void onRecordChecked(AddonRecord record);
    }

    public static int checkAll(List<AddonRecord> records, ProviderRegistry registry,
                               WowFlavor flavor, int clientInterface, Listener listener) {
        if (records == null || registry == null || flavor == null) return 0;
        LinkedHashMap<ProviderType, LinkedHashMap<Channel, ArrayList<AddonRecord>>> groups = new LinkedHashMap<>();
        for (AddonRecord record : records) {
            if (!record.hasProviderBinding() || record.ignored || record.unrecognized) continue;
            ProviderType provider = record.provider();
            if (!provider.isRemote()) continue;
            Channel channel = Channel.fromId(record.channel);
            LinkedHashMap<Channel, ArrayList<AddonRecord>> byChannel = groups.get(provider);
            if (byChannel == null) {
                byChannel = new LinkedHashMap<>();
                groups.put(provider, byChannel);
            }
            ArrayList<AddonRecord> bucket = byChannel.get(channel);
            if (bucket == null) {
                bucket = new ArrayList<>();
                byChannel.put(channel, bucket);
            }
            bucket.add(record);
        }

        long now = System.currentTimeMillis();
        int checked = 0;
        for (Map.Entry<ProviderType, LinkedHashMap<Channel, ArrayList<AddonRecord>>> group : groups.entrySet()) {
            AddonProvider provider = registry.get(group.getKey());
            if (provider == null) continue;
            if (listener != null) listener.onProviderStart(group.getKey());
            for (Map.Entry<Channel, ArrayList<AddonRecord>> channelGroup : group.getValue().entrySet()) {
                ArrayList<AddonRecord> bucket = channelGroup.getValue();
                ArrayList<String> ids = new ArrayList<>();
                for (AddonRecord record : bucket) {
                    if (!ids.contains(record.externalId)) ids.add(record.externalId);
                }
                Map<String, RemoteFile> files;
                try {
                    files = provider.getLatestFiles(ids, flavor, channelGroup.getKey());
                }
                catch (ProviderException e) {
                    for (AddonRecord record : bucket) {
                        record.lastCheckedAt = now;
                        record.lastError = describe(e);
                    }
                    if (listener != null) {
                        for (AddonRecord record : bucket) listener.onRecordChecked(record);
                    }
                    continue;
                }
                for (AddonRecord record : bucket) {
                    RemoteFile file = files != null ? files.get(record.externalId) : null;
                    record.lastCheckedAt = now;
                    record.lastError = "";
                    if (file == null) {
                        if (!record.hasProviderBinding()) record.setStatus(InstallStatus.LOCAL_ONLY);
                        else if (!InstallStatus.UP_TO_DATE.id.equals(record.status)) {
                            record.setStatus(InstallStatus.UNKNOWN);
                        }
                    }
                    else {
                        record.latestVersion = file.version;
                        record.latestReleaseId = file.releaseId;
                        record.latestReleaseDate = file.releaseDate;
                        record.latestUrl = file.downloadUrl != null ? file.downloadUrl : "";
                        record.latestChangelog = capText(file.changelog);
                        String installed = record.installedVersion();
                        boolean newer = VersionComparator.isNewer(file.version, installed);
                        boolean outdatedInterface = clientInterface > 0
                                && remoteInterface(file) > 0
                                && remoteInterface(file) < clientInterface;
                        if (VersionComparator.isUnknown(installed) && !outdatedInterface) {
                            record.setStatus(InstallStatus.UNKNOWN);
                        }
                        else if (newer || outdatedInterface) {
                            record.setStatus(InstallStatus.UPDATE);
                        }
                        else {
                            record.setStatus(InstallStatus.UP_TO_DATE);
                        }
                    }
                    checked++;
                    if (listener != null) listener.onRecordChecked(record);
                }
            }
        }
        return checked;
    }

    /** Highest interface number advertised by the remote file, or 0. */
    private static int remoteInterface(RemoteFile file) {
        if (file == null || file.gameVersions == null) return 0;
        int highest = 0;
        for (String version : file.gameVersions) {
            int value = WowFlavor.interfaceFromVersion(version);
            if (value > highest) highest = value;
        }
        return highest;
    }

    private static String capText(String value) {
        if (value == null) return "";
        return value.length() > 20000 ? value.substring(0, 20000) : value;
    }

    private static String describe(ProviderException e) {
        switch (e.kind) {
            case AUTH: return "Authentication failed. Check the provider key";
            case RATE_LIMIT: return "Rate limited. Try again later";
            case NETWORK: return e.getMessage() != null ? e.getMessage() : "Network error";
            default: return e.getMessage() != null ? e.getMessage() : "Update check failed";
        }
    }
}
