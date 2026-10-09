package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.container.Container;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class WowInstallationScanner {
    private WowInstallationScanner() {}

    public static ArrayList<WowProduct> scan(Container container) {
        ArrayList<WowProduct> products = new ArrayList<>();
        if (container == null) return products;
        File root = WeHavePhonesPaths.wowRootFor(container);
        File[] children = root.listFiles();
        if (children == null) return products;
        BattleNetBuildInfo rootInfo = BattleNetBuildInfo.read(new File(root, BattleNetBuildInfo.BUILD_INFO));

        for (File child : children) {
            if (!child.isDirectory() || !isProductDirectoryName(child.getName())) continue;
            String[] names = child.list();
            List<String> fileNames = names != null ? Arrays.asList(names) : Collections.<String>emptyList();
            String executable = WowFlavor.pickExecutable(fileNames);
            if (!looksLikeProduct(child, executable)) continue;
            WowProduct product = new WowProduct(child.getName(), child);
            product.setProductCode(BattleNetBuildInfo.readFlavorCode(new File(child, BattleNetBuildInfo.FLAVOR_INFO)));
            applyBuildInfo(product, rootInfo);
            product.setExecutable(executable);
            product.setFlavor(WowFlavor.detect(product.directoryName, product.getProductCode(), product.getVersion()));
            products.add(product);
        }

        // Catalog order: retail first, then Forever, the Classic line and test clients.
        Collections.sort(products, new Comparator<WowProduct>() {
            @Override
            public int compare(WowProduct first, WowProduct second) {
                int byFlavor = Integer.compare(rank(first), rank(second));
                return byFlavor != 0 ? byFlavor : first.directoryName.compareTo(second.directoryName);
            }
        });
        return products;
    }

    private static int rank(WowProduct product) {
        return product.getFlavor().isKnown() ? product.getFlavor().ordinal() : Integer.MAX_VALUE;
    }

    /**
     * Every WoW game the Battle.net client on this device knows about: the
     * installed folders merged with the Agent's .build.info and product.db and
     * the newest launcher log (see BattleNetGames).
     */
    public static List<BattleNetGames.Game> battleNetGames(Container container) {
        ArrayList<WowProduct> folders = scan(container);
        if (container == null) return BattleNetGames.merge(folders, null, null, null);
        BattleNetBuildInfo rootInfo = BattleNetBuildInfo.read(new File(WeHavePhonesPaths.wowRootFor(container), BattleNetBuildInfo.BUILD_INFO));
        BattleNetProductDb productDb = BattleNetProductDb.read(WeHavePhonesPaths.bnetProductDb(container));
        BattleNetLauncherLog launcherLog = null;
        File mainLog = BattleNetLauncherLog.findMainLog(WeHavePhonesPaths.bnetLogsDir(container), "");
        if (mainLog != null) {
            BattleNetLauncherLog.Tail tail = new BattleNetLauncherLog.Tail(mainLog);
            tail.readAll(16);
            launcherLog = tail.log;
        }
        return BattleNetGames.merge(folders, rootInfo, productDb, launcherLog);
    }

    /** The game chosen on Home (shared with Play and Add-ons), else retail, else the first install. */
    public static WowProduct selected(Context context, Container container) {
        return pick(scan(container), WeHavePhonesConfig.getProductDir(context));
    }

    public static WowProduct pick(List<WowProduct> products, String directoryName) {
        if (products.isEmpty()) return null;
        WowProduct retail = null;
        for (WowProduct product : products) {
            if (product.directoryName.equalsIgnoreCase(directoryName)) return product;
            if (retail == null && product.getFlavor() == WowFlavor.RETAIL) retail = product;
        }
        return retail != null ? retail : products.get(0);
    }

    static boolean isProductDirectoryName(String name) {
        if (name.length() < 3) return false;
        if (name.charAt(0) != '_' || name.charAt(name.length() - 1) != '_') return false;
        for (int i = 1; i < name.length() - 1; i++) {
            char character = name.charAt(i);
            if (!Character.isLetterOrDigit(character) && character != '_') return false;
        }
        return true;
    }

    private static boolean looksLikeProduct(File directory, String executable) {
        return !executable.isEmpty()
                || new File(directory, ".build.info").isFile()
                || new File(directory, "Data").isDirectory();
    }

    /**
     * Version/branch/build from the Agent's root .build.info row for the
     * product's code, else from a legacy single-product file in the folder.
     */
    static void applyBuildInfo(WowProduct product, BattleNetBuildInfo rootInfo) {
        Map<String, String> row = null;
        if (!product.getProductCode().isEmpty()) row = rootInfo.rowFor(product.getProductCode());
        if (row == null) row = BattleNetBuildInfo.read(new File(product.directory, BattleNetBuildInfo.BUILD_INFO)).singleRow();
        if (row == null) return;
        product.setVersion(BattleNetBuildInfo.value(row, "version"));
        product.setBranch(BattleNetBuildInfo.value(row, "branch"));
        product.setBuildKey(BattleNetBuildInfo.value(row, "build key"));
        if (product.getProductCode().isEmpty()) product.setProductCode(BattleNetBuildInfo.value(row, "product"));
    }
}
