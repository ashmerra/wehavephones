package com.wehavephones.mobile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;

/**
 * Pure launch plan for the one-button Play flow: product table
 * ({@code assets/wehavephones/wehavephones-launch-products.json} plus an optional
 * override file), the fallback ladder, generated batch files and the
 * WinHandler EXEC command. No Android dependencies (host self-tested).
 */
public final class WeHavePhonesLaunchPlan {
    public static final String ASSET_PATH = "wehavephones/wehavephones-launch-products.json";
    public static final String OVERRIDE_FILE_NAME = "launch-overrides.json";

    /** Launch bat run inside the live session (short: WinHandler packets are 64 bytes). */
    public static final String LAUNCH_BAT_NAME = "wclaunch.bat";
    public static final String LAUNCH_BAT_GUEST = "C:\\" + LAUNCH_BAT_NAME;
    /** Session start bat: the profile's bnet.bat plus one-button flags. */
    public static final String START_BAT_NAME = "wcstart.bat";
    public static final String START_BAT_GUEST = "C:\\" + START_BAT_NAME;
    public static final String START_SHORTCUT_NAME = "WeHavePhones Play";
    /** Install/update request forwarded to a running client (same 64-byte limit). */
    public static final String INSTALL_BAT_NAME = "wcinstall.bat";
    public static final String INSTALL_BAT_GUEST = "C:\\" + INSTALL_BAT_NAME;
    public static final String CMD_EXE = "C:\\windows\\system32\\cmd.exe";
    /** Runs {@code cmd /c <bat>} without a console window (tools/wcrun). */
    public static final String HIDDEN_RUNNER_NAME = "wcrun.exe";
    /** The WoW graphics API flag used unless the device profile sets another. */
    public static final String DEFAULT_WOW_API_ARG = "-d3d12";
    public static final String HIDDEN_RUNNER_GUEST = "C:\\windows\\" + HIDDEN_RUNNER_NAME;

    /** WinHandler.exec sends one 64-byte datagram: code + 3 int32 + filename + parameters. */
    public static final int WINHANDLER_PACKET_BYTES = 64;
    public static final int WINHANDLER_HEADER_BYTES = 13;

    public static final String BNET_DIR_X86 = "C:\\Program Files (x86)\\Battle.net";
    public static final String BNET_DIR = "C:\\Program Files\\Battle.net";
    public static final String WOW_DIR_X86 = "C:\\Program Files (x86)\\World of Warcraft";
    public static final String WOW_DIR = "C:\\Program Files\\World of Warcraft";

    /** Configure only a new Battle.net installation before its automatic client launch. */
    public static String installerBat(String executable, boolean freshInstall, String bnetFlags) {
        String graphics = freshInstall
                ? "reg.exe add \"HKCU\\Software\\Wine\\AppDefaults\\Battle.net.exe\\X11 Driver\""
                    + " /v ClientSideGraphics /t REG_SZ /d N /f >NUL\r\n"
                    + "if errorlevel 1 exit /b 1\r\n"
                : "";
        // The bootstrapper forwards these arguments to its automatic client
        // launch, which otherwise bypasses the runtime profile's bnet.bat.
        String flags = freshInstall && bnetFlags != null ? bnetFlags.trim() : "";
        return "@echo off\r\n" + graphics + "\"" + executable + "\""
                + (flags.isEmpty() ? "" : " " + flags) + "\r\n";
    }

    /**
     * Ways to start the game. Only {@link #PLAY} is in the default ladder: on
     * Battle.net 2.53 {@code --exec="launch <code>"} and {@code --game=<uid>}
     * only select the game, and a game started without Battle.net (direct)
     * never gets Battle.net's sign-in, so it stops at its own password prompt.
     * The others stay reachable with {@code forcePath} for testing.
     */
    public enum Path {
        /** A click on the Play button of the game's page (opened by {@code --game=<uid>}). */
        PLAY,
        /** {@code Battle.net.exe --exec="launch <code>"} into the running client (selects only). */
        EXEC,
        /** {@code Battle.net.exe --game=<uid>} into the running client (selects only). */
        GAME,
        /** {@code Wow*.exe -launcherlogin -uid <uid> -d3d12} while the client runs (no sign-in). */
        DIRECT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Path fromId(String id) {
            if (id == null) return null;
            for (Path path : values()) {
                if (path.id().equalsIgnoreCase(id.trim())) return path;
            }
            return null;
        }
    }

    public static final class Product {
        public final String dir;
        /** Battle.net's InstallState/product.db uid and --game id, not the TACT product code. */
        public final String uid;
        public final String launchUid;
        public final ArrayList<String> execCodes;
        public final String exe;
        public final String confidence;

        public Product(String dir, String uid, String launchUid, ArrayList<String> execCodes, String exe, String confidence) {
            this.dir = dir;
            this.uid = uid;
            this.launchUid = launchUid != null && !launchUid.isEmpty() ? launchUid : uid;
            this.execCodes = execCodes;
            this.exe = exe;
            this.confidence = confidence;
        }

        Product withExecCodes(ArrayList<String> codes) {
            return new Product(dir, uid, launchUid, codes, exe, confidence);
        }

        Product withExe(String executable) {
            return new Product(dir, uid, launchUid, execCodes, executable, confidence);
        }
    }

    public static final class Attempt {
        public final Path path;
        /** Exec code for {@link Path#EXEC}, empty otherwise. */
        public final String code;

        Attempt(Path path, String code) {
            this.path = path;
            this.code = code != null ? code : "";
        }

        public String describe() {
            return code.isEmpty() ? path.id() : path.id() + ":" + code;
        }
    }

    public static final class Timeouts {
        public int sessionStartSeconds = 90;
        public int readySeconds = 420;
        public int updateSeconds = 3600;
        public int settleSeconds = 5;
        public int spawnSeconds = 30;
        /** How long Battle.net may take to close itself after the game starts before it is closed for it. */
        public int launcherExitSeconds = 240;

        void merge(JSONObject json) {
            if (json == null) return;
            sessionStartSeconds = json.optInt("sessionStartSeconds", sessionStartSeconds);
            readySeconds = json.optInt("readySeconds", readySeconds);
            updateSeconds = json.optInt("updateSeconds", updateSeconds);
            settleSeconds = json.optInt("settleSeconds", settleSeconds);
            spawnSeconds = json.optInt("spawnSeconds", spawnSeconds);
            launcherExitSeconds = json.optInt("launcherExitSeconds", launcherExitSeconds);
        }
    }

    /** Launch options set by the override file for testing. */
    public static final class Options {
        public boolean minimized;
        public boolean coldExec;
        /**
         * Start Battle.net with {@code --game=<uid>} so it opens on the game's
         * page. ({@code --launch} is not used: Battle.net treats it as an
         * Xbox launch, waits for an external sign-in token and starts minimized.)
         */
        public boolean coldSelect = true;
        public Path forcePath;
        public String execCodeOverride = "";

        void merge(JSONObject json) {
            if (json == null) return;
            if (json.has("minimized")) minimized = json.optBoolean("minimized", minimized);
            if (json.has("coldExec")) coldExec = json.optBoolean("coldExec", coldExec);
            if (json.has("coldSelect")) coldSelect = json.optBoolean("coldSelect", coldSelect);
            if (json.has("forcePath")) forcePath = Path.fromId(json.optString("forcePath", ""));
            if (json.has("execCode")) execCodeOverride = json.optString("execCode", "").trim();
        }
    }

    private final LinkedHashMap<String, Product> products = new LinkedHashMap<>();
    public final Timeouts timeouts = new Timeouts();
    public final Options options = new Options();
    /**
     * Battle.net's Play button, in X pixels from the main window's left and
     * bottom edges (measured on the S23 with the 1280x720 X screen; the button
     * spans 39-272 across and 81-136 up).
     */
    public int playFromLeft = 155;
    public int playFromBottom = 108;
    /**
     * Play clicks before giving up, {@code playWaitSeconds} apart. Battle.net's
     * page can take minutes to render after sign-in on a phone and drops
     * clicks until then, so the click repeats until the game starts.
     */
    public int playAttempts = 6;
    public int playWaitSeconds = 10;
    /**
     * Battle.net restarts when the clicks did not start the game: it
     * sometimes hangs before its page renders (spinner, log silent).
     */
    public int launcherRestarts = 2;
    /** Wait after an in-session {@code --game=<uid>} before clicking, for the page to show. */
    public int playSettleMs = 4000;

    public static WeHavePhonesLaunchPlan parse(String bundledJson, String overrideJson) throws JSONException {
        WeHavePhonesLaunchPlan plan = new WeHavePhonesLaunchPlan();
        plan.merge(new JSONObject(bundledJson), true);
        if (overrideJson != null && !overrideJson.trim().isEmpty()) plan.merge(new JSONObject(overrideJson), false);
        return plan;
    }

    private void merge(JSONObject root, boolean bundled) throws JSONException {
        JSONArray array = root.optJSONArray("products");
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                String dir = item.optString("dir", "").trim();
                if (dir.isEmpty()) continue;
                Product existing = products.get(dir.toLowerCase(Locale.ROOT));
                ArrayList<String> codes = new ArrayList<>();
                JSONArray codeArray = item.optJSONArray("execCodes");
                if (codeArray != null) {
                    for (int j = 0; j < codeArray.length(); j++) {
                        String code = codeArray.optString(j, "").trim();
                        if (!code.isEmpty()) codes.add(code);
                    }
                }
                if (existing != null && !bundled) {
                    Product updated = existing;
                    if (codeArray != null) updated = updated.withExecCodes(codes);
                    if (item.has("exe")) updated = updated.withExe(item.optString("exe", existing.exe));
                    String uid = item.optString("uid", updated.uid);
                    String launchUid = item.optString("launchUid", updated.launchUid);
                    updated = new Product(updated.dir, uid, launchUid, updated.execCodes, updated.exe,
                            item.optString("confidence", "override"));
                    products.put(dir.toLowerCase(Locale.ROOT), updated);
                }
                else {
                    products.put(dir.toLowerCase(Locale.ROOT), new Product(dir,
                            item.optString("uid", defaultUid(dir)),
                            item.optString("launchUid", ""),
                            codes,
                            item.optString("exe", ""),
                            item.optString("confidence", bundled ? "community" : "override")));
                }
            }
        }
        JSONObject play = root.optJSONObject("playButton");
        if (play != null) {
            playFromLeft = play.optInt("fromLeft", playFromLeft);
            playFromBottom = play.optInt("fromBottom", playFromBottom);
            playAttempts = Math.max(1, play.optInt("attempts", playAttempts));
            playWaitSeconds = Math.max(1, play.optInt("waitSeconds", playWaitSeconds));
            launcherRestarts = Math.max(0, play.optInt("restarts", launcherRestarts));
            playSettleMs = play.optInt("settleMs", playSettleMs);
        }
        timeouts.merge(root.optJSONObject("timeouts"));
        options.merge(root.optJSONObject("options"));
    }

    public ArrayList<Product> getProducts() {
        return new ArrayList<>(products.values());
    }

    /**
     * Product entry for an install directory. Unknown directories get a
     * synthesized entry (uid derived from the directory, no exec codes) so the
     * direct and tap fallbacks still work.
     */
    public Product resolve(String dir) {
        String key = dir != null && !dir.isEmpty() ? dir.toLowerCase(Locale.ROOT) : "_retail_";
        Product product = products.get(key);
        if (product == null) {
            product = new Product(dir, defaultUid(dir), "", new ArrayList<String>(), "", "unknown");
        }
        if (!options.execCodeOverride.isEmpty()) {
            ArrayList<String> codes = new ArrayList<>();
            codes.add(options.execCodeOverride);
            for (String code : product.execCodes) {
                if (!code.equals(options.execCodeOverride)) codes.add(code);
            }
            product = product.withExecCodes(codes);
        }
        return product;
    }

    static String defaultUid(String dir) {
        if (dir == null) return "wow";
        String trimmed = dir.replaceAll("^_+|_+$", "").toLowerCase(Locale.ROOT);
        if (trimmed.isEmpty() || trimmed.equals("retail")) return "wow";
        return "wow_" + trimmed;
    }

    /**
     * Launch attempts: Play clicks on the game's page ({@code playAttempts}),
     * so Battle.net starts the game, hands it the sign-in and, with
     * {@code GameLaunchWindowBehavior}, closes itself. The page is opened by
     * the start flags ({@link #startFlags}) or, for a client that was already
     * running, by one in-session {@code --game=<uid>}; re-sending it reloads
     * the page, so the clicks do not. {@code forcePath}
     * replaces them with one kind of selection-only or direct rung (testing).
     */
    public ArrayList<Attempt> ladder(Product product) {
        ArrayList<Attempt> attempts = new ArrayList<>();
        Path force = options.forcePath;
        if ((force == null || force == Path.PLAY) && isProductId(product.uid)) {
            for (int i = 0; i < playAttempts; i++) attempts.add(new Attempt(Path.PLAY, product.uid));
        }
        if (force == Path.EXEC) {
            for (String code : product.execCodes) attempts.add(new Attempt(Path.EXEC, code));
        }
        if (force == Path.GAME && isProductId(product.uid)) attempts.add(new Attempt(Path.GAME, product.uid));
        if (force == Path.DIRECT) attempts.add(new Attempt(Path.DIRECT, ""));
        return attempts;
    }

    // ---- batch files ------------------------------------------------------------------

    public static String execBat(String bnetGuestDir, String code) {
        return "@echo off\r\n"
                + "rem WeHavePhones one-button launch (exec " + code + ")\r\n"
                + "cd /d \"" + bnetGuestDir + "\"\r\n"
                + "Battle.net.exe --exec=\"launch " + code + "\"\r\n";
    }

    /** Opens the game's page in the running client. */
    public static String gameBat(String bnetGuestDir, String uid) {
        return "@echo off\r\n"
                + "rem WeHavePhones one-button launch (game " + uid + ")\r\n"
                + "cd /d \"" + bnetGuestDir + "\"\r\n"
                + "Battle.net.exe --game=" + uid + "\r\n";
    }

    public static String directBat(String wowGuestRoot, Product product, String executable) {
        return directBat(wowGuestRoot, product, executable, null);
    }

    /** {@code apiArg} is the device profile's graphics API flag (for example "-d3d11"); null keeps "-d3d12". */
    public static String directBat(String wowGuestRoot, Product product, String executable, String apiArg) {
        String exe = executable != null && !executable.isEmpty() ? executable : product.exe;
        return "@echo off\r\n"
                + "rem WeHavePhones one-button launch (direct " + product.launchUid + ")\r\n"
                + "cd /d \"" + wowGuestRoot + "\\" + product.dir + "\"\r\n"
                + "start \"\" \"" + exe + "\" -launcherlogin -uid " + product.launchUid + " "
                + (apiArg != null && !apiArg.isEmpty() ? apiArg : DEFAULT_WOW_API_ARG) + "\r\n";
    }

    /**
     * Flags that open a game in Battle.net and start its install
     * ({@code --game=wow_classic_beta --install} is how the community installs
     * the Forever beta). Empty for ids that are not plain product ids.
     */
    public static String installFlags(String gameId) {
        if (!isProductId(gameId)) return "";
        return "--game=" + gameId + " --install";
    }

    static boolean isProductId(String id) {
        return id != null && id.matches("[a-z0-9_]+");
    }

    /** Batch file that forwards an install request to the running client. */
    public static String installBat(String bnetGuestDir, String gameId) {
        return "@echo off\r\n"
                + "rem WeHavePhones install request (" + gameId + ")\r\n"
                + "cd /d \"" + bnetGuestDir + "\"\r\n"
                + "Battle.net.exe " + installFlags(gameId) + "\r\n";
    }

    /**
     * Append flags to the {@code Battle.net.exe …} line of a profile bnet.bat,
     * leaving the DLL copy lines untouched. Returns the bat unchanged when it
     * has no such line.
     */
    public static String appendBnetFlags(String bat, String extraFlags) {
        if (extraFlags == null || extraFlags.trim().isEmpty()) return bat;
        String[] lines = bat.split("\r\n", -1);
        StringBuilder builder = new StringBuilder();
        boolean done = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!done && line.trim().toLowerCase(Locale.ROOT).startsWith("battle.net.exe")) {
                line = line.replaceAll("\\s+$", "") + " " + extraFlags.trim();
                done = true;
            }
            builder.append(line);
            if (i < lines.length - 1) builder.append("\r\n");
        }
        return builder.toString();
    }

    /** Select a page without requesting installation or launching the game. */
    public static String openFlags(String gameId) {
        return isProductId(gameId) ? "--game=" + gameId : "";
    }

    /** Allow fixed browser diagnostics in Dev builds without accepting shell arguments. */
    public static String openFlags(String gameId, String javascriptMode, boolean diagnosticsEnabled) {
        String flags = openFlags(gameId);
        if (!diagnosticsEnabled) return flags;
        String browser = "jitless".equals(javascriptMode) ? "--js-flags=--jitless"
                : "no-opt".equals(javascriptMode) ? "--js-flags=--no-opt" : "";
        return browser.isEmpty() ? flags : flags + (flags.isEmpty() ? "" : " ") + browser;
    }

    public static String startFlags(Options options, Product product) {
        StringBuilder flags = new StringBuilder();
        if (options.minimized) flags.append("--autostarted");
        if (options.coldExec && !product.execCodes.isEmpty()) {
            if (flags.length() > 0) flags.append(' ');
            flags.append("--exec=\"launch ").append(product.execCodes.get(0)).append('"');
        }
        if (options.coldSelect && isProductId(product.uid)) {
            if (flags.length() > 0) flags.append(' ');
            flags.append("--game=").append(product.uid);
        }
        return flags.toString();
    }

    // ---- WinHandler EXEC --------------------------------------------------------------

    /** Runs the bat through wcrun.exe, which the session installs, so no console window opens. */
    public static String winHandlerCommand(String batGuestPath) {
        return HIDDEN_RUNNER_GUEST + " " + batGuestPath;
    }

    /** Bytes WinHandler.exec would put in its datagram for {@code command}. */
    public static int packetBytes(String command) {
        String trimmed = command.trim();
        String[] parts = trimmed.split(" ", 2);
        int filename = parts[0].getBytes(StandardCharsets.UTF_8).length;
        int parameters = parts.length > 1 ? parts[1].getBytes(StandardCharsets.UTF_8).length : 0;
        return WINHANDLER_HEADER_BYTES + filename + parameters;
    }

    public static boolean fitsWinHandler(String command) {
        return packetBytes(command) <= WINHANDLER_PACKET_BYTES && !command.trim().split(" ", 2)[0].contains("\"");
    }
}
