package com.wehavephones.widget;
import com.wehavephones.inputcontrols.ControlsProfile;
public class InputControlsView {
    public ControlsProfile profile;
    public int visibility = 8;
    private boolean show = true;
    public ControlsProfile getProfile() { return profile; }
    public int getVisibility() { return visibility; }
    public boolean isShowTouchscreenControls() { return show; }
    public void setShowTouchscreenControls(boolean show) { this.show = show; }
}
