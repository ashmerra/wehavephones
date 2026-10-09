package com.wehavephones.mobile;

/**
 * Controller-to-keyboard bridge for WoW's pre-game screens. The client
 * ignores gamepads before a character is in the world (login, realm list,
 * character select and create), and ConsolePort only loads in the world.
 * While the bridge is active, A sends Enter (Enter World / OK), B sends
 * Escape and the D-pad sends the arrow keys (pick a character).
 *
 * <p>In the world these keys would double up with ConsolePort's bindings
 * (Enter opens chat). The client's logs are buffered until logout, so the
 * bridge follows file writes, which hit the disk immediately. Each burst
 * of writes is classified once it settles (measured on Classic Era 1.15.9):
 * <ul>
 * <li>Only the global {@code WTF/SavedVariables}: entered the world.
 *     (Blizzard's own account files are written too, so they are not
 *     followed.)
 * <li>ConsolePort's account SavedVariables, which only the in-world UI
 *     writes when it unloads, plus a logout marker
 *     ({@code WTF/Account/*}{@code /tts-cache-account.txt}, the
 *     {@code Logs/EditMode.log} flush): logged out to character select.
 * <li>ConsolePort's SavedVariables without a marker: {@code /reload},
 *     still in the world.
 * </ul>
 * Every unclear case keeps the bridge off, and it only arms for clients
 * with ConsolePort installed.
 *
 * <p>Pure Java (host-testable); {@link GlueControllerBridge} feeds it.
 */
public final class GlueInputBridge {
    /** A burst of writes is classified once nothing changed for this long. */
    public static final long SETTLE_MS = 1500;

    /** Logical keys, also the button bits passed to {@link #onControllerState}. */
    public static final int ENTER = 1, ESCAPE = 1 << 1, UP = 1 << 2, DOWN = 1 << 3, LEFT = 1 << 4, RIGHT = 1 << 5;
    private static final int[] KEYS = {ENTER, ESCAPE, UP, DOWN, LEFT, RIGHT};

    public interface KeySink {
        void press(int key);
        void release(int key);
    }

    /** Newest modification times (0 when missing) of the files the bridge follows. */
    public static final class Writes {
        public final long globalSavedVariables;
        /** ConsolePort's account SavedVariables. */
        public final long accountSavedVariables;
        public final long logoutMarkers;

        public Writes(long globalSavedVariables, long accountSavedVariables, long logoutMarkers) {
            this.globalSavedVariables = globalSavedVariables;
            this.accountSavedVariables = accountSavedVariables;
            this.logoutMarkers = logoutMarkers;
        }
    }

    private boolean gameRunning;
    private boolean armed;
    private boolean inWorld;
    private Writes last;
    private boolean burstGlobal, burstAccount, burstLogout;
    private long burstChangedAt = -1;
    private int previousButtons;
    private int heldKeys;

    public static int buttons(boolean a, boolean b, boolean up, boolean down, boolean left, boolean right) {
        return (a ? ENTER : 0) | (b ? ESCAPE : 0) | (up ? UP : 0) | (down ? DOWN : 0)
                | (left ? LEFT : 0) | (right ? RIGHT : 0);
    }

    /** A new game process, which starts on the pre-game screens; {@code writes} is the baseline. */
    public synchronized void onGameStarted(boolean armed, Writes writes) {
        gameRunning = true;
        this.armed = armed;
        inWorld = false;
        last = writes;
        burstChangedAt = -1;
        burstGlobal = burstAccount = burstLogout = false;
    }

    public synchronized void onGameStopped() {
        gameRunning = false;
        inWorld = false;
    }

    /** One poll of the followed files. */
    public synchronized void onWrites(long now, Writes writes) {
        if (!gameRunning || last == null) return;
        boolean global = writes.globalSavedVariables != last.globalSavedVariables;
        boolean account = writes.accountSavedVariables != last.accountSavedVariables;
        boolean logout = writes.logoutMarkers != last.logoutMarkers;
        last = writes;
        if (global || account || logout) {
            burstGlobal |= global;
            burstAccount |= account;
            burstLogout |= logout;
            burstChangedAt = now;
        }
        if (burstChangedAt < 0 || now - burstChangedAt < SETTLE_MS) return;
        if (burstLogout && burstAccount) inWorld = false;
        else if (burstGlobal && !burstAccount) inWorld = true;
        burstChangedAt = -1;
        burstGlobal = burstAccount = burstLogout = false;
    }

    public synchronized boolean isActive() {
        return gameRunning && armed && !inWorld;
    }

    /**
     * Presses a key only on a fresh button press while active, and releases
     * it when the button is released or the bridge deactivates, so a button
     * held across a screen change never leaks a key into the world.
     */
    public synchronized void onControllerState(int buttons, KeySink sink) {
        boolean active = isActive();
        int release = heldKeys & (active ? ~buttons : ~0);
        int press = active ? buttons & ~previousButtons & ~heldKeys : 0;
        previousButtons = buttons;
        for (int key : KEYS) {
            if ((release & key) != 0) {
                heldKeys &= ~key;
                sink.release(key);
            }
        }
        for (int key : KEYS) {
            if ((press & key) != 0) {
                heldKeys |= key;
                sink.press(key);
            }
        }
    }
}
