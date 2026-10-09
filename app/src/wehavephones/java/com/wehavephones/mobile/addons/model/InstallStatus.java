package com.wehavephones.mobile.addons.model;

import java.util.Locale;

public enum InstallStatus {
    UP_TO_DATE("up_to_date", "Up to date"),
    UPDATE("update", "Update available"),
    UNKNOWN("unknown", "Unknown"),
    LOCAL_ONLY("local_only", "Local only"),
    DISABLED("disabled", "Disabled"),
    REMOVED("removed", "Removed upstream"),
    ERROR("error", "Error");

    public final String id;
    public final String label;

    InstallStatus(String id, String label) {
        this.id = id;
        this.label = label;
    }

    public static InstallStatus fromId(String id) {
        if (id == null) return UNKNOWN;
        String key = id.trim().toLowerCase(Locale.US);
        for (InstallStatus status : values()) {
            if (status.id.equals(key)) return status;
        }
        return UNKNOWN;
    }
}
