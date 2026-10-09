package com.wehavephones.mobile.addons.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class TocData {
    public String baseName = "";
    public String tocFileName = "";
    public String version = "";
    public final ArrayList<String> interfaces = new ArrayList<>();
    public String title = "";
    public String author = "";
    public String notes = "";
    public String website = "";
    public String curseId = "";
    public String wagoId = "";
    public String wowiId = "";
    public String tukuiId = "";
    public final ArrayList<String> tukuiFolders = new ArrayList<>();
    public final ArrayList<String> dependencies = new ArrayList<>();
    public final ArrayList<String> optionalDeps = new ArrayList<>();
    public boolean loadOnDemand;
    public String defaultState = "";
    public final ArrayList<String> allowLoadGameType = new ArrayList<>();

    public String displayName() {
        if (title != null && !title.isEmpty()) return title;
        return baseName;
    }

    public String primaryInterface() {
        return interfaces.isEmpty() ? "" : interfaces.get(0);
    }

    public boolean hasPlaceholderVersion() {
        return version != null && version.indexOf('@') >= 0;
    }

    public String versionOrUnknown() {
        if (version == null || version.isEmpty() || hasPlaceholderVersion()) return "";
        return version;
    }

    /** The best provider identity found in the TOC, or null. */
    public ProviderBinding identity() {
        if (isFilled(curseId)) return new ProviderBinding(ProviderType.CURSEFORGE, curseId.trim());
        if (isFilled(wagoId)) return new ProviderBinding(ProviderType.WAGO, wagoId.trim());
        if (isFilled(tukuiId)) return new ProviderBinding(ProviderType.TUKUI, tukuiId.trim());
        if (isFilled(wowiId)) return new ProviderBinding(ProviderType.LOCAL, wowiId.trim());
        return null;
    }

    public List<String> requiredDependencies() {
        return Collections.unmodifiableList(dependencies);
    }

    public List<String> optionalDependencies() {
        return Collections.unmodifiableList(optionalDeps);
    }

    private static boolean isFilled(String value) {
        return value != null && !value.trim().isEmpty() && !"0".equals(value.trim());
    }

    public static final class ProviderBinding {
        public final ProviderType provider;
        public final String externalId;

        public ProviderBinding(ProviderType provider, String externalId) {
            this.provider = provider;
            this.externalId = externalId != null ? externalId : "";
        }

        public String key() {
            return provider.id + ":" + externalId;
        }
    }
}
