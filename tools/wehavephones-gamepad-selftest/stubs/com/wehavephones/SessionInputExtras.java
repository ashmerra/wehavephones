package com.wehavephones;
import com.wehavephones.inputcontrols.GamepadState;
public class SessionInputExtras {
    public static short bridgeButtons;
    public static void onControllerInput(XServerDisplayActivity activity) {
        GamepadState state = activity.getWinHandler().getGamepadState();
        if (state != null) bridgeButtons = state.buttons;
    }
}
