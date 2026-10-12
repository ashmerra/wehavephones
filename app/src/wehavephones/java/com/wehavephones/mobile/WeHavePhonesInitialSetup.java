package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.container.Container;
import com.wehavephones.xenvironment.ImageFs;

public final class WeHavePhonesInitialSetup {
    public static final String PREPARED_PROFILE = "wehavephonesSetupProfile";

    private WeHavePhonesInitialSetup() {}

    public static InitialSetupState load(Context context) {
        return load(context, WeHavePhonesContainers.resolve(context), WeHavePhonesConfig.isInitialSetupComplete(context));
    }

    /** Setup state of one container; completedBefore is false when setting up an extra container. */
    public static InitialSetupState load(Context context, Container container, boolean completedBefore) {
        return new InitialSetupState(completedBefore,
                ImageFs.find(context).isValid(), container != null,
                container != null && !container.getExtra(PREPARED_PROFILE).isEmpty(),
                container != null && WeHavePhonesShortcuts.findBnetShortcut(container) != null
                        && WeHavePhonesShortcuts.findWowShortcut(container) != null,
                BattleNetManager.isInstalled(container));
    }

    public static boolean isComplete(Context context) {
        if (WeHavePhonesConfig.isInitialSetupComplete(context)) return true;
        if (!load(context).complete) return false;
        WeHavePhonesConfig.setInitialSetupComplete(context);
        return true;
    }
}
