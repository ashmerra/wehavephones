package com.wehavephones.mobile.addons.model;

import java.io.File;

public final class AddonFolderInfo {
    /** On-disk folder name (may end in .disabled). */
    public final String folderName;
    /** Logical folder name with any .disabled suffix removed. */
    public final String logicalName;
    public final boolean disabled;
    public final TocData toc;
    public final File directory;

    public AddonFolderInfo(String folderName, String logicalName, boolean disabled, TocData toc, File directory) {
        this.folderName = folderName;
        this.logicalName = logicalName;
        this.disabled = disabled;
        this.toc = toc;
        this.directory = directory;
    }

    public String bindingKey() {
        if (toc == null) return null;
        TocData.ProviderBinding binding = toc.identity();
        return binding != null ? binding.key() : null;
    }
}
