package com.wehavephones.inputcontrols;

import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;

/**
 * Feeds a physical controller to the guest through libfakeinput.so. The
 * preload library redirects {@code /dev/input/eventN} to
 * {@code FAKE_EVDEV_DIR/eventN}, answers the evdev ioctls as "Generic HID
 * Gamepad N", and reads the file as a stream of {@code struct input_event}
 * (24 bytes on arm64). Wine's SDL joystick backend then exposes it to XInput
 * and DirectInput. The file must exist before Wine starts (the directory is
 * scanned once) and is emptied per session so old events are not replayed;
 * it only grows, since a reader's offset cannot move back.
 */
public class FakeGamepadWriter {
    private static final String TAG = "FakeGamepadWriter";
    private static final int EVENT_SIZE = 24;
    private static final short EV_SYN = 0x00, EV_KEY = 0x01, EV_ABS = 0x03;
    private static final short SYN_REPORT = 0x00;
    private static final short ABS_X = 0x00, ABS_Y = 0x01, ABS_RX = 0x03, ABS_RY = 0x04;
    private static final short ABS_GAS = 0x09, ABS_BRAKE = 0x0a, ABS_HAT0X = 0x10, ABS_HAT0Y = 0x11;
    /** Linux gamepad codes by ExternalController button index (A, B, X, Y, L1, R1, Select, Start, L3, R3). */
    private static final short[] BUTTON_CODES = {0x130, 0x131, 0x133, 0x134, 0x136, 0x137, 0x13a, 0x13b, 0x13d, 0x13e};

    private final File eventFile;
    private final ByteBuffer buffer = ByteBuffer.allocate(EVENT_SIZE * 24).order(ByteOrder.LITTLE_ENDIAN);
    private FileChannel channel;
    private short buttons;
    private int thumbLX, thumbLY, thumbRX, thumbRY, triggerL, triggerR, hatX, hatY;

    private FakeGamepadWriter(File eventFile) {
        this.eventFile = eventFile;
    }

    /** Creates or empties {@code event0}; null when the directory cannot be prepared. */
    public static FakeGamepadWriter create(File fakeEvdevDir) {
        if (!fakeEvdevDir.isDirectory() && !fakeEvdevDir.mkdirs()) return null;
        FakeGamepadWriter writer = new FakeGamepadWriter(new File(fakeEvdevDir, "event0"));
        try {
            writer.channel = new FileOutputStream(writer.eventFile, false).getChannel();
            return writer;
        }
        catch (IOException e) {
            Log.w(TAG, "Cannot prepare " + writer.eventFile, e);
            return null;
        }
    }

    public synchronized void write(GamepadState state) {
        if (channel == null) return;
        buffer.clear();
        for (int i = 0; i < BUTTON_CODES.length; i++) {
            boolean pressed = state.isPressed(i);
            if (pressed != ((buttons & (1 << i)) != 0)) putEvent(EV_KEY, BUTTON_CODES[i], pressed ? 1 : 0);
        }
        buttons = state.buttons;
        thumbLX = putAxis(ABS_X, thumbLX, (int)(state.thumbLX * Short.MAX_VALUE));
        thumbLY = putAxis(ABS_Y, thumbLY, (int)(state.thumbLY * Short.MAX_VALUE));
        thumbRX = putAxis(ABS_RX, thumbRX, (int)(state.thumbRX * Short.MAX_VALUE));
        thumbRY = putAxis(ABS_RY, thumbRY, (int)(state.thumbRY * Short.MAX_VALUE));
        triggerL = putAxis(ABS_BRAKE, triggerL, state.isPressed(ExternalController.IDX_BUTTON_L2) ? 255 : 0);
        triggerR = putAxis(ABS_GAS, triggerR, state.isPressed(ExternalController.IDX_BUTTON_R2) ? 255 : 0);
        hatX = putAxis(ABS_HAT0X, hatX, state.getDPadX());
        hatY = putAxis(ABS_HAT0Y, hatY, state.getDPadY());
        if (buffer.position() == 0) return;
        putEvent(EV_SYN, SYN_REPORT, 0);
        buffer.flip();
        try {
            while (buffer.hasRemaining()) channel.write(buffer);
        }
        catch (IOException e) {
            Log.w(TAG, "Write failed", e);
        }
    }

    /** All buttons up and axes centered, e.g. when the controller disconnects. */
    public void release() {
        write(new GamepadState());
    }

    private int putAxis(short code, int previous, int value) {
        if (value != previous) putEvent(EV_ABS, code, value);
        return value;
    }

    private void putEvent(short type, short code, int value) {
        long now = System.currentTimeMillis();
        buffer.putLong(now / 1000).putLong((now % 1000) * 1000).putShort(type).putShort(code).putInt(value);
    }
}
