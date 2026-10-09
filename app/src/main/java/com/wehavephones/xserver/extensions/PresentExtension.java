package com.wehavephones.xserver.extensions;

import static com.wehavephones.xserver.XClientRequestHandler.RESPONSE_CODE_SUCCESS;

import android.os.SystemClock;
import android.util.SparseArray;

import com.wehavephones.renderer.GPUImage;
import com.wehavephones.renderer.Texture;
import com.wehavephones.xconnector.XInputStream;
import com.wehavephones.xconnector.XOutputStream;
import com.wehavephones.xconnector.XStreamLock;
import com.wehavephones.xserver.Bitmask;
import com.wehavephones.xserver.Drawable;
import com.wehavephones.xserver.Pixmap;
import com.wehavephones.xserver.Window;
import com.wehavephones.xserver.XClient;
import com.wehavephones.xserver.XLock;
import com.wehavephones.xserver.XServer;
import com.wehavephones.xserver.errors.BadImplementation;
import com.wehavephones.xserver.errors.BadMatch;
import com.wehavephones.xserver.errors.BadPixmap;
import com.wehavephones.xserver.errors.BadWindow;
import com.wehavephones.xserver.errors.XRequestError;
import com.wehavephones.xserver.events.PresentCompleteNotify;
import com.wehavephones.xserver.events.PresentIdleNotify;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class PresentExtension implements Extension {
    public static final byte MAJOR_OPCODE = -103;
    private static final int FAKE_INTERVAL = 1000000 / 60;
    private static final long HIDDEN_FRAME_INTERVAL_MS = 200;
    public enum Kind {PIXMAP, MSC_NOTIFY}
    public enum Mode {COPY, FLIP, SKIP}
    private final SparseArray<Event> events = new SparseArray<>();
    private SyncExtension syncExtension;
    private final PresentPacer pacer = new PresentPacer();
    private ScheduledExecutorService pacingExecutor;

    private static abstract class ClientOpcodes {
        private static final byte QUERY_VERSION = 0;
        private static final byte PRESENT_PIXMAP = 1;
        private static final byte SELECT_INPUT = 3;
    }

    private static class Event {
        private Window window;
        private XClient client;
        private int id;
        private Bitmask mask;
    }

    /** While the session is hidden, holds presenting clients to 5 FPS; nothing is on screen. */
    public void setHidden(boolean hidden) {
        pacer.setIntervalMs(hidden ? HIDDEN_FRAME_INTERVAL_MS : 0);
    }

    @Override
    public String getName() {
        return "Present";
    }

    @Override
    public byte getMajorOpcode() {
        return MAJOR_OPCODE;
    }

    @Override
    public byte getFirstErrorId() {
        return 0;
    }

    @Override
    public byte getFirstEventId() {
        return 0;
    }

    private void sendIdleNotify(Window window, Pixmap pixmap, int serial, int idleFence) {
        if (idleFence != 0) syncExtension.setTriggered(idleFence);

        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentIdleNotify.getEventMask())) {
                    event.client.sendEvent(new PresentIdleNotify(event.id, window, pixmap, serial, idleFence));
                }
            }
        }
    }

    private void sendCompleteNotify(Window window, int serial, Kind kind, Mode mode, long ust, long msc) {
        synchronized (events) {
            for (int i = 0; i < events.size(); i++) {
                Event event = events.valueAt(i);
                if (event.window == window && event.mask.isSet(PresentCompleteNotify.getEventMask())) {
                    event.client.sendEvent(new PresentCompleteNotify(event.id, window, serial, kind, mode, ust, msc));
                }
            }
        }
    }

    private static void queryVersion(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        inputStream.skip(8);

        try (XStreamLock lock = outputStream.lock()) {
            outputStream.writeByte(RESPONSE_CODE_SUCCESS);
            outputStream.writeByte((byte)0);
            outputStream.writeShort(client.getSequenceNumber());
            outputStream.writeInt(0);
            outputStream.writeInt(1);
            outputStream.writeInt(0);
            outputStream.writePad(16);
        }
    }

    private void presentPixmap(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int windowId = inputStream.readInt();
        int pixmapId = inputStream.readInt();
        int serial = inputStream.readInt();
        inputStream.skip(8);
        short xOff = inputStream.readShort();
        short yOff = inputStream.readShort();
        inputStream.skip(8);
        int idleFence = inputStream.readInt();
        inputStream.skip(client.getRemainingRequestLength());

        final Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        final Pixmap pixmap = client.xServer.pixmapManager.getPixmap(pixmapId);
        if (pixmap == null) throw new BadPixmap(pixmapId);

        Drawable content = window.getContent();
        if (content.visual.depth != pixmap.drawable.visual.depth) throw new BadMatch();

        synchronized (content.renderLock) {
            content.copyArea((short)0, (short)0, xOff, yOff, pixmap.drawable.width, pixmap.drawable.height, pixmap.drawable);
            Runnable notify = () -> {
                long ust = System.nanoTime() / 1000;
                long msc = ust / FAKE_INTERVAL;
                sendIdleNotify(window, pixmap, serial, idleFence);
                sendCompleteNotify(window, serial, Kind.PIXMAP, Mode.COPY, ust, msc);
            };
            long delayMs = pacer.submit(notify, SystemClock.uptimeMillis());
            if (delayMs < 0) notify.run();
            else getPacingExecutor().schedule(pacer::sendNext, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    private synchronized ScheduledExecutorService getPacingExecutor() {
        if (pacingExecutor == null) {
            pacingExecutor = Executors.newSingleThreadScheduledExecutor((runnable) -> {
                Thread thread = new Thread(runnable, "PresentPacer");
                thread.setDaemon(true);
                return thread;
            });
        }
        return pacingExecutor;
    }

    private void selectInput(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int eventId = inputStream.readInt();
        int windowId = inputStream.readInt();
        Bitmask mask = new Bitmask(inputStream.readInt());

        Window window = client.xServer.windowManager.getWindow(windowId);
        if (window == null) throw new BadWindow(windowId);

        if (GPUImage.isSupported() && !mask.isEmpty()) {
            Drawable content = window.getContent();
            final Texture oldTexture = content.getTexture();
            client.xServer.getRenderer().xServerView.queueEvent(oldTexture::destroy);
            content.setTexture(new GPUImage(content.width, content.height));
        }

        synchronized (events) {
            Event event = events.get(eventId);
            if (event != null) {
                if (event.window != window || event.client != client) throw new BadMatch();

                if (!mask.isEmpty()) {
                    event.mask = mask;
                }
                else events.remove(eventId);
            }
            else {
                event = new Event();
                event.id = eventId;
                event.window = window;
                event.client = client;
                event.mask = mask;
                events.put(eventId, event);
            }
        }
    }

    @Override
    public void handleRequest(XClient client, XInputStream inputStream, XOutputStream outputStream) throws IOException, XRequestError {
        int opcode = client.getRequestData();
        if (syncExtension == null) syncExtension = client.xServer.getExtension(SyncExtension.MAJOR_OPCODE);

        switch (opcode) {
            case ClientOpcodes.QUERY_VERSION :
                queryVersion(client, inputStream, outputStream);
                break;
            case ClientOpcodes.PRESENT_PIXMAP:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.PIXMAP_MANAGER)) {
                    presentPixmap(client, inputStream, outputStream);
                }
                break;
            case ClientOpcodes.SELECT_INPUT:
                try (XLock lock = client.xServer.lock(XServer.Lockable.WINDOW_MANAGER)) {
                    selectInput(client, inputStream, outputStream);
                }
                break;
            default:
                throw new BadImplementation();
        }
    }
}
