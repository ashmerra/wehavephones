package android.view;

/** Real Android key codes, so ExternalController's switch statements match the platform. */
public class KeyEvent {
    public static final int ACTION_DOWN = 0, ACTION_UP = 1;
    public static final int KEYCODE_UNKNOWN = 0;
    public static final int KEYCODE_DPAD_UP = 19, KEYCODE_DPAD_DOWN = 20, KEYCODE_DPAD_LEFT = 21, KEYCODE_DPAD_RIGHT = 22;
    public static final int KEYCODE_BUTTON_A = 96, KEYCODE_BUTTON_B = 97, KEYCODE_BUTTON_C = 98;
    public static final int KEYCODE_BUTTON_X = 99, KEYCODE_BUTTON_Y = 100, KEYCODE_BUTTON_Z = 101;
    public static final int KEYCODE_BUTTON_L1 = 102, KEYCODE_BUTTON_R1 = 103, KEYCODE_BUTTON_L2 = 104, KEYCODE_BUTTON_R2 = 105;
    public static final int KEYCODE_BUTTON_THUMBL = 106, KEYCODE_BUTTON_THUMBR = 107;
    public static final int KEYCODE_BUTTON_START = 108, KEYCODE_BUTTON_SELECT = 109, KEYCODE_BUTTON_MODE = 110;
    public static final int KEYCODE_BUTTON_1 = 188, KEYCODE_BUTTON_16 = 203;
    private final int action, keyCode;

    public KeyEvent(int action, int keyCode) {
        this.action = action;
        this.keyCode = keyCode;
    }

    public int getAction() {
        return action;
    }

    public int getKeyCode() {
        return keyCode;
    }

    public int getScanCode() {
        return 0;
    }

    public int getSource() {
        return InputDevice.SOURCE_GAMEPAD;
    }

    public int getDeviceId() {
        return 1;
    }

    public static String keyCodeToString(int keyCode) {
        return Integer.toString(keyCode);
    }
}
