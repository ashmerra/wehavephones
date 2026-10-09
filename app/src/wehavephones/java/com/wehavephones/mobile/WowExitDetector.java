package com.wehavephones.mobile;

/**
 * Decides when a Wine session should end because the game closed. Battle.net
 * and the Wine desktop outlive the game, so the session never ends on its
 * own. The game must first be seen running, then stay gone for
 * {@link #GRACE_MS}, so a client that restarts itself (graphics API change,
 * update relaunch) keeps the session. Pure logic, fed by {@link WowExitWatcher}.
 */
public final class WowExitDetector {
    public static final long GRACE_MS = 6_000L;

    public enum State {
        /** The game has not run in this session yet. */
        WAITING,
        RUNNING,
        /** The game is gone; the grace period is running. */
        CLOSING,
        /** The game stayed gone for the grace period: end the session. */
        EXIT
    }

    private boolean seen;
    private long goneSince = -1;

    /**
     * One process scan. {@code paused} means something else owns the session
     * (the runtime handoff, the setting is off); it forgets the game, so only
     * a game seen after the pause can end the session.
     */
    public State onScan(long now, boolean gameRunning, boolean paused) {
        if (paused) {
            seen = false;
            goneSince = -1;
            return State.WAITING;
        }
        if (gameRunning) {
            seen = true;
            goneSince = -1;
            return State.RUNNING;
        }
        if (!seen) return State.WAITING;
        if (goneSince < 0) goneSince = now;
        return now - goneSince >= GRACE_MS ? State.EXIT : State.CLOSING;
    }
}
