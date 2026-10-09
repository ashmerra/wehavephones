package com.wehavephones.mobile;

/**
 * Host test for WowExitDetector: no exit before the game has run, the grace
 * period before an exit, a self-restarting client keeping the session, and a
 * pause (runtime handoff, setting off) forgetting the game.
 */
public final class WowExitDetectorSelfTest {
    private static int failures;

    public static void main(String[] args) {
        final long grace = WowExitDetector.GRACE_MS;
        WowExitDetector.State WAITING = WowExitDetector.State.WAITING;
        WowExitDetector.State RUNNING = WowExitDetector.State.RUNNING;
        WowExitDetector.State CLOSING = WowExitDetector.State.CLOSING;
        WowExitDetector.State EXIT = WowExitDetector.State.EXIT;

        WowExitDetector detector = new WowExitDetector();
        check("Battle.net only: waiting", detector.onScan(0, false, false) == WAITING);
        check("Battle.net only, much later: still waiting", detector.onScan(10 * grace, false, false) == WAITING);

        long t = 100_000;
        check("game seen", detector.onScan(t, true, false) == RUNNING);
        check("game gone: closing", detector.onScan(t + 2_000, false, false) == CLOSING);
        check("inside the grace period: closing", detector.onScan(t + 2_000 + grace - 1, false, false) == CLOSING);
        check("grace period over: exit", detector.onScan(t + 2_000 + grace, false, false) == EXIT);

        detector = new WowExitDetector();
        detector.onScan(t, true, false);
        detector.onScan(t + 2_000, false, false);
        check("client restarts itself: running", detector.onScan(t + 4_000, true, false) == RUNNING);
        check("restart resets the grace period", detector.onScan(t + 2_000 + grace, false, false) == CLOSING);
        check("new grace period ends: exit", detector.onScan(t + 2_000 + 2 * grace, false, false) == EXIT);

        detector = new WowExitDetector();
        detector.onScan(t, true, false);
        check("handoff owns the session: waiting", detector.onScan(t + 2_000, false, true) == WAITING);
        check("after the pause the old game is forgotten", detector.onScan(t + 2_000 + 2 * grace, false, false) == WAITING);
        check("game seen again after the pause", detector.onScan(t + 3 * grace, true, false) == RUNNING);
        check("and its exit counts", detector.onScan(t + 5 * grace, false, false) == CLOSING
                && detector.onScan(t + 6 * grace, false, false) == EXIT);

        if (failures != 0) {
            System.out.println("WowExitDetectorSelfTest: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("WowExitDetectorSelfTest: PASS");
    }

    private static void check(String name, boolean ok) {
        if (ok) return;
        failures++;
        System.out.println("FAIL: " + name);
    }
}
