package com.wehavephones.mobile;

/** Screen geometry shared by drawing, hit testing and host checks. Coordinates are view pixels. */
public final class MobileTouchLayout {
    public static final int ESCAPE = 0, MAP = 1, BAGS = 2, MORE = 3, TARGET = 4,
            INTERACT = 5, JUMP = 6, MODE = 7, KEYBOARD = 8, INSPECT = 9, COUNT = 10;

    public static final class Bounds {
        public final float left, top, right, bottom;
        Bounds(float left, float top, float right, float bottom) {
            this.left = left; this.top = top; this.right = right; this.bottom = bottom;
        }
        public boolean contains(float x, float y) { return x >= left && x < right && y >= top && y < bottom; }
        public float width() { return right - left; }
        public float height() { return bottom - top; }
    }

    public final Bounds[] buttons = new Bounds[COUNT];
    public final float stickX, stickY, radius, textSize;

    public MobileTouchLayout(float width, float height, float density,
                             float insetLeft, float insetTop, float insetRight, float insetBottom,
                             boolean windows) {
        float w = Math.max(1, width - insetLeft - insetRight);
        float h = Math.max(1, height - insetTop - insetBottom);
        float side = Math.min(48 * density, Math.min(h * .115f, w * .095f));
        float gap = side * .16f, margin = Math.min(22 * density, w * .025f);
        float rowWidth = 5 * side + 4 * gap;
        float left = Math.max(margin, Math.min(w - margin - rowWidth, w * .56f - rowWidth / 2));
        float top = Math.min(12 * density, h * .018f);
        int[] toolbar = windows ? new int[]{ESCAPE, KEYBOARD, INSPECT, MORE, MODE}
                : new int[]{ESCAPE, MAP, BAGS, MORE, MODE};
        for (int i = 0; i < toolbar.length; i++) {
            float x = insetLeft + left + i * (side + gap);
            buttons[toolbar[i]] = new Bounds(x, insetTop + top, x + side, insetTop + top + side);
        }
        if (!windows) for (int i = TARGET; i <= JUMP; i++) {
            float y = insetTop + h * .48f + (i - TARGET) * (side + gap);
            buttons[i] = new Bounds(insetLeft + w - margin - side, y, insetLeft + w - margin, y + side);
        }
        radius = Math.min(66 * density, Math.min(h * (w / h < 1.6f ? .12f : .13f), w * .13f));
        stickX = insetLeft + Math.max(radius * 1.2f + margin, w * .12f);
        stickY = insetTop + h * (w / h < 1.6f ? .68f : .70f);
        textSize = Math.min(12 * density, side * .25f);
    }

    /** Renderer viewport, including letterboxing. Fullscreen may stretch the two axes separately. */
    public static Bounds viewport(float width, float height, float guestWidth, float guestHeight, boolean fullscreen) {
        if (fullscreen) return new Bounds(0, 0, width, height);
        float scale = Math.min(width / guestWidth, height / guestHeight);
        float left = (width - guestWidth * scale) / 2, top = (height - guestHeight * scale) / 2;
        return new Bounds(left, top, width - left, height - top);
    }

    /** Movable access to controls when a login screen or third-party panel needs the full view. */
    public static Bounds handle(float width, float height, float density, float x, float y,
                                float insetLeft, float insetTop, float insetRight, float insetBottom) {
        float w = Math.max(1, width - insetLeft - insetRight), h = Math.max(1, height - insetTop - insetBottom);
        float side = Math.min(48 * density, Math.min(h * .10f, w * .12f));
        float left = Math.max(insetLeft, Math.min(width - insetRight - side * 1.65f, x));
        float top = Math.max(insetTop, Math.min(height - insetBottom - side * .85f, y));
        return new Bounds(left, top, left + side * 1.65f, top + side * .85f);
    }
}
