package com.wehavephones.mobile;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Host self-test for the one-button launch logic. Compiles against the real
 * BattleNetLauncherLog, BattleNetPlayButton, WeHavePhonesLaunchPlan and
 * WeHavePhonesBnetConfig sources.
 *
 * Usage: LaunchSelfTest <products.json> <runtime-profiles.json> <fixtures-dir>
 */
public final class LaunchSelfTest {
    private static int failures;
    private static int checks;

    public static void main(String[] args) throws Exception {
        String products = read(new File(args[0]));
        String runtime = read(new File(args[1]));
        File fixtures = new File(args[2]);

        productMapping(products);
        ladderOrdering(products);
        playButtonProbe();
        readinessWarm(fixtures);
        readinessUpdate(fixtures);
        readinessAnniversary(products, fixtures);
        forwarderAndFields(fixtures);
        logFiles(fixtures);
        batGeneration(products, runtime);
        winHandlerPackets();
        bnetConfig();
        firstLoginConfig();
        loginForm();
        authenticationDiagnostics();
        curseForgeFeed();
        curseForgeBat();
        curseForgeSettings();
        wowUp();

        System.out.println("[launch-selftest] " + checks + " checks, " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- product codes ----------------------------------------------------------------

    private static void productMapping(String products) throws Exception {
        WeHavePhonesLaunchPlan plan = WeHavePhonesLaunchPlan.parse(products, null);
        WeHavePhonesLaunchPlan.Product retail = plan.resolve("_retail_");
        check("retail uid", "wow", retail.uid);
        check("retail launch uid", "wow", retail.launchUid);
        check("retail first exec code", "WoW", retail.execCodes.get(0));
        check("retail exec code count", 1, retail.execCodes.size());
        check("retail exe", "Wow-ARM64.exe", retail.exe);
        check("retail confidence", "verified", retail.confidence);
        check("retail is first product", "_retail_", plan.getProducts().get(0).dir);

        WeHavePhonesLaunchPlan.Product classic = plan.resolve("_classic_");
        check("classic codes", "[WoW_wow_classic, WoWC]", classic.execCodes.toString());
        check("classic exe", "WowClassic-arm64.exe", classic.exe);
        check("era uid", "wow_classic_era", plan.resolve("_classic_era_").uid);
        check("anniversary launcher uid", "wow_classic_anniversary", plan.resolve("_anniversary_").uid);
        check("anniversary launch uid", "wow_classic_anniversary", plan.resolve("_anniversary_").launchUid);
        check("titan uid", "wow_classic_titan", plan.resolve("_classic_titan_").uid);
        check("forever beta uid (_classic_beta_)", "wow_classic_beta", plan.resolve("_classic_beta_").uid);
        check("forever uid", "wowf", plan.resolve("_forever_").uid);
        check("forever exec code", "WoWF", plan.resolve("_forever_").execCodes.get(0));
        check("forever exe comes from the scan", "", plan.resolve("_forever_").exe);
        check("play button from left", 155, plan.playFromLeft);
        check("play button from bottom", 108, plan.playFromBottom);
        check("play attempts", 6, plan.playAttempts);
        check("launcher restarts", 2, plan.launcherRestarts);
        check("play wait", 10, plan.playWaitSeconds);
        check("case-insensitive dir", "wow", plan.resolve("_RETAIL_").uid);
        check("null dir defaults to retail", "wow", plan.resolve(null).uid);

        WeHavePhonesLaunchPlan.Product unknown = plan.resolve("_future_");
        check("unknown dir uid derived", "wow_future", unknown.uid);
        check("unknown dir has no exec code", 0, unknown.execCodes.size());

        // Old override files may still contain keepLauncher; other options remain usable.
        WeHavePhonesLaunchPlan overridden = WeHavePhonesLaunchPlan.parse(products,
                "{\"options\":{\"execCode\":\"WoWX\",\"forcePath\":\"direct\",\"keepLauncher\":true,\"minimized\":true},"
                        + "\"products\":[{\"dir\":\"_classic_\",\"execCodes\":[\"WoWC\"]}],"
                        + "\"timeouts\":{\"launcherExitSeconds\":3}}");
        check("override exec code first", "[WoWX, WoW]", overridden.resolve("_retail_").execCodes.toString());
        check("override replaces classic codes", "[WoWX, WoWC]", overridden.resolve("_classic_").execCodes.toString());
        check("override keeps classic exe", "WowClassic-arm64.exe", overridden.resolve("_classic_").exe);
        check("override forcePath", WeHavePhonesLaunchPlan.Path.DIRECT, overridden.options.forcePath);
        check("override minimized", true, overridden.options.minimized);
        check("override timeout", 3, overridden.timeouts.launcherExitSeconds);
        check("bundled launcher exit timeout", 90, plan.timeouts.launcherExitSeconds);
        check("bundled spawn timeout", 30, plan.timeouts.spawnSeconds);
        WeHavePhonesLaunchPlan moved = WeHavePhonesLaunchPlan.parse(products, "{\"playButton\":{\"fromLeft\":200,\"attempts\":0}}");
        check("override play button", 200, moved.playFromLeft);
        check("override keeps the other play offset", 108, moved.playFromBottom);
        check("at least one play attempt", 1, moved.playAttempts);
    }

    private static void ladderOrdering(String products) throws Exception {
        WeHavePhonesLaunchPlan plan = WeHavePhonesLaunchPlan.parse(products, null);
        check("retail ladder", 6, plan.ladder(plan.resolve("_retail_")).size());
        check("retail ladder clicks Play", "play:wow", describe(plan.ladder(plan.resolve("_retail_"))).split(" -> ")[5]);
        check("classic era ladder", "play:wow_classic_era", describe(plan.ladder(plan.resolve("_classic_era_"))).split(" -> ")[0]);
        check("unknown dir ladder uses the derived uid", "play:wow_future", describe(plan.ladder(plan.resolve("_future_"))).split(" -> ")[0]);
        check("no product id, no play", "", describe(plan.ladder(plan.resolve("_Odd Dir_"))));

        WeHavePhonesLaunchPlan once = WeHavePhonesLaunchPlan.parse(products, "{\"playButton\":{\"attempts\":1}}");
        check("one play attempt", "play:wow", describe(once.ladder(once.resolve("_retail_"))));
        String[][] forced = {{"exec", "exec:WoW"}, {"game", "game:wow"}, {"direct", "direct"}};
        for (String[] force : forced) {
            WeHavePhonesLaunchPlan plan2 = WeHavePhonesLaunchPlan.parse(products, "{\"options\":{\"forcePath\":\"" + force[0] + "\"}}");
            check("forced " + force[0], force[1], describe(plan2.ladder(plan2.resolve("_retail_"))));
        }
        WeHavePhonesLaunchPlan classicExec = WeHavePhonesLaunchPlan.parse(products, "{\"options\":{\"forcePath\":\"exec\"}}");
        check("forced exec classic", "exec:WoW_wow_classic -> exec:WoWC", describe(classicExec.ladder(classicExec.resolve("_classic_"))));
    }

    /**
     * The Play button classifier, with the fills measured on the Galaxy S23
     * on 2026-10-17: the enabled button and the settings gear beside it both
     * {@code 0x0074E0}, the Forever Stress Test pre-install's disabled button
     * {@code 0x003F7A} against the same gear.
     */
    private static void playButtonProbe() {
        int enabled = 0x0074E0;
        int disabled = 0x003F7A;
        check("enabled Play reads enabled", BattleNetPlayButton.State.ENABLED,
                BattleNetPlayButton.classify(enabled, enabled));
        check("greyed-out Play reads disabled", BattleNetPlayButton.State.DISABLED,
                BattleNetPlayButton.classify(disabled, enabled));
        check("dimmed page keeps Play enabled", BattleNetPlayButton.State.ENABLED,
                BattleNetPlayButton.classify(0x003A70, 0x003A70));
        check("unreadable Play is unknown", BattleNetPlayButton.State.UNKNOWN,
                BattleNetPlayButton.classify(-1, enabled));
        check("unreadable gear is unknown", BattleNetPlayButton.State.UNKNOWN,
                BattleNetPlayButton.classify(enabled, -1));
        check("blank page is unknown", BattleNetPlayButton.State.UNKNOWN,
                BattleNetPlayButton.classify(0x000000, 0x000000));
        check("dark page background is unknown", BattleNetPlayButton.State.UNKNOWN,
                BattleNetPlayButton.classify(0x1c1d27, 0x1c1d27));
        check("grey accent dims the same way", BattleNetPlayButton.State.DISABLED,
                BattleNetPlayButton.classify(0x555555, 0x9e9e9e));
        check("bright grey accent stays enabled", BattleNetPlayButton.State.ENABLED,
                BattleNetPlayButton.classify(0x9e9e9e, 0x9e9e9e));
        check("probe hex", "#0074e0", BattleNetPlayButton.hex(enabled));
        check("probe hex for unreadable", "none", BattleNetPlayButton.hex(-1));
    }

    // ---- launcher log -----------------------------------------------------------------

    private static void readinessWarm(File fixtures) throws Exception {
        List<String> lines = Files.readAllLines(new File(fixtures, "warm-exec-main.log").toPath());
        BattleNetLauncherLog log = new BattleNetLauncherLog();
        check("empty log is STARTING", BattleNetLauncherLog.Phase.STARTING, log.phase("wow"));
        ArrayList<BattleNetLauncherLog.Phase> phases = new ArrayList<>();
        BattleNetLauncherLog.Phase readyAt = null;
        int readyLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            log.feed(lines.get(i));
            BattleNetLauncherLog.Phase phase = log.phase("wow");
            if (phases.isEmpty() || phases.get(phases.size() - 1) != phase) phases.add(phase);
            if (phase == BattleNetLauncherLog.Phase.READY && readyAt == null) {
                readyAt = phase;
                readyLine = i;
            }
        }
        check("warm: server log", true, log.isServerLog());
        check("warm: command line", "{[0]=--disable-gpu}", log.getCommandLine());
        check("warm: phases", "[SIGNING_IN, READY]", phases.toString());
        check("warm: ready exactly at login line", true, lines.get(readyLine).contains("Logged into Battle.net successfully."));
        check("warm: logged in at", "2026-09-26 01:54:20.131887", log.getLoggedInAt());
        BattleNetLauncherLog.Launch launch = log.lastLaunch("wow");
        check("warm: launch found", true, launch != null);
        check("warm: launch pid", 1720, launch.pid);
        check("warm: launch uid", "wow", launch.uid);
        check("warm: launch args", "-launcherlogin -uid wow", launch.args);
        check("warm: launch exe", "C:/Program Files (x86)/World of Warcraft/_retail_/Wow-ARM64.exe", launch.executable);
        check("warm: game running", true, log.isGameRunning("wow"));
        check("warm: era not installed -> updating", true, log.isUpdating("wow_classic_era"));
        check("warm: no launch for other uid", true, log.lastLaunch("wow_classic") == null);
        check("warm: installPath with spaces", "C:/Program Files (x86)/World of Warcraft", log.productFields("wow").get("installPath"));
    }

    private static void readinessAnniversary(String products, File fixtures) throws Exception {
        WeHavePhonesLaunchPlan plan = WeHavePhonesLaunchPlan.parse(products, null);
        WeHavePhonesLaunchPlan.Product anniversary = plan.resolve("_anniversary_");
        BattleNetLauncherLog log = new BattleNetLauncherLog();
        for (String line : Files.readAllLines(new File(fixtures, "anniversary-ready.log").toPath())) {
            log.feed(line);
        }
        check("anniversary: launcher uid passes update gate", BattleNetLauncherLog.Phase.READY, log.phase(anniversary.uid));
        check("anniversary: TACT code has no launcher state", BattleNetLauncherLog.Phase.CHECKING_UPDATES, log.phase("wow_anniversary"));
        check("anniversary: cold start selects launcher uid", true,
                WeHavePhonesLaunchPlan.startFlags(plan.options, anniversary).contains("--game=wow_classic_anniversary"));
        check("anniversary: warm page selects launcher uid", true,
                WeHavePhonesLaunchPlan.gameBat(WeHavePhonesLaunchPlan.BNET_DIR_X86, anniversary.uid).contains("--game=wow_classic_anniversary"));
        check("anniversary: Play ladder uses launcher uid", "play:wow_classic_anniversary",
                describe(plan.ladder(anniversary)).split(" -> ")[0]);
    }

    private static void readinessUpdate(File fixtures) throws Exception {
        List<String> lines = Files.readAllLines(new File(fixtures, "update-then-ready.log").toPath());
        BattleNetLauncherLog log = new BattleNetLauncherLog();
        ArrayList<BattleNetLauncherLog.Phase> phases = new ArrayList<>();
        boolean readyWhilePlayableOnly = false;
        for (String line : lines) {
            log.feed(line);
            BattleNetLauncherLog.Phase phase = log.phase("wow");
            if (phases.isEmpty() || phases.get(phases.size() - 1) != phase) phases.add(phase);
            if (line.contains("playable=1") && line.contains("regionalVersions") && !line.contains("currentOperation")) {
                readyWhilePlayableOnly = phase == BattleNetLauncherLog.Phase.READY;
                check("update: playable=1 during Update keeps UPDATING", BattleNetLauncherLog.Phase.UPDATING, phase);
            }
        }
        check("update: phases", "[SIGNING_IN, UPDATING, READY]", phases.toString());
        check("update: never ready on playable alone", false, readyWhilePlayableOnly);
        check("update: final operation", "UpToDate", log.currentOperation("wow"));
        check("update: stage", "Complete", log.currentStage("wow"));
        check("update: no progress field -> -1", -1.0, log.progress("wow"));

        BattleNetLauncherLog synthetic = new BattleNetLauncherLog();
        synthetic.feed("I 2026-09-25 21:56:48.527894 [BNLogin] {Main} Logged into Battle.net successfully.");
        synthetic.feed("D 2026-09-25 21:57:00.000000 [ProductState] {Main} InstallState (wow): currentOperation=Update playable=0 progress=0.42 currentStage=Downloading");
        check("synthetic: updating", BattleNetLauncherLog.Phase.UPDATING, synthetic.phase("wow"));
        check("synthetic: progress", 0.42, synthetic.progress("wow"));
        synthetic.feed("D 2026-09-25 21:58:00.000000 [ProductState] {Main} InstallState (wow): progress=87");
        check("synthetic: percent progress", 0.87, synthetic.progress("wow"));
        synthetic.feed("D 2026-09-25 21:59:00.000000 [ProductState] {Main} InstallState (wow): currentOperation=UpToDate");
        check("synthetic: UpToDate but playable=0 -> UPDATING", BattleNetLauncherLog.Phase.UPDATING, synthetic.phase("wow"));
        synthetic.feed("D 2026-09-25 21:59:01.000000 [ProductState] {Main} InstallState (wow): playable=1");
        check("synthetic: playable while still downloading", BattleNetLauncherLog.Phase.UPDATING, synthetic.phase("wow"));
        synthetic.feed("D 2026-09-25 21:59:02.000000 [ProductState] {Main} InstallState (wow): currentStage=Complete");
        check("synthetic: ready", BattleNetLauncherLog.Phase.READY, synthetic.phase("wow"));
        check("synthetic: checking when uid unknown", BattleNetLauncherLog.Phase.CHECKING_UPDATES, synthetic.phase("wow_classic"));
    }

    private static void forwarderAndFields(File fixtures) throws Exception {
        BattleNetLauncherLog forwarder = new BattleNetLauncherLog();
        forwarder.feedAll(Files.readAllLines(new File(fixtures, "exec-forwarder.log").toPath()));
        check("forwarder: client log", true, forwarder.isClientLog());
        check("forwarder: not server", false, forwarder.isServerLog());
        check("forwarder: exec command line", "{[0]=--exec=launch WoW}", forwarder.getCommandLine());

        check("fields: split", "{a=1, installPath=C:/Program Files (x86)/X, b=two words}",
                BattleNetLauncherLog.parseFields(" a=1 installPath=C:/Program Files (x86)/X b=two words").toString());
        check("fields: empty", "{}", BattleNetLauncherLog.parseFields("").toString());
    }

    private static void logFiles(File fixtures) throws Exception {
        File dir = Files.createTempDirectory("wehavephones-launch-logs").toFile();
        copy(new File(fixtures, "update-then-ready.log"), new File(dir, "battle.net-20260925T215611.298231.log"));
        copy(new File(fixtures, "warm-exec-main.log"), new File(dir, "battle.net-20260926T015305.502543.log"));
        copy(new File(fixtures, "exec-forwarder.log"), new File(dir, "battle.net-20260926T015519.788644.log"));
        write(new File(dir, "libcef-20260926T015310.383490.log"), "noise\n");

        check("list: only battle.net logs, newest first", "battle.net-20260926T015519.788644.log",
                BattleNetLauncherLog.listLogs(dir).get(0).getName());
        check("list: count", 3, BattleNetLauncherLog.listLogs(dir).size());
        check("main log skips the exec forwarder", "battle.net-20260926T015305.502543.log",
                BattleNetLauncherLog.findMainLog(dir, "").getName());
        check("main log newer than marker", "battle.net-20260926T015305.502543.log",
                BattleNetLauncherLog.findMainLog(dir, "battle.net-20260925T215611.298231.log").getName());
        check("no main log newer than the forwarder", true,
                BattleNetLauncherLog.findMainLog(dir, "battle.net-20260926T015519.788644.log") == null);

        File fresh = new File(dir, "battle.net-20260926T020000.000000.log");
        write(fresh, "I 2026-09-26 02:00:00.1 [Main] {Main} Command line arguments: {[0]=--disable-gpu}\n");
        check("fresh log without IPC line is pending main", fresh.getName(),
                BattleNetLauncherLog.findMainLog(dir, "battle.net-20260926T015519.788644.log").getName());

        BattleNetLauncherLog.Tail tail = new BattleNetLauncherLog.Tail(fresh);
        check("tail: first poll", 1, tail.poll());
        append(fresh, "I 2026-09-26 02:00:00.2 [Main] {Main} Opening IPC shared memory. queueName=x mode=ser");
        check("tail: partial line not parsed", 0, tail.poll());
        append(fresh, "ver\r\nI 2026-09-26 02:00:30.0 [BNLogin] {Main} Logged into Battle.net successfully.\n");
        check("tail: completed lines parsed", 2, tail.poll());
        check("tail: server after completed line", true, tail.log.isServerLog());
        check("tail: logged in", true, tail.log.isLoggedIn());
        check("tail: no new data", 0, tail.poll());
        for (File file : dir.listFiles()) file.delete();
        dir.delete();
    }

    // ---- bat files + WinHandler ------------------------------------------------------------

    private static void batGeneration(String products, String runtime) throws Exception {
        WeHavePhonesLaunchPlan plan = WeHavePhonesLaunchPlan.parse(products, null);
        WeHavePhonesLaunchPlan.Product retail = plan.resolve("_retail_");
        check("exec bat", "@echo off\r\nrem WeHavePhones one-button launch (exec WoW)\r\ncd /d \"C:\\Program Files (x86)\\Battle.net\"\r\n"
                        + "Battle.net.exe --exec=\"launch WoW\"\r\n",
                WeHavePhonesLaunchPlan.execBat(WeHavePhonesLaunchPlan.BNET_DIR_X86, "WoW"));
        check("exec bat matches the proven C:\\wl.bat command", true,
                WeHavePhonesLaunchPlan.execBat(WeHavePhonesLaunchPlan.BNET_DIR_X86, "WoW").contains("Battle.net.exe --exec=\"launch WoW\"\r\n"));
        check("game bat", "@echo off\r\nrem WeHavePhones one-button launch (game wow_classic_era)\r\ncd /d \"C:\\Program Files (x86)\\Battle.net\"\r\n"
                        + "Battle.net.exe --game=wow_classic_era\r\n",
                WeHavePhonesLaunchPlan.gameBat(WeHavePhonesLaunchPlan.BNET_DIR_X86, "wow_classic_era"));
        check("direct bat", "@echo off\r\nrem WeHavePhones one-button launch (direct wow)\r\n"
                        + "cd /d \"C:\\Program Files (x86)\\World of Warcraft\\_retail_\"\r\n"
                        + "start \"\" \"Wow-ARM64.exe\" -launcherlogin -uid wow -d3d12\r\n",
                WeHavePhonesLaunchPlan.directBat(WeHavePhonesLaunchPlan.WOW_DIR_X86, retail, ""));
        check("direct bat null api arg == default", WeHavePhonesLaunchPlan.directBat(WeHavePhonesLaunchPlan.WOW_DIR_X86, retail, ""),
                WeHavePhonesLaunchPlan.directBat(WeHavePhonesLaunchPlan.WOW_DIR_X86, retail, "", null));
        check("direct bat device api arg", true,
                WeHavePhonesLaunchPlan.directBat(WeHavePhonesLaunchPlan.WOW_DIR_X86, retail, "", "-d3d11").endsWith("-uid wow -d3d11\r\n"));
        check("direct bat anniversary uid", true,
                WeHavePhonesLaunchPlan.directBat(WeHavePhonesLaunchPlan.WOW_DIR_X86, plan.resolve("_anniversary_"), "").contains("-uid wow_classic_anniversary -d3d12"));

        JSONObject runtimeJson = new JSONObject(runtime);
        String template = runtimeJson.getString("bnetBat");
        String flags = runtimeJson.getJSONObject("profiles").getJSONObject(runtimeJson.getString("singleStageProfile")).getString("bnetFlags");
        String bnetBat = template.replace("{flags}", " " + flags);
        WeHavePhonesLaunchPlan.Options options = new WeHavePhonesLaunchPlan.Options();
        check("start flags open the game's page", "--game=wow", WeHavePhonesLaunchPlan.startFlags(options, retail));
        check("start flags classic era", "--game=wow_classic_era", WeHavePhonesLaunchPlan.startFlags(options, plan.resolve("_classic_era_")));
        options.coldSelect = false;
        check("start flags without cold select", "", WeHavePhonesLaunchPlan.startFlags(options, retail));
        check("start bat without flags == bnet.bat", bnetBat, WeHavePhonesLaunchPlan.appendBnetFlags(bnetBat, WeHavePhonesLaunchPlan.startFlags(options, retail)));
        options.minimized = true;
        options.coldExec = true;
        String start = WeHavePhonesLaunchPlan.appendBnetFlags(bnetBat, WeHavePhonesLaunchPlan.startFlags(options, retail));
        check("start bat keeps dll copies", true, start.contains("copy /Y \"C:\\arm64-vkd3d.dll\""));
        check("start bat keeps the CEF network workaround and launch request", "Battle.net.exe --disable-gpu --enable-features=NetworkServiceInProcess2 --autostarted --exec=\"launch WoW\"", lastLine(start));
        check("open only selects the game page", "--game=wow_classic_era", WeHavePhonesLaunchPlan.openFlags("wow_classic_era"));
        check("open without a selection keeps the home page", "", WeHavePhonesLaunchPlan.openFlags(null));
        check("open rejects shell arguments", "", WeHavePhonesLaunchPlan.openFlags("wow & start Wow.exe"));
        check("Dev browser diagnostic keeps the game selection", "--game=wow_classic_era --js-flags=--jitless",
                WeHavePhonesLaunchPlan.openFlags("wow_classic_era", "jitless", true));
        check("Dev browser diagnostic can isolate the optimizing compiler", "--game=wow --js-flags=--no-opt",
                WeHavePhonesLaunchPlan.openFlags("wow", "no-opt", true));
        check("release ignores browser diagnostics", "--game=wow",
                WeHavePhonesLaunchPlan.openFlags("wow", "jitless", false));
        check("browser diagnostic rejects shell arguments", "--game=wow",
                WeHavePhonesLaunchPlan.openFlags("wow", "jitless & start Wow.exe", true));
        check("browser diagnostic without selection never requests a game launch", "--js-flags=--jitless",
                WeHavePhonesLaunchPlan.openFlags(null, "jitless", true));
        check("start bat CRLF", true, start.endsWith("\r\n") && !start.replace("\r\n", "").contains("\n"));
        check("append flags without Battle.net line", "@echo off\r\n", WeHavePhonesLaunchPlan.appendBnetFlags("@echo off\r\n", "--x"));
    }

    private static void winHandlerPackets() {
        String launch = WeHavePhonesLaunchPlan.winHandlerCommand(WeHavePhonesLaunchPlan.LAUNCH_BAT_GUEST);
        check("launch command", "C:\\windows\\wcrun.exe C:\\wclaunch.bat", launch);
        check("launch packet bytes", 48, WeHavePhonesLaunchPlan.packetBytes(launch));
        check("launch fits", true, WeHavePhonesLaunchPlan.fitsWinHandler(launch));
        check("start fits", true, WeHavePhonesLaunchPlan.fitsWinHandler(WeHavePhonesLaunchPlan.winHandlerCommand(WeHavePhonesLaunchPlan.START_BAT_GUEST)));
        String proven = "C:\\windows\\system32\\cmd.exe /c C:\\wl.bat";
        check("proven exec-replay command fits", true, WeHavePhonesLaunchPlan.fitsWinHandler(proven));
        String longName = WeHavePhonesLaunchPlan.winHandlerCommand("C:\\wehavephones-one-button-launch.bat");
        check("long bat name overflows the 64-byte packet", false, WeHavePhonesLaunchPlan.fitsWinHandler(longName));
        check("quoted filename rejected", false, WeHavePhonesLaunchPlan.fitsWinHandler("\"C:\\a b.exe\" x"));

        // Encode exactly like WinHandler.exec and compare with exec-replay.py's layout.
        byte[] packet = encodeLikeWinHandler(launch);
        ByteBuffer buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
        check("packet code EXEC", (byte)2, buffer.get());
        int total = buffer.getInt();
        int filenameLength = buffer.getInt();
        int parametersLength = buffer.getInt();
        check("packet filename length", 20, filenameLength);
        check("packet parameters length", 15, parametersLength);
        check("packet total", filenameLength + parametersLength + 8, total);
        byte[] filename = new byte[filenameLength];
        buffer.get(filename);
        byte[] parameters = new byte[parametersLength];
        buffer.get(parameters);
        check("packet filename", "C:\\windows\\wcrun.exe", new String(filename, StandardCharsets.UTF_8));
        check("packet parameters", "C:\\wclaunch.bat", new String(parameters, StandardCharsets.UTF_8));
        boolean overflow = false;
        try {
            encodeLikeWinHandler(longName);
        }
        catch (java.nio.BufferOverflowException e) {
            overflow = true;
        }
        check("WinHandler encoder overflows on the long name", true, overflow);
    }

    /** Mirror of WinHandler.exec's buffer writes (64-byte little-endian buffer). */
    private static byte[] encodeLikeWinHandler(String command) {
        String[] parts = command.trim().split(" ", 2);
        byte[] filename = parts[0].getBytes();
        byte[] parameters = parts.length > 1 ? parts[1].getBytes() : new byte[0];
        ByteBuffer data = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
        data.put((byte)2);
        data.putInt(filename.length + parameters.length + 8);
        data.putInt(filename.length);
        data.putInt(parameters.length);
        data.put(filename);
        data.put(parameters);
        return data.array();
    }

    // ---- Battle.net.config ----------------------------------------------------------------

    private static void firstLoginConfig() throws Exception {
        String installer = "D:\\Installer Folder\\Battle.net-Setup.exe";
        String flags = "--disable-gpu --enable-features=NetworkServiceInProcess2";
        String batch = WeHavePhonesLaunchPlan.installerBat(installer, true, flags);
        check("first installer: runtime flags forwarded with quoted installer path",
                "\"" + installer + "\" " + flags, lastLine(batch));
        check("first installer: empty profile flags add no arguments", "\"" + installer + "\"",
                lastLine(WeHavePhonesLaunchPlan.installerBat(installer, true, "")));
        check("first installer: absent profile flags add no arguments", "\"" + installer + "\"",
                lastLine(WeHavePhonesLaunchPlan.installerBat(installer, true, null)));
        check("first installer: graphics scoped to Battle.net", true,
                batch.contains("HKCU\\Software\\Wine\\AppDefaults\\Battle.net.exe\\X11 Driver"));
        check("first installer: direct GDI before automatic client launch", true,
                batch.contains("/v ClientSideGraphics /t REG_SZ /d N /f")
                        && batch.indexOf("reg.exe add") < batch.indexOf("\"" + installer + "\""));
        check("first installer: failed registry preparation blocks launch", true,
                batch.contains("if errorlevel 1 exit /b 1\r\n\"" + installer + "\""));
        check("existing install: runtime settings left alone", "@echo off\r\n\"" + installer + "\"\r\n",
                WeHavePhonesLaunchPlan.installerBat(installer, false, flags));
        File dir = Files.createTempDirectory("bnet-first-login-").toFile();
        File roaming = new File(dir, "Battle.net");
        File config = new File(roaming, WeHavePhonesBnetConfig.CONFIG_NAME);
        try {
            WeHavePhonesBnetConfig.prepareFirstLogin(roaming);
            String first = read(config);
            JSONObject parsed = new JSONObject(first);
            check("first login: software browser before installer launch", "false",
                    parsed.getJSONObject("Client").getString("HardwareAcceleration"));
            check("first login: automatic-login preference seeded", "true",
                    parsed.getJSONObject("Client").getString("AutoLogin"));
            check("first login: only browser and login preferences seeded", 2, parsed.getJSONObject("Client").length());
            WeHavePhonesBnetConfig.prepareFirstLogin(roaming);
            check("first login: repeat is unchanged", first, read(config));
            String existing = "{\r\n  \"Client\": {\"HardwareAcceleration\":\"true\",\"AutoLogin\":\"false\"},\r\n  \"Custom\":42\r\n}";
            Files.write(config.toPath(), existing.getBytes(StandardCharsets.UTF_8));
            WeHavePhonesBnetConfig.prepareFirstLogin(roaming);
            check("first login: existing config stays byte-for-byte unchanged", existing, read(config));
        }
        finally {
            config.delete();
            roaming.delete();
            dir.delete();
        }
    }

    private static void gameLaunchConfig(String base, String absent) throws Exception {
        String set = WeHavePhonesBnetConfig.setGameLaunchBehavior(base, "2");
        check("game launch: inserted parses", "2", new JSONObject(set).getJSONObject("Client").getString("GameLaunchWindowBehavior"));
        check("game launch: keeps other keys", "x", new JSONObject(set).getJSONObject("Client").getString("SavedAccountNames"));
        check("game launch: idempotent", set, WeHavePhonesBnetConfig.setGameLaunchBehavior(set, "2"));
        check("game launch: reads value", "2", WeHavePhonesBnetConfig.gameLaunchBehavior(set));
        check("game launch: replaced", "0", WeHavePhonesBnetConfig.gameLaunchBehavior(WeHavePhonesBnetConfig.setGameLaunchBehavior(set, "0")));
        check("game launch: removed (first key)", base, WeHavePhonesBnetConfig.setGameLaunchBehavior(set, null));
        String last = "{\n  \"Client\": {\n    \"AutoLogin\": \"true\",\n    \"GameLaunchWindowBehavior\": \"2\"\n  }\n}\n";
        check("game launch: removed (last key)", absent, WeHavePhonesBnetConfig.setGameLaunchBehavior(last, null));
        String middle = "{\"Client\": {\"A\": \"1\", \"GameLaunchWindowBehavior\": \"2\", \"B\": \"3\"}}";
        check("game launch: removed (middle key)", "{\"Client\": {\"A\": \"1\", \"B\": \"3\"}}", WeHavePhonesBnetConfig.setGameLaunchBehavior(middle, null));
        check("game launch: remove absent is a no-op", absent, WeHavePhonesBnetConfig.setGameLaunchBehavior(absent, null));
        String empty = "{\"Client\": {}}";
        check("game launch: empty Client parses", "2", new JSONObject(WeHavePhonesBnetConfig.setGameLaunchBehavior(empty, "2")).getJSONObject("Client").getString("GameLaunchWindowBehavior"));
        check("game launch: unrecognised -> null", true, WeHavePhonesBnetConfig.setGameLaunchBehavior("{}", "2") == null);

        File dir = Files.createTempDirectory("wehavephones-game-launch").toFile();
        File roaming = new File(dir, "roaming");
        File backups = new File(dir, "backups");
        roaming.mkdirs();
        File config = new File(roaming, WeHavePhonesBnetConfig.CONFIG_NAME);
        byte[] bom = {(byte)0xEF, (byte)0xBB, (byte)0xBF};
        byte[] original = (new String(bom, StandardCharsets.ISO_8859_1) + absent).getBytes(StandardCharsets.ISO_8859_1);
        Files.write(config.toPath(), original);
        check("game launch file: set", true, WeHavePhonesBnetConfig.applyExitOnGameLaunch(roaming, backups).contains("set to exit"));
        byte[] edited = Files.readAllBytes(config.toPath());
        check("game launch file: BOM kept", true, edited[0] == bom[0] && edited[1] == bom[1] && edited[2] == bom[2]);
        check("game launch file: value", "2", WeHavePhonesBnetConfig.gameLaunchBehavior(new String(edited, StandardCharsets.UTF_8)));
        File backup = new File(backups, WeHavePhonesBnetConfig.GAME_LAUNCH_BACKUP_NAME);
        check("game launch file: unset original saved", "<absent>", read(backup));
        check("game launch file: second call no-op", true, WeHavePhonesBnetConfig.applyExitOnGameLaunch(roaming, backups).contains("already"));
        check("game launch file: second call preserves bytes", true, java.util.Arrays.equals(edited, Files.readAllBytes(config.toPath())));
        Files.write(config.toPath(), WeHavePhonesBnetConfig.setGameLaunchBehavior(absent, "0").getBytes(StandardCharsets.UTF_8));
        WeHavePhonesBnetConfig.applyExitOnGameLaunch(roaming, backups);
        check("game launch file: always reapplies exit", "2", WeHavePhonesBnetConfig.gameLaunchBehavior(read(config)));
        check("game launch file: keeps first original backup", "<absent>", read(backup));
        backup.delete();
        Files.write(config.toPath(), WeHavePhonesBnetConfig.setGameLaunchBehavior(absent, "0").getBytes(StandardCharsets.UTF_8));
        WeHavePhonesBnetConfig.applyExitOnGameLaunch(roaming, backups);
        check("game launch file: existing user value saved", "0", read(backup));
        check("game launch file: existing user value changed to exit", "2", WeHavePhonesBnetConfig.gameLaunchBehavior(read(config)));
        check("game launch file: missing file", true, WeHavePhonesBnetConfig.applyExitOnGameLaunch(new File(dir, "none"), backups).contains("not found"));
        config.delete();
        roaming.delete();
        for (File file : backups.listFiles()) file.delete();
        backups.delete();
        dir.delete();
    }

    private static void bnetConfig() throws Exception {
        String base = "{\r\n    \"Client\": {\r\n        \"AutoStartMinimized\": \"false\",\r\n        \"SavedAccountNames\": \"x\"\r\n    }\r\n}\r\n";
        String on = WeHavePhonesBnetConfig.setAutoStartMinimized(base, true);
        check("config: false -> true", base.replace("\"false\"", "\"true\""), on);
        check("config: idempotent", on, WeHavePhonesBnetConfig.setAutoStartMinimized(on, true));
        check("config: reads true", true, WeHavePhonesBnetConfig.isAutoStartMinimized(on));
        String absent = "{\n  \"Client\": {\n    \"AutoLogin\": \"true\"\n  }\n}\n";
        String inserted = WeHavePhonesBnetConfig.setAutoStartMinimized(absent, true);
        check("config: inserted", true, inserted.contains("\"AutoStartMinimized\": \"true\",") && inserted.contains("\"AutoLogin\": \"true\""));
        check("config: inserted parses", "true", new JSONObject(inserted).getJSONObject("Client").getString("AutoStartMinimized"));
        check("config: unrecognised -> null", true, WeHavePhonesBnetConfig.setAutoStartMinimized("{}", true) == null);

        gameLaunchConfig(base, absent);

        File dir = Files.createTempDirectory("wehavephones-bnet-config").toFile();
        File roaming = new File(dir, "roaming");
        File backups = new File(dir, "backups");
        roaming.mkdirs();
        byte[] bom = {(byte)0xEF, (byte)0xBB, (byte)0xBF};
        byte[] original = concat(bom, base.getBytes(StandardCharsets.UTF_8));
        File config = new File(roaming, WeHavePhonesBnetConfig.CONFIG_NAME);
        Files.write(config.toPath(), original);
        String message = WeHavePhonesBnetConfig.ensureMinimized(roaming, backups);
        check("config file: changed", true, message.startsWith("AutoStartMinimized set to true"));
        byte[] changed = Files.readAllBytes(config.toPath());
        check("config file: BOM kept", true, changed[0] == bom[0] && changed[1] == bom[1] && changed[2] == bom[2]);
        check("config file: value", true, new String(changed, StandardCharsets.UTF_8).contains("\"AutoStartMinimized\": \"true\""));
        check("config file: message has no values", false, message.contains("SavedAccountNames"));
        check("config file: second call no-op", "AutoStartMinimized already true", WeHavePhonesBnetConfig.ensureMinimized(roaming, backups));
        check("config file: restore", true, WeHavePhonesBnetConfig.restore(roaming, backups));
        check("config file: restored bytes", true, java.util.Arrays.equals(original, Files.readAllBytes(config.toPath())));
        check("config file: restore without backup", false, WeHavePhonesBnetConfig.restore(roaming, backups));
        check("config file: missing file", true, WeHavePhonesBnetConfig.ensureMinimized(new File(dir, "none"), backups).contains("not found"));
        config.delete();
        roaming.delete();
        backups.delete();
        dir.delete();
    }

    private static void authenticationDiagnostics() {
        BattleNetLauncherLog log = new BattleNetLauncherLog();
        log.feed("I 2026-10-16 01:02:03.000000 [UnifiedAuth] UAuth: begin loading: https://account.battle.net/login/en/challenge/private-ticket?token=secret-value&email=private@example.invalid");
        log.feed("UAuth: browser state changed: Challenge");
        log.feed("UAuth: finished loading. statusCode=403 state=Challenge");
        log.feed("UAuth: browser error: errorCode=-101 private-ticket");
        log.feed("[BNLogin] {Main} Login failed. BLZBNTBGS80000023 private@example.invalid");
        log.feed("UAuth: browser state changed: PrivateAccountName");
        log.feed("Command line arguments: --token secret-value");
        String summary = log.authenticationSummary();
        check("auth diagnostics: categorizes checkpoint", true, summary.contains("page=challenge"));
        check("auth diagnostics: preserves HTTP failure", true, summary.contains("status=403 state=Challenge"));
        check("auth diagnostics: preserves numeric browser error", true, summary.contains("browser-error=-101"));
        check("auth diagnostics: preserves login failure code", true, summary.contains("login-failed") && summary.contains("BLZBNTBGS80000023"));
        check("auth diagnostics: unknown state is allowlisted", true, summary.contains("browser-state=other"));
        for (String secret : new String[]{"https://", "private-ticket", "secret-value", "private@example.invalid", "PrivateAccountName"}) {
            check("auth diagnostics: excludes private input " + secret, false, summary.contains(secret));
        }
        log.feed("Logged into Battle.net successfully.");
        check("auth diagnostics: successful recovery", true, log.authenticationSummary().contains("Signed in: yes"));
        for (int i = 0; i < 100; i++) log.feed("UAuth: browser error: errorCode=" + i);
        check("auth diagnostics: bounded event history", 34, log.authenticationSummary().split("\n").length);
    }

    // ---- Battle.net login form -------------------------------------------------------------

    /** Sequences from the S23's launcher logs (2026-09-28), sanitized. */
    private static void loginForm() {
        BattleNetLauncherLog cached = new BattleNetLauncherLog();
        cached.feed("I 2026-09-26 03:12:49.830536 [UnifiedAuth] {Main} UAuth: setting url: https://account.battle.net/login/en/login.app?app=app reason: reset");
        check("login form: cached token has none", BattleNetLauncherLog.LoginForm.NONE, cached.loginForm());
        cached.feed("I 2026-09-26 03:12:53.204131 [BNLogin] {Main} Logged into Battle.net successfully. |bnet=1:0:0|game=2:0:0");
        check("login form: signed in", BattleNetLauncherLog.LoginForm.NONE, cached.loginForm());

        BattleNetLauncherLog challenge = new BattleNetLauncherLog();
        challenge.feed("D 2026-09-26 02:53:49.862826 [UnifiedAuth] {} UAuth: browser state changed: Challenge");
        challenge.feed("D 2026-09-26 02:53:50.697822 [UnifiedAuth] {Main} UAuth: begin loading: https://us.account.battle.net/login/en/challenge/x?app&ref=y");
        challenge.feed("D 2026-08-25 08:56:17.534532 [UnifiedAuth] {Main} UAuth: finished loading. statusCode=200 state=Challenge");
        check("login form: token checkpoint is not a form", BattleNetLauncherLog.LoginForm.NONE, challenge.loginForm());

        BattleNetLauncherLog log = new BattleNetLauncherLog();
        log.feed("W 2026-09-28 03:19:48.070944 [LoginController] {Main} Tassadar token rejected by BGS: web_auth_url");
        log.feed("E 2026-09-28 03:19:49.405240 [BNLogin] {Main} Login failed. error=ERROR_TOKEN_NOT_FOUND (49)");
        log.feed("D 2026-09-28 03:19:56.239942 [UnifiedAuth] {} UAuth: browser state changed: LoginCredential");
        log.feed("D 2026-09-28 03:19:58.522907 [UnifiedAuth] {Main} UAuth: begin loading: https://account.battle.net/login/en/password?app=app");
        check("login form: loading is not shown yet", BattleNetLauncherLog.LoginForm.NONE, log.loginForm());
        log.feed("D 2026-09-28 03:20:18.138034 [UnifiedAuth] {Main} UAuth: finished loading. statusCode=200 state=LoginCredential");
        check("login form: password page", BattleNetLauncherLog.LoginForm.PASSWORD, log.loginForm());
        check("login form: phase", BattleNetLauncherLog.Phase.SIGNING_IN, log.phase("wow"));
        check("login form: one load", 1, log.getLoginFormLoads());
        log.feed("D 2026-09-28 03:20:25.000000 [UnifiedAuth] {Main} UAuth: status changed: RequestingToken");
        log.feed("D 2026-09-28 03:20:25.100000 [UnifiedAuth] {} UAuth: browser state changed: Done");
        check("login form: submitted", BattleNetLauncherLog.LoginForm.NONE, log.loginForm());
        log.feed("D 2026-09-28 03:20:30.000000 [UnifiedAuth] {Main} UAuth: begin loading: https://account.battle.net/login/en/password?app=app");
        log.feed("D 2026-09-28 03:20:31.000000 [UnifiedAuth] {Main} UAuth: finished loading. statusCode=200 state=LoginCredential");
        check("login form: shown again counts again", 2, log.getLoginFormLoads());
        log.feed("E 2026-09-28 03:20:32.000000 [BNLogin] {Main} Login failed. error=ERROR_TOKEN_NOT_FOUND (49)");
        check("login form: still shown after a failed login", BattleNetLauncherLog.LoginForm.PASSWORD, log.loginForm());
        log.feed("D 2026-09-28 04:00:21.103844 [UnifiedAuth] {Main} UAuth: web login complete, beginning connection.");
        log.feed("D 2026-09-28 04:00:26.916624 [UnifiedAuth] {Main} UAuth: begin loading: https://account.battle.net/login/en/password?app=app");
        log.feed("D 2026-09-28 04:00:27.000000 [UnifiedAuth] {Main} UAuth: finished loading. statusCode=200 state=LoginCredential");
        check("login form: background reload while connecting", BattleNetLauncherLog.LoginForm.NONE, log.loginForm());
        log.feed("E 2026-09-28 04:00:28.000000 [BNLogin] {Main} Login failed. error=ERROR_TOKEN_NOT_FOUND (49)");
        check("login form: back after the connection fails", BattleNetLauncherLog.LoginForm.PASSWORD, log.loginForm());
        log.feed("I 2026-09-28 03:20:40.000000 [BNLogin] {Main} Logged into Battle.net successfully. |bnet=1:0:0|game=2:0:0");
        check("login form: gone after sign-in", BattleNetLauncherLog.LoginForm.NONE, log.loginForm());

        // S23 2026-10-08: Log Out from Battle.net's menu, then its password page.
        log.feed("I 2026-10-08 22:50:48.621516 [BNLogin] {Main} Logout");
        check("logout: signed out", false, log.isLoggedIn());
        check("logout: no form before it loads", BattleNetLauncherLog.LoginForm.NONE, log.loginForm());
        log.feed("I 2026-10-08 22:50:49.337990 [UnifiedAuth] {Main} UAuth: setting url: https://account.battle.net/login/en/login.app");
        log.feed("D 2026-10-08 22:50:50.619866 [UnifiedAuth] {} UAuth: browser state changed: LoginCredential");
        log.feed("D 2026-10-08 22:50:52.285293 [UnifiedAuth] {Main} UAuth: begin loading: https://account.battle.net/login/en/password?app=app");
        log.feed("D 2026-10-08 22:51:00.484343 [UnifiedAuth] {Main} UAuth: finished loading. statusCode=200 state=LoginCredential");
        check("logout: password page", BattleNetLauncherLog.LoginForm.PASSWORD, log.loginForm());
        check("logout: phase", BattleNetLauncherLog.Phase.SIGNING_IN, log.phase("wow"));

        BattleNetLauncherLog email = new BattleNetLauncherLog();
        email.feed("D 2026-09-28 03:22:05.863099 [UnifiedAuth] {Main} UAuth: begin loading: https://account.battle.net/login/en/login.app?app=app");
        email.feed("D 2026-09-28 03:22:18.105201 [UnifiedAuth] {Main} UAuth: finished loading. statusCode=200 state=LoginCredential");
        check("login form: email page", BattleNetLauncherLog.LoginForm.EMAIL, email.loginForm());
        email.feed("D 2026-09-28 03:22:30.000000 [UnifiedAuth] {Main} UAuth: finished loading. statusCode=500 state=LoginCredential");
        check("login form: error page is not a form", BattleNetLauncherLog.LoginForm.NONE, email.loginForm());

        BattleNetLauncherLog fresh = new BattleNetLauncherLog();
        fresh.feed("UAuth: begin loading: https://account.battle.net/login/en/login.app?app=app");
        check("fresh email: waits for load", BattleNetLauncherLog.LoginForm.NONE, fresh.loginForm());
        fresh.feed("UAuth: finished loading. statusCode=200 state=Login");
        check("fresh email: Login state is the email form", BattleNetLauncherLog.LoginForm.EMAIL, fresh.loginForm());
        fresh.feed("UAuth: browser state changed: Done");
        check("fresh email: submission clears the form", BattleNetLauncherLog.LoginForm.NONE, fresh.loginForm());
        fresh.feed("UAuth: begin loading: https://account.battle.net/login/en/challenge/x");
        fresh.feed("UAuth: finished loading. statusCode=200 state=Login");
        check("fresh email: challenge is not an email field", BattleNetLauncherLog.LoginForm.NONE, fresh.loginForm());
        fresh.feed("UAuth: begin loading: https://example.invalid/login/en/login.app");
        fresh.feed("UAuth: finished loading. statusCode=200 state=Login");
        check("fresh email: unrelated host is not an email field", BattleNetLauncherLog.LoginForm.NONE, fresh.loginForm());
        fresh.feed("UAuth: begin loading: https://account.battle.net/login/en/?app=app");
        fresh.feed("UAuth: finished loading. statusCode=200 state=Login");
        check("switch account: locale root is the email form", BattleNetLauncherLog.LoginForm.EMAIL, fresh.loginForm());
        fresh.feed("UAuth: begin loading: https://account.battle.net/login/en/challenge/");
        fresh.feed("UAuth: finished loading. statusCode=200 state=Login");
        check("switch account: nested challenge path stays excluded", BattleNetLauncherLog.LoginForm.NONE, fresh.loginForm());
        fresh.feed("UAuth: begin loading: https://example.invalid/login/en/");
        fresh.feed("UAuth: finished loading. statusCode=200 state=Login");
        check("switch account: unrelated locale root stays excluded", BattleNetLauncherLog.LoginForm.NONE, fresh.loginForm());

        BattleNetLauncherLog china = new BattleNetLauncherLog();
        china.feed("UAuth: begin loading: https://account.battlenet.com.cn/login/zh-cn/?app=app");
        china.feed("UAuth: finished loading. statusCode=200 state=Login");
        // China's login.app is a NetEase account gate (terms checkbox and buttons), not an email field.
        check("China: NetEase gate page is not an email field", BattleNetLauncherLog.LoginForm.NONE, china.loginForm());
    }

    // ---- helpers ------------------------------------------------------------------------

    private static String describe(List<WeHavePhonesLaunchPlan.Attempt> attempts) {
        ArrayList<String> names = new ArrayList<>();
        for (WeHavePhonesLaunchPlan.Attempt attempt : attempts) names.add(attempt.describe());
        return String.join(" -> ", names);
    }

    // ---- CurseForge app in the container -------------------------------------------------

    private static final String CF_SHA = "+5P7TW9HwQICBl4QioxDkIQKrd8A3j5+DLAV8WrhGQ/izH9Q6Nr+0v59Wu2KVcGnkvykNsDTbX+Y1bTl3+i7ig==";

    private static String cfFeed(String url, String sha, String size) {
        return "releaseName: CurseForge Windows\nstagingPercentage: 10\nfiles:\n"
                + "  - url: " + url + "\n    sha512: " + sha + "\n    size: " + size + "\n"
                + "    blockMapSize: 133116\n    IsAdminRightsRequired: false\n"
                + "  - url: https://appsdl.overwolf.com/prod/apps/other/setup2.exe\n    sha512: x\n    size: 1\n"
                + "version: 1.324.0-41180\nreleaseDate: 2026-10-15T07:36:11.167Z\n"
                + "releaseNotes: >\n  - url: https://evil.example/notes\n  size: 5\n";
    }

    private static void curseForgeFeed() {
        String url = "https://appsdl.overwolf.com/prod/apps/cfiahnpaolfnlgaihhmobmnjdafknjnjdpdabpcm/1.324.0-41180/setup.exe";
        AddonApp.Release release = AddonApp.CURSEFORGE.parseFeed(cfFeed(url, CF_SHA, "126470712"));
        check("cf feed parsed", true, release != null);
        if (release != null) {
            check("cf feed version", "1.324.0-41180", release.version);
            check("cf feed first file url", url, release.url);
            check("cf feed sha512", CF_SHA, release.sha512);
            check("cf feed size", 126470712L, release.size);
        }
        check("cf feed rejects http", null, AddonApp.CURSEFORGE.parseFeed(cfFeed(url.replace("https:", "http:"), CF_SHA, "1")));
        check("cf feed rejects other host", null,
                AddonApp.CURSEFORGE.parseFeed(cfFeed("https://appsdl.overwolf.com.evil.example/setup.exe", CF_SHA, "1")));
        check("cf feed rejects bad sha", null, AddonApp.CURSEFORGE.parseFeed(cfFeed(url, "abc", "1")));
        check("cf feed rejects bad size", null, AddonApp.CURSEFORGE.parseFeed(cfFeed(url, CF_SHA, "big")));
        check("cf feed rejects empty", null, AddonApp.CURSEFORGE.parseFeed(""));
        check("cf update skips unknown install", false, AddonApp.needsUpdate(null, release));
        check("cf update skips same version", false, AddonApp.needsUpdate("1.324.0-41180\r\n", release));
        check("cf update on new version", true, AddonApp.needsUpdate("1.323.0-1", release));
    }

    private static void curseForgeBat() {
        String launch = AddonApp.CURSEFORGE.bat(null);
        String install = AddonApp.CURSEFORGE.bat("1.324.0-41180");
        check("cf launch restores PowerShell first", true,
                launch.indexOf("if exist \"%PS64%.wehavephones\"") < launch.indexOf(":launch"));
        check("cf launch runs the app", "\"%APP%\\CurseForge.exe\" --no-sandbox --disable-gpu", lastLine(launch));
        check("cf launch disables W^X for the agent", true, launch.contains("set DOTNET_EnableWriteXorExecute=0\r\n"));
        check("cf launch copies hybrid dxgi", true,
                launch.contains("copy /y \"%STAGE%\\dxgi.dll\" \"%APP%\\dxgi.dll\""));
        check("cf launch writes no version", false, launch.contains("installed-version.txt"));
        check("cf install hides PowerShell around setup", true,
                install.indexOf("move /y \"%PS64%\" \"%PS64%.wehavephones\"") < install.indexOf("CurseForge-Setup.exe\" /S")
                && install.indexOf("CurseForge-Setup.exe\" /S") < install.lastIndexOf("move /y \"%PS64%.wehavephones\" \"%PS64%\""));
        check("cf install stops on failure", true, install.contains("if not \"%RESULT%\"==\"0\" exit /b %RESULT%"));
        check("cf install records version", true,
                install.contains(">\"%STAGE%\\installed-version.txt\" echo 1.324.0-41180\r\n"));
        check("cf bat uses CRLF only", false, install.replace("\r\n", "").contains("\n"));
    }

    private static void curseForgeSettings() {
        String existing = "{\"session-counter\":3,\"general-settings\":"
                + "\"{\\\"runOnStartup\\\":true,\\\"closeCurseForgeAction\\\":0,\\\"dateFormat\\\":null}\"}";
        JSONObject root = new JSONObject(AddonApp.CURSEFORGE.configureSettings(existing));
        JSONObject general = new JSONObject(root.getString("general-settings"));
        check("cf settings exit on close", 1, general.getInt("closeCurseForgeAction"));
        check("cf settings no startup", false, general.getBoolean("runOnStartup"));
        check("cf settings keep other general keys", true, general.has("dateFormat"));
        check("cf settings keep other sections", 3, root.getInt("session-counter"));
        JSONObject fresh = new JSONObject(new JSONObject(AddonApp.CURSEFORGE.configureSettings(null)).getString("general-settings"));
        check("cf settings fresh exit on close", 1, fresh.getInt("closeCurseForgeAction"));
        JSONObject broken = new JSONObject(new JSONObject(AddonApp.CURSEFORGE.configureSettings("{not json")).getString("general-settings"));
        check("cf settings replace unreadable", false, broken.getBoolean("runOnStartup"));
        check("cf bat removes startup entry", true, AddonApp.CURSEFORGE.bat(null).contains(
                "reg.exe delete \"HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run\" /v com.overwolf.curseforge /f"));
    }

    private static void wowUp() {
        String sha = "ZJARrxK5H5kk/OFFdYzr09mLqJyKtGj2lGlVR2I03VuE5nA+Eja+1j3rfenShi0f/t984CFlx6C5v8Oh4CrgtQ==";
        String feed = "version: 2.24.0\nfiles:\n  - url: WowUp-Setup-2.24.0.exe\n    sha512: " + sha
                + "\n    size: 117996928\npath: WowUp-Setup-2.24.0.exe\nsha512: " + sha
                + "\nreleaseDate: '2026-10-10T20:55:53.843Z'\n";
        AddonApp.Release release = AddonApp.WOWUP.parseFeed(feed);
        check("wowup feed parsed", true, release != null);
        if (release != null) {
            check("wowup asset pinned to its tag",
                    "https://github.com/WowUp/WowUp/releases/download/v2.24.0/WowUp-Setup-2.24.0.exe", release.url);
            check("wowup size", 117996928L, release.size);
        }
        check("wowup rejects path in asset name", null,
                AddonApp.WOWUP.parseFeed(feed.replace("url: WowUp-Setup", "url: ../WowUp-Setup")));
        check("wowup rejects absolute other host", null,
                AddonApp.WOWUP.parseFeed(feed.replace("url: WowUp-Setup-2.24.0.exe", "url: https://evil.example/a.exe")));
        check("curseforge rejects relative asset", null,
                AddonApp.CURSEFORGE.parseFeed(feed));
        String bat = AddonApp.WOWUP.bat("2.24.0");
        check("wowup installs its own setup", true, bat.contains("\"%STAGE%\\WowUp-Setup.exe\" /S\r\n"));
        check("wowup stage dir", true, bat.contains("set \"STAGE=C:\\windows\\wehavephones-wowup\""));
        check("wowup app dir", true, bat.contains("set \"APP=%LOCALAPPDATA%\\Programs\\wowup\""));
        check("wowup runs the app", "\"%APP%\\WowUp.exe\" --no-sandbox --disable-gpu", lastLine(bat));
        String patch = "set ELECTRON_RUN_AS_NODE=1\r\n\"%APP%\\WowUp.exe\" \"%STAGE%\\container-fixes.js\" \"%APP%\\resources\\app.asar\"\r\n"
                + "set ELECTRON_RUN_AS_NODE=\r\n";
        check("wowup removes its tray before starting", true,
                bat.contains(patch) && bat.indexOf(patch) < bat.lastIndexOf("--no-sandbox"));
        check("wowup patch runs after install", true, bat.indexOf(patch) > bat.indexOf(":launch"));
        String fixes = AddonApp.WOWUP_CONTAINER_FIXES_JS;
        check("wowup fixes script is Node", true, fixes.startsWith("process.noAsar = true;\n"));
        check("wowup fixes cover tray, ARM64 names and drive C", true,
                fixes.contains("function createTray(window, config) {") && fixes.contains("class WarcraftPlatformWin {")
                && fixes.contains("getBlizzardAgentPath() {"));
        check("curseforge has no patch", false, AddonApp.CURSEFORGE.bat(null).contains("ELECTRON_RUN_AS_NODE"));
        check("wowup skips the .NET setting", false, bat.contains("DOTNET_"));
        check("wowup skips the startup entry", false, bat.contains("CurrentVersion\\Run"));
        check("wowup bat name", "wehavephones-wowup.bat", AddonApp.WOWUP.batName());
        JSONObject prefs = new JSONObject(AddonApp.WOWUP.configureSettings(
                "{\"collapse_to_tray\":\"true\",\"current_theme\":\"default-theme\"}"));
        check("wowup exits on close", "false", prefs.getString("collapse_to_tray"));
        check("wowup software rendering", "false", prefs.getString("use_hardware_acceleration"));
        check("wowup keeps other prefs", "default-theme", prefs.getString("current_theme"));
        check("curseforge names unchanged", "wehavephones-curseforge.bat CurseForge-Setup.exe Programs/CurseForge",
                AddonApp.CURSEFORGE.batName() + " " + AddonApp.CURSEFORGE.installerName() + " " + AddonApp.CURSEFORGE.appDir());
    }

    private static String lastLine(String bat) {
        String[] lines = bat.trim().split("\r\n");
        return lines[lines.length - 1];
    }

    private static void check(String name, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name + ": expected <" + expected + "> got <" + actual + ">");
        }
        else {
            System.out.println("ok   " + name);
        }
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void copy(File from, File to) throws Exception {
        Files.copy(from.toPath(), to.toPath());
    }

    private static void write(File file, String text) throws Exception {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static void append(File file, String text) throws Exception {
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] result = new byte[first.length + second.length];
        System.arraycopy(first, 0, result, 0, first.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
    }
}
