package com.wehavephones.inputcontrols;
import android.view.*;
import java.util.LinkedHashMap;
public class ExternalController {
    public static final int IDX_BUTTON_L2 = 10, IDX_BUTTON_R2 = 11;
    public static final LinkedHashMap<Integer, ExternalController> devices = new LinkedHashMap<>();
    public final GamepadState state = new GamepadState();
    private final int id;
    public ExternalController(int id) { this.id = id; devices.put(id, this); }
    public static ExternalController getController(int id) {
        return id == 0 ? devices.values().stream().findFirst().orElse(null) : devices.get(id);
    }
    public static boolean isGameController(InputDevice device) { return device != null; }
    public int getDeviceId() { return id; }
    public String getName() { return "Physical controller"; }
    public boolean isConnected() { return devices.get(id) == this; }
    public boolean updateStateFromMotionEvent(MotionEvent event) { return false; }
    public boolean updateStateFromKeyEvent(KeyEvent event) { return false; }
}
