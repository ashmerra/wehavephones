package com.wehavephones.mobile;

/** Screen geometry shared by drawing, hit testing and host checks. Coordinates are view pixels. */
public final class MobileTouchLayout {
    public static final int ESCAPE = 0, MAP = 1, BAGS = 2, MORE = 3, TARGET = 4, INTERACT = 5, JUMP = 6,
            PLAY = 7, MENUS = 8, KEYBOARD = 9, INSPECT = 10, HANDLE = 11, COUNT = 12;
    /**
     * The touch addon keeps the stock HUD out of these regions, in fractions of the game's height.
     * Wide games: the toolbar band at the top, left of a minimap square in the top-right corner.
     * Narrower games: the right rail beside the centered layout, above the thumb keys.
     */
    public static final float TOOLBAR_BAND = .14f, MINIMAP_RESERVE = .30f, ZONE_GAP = .012f;
    /** Each Play or Menus segment is narrower than a key. The two form one switch. */
    static final float SEGMENT = .85f, GAP = .16f;

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
    public final float stickX, stickY, radius, textSize, side;
    /** True when the toolbar is a column in the right rail rather than a row along the top. */
    public final boolean column;
    /** Map and Bags, or Keyboard and Inspect, moved into the menu because keys would be too small. */
    public final boolean overflow;
    /** The area covered by the toolbar, for placing hints beside it. */
    public final Bounds toolbar;

    /** Width of the wide toolbar zone, left of the minimap square, in fractions of the game's height. */
    public static float toolbarZone(float aspect) {
        return Math.max(.42f, Math.min(.75f, aspect - MINIMAP_RESERVE - .80f));
    }

    /** The touch addon uses its taller grids below this game aspect ratio. */
    public static boolean isCompact(float aspect) { return aspect < 1.59f; }

    public MobileTouchLayout(float width, float height, float density,
                             float insetLeft, float insetTop, float insetRight, float insetBottom,
                             Bounds viewport, boolean windows) {
        float w = Math.max(1, width - insetLeft - insetRight);
        float h = Math.max(1, height - insetTop - insetBottom);
        float thumb = Math.min(48 * density, Math.min(h * .115f, w * .095f));
        float minKey = Math.min(40 * density, thumb);
        float margin = Math.min(22 * density, w * .025f);
        float top = insetTop + Math.min(12 * density, h * .018f);
        float right = insetLeft + w - margin;
        float vpH = Math.max(1, viewport.height()), aspect = viewport.width() / vpH;
        float thumbTop = insetTop + h * .48f;
        column = isCompact(aspect);
        int[] keys = windows ? new int[]{ESCAPE, KEYBOARD, INSPECT, MORE} : new int[]{ESCAPE, MAP, BAGS, MORE};
        int[] few = {ESCAPE, MORE};
        float s;
        boolean less = false;
        Bounds band;
        if (!column) {
            // Upper right: navigation beside the minimap, clear of the unit frames on the left.
            float zoneRight = Math.min(right, viewport.right - (MINIMAP_RESERVE + ZONE_GAP) * vpH);
            float zoneLeft = Math.max(insetLeft + margin, zoneRight - toolbarZone(aspect) * vpH);
            float available = Math.max(0, zoneRight - zoneLeft);
            // A top inset can push keys down. They still end inside the band the addon keeps free.
            float room = viewport.top + TOOLBAR_BAND * vpH - top;
            float largest = room > 0 ? Math.min(thumb, room) : thumb;
            s = Math.min(largest, available / rowUnits(keys.length));
            if (s < Math.min(minKey, largest)) {
                less = true;
                keys = few;
                s = Math.min(largest, available / rowUnits(keys.length));
            }
            float x = zoneRight - s * rowUnits(keys.length);
            for (int key : keys) {
                buttons[key] = new Bounds(x, top, x + s, top + s);
                x += s * (1 + GAP);
            }
            buttons[PLAY] = new Bounds(x, top, x + s * SEGMENT, top + s);
            buttons[MENUS] = new Bounds(x + s * SEGMENT, top, x + 2 * s * SEGMENT, top + s);
            band = new Bounds(zoneRight - s * rowUnits(keys.length), top, zoneRight, top + s);
        } else {
            // Right rail: outside the addon's centered layout and above Target, Use and Jump.
            float layoutWidth = Math.min(viewport.width() * .8f, vpH * 16 / 9);
            float railLeft = Math.max(insetLeft, viewport.left + (viewport.width() + layoutWidth) / 2);
            float rail = Math.max(0, right - railLeft);
            float available = Math.max(0, thumbTop - thumb * GAP - top);
            boolean twoColumns = rail / (2 + GAP) >= minKey;
            if (twoColumns) {
                s = Math.min(thumb, Math.min(rail / (2 + GAP), available / (2 + SEGMENT + 2 * GAP)));
                float x = right - s * (2 + GAP), y = top;
                for (int i = 0; i < keys.length; i++) {
                    float kx = x + (i % 2) * s * (1 + GAP), ky = y + (i / 2) * s * (1 + GAP);
                    buttons[keys[i]] = new Bounds(kx, ky, kx + s, ky + s);
                }
                float sy = y + 2 * s * (1 + GAP), half = s * (2 + GAP) / 2;
                buttons[PLAY] = new Bounds(x, sy, x + half, sy + s * SEGMENT);
                buttons[MENUS] = new Bounds(x + half, sy, x + 2 * half, sy + s * SEGMENT);
                band = new Bounds(x, top, right, sy + s * SEGMENT);
            } else {
                s = Math.min(thumb, Math.min(rail, available / rowUnits(keys.length)));
                if (s < minKey) {
                    less = true;
                    keys = few;
                    s = Math.min(thumb, Math.min(rail, available / rowUnits(keys.length)));
                }
                float x = right - s, y = top;
                for (int key : keys) {
                    buttons[key] = new Bounds(x, y, x + s, y + s);
                    y += s * (1 + GAP);
                }
                buttons[PLAY] = new Bounds(x, y, right, y + s * SEGMENT);
                buttons[MENUS] = new Bounds(x, y + s * SEGMENT, right, y + 2 * s * SEGMENT);
                band = new Bounds(x, top, right, y + 2 * s * SEGMENT);
            }
        }
        side = s;
        overflow = less;
        toolbar = band;
        if (!windows) for (int i = TARGET; i <= JUMP; i++) {
            float y = thumbTop + (i - TARGET) * (thumb + thumb * GAP);
            buttons[i] = new Bounds(right - thumb, y, right, y + thumb);
        }
        radius = Math.min(66 * density, Math.min(h * (w / h < 1.6f ? .12f : .13f), w * .13f));
        stickX = insetLeft + Math.max(radius * 1.2f + margin, w * .12f);
        stickY = insetTop + h * (w / h < 1.6f ? .68f : .70f);
        textSize = Math.min(11 * density, s * .22f);
    }

    /** Keys, their gaps, and the joined Play and Menus switch, in key widths. */
    static float rowUnits(int keys) { return keys + 2 * SEGMENT + keys * GAP; }

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
        float handleWidth = side * 2.4f, handleHeight = side * .9f;
        float left = Math.max(insetLeft, Math.min(width - insetRight - handleWidth, x));
        float top = Math.max(insetTop, Math.min(height - insetBottom - handleHeight, y));
        return new Bounds(left, top, left + handleWidth, top + handleHeight);
    }
}
