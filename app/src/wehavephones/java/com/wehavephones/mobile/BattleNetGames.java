package com.wehavephones.mobile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The WoW games the Battle.net client on this device knows about, merged from
 * what the client itself writes:
 * <ol>
 *   <li>installed folders ({@code .flavor.info} product code, exe) - "folder";</li>
 *   <li>the Agent's root {@code .build.info} install rows - "build.info";</li>
 *   <li>{@code product.db} installs (uid, product code, installed/playable,
 *       version) and product configs - "product.db";</li>
 *   <li>the newest launcher log's {@code InstallState (<uid>)} lines,
 *       including {@code sharedContainerSubpath} - "launcher log".</li>
 * </ol>
 * Games with a playable folder are {@link Game#installed}; the rest are games
 * Battle.net has registered but that are not on disk (offered as Install).
 * Pure Java (host self-tested).
 */
public final class BattleNetGames {
    public static final String SELECTION_PREFIX = "bnet:";
    private static final Pattern GAME_ID = Pattern.compile("[a-z0-9_]+");

    public static final class Game {
        public String directoryName = "";
        public String productCode = "";
        public String uid = "";
        public String version = "";
        /** A game folder with an executable exists (Play works). */
        public boolean installed;
        /** Battle.net's own installed flag (product.db / launcher log); null when unknown. */
        public Boolean bnetInstalled;
        public WowProduct product;
        public WowFlavor flavor = WowFlavor.UNKNOWN;
        public final LinkedHashSet<String> sources = new LinkedHashSet<>();

        /** Key stored as the selected game: the folder for installs, else "bnet:<id>". */
        public String selectionKey() {
            return installed ? directoryName : SELECTION_PREFIX + bnetGameId();
        }

        /** Id for {@code Battle.net.exe --game=<id>}: the uid when known, else the product code. */
        public String bnetGameId() {
            String id = !uid.isEmpty() ? uid : productCode;
            return GAME_ID.matcher(id).matches() ? id : "";
        }

        public String getDisplayName() {
            if (product != null) return product.getDisplayName();
            if (flavor.isKnown()) return flavor.displayName;
            if (!directoryName.isEmpty()) return WowFlavor.prettyDirectory(directoryName);
            return productCode.isEmpty() ? uid : productCode;
        }

        public String getTitle() {
            return WowFlavor.gameTitle(flavor, directoryName.isEmpty() ? getDisplayName() : directoryName);
        }

        public String getEdition() {
            return WowFlavor.edition(flavor, version);
        }

        boolean matches(String dir, String code, String id) {
            return (!dir.isEmpty() && dir.equalsIgnoreCase(directoryName))
                    || (!code.isEmpty() && code.equalsIgnoreCase(productCode))
                    || (!id.isEmpty() && id.equalsIgnoreCase(uid));
        }
    }

    private final ArrayList<Game> games = new ArrayList<>();

    private BattleNetGames() {}

    /**
     * Merge the sources; any of {@code rootInfo}, {@code productDb} and
     * {@code launcherLog} may be null (Battle.net not installed or never run).
     */
    public static List<Game> merge(List<WowProduct> folders, BattleNetBuildInfo rootInfo,
                                   BattleNetProductDb productDb, BattleNetLauncherLog launcherLog) {
        BattleNetGames merged = new BattleNetGames();
        for (WowProduct product : folders) {
            Game game = merged.obtain(product.directoryName, product.getProductCode(), "");
            game.product = product;
            game.installed = true;
            fill(game, product.getVersion(), "folder");
        }
        if (rootInfo != null) {
            for (String code : rootInfo.productCodes()) {
                if (!WowFlavor.isGameProductCode(code)) continue;
                Game game = merged.obtain(directoryFor(code), code, "");
                fill(game, BattleNetBuildInfo.value(rootInfo.rowFor(code), "version"), "build.info");
            }
        }
        if (productDb != null) {
            for (BattleNetProductDb.Install install : productDb.gameInstalls()) {
                String code = lower(install.productCode);
                Game game = merged.obtain(directoryFor(code), code, lower(install.uid));
                if (game.uid.isEmpty()) game.uid = lower(install.uid);
                game.bnetInstalled = install.installed;
                fill(game, install.version, "product.db");
            }
            for (String code : productDb.gameConfigCodes()) {
                fill(merged.obtain(directoryFor(lower(code)), lower(code), ""), "", "product.db");
            }
        }
        if (launcherLog != null) {
            for (String uid : launcherLog.productUids()) {
                Map<String, String> fields = launcherLog.productFields(uid);
                String dir = value(fields, "sharedContainerSubpath");
                String code = merged.codeForUid(uid, dir);
                if (!WowFlavor.isGameProductCode(uid) && !WowFlavor.isGameProductCode(code)) continue;
                Game game = merged.obtain(!dir.isEmpty() ? dir : directoryFor(code), code, uid);
                if (game.uid.isEmpty()) game.uid = uid;
                if (fields.containsKey("installed")) game.bnetInstalled = "1".equals(value(fields, "installed"));
                fill(game, value(fields, "localVersion"), "launcher log");
            }
        }

        for (Game game : merged.games) {
            if (game.product != null) game.flavor = game.product.getFlavor();
            else game.flavor = WowFlavor.detect(game.directoryName, game.productCode, game.version);
            // By folder, not flavor: the Forever beta's folder _classic_beta_ is product wow_classic_beta.
            if (game.productCode.isEmpty()) game.productCode = WowFlavor.forDirectory(game.directoryName).productCode;
        }
        ArrayList<Game> result = new ArrayList<>(merged.games);
        Collections.sort(result, (first, second) -> {
            if (first.installed != second.installed) return first.installed ? -1 : 1;
            int byFlavor = Integer.compare(rank(first), rank(second));
            return byFlavor != 0 ? byFlavor : first.getDisplayName().compareToIgnoreCase(second.getDisplayName());
        });
        return result;
    }

    /** The game for a stored selection key; falls back to retail, then the first installed game. */
    public static Game pick(List<Game> games, String selectionKey) {
        Game retail = null;
        Game firstInstalled = null;
        for (Game game : games) {
            if (selectionKey != null && game.selectionKey().equalsIgnoreCase(selectionKey)) return game;
            if (!game.installed) continue;
            if (firstInstalled == null) firstInstalled = game;
            if (retail == null && game.flavor == WowFlavor.RETAIL) retail = game;
        }
        return retail != null ? retail : firstInstalled;
    }

    private Game obtain(String dir, String code, String uid) {
        for (Game game : games) {
            if (game.matches(dir, code, uid)) {
                if (game.directoryName.isEmpty()) game.directoryName = dir;
                if (game.productCode.isEmpty()) game.productCode = code;
                return game;
            }
        }
        Game game = new Game();
        game.directoryName = dir;
        game.productCode = code;
        game.uid = uid;
        games.add(game);
        return game;
    }

    /** Product code for a launcher-log uid: from a merged game, the catalog, or the uid itself. */
    private String codeForUid(String uid, String dir) {
        for (Game game : games) {
            if (!game.productCode.isEmpty() && (game.uid.equalsIgnoreCase(uid) || (!dir.isEmpty() && game.directoryName.equalsIgnoreCase(dir)))) {
                return game.productCode;
            }
        }
        WowFlavor byDir = WowFlavor.forDirectory(dir);
        if (byDir.isKnown()) return byDir.productCode;
        return WowFlavor.forProductCode(uid).isKnown() ? uid : "";
    }

    private static void fill(Game game, String version, String source) {
        if (game.version.isEmpty() && version != null) game.version = version.trim();
        game.sources.add(source);
    }

    private static String directoryFor(String code) {
        return WowFlavor.forProductCode(code).directoryName;
    }

    private static int rank(Game game) {
        return game.flavor.isKnown() ? game.flavor.ordinal() : Integer.MAX_VALUE;
    }

    private static String value(Map<String, String> fields, String key) {
        String value = fields.get(key);
        return value != null ? value.trim() : "";
    }

    private static String lower(String value) {
        return value != null ? value.trim().toLowerCase(Locale.US) : "";
    }
}
