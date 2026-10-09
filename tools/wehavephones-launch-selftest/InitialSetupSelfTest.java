package com.wehavephones.mobile;

public final class InitialSetupSelfTest {
    public static void main(String[] args) {
        check("fresh app needs container", false, false, false, false, false, false, false, false);
        check("container creation alone is incomplete", false, true, true, false, false, false, false, false);
        check("failed preparation is retryable", false, true, true, true, false, false, false, false);
        check("prepared container enables Battle.net after restart", false, true, true, true, true, false, true, false);
        check("installed Battle.net completes setup without login or WoW", false, true, true, true, true, true, true, true);
        check("existing installation migrates without a new marker", false, true, true, false, false, true, true, true);
        check("orphan launcher does not complete missing system files", false, false, true, true, true, true, false, false);
        check("missing container does not complete setup", false, true, false, true, true, true, false, false);
        check("container switch never reopens one-time setup", true, true, true, false, false, false, false, true);
        check("completed setup stays hidden after container removal", true, false, false, false, false, false, false, true);
        System.out.println("[initial-setup-selftest] PASS");
    }

    private static void check(String name, boolean previous, boolean system, boolean container,
            boolean recorded, boolean shortcuts, boolean battleNet, boolean ready, boolean complete) {
        InitialSetupState state = new InitialSetupState(previous, system, container, recorded, shortcuts, battleNet);
        if (state.containerPrepared != ready || state.complete != complete) throw new AssertionError(name);
        System.out.println("  ok   " + name);
    }
}
