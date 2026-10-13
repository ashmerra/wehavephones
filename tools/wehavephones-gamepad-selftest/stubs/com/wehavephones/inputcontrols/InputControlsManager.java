package com.wehavephones.inputcontrols;
import java.util.ArrayList;
import org.json.JSONObject;
public class InputControlsManager {
    public final ArrayList<ControlsProfile> profiles = new ArrayList<>();
    public InputControlsManager() { profiles.add(new ControlsProfile(3, "Virtual Gamepad")); }
    public ArrayList<ControlsProfile> getProfiles() { return profiles; }
    public ControlsProfile getProfile(int id) {
        for (ControlsProfile profile : profiles) if (profile.id == id) return profile;
        return null;
    }
    public ControlsProfile importProfile(JSONObject data) {
        ControlsProfile profile = new ControlsProfile(9, data.optString("name"));
        profiles.add(profile);
        return profile;
    }
}
