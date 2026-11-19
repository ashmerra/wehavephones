package com.wehavephones.mobile;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

/**
 * Host test for driving Blizzard's Agent: request bodies, HTTP framing
 * (Host header with the Agent's port), progress and game answers recorded on
 * a Galaxy S23 (fixtures/agent-*.json), the version check against
 * .build.info and the game accounts read from Battle.net's log.
 */
public final class BattleNetAgentSelfTest {
    private static int checks;
    private static int failures;
    private static File fixtures;

    public static void main(String[] args) throws Exception {
        fixtures = new File(args.length > 0 ? args[0] : "tools/wehavephones-launch-selftest/fixtures");
        requests();
        framing();
        progress();
        game();
        formatting();
        updateCheck();
        accounts();
        pad();
        System.out.println("[bnet-agent-selftest] " + checks + " checks, " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    static void requests() throws Exception {
        JSONObject install = BattleNetAgentApi.installRequest("wow_classic_era", "wow_classic_era", "eu");
        check("install patch url", "http://eu.patch.battle.net:1119/wow_classic_era", install.getString("instructions_patch_url"));
        check("install product", "NGDP", install.getString("instructions_product"));
        check("install priority", 900, install.getJSONObject("priority").getInt("value"));
        check("china patch host", "cn.patch.battlenet.com.cn", BattleNetAgentApi.patchHost("cn"));
        check("bad region falls back", "us.patch.battle.net", BattleNetAgentApi.patchHost("../x"));
        JSONObject form = BattleNetAgentApi.installForm("C:/Program Files (x86)/World of Warcraft", "deDE", "DEU", "DE");
        check("form finalized", true, form.getBoolean("finalized"));
        check("form locale as Battle.net writes it", "deDE", form.getString("selected_locale"));
        check("form language list", "deDE", form.getJSONArray("language").getString(0));
        check("form no shortcut", "none", form.getString("shortcut"));
        check("bad locale", "enUS", BattleNetAgentApi.agentLocale("x"));
        check("uninstall compaction", true, BattleNetAgentApi.uninstallRequest("wow", true).getBoolean("run_compaction"));
        check("update uid", "wowf", BattleNetAgentApi.updateRequest("wowf").getString("uid"));
        check("game dir slashes", "C:/Program Files (x86)/World of Warcraft",
                BattleNetAgentApi.gameDir("C:\\Program Files (x86)\\World of Warcraft\\"));
        String bat = BattleNetAgentApi.agentBat("C:\\ProgramData\\Battle.net\\Agent\\Agent.9886\\Agent.exe", 47001, "0123456789abcdef", 900);
        check("agent bat starts the agent alone", true, bat.contains("Agent.9886\\Agent.exe\" --internalclienttools\r\n"));
        check("agent bat starts the pipe", true, bat.contains("\"C:\\windows\\agentpipe.exe\" 47001 0123456789abcdef 900\r\n"));
    }

    static void framing() {
        String request = new String(BattleNetAgentApi.httpRequest("POST", "/update", 41385, "tok", "{\"uid\":\"wow\"}"), StandardCharsets.UTF_8);
        check("request line", true, request.startsWith("POST /update HTTP/1.1\r\n"));
        check("host keeps the agent port", true, request.contains("\r\nHost: 127.0.0.1:41385\r\n"));
        check("user agent", true, request.contains("\r\nUser-Agent: phoenix-agent/1.0\r\n"));
        check("authorization", true, request.contains("\r\nAuthorization: tok\r\n"));
        check("content length", true, request.contains("\r\nContent-Length: 13\r\n"));
        check("body last", true, request.endsWith("\r\n\r\n{\"uid\":\"wow\"}"));
        String bare = new String(BattleNetAgentApi.httpRequest("GET", "/agent", 1120, null, null), StandardCharsets.UTF_8);
        check("no token header", false, bare.contains("Authorization"));
        BattleNetAgentApi.Response response = BattleNetAgentApi.parseResponse(
                "HTTP/1.1 201 Created\r\nContent-Length: 11\r\n\r\n{\"a\":1}xxxxextra".getBytes(StandardCharsets.UTF_8));
        check("status", 201, response.status);
        check("body by length", "{\"a\":1}xxxx", response.body);
        check("401", 401, BattleNetAgentApi.parseResponse("HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\n\r\n"
                .getBytes(StandardCharsets.UTF_8)).status);
        check("garbage", 0, BattleNetAgentApi.parseResponse("nope".getBytes(StandardCharsets.UTF_8)).status);
        check("port", 41385, BattleNetAgentApi.parsePort("41385\n"));
        check("bad port", -1, BattleNetAgentApi.parsePort("abc"));
        check("token", "X", BattleNetAgentApi.parseToken(new JSONObject().put("authorization", "X")));
    }

    static void progress() throws Exception {
        BattleNetAgentApi.Progress download = BattleNetAgentApi.parseProgress(fixture("agent-progress-download.json"));
        check("download step", BattleNetAgentApi.Step.DOWNLOADING, download.step());
        check("download total from array", true, download.downloadTotal > 100_000_000L);
        check("download fraction", true, download.fraction() > 0 && download.fraction() < 0.1);
        check("download eta from rate", true, download.etaSeconds > 0);
        BattleNetAgentApi.Progress apply = BattleNetAgentApi.parseProgress(fixture("agent-progress-apply.json"));
        check("apply step", BattleNetAgentApi.Step.APPLYING, apply.step());
        check("apply phase", BattleNetAgentApi.PHASE_APPLY, apply.phase);
        JSONObject writingJson = fixture("agent-progress-download.json");
        writingJson.put("download_current", new org.json.JSONArray().put(writingJson.getJSONArray("download_total").getLong(0)));
        writingJson.getJSONObject("extended_status").put("current", 113643324L).put("total", 227286648L);
        BattleNetAgentApi.Progress writing = BattleNetAgentApi.parseProgress(writingJson);
        check("downloaded but still writing", BattleNetAgentApi.Step.WRITING, writing.step());
        check("writing fraction from bytes written", 0.5, writing.fraction());
        check("apply is the middle third", true, apply.installFraction() > 0.6 && apply.installFraction() <= 0.67);
        BattleNetAgentApi.Progress finishing = BattleNetAgentApi.parseProgress(new JSONObject()
                .put("state", 1003).put("progress", 1.0).put("download_total", new org.json.JSONArray().put(10)).put("download_current", new org.json.JSONArray().put(10))
                .put("extended_status", new JSONObject().put("state", 1000).put("unit_type", "bytes").put("current", 5).put("total", 10)));
        check("finishing", BattleNetAgentApi.Step.FINISHING, finishing.step());
        check("finishing fraction", 0.5, finishing.fraction());
        BattleNetAgentApi.Progress done = BattleNetAgentApi.parseProgress(fixture("agent-progress-done.json"));
        check("done", true, done.done());
        check("done step", BattleNetAgentApi.Step.DONE, done.step());
        check("done fraction", 1.0, done.fraction());
        BattleNetAgentApi.Progress failed = BattleNetAgentApi.parseProgress(fixture("agent-progress-5016.json"));
        check("5016 failed", true, failed.failed());
        check("5016 code", BattleNetAgentApi.ERROR_TACT_INIT, failed.error);
        check("5016 message", "Failed to initialize the tact instance", failed.errorMessage);
        check("5016 not done", false, failed.done());
        BattleNetAgentApi.Progress queued = BattleNetAgentApi.parseProgress(new JSONObject().put("state", 1008));
        check("queued", BattleNetAgentApi.Step.QUEUED, queued.step());
        check("queued not started", false, queued.started());
        check("empty", BattleNetAgentApi.Step.PREPARING, BattleNetAgentApi.parseProgress(null).step());
    }

    static void game() throws Exception {
        BattleNetAgentApi.GameState g = BattleNetAgentApi.parseGame("wow_classic_era", fixture("agent-game.json"));
        check("installed", true, g.installed);
        check("playable", true, g.playable);
        check("version", "1.15.9.70576", g.localVersion);
        check("subpath", "_classic_era_", g.subpath);
        check("latest for selected region", "1.15.9.70576", g.latestVersion);
        check("up to date", false, g.updateAvailable());
        check("languages", true, g.supportedLanguages.contains("deDE") && g.textLanguages.size() == 1);
        JSONObject older = fixture("agent-game.json").put("local_version", "1.15.8.1");
        check("update available", true, BattleNetAgentApi.parseGame("wow_classic_era", older).updateAvailable());
        List<String> list = BattleNetAgentApi.parseGameList(new JSONObject("{\"wow\":{\"link\":\"/game/wow\"},\"wowf\":{}}"));
        check("game list", 2, list.size());
    }

    static void formatting() {
        check("gb", "1.2 GB", BattleNetAgentApi.formatBytes(1_234_000_000L));
        check("mb", "126 MB", BattleNetAgentApi.formatBytes(125_939_400L));
        check("rate", "8.3 MB/s", BattleNetAgentApi.formatRate(8_300_000L));
        check("eta min", "9 min", BattleNetAgentApi.formatEta(530));
        check("eta hours", "1 h 05 min", BattleNetAgentApi.formatEta(3900));
        check("eta none", "", BattleNetAgentApi.formatEta(-1));
    }

    static void updateCheck() {
        String buildInfo = "Branch!STRING:0|Active!DEC:1|Build Key!HEX:16|Version!STRING:0|Product!STRING:0\n"
                + "us|1|5604ce35a8644bdcf0e26c1daf979c15|1.15.9.70576|wow_classic_era\n"
                + "eu|1|aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|12.0.1.1|wow\n";
        String versions = "Region!STRING:0|BuildConfig!HEX:16|CDNConfig!HEX:16|KeyRing!HEX:16|BuildId!DEC:4|VersionsName!String:0|ProductConfig!HEX:16\n"
                + "## seqn = 4146499\n"
                + "us|5604ce35a8644bdcf0e26c1daf979c15|bd519aabe3832b484a0a4064382ebe90||70576|1.15.9.70576|c9934edfc8f217a2e01c47e4deae8454\n"
                + "eu|bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb|bd519aabe3832b484a0a4064382ebe90||70600|1.15.9.70600|c9934edfc8f217a2e01c47e4deae8454\n";
        BattleNetUpdateCheck.Result same = BattleNetUpdateCheck.compare("wow_classic_era", buildInfo, versions);
        check("same build", false, same != null && same.updateAvailable);
        String eu = buildInfo.replace("us|1|5604", "eu|1|5604");
        BattleNetUpdateCheck.Result newer = BattleNetUpdateCheck.compare("wow_classic_era", eu, versions);
        check("newer build in the install's region", true, newer != null && newer.updateAvailable);
        check("latest version", "1.15.9.70600", newer != null ? newer.latestVersion : "");
        check("missing product", null, BattleNetUpdateCheck.compare("wowf", buildInfo, versions));
        check("versions url", "https://eu.version.battle.net/v2/products/wow/versions", BattleNetUpdateCheck.versionsUrl("eu", "wow"));
        check("china versions url", "https://cn.version.battlenet.com.cn/v2/products/wow/versions", BattleNetUpdateCheck.versionsUrl("cn", "wow"));
    }

    static void accounts() {
        String log = "I 2026 [GameController] {Main} GameAccount added to family: family=WoW account=GameAccount(WoW:wow_classic_era_ptr region=XX)\n"
                + "I 2026 [GameController] {Main} GameAccount added to family: family=WoW account=GameAccount(WoW:wow_classic_era region=US entity=2:1:2 name=WoW1)\n"
                + "I 2026 [GameController] {Main} GameAccount added to family: family=WoW account=GameAccount(WoW:retail region=US entity=2:1:2 name=WoW1)\n"
                + "I 2026 [GameController] {Main} GameAccount added to family: family=WoW account=GameAccount(WoW:retail region=EU entity=2:1:3 name=WoW2)\n"
                + "I 2026 [GameController] {Main} GameAccount added to family: family=WoW account=GameAccount(WoW:retail region=US entity=2:1:2 name=WoW1)\n";
        Map<String, List<BattleNetGameAccounts.Account>> all = BattleNetGameAccounts.parse(log);
        check("unbound ptr skipped", false, all.containsKey("wow_classic_era_ptr"));
        check("era account", "WoW1", all.get("wow_classic_era").get(0).name);
        check("retail deduplicated", 2, all.get("retail").size());
        check("retail second region", "EU", all.get("retail").get(1).region);
        check("retail key", "retail", BattleNetGameAccounts.key("wow"));
        check("other key", "wowf", BattleNetGameAccounts.key("wowf"));
    }

    static void pad() {
        final StringBuilder log = new StringBuilder();
        final int[] moved = {0, 0};
        final int[] scrolled = {0};
        BattleNetPadInput.Sink sink = new BattleNetPadInput.Sink() {
            public void key(int key, boolean down) { log.append(down ? "+k" : "-k").append(key).append(' '); }
            public void button(int button, boolean down) { log.append(down ? "+b" : "-b").append(button).append(' '); }
            public void move(int dx, int dy) { moved[0] += dx; moved[1] += dy; }
            public void scroll(int steps) { scrolled[0] += steps; }
            public void keyboard() { log.append("kbd "); }
            public void pointerMode(boolean on) { log.append(on ? "ptr+ " : "ptr- "); }
        };
        BattleNetPadInput pad = new BattleNetPadInput();
        BattleNetPadInput.State s = new BattleNetPadInput.State();
        // Normal mode: Battle.net's own navigation keeps A, the d-pad and the sticks.
        s.buttons = BattleNetPadInput.A | BattleNetPadInput.DOWN;
        s.leftX = 1f;
        pad.onState(s, 0, sink);
        check("normal mode leaves A and the d-pad to Battle.net", "", log.toString());
        check("normal mode does not move the pointer", false, pad.moving());
        s.buttons = BattleNetPadInput.Y;
        s.leftX = 0;
        pad.onState(s, 10, sink);
        check("Y opens the keyboard in normal mode", "kbd ", log.toString());
        s.buttons = 0;
        pad.onState(s, 20, sink);
        log.setLength(0);
        // View turns pointer mode on.
        s.buttons = BattleNetPadInput.SELECT;
        pad.onState(s, 30, sink);
        check("View turns pointer mode on", "ptr+ ", log.toString());
        check("pointer mode flag", true, pad.pointerMode());
        s.buttons = 0;
        pad.onState(s, 40, sink);
        log.setLength(0);
        s.buttons = BattleNetPadInput.A;
        pad.onState(s, 50, sink);
        check("A presses the left button", "+b1 ", log.toString());
        pad.onState(s, 60, sink);
        check("held A sends nothing more", "+b1 ", log.toString());
        s.buttons = 0;
        pad.onState(s, 70, sink);
        check("release A", "+b1 -b1 ", log.toString());
        log.setLength(0);
        s.buttons = BattleNetPadInput.LB;
        pad.onState(s, 80, sink);
        s.buttons = 0;
        pad.onState(s, 90, sink);
        check("LB is Shift+Tab, released in reverse", "+k4 +k3 -k3 -k4 ", log.toString());
        log.setLength(0);
        s.buttons = BattleNetPadInput.B;
        pad.onState(s, 100, sink);
        s.buttons = 0;
        pad.onState(s, 110, sink);
        check("B is Escape", "+k2 -k2 ", log.toString());
        log.setLength(0);
        s.buttons = BattleNetPadInput.DOWN;
        pad.onState(s, 1000, sink);
        check("d-pad arrow", "+k6 ", log.toString());
        check("held arrow ticks", true, pad.moving());
        pad.tick(1100, 0.016f, 1280, sink);
        check("no repeat before the delay", "+k6 ", log.toString());
        pad.tick(1000 + BattleNetPadInput.REPEAT_FIRST_MS, 0.016f, 1280, sink);
        check("repeat after the delay", "+k6 -k6 +k6 ", log.toString());
        s.buttons = 0;
        pad.onState(s, 1500, sink);
        check("arrow released, no ticks", false, pad.moving());
        s.leftX = 1f;
        pad.onState(s, 1600, sink);
        for (int i = 0; i < 60; i++) pad.tick(1600 + i * 16, 1f / 60f, 1280, sink);
        check("full stick crosses most of the screen in a second", true, moved[0] > 900 && moved[0] < 1200 && moved[1] == 0);
        s.leftX = 0.1f;
        pad.onState(s, 3000, sink);
        int before = moved[0];
        pad.tick(3016, 1f / 60f, 1280, sink);
        check("dead zone", before, moved[0]);
        s.leftX = 0;
        s.buttons = BattleNetPadInput.RT;
        pad.onState(s, 4000, sink);
        for (int i = 0; i < 60; i++) pad.tick(4000 + i * 16, 1f / 60f, 1280, sink);
        check("right trigger scrolls down", true, scrolled[0] >= 12 && scrolled[0] <= 15);
        s.buttons = BattleNetPadInput.A | BattleNetPadInput.RB;
        pad.onState(s, 5000, sink);
        log.setLength(0);
        s.buttons |= BattleNetPadInput.SELECT;
        pad.onState(s, 5100, sink);
        check("View with buttons held releases them and leaves pointer mode", true,
                log.toString().contains("-b1") && log.toString().contains("-k3") && log.toString().endsWith("ptr- "));
        check("left pointer mode", false, pad.pointerMode());
        s.buttons = 0;
        pad.onState(s, 5200, sink);
        s.buttons = BattleNetPadInput.SELECT;
        pad.onState(s, 5300, sink);
        log.setLength(0);
        pad.reset(sink);
        check("reset leaves pointer mode", "ptr- ", log.toString());
        check("reset stops ticks", false, pad.moving());
    }

    private static JSONObject fixture(String name) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(new File(fixtures, name).toPath()), StandardCharsets.UTF_8));
    }

    private static void check(String name, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) {
            failures++;
            System.out.println("FAIL " + name + ": expected " + expected + ", got " + actual);
        }
    }
}
