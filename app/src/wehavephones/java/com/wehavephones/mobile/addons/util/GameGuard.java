package com.wehavephones.mobile.addons.util;

import com.wehavephones.mobile.ProcessScanner;

/**
 * Blocks addon filesystem changes while WoW is running. Uses the Phase 2
 * /proc-based process scanner, so it needs no new dependency and works
 * on-device; the return value is trivially stubbed on the host.
 */
public final class GameGuard {
    private GameGuard() {}

    public static boolean isGameRunning() {
        try {
            return ProcessScanner.findWowProcess() != null;
        }
        catch (Throwable ignored) {
            return false;
        }
    }

    public static String runningExecutable() {
        ProcessScanner.Entry entry = ProcessScanner.findWowProcess();
        return entry != null ? entry.name : "";
    }
}
