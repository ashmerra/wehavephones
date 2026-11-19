package com.wehavephones.mobile;

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
import android.widget.TextView;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;

import java.util.Locale;

/**
 * Covers the game session while {@link BattleNetAgentJobs} installs, updates,
 * repairs or removes a game: the game, the stage, bytes, speed and time left,
 * and the silver-framed bar over Runtime, Agent, Download and Install. It uses
 * Play's launch overlay layout, so both screens look alike.
 */
public class AgentJobOverlayView extends FrameLayout {
    private static final PathInterpolator EASE = new PathInterpolator(0.32f, 0.72f, 0f, 1f);
    private static final int STEP_RUNTIME = 0;
    private static final int STEP_AGENT = 1;
    private static final int STEP_DOWNLOAD = 2;
    private static final int STEP_INSTALL = 3;

    private final XServerDisplayActivity activity;
    private final boolean motion;
    private final WeHavePhonesAtmosphereView atmosphere;
    private final LaunchBarView bar;
    private final LinearLayout steps;
    private final TextView headline;
    private final TextView detail;
    private final TextView elapsed;
    private final TextView showScreen;
    private final TextView cancel;
    private int[] stepLabels = new int[0];
    private long startedAt;
    private boolean finished;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (startedAt > 0) {
                long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000;
                elapsed.setText(String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60));
            }
            if (!finished) postDelayed(this, 1000);
        }
    };

    public AgentJobOverlayView(XServerDisplayActivity activity) {
        super(activity);
        this.activity = activity;
        motion = WeHavePhonesAtmosphereView.animationsEnabled(activity);
        LayoutInflater.from(activity).inflate(R.layout.wehavephones_launch_overlay, this, true);
        setClickable(true);
        setFocusable(true);
        setElevation(12 * getResources().getDisplayMetrics().density);
        setTag(R.id.keep_in_picture_in_picture, true);
        atmosphere = findViewById(R.id.VOverlayAtmosphere);
        bar = findViewById(R.id.VOverlayBar);
        steps = findViewById(R.id.LLOverlaySteps);
        headline = findViewById(R.id.TVOverlayHeadline);
        detail = findViewById(R.id.TVOverlayDetail);
        elapsed = findViewById(R.id.TVOverlayElapsed);
        showScreen = findViewById(R.id.BTOverlayShowGame);
        cancel = findViewById(R.id.BTOverlayCancel);
        TextView tip = findViewById(R.id.TVOverlayTip);
        tip.setText(R.string.wehavephones_agent_tip);
        atmosphere.setAnimated(true);
        bar.setAnimated(motion);
        showScreen.setOnClickListener((v) -> BattleNetAgentJobs.reveal());
        cancel.setOnClickListener((v) -> BattleNetAgentJobs.leave(activity));
        if (motion) {
            setAlpha(0f);
            animate().alpha(1f).setDuration(300).setInterpolator(EASE).start();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    void render(BattleNetAgentJobs.Status status) {
        startedAt = status.startedAt;
        renderGame(status.job);
        buildSteps(status.job.action);
        headline.setText(status.headline);
        detail.setText(status.detail);
        int step = stepFor(status);
        boolean failed = status.phase == BattleNetAgentJobs.Phase.FAILED;
        boolean done = status.phase == BattleNetAgentJobs.Phase.DONE;
        double within = status.fraction < 0 ? 0 : status.fraction;
        float overall = done ? 1f : (float)((step + within) / stepLabels.length);
        bar.setFraction(Math.max(0f, Math.min(1f, overall)), failed);
        renderSteps(done ? stepLabels.length : step, failed);
        if (done || failed) {
            finished = true;
            cancel.setText(R.string.wehavephones_overlay_back);
            cancel.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
        }
        else {
            cancel.setText(R.string.wehavephones_overlay_cancel);
            cancel.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.wehavephones_ic_close, 0, 0, 0);
        }
        // An Agent-only session has nothing to show behind the overlay.
        showScreen.setVisibility(BattleNetAgentJobs.isAgentSession(activity) ? View.GONE : View.VISIBLE);
    }

    private void renderGame(BattleNetAgentJobs.Job job) {
        TextView title = findViewById(R.id.TVOverlayGame);
        TextView version = findViewById(R.id.TVOverlayVersion);
        TextView letter = findViewById(R.id.TVOverlayCrestLetter);
        title.setText(job.title);
        version.setText(actionLabel(job.action));
        letter.setText(job.letter);
        Drawable gem = findViewById(R.id.VOverlayCrestGem).getBackground().mutate();
        gem.setTintMode(PorterDuff.Mode.MULTIPLY);
        gem.setTint(0xFF0D315A);
    }

    private int actionLabel(BattleNetAgentJobs.Action action) {
        switch (action) {
            case UPDATE: return R.string.wehavephones_agent_action_update;
            case REPAIR: return R.string.wehavephones_agent_action_repair;
            case UNINSTALL: return R.string.wehavephones_agent_action_uninstall;
            case INSTALL:
            default: return R.string.wehavephones_agent_action_install;
        }
    }

    private void buildSteps(BattleNetAgentJobs.Action action) {
        int[] labels = action == BattleNetAgentJobs.Action.UNINSTALL
                ? new int[] {R.string.wehavephones_step_runtime, R.string.wehavephones_agent_step_agent, R.string.wehavephones_agent_step_remove}
                : new int[] {R.string.wehavephones_step_runtime, R.string.wehavephones_agent_step_agent,
                        action == BattleNetAgentJobs.Action.REPAIR ? R.string.wehavephones_agent_step_check : R.string.wehavephones_agent_step_download,
                        R.string.wehavephones_agent_step_install};
        if (java.util.Arrays.equals(labels, stepLabels)) return;
        stepLabels = labels;
        steps.removeAllViews();
        bar.setSteps(labels.length);
        for (int label : labels) {
            TextView view = new TextView(getContext());
            view.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            view.setGravity(Gravity.CENTER);
            view.setTextSize(12);
            view.setMaxLines(1);
            view.setCompoundDrawablePadding(Math.round(5 * getResources().getDisplayMetrics().density));
            view.setText(label);
            steps.addView(view);
        }
    }

    private int stepFor(BattleNetAgentJobs.Status status) {
        boolean uninstall = status.job.action == BattleNetAgentJobs.Action.UNINSTALL;
        switch (status.phase) {
            case STARTING: return STEP_RUNTIME;
            case CONNECTING: return STEP_AGENT;
            case REMOVING: return uninstall ? 2 : STEP_DOWNLOAD;
            case APPLYING: return STEP_INSTALL;
            case QUEUED:
            case PREPARING:
            case DOWNLOADING: return uninstall ? 2 : STEP_DOWNLOAD;
            case DONE: return stepLabels.length;
            case FAILED:
            default: return lastStep;
        }
    }

    private int lastStep;

    private void renderSteps(int current, boolean failed) {
        if (!failed) lastStep = Math.min(current, stepLabels.length - 1);
        for (int i = 0; i < steps.getChildCount(); i++) {
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

    void detach() {
        atmosphere.setAnimated(false);
        bar.setAnimated(false);
        ViewGroup parent = (ViewGroup)getParent();
        if (parent != null) parent.removeView(this);
    }
}
