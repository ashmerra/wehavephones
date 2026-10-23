package com.wehavephones.inputcontrols;
import com.wehavephones.widget.InputControlsView;
public class ControlsProfile {
    public final int id;
    private final String name;
    private final GamepadState state = new GamepadState();
    public int reloads;
    public ControlsProfile(int id, String name) { this.id = id; this.name = name; }
    public String getName() { return name; }
    public boolean isVirtualGamepad() { return name.startsWith("Virtual Gamepad"); }
    public boolean isElementsLoaded() { return true; }
    public GamepadState getGamepadState() { return state; }
    public void loadElements(InputControlsView view) { reloads++; }
}
