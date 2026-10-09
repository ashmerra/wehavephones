package com.wehavephones.mobile.addons.model;

import java.util.Locale;

public enum Channel {
    STABLE("stable", 1),
    BETA("beta", 2),
    ALPHA("alpha", 3);

    public final String id;
    /** CurseForge releaseType value; lower is more stable. */
    public final int curseForgeReleaseType;

    Channel(String id, int curseForgeReleaseType) {
        this.id = id;
        this.curseForgeReleaseType = curseForgeReleaseType;
    }

    public static Channel fromId(String id) {
        if (id == null) return STABLE;
        String key = id.trim().toLowerCase(Locale.US);
        for (Channel channel : values()) {
            if (channel.id.equals(key)) return channel;
        }
        return STABLE;
    }

    public boolean allows(Channel candidate) {
        return candidate != null && candidate.curseForgeReleaseType <= curseForgeReleaseType;
    }
}
