package com.wehavephones.mobile;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Incremental parser for the Battle.net client's own log
 * ({@code …/AppData/Local/Battle.net/Logs/battle.net-*.log}). Pure Java so the
 * host self-test can feed it real, sanitized log excerpts.
 *
 * Markers (verified on device):
 * <ul>
 *   <li>{@code Opening IPC shared memory. … mode=server|client}: the main client
 *   is the server; a {@code --exec} forwarder writes its own log as client.</li>
 *   <li>{@code [BNLogin] … Logged into Battle.net successfully.}</li>
 *   <li>{@code InstallState (<uid>): k=v …}: incremental deltas; fields are merged.
 *   Ready = {@code currentOperation=UpToDate} and {@code playable=1}.</li>
 *   <li>{@code [InstallManager] … Launched <exe> with args: <args>, pid: N}</li>
 *   <li>{@code [InstallManager] … Game is running: <uid>}</li>
 *   <li>{@code [UnifiedAuth] … UAuth: finished loading. statusCode=200 state=LoginCredential}:
 *   the login form is up (fresh email pages report {@code state=Login});
 *   the preceding {@code UAuth: begin loading: <url>}
 *   is {@code …/login/en/password?app=app} for the password page of the
 *   remembered account, otherwise the email page (verified on device 2026-09-28).</li>
 * </ul>
 */
public final class BattleNetLauncherLog {
    public enum Phase {
        /** No main-client log line seen yet. */
        STARTING,
        /** Client running, not signed in yet. */
        SIGNING_IN,
        /** Signed in, product state unknown or not settled. */
        CHECKING_UPDATES,
        /** Product is installing/updating or not playable. */
        UPDATING,
        /** Signed in and the product is up to date and playable. */
        READY
    }

    /** Which login page Battle.net shows, if any. */
    public enum LoginForm { NONE, EMAIL, PASSWORD }

    public static final class Launch {
        public final String executable;
        public final String args;
        public final int pid;
        public final String uid;
        public final String timestamp;

        Launch(String executable, String args, int pid, String uid, String timestamp) {
            this.executable = executable;
            this.args = args;
            this.pid = pid;
            this.uid = uid;
            this.timestamp = timestamp;
        }
    }

    public static final String LOG_PREFIX = "battle.net-";
    public static final String LOG_SUFFIX = ".log";

    private static final Pattern INSTALL_STATE = Pattern.compile("InstallState \\(([^)]*)\\):(.*)$");
    private static final Pattern FIELD = Pattern.compile("(?:^|\\s)([A-Za-z][A-Za-z0-9_]*)=");
    private static final Pattern LAUNCHED = Pattern.compile("\\[InstallManager\\].*? Launched (.+?) with args: (.*), pid: (\\d+)");
    private static final Pattern GAME_RUNNING = Pattern.compile("Game is running: (\\S+)");
    private static final Pattern UID_ARG = Pattern.compile("-uid\\s+(\\S+)");
    private static final Pattern IPC_MODE = Pattern.compile("IPC shared memory\\..*mode=(server|client)");
    private static final Pattern TIMESTAMP = Pattern.compile("^[A-Z] (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+)");
    private static final Pattern UAUTH_BEGIN = Pattern.compile("UAuth: begin loading: (\\S+)");
    private static final Pattern UAUTH_FINISHED = Pattern.compile("UAuth: finished loading\\. statusCode=(\\d+) state=(\\w+)");
    private static final Pattern UAUTH_STATE = Pattern.compile("UAuth: browser state changed: (\\w+)");
    private static final Pattern AUTH_BROWSER_ERROR = Pattern.compile("UAuth: browser error:.*?errorCode=(-?\\d{1,8})\\b");
    private static final Pattern CLIENT_ERROR_CODE = Pattern.compile("\\bBLZ[A-Z0-9]{5,32}\\b");
    private static final String LOGIN_CREDENTIAL = "LoginCredential";
    private static final String WEB_LOGIN_COMPLETE = "UAuth: web login complete";
    private static final String LOGIN_FAILED = "[BNLogin] {Main} Login failed.";
    private static final String LOGGED_IN = "Logged into Battle.net successfully.";
    private static final String LOGGED_OUT = "[BNLogin] {Main} Logout";
    private static final String COMMAND_LINE = "Command line arguments: ";

    private final HashMap<String, LinkedHashMap<String, String>> products = new HashMap<>();
    private final ArrayList<Launch> launches = new ArrayList<>();
    private final ArrayList<String> runningGames = new ArrayList<>();
    private String ipcMode = "";
    private String commandLine = "";
    private boolean loggedIn;
    private String loggedInAt = "";
    private int lineCount;
    private int lineCountAtLogin = -1;
    private String loginUrl = "";
    private boolean loginFormShown;
    private boolean webLoginComplete;
    private int loginFormLoads;
    private final ArrayList<String> authenticationEvents = new ArrayList<>();

    public void feed(String line) {
        if (line == null) return;
        lineCount++;
        recordAuthentication(line);
        if (line.contains(LOGGED_IN)) {
            if (!loggedIn) {
                loggedInAt = timestamp(line);
                lineCountAtLogin = lineCount;
            }
            loggedIn = true;
            return;
        }
        if (line.endsWith(LOGGED_OUT)) {
            // The player logged out from Battle.net's menu; its sign-in form comes next.
            loggedIn = false;
            webLoginComplete = false;
            loginFormShown = false;
            return;
        }
        if (line.contains("UAuth: ")) {
            feedUnifiedAuth(line);
            return;
        }
        if (line.contains(LOGIN_FAILED)) {
            webLoginComplete = false;
            return;
        }
        Matcher matcher = INSTALL_STATE.matcher(line);
        if (matcher.find()) {
            mergeInstallState(matcher.group(1).trim().toLowerCase(Locale.ROOT), matcher.group(2));
            return;
        }
        matcher = LAUNCHED.matcher(line);
        if (matcher.find()) {
            String args = matcher.group(2).trim();
            Matcher uid = UID_ARG.matcher(args);
            int pid;
            try {
                pid = Integer.parseInt(matcher.group(3));
            }
            catch (NumberFormatException e) {
                pid = -1;
            }
            launches.add(new Launch(matcher.group(1).trim(), args, pid,
                    uid.find() ? uid.group(1).toLowerCase(Locale.ROOT) : "", timestamp(line)));
            return;
        }
        matcher = GAME_RUNNING.matcher(line);
        if (matcher.find()) {
            runningGames.add(matcher.group(1).toLowerCase(Locale.ROOT));
            return;
        }
        matcher = IPC_MODE.matcher(line);
        if (matcher.find()) {
            ipcMode = matcher.group(1);
            return;
        }
        int index = line.indexOf(COMMAND_LINE);
        if (index >= 0) commandLine = line.substring(index + COMMAND_LINE.length()).trim();
    }

    private void feedUnifiedAuth(String line) {
        if (line.contains(WEB_LOGIN_COMPLETE)) {
            webLoginComplete = true;
            return;
        }
        Matcher matcher = UAUTH_BEGIN.matcher(line);
        if (matcher.find()) {
            loginUrl = matcher.group(1);
            loginFormShown = false;
            return;
        }
        matcher = UAUTH_FINISHED.matcher(line);
        if (matcher.find()) {
            loginFormShown = "200".equals(matcher.group(1)) && isCredentialState(matcher.group(2));
            if (loginFormShown) loginFormLoads++;
            return;
        }
        matcher = UAUTH_STATE.matcher(line);
        if (matcher.find() && !isCredentialState(matcher.group(1))) loginFormShown = false;
    }

    private boolean isCredentialState(String state) {
        if (LOGIN_CREDENTIAL.equals(state)) return true;
        if (!"Login".equals(state)) return false;
        // The first email page uses Login rather than LoginCredential. Restrict
        // that extra state to the observed account form, not challenge pages.
        try {
            URI uri = URI.create(loginUrl);
            String host = uri.getHost();
            String path = uri.getPath();
            return "https".equalsIgnoreCase(uri.getScheme()) && host != null
                    && (host.equalsIgnoreCase("account.battle.net")
                    || host.toLowerCase(Locale.ROOT).endsWith(".account.battle.net"))
                    && path != null && path.matches("/login/[A-Za-z_-]+/(?:login\\.app)?");
        }
        catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    public void feedAll(Iterable<String> lines) {
        for (String line : lines) feed(line);
    }

    public boolean isLoggedIn() {
        return loggedIn;
    }

    /** Bounded, allowlisted diagnostics. Never returns URLs, account data or arbitrary log text. */
    public String authenticationSummary() {
        StringBuilder result = new StringBuilder("Signed in: ").append(loggedIn ? "yes" : "no")
                .append("\nCredential form: ").append(loginForm()).append('\n');
        for (String event : authenticationEvents) result.append(event).append('\n');
        return result.toString();
    }

    private void recordAuthentication(String line) {
        String event = null;
        if (line.contains(LOGGED_IN)) event = "signed-in";
        else if (line.contains(LOGIN_FAILED)) event = "login-failed";
        else if (line.endsWith(LOGGED_OUT)) event = "logged-out";
        else if (line.contains(WEB_LOGIN_COMPLETE)) event = "web-login-complete";
        else if (line.contains("UAuth: tassadar Login URL is empty!")) event = "login-url-missing";
        else if (line.contains("UAuth: ")) {
            Matcher state = UAUTH_STATE.matcher(line);
            Matcher loaded = UAUTH_FINISHED.matcher(line);
            Matcher error = AUTH_BROWSER_ERROR.matcher(line);
            Matcher begin = UAUTH_BEGIN.matcher(line);
            if (state.find()) event = "browser-state=" + safeAuthState(state.group(1));
            else if (loaded.find()) event = "page-loaded status=" + loaded.group(1)
                    + " state=" + safeAuthState(loaded.group(2));
            else if (error.find()) event = "browser-error=" + error.group(1);
            else if (begin.find()) {
                String category = "other";
                try {
                    String path = URI.create(begin.group(1)).getPath();
                    if (path != null && path.contains("/challenge")) category = "challenge";
                    else if (path != null && path.endsWith("/password")) category = "password";
                    else if (path != null && path.matches("/login/[A-Za-z_-]+/(?:login\\.app)?")) category = "account-login";
                }
                catch (IllegalArgumentException ignored) {}
                event = "page=" + category;
            }
        }
        if (event != null) appendAuthenticationEvent(line, event);
        Matcher code = CLIENT_ERROR_CODE.matcher(line);
        while (code.find()) appendAuthenticationEvent(line, "client-error=" + code.group());
        String lower = line.toLowerCase(Locale.ROOT);
        if ((lower.contains("renderer") || lower.contains("mojo"))
                && (lower.contains("timed out") || lower.contains("crashed"))) {
            appendAuthenticationEvent(line, "renderer-failure");
        }
    }

    private static String safeAuthState(String state) {
        switch (state) {
            case "Login": case "LoginCredential": case "Challenge": case "Done": case "None":
                return state;
            default: return "other";
        }
    }

    private void appendAuthenticationEvent(String line, String event) {
        String entry = timestamp(line) + " " + event;
        if (!authenticationEvents.isEmpty() && authenticationEvents.get(authenticationEvents.size() - 1).equals(entry)) return;
        if (authenticationEvents.size() == 32) authenticationEvents.remove(0);
        authenticationEvents.add(entry);
    }

    /**
     * The login page on screen, or NONE (signed in, no form loaded yet, or the
     * web login went through: the client reloads the form in the background
     * while it connects, before {@code Logged into Battle.net}).
     */
    public LoginForm loginForm() {
        if (loggedIn || webLoginComplete || !loginFormShown) return LoginForm.NONE;
        String path = loginUrl.toLowerCase(Locale.ROOT);
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        return path.endsWith("/password") ? LoginForm.PASSWORD : LoginForm.EMAIL;
    }

    /** How many times a login page finished loading; a new page (e.g. after a wrong password) counts again. */
    public int getLoginFormLoads() {
        return loginFormLoads;
    }

    public String getLoggedInAt() {
        return loggedInAt;
    }

    public boolean isServerLog() {
        return "server".equals(ipcMode);
    }

    public boolean isClientLog() {
        return "client".equals(ipcMode);
    }

    public String getCommandLine() {
        return commandLine;
    }

    public int getLineCount() {
        return lineCount;
    }

    /** Lines parsed since the sign-in marker (-1 before sign-in). */
    public int linesSinceLogin() {
        return lineCountAtLogin < 0 ? -1 : lineCount - lineCountAtLogin;
    }

    public Map<String, String> productFields(String uid) {
        LinkedHashMap<String, String> fields = products.get(normalize(uid));
        return fields != null ? fields : new LinkedHashMap<String, String>();
    }

    /** Every product uid the client reported InstallState for, lower-cased. */
    public ArrayList<String> productUids() {
        ArrayList<String> uids = new ArrayList<>(products.keySet());
        java.util.Collections.sort(uids);
        return uids;
    }

    public boolean hasProductState(String uid) {
        LinkedHashMap<String, String> fields = products.get(normalize(uid));
        return fields != null && fields.containsKey("currentOperation");
    }

    public String currentOperation(String uid) {
        String value = productFields(uid).get("currentOperation");
        return value != null ? value : "";
    }

    public boolean isPlayable(String uid) {
        return "1".equals(productFields(uid).get("playable"));
    }

    public boolean isUpToDate(String uid) {
        return "UpToDate".equalsIgnoreCase(currentOperation(uid));
    }

    public boolean isUpdating(String uid) {
        if (!hasProductState(uid)) return false;
        return !isDownloadComplete(uid);
    }

    public boolean isDownloadComplete(String uid) {
        if (!isUpToDate(uid) || !isPlayable(uid)) return false;
        Map<String, String> fields = productFields(uid);
        if ("0".equals(fields.get("installed"))) return false;
        String stage = currentStage(uid);
        return stage.isEmpty() || "Complete".equalsIgnoreCase(stage);
    }

    /** Progress fraction 0..1 from a {@code progress=} field, or -1 when absent. */
    public double progress(String uid) {
        String value = productFields(uid).get("progress");
        if (value == null || value.isEmpty()) return -1;
        try {
            double parsed = Double.parseDouble(value);
            if (parsed > 1.0 && parsed <= 100.0) parsed /= 100.0;
            return Math.max(0, Math.min(1, parsed));
        }
        catch (NumberFormatException e) {
            return -1;
        }
    }

    public String currentStage(String uid) {
        String value = productFields(uid).get("currentStage");
        return value != null ? value : "";
    }

    public Phase phase(String uid) {
        if (ipcMode.isEmpty() && lineCount == 0) return Phase.STARTING;
        if (!loggedIn) return Phase.SIGNING_IN;
        if (!hasProductState(uid)) return Phase.CHECKING_UPDATES;
        if (isDownloadComplete(uid)) return Phase.READY;
        return Phase.UPDATING;
    }

    public ArrayList<Launch> getLaunches() {
        return launches;
    }

    /** Newest {@code Launched …} line for {@code uid} (any uid when empty). */
    public Launch lastLaunch(String uid) {
        String wanted = normalize(uid);
        for (int i = launches.size() - 1; i >= 0; i--) {
            Launch launch = launches.get(i);
            if (wanted.isEmpty() || wanted.equals(launch.uid)) return launch;
        }
        return null;
    }

    public boolean isGameRunning(String uid) {
        return runningGames.contains(normalize(uid));
    }

    private void mergeInstallState(String uid, String body) {
        LinkedHashMap<String, String> fields = products.get(uid);
        if (fields == null) {
            fields = new LinkedHashMap<>();
            products.put(uid, fields);
        }
        for (Map.Entry<String, String> entry : parseFields(body).entrySet()) {
            fields.put(entry.getKey(), entry.getValue());
        }
    }

    /**
     * Split {@code k=v k2=v with spaces k3=v} into fields. A value runs until
     * the next {@code name=} token, so paths containing spaces survive.
     */
    public static LinkedHashMap<String, String> parseFields(String body) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        if (body == null) return fields;
        Matcher matcher = FIELD.matcher(body);
        String key = null;
        int valueStart = 0;
        while (matcher.find()) {
            if (key != null) fields.put(key, body.substring(valueStart, matcher.start()).trim());
            key = matcher.group(1);
            valueStart = matcher.end();
        }
        if (key != null) fields.put(key, body.substring(valueStart).trim());
        return fields;
    }

    private static String timestamp(String line) {
        Matcher matcher = TIMESTAMP.matcher(line);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static String normalize(String uid) {
        return uid != null ? uid.trim().toLowerCase(Locale.ROOT) : "";
    }

    // ---- file helpers (java.io only) -------------------------------------------------

    /** All {@code battle.net-*.log} files in {@code logsDir}, newest name first. */
    public static ArrayList<File> listLogs(File logsDir) {
        ArrayList<File> logs = new ArrayList<>();
        File[] files = logsDir != null ? logsDir.listFiles() : null;
        if (files == null) return logs;
        for (File file : files) {
            String name = file.getName();
            if (file.isFile() && name.startsWith(LOG_PREFIX) && name.endsWith(LOG_SUFFIX)) logs.add(file);
        }
        // Names embed a sortable UTC stamp (battle.net-20260926T015305.502543.log).
        logs.sort((first, second) -> second.getName().compareTo(first.getName()));
        return logs;
    }

    /**
     * The newest main-client log whose name sorts strictly after
     * {@code newerThanName} (empty = any). A log that has not yet written its
     * IPC line is returned only if no server log qualifies, so a fresh client
     * is picked up early; callers re-resolve until a server log appears.
     */
    public static File findMainLog(File logsDir, String newerThanName) {
        File pending = null;
        for (File file : listLogs(logsDir)) {
            if (newerThanName != null && !newerThanName.isEmpty() && file.getName().compareTo(newerThanName) <= 0) break;
            String mode = readIpcMode(file);
            if ("server".equals(mode)) return file;
            if (mode.isEmpty() && pending == null) pending = file;
        }
        return pending;
    }

    static String readIpcMode(File file) {
        try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
            byte[] buffer = new byte[(int)Math.min(input.length(), 16 * 1024)];
            input.readFully(buffer);
            Matcher matcher = IPC_MODE.matcher(new String(buffer, StandardCharsets.UTF_8));
            return matcher.find() ? matcher.group(1) : "";
        }
        catch (IOException e) {
            return "";
        }
    }

    /** Tails one log file; call {@link #poll()} repeatedly. */
    public static final class Tail {
        public final File file;
        public final BattleNetLauncherLog log = new BattleNetLauncherLog();
        private long offset;
        private final StringBuilder partial = new StringBuilder();

        public Tail(File file) {
            this.file = file;
        }

        /** Reads the whole file (up to {@code maxChunks} polls of 4 MB); returns the lines parsed. */
        public int readAll(int maxChunks) {
            int total = 0;
            for (int chunk = 0; chunk < maxChunks; chunk++) {
                int parsed = poll();
                if (parsed == 0) break;
                total += parsed;
            }
            return total;
        }

        /** Reads new complete lines; returns how many were parsed. */
        public int poll() {
            int parsed = 0;
            try (RandomAccessFile input = new RandomAccessFile(file, "r")) {
                long length = input.length();
                if (length < offset) {
                    offset = 0;
                    partial.setLength(0);
                }
                if (length == offset) return 0;
                input.seek(offset);
                byte[] buffer = new byte[(int)Math.min(length - offset, 4 * 1024 * 1024)];
                input.readFully(buffer);
                offset += buffer.length;
                partial.append(new String(buffer, StandardCharsets.UTF_8));
                int start = 0;
                for (int i = 0; i < partial.length(); i++) {
                    if (partial.charAt(i) != '\n') continue;
                    String line = partial.substring(start, i);
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    log.feed(line);
                    parsed++;
                    start = i + 1;
                }
                partial.delete(0, start);
            }
            catch (IOException ignored) {}
            return parsed;
        }
    }
}
