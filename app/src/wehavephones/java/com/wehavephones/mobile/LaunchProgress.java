package com.wehavephones.mobile;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What the launch overlay shows, driven by the launchers' stage callbacks.
 * Plain Java (no Android types) so the reveal rules are host-tested.
 *
 * <p>The overlay covers the game only while there is nothing for the player
 * to do. It steps aside for good when the game is ready (WoW's first frame),
 * when the player may need to act (Battle.net asks the player to sign in, a
 * stage fails or times out while
 * a window exists), or when the player taps Show game. WoW's own login
 * errors and the realm login queue are therefore always visible. Only a
 * failure before any window appeared keeps the overlay up, as an error card.
 */
public final class LaunchProgress {
    /** Segments of the loading bar. */
    public enum Step { RUNTIME, BATTLE_NET, SIGN_IN, UPDATES, GAME }

    /** Segments of the one-button route, in order. */
    public static final List<Step> STEPS = Collections.unmodifiableList(
            Arrays.asList(Step.RUNTIME, Step.BATTLE_NET, Step.SIGN_IN, Step.UPDATES, Step.GAME));

    public enum Mode {
        /** Overlay up, launch in progress. */
        COVERING,
        /** Game is ready; the overlay fades out without a note. */
        DONE,
        /** Overlay stepped aside so the player can act; {@link #note} says why (empty when the player asked). */
        REVEALED,
        /** Failed before any window existed: the overlay stays up as an error card. */
        FAILED
    }

    private Step step = Step.RUNTIME;
    private Mode mode = Mode.COVERING;
    private String message = "";
    private String note = "";
    private double stepProgress = -1;
    private boolean windowShown;
    private boolean awaitingFirstFrame;


    public Step step() { return step; }
    public Mode mode() { return mode; }
    /** The launcher's latest stage message. */
    public String message() { return message; }
    public String note() { return note; }
    public boolean isCovering() { return mode == Mode.COVERING; }
    public boolean isWindowShown() { return windowShown; }
    /** True after the game process started, until its first frame is seen. */
    public boolean isAwaitingFirstFrame() { return awaitingFirstFrame && mode == Mode.COVERING; }
    /** Progress inside the current step (0..1), or -1 when unknown. */
    public double stepProgress() { return stepProgress; }

    public int stepIndex() {
        int index = STEPS.indexOf(step);
        return Math.max(index, 0);
    }

    /** Fill of the whole bar (0..1): finished steps, plus half a step (or the known progress) for the current one. */
    public double fraction() {
        int count = STEPS.size();
        double within = stepProgress >= 0 ? Math.min(1, stepProgress) : 0.3;
        if (mode == Mode.DONE) return 1;
        return Math.min(1, (stepIndex() + within) / count);
    }

    /** The session's first application window was mapped: the runtime is up. */
    public void onWindowShown() {
        windowShown = true;
        if (step == Step.RUNTIME && mode == Mode.COVERING) {
            moveTo(Step.BATTLE_NET);
        }
    }

    /** {@link OneButtonLauncher.Stage} by name, so this class stays free of Android types. */
    public void onOneButtonStage(String stage, String text, boolean error) {
        message = text;
        switch (stage) {
            case "STARTING":
                moveTo(windowShown ? Step.BATTLE_NET : Step.RUNTIME);
                break;
            case "SIGNING_IN":
                moveTo(Step.SIGN_IN);
                break;
            case "NEEDS_USER":
                moveTo(Step.SIGN_IN);
                reveal(text);
                break;
            case "CHECKING_UPDATES":
            case "UPDATING":
                moveTo(Step.UPDATES);
                break;
            case "LAUNCHING":
                moveTo(Step.GAME);
                break;
            case "RUNNING":
                moveTo(Step.GAME);
                awaitingFirstFrame = true;
                break;
            case "FAILED":
                fail(text);
                break;
            default:
                break;
        }
        if (error && !"FAILED".equals(stage)) fail(text);
    }

    /** Update progress inside the current step (Battle.net patch percentage). */
    public void onStepProgress(double progress) {
        stepProgress = progress;
    }

    /** WoW drew its first frame: the game takes over. */
    public void onFirstFrame() {
        if (awaitingFirstFrame) finish();
    }

    public void onShowGame() {
        reveal("");
    }

    private void moveTo(Step next) {
        if (mode != Mode.COVERING) return;
        // Steps only move forward; a late message from an earlier phase does not rewind the bar.
        if (STEPS.indexOf(next) < STEPS.indexOf(step)) return;
        if (next != step) stepProgress = -1;
        step = next;
    }

    private void finish() {
        if (mode == Mode.COVERING) mode = Mode.DONE;
        awaitingFirstFrame = false;
    }

    private void reveal(String why) {
        if (mode != Mode.COVERING && mode != Mode.FAILED) return;
        mode = Mode.REVEALED;
        note = why;
        awaitingFirstFrame = false;
    }

    private void fail(String why) {
        if (mode != Mode.COVERING) return;
        if (windowShown) {
            reveal(why);
        }
        else {
            mode = Mode.FAILED;
            note = why;
        }
        awaitingFirstFrame = false;
    }
}
