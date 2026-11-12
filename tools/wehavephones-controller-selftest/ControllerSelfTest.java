package com.wehavephones.inputcontrols;

import android.view.KeyEvent;
import android.view.MotionEvent;

/** Simultaneous physical controller inputs, in both Switch-style (keys) and Xbox-style (axes) modes. */
public class ControllerSelfTest {
    private static int checks;

    public static void main(String[] args) {
        ExternalController c = new ExternalController();
        GamepadState s = c.state;
        int l2 = ExternalController.IDX_BUTTON_L2, r2 = ExternalController.IDX_BUTTON_R2;

        key(c, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_L2);
        check(s.isPressed(l2), "digital ZL press holds L2");
        move(c, new MotionEvent().axis(MotionEvent.AXIS_X, 0.6f).axis(MotionEvent.AXIS_Y, -0.4f));
        move(c, new MotionEvent().axis(MotionEvent.AXIS_X, -0.3f).axis(MotionEvent.AXIS_Y, 0.8f));
        check(s.isPressed(l2) && s.thumbLY == 0.8f, "moving a stick keeps a digital L2 held");
        key(c, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_L2);
        check(!s.isPressed(l2), "releasing ZL releases L2");

        key(c, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP);
        check(s.dpad[0], "D-pad key press registers while the stick is centred");
        move(c, new MotionEvent().axis(MotionEvent.AXIS_Y, -0.9f).axis(MotionEvent.AXIS_Z, 0.5f));
        check(s.dpad[0], "moving the sticks keeps a D-pad key held");
        key(c, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);
        check(s.dpad[0] && s.dpad[1] && s.getPovHat() == 1, "D-pad key press registers while the left stick is deflected");
        key(c, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP);
        key(c, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT);
        check(!s.dpad[0] && !s.dpad[1], "D-pad key releases");

        move(c, new MotionEvent().axis(MotionEvent.AXIS_LTRIGGER, 0.97f).axis(MotionEvent.AXIS_RTRIGGER, 0.6f));
        check(s.isPressed(l2) && s.isPressed(r2), "analog triggers that stop short of 1.0 press L2 and R2");
        move(c, new MotionEvent().axis(MotionEvent.AXIS_LTRIGGER, 0.2f).axis(MotionEvent.AXIS_RTRIGGER, 0.6f).axis(MotionEvent.AXIS_X, 1f));
        check(s.isPressed(l2), "a held trigger does not chatter around the press threshold");
        move(c, new MotionEvent().axis(MotionEvent.AXIS_LTRIGGER, 0.1f).axis(MotionEvent.AXIS_RTRIGGER, 0.6f));
        check(!s.isPressed(l2) && s.isPressed(r2), "letting a trigger off releases only that trigger");
        move(c, new MotionEvent());
        check(!s.isPressed(r2), "released triggers report up");
        move(c, new MotionEvent().axis(MotionEvent.AXIS_BRAKE, 1f).axis(MotionEvent.AXIS_GAS, 0.5f));
        check(s.isPressed(l2) && s.isPressed(r2), "brake and gas axes act as triggers");
        move(c, new MotionEvent().axis(MotionEvent.AXIS_RTRIGGER, 0.1f));
        check(!s.isPressed(l2) && !s.isPressed(r2), "brake and gas release");
        move(c, new MotionEvent().axis(MotionEvent.AXIS_LTRIGGER, 0.2f));
        check(!s.isPressed(l2), "a light resting pull below the press threshold stays up");

        key(c, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2);
        move(c, new MotionEvent().axis(MotionEvent.AXIS_RTRIGGER, 0.9f));
        move(c, new MotionEvent());
        check(s.isPressed(r2), "a controller reporting both a key and an axis stays held until both release");
        key(c, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BUTTON_R2);
        check(!s.isPressed(r2), "R2 releases once both sources are up");

        move(c, new MotionEvent().axis(MotionEvent.AXIS_HAT_Y, -1f).axis(MotionEvent.AXIS_X, 0.7f));
        check(s.dpad[0] && s.thumbLX == 0.7f, "hat D-pad works while the left stick is deflected");
        key(c, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_A);
        move(c, new MotionEvent().axis(MotionEvent.AXIS_HAT_X, 1f));
        check(!s.dpad[0] && s.dpad[1] && s.isPressed(ExternalController.IDX_BUTTON_A), "hat changes keep face buttons held");
        move(c, new MotionEvent());
        check(!s.dpad[1], "centred hat releases the D-pad");

        System.out.println("Controller self-test passed (" + checks + " checks)");
    }

    private static void key(ExternalController c, int action, int keyCode) {
        check(c.updateStateFromKeyEvent(new KeyEvent(action, keyCode)), "key " + keyCode + " is handled");
    }

    private static void move(ExternalController c, MotionEvent event) {
        check(c.updateStateFromMotionEvent(event), "joystick motion is handled");
    }

    private static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError(what);
    }
}
