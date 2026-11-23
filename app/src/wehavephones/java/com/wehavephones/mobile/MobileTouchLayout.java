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
    /**
     * The action wheel, in fractions of the game's height. Keep in step with the touch addon.
     * Its rightmost slot ends at the layout edge, or {@link #WHEEL_GAP} game heights left of
     * {@link #WHEEL_RIGHT} of the game's width, whichever is nearer: clear of Target, Use and Jump.
     * The hub sits {@link #WHEEL_HUB_X} left of that edge and {@link #WHEEL_HUB_Y} above the
     * bottom. Slots form two arcs around the hub.
     */
    public static final float WHEEL_RIGHT = .975f, WHEEL_GAP = .17f, WHEEL_HUB_X = .125f, WHEEL_HUB_Y = .075f, WHEEL_SLOT = .10f,
            WHEEL_INNER = .21f, WHEEL_OUTER = .32f;
    public static final float WHEEL_KEY = .107f, WHEEL_KEY_X = -.04f, WHEEL_KEY_Y = -.069f;
    /** Swipes around the hub count from this far out, where the angle around it is steady. */
    public static final float WHEEL_DEAD = .052f;
    /** Slot angles in degrees, counterclockwise from the right: four inner slots, then five outer slots. */
    public static final float[] WHEEL_ANGLES = {180, 143, 107, 70, 180, 154, 129, 104, 78};
    /** How far the wheel's touch area reaches from the hub, past the outer slots. */
    public static final float WHEEL_REACH = WHEEL_OUTER + WHEEL_SLOT * .7f;
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

        /** Translate these bounds to a new center, keeping their size and fitting them inside the given area. */
        public Bounds centeredInside(float x, float y, Bounds area) {
            float left = Math.max(area.left, Math.min(area.right - width(), x - width() / 2));
            float top = Math.max(area.top, Math.min(area.bottom - height(), y - height() / 2));
            return new Bounds(left, top, left + width(), top + height());
        }
    }

    public final Bounds[] buttons = new Bounds[COUNT];
    public final float stickX, stickY, radius, textSize, side;
    /** True when the toolbar is a column in the right rail rather than a row along the top. */
    public final boolean column;
    /** Map and Bags, or Keyboard and Inspect, moved into the menu because keys would be too small. */
    public final boolean overflow;
    /** The area covered by the toolbar, for placing hints beside it. */
    public final Bounds toolbar;
    /** The control size from {@link #controlScale}. 1 at the default interface size. */
    public final float controls;

    /**
     * Size of the native controls and the wheel for an interface size, relative to the default 150%.
     * They change half as much as the WoW interface, so small sizes stay easy to tap. Keep in step
     * with ControlScale in the touch addon.
     */
    public static float controlScale(float interfaceScale) { return .5f + interfaceScale / 3f; }

    /** Width of the wide toolbar zone, left of the minimap square, in fractions of the game's height. */
    public static float toolbarZone(float aspect) {
        return Math.max(.42f, Math.min(.75f, aspect - MINIMAP_RESERVE - .80f));
    }

    /** The touch addon uses its taller grids below this game aspect ratio. */
    public static boolean isCompact(float aspect) { return aspect < 1.59f; }

    public MobileTouchLayout(float width, float height, float density,
                             float insetLeft, float insetTop, float insetRight, float insetBottom,
                             Bounds viewport, boolean windows) {
        this(width, height, density, insetLeft, insetTop, insetRight, insetBottom, viewport, windows, 1);
    }

    public MobileTouchLayout(float width, float height, float density,
                             float insetLeft, float insetTop, float insetRight, float insetBottom,
                             Bounds viewport, boolean windows, float controls) {
        this.controls = controls;
        float w = Math.max(1, width - insetLeft - insetRight);
        float h = Math.max(1, height - insetTop - insetBottom);
        float fullThumb = Math.min(48 * density, Math.min(h * .115f, w * .095f));
        float thumb = fullThumb * controls;
        // Larger controls shrink back to the room there is before any key moves into the menu.
        float minKey = Math.min(40 * density * Math.min(1, controls), thumb);
        float margin = Math.min(22 * density, w * .025f);
        float top = insetTop + Math.min(12 * density, h * .018f);
        float right = insetLeft + w - margin;
        float vpH = Math.max(1, viewport.height()), aspect = viewport.width() / vpH;
        // Target, Use and Jump keep the bottom edge they have at full size, under the thumb.
        float thumbTop = insetTop + h * .48f + (fullThumb - thumb) * (3 + 2 * GAP);
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
            float room = viewport.top + TOOLBAR_BAND * controls * vpH - top;
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
        radius = Math.min(66 * density, Math.min(h * (w / h < 1.6f ? .12f : .13f), w * .13f)) * controls;
        stickX = insetLeft + Math.max(radius * 1.2f + margin, w * .12f);
        stickY = insetTop + h * (w / h < 1.6f ? .68f : .70f);
        textSize = Math.min(11 * density, s * .22f);
    }

    /**
     * Hub and wheel units in view pixels. Fullscreen stretching can give the axes different units.
     * Keep the anchor and its clamps in step with WheelHub in the touch addon.
     */
    public static final class Wheel {
        public final float x, y, unitX, unitY;

        public Wheel(Bounds viewport, float gameAspect, float controls, float xFraction, float yFraction) {
            unitX = viewport.width() / gameAspect * controls;
            unitY = viewport.height() * controls;
            if (validPosition(xFraction, yFraction)) {
                x = Math.max(viewport.left + WHEEL_REACH * unitX,
                        Math.min(viewport.right - WHEEL_HUB_X * unitX, viewport.left + xFraction * viewport.width()));
                y = Math.max(viewport.top + WHEEL_REACH * unitY,
                        Math.min(viewport.bottom - WHEEL_HUB_Y * unitY, viewport.top + yFraction * viewport.height()));
            } else {
                float layoutWidth = Math.min(viewport.width() * .8f, viewport.width() / gameAspect * 16 / 9);
                float right = viewport.left + Math.min((viewport.width() + layoutWidth) / 2,
                        viewport.width() * WHEEL_RIGHT - unitX * WHEEL_GAP);
                x = right - WHEEL_HUB_X * unitX;
                y = viewport.bottom - WHEEL_HUB_Y * unitY;
            }
        }

        public static boolean validPosition(float x, float y) { return x >= 0 && x <= 1 && y >= 0 && y <= 1; }

        /** Swipes start within reach, left of the rightmost slot and above the hub's bottom edge. */
        public boolean contains(float viewX, float viewY) {
            float dx = (viewX - x) / unitX, dy = (viewY - y) / unitY;
            return Math.hypot(dx, dy) <= WHEEL_REACH && dx <= WHEEL_HUB_X && dy <= WHEEL_HUB_Y;
        }

        public Bounds bounds() {
            return new Bounds(x - WHEEL_REACH * unitX, y - WHEEL_REACH * unitY,
                    x + WHEEL_HUB_X * unitX, y + WHEEL_HUB_Y * unitY);
        }
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
