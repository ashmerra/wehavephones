package com.wehavephones.mobile.addons.model;

import java.util.Locale;

/**
 * CurseForge, Tukui and Wago are implemented but untested live, so they are not
 * offered in the app yet: no search, key settings, update checks or labels.
 */
public enum ProviderType {
    CURSEFORGE("curseforge", "CurseForge", true, false),
    GITHUB("github", "GitHub releases", false, true),
    TUKUI("tukui", "Tukui", false, false),
    WAGO("wago", "Wago", true, false),
    LOCAL("local", "Local ZIP", false, true);

    public final String id;
    public final String displayName;
    public final boolean requiresKey;
    public final boolean offered;

    ProviderType(String id, String displayName, boolean requiresKey, boolean offered) {
        this.id = id;
        this.displayName = displayName;
        this.requiresKey = requiresKey;
        this.offered = offered;
    }

    public static ProviderType fromId(String id) {
        if (id == null) return LOCAL;
        String key = id.trim().toLowerCase(Locale.US);
        for (ProviderType type : values()) {
            if (type.id.equals(key)) return type;
        }
        return LOCAL;
    }

    /** Providers that can actually resolve remote addons (LOCAL cannot). */
    public boolean isRemote() {
        return this != LOCAL;
    }
}
