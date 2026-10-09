package com.wehavephones.alsaserver;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises the real client with a JNI shim that holds a write in flight. */
public final class ALSAClientSelfTest {
    private static CountDownLatch entered;
    private static CountDownLatch resume;
    private static volatile boolean writing;
    private static volatile boolean unmapped;
    private static final List<String> events = Collections.synchronizedList(new ArrayList<>());

    public static int onWrite(int frames) throws InterruptedException {
        writing = true;
        events.add("write-enter");
        entered.countDown();
        if (!resume.await(5, TimeUnit.SECONDS)) throw new AssertionError("write never resumed");
        check(!unmapped, "audio read from an unmapped buffer");
        events.add("write-exit");
        writing = false;
        return frames;
    }

    public static void onEvent(String event) {
        check(!writing, event + " raced with an active audio write");
        events.add(event);
        if (event.equals("unmap")) unmapped = true;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void race(boolean prepare) throws Exception {
        ALSAClient client = new ALSAClient();
        client.setDataType(ALSAClient.DataType.S16LE);
        client.setChannels(2);
        client.setSampleRate(44100);
        client.setBufferSize(256);
        client.prepare();
        ByteBuffer buffer = ByteBuffer.allocateDirect(client.getBufferSizeInBytes());
        client.setSharedBuffer(buffer);
        entered = new CountDownLatch(1);
        resume = new CountDownLatch(1);
        unmapped = false;
        events.clear();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread writer = new Thread(() -> {
            try { client.writeDataToStream(buffer); }
            catch (Throwable t) { failure.compareAndSet(null, t); }
        }, "audio-writer");
        Thread teardown = new Thread(() -> {
            try {
                if (prepare) client.prepare();
                else client.release();
            }
            catch (Throwable t) { failure.compareAndSet(null, t); }
        }, "audio-teardown");
        writer.start();
        try {
            check(entered.await(2, TimeUnit.SECONDS), "native write did not start");
            teardown.start();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (teardown.isAlive() && teardown.getState() != Thread.State.BLOCKED
                    && System.nanoTime() < deadline) Thread.sleep(1);
            check(teardown.getState() == Thread.State.BLOCKED,
                    "teardown did not wait for the active write: " + failure.get());
            check(!unmapped && client.getSharedBuffer() == buffer,
                    "shared buffer was released during playback");
            check(events.equals(List.of("write-enter")), "stream changed during write: " + events);
        }
        finally {
            resume.countDown();
            writer.join(3000);
            teardown.join(3000);
        }
        check(!writer.isAlive() && !teardown.isAlive(), "audio lifecycle deadlocked");
        if (failure.get() != null) throw new AssertionError("worker failed", failure.get());
        check(events.equals(prepare
                ? List.of("write-enter", "write-exit", "stop", "close", "unmap", "create", "start")
                : List.of("write-enter", "write-exit", "stop", "close", "unmap")),
                "unexpected release order: " + events);
        check(client.getSharedBuffer() == null, "released buffer remains attached");
        if (prepare) {
            check(client.pointer() == 0, "prepare did not reset playback position");
            client.release();
        } else {
            int count = events.size();
            client.writeDataToStream(buffer);
            client.release();
            check(events.size() == count, "released stream accepted audio or closed twice");
        }
    }

    public static void main(String[] args) throws Exception {
        race(false);
        race(true);
        System.out.println("ALSAClientSelfTest PASS: release and prepare wait for active writes");
    }
}
