package com.wehavephones.mobile;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Tells whether Blizzard serves a newer build of an installed game, without
 * Battle.net: the Agent's {@code World of Warcraft\.build.info} names the
 * installed build config per product, and Blizzard's public version service
 * ({@code <region>.version.battle.net/v2/products/<product>/versions}) names
 * the current one per region. DroidDeck checks updates the same way. Parsing
 * is pure Java (host-tested), the fetch runs off the main thread.
 */
public final class BattleNetUpdateCheck {
    private static final long CACHE_MS = 30 * 60_000L;
    private static final Map<String, Object[]> CACHE = new HashMap<>();
    private static int cacheGeneration;

    private BattleNetUpdateCheck() {}

    public static final class Result {
        public final String product;
        public final String installedVersion;
        public final String latestVersion;
        public final boolean updateAvailable;

        Result(String product, String installedVersion, String latestVersion, boolean updateAvailable) {
            this.product = product;
            this.installedVersion = installedVersion;
            this.latestVersion = latestVersion;
            this.updateAvailable = updateAvailable;
        }
    }

    public static String versionsUrl(String region, String product) {
        String zone = region == null || !region.matches("[a-z]{2}") ? "us" : region;
        String host = "cn".equals(zone) ? "cn.version.battlenet.com.cn" : zone + ".version.battle.net";
        return "https://" + host + "/v2/products/" + product + "/versions";
    }

    /** Compares the installed row with the versions table; null when either is unusable. */
    public static Result compare(String product, String buildInfoText, String versionsText) {
        if (buildInfoText == null || versionsText == null) return null;
        Map<String, String> local = BattleNetBuildInfo.parse(buildInfoText).rowFor(product);
        if (local == null) return null;
        String region = BattleNetBuildInfo.value(local, "branch").toLowerCase(Locale.US);
        String installedKey = BattleNetBuildInfo.value(local, "build key").toLowerCase(Locale.US);
        String installedVersion = BattleNetBuildInfo.value(local, "version");
        if (installedKey.isEmpty()) return null;
        Map<String, String> latest = null;
        for (Map<String, String> row : BattleNetBuildInfo.parse(versionsText).rows) {
            if (BattleNetBuildInfo.value(row, "region").equalsIgnoreCase(region)) {
                latest = row;
                break;
            }
        }
        if (latest == null) return null;
        String latestKey = BattleNetBuildInfo.value(latest, "buildconfig").toLowerCase(Locale.US);
        if (!latestKey.matches("[0-9a-f]{32}")) return null;
        return new Result(product, installedVersion, BattleNetBuildInfo.value(latest, "versionsname"), !latestKey.equals(installedKey));
    }

    /** Blocking (network); cached for 30 minutes per product. Null when unknown. */
    public static Result check(File wowRoot, String product, boolean force) {
        if (wowRoot == null || product == null || product.isEmpty()) return null;
        String key = wowRoot.getPath() + "|" + product;
        final int generation;
        synchronized (CACHE) {
            generation = cacheGeneration;
            Object[] cached = CACHE.get(key);
            if (!force && cached != null && System.currentTimeMillis() - (Long)cached[0] < CACHE_MS) return (Result)cached[1];
        }
        String buildInfo = readText(new File(wowRoot, BattleNetBuildInfo.BUILD_INFO));
        Map<String, String> local = buildInfo == null ? null : BattleNetBuildInfo.parse(buildInfo).rowFor(product);
        if (local == null) return null;
        String region = BattleNetBuildInfo.value(local, "branch").toLowerCase(Locale.US);
        Result result = compare(product, buildInfo, fetch(versionsUrl(region, product)));
        if (result != null) {
            synchronized (CACHE) {
                if (generation == cacheGeneration) {
                    CACHE.put(key, new Object[] {System.currentTimeMillis(), result});
                }
            }
        }
        return result;
    }

    /** Forget cached answers (after an install or update). */
    public static void clear() {
        synchronized (CACHE) {
            CACHE.clear();
            cacheGeneration++;
        }
    }

    private static String fetch(String url) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection)new URL(url).openConnection();
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", "WeHavePhones");
            if (connection.getResponseCode() != 200) return null;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0 && out.size() < 256 * 1024) out.write(buffer, 0, n);
                return out.toString("UTF-8");
            }
        }
        catch (IOException e) {
            return null;
        }
        finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String readText(File file) {
        if (file == null || !file.isFile() || file.length() > 256 * 1024) return null;
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            return null;
        }
    }
}
