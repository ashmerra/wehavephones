package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.container.Container;
import com.wehavephones.xenvironment.ImageFs;

public final class WeHavePhonesInitialSetup {
    public static final String PREPARED_PROFILE = "wehavephonesSetupProfile";

    private WeHavePhonesInitialSetup() {}

    public static InitialSetupState load(Context context) {
        Container container = WeHavePhonesContainers.resolve(context);
        return new InitialSetupState(WeHavePhonesConfig.isInitialSetupComplete(context),
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
