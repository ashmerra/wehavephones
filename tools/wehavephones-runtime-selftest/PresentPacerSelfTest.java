package com.wehavephones.xserver.extensions;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test for PresentPacer: frames go out right away while the session is
 * shown, 200 ms apart while it is hidden, never out of presentation order
 * (also when the timers fire out of order or the session comes back with
 * frames still queued), and inline again once the queue has drained.
 */
public final class PresentPacerSelfTest {
    private static int failures;

    public static void main(String[] args) {
        List<Integer> sent = new ArrayList<>();
        PresentPacer pacer = new PresentPacer();

        check("shown: sent right away", pacer.submit(frame(sent, 1), 1_000) == -1);

        pacer.setIntervalMs(200);
        check("hidden, first frame: due now", pacer.submit(frame(sent, 2), 1_000) == 0);
        check("hidden, next frame: 200 ms later", pacer.submit(frame(sent, 3), 1_010) == 190);
        check("hidden, third frame: 400 ms after the first", pacer.submit(frame(sent, 4), 1_020) == 380);

        // Timers may fire out of order; each send takes the oldest frame.
        pacer.sendNext();
        pacer.sendNext();
        check("oldest frames first", sent.equals(List.of(2, 3)));

        pacer.setIntervalMs(0);
        check("shown again with a frame queued: queued behind it", pacer.submit(frame(sent, 5), 1_030) == 370);
        pacer.sendNext();
        pacer.sendNext();
        check("queued frames drain in order", sent.equals(List.of(2, 3, 4, 5)));
        pacer.sendNext();
        check("extra send with an empty queue is a no-op", sent.size() == 4);
        check("drained: inline again", pacer.submit(frame(sent, 6), 1_500) == -1);

        pacer.setIntervalMs(200);
        check("hidden later: spacing restarts from now", pacer.submit(frame(sent, 7), 5_000) == 0);
        check("and keeps 200 ms", pacer.submit(frame(sent, 8), 5_000) == 200);

        if (failures != 0) {
            System.out.println("PresentPacerSelfTest: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("PresentPacerSelfTest: PASS");
    }

    private static Runnable frame(List<Integer> sent, int id) {
        return () -> sent.add(id);
    }

    private static void check(String name, boolean ok) {
        if (ok) System.out.println("  ok   " + name);
        else {
            System.out.println("  FAIL " + name);
            failures++;
        }
    }
}
