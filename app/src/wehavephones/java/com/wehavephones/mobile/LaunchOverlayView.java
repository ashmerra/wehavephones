package com.wehavephones.mobile;

import android.animation.ObjectAnimator;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;

import java.util.List;
import java.util.Locale;

/**
 * Loading screen over the game session while Play works: the game, one tip,
 * the stage line with elapsed time, and the silver-framed bar. It consumes
 * touches only while covering; when {@link LaunchProgress} says the game is
 * ready or the player may need to act, it fades out and, with a reason,
 * leaves a short note in the corner that touches pass through.
 */
public class LaunchOverlayView extends FrameLayout {
    private static final long TIP_MS = 12_000L;
    private static final long NOTE_MS = 6_000L;
    private static final PathInterpolator EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);

    private final XServerDisplayActivity activity;
    private final boolean motion;
    private final WeHavePhonesAtmosphereView atmosphere;
    private final LaunchBarView bar;
    private final LinearLayout steps;
    private final TextView headline;
    private final TextView detail;
    private final TextView elapsed;
    private final TextView tip;
    private final TextView showGame;
    private final TextView cancel;
    private final String[] tips;
    private boolean stepsBuilt;
    private long startedAt;
    private int tipIndex;
    private boolean leaving;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000;
            elapsed.setText(String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60));
            postDelayed(this, 1000);
        }
    };

    private final Runnable nextTip = new Runnable() {
        @Override
        public void run() {
            tipIndex = (tipIndex + 1) % tips.length;
            if (motion) {
                tip.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                    tip.setText(tips[tipIndex]);
                    tip.animate().alpha(1f).setDuration(350).setInterpolator(EASE).start();
                }).start();
            }
            else tip.setText(tips[tipIndex]);
            postDelayed(this, TIP_MS);
        }
    };

    public LaunchOverlayView(XServerDisplayActivity activity) {
        super(activity);
        this.activity = activity;
        motion = WeHavePhonesAtmosphereView.animationsEnabled(activity);
        LayoutInflater.from(activity).inflate(R.layout.wehavephones_launch_overlay, this, true);
        setClickable(true);
        setFocusable(true);
        setElevation(12 * getResources().getDisplayMetrics().density);
        atmosphere = findViewById(R.id.VOverlayAtmosphere);
        bar = findViewById(R.id.VOverlayBar);
        steps = findViewById(R.id.LLOverlaySteps);
        headline = findViewById(R.id.TVOverlayHeadline);
        detail = findViewById(R.id.TVOverlayDetail);
        elapsed = findViewById(R.id.TVOverlayElapsed);
        tip = findViewById(R.id.TVOverlayTip);
        showGame = findViewById(R.id.BTOverlayShowGame);
        cancel = findViewById(R.id.BTOverlayCancel);
        tips = getResources().getStringArray(R.array.wehavephones_overlay_tips);
        tipIndex = (int)((System.currentTimeMillis() / 1000) % tips.length);
        tip.setText(tips[tipIndex]);
        atmosphere.setAnimated(true);
        bar.setAnimated(motion);
        showGame.setOnClickListener((v) -> LaunchOverlayController.showGame());
        cancel.setOnClickListener((v) -> LaunchOverlayController.cancel(activity));
        if (motion) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(300).setInterpolator(EASE).start();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
        postDelayed(nextTip, TIP_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        removeCallbacks(nextTip);
        super.onDetachedFromWindow();
    }

    void render(LaunchProgress progress, LaunchOverlayController.Game game, long startedAt) {
        if (leaving) return;
        this.startedAt = startedAt;
        renderGame(game);
        if (!stepsBuilt) buildSteps();

        switch (progress.mode()) {
            case DONE:
                leave(null, null);
                return;
            case REVEALED:
                String note = progress.note();
                leave(note.isEmpty() ? null : getContext().getString(progress.step() == LaunchProgress.Step.SIGN_IN
                        ? R.string.wehavephones_overlay_note_sign_in : R.string.wehavephones_overlay_note_stopped), note);
                return;
            case FAILED:
                headline.setText(progress.note());
                detail.setText(R.string.wehavephones_overlay_failed_detail);
                cancel.setText(R.string.wehavephones_overlay_back);
                cancel.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
                removeCallbacks(tick);
                bar.setFraction((float)progress.fraction(), true);
                renderSteps(progress, true);
                return;
            case COVERING:
            default:
                headline.setText(progress.step() == LaunchProgress.Step.RUNTIME
                        ? getContext().getString(R.string.wehavephones_overlay_runtime) : trimEllipsis(progress.message()));
                detail.setText(detailFor(progress));
                bar.setFraction((float)progress.fraction(), false);
                renderSteps(progress, false);
        }
    }

    private void renderGame(LaunchOverlayController.Game game) {
        TextView title = findViewById(R.id.TVOverlayGame);
        TextView version = findViewById(R.id.TVOverlayVersion);
        TextView letter = findViewById(R.id.TVOverlayCrestLetter);
        if (game == null) {
            title.setText(R.string.wehavephones_game_default_title);
            version.setText("");
            letter.setText("W");
            return;
        }
        title.setText(game.title);
        version.setText(game.version);
        letter.setText(game.letter);
        Drawable gem = findViewById(R.id.VOverlayCrestGem).getBackground().mutate();
        gem.setTintMode(PorterDuff.Mode.MULTIPLY);
        gem.setTint(0xFF0D315A);
    }

    private int detailFor(LaunchProgress progress) {
        switch (progress.step()) {
            case BATTLE_NET: return R.string.wehavephones_overlay_detail_battle_net;
            case SIGN_IN: return R.string.wehavephones_overlay_detail_sign_in;
            case UPDATES: return R.string.wehavephones_overlay_detail_updates;
            case GAME: return R.string.wehavephones_overlay_detail_game;
            case RUNTIME:
            default: return R.string.wehavephones_overlay_detail_runtime;
        }
    }

    private void buildSteps() {
        stepsBuilt = true;
        steps.removeAllViews();
        bar.setSteps(LaunchProgress.STEPS.size());
        for (LaunchProgress.Step step : LaunchProgress.STEPS) {
            TextView label = new TextView(getContext());
            label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            label.setGravity(Gravity.CENTER);
            label.setTextSize(12);
            label.setMaxLines(1);
            label.setCompoundDrawablePadding(Math.round(5 * getResources().getDisplayMetrics().density));
            label.setText(labelFor(step));
            steps.addView(label);
        }
    }

    private void renderSteps(LaunchProgress progress, boolean failed) {
        List<LaunchProgress.Step> route = LaunchProgress.STEPS;
        int current = progress.stepIndex();
        for (int i = 0; i < route.size(); i++) {
            TextView label = (TextView)steps.getChildAt(i);
            int color;
            int icon = 0;
            if (i < current) {
                color = R.color.wehavephones_text_dim;
                icon = R.drawable.wehavephones_ic_check;
            }
            else if (i == current) {
                color = failed ? R.color.wehavephones_error : R.color.wehavephones_accent;
                icon = R.drawable.wehavephones_step_dot;
            }
            else color = R.color.wehavephones_text_faint;
            int tint = getContext().getColor(i < current ? R.color.wehavephones_accent : color);
            label.setTextColor(getContext().getColor(color));
            label.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
            label.setCompoundDrawableTintList(ColorStateList.valueOf(tint));
        }
    }

    private int labelFor(LaunchProgress.Step step) {
        switch (step) {
            case BATTLE_NET: return R.string.wehavephones_step_battle_net;
            case SIGN_IN: return R.string.wehavephones_step_sign_in;
            case UPDATES: return R.string.wehavephones_step_updates;
            case GAME: return R.string.wehavephones_step_game;
            case RUNTIME:
            default: return R.string.wehavephones_step_runtime;
        }
    }

    private static String trimEllipsis(String text) {
        String trimmed = text.trim();
        if (trimmed.endsWith("…")) return trimmed.substring(0, trimmed.length() - 1);
        if (trimmed.endsWith("...")) return trimmed.substring(0, trimmed.length() - 3);
        return trimmed;
    }

    /** Fade out and hand touches back to the game; with a reason, leave the note behind. */
    private void leave(String noteTitle, String noteText) {
        leaving = true;
        setClickable(false);
        setFocusable(false);
        ViewGroup parent = (ViewGroup)getParent();
        if (parent != null && noteTitle != null) showNote(parent, noteTitle, noteText);
        if (motion) animate().alpha(0f).setDuration(400).setInterpolator(EASE).withEndAction(this::detach).start();
        else detach();
    }

    void detach() {
        atmosphere.setAnimated(false);
        bar.setAnimated(false);
        ViewGroup parent = (ViewGroup)getParent();
        if (parent != null) parent.removeView(this);
    }

    private void showNote(final ViewGroup parent, String title, String text) {
        final View note = LayoutInflater.from(getContext()).inflate(R.layout.wehavephones_launch_note, parent, false);
        ((TextView)note.findViewById(R.id.TVNoteTitle)).setText(title);
        ((TextView)note.findViewById(R.id.TVNoteText)).setText(text);
        ViewGroup.LayoutParams inflated = note.getLayoutParams();
        FrameLayout.LayoutParams params = inflated instanceof FrameLayout.LayoutParams
                ? (FrameLayout.LayoutParams)inflated : new FrameLayout.LayoutParams(inflated);
        params.gravity = Gravity.TOP | Gravity.START;
        parent.addView(note, params);
        ProgressBar timer = note.findViewById(R.id.PBNoteTimer);
        timer.setProgress(1000);
        ObjectAnimator countdown = ObjectAnimator.ofInt(timer, "progress", 1000, 0);
        countdown.setDuration(NOTE_MS);
        countdown.start();
        if (motion) {
            note.setAlpha(0f);
            note.setTranslationY(12 * getResources().getDisplayMetrics().density);
            note.animate().alpha(1f).translationY(0).setDuration(500).setInterpolator(EASE).start();
        }
        note.postDelayed(() -> {
            if (motion) note.animate().alpha(0f).setDuration(400).withEndAction(() -> parent.removeView(note)).start();
            else parent.removeView(note);
        }, NOTE_MS);
    }
}
