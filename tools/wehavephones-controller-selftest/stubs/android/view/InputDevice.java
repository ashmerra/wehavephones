package android.view;

public class InputDevice {
    public static final int SOURCE_GAMEPAD = 0x00000401, SOURCE_JOYSTICK = 0x01000010;

    public static class MotionRange {
        public float getFlat() {
            return 0.05f;
        }
    }

    public MotionRange getMotionRange(int axis, int source) {
        return new MotionRange();
    }

    public static int[] getDeviceIds() {
        return new int[0];
    }

    public static InputDevice getDevice(int id) {
        return null;
    }

    public String getDescriptor() {
        return "selftest";
    }

    public String getName() {
        return "Self-test controller";
    }

    public int getSources() {
        return SOURCE_GAMEPAD | SOURCE_JOYSTICK;
    }

    public boolean isVirtual() {
        return false;
    }
}
