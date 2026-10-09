package com.wehavephones.mobile.addons.ui;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.wehavephones.mobile.addons.AddonManager;
import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.InstallStatus;
import com.wehavephones.mobile.addons.required.ConsolePort;
import com.wehavephones.R;

final class AddonUi {
    private AddonUi() {}

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
                return context.getColor(R.color.wehavephones_text_dim);
            default:
                return context.getColor(R.color.wehavephones_error);
        }
    }
}
