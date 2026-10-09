package com.wehavephones.xconnector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

/** Uses real small-buffer Unix sockets and the production JNI writer. */
public final class XSocketOutputSelfTest {
    private static final byte[] PAYLOAD = new byte[1024 * 1024];
    static {
        System.loadLibrary("wehavephones");
        for (int i = 0; i < PAYLOAD.length; i++) PAYLOAD[i] = (byte)(i * 17);
    }

    private static native int[] socketPair();
    private static native int available(int fd);
    private static native byte[] readExactly(int fd, int length);
    private static native boolean drainToEof(int fd);
    private static native void closeFd(int fd);
    private static native void sendProbe(int fd);

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static ByteBuffer payload() {
        ByteBuffer data = ByteBuffer.allocateDirect(PAYLOAD.length);
        return data.put(PAYLOAD).flip();
    }

    private static void awaitBackpressure(int peer) throws Exception {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (available(peer) == 0 && System.nanoTime() < deadline) Thread.sleep(1);
        check(available(peer) > 0 && available(peer) < PAYLOAD.length,
                "writer did not reach socket backpressure");
    }

    private static void orderingUnderBackpressure() throws Exception {
        int[] fds = socketPair();
        XOutputStream output = new XOutputStream(new ClientSocket(), 4096);
        output.enableAsyncWrites(fds[0]);
        Object windowLock = new Object();
        try {
            synchronized (windowLock) {
                try (XStreamLock lock = output.lock()) { output.write(PAYLOAD); }
            }
            awaitBackpressure(fds[1]);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread reply = new Thread(() -> {
                try {
                    synchronized (windowLock) {
                        try (XStreamLock lock = output.lock()) {
                            output.write(new byte[] {1, 2, 3, 4});
                        }
                        try (XStreamLock lock = output.lock()) {
                            output.write(new byte[] {5, 6, 7, 8});
                        }
                    }
                } catch (Throwable t) { failure.set(t); }
            }, "X-request-handler-test");
            reply.start();
            reply.join(1000);
            check(!reply.isAlive(), "socket backpressure blocked an X request holding the window lock");
            check(failure.get() == null, "enqueue failed: " + failure.get());
            // Flush reuses the output buffer; queued packets must retain their bytes.
            byte[] expected = Arrays.copyOf(PAYLOAD, PAYLOAD.length + 8);
            System.arraycopy(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0, expected, PAYLOAD.length, 8);
            check(Arrays.equals(expected, readExactly(fds[1], expected.length)),
                    "queued replies were truncated, reordered, or overwritten");
        } finally {
            output.close();
            closeFd(fds[0]);
            closeFd(fds[1]);
        }
    }

    private static void closeDuringWrite() throws Exception {
        int[] fds = socketPair();
        XSocketOutput output = new XSocketOutput(fds[0]);
        try {
            output.write(payload());
            awaitBackpressure(fds[1]);
            // The connector may close/reuse its original while a send is active.
            closeFd(fds[0]);
            fds[0] = -1;
            int[] reused = socketPair();
            try {
                output.close();
                check(drainToEof(fds[1]), "close did not wake the blocked socket writer");
                sendProbe(reused[0]);
                check(Arrays.equals(new byte[] {9, 8, 7, 6}, readExactly(reused[1], 4)),
                        "output shutdown touched a reused connector descriptor");
            } finally {
                closeFd(reused[0]); closeFd(reused[1]);
            }
            try {
                output.write(payload());
                throw new AssertionError("closed output accepted a packet");
            } catch (IOException expected) {}
            output.close();
        } finally {
            output.close(); closeFd(fds[0]); closeFd(fds[1]);
        }
    }

    private static void queueLimitIncludesActiveWrite() throws Exception {
        int[] fds = socketPair();
        XSocketOutput output = new XSocketOutput(fds[0], PAYLOAD.length * 2);
        try {
            output.write(payload());
            awaitBackpressure(fds[1]);
            output.write(payload());
            try {
                output.write(ByteBuffer.allocateDirect(1));
                throw new AssertionError("queue byte limit did not include the active write");
            } catch (IOException expected) {
                check(expected.getMessage().contains("byte limit"), "wrong queue failure");
            }
            check(drainToEof(fds[1]), "queue overflow did not disconnect the stalled client");
        } finally {
            output.close(); closeFd(fds[0]); closeFd(fds[1]);
        }
    }

    private static void disconnectedPeer() throws Exception {
        int[] fds = socketPair();
        XSocketOutput output = new XSocketOutput(fds[0]);
        closeFd(fds[1]);
        try {
            output.write(ByteBuffer.allocateDirect(4));
            long deadline = System.nanoTime() + 2_000_000_000L;
            boolean failed = false;
            while (!failed && System.nanoTime() < deadline) {
                try { output.write(ByteBuffer.allocateDirect(4)); }
                catch (IOException expected) { failed = true; }
                Thread.sleep(1);
            }
            check(failed, "disconnected peer did not fail queued output");
        } finally {
            output.close(); closeFd(fds[0]);
        }
    }

    public static void main(String[] args) throws Exception {
        orderingUnderBackpressure();
        closeDuringWrite();
        queueLimitIncludesActiveWrite();
        disconnectedPeer();
        System.out.println("XSocketOutputSelfTest PASS: backpressure, ordering, partial sends, shutdown, and byte limit");
    }
}
