package com.wehavephones.inputcontrols;
import java.util.ArrayList;
import org.json.JSONObject;
public class InputControlsManager {
    public final ArrayList<ControlsProfile> profiles = new ArrayList<>();
    public InputControlsManager() { profiles.add(new ControlsProfile(3, "Virtual Gamepad")); }
    public ArrayList<ControlsProfile> getProfiles() { return profiles; }
    public ControlsProfile importProfile(JSONObject data) {
        ControlsProfile profile = new ControlsProfile(9, data.optString("name"));
        profiles.add(profile);
        return profile;
    }
}
