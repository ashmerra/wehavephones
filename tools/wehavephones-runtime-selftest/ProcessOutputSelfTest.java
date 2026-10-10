package com.wehavephones.core;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Fill both real child pipes, including when no log observer can be installed. */
public final class ProcessOutputSelfTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void runChild(String label) throws Exception {
        CountDownLatch exited = new CountDownLatch(1);
        AtomicInteger status = new AtomicInteger(-1);
        String command = "\"" + System.getProperty("java.home") + "/bin/java\" -cp \""
                + System.getProperty("java.class.path") + "\" "
                + ProcessOutputSelfTest.class.getName() + " --child";
        int pid = ProcessHelper.exec(command, null, null, code -> {
            status.set(code);
            exited.countDown();
        });
        check(pid > 0, "child did not launch");
        try {
            check(exited.await(5, TimeUnit.SECONDS), "child output blocked " + label);
            check(status.get() == 0, "child failed " + label);
        } finally {
            ProcessHandle.of(pid).ifPresent(process -> {
                if (process.isAlive()) process.destroyForcibly();
            });
        }
    }

    private static void logFallback() throws Exception {
        File temp = Files.createTempDirectory("wehavephones-log-dirs").toFile();
        File publicDir = new File(temp, "downloads");
        File privateDir = new File(temp, "user-150/files/wehavephones/logs");
        try {
            check(SessionLogs.chooseLogDir(publicDir, privateDir).equals(publicDir),
                    "writable public logs were not retained");
            File denied = new File(publicDir.getPath()) {
                @Override public boolean canWrite() { return false; }
            };
            check(SessionLogs.chooseLogDir(denied, privateDir).equals(privateDir),
                    "inaccessible primary-user storage did not select app-private logs");
            File blocked = new File(temp, "blocked");
            check(blocked.createNewFile(), "test log-path blocker was not created");
            check(SessionLogs.chooseLogDir(blocked, privateDir).equals(privateDir),
                    "failed public directory creation did not select private logs");
            check(blocked.isFile(), "log selection overwrote an existing file");
            blocked.delete();
        } finally {
            publicDir.delete();
            temp.delete();
        }
    }

    private static void wineLogPruning() throws Exception {
        File dir = Files.createTempDirectory("wehavephones-wine-logs").toFile();
        try {
            for (int day = 10; day <= 22; day++) {
                check(new File(dir, "wine-202609" + day + "-120000.log").createNewFile(), "rotated log not created");
            }
            String[] others = {"wine.log", "guest.log", "wine-notes.log"};
            for (String name : others) check(new File(dir, name).createNewFile(), name + " not created");
            SessionLogs.pruneRotatedWineLogs(dir, 10);
            for (int day = 10; day <= 12; day++) {
                check(!new File(dir, "wine-202609" + day + "-120000.log").exists(), "old rotated log " + day + " kept");
            }
            for (int day = 13; day <= 22; day++) {
                check(new File(dir, "wine-202609" + day + "-120000.log").isFile(), "recent rotated log " + day + " deleted");
            }
            for (String name : others) check(new File(dir, name).isFile(), name + " was pruned");
        } finally {
            File[] files = dir.listFiles();
            if (files != null) for (File file : files) file.delete();
            dir.delete();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("--child")) {
            String line = "diagnostic:" + "x".repeat(1024);
            for (int i = 0; i < 4096; i++) {
                System.out.println(line);
                System.err.println(line);
            }
            System.out.println("OUT_DONE");
            System.err.println("ERR_DONE");
            return;
        }
        ProcessHelper.removeAllDebugCallbacks();
        runChild("without a logger");

        CountDownLatch markers = new CountDownLatch(2);
        Callback<String> observer = line -> {
            if (line.equals("OUT_DONE") || line.equals("ERR_DONE")) markers.countDown();
        };
        ProcessHelper.addDebugCallback(observer);
        try {
            runChild("with a logger");
            check(markers.await(2, TimeUnit.SECONDS), "stdout or stderr marker was lost");
        } finally {
            ProcessHelper.removeDebugCallback(observer);
        }

        AtomicInteger failures = new AtomicInteger();
        Callback<String> failingObserver = line -> {
            failures.incrementAndGet();
            throw new IllegalStateException("test log observer failed");
        };
        ProcessHelper.addDebugCallback(failingObserver);
        try {
            runChild("after a log observer failed");
            check(failures.get() > 0, "failing log observer was not exercised");
        } finally {
            ProcessHelper.removeAllDebugCallbacks();
        }
        logFallback();
        wineLogPruning();
        System.out.println("ProcessOutputSelfTest PASS: both pipes drain without logging, observer failures, private log fallback, and wine log pruning");
    }
}
