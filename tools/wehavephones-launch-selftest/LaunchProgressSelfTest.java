package com.wehavephones.mobile;

import com.wehavephones.mobile.LaunchProgress.Mode;
import com.wehavephones.mobile.LaunchProgress.Step;

/**
 * Host test for LaunchProgress, the launch overlay's state: stage mapping
 * for both routes, the bar only moving forward, and the reveal rules (game
 * ready, sign-in needs the player, failures with and without a window, Show
 * game), which must keep WoW's own login errors and queue visible.
 */
public final class LaunchProgressSelfTest {
    private static int failures;

    public static void main(String[] args) {
        // One-button route, happy path.
        LaunchProgress p = new LaunchProgress();
        check("starts covering on Runtime", p.isCovering() && p.step() == Step.RUNTIME);
        p.onOneButtonStage("STARTING", "Starting Battle.net", false);
        check("Battle.net starting before any window: still Runtime", p.step() == Step.RUNTIME);
        p.onWindowShown();
        check("first window: Battle.net step", p.step() == Step.BATTLE_NET);
        p.onOneButtonStage("SIGNING_IN", "Signing in", false);
        check("signing in", p.step() == Step.SIGN_IN && p.isCovering());
        p.onOneButtonStage("UPDATING", "Updating 38%", false);
        p.onStepProgress(0.38);
        check("updating with progress", p.step() == Step.UPDATES && p.stepProgress() == 0.38);
        check("bar fill includes the patch progress", near(p.fraction(), (3 + 0.38) / 5));
        p.onOneButtonStage("SIGNING_IN", "late sign-in line", false);
        check("bar never moves back", p.step() == Step.UPDATES);
        p.onOneButtonStage("LAUNCHING", "Launching", false);
        check("launching: Game step, progress reset", p.step() == Step.GAME && p.stepProgress() < 0);
        p.onOneButtonStage("RUNNING", "Running", false);
        check("running: still covering until WoW draws", p.isCovering() && p.isAwaitingFirstFrame());
        p.onFirstFrame();
        check("first frame: done, bar full", p.mode() == Mode.DONE && p.fraction() == 1);
        p.onOneButtonStage("FAILED", "launcher closed late", true);
        check("done is final", p.mode() == Mode.DONE);

        // First frame before the game runs is ignored.
        p = new LaunchProgress();
        p.onFirstFrame();
        check("first frame ignored before RUNNING", p.isCovering());

        // Battle.net needs the player (authenticator, captcha, no saved account).
        p = new LaunchProgress();
        p.onWindowShown();
        p.onOneButtonStage("NEEDS_USER", "Check the Battle.net window", false);
        check("needs user: revealed with the reason", p.mode() == Mode.REVEALED && p.note().equals("Check the Battle.net window"));
        p.onOneButtonStage("UPDATING", "Updating", false);
        check("revealed stays revealed after sign-in", p.mode() == Mode.REVEALED && p.step() == Step.SIGN_IN);

        // Failure while a window exists: show the game, never an error card over it.
        p = new LaunchProgress();
        p.onWindowShown();
        p.onOneButtonStage("SIGNING_IN", "Signing in", false);
        p.onOneButtonStage("FAILED", "Battle.net did not finish in 420 s", true);
        check("failure with a window: revealed", p.mode() == Mode.REVEALED && p.note().startsWith("Battle.net did not"));

        // Failure before any window: error card.
        p = new LaunchProgress();
        p.onOneButtonStage("FAILED", "The session did not start in 90 s", true);
        check("failure without a window: error card", p.mode() == Mode.FAILED && !p.note().isEmpty());
        p.onShowGame();
        check("Show game still works from the error card", p.mode() == Mode.REVEALED);

        // Show game while covering.
        p = new LaunchProgress();
        p.onWindowShown();
        p.onShowGame();
        check("show game: revealed without a note", p.mode() == Mode.REVEALED && p.note().isEmpty());
        p.onOneButtonStage("RUNNING", "Running", false);
        p.onFirstFrame();
        check("show game is final", p.mode() == Mode.REVEALED);

        if (failures != 0) {
            System.out.println("LaunchProgressSelfTest: " + failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("LaunchProgressSelfTest: PASS");
    }

    private static boolean near(double a, double b) {
        return Math.abs(a - b) < 1e-9;
    }

    private static void check(String name, boolean ok) {
        if (ok) return;
        failures++;
        System.out.println("FAIL: " + name);
    }
}
