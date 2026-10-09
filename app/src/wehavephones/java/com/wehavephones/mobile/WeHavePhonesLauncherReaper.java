package com.wehavephones.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Closes Battle.net, Agent and CEF helper processes after the WoW game process
 * is observed, unless Battle.net closes itself first (it is set to exit once
 * it has launched the game; see {@link WeHavePhonesBnetConfig#applyExitOnGameLaunch}).
 * Only processes of this app's uid are touched, the game process
 * must already exist, and WinHandler/WinHandler-lite/WoW/wineserver are never
 * killed.
 */
public final class WeHavePhonesLauncherReaper {
    public interface Listener {
        void onLauncherClosed(int count);

        void onLauncherCloseSkipped(String message);
    }

    /** Time Battle.net gets to close itself; killing it while the game signs in sends the game to its own login. */
    public static final long GRACE_MS = 60_000L;
    private static final long POLL_MS = 2_000L;
    private static final long TERM_WAIT_MS = 3_000L;

    private static volatile WeHavePhonesLauncherReaper active;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile boolean running;

    private WeHavePhonesLauncherReaper(Context context, Listener listener) {
        this.listener = listener;
    }

    public static boolean isRunning() {
        WeHavePhonesLauncherReaper reaper = active;
        return reaper != null && reaper.running;
    }

    public static WeHavePhonesLauncherReaper start(Context context, Listener listener) {
        WeHavePhonesLauncherReaper existing = active;
        if (existing != null && existing.running) return existing;
        WeHavePhonesLauncherReaper reaper = new WeHavePhonesLauncherReaper(context, listener);
        active = reaper;
        reaper.start();
        return reaper;
    }

    public void cancel() {
        running = false;
    }

    private void start() {
        running = true;
        Thread thread = new Thread(this::run, "wehavephones-launcher-reaper");
        thread.setDaemon(true);
        thread.start();
    }

    private void run() {
        try {
            ProcessScanner.Entry wow = null;
            while (running) {
                wow = ProcessScanner.findWowProcess();
                if (wow != null) break;
                sleep(POLL_MS);
            }
            if (!running) return;

            boolean exited = false;
            for (long waited = 0; running && waited < GRACE_MS; waited += POLL_MS) {
                if (!ProcessScanner.isBattleNetRunning()) {
                    exited = true;
                    break;
                }
                sleep(POLL_MS);
            }
            if (!running) return;

            if (ProcessScanner.findWowProcess() == null) {
                postSkip("World of Warcraft exited before the launcher-close grace period ended");
                return;
            }
            if (exited) {
                postSkip("Battle.net closed itself after starting World of Warcraft");
                return;
            }

            int count = killLaunchers();
            if (count > 0) postClosed(count);
            else postSkip("No Battle.net/Agent processes were running to close");
        }
        finally {
            running = false;
            active = null;
        }
    }

    /**
     * Kill the launcher processes of this uid now. Refuses to do anything when
     * no WoW process exists, so it can never close the launcher too early.
     */
    public static int reapNow() {
        if (ProcessScanner.findWowProcess() == null) return 0;
        return killLaunchers();
    }

    /**
     * Battle.net (main and CEF helpers) and Agent, judged by the executable
     * only: the main Battle.net.exe carries flags such as
     * {@code --exec="launch WoW"} or {@code --game=wow_classic_beta}, and
     * searching those for "wow" used to spare it, so it kept its memory and
     * respawned the helpers the reap had just closed.
     */
    public static boolean isLauncherProcess(ProcessScanner.Entry entry) {
        if (entry == null || ProcessScanner.isWowProcess(entry)) return false;
        String program = ProcessScanner.program(entry).toLowerCase(Locale.ROOT);
        if (program.contains("winhandler") || program.contains("wineserver")) return false;
        String basename = program.substring(Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\')) + 1);
        return program.contains("battle.net") || basename.equals("agent.exe");
    }

    /** Battle.net itself (main or CEF helper), not Agent; zombies (no command line) are gone already. */
    public static boolean isBattleNetProcess(ProcessScanner.Entry entry) {
        if (!isLauncherProcess(entry) || entry.cmdline.isEmpty()) return false;
        String program = ProcessScanner.program(entry).toLowerCase(Locale.ROOT);
        // Agent lives under C:\ProgramData\Battle.net\, so judge the file name.
        return program.substring(Math.max(program.lastIndexOf('/'), program.lastIndexOf('\\')) + 1).startsWith("battle.net");
    }

    /**
     * Close Battle.net and Agent now, with or without a game: restarts a
     * Battle.net that hung before it could launch the game.
     */
    public static int closeLaunchersNow() {
        return killLaunchers();
    }

    private static int killLaunchers() {
        List<ProcessScanner.Entry> targets = new ArrayList<>();
        for (ProcessScanner.Entry entry : ProcessScanner.sameUidProcesses()) {
            if (isLauncherProcess(entry)) targets.add(entry);
        }
        if (targets.isEmpty()) return 0;

        for (ProcessScanner.Entry entry : targets) {
            try {
                Process.sendSignal(entry.pid, 15);
            }
            catch (Exception ignored) {}
        }

        long deadline = System.currentTimeMillis() + TERM_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            boolean anyAlive = false;
            for (ProcessScanner.Entry entry : targets) {
                if (new File("/proc/" + entry.pid).exists()) {
                    anyAlive = true;
                    break;
                }
            }
            if (!anyAlive) break;
            sleep(250);
        }

        int killed = 0;
        for (ProcessScanner.Entry entry : targets) {
            if (!new File("/proc/" + entry.pid).exists()) {
                killed++;
                continue;
            }
            try {
                Process.killProcess(entry.pid);
                killed++;
            }
            catch (Exception ignored) {}
        }
        return killed;
    }

    private void postClosed(final int count) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onLauncherClosed(count));
    }

    private void postSkip(final String message) {
        if (listener == null) return;
        mainHandler.post(() -> listener.onLauncherCloseSkipped(message));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
