package com.wehavephones.mobile.addons;

import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.mobile.addons.model.ProviderType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Lightweight view model used by the Phase 2 repository bridge. */
public final class InstalledAddon {
    public final String name;
    public final String version;
    public final String interfaceVersion;
    public final String providerId;
    public final String externalId;
    public final boolean enabled;
    public final String status;
    private final ArrayList<String> folders = new ArrayList<>();

    public InstalledAddon(String name, String version, String interfaceVersion, String providerId) {
        this(name, version, interfaceVersion, providerId, "", true, InstallStatus.UNKNOWN.id);
    }

    public InstalledAddon(String name, String version, String interfaceVersion, String providerId,
                          String externalId, boolean enabled, String status) {
        this.name = name != null ? name : "";
        this.version = version != null ? version : "";
        this.interfaceVersion = interfaceVersion != null ? interfaceVersion : "";
        this.providerId = providerId != null ? providerId : "";
        this.externalId = externalId != null ? externalId : "";
        this.enabled = enabled;
        this.status = status != null ? status : InstallStatus.UNKNOWN.id;
    }

    public static InstalledAddon fromRecord(AddonRecord record) {
        InstalledAddon addon = new InstalledAddon(record.displayName(), record.installedVersion(),
                record.installedInterface(), record.providerId, record.externalId,
                record.enabled, record.status);
        for (String folder : record.folderNames) addon.addFolder(folder);
        return addon;
    }

    public void addFolder(String folder) {
        if (folder != null && !folder.isEmpty() && !folders.contains(folder)) folders.add(folder);
    }

    public List<String> getFolders() {
        return Collections.unmodifiableList(folders);
    }

    public ProviderType provider() {
        return ProviderType.fromId(providerId);
    }

    public String describe() {
        StringBuilder builder = new StringBuilder(name);
        if (!version.isEmpty()) builder.append(' ').append(version);
        if (!interfaceVersion.isEmpty()) builder.append(" (interface ").append(interfaceVersion).append(')');
        return builder.toString();
    }
}
