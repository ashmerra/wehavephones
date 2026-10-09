package com.wehavephones.mobile;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.PathInterpolator;

/** Silver-framed LCD progress meter. It redraws only when progress changes. */
public class LaunchBarView extends View {
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private int steps = 5;
    private float fraction;
    private boolean failed;
    private boolean animate = true;
    private ValueAnimator fillAnimator;
    private Shader bezel;
    private Shader fill;

    public LaunchBarView(Context context) { this(context, null); }

    public LaunchBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public void setSteps(int steps) { this.steps = Math.max(1, steps); invalidate(); }

    public void setAnimated(boolean animated) {
        animate = animated && WeHavePhonesAtmosphereView.animationsEnabled(getContext());
        if (!animate && fillAnimator != null) fillAnimator.end();
    }

    /** Moves the fill to {@code target} (0..1) with a short eased slide. */
    public void setFraction(float target, boolean failed) {
        this.failed = failed;
        target = Math.max(0f, Math.min(1f, target));
        if (fillAnimator != null) fillAnimator.cancel();
        if (!animate || !isLaidOut()) {
            fraction = target;
            invalidate();
            return;
        }
        fillAnimator = ValueAnimator.ofFloat(fraction, target);
        fillAnimator.setDuration(600);
        fillAnimator.setInterpolator(new PathInterpolator(0.32f, 0.72f, 0f, 1f));
        fillAnimator.addUpdateListener((a) -> { fraction = (float)a.getAnimatedValue(); invalidate(); });
        fillAnimator.start();
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        bezel = new LinearGradient(0, 0, 0, Math.max(1, h), 0xFFF0F4F8, 0xFF93A9BC, Shader.TileMode.CLAMP);
        fill = new LinearGradient(0, 0, 0, Math.max(1, h), 0xFF3196E5, 0xFF1059AF, Shader.TileMode.CLAMP);
    }

    @Override protected void onDetachedFromWindow() {
        if (fillAnimator != null) fillAnimator.cancel();
        super.onDetachedFromWindow();
    }

    @Override protected void onDraw(Canvas canvas) {
        float w = getWidth(), h = getHeight(), inset = 3 * density;
        if (w <= 2 * inset || h <= 2 * inset) return;
        paint.setShader(bezel);
        track.set(0, 0, w, h);
        canvas.drawRoundRect(track, 6 * density, 6 * density, paint);
        paint.setShader(null);
        paint.setColor(0xFF123747);
        track.inset(inset, inset);
        canvas.drawRoundRect(track, 3 * density, 3 * density, paint);
        canvas.save();
        canvas.clipRect(track.left, track.top, track.left + track.width() * fraction, track.bottom);
        paint.setShader(failed ? null : fill);
        if (failed) paint.setColor(0xFF9A2838);
        canvas.drawRoundRect(track, 3 * density, 3 * density, paint);
        canvas.restore();
        paint.setShader(null);
        paint.setColor(0xFF123747);
        paint.setStrokeWidth(2 * density);
        for (int i = 1; i < steps; i++) {
            float x = track.left + track.width() * i / steps;
            canvas.drawLine(x, track.top, x, track.bottom, paint);
        }
    }
}
