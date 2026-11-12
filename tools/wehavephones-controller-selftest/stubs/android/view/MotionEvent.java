package android.view;

import java.util.HashMap;
import java.util.Map;

/** A single joystick ACTION_MOVE sample; axes that are not set read as 0 like on Android. */
public class MotionEvent {
    public static final int ACTION_MOVE = 2;
    public static final int AXIS_X = 0, AXIS_Y = 1, AXIS_Z = 11, AXIS_RZ = 14;
    public static final int AXIS_HAT_X = 15, AXIS_HAT_Y = 16, AXIS_LTRIGGER = 17, AXIS_RTRIGGER = 18;
    public static final int AXIS_GAS = 22, AXIS_BRAKE = 23;
    private final Map<Integer, Float> axes = new HashMap<>();
    private final InputDevice device = new InputDevice();

    public MotionEvent axis(int axis, float value) {
        axes.put(axis, value);
        return this;
    }

    public int getSource() {
        return InputDevice.SOURCE_JOYSTICK;
    }

    public int getAction() {
        return ACTION_MOVE;
    }

    public int getHistorySize() {
        return 0;
    }

    public float getAxisValue(int axis) {
        return axes.getOrDefault(axis, 0f);
    }

    public float getHistoricalAxisValue(int axis, int pos) {
        return getAxisValue(axis);
    }

    public InputDevice getDevice() {
        return device;
    }
}
