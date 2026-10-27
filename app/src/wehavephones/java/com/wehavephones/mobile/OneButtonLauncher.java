package com.wehavephones.mobile;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.lifecycle.Lifecycle;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.core.FileUtils;
import com.wehavephones.mobile.devices.DeviceProfileSelector;
import com.wehavephones.winhandler.WinHandler;
import com.wehavephones.xserver.Window;
import com.wehavephones.xserver.XKeycode;
import com.wehavephones.xserver.XLock;
import com.wehavephones.xserver.XServer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * One-button Play: start (or reuse) the signed-in Battle.net session on the
 * game's page ({@code --game=<uid>}), wait for sign-in and for the product to
 * be up to date, then click Battle.net's own Play button. A Play button
 * Battle.net has greyed out (for example a pre-installed pre-release) fails
 * the run with "Play is not available" instead of clicking and restarting
 * Battle.net for nothing. Only a game Battle.net launched gets its sign-in
 * (a directly started game stops at its password prompt), and Battle.net, set
 * to exit on game launch
 * ({@code GameLaunchWindowBehavior}), then closes itself; one still running
 * after {@code launcherExitSeconds} is closed (the Wine+CEF+game peak
 * otherwise gets the phone's background apps killed). Progress goes to
 * {@link Listener}; every run is recorded in
 * {@code files/wehavephones/launch-last.json} and {@code launch.log} (and
 * logcat tag {@value #TAG}).
 */
public final class OneButtonLauncher {
    public static final String TAG = "WeHavePhonesLaunch";

    /** NEEDS_USER: Battle.net shows its login form (no saved Battle.net login), so the player signs in there. */
    public enum Stage { STARTING, SIGNING_IN, NEEDS_USER, CHECKING_UPDATES, UPDATING, LAUNCHING, RUNNING, FAILED }

    public interface Listener {
        void onStage(Stage stage, String message, boolean error);

        /** Progress of the current stage (0..1; Battle.net's patch progress). */
        default void onProgress(double progress) {}
    }

    private static final long POLL_MS = 1000L;
    private static final long MAX_LOG_BYTES = 256 * 1024;
    /**
     * Probe offsets from the Play click point (the button spans about -116..+121
     * across): Play's fill left of its label, and the settings gear beside it,
     * whose fill keeps the accent colour when Play is dimmed.
     */
    private static final int PLAY_FILL_OFFSET = -95;
    private static final int GEAR_FILL_OFFSET = 140;
    private static final int PROBE_RADIUS = 2;

    private static volatile OneButtonLauncher active;
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION = new java.util.concurrent.atomic.AtomicInteger();
    private final int generation = GENERATION.incrementAndGet();

    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile boolean running;
    private Stage lastStage;
    private String lastMessage = "";

    private WeHavePhonesLaunchPlan plan;
    private WeHavePhonesLaunchPlan.Product product;
    private Container container;
    private BattleNetLauncherLog.Tail tail;
    private String logsNewerThan = "";
    private final JSONObject record = new JSONObject();
    private final JSONArray attemptsJson = new JSONArray();
    private final long startedAt = SystemClock.elapsedRealtime();
    private long gameSeenAt;
    /** Battle.net's Play button reads as greyed out: stop the ladder instead of retrying. */
    private boolean playUnavailable;
    /** Update clicks after Agent failed an update, for the whole launch. */
    private int updateRetries;
    /** The log whose update failures were counted; a restarted Battle.net starts over. */
    private BattleNetLauncherLog failuresLog;
    private int failuresHandled;
    /** Update operations Battle.net had started before the last retry click, or -1 when none is pending. */
    private int startsBeforeRetry = -1;
    private long retryClickedAt;

    private OneButtonLauncher(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public static boolean isRunning() {
        OneButtonLauncher launcher = active;
        return launcher != null && launcher.running;
    }

    public static OneButtonLauncher start(Context context, Listener listener) {
        OneButtonLauncher existing = active;
        if (existing != null && existing.running) return existing;
        OneButtonLauncher launcher = new OneButtonLauncher(context, listener);
        active = launcher;
        launcher.running = true;
        Thread thread = new Thread(launcher::run, "wehavephones-one-button");
        thread.setDaemon(true);
        thread.start();
        return launcher;
    }

    public void cancel() {
        running = false;
    }

    /** Stops the running launch, if any (the overlay's Cancel). */
    public static void cancelActive() {
        GENERATION.incrementAndGet();
        OneButtonLauncher current = active;
        if (current != null) current.cancel();
    }

    // ---- flow ---------------------------------------------------------------------------

    private void run() {
        try {
            put("startedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(new Date()));
            put("attempts", attemptsJson);
            event("one-button launch started");
            runFlow();
        }
        catch (Throwable throwable) {
            Log.e(TAG, "launch failed", throwable);
            fail(appContext.getString(R.string.wehavephones_launch_error, String.valueOf(throwable)));
        }
        finally {
            put("elapsedMs", elapsed());
            writeRecord();
            running = false;
            if (active == this) active = null;
        }
    }

    private void runFlow() throws Exception {
        stage(Stage.STARTING, appContext.getString(R.string.wehavephones_launch_starting));
        plan = loadPlan();
        if (plan == null) {
            fail(appContext.getString(R.string.wehavephones_launch_no_plan));
            return;
        }

        XServerDisplayActivity session = liveSession();
        boolean warm = session != null;
        WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.loadOrNull(appContext);
        if (profiles == null) {
            fail(appContext.getString(R.string.wehavephones_stage_no_profiles));
            return;
        }
        WeHavePhonesRuntimeProfiles.Profile profile = profiles.getSingleStageProfile();
        if (!warm) {
            WeHavePhonesRuntimeApplier.Result applied = WeHavePhonesRuntimeApplier.apply(appContext, WeHavePhonesRuntimeApplier.Stage.SINGLE);
            if (!applied.success) {
                fail(appContext.getString(R.string.wehavephones_stage_apply_failed) + ": " + String.join("; ", applied.errors));
                return;
            }
            container = applied.container;
            if (applied.profile != null) profile = applied.profile;
        }
        else {
            container = WeHavePhonesContainers.resolve(appContext);
        }
        if (container == null) {
            fail(appContext.getString(R.string.wehavephones_stage_setup_required));
            return;
        }
        if (!WeHavePhonesPaths.isBnetInstalled(container)) {
            fail(appContext.getString(R.string.wehavephones_launch_no_bnet));
            return;
        }

        WowProduct installed = WowInstallationScanner.selected(appContext, container);
        if (installed == null) {
            fail(appContext.getString(R.string.wehavephones_launch_no_product));
            return;
        }
        product = plan.resolve(installed.directoryName);
        WowFirstBoot.prepare(installed.directory, WeHavePhonesConfig.getGameRegion(appContext),
                installed.getFlavor() == WowFlavor.RETAIL,
                WeHavePhonesConfig.getGameScreenSize(appContext), WeHavePhonesConfig.isGameRegionOverride(appContext),
                DeviceProfileSelector.wowApiArg(appContext),
                installed.getFlavor().family == WowFlavor.Family.FOREVER);
        // The scanned exe (native ARM64 first) wins; the table's exe is the fallback.
        String exe = installed.getExecutable();
        if (exe.isEmpty()) exe = product.exe;
        product = new WeHavePhonesLaunchPlan.Product(product.dir, product.uid, product.launchUid, product.execCodes, exe, product.confidence);
        put("product", product.dir);
        put("uid", product.uid);
        put("confidence", product.confidence);
        put("warm", warm);
        put("minimized", plan.options.minimized);
        put("coldExec", plan.options.coldExec);
        event("product " + product.dir + " uid=" + product.uid + " exe=" + product.exe
                + " execCodes=" + product.execCodes + " warm=" + warm);

        if (ProcessScanner.findWowProcess() != null) {
            bringSessionToFront();
            put("result", "already-running");
            stage(Stage.RUNNING, appContext.getString(R.string.wehavephones_launch_already_running));
            return;
        }

        File logsDir = WeHavePhonesPaths.bnetLogsDir(container);
        boolean launcherRunning = ProcessScanner.isLauncherRunning();
        if (!warm || !launcherRunning) logsNewerThan = newestLogName(logsDir);

        prepareBnetConfig(launcherRunning);
        // A cold --exec could start a streaming install before the update gate.
        plan.options.coldExec = false;
        String startBat = WeHavePhonesLaunchPlan.appendBnetFlags(profiles.buildBnetBat(profile),
                WeHavePhonesLaunchPlan.startFlags(plan.options, product));
        if (!FileUtils.write(WeHavePhonesPaths.startBat(container), startBat.getBytes(StandardCharsets.UTF_8))) {
            fail(appContext.getString(R.string.wehavephones_launch_write_failed, WeHavePhonesLaunchPlan.START_BAT_NAME));
            return;
        }

        if (!warm) {
            File shortcut = WeHavePhonesShortcuts.ensureShortcut(container, WeHavePhonesLaunchPlan.START_SHORTCUT_NAME, WeHavePhonesLaunchPlan.START_BAT_GUEST);
            if (shortcut == null || !launchOnMain(shortcut)) {
                fail(appContext.getString(R.string.wehavephones_stage_launch_failed));
                return;
            }
            event("session started with " + WeHavePhonesLaunchPlan.START_BAT_GUEST);
        }
        WinHandler winHandler = waitForWinHandler(plan.timeouts.sessionStartSeconds * 1000L);
        if (winHandler == null) {
            fail(appContext.getString(R.string.wehavephones_launch_session_timeout, plan.timeouts.sessionStartSeconds));
            return;
        }
        if (warm && !launcherRunning) {
            if (!execInSession(winHandler, WeHavePhonesLaunchPlan.START_BAT_GUEST)) {
                fail(appContext.getString(R.string.wehavephones_stage_launch_failed));
                return;
            }
            event("Battle.net started inside the live session");
        }

        LinkedHashSet<String> tried = new LinkedHashSet<>();
        for (int start = 0; ; start++) {
            if (!waitUntilReady(logsDir)) return;
            if (confirmedLaunch("pre-ladder")) return;

            if (plan.options.coldExec) {
                event("cold exec: waiting for the client to run its startup --exec");
                if (waitForSpawn(plan.timeouts.spawnSeconds * 1000L, tail != null ? tail.log.getLaunches().size() : 0)) {
                    recordAttempt("cold-exec", "", true, 0);
                    finishLaunched("cold-exec");
                    return;
                }
            }

            ArrayList<WeHavePhonesLaunchPlan.Attempt> ladder = plan.ladder(product);
            event("ladder: " + describe(ladder));
            boolean selectedAtStart = (start > 0 || !(warm && launcherRunning)) && plan.options.coldSelect;
            if (!selectedAtStart && !ladder.isEmpty() && ladder.get(0).path == WeHavePhonesLaunchPlan.Path.PLAY) {
                // A client that was already running is on whatever page it showed last.
                if (writeLaunchBat(WeHavePhonesLaunchPlan.gameBat(WeHavePhonesPaths.bnetGuestDir(container), product.uid))
                        && execInSession(liveWinHandler(), WeHavePhonesLaunchPlan.LAUNCH_BAT_GUEST)) {
                    event("opened the " + product.uid + " page in the running client");
                    sleep(plan.playSettleMs);
                }
            }
            if (runLadder(ladder, tried) || !running) return;
            if (start >= plan.launcherRestarts || ladder.isEmpty() || ladder.get(0).path != WeHavePhonesLaunchPlan.Path.PLAY) break;
            // Battle.net sometimes hangs before its page renders (spinner, log silent).
            event("Battle.net did not start the game; restarting it (" + (start + 1) + "/" + plan.launcherRestarts + ")");
            put("launcherRestarts", start + 1);
            stage(Stage.STARTING, appContext.getString(R.string.wehavephones_launch_restarting_bnet));
            event("closed " + WeHavePhonesLauncherReaper.closeLaunchersNow() + " launcher process(es)");
            sleep(3000);
            logsNewerThan = newestLogName(logsDir);
            tail = null;
            if (!execInSession(liveWinHandler(), WeHavePhonesLaunchPlan.START_BAT_GUEST)) break;
        }
        bringSessionToFront();
        fail(appContext.getString(R.string.wehavephones_launch_failed, String.join(", ", tried)));
    }

    /** Runs the attempts; true once the game started (or the run ended another way). */
    private boolean runLadder(ArrayList<WeHavePhonesLaunchPlan.Attempt> ladder, Set<String> tried) {
        for (WeHavePhonesLaunchPlan.Attempt attempt : ladder) {
            if (!running) return true;
            tried.add(attempt.describe());
            stage(Stage.LAUNCHING, appContext.getString(R.string.wehavephones_launch_launching, attempt.describe()));
            long attemptStart = SystemClock.elapsedRealtime();
            int launchesBefore = tail != null ? tail.log.getLaunches().size() : 0;
            boolean sent = perform(attempt, liveWinHandler());
            int waitSeconds = attempt.path == WeHavePhonesLaunchPlan.Path.PLAY ? plan.playWaitSeconds : plan.timeouts.spawnSeconds;
            boolean spawned = sent && waitForSpawn(waitSeconds * 1000L, launchesBefore);
            recordAttempt(attempt.path.id(), attempt.code, spawned, SystemClock.elapsedRealtime() - attemptStart);
            if (spawned) {
                finishLaunched(attempt.describe());
                return true;
            }
            event("attempt " + attempt.describe() + (sent ? " did not start the game" : " could not be sent"));
            if (playUnavailable) return true;
        }
        return false;
    }

    private boolean waitUntilReady(File logsDir) {
        long deadline = SystemClock.elapsedRealtime() + plan.timeouts.readySeconds * 1000L;
        long readySince = -1;
        long loggedInSince = -1;
        boolean updating = false;
        boolean needsUser = false;
        while (running) {
            if (confirmedLaunch("while-waiting")) return false;
            BattleNetLauncherLog log = pollLog(logsDir);
            BattleNetLauncherLog.Phase phase = log != null ? log.phase(product.uid) : BattleNetLauncherLog.Phase.STARTING;
            long now = SystemClock.elapsedRealtime();
            if (log != null && log.isLoggedIn() && loggedInSince < 0) {
                loggedInSince = now;
                put("signedInAfterMs", now - startedAt);
                event("signed in (" + log.getLoggedInAt() + ")");
            }
            if (log != null && phase != BattleNetLauncherLog.Phase.READY && !retryFailedUpdate(log)) return false;
            switch (phase) {
                case STARTING:
                    stage(Stage.STARTING, appContext.getString(R.string.wehavephones_launch_starting));
                    break;
                case SIGNING_IN: {
                    // Battle.net signs in with its own saved login; a login form means the player signs in.
                    if (!needsUser && log != null && log.loginForm() != BattleNetLauncherLog.LoginForm.NONE) {
                        needsUser = true;
                        event("Battle.net shows its login form; the player signs in");
                        bringSessionToFront();
                    }
                    stage(needsUser ? Stage.NEEDS_USER : Stage.SIGNING_IN, appContext.getString(needsUser
                            ? R.string.wehavephones_launch_sign_in_yourself : R.string.wehavephones_launch_signing_in));
                    break;
                }
                case CHECKING_UPDATES:
                    stage(Stage.CHECKING_UPDATES, appContext.getString(R.string.wehavephones_launch_checking));
                    readySince = -1;
                    break;
                case UPDATING: {
                    if (!updating) {
                        updating = true;
                        deadline = now + plan.timeouts.updateSeconds * 1000L;
                        put("updateSeen", true);
                        event("update in progress: currentOperation=" + log.currentOperation(product.uid)
                                + " playable=" + log.isPlayable(product.uid));
                    }
                    double progress = log.progress(product.uid);
                    if (progress >= 0 && listener != null) mainHandler.post(() -> { if (generation == GENERATION.get()) listener.onProgress(progress); });
                    stage(Stage.UPDATING, progress >= 0
                            ? appContext.getString(R.string.wehavephones_launch_updating_progress, (int)Math.round(progress * 100))
                            : appContext.getString(R.string.wehavephones_launch_updating));
                    readySince = -1;
                    break;
                }
                case READY:
                    BattleNetProductDb database = BattleNetProductDb.read(WeHavePhonesPaths.bnetProductDb(container));
                    if (database == null || !database.isDownloadComplete("", product.uid)) {
                        readySince = -1;
                        stage(Stage.UPDATING, appContext.getString(R.string.wehavephones_launch_updating));
                        if (!updating) {
                            updating = true;
                            deadline = now + plan.timeouts.updateSeconds * 1000L;
                            event("waiting for Agent update_complete; playable alone is insufficient");
                        }
                        break;
                    }
                    if (readySince < 0) {
                        readySince = now;
                        stage(Stage.CHECKING_UPDATES, appContext.getString(R.string.wehavephones_launch_checking));
                    }
                    else if (now - readySince >= plan.timeouts.settleSeconds * 1000L) {
                        put("readyAfterMs", now - startedAt);
                        put("updateGate", "UpToDate and Agent update_complete");
                        event("ready: " + product.uid + " UpToDate playable=1 (log " + tail.file.getName() + ")");
                        return true;
                    }
                    break;
            }
            if (now > deadline) {
                int seconds = updating ? plan.timeouts.updateSeconds : plan.timeouts.readySeconds;
                bringSessionToFront();
                fail(appContext.getString(updating
                        ? R.string.wehavephones_launch_update_timeout
                        : R.string.wehavephones_launch_ready_timeout, seconds));
                return false;
            }
            if (liveSession() == null && now - startedAt > 10_000L) {
                fail(appContext.getString(R.string.wehavephones_launch_session_ended));
                return false;
            }
            sleep(POLL_MS);
        }
        return false;
    }

    /**
     * Agent gives up on some updates at once ("Failed to initialize the tact
     * instance", error 5016) and Battle.net leaves them failed, so the update
     * stage would otherwise wait out {@code updateSeconds}. Clicks Battle.net's
     * main button, which reads Update then, a few times; stops with
     * instructions when the failures continue or a click starts nothing.
     * False when the launch ended.
     */
    private boolean retryFailedUpdate(BattleNetLauncherLog log) {
        if (log != failuresLog) {
            failuresLog = log;
            failuresHandled = 0;
            startsBeforeRetry = -1;
        }
        int failures = log.updateFailures(product.uid);
        if (failures <= failuresHandled) {
            if (startsBeforeRetry < 0) return true;
            if (log.updateStarts(product.uid) > startsBeforeRetry) {
                event("Battle.net started the update again");
                startsBeforeRetry = -1;
                return true;
            }
            if (SystemClock.elapsedRealtime() - retryClickedAt < plan.updateRetryAckSeconds * 1000L) return true;
            event("Update click started nothing within " + plan.updateRetryAckSeconds + " s");
            return failUpdate(log);
        }
        failuresHandled = failures;
        startsBeforeRetry = -1;
        put("updateFailures", failures);
        if (updateRetries >= plan.updateRetries) return failUpdate(log);
        updateRetries++;
        put("updateRetries", updateRetries);
        stage(Stage.UPDATING, appContext.getString(R.string.wehavephones_launch_update_retrying));
        sleep(plan.updateRetryDelayMs);
        if (!running) return false;
        tail.poll();
        event("Battle.net could not update " + product.uid + " (Agent error " + errorCode(log)
                + "); clicking Update (" + updateRetries + "/" + plan.updateRetries + ")");
        int startsBefore = log.updateStarts(product.uid);
        if (!clickMainButton("Update", false)) {
            if (!running || playUnavailable) return false;
            return failUpdate(log);
        }
        startsBeforeRetry = startsBefore;
        retryClickedAt = SystemClock.elapsedRealtime();
        return true;
    }

    private boolean failUpdate(BattleNetLauncherLog log) {
        String code = errorCode(log);
        put("updateError", code);
        event("giving up on the " + product.uid + " update (Agent error " + code + ")");
        bringSessionToFront();
        fail(appContext.getString(R.string.wehavephones_launch_update_failed, code));
        return false;
    }

    private String errorCode(BattleNetLauncherLog log) {
        String code = log.agentError(product.uid);
        return code.isEmpty() ? "?" : code;
    }

    /** Parse new lines of the main-client log, switching to a newer main log when one appears. */
    private BattleNetLauncherLog pollLog(File logsDir) {
        File main = BattleNetLauncherLog.findMainLog(logsDir, logsNewerThan);
        if (main == null) return tail != null ? tail.log : null;
        if (tail == null || !tail.file.equals(main)) {
            if (tail != null && !tail.log.isServerLog()) {
                event("switching launcher log " + tail.file.getName() + " -> " + main.getName());
            }
            else if (tail != null) {
                // A newer server log means the client restarted; follow it.
                event("launcher restarted; following " + main.getName());
            }
            tail = new BattleNetLauncherLog.Tail(main);
            put("launcherLog", main.getName());
        }
        tail.poll();
        return tail.log;
    }

    private boolean perform(WeHavePhonesLaunchPlan.Attempt attempt, WinHandler winHandler) {
        switch (attempt.path) {
            case EXEC:
                return writeLaunchBat(WeHavePhonesLaunchPlan.execBat(WeHavePhonesPaths.bnetGuestDir(container), attempt.code))
                        && execInSession(winHandler, WeHavePhonesLaunchPlan.LAUNCH_BAT_GUEST);
            case GAME:
                return writeLaunchBat(WeHavePhonesLaunchPlan.gameBat(WeHavePhonesPaths.bnetGuestDir(container), attempt.code))
                        && execInSession(winHandler, WeHavePhonesLaunchPlan.LAUNCH_BAT_GUEST);
            case PLAY:
                return clickMainButton("Play", true);
            case DIRECT:
                return writeLaunchBat(WeHavePhonesLaunchPlan.directBat(WeHavePhonesPaths.wowGuestRoot(container), product, product.exe,
                        DeviceProfileSelector.wowApiArg(appContext)))
                        && execInSession(winHandler, WeHavePhonesLaunchPlan.LAUNCH_BAT_GUEST);
        }
        return false;
    }

    private boolean writeLaunchBat(String content) {
        boolean written = FileUtils.write(WeHavePhonesPaths.launchBat(container), content.getBytes(StandardCharsets.UTF_8));
        if (!written) event("could not write " + WeHavePhonesLaunchPlan.LAUNCH_BAT_NAME);
        return written;
    }

    private boolean execInSession(WinHandler winHandler, String batGuestPath) {
        if (winHandler == null) {
            event("no WinHandler for the live session");
            return false;
        }
        String command = WeHavePhonesLaunchPlan.winHandlerCommand(batGuestPath);
        if (!WeHavePhonesLaunchPlan.fitsWinHandler(command)) {
            event("command does not fit a WinHandler packet: " + command);
            return false;
        }
        winHandler.exec(command);
        event("WinHandler.exec " + command + " (" + WeHavePhonesLaunchPlan.packetBytes(command) + " bytes)");
        return true;
    }

    /**
     * Click Play in Battle.net's main window (the largest mapped Battle.net
     * window; the sign-in window is narrower), at {@code playButton} from
     * its bottom-left corner. Input only reaches Wine while the session is in
     * front, so it is brought there first. Escape dismisses Battle.net's
     * in-window What's New dialog before Play; it has no separate X window.
     * The same button reads Update after a failed update ({@code label} is
     * for the log). {@code probe} ends the launch when Play is greyed out.
     */
    private boolean clickMainButton(String label, boolean probe) {
        if (!running) return false;
        if (ProcessScanner.findWowProcess() != null) return true;
        XServerDisplayActivity session = liveSession();
        if (session == null || session.getXServer() == null) return false;
        if (!session.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) bringSessionToFront();
        long deadline = SystemClock.elapsedRealtime() + 10_000L;
        while (!session.getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.RESUMED)) {
            if (SystemClock.elapsedRealtime() > deadline) {
                event("the session did not come to the front; " + label + " not clicked");
                return false;
            }
            sleep(250);
        }
        sleep(500);
        final XServer xServer = session.getXServer();
        final WinHandler winHandler = session.getWinHandler();
        final int[] target = new int[2];
        final String[] found = {null};
        SessionInput.runOnMainAndWait(() -> {
            try (XLock lock = xServer.lock(XServer.Lockable.WINDOW_MANAGER, XServer.Lockable.INPUT_DEVICE)) {
                Window main = null;
                ArrayList<Window> mapped = new ArrayList<>();
                SessionInput.collectMapped(xServer.windowManager.rootWindow, mapped, 0, 3);
                for (Window window : mapped) {
                    if (!window.getClassName().toLowerCase(Locale.ROOT).contains("battle.net")) continue;
                    if (main == null || window.getWidth() * window.getHeight() > main.getWidth() * main.getHeight()) main = window;
                }
                if (main == null) return;
                target[0] = main.getRootX() + plan.playFromLeft;
                target[1] = main.getRootY() + main.getHeight() - plan.playFromBottom;
                if (winHandler != null) SessionInput.focus(xServer, winHandler, main);
                found[0] = main.getWidth() + "x" + main.getHeight() + " at " + main.getRootX() + "," + main.getRootY();
            }
        });
        if (found[0] == null) {
            event("no Battle.net window among the mapped X windows; " + label + " not clicked");
            return false;
        }
        sleep(300);
        if (!running) return false;
        if (ProcessScanner.findWowProcess() != null) return true;
        // Only reached after sign-in and the update gate. On the S23 the
        // patch-notes modal swallowed Play clicks, even when its content failed
        // to render. Escape closes it and is harmless on the normal game page.
        // Repeat on retries: the modal may render after the first Play attempt.
        SessionInput.key(xServer, XKeycode.KEY_ESC);
        event("sent Escape to Battle.net before " + label + " (dismiss What's New)");
        sleep(500);
        if (!running) return false;
        if (ProcessScanner.findWowProcess() != null) return true;
        if (probe && probePlayButton(xServer, target[0], target[1])) {
            // The Play button is greyed out (for example a pre-installed
            // pre-release like the Forever Stress Test): clicking it does
            // nothing, so the normal retries and Battle.net restarts would
            // only delay the same outcome.
            playUnavailable = true;
            bringSessionToFront();
            fail(appContext.getString(R.string.wehavephones_launch_play_unavailable));
            return false;
        }
        if (!running) return false;
        SessionInput.click(xServer, target[0], target[1]);
        event("clicked " + label + " at X " + target[0] + "," + target[1] + " (Battle.net window " + found[0] + ")");
        return true;
    }

    /**
     * True when the Play button reads as greyed out twice about a second
     * apart. Battle.net paints the button in the game's accent colour and
     * dims it when the game cannot launch; the settings gear keeps the accent
     * (see {@link BattleNetPlayButton}). The second reading guards against a
     * page that is still painting, and an unreadable button (page not
     * rendered, no gear button) never counts as greyed out.
     */
    private boolean probePlayButton(XServer xServer, int playX, int playY) {
        for (int attempt = 0; attempt < 2; attempt++) {
            int playFill = SessionInput.averagePixel(xServer, playX + PLAY_FILL_OFFSET, playY, PROBE_RADIUS);
            int gearFill = SessionInput.averagePixel(xServer, playX + GEAR_FILL_OFFSET, playY, PROBE_RADIUS);
            BattleNetPlayButton.State state = BattleNetPlayButton.classify(playFill, gearFill);
            put("playFill", BattleNetPlayButton.hex(playFill));
            put("gearFill", BattleNetPlayButton.hex(gearFill));
            put("playButton", state.name().toLowerCase(Locale.ROOT));
            event("Play button probe: " + state + " (fill " + BattleNetPlayButton.hex(playFill)
                    + ", gear " + BattleNetPlayButton.hex(gearFill) + ")");
            if (state != BattleNetPlayButton.State.DISABLED) return false;
            if (!running) return false;
            if (attempt == 0) sleep(800);
        }
        return true;
    }

    private boolean waitForSpawn(long timeoutMs, int launchesBefore) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        File logsDir = WeHavePhonesPaths.bnetLogsDir(container);
        while (running && SystemClock.elapsedRealtime() < deadline) {
            BattleNetLauncherLog log = pollLog(logsDir);
            if (log != null && log.getLaunches().size() > launchesBefore && log.lastLaunch(product.launchUid) != null) return true;
            if (ProcessScanner.findWowProcess() != null) return true;
            sleep(POLL_MS);
        }
        return false;
    }

    /** A game appeared on its own (user pressed Play, or a cold --exec). */
    private boolean confirmedLaunch(String when) {
        if (ProcessScanner.findWowProcess() == null) return false;
        event("game process found " + when);
        recordAttempt("observed", "", true, 0);
        finishLaunched("observed");
        return true;
    }

    private void finishLaunched(String path) {
        gameSeenAt = SystemClock.elapsedRealtime();
        put("pathUsed", path);
        put("spawnAfterMs", elapsed());
        ProcessScanner.Entry wow = waitForWowProcess(10_000L);
        BattleNetLauncherLog.Launch launch = tail != null ? tail.log.lastLaunch(product.launchUid) : null;
        if (launch != null) {
            put("launcherPid", launch.pid);
            put("launchedExe", launch.executable);
            put("launchedArgs", launch.args);
            event("launcher log: Launched " + launch.executable + " with args: " + launch.args + ", pid: " + launch.pid);
        }
        if (wow == null) {
            fail(appContext.getString(R.string.wehavephones_launch_exited));
            return;
        }
        put("wowPid", wow.pid);
        event("WoW process " + wow.pid + " (" + wow.name + ") via " + path);
        bringSessionToFront();
        stage(Stage.RUNNING, appContext.getString(R.string.wehavephones_launch_running, path));
        if (reapLauncher(path)) put("result", "running");
    }

    /**
     * Wait for Battle.net to close itself (it does once it has launched the
     * game; see {@code GameLaunchWindowBehavior}); one still running after
     * {@code launcherExitSeconds} is closed. Agent.exe is left to the game.
     * Returns false when the game exited first.
     */
    private boolean reapLauncher(String path) {
        long before = WeHavePhonesMemoryDiagnostics.sample().memAvailableKb;
        long exitDeadline = gameSeenAt + plan.timeouts.launcherExitSeconds * 1000L;
        File logsDir = WeHavePhonesPaths.bnetLogsDir(container);
        boolean gameRunningLogged = false;
        boolean exited = false;
        while (running && SystemClock.elapsedRealtime() < exitDeadline) {
            if (ProcessScanner.findWowProcess() == null) break;
            BattleNetLauncherLog log = pollLog(logsDir);
            if (!gameRunningLogged && log != null && log.isGameRunning(product.launchUid)) {
                gameRunningLogged = true;
                event("launcher reports: Game is running: " + product.launchUid);
            }
            if (!ProcessScanner.isBattleNetRunning()) {
                exited = true;
                break;
            }
            sleep(POLL_MS);
        }
        if (ProcessScanner.findWowProcess() == null) {
            put("reap", "skipped (game exited)");
            fail(appContext.getString(R.string.wehavephones_launch_exited));
            return false;
        }
        if (!running) return true;
        int count = 0;
        if (exited) {
            put("launcherExitedAfterMs", SystemClock.elapsedRealtime() - gameSeenAt);
            event("Battle.net closed itself " + (SystemClock.elapsedRealtime() - gameSeenAt) + " ms after the game appeared");
        }
        else {
            event("Battle.net still running " + plan.timeouts.launcherExitSeconds + " s after the game appeared; closing it");
            count = WeHavePhonesLauncherReaper.reapNow();
        }
        sleep(5000);
        long after = WeHavePhonesMemoryDiagnostics.sample().memAvailableKb;
        put("reap", exited ? "launcher exited on its own" : "closed after timeout");
        put("reapedProcesses", count);
        put("memAvailableBeforeReapMb", before / 1024);
        put("memAvailableAfterReapMb", after / 1024);
        put("reapAfterMs", elapsed());
        event((exited ? "launcher exited" : "closed " + count + " launcher process(es)")
                + "; MemAvailable " + before / 1024 + " -> " + after / 1024 + " MB");
        stage(Stage.RUNNING, exited
                ? appContext.getString(R.string.wehavephones_launch_running_launcher_exited, path)
                : appContext.getString(R.string.wehavephones_launch_running_reaped, path, count));
        return true;
    }

    // ---- session helpers ----------------------------------------------------------------

    private static XServerDisplayActivity liveSession() {
        XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
        return session != null && session.isSessionActive() ? session : null;
    }

    private static WinHandler liveWinHandler() {
        XServerDisplayActivity session = liveSession();
        return session != null ? session.getWinHandler() : null;
    }

    private WinHandler waitForWinHandler(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (running && SystemClock.elapsedRealtime() < deadline) {
            WinHandler winHandler = liveWinHandler();
            if (winHandler != null && winHandler.isReady()) return winHandler;
            sleep(500);
        }
        return null;
    }

    private ProcessScanner.Entry waitForWowProcess(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        do {
            ProcessScanner.Entry entry = ProcessScanner.findWowProcess();
            if (entry != null) return entry;
            sleep(500);
        }
        while (running && SystemClock.elapsedRealtime() < deadline);
        return null;
    }

    private void bringSessionToFront() {
        final XServerDisplayActivity session = liveSession();
        if (session == null || container == null) return;
        mainHandler.post(() -> {
            try {
                appContext.startActivity(WeHavePhonesSessionLauncher.buildIntent(appContext, container, null));
            }
            catch (Exception e) {
                Log.w(TAG, "could not bring the session to front", e);
            }
        });
    }

    private boolean launchOnMain(final File shortcut) {
        final boolean[] launched = {false};
        runOnMainAndWait(() -> launched[0] = WeHavePhonesSessionLauncher.launch(appContext, container, shortcut));
        return launched[0];
    }

    private void runOnMainAndWait(final Runnable runnable) {
        final CountDownLatch latch = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                runnable.run();
            }
            finally {
                latch.countDown();
            }
        });
        try {
            latch.await(10, TimeUnit.SECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void prepareBnetConfig(boolean launcherRunning) {
        File roaming = WeHavePhonesPaths.bnetRoamingDir(container);
        File backupDir = WeHavePhonesPaths.wehavephonesDir(appContext);
        if (plan.options.minimized) {
            event("minimized: " + WeHavePhonesBnetConfig.ensureMinimized(roaming, backupDir));
        }
        else if (WeHavePhonesBnetConfig.restore(roaming, backupDir)) {
            event("minimized off: restored the original Battle.net.config");
        }
        event("game launch: " + WeHavePhonesBnetConfig.applyExitOnGameLaunch(roaming, backupDir)
                + (launcherRunning ? " (the running Battle.net keeps its loaded setting)" : ""));
    }

    private WeHavePhonesLaunchPlan loadPlan() {
        try (InputStream input = appContext.getAssets().open(WeHavePhonesLaunchPlan.ASSET_PATH)) {
            String bundled = new String(readAll(input), StandardCharsets.UTF_8);
            File overrides = WeHavePhonesPaths.launchOverrides(appContext);
            String overrideJson = overrides.isFile() ? FileUtils.readString(overrides) : null;
            WeHavePhonesLaunchPlan loaded = WeHavePhonesLaunchPlan.parse(bundled, overrideJson);
            if (overrideJson != null) {
                event("launch overrides applied from " + overrides.getName());
                put("overrides", true);
            }
            return loaded;
        }
        catch (Exception e) {
            event("launch plan unavailable: " + e);
            return null;
        }
    }

    private static byte[] readAll(InputStream input) throws java.io.IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) > 0) output.write(buffer, 0, count);
        return output.toByteArray();
    }

    private static String newestLogName(File logsDir) {
        ArrayList<File> logs = BattleNetLauncherLog.listLogs(logsDir);
        return logs.isEmpty() ? "" : logs.get(0).getName();
    }

    // ---- reporting ----------------------------------------------------------------------

    private void stage(Stage stage, String message) {
        if (stage == lastStage && message.equals(lastMessage)) return;
        boolean stageChanged = stage != lastStage;
        lastStage = stage;
        lastMessage = message;
        put("lastStage", stage.name());
        event("stage " + stage + ": " + message);
        if (listener != null) mainHandler.post(() -> { if (generation == GENERATION.get()) listener.onStage(stage, message, false); });
        if (stageChanged) writeRecord();
    }

    private void fail(String message) {
        lastStage = Stage.FAILED;
        lastMessage = message;
        put("result", "failed");
        put("error", message);
        put("lastStage", Stage.FAILED.name());
        event("FAILED: " + message);
        if (listener != null) mainHandler.post(() -> { if (generation == GENERATION.get()) listener.onStage(Stage.FAILED, message, true); });
    }

    private void recordAttempt(String path, String code, boolean spawned, long millis) {
        try {
            JSONObject attempt = new JSONObject();
            attempt.put("path", path);
            if (!code.isEmpty()) attempt.put("code", code);
            attempt.put("spawned", spawned);
            attempt.put("ms", millis);
            attemptsJson.put(attempt);
        }
        catch (Exception ignored) {}
    }

    private void put(String key, Object value) {
        try {
            record.put(key, value);
        }
        catch (Exception ignored) {}
    }

    private long elapsed() {
        return SystemClock.elapsedRealtime() - startedAt;
    }

    private void event(String message) {
        String line = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date())
                + " +" + elapsed() + "ms " + message;
        Log.i(TAG, message);
        File log = WeHavePhonesPaths.launchLog(appContext);
        File dir = log.getParentFile();
        if (dir != null && !dir.isDirectory()) dir.mkdirs();
        if (log.length() > MAX_LOG_BYTES) {
            File old = new File(log.getPath() + ".1");
            old.delete();
            log.renameTo(old);
        }
        try (FileOutputStream output = new FileOutputStream(log, true)) {
            output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception ignored) {}
    }

    private void writeRecord() {
        try {
            File file = WeHavePhonesPaths.launchRecord(appContext);
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory()) dir.mkdirs();
            FileUtils.write(file, record.toString(2).getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception ignored) {}
    }

    private static String describe(ArrayList<WeHavePhonesLaunchPlan.Attempt> ladder) {
        ArrayList<String> names = new ArrayList<>();
        for (WeHavePhonesLaunchPlan.Attempt attempt : ladder) names.add(attempt.describe());
        return String.join(" -> ", names);
    }

    /** Human-readable summary of the last run for Diagnostics. */
    public static String lastRunSummary(Context context) {
        File file = WeHavePhonesPaths.launchRecord(context);
        if (!file.isFile()) return "No one-button launch recorded yet\n";
        String json = FileUtils.readString(file);
        return json != null ? json + "\n" : "Unreadable launch record\n";
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
