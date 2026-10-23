package com.wehavephones.winhandler;

import com.wehavephones.mobile.VirtualGamepadController;
import com.wehavephones.mobile.WeHavePhonesConfig;

import android.os.Handler;
import androidx.preference.PreferenceManager;
import com.wehavephones.SessionInputExtras;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.inputcontrols.*;
import com.wehavephones.winhandler.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;

/** Android edges are faked; the session controller, settings, WinHandler and evdev writer are real. */
public class GamepadSelfTest {
    private static int checks;

    public static void main(String[] args) throws Exception {
        XServerDisplayActivity activity = new XServerDisplayActivity();
        WinHandler win = activity.winHandler;
        VirtualGamepadController controller = new VirtualGamepadController(activity);
        Path evdev = Files.createTempDirectory("wehavephones-gamepad-events");
        FakeGamepadWriter writer = FakeGamepadWriter.create(evdev.toFile());
        win.setFakeGamepad(writer);
        try (CaptureSocket socket = new CaptureSocket()) {
            set(win, "socket", socket);
            set(win, "localhost", InetAddress.getLoopbackAddress());
            set(win, "initReceived", true);
            controller.start();
            check(!WeHavePhonesConfig.isVirtualGamepadDisabled(activity), "touch controls enabled by default");
            controller.refresh();
            check(activity.view.profile == null, "no controls over Battle.net before WoW starts");

            set(controller, "gameRunning", true);
            controller.refresh();
            ControlsProfile virtual = activity.view.profile;
            check(virtual != null && activity.view.visibility == 0, "WoW start shows touch controls");
            controller.refresh();
            check(activity.shows == 1, "polling does not repeatedly reset controls");
            request(win, RequestCodes.GET_GAMEPAD, (byte) 1, (byte) 1);
            drain(win);
            int guestId = buffer(socket.last).getInt(1);
            check(guestId == virtual.id, "guest discovers virtual controller");

            virtual.getGamepadState().setPressed(0, true);
            virtual.getGamepadState().thumbLX = 1;
            win.onVirtualGamepadInput(virtual.getGamepadState());
            check(SessionInputExtras.bridgeButtons == 1, "virtual input reaches pre-game bridge");
            ExternalController first = new ExternalController(41);
            activity.inputManager.listener.onInputDeviceAdded(41);
            check(activity.view.profile == null && win.getCurrentController() == first,
                    "connect hides touch controls and acquires controller without input");
            check(virtual.getGamepadState().buttons == 0 && virtual.reloads == 1,
                    "connect releases touches and resets element pointer state");
            check(SessionInputExtras.bridgeButtons == 0, "connect releases pre-game keys");
            checkReleased(evdev);
            drain(win); // Includes packets queued before the source switched.

            first.state.setPressed(1, true);
            first.state.thumbLY = -1;
            writer.write(first.state);
            SessionInputExtras.onControllerInput(activity);
            win.sendGamepadState();
            first.state.buttons = 0;
            drain(win);
            check(buffer(socket.last).getInt(2) == guestId && buffer(socket.last).getShort(6) == 2,
                    "queued physical state keeps both guest id and input snapshot");

            request(win, RequestCodes.GET_GAMEPAD, (byte) 1, (byte) 1);
            drain(win);
            check(buffer(socket.last).getInt(1) == guestId, "re-enumeration preserves guest id after handoff");
            requestState(win, guestId + 100);
            drain(win);
            check(socket.last[1] == 0 && win.getCurrentController() == first,
                    "unknown guest id returns disabled without discarding active controller");

            ExternalController second = new ExternalController(52);
            activity.inputManager.listener.onInputDeviceAdded(52);
            ExternalController.devices.remove(41);
            activity.inputManager.listener.onInputDeviceRemoved(41);
            check(activity.view.profile == null && win.getCurrentController() == second,
                    "removing one of two controllers keeps touch controls hidden");
            check(SessionInputExtras.bridgeButtons == 0, "disconnect releases physical pre-game keys");
            checkReleased(evdev);

            requestState(win, guestId); // Delay reply until after removing its controller.
            ExternalController.devices.clear();
            activity.inputManager.listener.onInputDeviceRemoved(52);
            check(activity.view.profile == virtual && win.getCurrentController() == null,
                    "last disconnect restores virtual controller and removes stale physical state");
            drain(win);
            check(buffer(socket.last).getInt(2) == guestId, "pending reply survives device removal");
            virtual.getGamepadState().setPressed(2, true);
            win.onVirtualGamepadInput(virtual.getGamepadState());
            requestState(win, guestId);
            drain(win);
            check(buffer(socket.last).getShort(6) == 4, "guest can read restored virtual buttons");

            WeHavePhonesConfig.setVirtualGamepadDisabled(activity, true);
            check(activity.view.profile == null, "setting immediately disables and releases touch controls");
            new ExternalController(63);
            activity.inputManager.listener.onInputDeviceAdded(63);
            ExternalController.devices.clear();
            activity.inputManager.listener.onInputDeviceRemoved(63);
            check(activity.view.profile == null, "opt-out survives connect/disconnect");
            WeHavePhonesConfig.setVirtualGamepadDisabled(activity, false);
            check(activity.view.profile == virtual, "clearing opt-out restores controls during game");
            activity.hideInputControls();
            controller.refresh();
            check(activity.view.profile == virtual, "automatic mode restores manually hidden controls");
            set(controller, "gameRunning", false);
            controller.refresh();
            check(activity.view.profile == null, "game exit removes virtual controls");

            new ExternalController(74);
            set(controller, "gameRunning", true);
            controller.refresh();
            check(activity.view.profile == null, "game starts with controller already connected");
            ExternalController.devices.clear(); // Simulate a missed notification while backgrounded.
            controller.refresh();
            check(activity.view.profile == virtual, "resume catches missed controller disconnect");

            ControlsProfile edited = new ControlsProfile(virtual.id, "Virtual Gamepad");
            activity.profiles.profiles.set(0, edited);
            activity.showInputControls(edited); // Returning from the editor applies the saved profile.
            int showsAfterEdit = activity.shows;
            controller.refresh();
            controller.refresh();
            check(activity.view.profile == edited && activity.shows == showsAfterEdit,
                    "polling preserves the reloaded profile instead of restoring pre-edit controls");
            edited.getGamepadState().setPressed(3, true);
            requestState(win, guestId);
            drain(win);
            check(buffer(socket.last).getInt(2) == guestId && buffer(socket.last).getShort(6) == 8,
                    "edited controls supply live guest input with the same controller id");
            WeHavePhonesConfig.setVirtualGamepadDisabled(activity, true);
            WeHavePhonesConfig.setVirtualGamepadDisabled(activity, false);
            check(activity.view.profile == edited, "disable and re-enable keep the edited profile");

            controller.stop();
            check(activity.inputManager.listener == null && PreferenceManager.listeners.isEmpty(),
                    "session teardown unregisters device and preference listeners");
            activity.hideInputControls();
            controller.refresh();
            ((Handler) get(controller, "handler")).drain();
            check(activity.view.profile == null, "stopped session cannot modify controls");

            activity.profiles.profiles.clear();
            VirtualGamepadController restored = new VirtualGamepadController(activity);
            set(restored, "gameRunning", true);
            restored.refresh();
            check(activity.view.profile != null && activity.view.profile.id == 9,
                    "deleted stock profile restored under a new id");
            restored.stop();
            checkManualChoice(activity);
        } finally {
            controller.stop();
            Files.deleteIfExists(evdev.resolve("event0"));
            Files.deleteIfExists(evdev);
        }
        System.out.println("GamepadSelfTest: PASS (" + checks + " checks)");
    }

    /** Input Controls picks (issue #25) must survive polling, controller hotplug and the opt-out. */
    private static void checkManualChoice(XServerDisplayActivity activity) throws Exception {
        activity.hideInputControls();
        ExternalController.devices.clear();
        VirtualGamepadController controller = new VirtualGamepadController(activity);
        controller.start();
        ControlsProfile copy = new ControlsProfile(4, "Virtual Gamepad (copy)");
        ControlsProfile keyboard = new ControlsProfile(5, "Keyboard");
        activity.profiles.profiles.add(copy);
        activity.profiles.profiles.add(keyboard);

        controller.refresh();
        controller.onControlsChosen(null, true); // OK without changes keeps automatic mode.
        set(controller, "gameRunning", true);
        controller.refresh();
        check(activity.view.profile != null && activity.view.profile.getName().equals("Virtual Gamepad"),
                "unchanged confirm keeps automatic controls");
        ControlsProfile automatic = activity.view.profile;
        controller.onControlsChosen(automatic, true);
        new ExternalController(85);
        activity.inputManager.listener.onInputDeviceAdded(85);
        check(activity.view.profile == null, "confirm without changes keeps controller handoff");
        ExternalController.devices.clear();
        activity.inputManager.listener.onInputDeviceRemoved(85);

        set(controller, "gameRunning", false);
        controller.refresh();
        check(activity.view.profile == null, "automatic controls leave the desktop");
        activity.showInputControls(copy);
        controller.onControlsChosen(null, true);
        controller.refresh();
        check(activity.view.profile == copy, "desktop pick of a gamepad profile stays after polling");
        check(VirtualGamepadController.describeSession(activity).contains("Mode: manual"),
                "diagnostics report manual mode");

        set(controller, "gameRunning", true);
        controller.refresh();
        check(activity.view.profile == copy, "game start keeps the picked profile");
        new ExternalController(86);
        activity.inputManager.listener.onInputDeviceAdded(86);
        check(activity.view.profile == copy, "controller connect keeps the picked profile");
        WeHavePhonesConfig.setVirtualGamepadDisabled(activity, true);
        check(activity.view.profile == copy, "opt-out keeps the picked profile");
        WeHavePhonesConfig.setVirtualGamepadDisabled(activity, false);
        ExternalController.devices.clear();
        activity.inputManager.listener.onInputDeviceRemoved(86);

        copy.getGamepadState().setPressed(0, true);
        int reloads = copy.reloads;
        activity.showInputControls(keyboard);
        controller.onControlsChosen(copy, true);
        controller.refresh();
        check(activity.view.profile == keyboard, "in-game keyboard pick is not replaced by the gamepad");
        check(copy.getGamepadState().buttons == 0 && copy.reloads == reloads + 1,
                "switching away releases the picked gamepad");
        activity.hideInputControls();
        controller.onControlsChosen(keyboard, true);
        controller.refresh();
        check(activity.view.profile == null, "in-game Disabled pick stays hidden");
        controller.stop();

        VirtualGamepadController next = new VirtualGamepadController(activity);
        set(next, "gameRunning", true);
        next.refresh();
        check(activity.view.profile != null && activity.view.profile.isVirtualGamepad(),
                "a new session starts automatic again");
        next.stop();
    }

    private static void checkReleased(Path dir) throws Exception {
        ByteBuffer events = buffer(Files.readAllBytes(dir.resolve("event0")));
        Map<Integer, Integer> lastValues = new HashMap<>();
        while (events.remaining() >= 24) {
            events.getLong(); events.getLong();
            int type = events.getShort();
            int code = events.getShort();
            int value = events.getInt();
            if (type != 0) lastValues.put((type << 16) | code, value);
        }
        check(!lastValues.isEmpty() && lastValues.values().stream().allMatch(value -> value == 0),
                "evdev releases every held button and axis on handoff");
    }

    private static void requestState(WinHandler win, int id) throws Exception {
        byte[] bytes = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(id).array();
        request(win, RequestCodes.GET_GAMEPAD_STATE, bytes);
    }

    private static void request(WinHandler win, byte code, byte... payload) throws Exception {
        ByteBuffer receive = (ByteBuffer) get(win, "receiveData");
        receive.clear(); receive.put(payload); receive.flip();
        Method method = WinHandler.class.getDeclaredMethod("handleRequest", byte.class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(win, code, 12345, payload.length + 1);
    }

    @SuppressWarnings("unchecked")
    private static void drain(WinHandler win) throws Exception {
        ArrayDeque<Runnable> actions = (ArrayDeque<Runnable>) get(win, "actions");
        while (!actions.isEmpty()) actions.remove().run();
    }

    private static ByteBuffer buffer(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }
    private static Object get(Object object, String name) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }
    private static void set(Object object, String name, Object value) throws Exception {
        Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value);
    }
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    private static class CaptureSocket extends DatagramSocket {
        byte[] last;
        CaptureSocket() throws SocketException { super(); }
        @Override public void send(DatagramPacket packet) {
            last = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
        }
    }
}
