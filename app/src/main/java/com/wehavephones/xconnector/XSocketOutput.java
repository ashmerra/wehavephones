package com.wehavephones.xconnector;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;

/** Sends X replies and events without blocking a caller holding X server locks. */
public final class XSocketOutput implements AutoCloseable {
    private static final int MAX_PENDING_BYTES = 32 * 1024 * 1024;
    private final ArrayDeque<ByteBuffer> pending = new ArrayDeque<>();
    private final int maxPendingBytes;
    private int socketFd;
    private int pendingBytes;
    private boolean closed;
    private IOException failure;

    static {
        System.loadLibrary("wehavephones");
    }

    public XSocketOutput(int fd) throws IOException {
        this(fd, MAX_PENDING_BYTES);
    }

    XSocketOutput(int fd, int maxPendingBytes) throws IOException {
        this.maxPendingBytes = maxPendingBytes;
        // Own a descriptor until the writer exits; closing the connector's
        // original must never let a queued write target a reused descriptor.
        socketFd = duplicateSocket(fd);
        if (socketFd < 0) throw new IOException("Failed to duplicate X client socket");
        Thread writer = new Thread(this::run, "XSocketWriter-" + fd);
        writer.setDaemon(true);
        writer.start();
    }

    public synchronized void write(ByteBuffer data) throws IOException {
        if (failure != null) throw failure;
        if (closed) throw new IOException("X client output is closed");
        int size = data.remaining();
        if (size > maxPendingBytes - pendingBytes) {
            failure = new IOException("X client output queue exceeded its byte limit");
            close();
            throw failure;
        }
        if (size == 0) return;
        ByteBuffer copy = ByteBuffer.allocateDirect(size);
        copy.put(data.duplicate()).flip();
        pending.addLast(copy);
        pendingBytes += size;
        notifyAll();
    }

    private void run() {
        try {
            while (true) {
                ByteBuffer data;
                synchronized (this) {
                    while (!closed && pending.isEmpty()) wait();
                    if (closed) return;
                    data = pending.removeFirst();
                }
                int result = writeFully(socketFd, data, data.remaining());
                synchronized (this) {
                    if (result < 0) {
                        failure = new IOException("Failed to write X client output: " + result);
                        return;
                    }
                    if (!closed) pendingBytes -= data.remaining();
                }
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        finally {
            synchronized (this) {
                close();
                closeSocket(socketFd);
                socketFd = -1;
            }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        pending.clear();
        pendingBytes = 0;
        // Interrupt a blocked send and wake the connector's reader for cleanup.
        shutdownSocket(socketFd);
        notifyAll();
    }

    private static native int duplicateSocket(int fd);
    private static native int writeFully(int fd, ByteBuffer data, int length);
    public static native void shutdownSocket(int fd);
    private static native void closeSocket(int fd);
}
