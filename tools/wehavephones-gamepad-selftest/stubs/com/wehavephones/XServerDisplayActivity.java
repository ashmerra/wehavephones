package com.wehavephones;
import android.content.Context;
import android.hardware.input.InputManager;
import com.wehavephones.inputcontrols.*;
import com.wehavephones.widget.InputControlsView;
import com.wehavephones.winhandler.WinHandler;
import com.wehavephones.xserver.XServer;
public class XServerDisplayActivity extends Context {
    public final InputManager inputManager = new InputManager();
    public final InputControlsManager profiles = new InputControlsManager();
    public final InputControlsView view = new InputControlsView();
    public final WinHandler winHandler = new WinHandler(this);
    public boolean destroyed;
    public int shows;
    public Object getSystemService(String name) { return inputManager; }
    public boolean isFinishing() { return false; }
    public boolean isDestroyed() { return destroyed; }
    public InputControlsView getInputControlsView() { return view; }
    public InputControlsManager getInputControlsManager() { return profiles; }
    public WinHandler getWinHandler() { return winHandler; }
    public void showInputControls(ControlsProfile profile) { view.profile = profile; view.visibility = 0; shows++; }
    public void hideInputControls() { view.profile = null; view.visibility = 8; }
    public void openGuestUrl(String url) {}
    public XServer getXServer() { return null; }
    public RenderView getXServerView() { return null; }
    public static class RenderView { public void requestRender() {} }
}
