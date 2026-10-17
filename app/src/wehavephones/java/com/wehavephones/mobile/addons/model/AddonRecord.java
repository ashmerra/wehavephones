package com.wehavephones.mobile.addons.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Logical installed addon: one record per project + flavor. Persisted in the
 * addon index as JSON. Pure Java so the host JVM self-test can round-trip it.
 */
public final class AddonRecord {
    public String id = UUID.randomUUID().toString();
    public String flavorDir = "";
    public String providerId = ProviderType.LOCAL.id;
    public String externalId = "";
    public String providerVersionId = "";
    public String channel = Channel.STABLE.id;
    public String name = "";
    public final ArrayList<String> folderNames = new ArrayList<>();
    public TocData toc = new TocData();
    public boolean enabled = true;
    public boolean ignored = false;
    public boolean heuristic = false;
    public boolean unrecognized = false;
    public long installedAt;
    public String installedFrom = "";
    public String archiveSha256 = "";
    public String latestVersion = "";
    public String latestReleaseId = "";
    public long latestReleaseDate;
    public String latestUrl = "";
    public String latestChangelog = "";
    public String status = InstallStatus.UNKNOWN.id;
    public long lastCheckedAt;
    public String lastError = "";

    public ProviderType provider() {
        return ProviderType.fromId(providerId);
    }

    public InstallStatus installStatus() {
        return InstallStatus.fromId(status);
    }

    public void setStatus(InstallStatus value) {
        this.status = value != null ? value.id : InstallStatus.UNKNOWN.id;
    }

    public String displayName() {
        String plain = TocData.plainText(name);
        if (!plain.isEmpty()) return plain;
        if (toc != null && !toc.displayName().isEmpty()) return toc.displayName();
        return folderNames.isEmpty() ? "(unnamed)" : folderNames.get(0);
    }

    public String installedVersion() {
        return toc != null ? toc.versionOrUnknown() : "";
    }

    public String installedInterface() {
        return toc != null ? toc.primaryInterface() : "";
    }

    public List<String> folders() {
        return Collections.unmodifiableList(folderNames);
    }

    public boolean hasProviderBinding() {
        return provider().isRemote() && externalId != null && !externalId.isEmpty();
    }

    /** True when the binding's provider is offered in the app, so updates can run. */
    public boolean hasOfferedBinding() {
        return hasProviderBinding() && provider().offered;
    }

    /**
     * The provider to show. A detected catalog names itself even when it is not
     * offered yet, so an addon installed by another manager (CurseForge, WowUp)
     * keeps its origin. Only a record with no binding reads as Local ZIP.
     */
    public ProviderType shownProvider() {
        return hasProviderBinding() ? provider() : ProviderType.LOCAL;
    }

    public AddonRecord copy() {
        AddonRecord copy = new AddonRecord();
        copy.id = id;
        copy.flavorDir = flavorDir;
        copy.providerId = providerId;
        copy.externalId = externalId;
        copy.providerVersionId = providerVersionId;
        copy.channel = channel;
        copy.name = name;
        copy.folderNames.clear();
        copy.folderNames.addAll(folderNames);
        copy.toc = toc;
        copy.enabled = enabled;
        copy.ignored = ignored;
        copy.heuristic = heuristic;
        copy.unrecognized = unrecognized;
        copy.installedAt = installedAt;
        copy.installedFrom = installedFrom;
        copy.archiveSha256 = archiveSha256;
        copy.latestVersion = latestVersion;
        copy.latestReleaseId = latestReleaseId;
        copy.latestReleaseDate = latestReleaseDate;
        copy.latestUrl = latestUrl;
        copy.latestChangelog = latestChangelog;
        copy.status = status;
        copy.lastCheckedAt = lastCheckedAt;
        copy.lastError = lastError;
        return copy;
    }
}
