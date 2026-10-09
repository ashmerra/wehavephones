package com.wehavephones.mobile;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;

/** Green LCD shared by launcher screens and the loading overlay. The texture is
 * static, so idle menus do not spend battery on decorative redraws. */
public class WeHavePhonesAtmosphereView extends View {
    private final Paint paint = new Paint();
    private final Path outline = new Path();
    private final float density;
    private Shader background;

    public WeHavePhonesAtmosphereView(Context context) { this(context, null); }

    public WeHavePhonesAtmosphereView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    /** Kept for screen lifecycle callers; the phone LCD background stays still. */
    public void setAnimated(boolean animated) { invalidate(); }

    static boolean animationsEnabled(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        background = new LinearGradient(0, 0, 0, Math.max(1, h),
                0xFFC8DAB3, 0xFFB1C99C, Shader.TileMode.CLAMP);
        outline.reset();
        outline.addRoundRect(new RectF(0, 0, w, h), 9 * density, 9 * density, Path.Direction.CW);
    }

    @Override protected void onDraw(Canvas canvas) {
        canvas.save();
        canvas.clipPath(outline);
        paint.setShader(background);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        paint.setShader(null);
        paint.setColor(0x04405C36);
        paint.setStrokeWidth(1);
        for (float y = 0; y < getHeight(); y += 3 * density) {
            canvas.drawLine(0, y, getWidth(), y, paint);
        }
        canvas.restore();
    }
}
