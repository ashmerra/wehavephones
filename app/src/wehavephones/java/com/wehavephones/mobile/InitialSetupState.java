package com.wehavephones.mobile;

/** First-run routing, independent of account sign-in and game downloads. */
public final class InitialSetupState {
    public final boolean containerPrepared;
    public final boolean complete;

    public InitialSetupState(boolean completedBefore, boolean systemReady, boolean hasContainer,
            boolean preparationRecorded, boolean shortcutsReady, boolean battleNetInstalled) {
        // Existing installations predate the preparation marker. Their installed
        // launcher is enough to migrate without changing a working runtime.
        containerPrepared = systemReady && hasContainer
                && (battleNetInstalled || (preparationRecorded && shortcutsReady));
        // Completion belongs to the app, so switching containers or runtime
        // profiles later cannot bring the one-time screen back.
        complete = completedBefore || (systemReady && hasContainer && battleNetInstalled);
    }
}
