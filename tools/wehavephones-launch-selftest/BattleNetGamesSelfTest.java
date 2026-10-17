package com.wehavephones.mobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;

/**
 * Host test for the Battle.net game list: product.db protobuf decoding,
 * .build.info/.flavor.info parsing and the merge behind the Home dropdown.
 * product.db is synthesized with the documented field numbers (see
 * BattleNetProductDb), including unknown fields that must be skipped.
 */
public final class BattleNetGamesSelfTest {
    private static int checks;
    private static int failures;

    public static void main(String[] args) throws Exception {
        productDb();
        buildInfo();
        merge();
        foreverStressTest();
        installFlags();
        System.out.println("[bnet-games-selftest] " + checks + " checks, " + failures + " failure(s)");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- product.db -------------------------------------------------------------------

    static byte[] sampleDb() {
        Proto db = new Proto();
        db.message(1, new Proto()
                .string(1, "wow").string(2, "wow")
                .message(3, new Proto().string(1, "C:/Program Files (x86)/World of Warcraft").string(2, "us").string(10, "us"))
                .message(4, new Proto().message(1, new Proto()
                        .varint(1, 1).varint(2, 1).varint(3, 1).string(7, "12.1.0.69933").fixed32(99)))
                .fixed64(42));
        db.message(1, new Proto()
                .string(1, "wow_classic_beta").string(2, "wow_classic_beta")
                .message(4, new Proto().message(1, new Proto().varint(1, 1).string(7, "1.60.1.69977"))));
        db.message(1, new Proto().string(1, "agent").string(2, "agent"));
        db.message(1, new Proto().string(1, "prometheus").string(2, "pro"));
        db.message(3, new Proto().string(1, "active process, skipped"));
        for (String code : new String[]{"wow", "wow_classic_era", "wowdev", "agent", "wow_anniversary", "bna"}) {
            db.message(4, new Proto().string(1, code).string(2, "hash"));
        }
        return db.bytes();
    }

    private static void productDb() {
        BattleNetProductDb db = BattleNetProductDb.parse(sampleDb());
        check("all installs decoded", 4, db.installs.size());
        BattleNetProductDb.Install retail = db.installs.get(0);
        check("retail uid", "wow", retail.uid);
        check("retail install path", "C:/Program Files (x86)/World of Warcraft", retail.installPath);
        check("retail branch", "us", retail.branch);
        check("retail installed", true, retail.installed);
        check("retail playable", true, retail.playable);
        check("completed download permits Play", true, db.isDownloadComplete("wow", ""));
        retail.updateComplete = false;
        check("playable streaming install cannot launch", false, db.isDownloadComplete("wow", ""));
        retail.updateComplete = true;
        retail.installed = false;
        check("uninstalled cached product cannot launch", false, db.isDownloadComplete("wow", ""));
        retail.installed = true;
        check("unknown product cannot launch", false, db.isDownloadComplete("wowt", ""));
        check("uid resolves completed product", true, db.isDownloadComplete("", "wow"));
        check("retail version", "12.1.0.69933", retail.version);
        check("beta not playable", false, db.installs.get(1).playable);
        check("game installs exclude agent/other games", 2, db.gameInstalls().size());
        check("game config codes", "[wow, wow_classic_era, wow_anniversary]", db.gameConfigCodes().toString());

        BattleNetProductDb anniversary = BattleNetProductDb.parse(new Proto().message(1, new Proto()
                .string(1, "wow_classic_anniversary").string(2, "wow_anniversary")
                .message(4, new Proto().message(1, new Proto()
                        .varint(1, 1).varint(2, 1).varint(3, 1)))).bytes());
        check("anniversary Agent gate uses launcher uid", true,
                anniversary.isDownloadComplete("", "wow_classic_anniversary"));
        check("anniversary TACT code is not the Agent uid", false,
                anniversary.isDownloadComplete("", "wow_anniversary"));
        anniversary.installs.get(0).updateComplete = false;
        check("anniversary playable alone cannot pass Agent gate", false,
                anniversary.isDownloadComplete("", "wow_classic_anniversary"));

        byte[] full = sampleDb();
        BattleNetProductDb truncated = BattleNetProductDb.parse(Arrays.copyOf(full, full.length / 2));
        check("truncated db does not throw and keeps whole records", true, truncated.installs.size() >= 1);
        check("garbage db does not throw", 0, BattleNetProductDb.parse(new byte[]{(byte)0xFF, (byte)0xFF, 0x07}).installs.size());
        check("missing file", null, BattleNetProductDb.read(new File("/nonexistent/product.db")));
    }

    // ---- .build.info / .flavor.info -----------------------------------------------------

    static final String ROOT_BUILD_INFO = "\uFEFFBranch!STRING:0|Active!DEC:1|Build Key!HEX:16|CDN Key!HEX:16|Version!STRING:0|Product!STRING:0\r\n"
            + "eu|0|aaaa|bbbb|12.0.7.1|wow\r\n"
            + "us|1|cccc|dddd|12.1.0.69933|wow\r\n"
            + "us|1|eeee|ffff|1.60.1.69977|wow_classic_beta\r\n";

    private static void buildInfo() throws Exception {
        BattleNetBuildInfo info = BattleNetBuildInfo.parse(ROOT_BUILD_INFO.replace("\uFEFF", ""));
        check("rows", 3, info.rows.size());
        check("active row wins", "12.1.0.69933", BattleNetBuildInfo.value(info.rowFor("wow"), "version"));
        check("build key column", "cccc", BattleNetBuildInfo.value(info.rowFor("wow"), "build key"));
        check("product codes", "[wow, wow_classic_beta]", info.productCodes().toString());
        check("absent product", null, info.rowFor("wowt"));

        File dir = Files.createTempDirectory("bnet-games-selftest").toFile();
        File flavor = new File(dir, ".flavor.info");
        Files.write(flavor.toPath(), "Product Flavor!STRING:0\nwow_classic_beta\n".getBytes(StandardCharsets.UTF_8));
        check(".flavor.info product code", "wow_classic_beta", BattleNetBuildInfo.readFlavorCode(flavor));
        File root = new File(dir, ".build.info");
        Files.write(root.toPath(), ROOT_BUILD_INFO.getBytes(StandardCharsets.UTF_8));
        check("BOM stripped on read", "wow", BattleNetBuildInfo.read(root).productCodes().get(0));
        check("missing .flavor.info", "", BattleNetBuildInfo.readFlavorCode(new File(dir, "nope")));
        flavor.delete();
        root.delete();
        dir.delete();
    }

    // ---- merge ------------------------------------------------------------------------

    private static WowProduct folder(String dir, String code, String version, String exe) {
        WowProduct product = new WowProduct(dir, new File("/wow", dir));
        product.setProductCode(code);
        product.setVersion(version);
        product.setExecutable(exe);
        product.setFlavor(WowFlavor.detect(dir, code, version));
        return product;
    }

    private static void merge() {
        List<WowProduct> folders = Arrays.asList(
                folder("_retail_", "wow", "12.1.0.69933", "Wow-ARM64.exe"),
                folder("_classic_beta_", "wow_classic_beta", "1.60.1.69977", "WowB.exe"));
        BattleNetLauncherLog log = new BattleNetLauncherLog();
        log.feed("D 2026-09-25 21:56:42.142620 [ProductState] {Main} InstallState (wow_classic_era): currentOperation=Install currentOperationStatus=New installed=0 sharedContainerSubpath=_classic_era_");
        log.feed("D 2026-09-25 21:56:42.2 [ProductState] {Main} InstallState (wow_ptr): installed=0 sharedContainerSubpath=_ptr_ localVersion=12.1.5.70100");
        log.feed("D 2026-09-25 21:56:42.3 [ProductState] {Main} InstallState (wow): installed=1 sharedContainerSubpath=_retail_");
        log.feed("D 2026-09-25 21:56:42.4 [ProductState] {Main} InstallState (fenris): installed=0");

        List<BattleNetGames.Game> games = BattleNetGames.merge(folders,
                BattleNetBuildInfo.parse(ROOT_BUILD_INFO), BattleNetProductDb.parse(sampleDb()), log);
        StringBuilder order = new StringBuilder();
        for (BattleNetGames.Game game : games) {
            if (order.length() > 0) order.append(", ");
            order.append(game.getDisplayName()).append(game.installed ? "" : " (install)");
        }
        check("merged list, installed first, catalog order",
                "Retail, Forever Beta, Retail PTR (install), Anniversary (TBC) (install), Classic Era (install)", order.toString());

        BattleNetGames.Game retail = games.get(0);
        check("retail sources", "[folder, build.info, product.db, launcher log]", retail.sources.toString());
        check("retail selection key", "_retail_", retail.selectionKey());
        BattleNetGames.Game forever = games.get(1);
        check("forever beta flavor", WowFlavor.FOREVER_BETA, forever.flavor);
        check("forever beta uid from product.db", "wow_classic_beta", forever.uid);
        check("forever beta title", "World of Warcraft: Forever", forever.getTitle());
        BattleNetGames.Game ptr = games.get(2);
        check("ptr product code from catalog", "wowt", ptr.productCode);
        check("ptr uid from log", "wow_ptr", ptr.uid);
        check("ptr version from log", "12.1.5.70100", ptr.version);
        check("ptr selection key", "bnet:wow_ptr", ptr.selectionKey());
        check("ptr battle.net installed flag", Boolean.FALSE, ptr.bnetInstalled);
        BattleNetGames.Game era = games.get(4);
        check("era sources", "[product.db, launcher log]", era.sources.toString());
        check("era install id", "wow_classic_era", era.bnetGameId());

        check("pick stored install", "Retail PTR", BattleNetGames.pick(games, "bnet:wow_ptr").getDisplayName());
        check("pick folder", "Forever Beta", BattleNetGames.pick(games, "_classic_beta_").getDisplayName());
        check("pick unknown falls back to retail", "Retail", BattleNetGames.pick(games, "_gone_").getDisplayName());
        List<BattleNetGames.Game> nothingInstalled = BattleNetGames.merge(
                java.util.Collections.<WowProduct>emptyList(), null, BattleNetProductDb.parse(sampleDb()), null);
        check("uninstalled games are still listed", 4, nothingInstalled.size());
        check("no default pick when nothing is installed", null, BattleNetGames.pick(nothingInstalled, null));

        List<BattleNetGames.Game> foldersOnly = BattleNetGames.merge(folders, null, null, null);
        check("no Battle.net data: folders only", 2, foldersOnly.size());
    }

    private static void foreverStressTest() {
        check("wowf is a game product code", true, WowFlavor.isGameProductCode("wowf"));
        check("wowf resolves to the Forever product", WowFlavor.FOREVER, WowFlavor.forProductCode("wowf"));
        check("wowf directory", "_forever_", WowFlavor.forProductCode("wowf").directoryName);
        check("wowdev is still not a game product code", false, WowFlavor.isGameProductCode("wowdev"));

        BattleNetLauncherLog log = new BattleNetLauncherLog();
        log.feed("D 2026-10-17 05:16:35.759199 [ProductState] {Main} Game product state created: uid=wowf");
        log.feed("D 2026-10-17 05:16:35.759282 [ProductState] {Main} InstallState (wowf): currentOperation=Install currentOperationStatus=New");
        List<BattleNetGames.Game> games = BattleNetGames.merge(
                java.util.Collections.<WowProduct>emptyList(), null, null, log);
        check("Forever Stress Test is listed without an install", 1, games.size());
        BattleNetGames.Game forever = games.get(0);
        check("Forever Stress Test install=false", false, forever.installed);
        check("Forever Stress Test uid", "wowf", forever.uid);
        check("Forever Stress Test directory", "_forever_", forever.directoryName);
        check("Forever Stress Test display name", "Forever Stress Test", forever.getDisplayName());
        check("Forever Stress Test selection key", "bnet:wowf", forever.selectionKey());
        check("Forever Stress Test install flags", "--game=wowf --install",
                WeHavePhonesLaunchPlan.installFlags(forever.bnetGameId()));

        // The launcher also offers the Forever beta as wow_classic_beta in
        // _classic_beta_ (productCode WoWF_wow_classic_beta); with no build yet
        // it must still read as the Forever beta, not the Classic beta.
        BattleNetLauncherLog betaLog = new BattleNetLauncherLog();
        betaLog.feed("D 2026-10-17 05:16:35.669945 [ProductState] {Main} Game product state created: uid=wow_classic_beta");
        betaLog.feed("D 2026-10-17 05:16:35.670055 [ProductState] {Main} InstallState (wow_classic_beta): currentOperation=Install currentOperationStatus=New");
        betaLog.feed("D 2026-10-17 05:16:35.759199 [ProductState] {Main} Game product state created: uid=wowf");
        betaLog.feed("D 2026-10-17 05:16:35.759282 [ProductState] {Main} InstallState (wowf): currentOperation=Install currentOperationStatus=New");
        List<BattleNetGames.Game> offered = BattleNetGames.merge(
                java.util.Collections.<WowProduct>emptyList(), null, null, betaLog);
        check("Forever live and beta are both offered without an install", 2, offered.size());
        BattleNetGames.Game beta = BattleNetGames.pick(offered, "bnet:wow_classic_beta");
        check("offered Forever beta flavor", WowFlavor.FOREVER_BETA, beta.flavor);
        check("offered Forever beta display name", "Forever Beta", beta.getDisplayName());
        check("offered Forever beta directory", "_classic_beta_", beta.directoryName);
        check("offered Forever beta product code", "wow_classic_beta", beta.productCode);
        check("offered Forever beta selection key", "bnet:wow_classic_beta", beta.selectionKey());
    }

    private static void installFlags() {
        check("install flags", "--game=wow_classic_beta --install", WeHavePhonesLaunchPlan.installFlags("wow_classic_beta"));
        check("install flags reject unsafe ids", "", WeHavePhonesLaunchPlan.installFlags("wow & calc"));
        check("install bat", true, WeHavePhonesLaunchPlan.installBat(WeHavePhonesLaunchPlan.BNET_DIR_X86, "wow_ptr")
                .contains("Battle.net.exe --game=wow_ptr --install\r\n"));
        check("install exec fits a WinHandler packet", true,
                WeHavePhonesLaunchPlan.fitsWinHandler(WeHavePhonesLaunchPlan.winHandlerCommand(WeHavePhonesLaunchPlan.INSTALL_BAT_GUEST)));
    }

    // ---- helpers ----------------------------------------------------------------------

    /** Minimal protobuf writer for fixtures. */
    static final class Proto {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Proto varint(int field, long value) {
            writeVarint((long)field << 3);
            writeVarint(value);
            return this;
        }

        Proto string(int field, String value) {
            return bytes(field, value.getBytes(StandardCharsets.UTF_8));
        }

        Proto message(int field, Proto message) {
            return bytes(field, message.bytes());
        }

        Proto fixed32(int field) {
            writeVarint(((long)field << 3) | 5);
            out.write(new byte[4], 0, 4);
            return this;
        }

        Proto fixed64(int field) {
            writeVarint(((long)field << 3) | 1);
            out.write(new byte[8], 0, 8);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private Proto bytes(int field, byte[] data) {
            writeVarint(((long)field << 3) | 2);
            writeVarint(data.length);
            out.write(data, 0, data.length);
            return this;
        }

        private void writeVarint(long value) {
            while ((value & ~0x7FL) != 0) {
                out.write((int)((value & 0x7F) | 0x80));
                value >>>= 7;
            }
            out.write((int)value);
        }
    }

    private static void check(String name, Object expected, Object actual) {
        checks++;
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (!ok) failures++;
        System.out.println((ok ? "ok   " : "FAIL ") + name + (ok ? "" : " expected=" + expected + " actual=" + actual));
    }
}
