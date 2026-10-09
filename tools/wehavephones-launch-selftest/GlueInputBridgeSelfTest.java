package com.wehavephones.mobile;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test for GlueInputBridge: world entry, /reload and logout classified
 * from bursts of file writes, the no-ConsolePort safety, and press/release
 * edges across screen changes (no key may leak into the world).
 */
public final class GlueInputBridgeSelfTest {
    private static int failures;

    private static final class Recorder implements GlueInputBridge.KeySink {
        final List<String> events = new ArrayList<>();

        @Override
        public void press(int key) {
            events.add("+" + key);
        }

        @Override
        public void release(int key) {
            events.add("-" + key);
        }

        String take() {
            String text = String.join(" ", events);
            events.clear();
            return text;
        }
    }

    private static GlueInputBridge.Writes w(long global, long consolePort, long logout) {
        return new GlueInputBridge.Writes(global, consolePort, logout);
    }

    public static void main(String[] args) {
        final int a = GlueInputBridge.ENTER, b = GlueInputBridge.ESCAPE, down = GlueInputBridge.DOWN;
        final long settle = GlueInputBridge.SETTLE_MS;

        GlueInputBridge bridge = new GlueInputBridge();
        Recorder keys = new Recorder();
        check("inactive before a game runs", !bridge.isActive());

        bridge.onGameStarted(false, w(100, 50, 40));
        bridge.onControllerState(a, keys);
        bridge.onControllerState(0, keys);
        check("no ConsolePort: no keys", keys.take().isEmpty() && !bridge.isActive());

        long t = 10_000;
        bridge.onGameStarted(true, w(100, 50, 40));
        check("new game starts active on the pre-game screens", bridge.isActive());
        bridge.onWrites(t, w(100, 50, 40));
        check("no writes: still active", bridge.isActive());
        bridge.onControllerState(a, keys);
        check("A press sends Enter", keys.take().equals("+" + a));
        bridge.onControllerState(a, keys);
        check("held A does not repeat", keys.take().isEmpty());
        bridge.onControllerState(0, keys);
        check("A release releases Enter", keys.take().equals("-" + a));

        // Enter World: only the global SavedVariables.
        bridge.onWrites(t += 500, w(200, 50, 40));
        check("world entry waits for the burst to settle", bridge.isActive());
        bridge.onControllerState(a | down, keys);
        check("chord presses both", keys.take().equals("+" + a + " +" + down));
        bridge.onWrites(t += settle, w(200, 50, 40));
        check("global write alone = in the world", !bridge.isActive());
        bridge.onControllerState(a | down | b, keys);
        check("entering the world releases held keys and presses nothing", keys.take().equals("-" + a + " -" + down));
        bridge.onControllerState(b, keys);
        bridge.onControllerState(0, keys);
        check("no keys in the world", keys.take().isEmpty());

        // /reload: global + ConsolePort, no logout marker; the load-time writes come later.
        bridge.onWrites(t += 500, w(300, 300, 40));
        bridge.onWrites(t += settle, w(300, 300, 40));
        check("reload stays in the world", !bridge.isActive());
        bridge.onWrites(t += 3000, w(310, 300, 40));
        bridge.onWrites(t += settle, w(310, 300, 40));
        check("load-time writes after a reload stay in the world", !bridge.isActive());

        // Logout: ConsolePort + marker, split across polls.
        bridge.onControllerState(a, keys);
        bridge.onWrites(t += 500, w(400, 400, 40));
        bridge.onWrites(t += 500, w(400, 400, 400));
        check("logout waits for the burst to settle", !bridge.isActive());
        bridge.onWrites(t += settle, w(400, 400, 400));
        check("ConsolePort write + logout marker = character select", bridge.isActive());
        bridge.onControllerState(a, keys);
        check("button held across logout is not pressed", keys.take().isEmpty());
        bridge.onControllerState(0, keys);
        bridge.onControllerState(a, keys);
        check("fresh press after logout works", keys.take().equals("+" + a));
        bridge.onControllerState(0, keys);
        keys.take();

        // First-ever world entry also creates the EditMode log.
        bridge.onWrites(t += 500, w(500, 400, 500));
        bridge.onWrites(t += settle, w(500, 400, 500));
        check("world entry with a new log file = in the world", !bridge.isActive());
        // A logout marker alone (no ConsolePort write) changes nothing.
        bridge.onWrites(t += 500, w(500, 400, 600));
        bridge.onWrites(t += settle, w(500, 400, 600));
        check("marker without a ConsolePort write stays in the world", !bridge.isActive());

        bridge.onControllerState(a, keys);
        bridge.onGameStopped();
        bridge.onControllerState(0, keys);
        check("no keys after exit", keys.take().isEmpty() && !bridge.isActive());

        if (failures > 0) {
            System.out.println("GlueInputBridgeSelfTest: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("GlueInputBridgeSelfTest: PASS");
    }

    private static void check(String name, boolean ok) {
        System.out.println((ok ? "  ok   " : "  FAIL ") + name);
        if (!ok) failures++;
    }
}
