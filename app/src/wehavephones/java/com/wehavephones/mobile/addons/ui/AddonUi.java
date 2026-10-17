package com.wehavephones.mobile.addons.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.required.ConsolePort;
import com.wehavephones.R;

final class AddonUi {
    private AddonUi() {}

    /** The one status a list row shows, from the record rather than the raw status alone. */
    static InstallStatus rowStatus(AddonRecord record) {
        InstallStatus status = record.installStatus();
        if (!record.enabled) return InstallStatus.DISABLED;
        if (status == InstallStatus.UNKNOWN && !record.hasOfferedBinding()) return InstallStatus.LOCAL_ONLY;
        return status;
    }

    /** True when the row and the details offer the Update button. */
    static boolean canUpdate(AddonRecord record) {
        return record.hasOfferedBinding() && !record.unrecognized
                && record.installStatus() != InstallStatus.UP_TO_DATE;
    }

    /** The source a row names, such as GitHub releases or Local ZIP. */
    static String sourceLabel(AddonRecord record) {
        return record.shownProvider().displayName;
    }

    static String websiteUrl(AddonRecord record) {
        if (record.toc != null && !record.toc.website.isEmpty()) return record.toc.website;
        if (record.hasOfferedBinding() && record.provider() == ProviderType.GITHUB) {
            return "https://github.com/" + record.externalId;
        }
        return "";
    }

    static String statusLabel(Context context, InstallStatus status) {
        switch (status) {
            case UP_TO_DATE: return context.getString(R.string.wehavephones_addons_status_up_to_date);
            case UPDATE: return context.getString(R.string.wehavephones_addons_status_update);
            case LOCAL_ONLY: return context.getString(R.string.wehavephones_addons_status_local_only);
            case DISABLED: return context.getString(R.string.wehavephones_addons_status_disabled);
            case REMOVED: return context.getString(R.string.wehavephones_addons_status_removed);
            case ERROR: return context.getString(R.string.wehavephones_addons_status_error);
            case UNKNOWN:
            default: return context.getString(R.string.wehavephones_addons_status_unknown);
        }
    }

    /** Why the app installed this addon itself, or null for user-installed addons. */
    static String autoInstallNote(Context context, AddonManager manager, AddonRecord record) {
        if (!manager.isRequired(record)) return null;
        return context.getString(ConsolePort.isConsolePort(record)
                ? R.string.wehavephones_addons_auto_consoleport : R.string.wehavephones_addons_auto_setup);
    }

    static void showNote(TextView view, String note) {
        view.setText(note != null ? note : "");
        view.setVisibility(note != null ? View.VISIBLE : View.GONE);
    }

    static int statusColor(Context context, InstallStatus status) {
        switch (status) {
            case UP_TO_DATE:
                return context.getColor(R.color.wehavephones_ok);
            case UPDATE:
                return context.getColor(R.color.wehavephones_accent);
            case DISABLED:
            case LOCAL_ONLY:
            case UNKNOWN:
            case REMOVED:
                return context.getColor(R.color.wehavephones_text_dim);
            default:
                return context.getColor(R.color.wehavephones_error);
        }
    }
}
