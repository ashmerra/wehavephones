package com.wehavephones.mobile.addons.install;

import com.wehavephones.mobile.addons.model.TocData;

import java.io.File;

public final class DiscoveredAddon {
    public final File directory;
    public final String folderName;
    public final TocData toc;
    public final String fallbackVersion;

    public DiscoveredAddon(File directory, String folderName, TocData toc, String fallbackVersion) {
        this.directory = directory;
        this.folderName = folderName;
        this.toc = toc;
        this.fallbackVersion = fallbackVersion != null ? fallbackVersion : "";
    }

    public String version() {
        if (toc != null && !toc.versionOrUnknown().isEmpty()) return toc.versionOrUnknown();
        return fallbackVersion;
    }

    public String displayName() {
        if (toc != null && toc.title != null && !toc.title.isEmpty()) return toc.title;
        return folderName;
    }
}
