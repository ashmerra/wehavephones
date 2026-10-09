package com.wehavephones;

/** Flavor hook for session input; the winlator flavor adds nothing. */
public final class SessionInputExtras {
    private SessionInputExtras() {}

    public static void onSessionCreated(XServerDisplayActivity activity) {}

    public static void onSessionResumed(XServerDisplayActivity activity) {}

    public static void onSessionDestroyed(XServerDisplayActivity activity) {}

    /** Controller input that WinHandler consumed. */
    public static void onControllerInput(XServerDisplayActivity activity) {}
}
