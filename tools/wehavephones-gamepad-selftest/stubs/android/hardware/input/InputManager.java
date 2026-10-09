package android.hardware.input;
import android.os.Handler;
public class InputManager {
    public interface InputDeviceListener {
        void onInputDeviceAdded(int id);
        void onInputDeviceRemoved(int id);
        void onInputDeviceChanged(int id);
    }
    public InputDeviceListener listener;
    public void registerInputDeviceListener(InputDeviceListener listener, Handler handler) { this.listener = listener; }
    public void unregisterInputDeviceListener(InputDeviceListener listener) { this.listener = null; }
}
