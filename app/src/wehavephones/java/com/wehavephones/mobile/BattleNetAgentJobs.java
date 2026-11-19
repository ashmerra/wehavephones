package com.wehavephones.mobile;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.ViewGroup;

import com.wehavephones.R;
import com.wehavephones.XServerDisplayActivity;
import com.wehavephones.container.Container;
import com.wehavephones.core.FileUtils;
import com.wehavephones.winhandler.WinHandler;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Installs, updates, repairs and removes WoW games through Blizzard's Agent,
 * without the Battle.net window. The Agent ({@code Agent.exe
 * --internalclienttools}) and {@code agentpipe.exe} run in a game session,
 * and {@link AgentJobOverlayView} covers that session with the game, the
 * stage and the download progress until the job ends. The idea and the
 * request sequence come from DroidDeck's Battle.net store, which drives the
 * Agent the same way. One job runs at a time.
 */
public final class BattleNetAgentJobs {
    public static final String TAG = "WeHavePhonesAgent";
    /** Intent extra: the session exists only to run the Agent. */
    public static final String EXTRA_AGENT_SESSION = "wehavephones_agent_session";
    public static final String AGENT_BAT_NAME = "wehavephones-agent.bat";
    public static final String AGENT_BAT_GUEST = "C:\\" + AGENT_BAT_NAME;
    static final String AGENT_SHORTCUT_NAME = "WeHavePhones Agent";
    private static final String PIPE_ASSET = "wehavephones/agentpipe.exe";
    private static final String PIPE_NAME = BattleNetAgentApi.PIPE_NAME;

    private static final long POLL_MS = 1_000L;
    private static final long SESSION_WAIT_MS = 90_000L;
    private static final long AGENT_WAIT_MS = 120_000L;
    private static final long NOT_STARTED_MS = 180_000L;
    private static final long STALL_MS = 10 * 60_000L;
    private static final long UNINSTALL_WAIT_MS = 60 * 60_000L;
    private static final long UNINSTALL_RESEND_MS = 15_000L;
    private static final long CLOSE_AFTER_DONE_MS = 4_000L;
    private static final int RETRIES = 3;
    private static final long ETA_MIN_RATE = 200_000L;
    private static final int PIPE_IDLE_SECONDS = 900;

    public enum Action { INSTALL, UPDATE, REPAIR, UNINSTALL }

    public enum Phase { STARTING, CONNECTING, QUEUED, PREPARING, DOWNLOADING, APPLYING, REMOVING, DONE, FAILED }

    /** One game and what to do with it. */
    public static final class Job {
        public final Action action;
        public final String uid;
        public final String productCode;
        public final String title;
        public final String letter;
        public final int accent;

        public Job(Action action, String uid, String productCode, String title, String letter, int accent) {
            this.action = action;
            this.uid = uid;
            this.productCode = productCode;
            this.title = title;
            this.letter = letter;
            this.accent = accent;
        }
    }

    /** What the overlay and the screens show. Immutable snapshot. */
    public static final class Status {
        public final Job job;
        public final Phase phase;
        public final String headline;
        public final String detail;
        /** 0..1, or -1 while the stage has no measurable progress. */
        public final double fraction;
        public final long startedAt;

        Status(Job job, Phase phase, String headline, String detail, double fraction, long startedAt) {
            this.job = job;
            this.phase = phase;
            this.headline = headline;
            this.detail = detail;
            this.fraction = fraction;
            this.startedAt = startedAt;
        }

        public boolean finished() {
            return phase == Phase.DONE || phase == Phase.FAILED;
        }
    }

    public interface Listener {
        void onStatus(Status status);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final CopyOnWriteArrayList<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile BattleNetAgentJobs active;
    private static volatile Status last;
    private static WeakReference<AgentJobOverlayView> overlay = new WeakReference<>(null);

    private final Context appContext;
    private final Job job;
    private final long startedAt = SystemClock.elapsedRealtime();
    private volatile boolean running = true;
    private volatile boolean cancelled;
    private boolean ownSession;
    private Container container;
    private BattleNetAgentClient client;

    private BattleNetAgentJobs(Context context, Job job) {
        this.appContext = context.getApplicationContext();
        this.job = job;
    }

    // ---- public API ---------------------------------------------------------------------

    public static boolean isRunning() {
        BattleNetAgentJobs current = active;
        return current != null && current.running;
    }

    /** The status of the running job, or of the last one until the app restarts. */
    public static Status current() {
        return last;
    }

    /** Starts a job unless one is running, Play is launching or a game is open. Returns an error message or null. */
    public static String start(Context context, Job job) {
        if (isRunning()) return context.getString(R.string.wehavephones_agent_busy);
        if (OneButtonLauncher.isRunning() || RuntimeStageController.isRunning()) return context.getString(R.string.wehavephones_launch_in_progress);
        if (WowImportService.isRunning()) return context.getString(R.string.wehavephones_import_wait);
        if (ProcessScanner.findWowProcess() != null) return context.getString(R.string.wehavephones_agent_close_game);
        if (job.uid == null || job.uid.isEmpty()) return context.getString(R.string.wehavephones_install_unknown_id, job.title);
        BattleNetAgentJobs runner = new BattleNetAgentJobs(context, job);
        active = runner;
        runner.publish(Phase.STARTING, context.getString(R.string.wehavephones_agent_starting), "", -1);
        Thread thread = new Thread(runner::run, "wehavephones-agent-job");
        thread.setDaemon(true);
        thread.start();
        return null;
    }

    /** The overlay's Cancel: stop watching, close the Agent and end a session that was only for it. */
    public static void cancel() {
        BattleNetAgentJobs current = active;
        if (current != null) current.cancelled = true;
    }

    public static void addListener(Listener listener) {
        LISTENERS.addIfAbsent(listener);
    }

    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /** The session was started for an Agent job (it keeps running in the background, without a floating window). */
    public static boolean isAgentSession(XServerDisplayActivity activity) {
        Intent intent = activity != null ? activity.getIntent() : null;
        return intent != null && intent.getBooleanExtra(EXTRA_AGENT_SESSION, false);
    }

    /** True while the overlay should cover the session start instead of the "Starting up" dialog. */
    public static boolean coversSessionStart() {
        return isRunning();
    }

    // ---- session hooks ------------------------------------------------------------------

    public static void attach(XServerDisplayActivity activity, ViewGroup root) {
        if (!isRunning() && (last == null || !last.finished() || !isAgentSession(activity))) return;
        AgentJobOverlayView existing = overlay.get();
        if (existing != null && existing.getParent() == root) return;
        if (existing != null) existing.detach();
        AgentJobOverlayView view = new AgentJobOverlayView(activity);
        root.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        overlay = new WeakReference<>(view);
        Status status = last;
        if (status != null) view.render(status);
    }

    public static void onSessionEnded() {
        cancel();
        AgentJobOverlayView view = overlay.get();
        overlay = new WeakReference<>(null);
        MAIN.post(() -> { if (view != null) view.detach(); });
    }

    /** Back to menu (overlay button): ends the session like the side menu's Exit. */
    static void leave(XServerDisplayActivity activity) {
        cancel();
        AgentJobOverlayView view = overlay.get();
        if (view != null) view.detach();
        overlay = new WeakReference<>(null);
        if (activity != null) activity.exitSession();
    }

    /** Show screen: the overlay steps aside (the Agent keeps working). */
    static void reveal() {
        AgentJobOverlayView view = overlay.get();
        if (view != null) view.detach();
        overlay = new WeakReference<>(null);
    }

    // ---- the job ------------------------------------------------------------------------

    private void run() {
        String failure = null;
        try {
            failure = runJob();
        }
        catch (Throwable throwable) {
            Log.e(TAG, "agent job failed", throwable);
            failure = appContext.getString(R.string.wehavephones_agent_failed, String.valueOf(throwable.getMessage()));
        }
        finally {
            if (cancelled && failure == null) failure = appContext.getString(R.string.wehavephones_agent_cancelled);
            if (failure != null) publish(Phase.FAILED, failure, appContext.getString(R.string.wehavephones_agent_failed_detail), -1);
            stopAgent();
            running = false;
            finish(failure == null);
        }
    }

    /** Null on success, otherwise the message for the player. */
    private String runJob() throws JSONException {
        container = WeHavePhonesContainers.resolve(appContext);
        if (container == null || !WeHavePhonesPaths.isBnetInstalled(container)) {
            return appContext.getString(R.string.wehavephones_launch_no_bnet);
        }
        String agentExe = agentExeGuest(container);
        if (agentExe == null) return appContext.getString(R.string.wehavephones_agent_missing);

        int pipePort = 47000 + new SecureRandom().nextInt(900);
        String secret = secret();
        File pipe = new File(WeHavePhonesPaths.driveC(container), "windows/" + PIPE_NAME);
        try {
            FileUtils.copy(appContext, PIPE_ASSET, pipe);
        }
        catch (Exception e) {
            Log.w(TAG, "agentpipe.exe not copied", e);
        }
        if (!pipe.isFile()) return appContext.getString(R.string.wehavephones_agent_failed, PIPE_NAME);
        String bat = BattleNetAgentApi.agentBat(agentExe, pipePort, secret, PIPE_IDLE_SECONDS);
        if (!FileUtils.write(new File(WeHavePhonesPaths.driveC(container), AGENT_BAT_NAME), bat.getBytes(StandardCharsets.UTF_8))) {
            return appContext.getString(R.string.wehavephones_launch_write_failed, AGENT_BAT_NAME);
        }

        XServerDisplayActivity session = liveSession();
        if (session != null) {
            // Battle.net's own Agent answers only Battle.net: close both, then start ours.
            int closed = WeHavePhonesLauncherReaper.closeLaunchersNow();
            Log.i(TAG, "closed " + closed + " Battle.net process(es) for the Agent job");
            MAIN.post(() -> attachToLive());
            WinHandler winHandler = session.getWinHandler();
            if (winHandler == null) return appContext.getString(R.string.wehavephones_stage_launch_failed);
            winHandler.exec(WeHavePhonesLaunchPlan.winHandlerCommand(AGENT_BAT_GUEST));
        }
        else {
            WeHavePhonesRuntimeApplier.Result applied = WeHavePhonesRuntimeApplier.apply(appContext, WeHavePhonesRuntimeApplier.Stage.BATTLE_NET);
            if (!applied.success) {
                return appContext.getString(R.string.wehavephones_stage_apply_failed) + ": " + String.join("; ", applied.errors);
            }
            if (applied.container != null) container = applied.container;
            File shortcut = WeHavePhonesShortcuts.ensureShortcut(container, AGENT_SHORTCUT_NAME, AGENT_BAT_GUEST);
            if (shortcut == null || !launchSession(shortcut)) return appContext.getString(R.string.wehavephones_stage_launch_failed);
            ownSession = true;
            if (!waitForSession()) return cancelled ? null : appContext.getString(R.string.wehavephones_launch_session_timeout, SESSION_WAIT_MS / 1000);
        }

        publish(Phase.CONNECTING, appContext.getString(R.string.wehavephones_agent_connecting), "", -1);
        client = new BattleNetAgentClient(pipePort, secret, new File(agentDir(container), BattleNetAgentApi.AGENT_DAT));
        long deadline = SystemClock.elapsedRealtime() + AGENT_WAIT_MS;
        JSONObject hello = null;
        while (!cancelled && SystemClock.elapsedRealtime() < deadline && (hello = client.hello()) == null) sleep(POLL_MS);
        if (cancelled) return null;
        if (hello == null) return appContext.getString(R.string.wehavephones_agent_no_answer);
        Log.i(TAG, "Agent " + hello.optString("version") + " answers; " + job.action + " " + job.uid);

        switch (job.action) {
            case UNINSTALL: return uninstall();
            case REPAIR: return watchedRetries("/repair", BattleNetAgentApi.repairRequest(job.uid), "/repair/" + job.uid);
            case UPDATE: return watchedRetries("/update", BattleNetAgentApi.updateRequest(job.uid), "/update/" + job.uid);
            case INSTALL:
            default: return install();
        }
    }

    private String install() throws JSONException {
        BattleNetAgentApi.Response game = client.get("/game/" + job.uid);
        BattleNetAgentApi.GameState state = BattleNetAgentApi.parseGame(job.uid, game != null && game.ok() ? game.json() : null);
        if (state.installed && state.playable) {
            publish(Phase.DONE, appContext.getString(R.string.wehavephones_agent_already_installed, job.title), "", 1);
            return null;
        }
        if (game != null && game.ok()) {
            // Registered earlier (an interrupted install): continue it.
            return watchedRetries("/update", BattleNetAgentApi.updateRequest(job.uid), "/update/" + job.uid);
        }
        Defaults defaults = defaults();
        String productCode = job.productCode == null || job.productCode.isEmpty() ? job.uid : job.productCode;
        BattleNetAgentApi.Response form = client.post("/install", BattleNetAgentApi.installRequest(job.uid, productCode, defaults.region));
        if (form == null || !form.ok()) return failure(form);
        String locale = defaults.locale;
        JSONObject formJson = form.json();
        if (formJson != null && !formJson.toString().contains("\"" + locale + "\"")) locale = "enUS";
        String dir = BattleNetAgentApi.gameDir(WeHavePhonesPaths.wowGuestRoot(container));
        BattleNetAgentApi.Response sent = client.post("/install/" + job.uid,
                BattleNetAgentApi.installForm(dir, locale, defaults.accountCountry, defaults.geoCountry));
        if (sent == null || !sent.ok()) return failure(sent);
        Log.i(TAG, "install of " + job.uid + " (" + productCode + ") into " + dir + " " + locale + " " + defaults.region);
        String result = watch("/install/" + job.uid);
        if (result == null || cancelled) return result;
        return retry(result, 0, "/update", BattleNetAgentApi.updateRequest(job.uid), "/update/" + job.uid);
    }

    /** POSTs the job, watches it and retries failed attempts (Agent's error 5016 usually clears). */
    private String watchedRetries(String postPath, JSONObject body, String watchPath) {
        BattleNetAgentApi.Response sent = client.post(postPath, body);
        if (sent == null || !sent.ok()) return failure(sent);
        String result = watch(watchPath);
        return result == null || cancelled ? result : retry(result, 0, postPath, body, watchPath);
    }

    private String retry(String result, int attempt, String postPath, JSONObject body, String watchPath) {
        while (result != null && !cancelled && attempt < RETRIES) {
            attempt++;
            Log.i(TAG, "retrying " + job.uid + " (" + attempt + "/" + RETRIES + ") after: " + result);
            publish(Phase.PREPARING, appContext.getString(R.string.wehavephones_agent_retrying), result, -1);
            sleep(3_000L);
            BattleNetAgentApi.Response sent = client.post(postPath, body);
            if (sent == null || !sent.ok()) return failure(sent);
            result = watch(watchPath);
        }
        return result;
    }

    /** Polls a job until it ends. Null when it finished. */
    private String watch(String path) {
        long since = SystemClock.elapsedRealtime();
        long moved = since;
        String signature = "";
        boolean started = false;
        int missing = 0;
        while (!cancelled) {
            BattleNetAgentApi.Response response = client.get(path);
            long now = SystemClock.elapsedRealtime();
            if (response != null && response.status == 404) {
                // The job left the Agent's list: it ended (or never began).
                if (++missing >= 3) return gameReady() ? null : started
                        ? appContext.getString(R.string.wehavephones_agent_stopped) : appContext.getString(R.string.wehavephones_agent_not_started);
            }
            else if (response != null && response.ok()) {
                missing = 0;
                BattleNetAgentApi.Progress p = BattleNetAgentApi.parseProgress(response.json());
                if (p.started()) started = true;
                String now_signature = p.state + "/" + p.phase + "/" + p.downloadCurrent + "/" + p.phaseCurrent + "/" + p.error;
                if (!now_signature.equals(signature)) {
                    signature = now_signature;
                    moved = now;
                    Log.i(TAG, job.uid + " " + p.describe());
                }
                if (p.failed()) {
                    String reason = p.errorMessage.isEmpty() ? "" : " (" + p.errorMessage + ")";
                    return appContext.getString(R.string.wehavephones_agent_error, p.error) + reason;
                }
                if (p.done() && (started || now - since > 15_000L)) {
                    publishProgress(p);
                    return null;
                }
                publishProgress(p);
            }
            else if (response == null && now - moved > AGENT_WAIT_MS) {
                return appContext.getString(R.string.wehavephones_agent_no_answer);
            }
            if (!started && now - since > NOT_STARTED_MS) {
                return gameReady() ? null : appContext.getString(R.string.wehavephones_agent_not_started);
            }
            if (now - moved > STALL_MS) return appContext.getString(R.string.wehavephones_agent_stalled);
            sleep(POLL_MS);
        }
        return null;
    }

    private boolean gameReady() {
        BattleNetAgentApi.Response game = client.get("/game/" + job.uid);
        BattleNetAgentApi.GameState state = BattleNetAgentApi.parseGame(job.uid, game != null && game.ok() ? game.json() : null);
        return state.installed && state.playable;
    }

    private String uninstall() throws JSONException {
        publish(Phase.REMOVING, appContext.getString(R.string.wehavephones_agent_removing, job.title),
                appContext.getString(R.string.wehavephones_agent_removing_detail), -1);
        BattleNetAgentApi.Response sent = client.post("/uninstall", BattleNetAgentApi.uninstallRequest(job.uid, true));
        if (sent == null || !sent.ok()) return failure(sent);
        long deadline = SystemClock.elapsedRealtime() + UNINSTALL_WAIT_MS;
        long sentAt = SystemClock.elapsedRealtime();
        int resent = 0;
        while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
            BattleNetAgentApi.Response game = client.get("/game/" + job.uid);
            if (game != null && (game.status == 404 || game.ok() && !BattleNetAgentApi.parseGame(job.uid, game.json()).installed)) {
                return null;
            }
            BattleNetAgentApi.Response progress = client.get("/uninstall/" + job.uid);
            if (progress != null && progress.status == 404 && SystemClock.elapsedRealtime() - sentAt > UNINSTALL_RESEND_MS) {
                // An operation the Agent resumed (an interrupted Check files) can swallow the request.
                if (++resent > RETRIES) return appContext.getString(R.string.wehavephones_agent_not_started);
                Log.i(TAG, "the Agent has no uninstall of " + job.uid + "; asking again (" + resent + "/" + RETRIES + ")");
                sent = client.post("/uninstall", BattleNetAgentApi.uninstallRequest(job.uid, true));
                if (sent == null || !sent.ok()) return failure(sent);
                sentAt = SystemClock.elapsedRealtime();
            }
            else if (progress != null && progress.ok()) {
                BattleNetAgentApi.Progress p = BattleNetAgentApi.parseProgress(progress.json());
                if (p.failed()) return appContext.getString(R.string.wehavephones_agent_error, p.error);
                double fraction = p.progress > 0 ? p.progress : -1;
                publish(Phase.REMOVING, appContext.getString(R.string.wehavephones_agent_removing, job.title),
                        appContext.getString(R.string.wehavephones_agent_removing_detail), fraction);
            }
            sleep(2_000L);
        }
        return cancelled ? null : appContext.getString(R.string.wehavephones_agent_stalled);
    }

    private String failure(BattleNetAgentApi.Response response) {
        if (response == null) return appContext.getString(R.string.wehavephones_agent_no_answer);
        JSONObject json = response.json();
        int code = json != null ? json.optInt("error", 0) : 0;
        return appContext.getString(R.string.wehavephones_agent_refused, response.status, code);
    }

    // ---- progress -----------------------------------------------------------------------

    private void publishProgress(BattleNetAgentApi.Progress p) {
        switch (p.step()) {
            case QUEUED:
                publish(Phase.QUEUED, appContext.getString(R.string.wehavephones_agent_queued), "", -1);
                break;
            case CHECKING: {
                String detail = appContext.getString(R.string.wehavephones_agent_download_detail,
                        BattleNetAgentApi.formatBytes(p.phaseCurrent), BattleNetAgentApi.formatBytes(p.phaseTotal));
                publish(Phase.DOWNLOADING, appContext.getString(R.string.wehavephones_agent_checking_percent,
                        Math.round(p.fraction() * 100)), detail, p.fraction());
                break;
            }
            case DOWNLOADING: {
                String detail = appContext.getString(R.string.wehavephones_agent_download_detail,
                        BattleNetAgentApi.formatBytes(p.downloadCurrent), BattleNetAgentApi.formatBytes(p.downloadTotal));
                if (p.downloadRate > 0) detail += "  \u00b7  " + BattleNetAgentApi.formatRate(p.downloadRate);
                // The first seconds of a download crawl, and the Agent's estimate then reads hours.
                if (p.etaSeconds > 0 && p.downloadRate >= ETA_MIN_RATE) detail += "  \u00b7  "
                        + appContext.getString(R.string.wehavephones_agent_eta, BattleNetAgentApi.formatEta(p.etaSeconds));
                publish(Phase.DOWNLOADING, appContext.getString(R.string.wehavephones_agent_downloading,
                        Math.round(p.fraction() * 100)), detail, p.fraction());
                break;
            }
            case WRITING:
            case APPLYING:
            case FINISHING: {
                int headline = p.step() == BattleNetAgentApi.Step.WRITING ? R.string.wehavephones_agent_writing
                        : p.step() == BattleNetAgentApi.Step.APPLYING ? R.string.wehavephones_agent_applying
                        : R.string.wehavephones_agent_finishing;
                String detail = appContext.getString(p.step() == BattleNetAgentApi.Step.FINISHING
                        ? R.string.wehavephones_agent_finishing_detail : R.string.wehavephones_agent_applying_detail);
                if (p.etaSeconds > 0) detail += "  \u00b7  " + appContext.getString(R.string.wehavephones_agent_eta,
                        BattleNetAgentApi.formatEta(p.etaSeconds));
                publish(Phase.APPLYING, appContext.getString(headline, Math.round(p.installFraction() * 100)), detail, p.installFraction());
                break;
            }
            case DONE:
                break;
            case PREPARING:
            default:
                publish(Phase.PREPARING, appContext.getString(job.action == Action.REPAIR
                        ? R.string.wehavephones_agent_checking_files : R.string.wehavephones_agent_preparing), "", -1);
        }
    }

    private void publish(Phase phase, String headline, String detail, double fraction) {
        final Status status = new Status(job, phase, headline, detail == null ? "" : detail, fraction, startedAt);
        last = status;
        MAIN.post(() -> {
            AgentJobOverlayView view = overlay.get();
            if (view != null) view.render(status);
            for (Listener listener : LISTENERS) listener.onStatus(status);
        });
    }

    private void finish(boolean success) {
        if (success) {
            int message;
            switch (job.action) {
                case UNINSTALL: message = R.string.wehavephones_agent_done_uninstall; break;
                case UPDATE: message = R.string.wehavephones_agent_done_update; break;
                case REPAIR: message = R.string.wehavephones_agent_done_repair; break;
                case INSTALL:
                default: message = R.string.wehavephones_agent_done_install;
            }
            publish(Phase.DONE, appContext.getString(message, job.title), appContext.getString(ownSession
                    ? R.string.wehavephones_agent_done_detail_menu : R.string.wehavephones_agent_done_detail), 1);
            if (job.action == Action.INSTALL || job.action == Action.UPDATE) {
                WeHavePhonesConfig.setProductDir(appContext, BattleNetGames.SELECTION_PREFIX + job.uid);
            }
        }
        if (active == this) active = null;
        if (ownSession && success && !cancelled) {
            // A session that only ran the Agent goes back to the menu by itself.
            MAIN.postDelayed(() -> {
                if (isRunning()) return;
                XServerDisplayActivity session = liveSession();
                if (session != null && isAgentSession(session)) session.exitSession();
            }, CLOSE_AFTER_DONE_MS);
        }
        else if (ownSession && cancelled) {
            MAIN.post(() -> {
                XServerDisplayActivity session = liveSession();
                if (session != null && isAgentSession(session)) session.exitSession();
            });
        }
    }

    /** Closes the Agent we started (the next Battle.net start brings up its own). */
    private void stopAgent() {
        try {
            int closed = WeHavePhonesLauncherReaper.closeLaunchersNow();
            if (closed > 0) Log.i(TAG, "closed the Agent (" + closed + " process(es))");
        }
        catch (Throwable throwable) {
            Log.w(TAG, "could not close the Agent", throwable);
        }
    }

    // ---- session ------------------------------------------------------------------------

    private void attachToLive() {
        XServerDisplayActivity session = liveSession();
        if (session == null) return;
        ViewGroup root = session.findViewById(R.id.FLXServerDisplay);
        if (root != null) attach(session, root);
    }

    private boolean launchSession(File shortcut) {
        final boolean[] launched = {false};
        final Object lock = new Object();
        MAIN.post(() -> {
            try {
                if (container != null && !WowImportService.isRunning()) {
                    Intent intent = WeHavePhonesSessionLauncher.buildIntent(appContext, container, shortcut);
                    intent.putExtra(EXTRA_AGENT_SESSION, true);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    appContext.startActivity(intent);
                    launched[0] = true;
                }
            }
            catch (Exception e) {
                Log.w(TAG, "agent session did not start", e);
            }
            finally {
                synchronized (lock) {
                    lock.notifyAll();
                }
            }
        });
        synchronized (lock) {
            try {
                lock.wait(5_000L);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return launched[0];
    }

    private boolean waitForSession() {
        long deadline = SystemClock.elapsedRealtime() + SESSION_WAIT_MS;
        while (!cancelled && SystemClock.elapsedRealtime() < deadline) {
            XServerDisplayActivity session = liveSession();
            WinHandler winHandler = session != null ? session.getWinHandler() : null;
            if (winHandler != null && winHandler.isReady()) return true;
            sleep(500);
        }
        return false;
    }

    private static XServerDisplayActivity liveSession() {
        XServerDisplayActivity session = XServerDisplayActivity.getActiveInstance();
        return session != null && session.isSessionActive() ? session : null;
    }

    // ---- files --------------------------------------------------------------------------

    static File agentDir(Container container) {
        return new File(WeHavePhonesPaths.driveC(container), "ProgramData/Battle.net/Agent");
    }

    /** Guest path of the newest {@code Agent.<build>\Agent.exe}, or null. */
    static String agentExeGuest(Container container) {
        File[] builds = agentDir(container).listFiles();
        int best = -1;
        if (builds != null) {
            for (File build : builds) {
                String name = build.getName();
                if (!build.isDirectory() || !name.matches("Agent\\.\\d+") || !new File(build, "Agent.exe").isFile()) continue;
                try {
                    best = Math.max(best, Integer.parseInt(name.substring(6)));
                }
                catch (NumberFormatException ignored) {}
            }
        }
        return best < 0 ? null : "C:\\ProgramData\\Battle.net\\Agent\\Agent." + best + "\\Agent.exe";
    }

    private static String secret() {
        byte[] bytes = new byte[16];
        new SecureRandom().nextBytes(bytes);
        StringBuilder out = new StringBuilder();
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    /** Region, language and countries for a new install: the other WoW games' settings first. */
    private static final class Defaults {
        String region = "us";
        String locale = "enUS";
        String accountCountry = "USA";
        String geoCountry = "US";
    }

    private Defaults defaults() {
        Defaults d = new Defaults();
        d.region = WeHavePhonesConfig.getGameRegion(appContext);
        Locale device = Locale.getDefault();
        String country = device.getCountry().toUpperCase(Locale.ROOT);
        String iso3 = "";
        try {
            iso3 = device.getISO3Country().toUpperCase(Locale.ROOT);
        }
        catch (Exception ignored) {}
        if (country.matches("[A-Z]{2}") && iso3.matches("[A-Z]{3}")) {
            d.geoCountry = country;
            d.accountCountry = iso3;
        }
        if ("cn".equals(d.region)) d.locale = "zhCN";
        BattleNetAgentApi.Response list = client.get("/game");
        for (String uid : BattleNetAgentApi.parseGameList(list != null && list.ok() ? list.json() : null)) {
            if (uid.equals(job.uid) || !uid.startsWith("wow")) continue;
            BattleNetAgentApi.Response game = client.get("/game/" + uid);
            JSONObject json = game != null && game.ok() ? game.json() : null;
            if (json == null || !json.optBoolean("installed", false)) continue;
            String account = json.optString("account_country", "");
            String geo = json.optString("geo_ip_country", "");
            String locale = json.optString("selected_locale", "");
            if (account.matches("[A-Z]{3}")) d.accountCountry = account;
            if (geo.matches("[A-Z]{2}")) d.geoCountry = geo;
            if (locale.matches("[a-zA-Z]{4}")) d.locale = locale.substring(0, 2).toLowerCase(Locale.ROOT) + locale.substring(2).toUpperCase(Locale.ROOT);
            break;
        }
        return d;
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
