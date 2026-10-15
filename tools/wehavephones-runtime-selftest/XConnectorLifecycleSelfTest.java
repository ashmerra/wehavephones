package com.wehavephones.xconnector;

import java.lang.reflect.Field;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

public final class XConnectorLifecycleSelfTest {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void await(BooleanSupplier condition, String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(2);
        check(condition.getAsBoolean(), message);
    }

    private static SocketChannel connect(String path) throws Exception {
        SocketChannel socket = SocketChannel.open(StandardProtocolFamily.UNIX);
        socket.connect(UnixDomainSocketAddress.of(path));
        return socket;
    }

    public static void main(String[] args) throws Exception {
        String path = Path.of(args[0], "lifecycle.sock").toString();
        ConcurrentHashMap<Client, AtomicInteger> shutdowns = new ConcurrentHashMap<>();
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger missing = new AtomicInteger();
        AtomicInteger duplicateShutdown = new AtomicInteger();
        ConnectionHandler handler = new ConnectionHandler() {
            public void handleNewConnection(Client client) {
                client.createIOStreams();
                shutdowns.put(client, new AtomicInteger());
            }
            public void handleConnectionShutdown(Client client) {
                if (shutdowns.get(client).incrementAndGet() != 1) duplicateShutdown.incrementAndGet();
            }
        };
        XConnectorEpoll[] reference = new XConnectorEpoll[1];
        RequestHandler reader = client -> {
            if (reference[0].getClient(client.clientSocket.fd) != client) missing.incrementAndGet();
            XInputStream input = client.getInputStream();
            if (input.available() == 0) return false;
            input.readByte();
            requests.incrementAndGet();
            return true;
        };
        XConnectorEpoll connector = new XConnectorEpoll(new UnixSocketConfig(path), handler, reader);
        reference[0] = connector;
        connector.setMultithreadedClients(true);
        Field threadField = XConnectorEpoll.class.getDeclaredField("epollThread");
        threadField.setAccessible(true);
        Thread serverThread = (Thread)threadField.get(connector);
        connector.start();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            // Two owners can close the same client during worker EOF and server shutdown.
            try (SocketChannel persistent = connect(path)) {
                await(() -> shutdowns.size() == 1, "persistent client initialized");
                Client client = shutdowns.keySet().iterator().next();
                CountDownLatch start = new CountDownLatch(1);
                List<Thread> closers = new ArrayList<>();
                for (int i = 0; i < 2; i++) {
                    Thread closer = new Thread(() -> {
                        try { start.await(); connector.killConnection(client); }
                        catch (Throwable error) { failures.add(error); }
                    });
                    closers.add(closer);
                    closer.start();
                }
                start.countDown();
                for (Thread closer : closers) { closer.join(2000); check(!closer.isAlive(), "close completed"); }
                check(failures.isEmpty(), "concurrent close raised an exception");
                check(shutdowns.get(client).get() == 1, "release exactly once");
                check(connector.getClient(client.clientSocket.fd) == null, "closed client removed");
            }

            // Reader workers remove clients while the accept thread publishes new ones.
            List<Thread> senders = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Thread sender = new Thread(() -> {
                    try {
                        for (int j = 0; j < 16; j++) {
                            try (SocketChannel socket = connect(path)) {
                                socket.write(ByteBuffer.wrap(new byte[] {42}));
                            }
                        }
                    } catch (Throwable error) { failures.add(error); }
                });
                senders.add(sender);
                sender.start();
            }
            for (Thread sender : senders) { sender.join(5000); check(!sender.isAlive(), "sender completed"); }
            check(failures.isEmpty(), "socket churn raised an exception");
            await(() -> requests.get() == 96, "all socket requests drained");
            await(() -> shutdowns.size() == 97 && shutdowns.values().stream().allMatch(n -> n.get() == 1),
                    "all closed peers released once");
            check(missing.get() == 0, "workers only read registered clients");
            check(duplicateShutdown.get() == 0, "no duplicate release");
        } finally {
            connector.stop();
            serverThread.join(5000);
            check(!serverThread.isAlive(), "server shutdown completed");
        }
        System.out.println("XConnectorLifecycleSelfTest PASS: concurrent close, client churn, registry visibility, and shutdown");
    }
}
