package com.wehavephones.mobile;

/**
 * Controller help for Battle.net's window. Battle.net reads the XInput pad
 * Wine exposes and has its own navigation (the d-pad and left stick move a
 * focus ring, A activates, LB and RB page carousels, the right stick
 * scrolls), so normal mode leaves those alone and only adds what Battle.net
 * lacks: Y opens the keyboard for text fields and View (Select) turns on
 * pointer mode.
 *
 * <p>Pointer mode reaches what the focus ring cannot. The guest then sees an
 * idle pad, the left stick moves the pointer, A clicks and holds (to drag),
 * X right-clicks, the right stick and the triggers scroll, the d-pad sends
 * arrow keys with key repeat, LB and RB move the keyboard focus (Shift+Tab,
 * Tab), B is Escape and Start is Enter. View turns it off again. The key
 * layout follows DroidDeck's controller navigation for store launchers, with
 * a pointer added because Battle.net is built for the mouse.
 *
 * <p>Pure Java, host-tested. {@link BattleNetControllerBridge} feeds it the
 * session's gamepad state and calls {@link #tick} on each frame while
 * {@link #moving} is true.
 */
public final class BattleNetPadInput {
    /** Logical keys sent to {@link Sink#key}. */
    public static final int KEY_ENTER = 1, KEY_ESCAPE = 2, KEY_TAB = 3, KEY_SHIFT = 4,
            KEY_UP = 5, KEY_DOWN = 6, KEY_LEFT = 7, KEY_RIGHT = 8;
    /** Pointer buttons sent to {@link Sink#button}. */
    public static final int BUTTON_LEFT = 1, BUTTON_RIGHT = 2;

    /** Button bits of {@link State#buttons}. */
    public static final int A = 1, B = 1 << 1, X = 1 << 2, Y = 1 << 3, LB = 1 << 4, RB = 1 << 5,
            START = 1 << 6, UP = 1 << 7, DOWN = 1 << 8, LEFT = 1 << 9, RIGHT = 1 << 10, LT = 1 << 11, RT = 1 << 12,
            SELECT = 1 << 13;

    static final float STICK_DEAD_ZONE = 0.18f;
    /** Full deflection crosses this share of the screen width per second. */
    static final float POINTER_SCREENS_PER_SECOND = 0.85f;
    static final float SCROLL_STEPS_PER_SECOND = 14f;
    static final long REPEAT_FIRST_MS = 350;
    static final long REPEAT_MS = 90;

    public interface Sink {
        void key(int key, boolean down);
        void button(int button, boolean down);
        void move(int dx, int dy);
        /** Positive scrolls down, negative up; one wheel notch per step. */
        void scroll(int steps);
        void keyboard();
        /** Pointer mode turned on or off. */
        void pointerMode(boolean on);
    }

    public static final class State {
        public int buttons;
        public float leftX;
        public float leftY;
        public float rightX;
        public float rightY;
    }

    private boolean pointer;
    private int held;
    private float leftX;
    private float leftY;
    private float rightY;
    private float restX;
    private float restY;
    private float restScroll;
    private int repeatKey;
    private long repeatAt;

    public boolean pointerMode() {
        return pointer;
    }

    /** A new controller state; edges become key and button events. */
    public void onState(State state, long nowMs, Sink sink) {
        int now = state.buttons;
        int changed = now ^ held;
        held = now;
        leftX = state.leftX;
        leftY = state.leftY;
        rightY = state.rightY;
        if (changed == 0) return;
        if ((changed & now & Y) != 0) sink.keyboard();
        if ((changed & now & SELECT) != 0) {
            setPointer(!pointer, sink);
            return;
        }
        if (!pointer) return;
        edge(changed, now, A, () -> sink.button(BUTTON_LEFT, true), () -> sink.button(BUTTON_LEFT, false));
        edge(changed, now, X, () -> sink.button(BUTTON_RIGHT, true), () -> sink.button(BUTTON_RIGHT, false));
        edge(changed, now, B, () -> sink.key(KEY_ESCAPE, true), () -> sink.key(KEY_ESCAPE, false));
        edge(changed, now, START, () -> sink.key(KEY_ENTER, true), () -> sink.key(KEY_ENTER, false));
        edge(changed, now, RB, () -> sink.key(KEY_TAB, true), () -> sink.key(KEY_TAB, false));
        edge(changed, now, LB, () -> {
            sink.key(KEY_SHIFT, true);
            sink.key(KEY_TAB, true);
        }, () -> {
            sink.key(KEY_TAB, false);
            sink.key(KEY_SHIFT, false);
        });
        int[][] arrows = {{UP, KEY_UP}, {DOWN, KEY_DOWN}, {LEFT, KEY_LEFT}, {RIGHT, KEY_RIGHT}};
        for (int[] arrow : arrows) {
            if ((changed & arrow[0]) == 0) continue;
            boolean down = (now & arrow[0]) != 0;
            sink.key(arrow[1], down);
            if (down) {
                repeatKey = arrow[1];
                repeatAt = nowMs + REPEAT_FIRST_MS;
            }
            else if (repeatKey == arrow[1]) repeatKey = 0;
        }
    }

    /** True while the pointer, scrolling or a held arrow needs frame ticks. */
    public boolean moving() {
        return pointer && (magnitude(leftX, leftY) > STICK_DEAD_ZONE || scrollRate() != 0 || repeatKey != 0);
    }

    /** One frame: moves the pointer by the stick, scrolls, repeats a held arrow. */
    public void tick(long nowMs, float seconds, int screenWidth, Sink sink) {
        if (!pointer) return;
        float dt = Math.max(0f, Math.min(0.05f, seconds));
        float m = magnitude(leftX, leftY);
        if (m > STICK_DEAD_ZONE) {
            float scaled = (Math.min(1f, m) - STICK_DEAD_ZONE) / (1f - STICK_DEAD_ZONE);
            float speed = scaled * scaled * POINTER_SCREENS_PER_SECOND * screenWidth;
            restX += leftX / m * speed * dt;
            restY += leftY / m * speed * dt;
            int dx = (int)restX;
            int dy = (int)restY;
            restX -= dx;
            restY -= dy;
            if (dx != 0 || dy != 0) sink.move(dx, dy);
        }
        else {
            restX = 0;
            restY = 0;
        }
        float rate = scrollRate();
        if (rate != 0) {
            restScroll += rate * SCROLL_STEPS_PER_SECOND * dt;
            int steps = (int)restScroll;
            restScroll -= steps;
            if (steps != 0) sink.scroll(steps);
        }
        else restScroll = 0;
        if (repeatKey != 0 && nowMs >= repeatAt) {
            sink.key(repeatKey, false);
            sink.key(repeatKey, true);
            repeatAt = nowMs + REPEAT_MS;
        }
    }

    /** Releases everything held and leaves pointer mode (the bridge turned off or the controller left). */
    public void reset(Sink sink) {
        if (pointer) setPointer(false, sink);
        held = 0;
        leftX = leftY = rightY = 0;
    }

    private void setPointer(boolean on, Sink sink) {
        if (pointer == on) return;
        if (!on) releaseAll(sink);
        pointer = on;
        repeatKey = 0;
        restX = restY = restScroll = 0;
        sink.pointerMode(on);
    }

    /** Lets go of every key and button pointer mode may hold. */
    private void releaseAll(Sink sink) {
        if ((held & A) != 0) sink.button(BUTTON_LEFT, false);
        if ((held & X) != 0) sink.button(BUTTON_RIGHT, false);
        if ((held & B) != 0) sink.key(KEY_ESCAPE, false);
        if ((held & START) != 0) sink.key(KEY_ENTER, false);
        if ((held & RB) != 0) sink.key(KEY_TAB, false);
        if ((held & LB) != 0) {
            sink.key(KEY_TAB, false);
            sink.key(KEY_SHIFT, false);
        }
        if ((held & UP) != 0) sink.key(KEY_UP, false);
        if ((held & DOWN) != 0) sink.key(KEY_DOWN, false);
        if ((held & LEFT) != 0) sink.key(KEY_LEFT, false);
        if ((held & RIGHT) != 0) sink.key(KEY_RIGHT, false);
    }

    private float scrollRate() {
        float rate = Math.abs(rightY) > STICK_DEAD_ZONE ? rightY : 0f;
        if ((held & RT) != 0) rate += 1f;
        if ((held & LT) != 0) rate -= 1f;
        return Math.max(-1f, Math.min(1f, rate));
    }

    private static float magnitude(float x, float y) {
        return (float)Math.sqrt(x * x + y * y);
    }

    private static void edge(int changed, int now, int bit, Runnable down, Runnable up) {
        if ((changed & bit) == 0) return;
        if ((now & bit) != 0) down.run();
        else up.run();
    }
}
