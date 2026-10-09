package com.wehavephones.mobile.addons.provider;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/**
 * Provider URL builders. Pure Java so the host JVM self-test can assert the
 * exact query strings without touching the network.
 */
public final class ProviderUrls {
    public static final String CURSEFORGE_API = "https://api.curseforge.com";
    public static final String TUKUI_API = "https://api.tukui.org";
    public static final String WAGO_EXTERNAL_API = "https://addons.wago.io/api/external";
    public static final String WAGO_DATA_API = "https://addons.wago.io/api/data";
    public static final String GITHUB_API = "https://api.github.com";

    private ProviderUrls() {}

    public static String curseForgeSearch(int gameVersionTypeId, String query, int pageSize) {
        StringBuilder builder = new StringBuilder(CURSEFORGE_API)
                .append("/v1/mods/search?gameId=1")
                .append("&gameVersionTypeId=").append(gameVersionTypeId)
                .append("&searchFilter=").append(encode(query))
                .append("&sortField=2&sortOrder=desc&index=0")
                .append("&pageSize=").append(clampPageSize(pageSize));
        return builder.toString();
    }

    public static String curseForgeMod(String modId) {
        return CURSEFORGE_API + "/v1/mods/" + encodePath(modId);
    }

    public static String curseForgeFiles(String modId, int gameVersionTypeId, int pageSize) {
        return CURSEFORGE_API + "/v1/mods/" + encodePath(modId) + "/files?gameVersionTypeId="
                + gameVersionTypeId + "&index=0&pageSize=" + clampPageSize(pageSize);
    }

    public static String curseForgeDownloadUrl(String modId, String fileId) {
        return CURSEFORGE_API + "/v1/mods/" + encodePath(modId) + "/files/" + encodePath(fileId) + "/download-url";
    }

    public static String curseForgeChangelog(String modId, String fileId) {
        return CURSEFORGE_API + "/v1/mods/" + encodePath(modId) + "/files/" + encodePath(fileId) + "/changelog";
    }

    public static String curseForgeGame(int gameId) {
        return CURSEFORGE_API + "/v1/games/" + gameId;
    }

    public static String tukuiList() {
        return TUKUI_API + "/v1/addons";
    }

    public static String tukuiAddon(String slug) {
        return TUKUI_API + "/v1/addon/" + encodePath(slug);
    }

    public static String tukuiChangelog(String slug) {
        return TUKUI_API + "/v1/changelog/" + encodePath(slug);
    }

    public static String wagoSearch(String query, String gameVersion) {
        return WAGO_EXTERNAL_API + "/addons/_search?query=" + encode(query)
                + "&game_version=" + encode(gameVersion) + "&stability=stable";
    }

    public static String wagoPopular(String gameVersion) {
        return WAGO_EXTERNAL_API + "/addons/popular?game_version=" + encode(gameVersion);
    }

    public static String wagoAddon(String id) {
        return WAGO_EXTERNAL_API + "/addons/" + encodePath(id);
    }

    public static String wagoBatchRecents() {
        return WAGO_EXTERNAL_API + "/addons/_recents";
    }

    public static String wagoGameData() {
        return WAGO_DATA_API + "/game";
    }

    public static String githubLatestRelease(String owner, String repo) {
        return GITHUB_API + "/repos/" + encodePath(owner) + "/" + encodePath(repo) + "/releases/latest";
    }

    public static String githubReleases(String owner, String repo, int perPage) {
        return GITHUB_API + "/repos/" + encodePath(owner) + "/" + encodePath(repo)
                + "/releases?per_page=" + Math.max(1, Math.min(perPage, 100));
    }

    public static String githubTags(String owner, String repo, int perPage) {
        return GITHUB_API + "/repos/" + encodePath(owner) + "/" + encodePath(repo)
                + "/tags?per_page=" + Math.max(1, Math.min(perPage, 100));
    }

    public static String githubRepo(String owner, String repo) {
        return GITHUB_API + "/repos/" + encodePath(owner) + "/" + encodePath(repo);
    }

    public static String githubSearch(String query, int perPage) {
        return GITHUB_API + "/search/repositories?q=" + encode(query)
                + "&sort=stars&order=desc&per_page=" + Math.max(1, Math.min(perPage, 100));
    }

    public static String githubRateLimit() {
        return GITHUB_API + "/rate_limit";
    }

    /** Wago download links take a query token rather than an auth header. */
    public static String withWagoToken(String downloadLink, String token) {
        if (downloadLink == null || downloadLink.isEmpty()) return downloadLink;
        if (token == null || token.isEmpty()) return downloadLink;
        return downloadLink + (downloadLink.indexOf('?') >= 0 ? "&" : "?") + "token=" + encode(token);
    }

    public static String encode(String value) {
        if (value == null) return "";
        try {
            return URLEncoder.encode(value, "UTF-8");
        }
        catch (UnsupportedEncodingException e) {
            return value;
        }
    }

    public static String encodePath(String value) {
        if (value == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (Character.isLetterOrDigit(character) || character == '-' || character == '_'
                    || character == '.' || character == '~' || character == '/') {
                builder.append(character);
            }
            else {
                builder.append(encode(String.valueOf(character)));
            }
        }
        return builder.toString();
    }

    private static int clampPageSize(int pageSize) {
        return Math.max(1, Math.min(pageSize, 50));
    }
}
