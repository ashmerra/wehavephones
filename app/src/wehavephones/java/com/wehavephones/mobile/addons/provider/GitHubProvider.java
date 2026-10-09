package com.wehavephones.mobile.addons.provider;

import com.wehavephones.mobile.addons.model.Channel;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.RemoteAddon;
import com.wehavephones.mobile.addons.model.RemoteFile;
import com.wehavephones.mobile.WowFlavor;
import com.wehavephones.mobile.addons.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GitHub releases provider. Uses api.github.com for metadata and
 * browser_download_url (github.com -> release-assets.githubusercontent.com) for
 * the archive; an optional PAT is only sent to api.github.com.
 */
public final class GitHubProvider extends BaseProvider {
    private static final Set<String> SECRET_HEADERS = Collections.singleton("Authorization");
    private static final Set<String> SECRET_HOSTS = Collections.singleton("api.github.com");
    private static final int MAX_RELEASE_JSON_BYTES = 256 * 1024;

    public GitHubProvider(String pat) {
        super(pat);
    }

    @Override
    public ProviderType type() {
        return ProviderType.GITHUB;
    }

    @Override
    public boolean requiresKey() {
        return false;
    }

    @Override
    public List<RemoteAddon> search(String query, WowFlavor flavor) throws ProviderException {
        ArrayList<RemoteAddon> results = new ArrayList<>();
        GitHubRepo direct = GitHubRepo.parse(query);
        if (direct != null) {
            RemoteAddon addon = getById(direct.key(), flavor);
            if (addon != null) results.add(addon);
            return results;
        }
        String searchQuery = (query != null ? query.trim() : "") + " wow addon";
        JSONObject root = requestJson(ProviderUrls.githubSearch(searchQuery, 20),
                apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
        JSONArray items = root.optJSONArray("items");
        if (items == null) return results;
        for (int i = 0; i < items.length(); i++) {
            JSONObject repo = items.optJSONObject(i);
            if (repo == null) continue;
            RemoteAddon addon = parseRepo(repo);
            if (addon != null) results.add(addon);
        }
        return results;
    }

    @Override
    public RemoteAddon getById(String externalId, WowFlavor flavor) throws ProviderException {
        GitHubRepo repo = GitHubRepo.parse(externalId);
        if (repo == null) {
            throw new ProviderException(ProviderException.Kind.PARSE,
                    "GitHub addons use owner/repo ids: " + externalId);
        }
        JSONObject object = requestJson(ProviderUrls.githubRepo(repo.owner, repo.repo),
                apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
        RemoteAddon addon = parseRepo(object);
        if (addon == null) return null;
        RemoteFile latest = getLatestFile(repo.key(), flavor, Channel.STABLE, "");
        if (latest == null) return addon;
        return new RemoteAddon(addon.provider, addon.externalId, addon.name, addon.author,
                addon.summary, addon.website, addon.downloads, latest);
    }

    @Override
    public RemoteFile getLatestFile(String externalId, WowFlavor flavor, Channel channel,
                                    String currentVersion) throws ProviderException {
        GitHubRepo repo = GitHubRepo.parse(externalId);
        if (repo == null) return null;
        JSONObject release = null;
        try {
            release = requestJson(ProviderUrls.githubLatestRelease(repo.owner, repo.repo),
                    apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
        }
        catch (ProviderException e) {
            if (e.kind != ProviderException.Kind.NOT_FOUND) throw e;
        }
        if (release == null) {
            JSONArray releases = requestJsonArray(ProviderUrls.githubReleases(repo.owner, repo.repo, 5),
                    apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
            for (int i = 0; i < releases.length(); i++) {
                JSONObject candidate = releases.optJSONObject(i);
                if (candidate != null && !candidate.optBoolean("draft", false)) {
                    release = candidate;
                    break;
                }
            }
        }
        if (release == null) return null;

        ArrayList<GitHubAssetSelector.Asset> assets = parseAssets(release.optJSONArray("assets"));
        if (assets.isEmpty()) return null;

        GitHubAssetSelector.Asset selected = selectFromReleaseJson(repo, release, assets, flavor);
        if (selected == null) selected = GitHubAssetSelector.select(assets, flavor, false);

        String tag = release.optString("tag_name", release.optString("name", ""));
        String version = tag.startsWith("v") || tag.startsWith("V") ? tag.substring(1) : tag;
        RemoteFile.Builder builder = RemoteFile.builder(ProviderType.GITHUB, repo.key())
                .releaseId(tag)
                .version(version)
                .name(selected != null ? selected.name : "")
                .releaseDate(CurseForgeProvider.parseDate(release.optString("published_at", "")))
                .changelog(release.optString("body", ""));
        if (selected != null) {
            builder.downloadUrl(selected.browserUrl != null && !selected.browserUrl.isEmpty()
                    ? selected.browserUrl : selected.apiUrl);
            builder.size(selected.size);
        }
        return builder.build();
    }

    @Override
    public Map<String, RemoteFile> getLatestFiles(Collection<String> externalIds, WowFlavor flavor,
                                                   Channel channel) throws ProviderException {
        LinkedHashMap<String, RemoteFile> result = new LinkedHashMap<>();
        if (externalIds == null) return result;
        for (String id : externalIds) {
            try {
                RemoteFile file = getLatestFile(id, flavor, channel, "");
                if (file != null) result.put(id, file);
            }
            catch (ProviderException e) {
                if (e.kind == ProviderException.Kind.RATE_LIMIT
                        || e.kind == ProviderException.Kind.CANCELLED) {
                    throw e;
                }
            }
        }
        return result;
    }

    @Override
    public ResolvedDownload resolveDownload(RemoteFile file) throws ProviderException {
        if (file.downloadUrl == null || file.downloadUrl.isEmpty()) {
            throw new ProviderException(ProviderException.Kind.NOT_FOUND, "GitHub release has no zip asset");
        }
        Map<String, String> headers = headers();
        Set<String> secretHeaders = SECRET_HEADERS;
        Set<String> secretHosts = SECRET_HOSTS;
        if (file.downloadUrl.contains("api.github.com")) {
            headers.put("Accept", "application/octet-stream");
        }
        else {
            secretHeaders = null;
            secretHosts = null;
        }
        return new ResolvedDownload(file.downloadUrl, headers, secretHeaders, secretHosts, file.size, null);
    }

    @Override
    public String getChangelog(String externalId, String releaseId, WowFlavor flavor)
            throws ProviderException {
        GitHubRepo repo = GitHubRepo.parse(externalId);
        if (repo == null || releaseId == null || releaseId.isEmpty()) return "";
        JSONObject release = requestJson(
                ProviderUrls.githubLatestRelease(repo.owner, repo.repo),
                apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
        return release.optString("body", "");
    }

    @Override
    public void testKey() throws ProviderException {
        requestJson(ProviderUrls.githubRateLimit(), apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 1);
    }

    private Map<String, String> apiHeaders() {
        return headers();
    }

    private Map<String, String> headers() {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        map.put("Accept", "application/vnd.github+json");
        map.put("X-GitHub-Api-Version", "2022-11-28");
        if (!apiKey.isEmpty()) map.put("Authorization", "Bearer " + apiKey);
        return map;
    }

    private JSONArray requestJsonArray(String url, Map<String, String> headers, Set<String> secretHeaders,
                                       Set<String> secretHosts, int attempts) throws ProviderException {
        Http.Response response = request(url, headers, secretHeaders, secretHosts, Http.MAX_JSON_BYTES, attempts);
        try {
            return new JSONArray(response.text());
        }
        catch (Exception e) {
            throw new ProviderException(ProviderException.Kind.PARSE, "Malformed JSON array from " + url, e);
        }
    }

    private RemoteAddon parseRepo(JSONObject repo) {
        if (repo == null) return null;
        String fullName = repo.optString("full_name", "");
        GitHubRepo parsed = GitHubRepo.parse(fullName);
        if (parsed == null) return null;
        JSONObject owner = repo.optJSONObject("owner");
        String author = owner != null ? owner.optString("login", "") : "";
        return new RemoteAddon(ProviderType.GITHUB, parsed.key(),
                repo.optString("name", parsed.repo), author,
                repo.optString("description", ""), repo.optString("html_url", ""),
                repo.optLong("stargazers_count", 0), null);
    }

    private static ArrayList<GitHubAssetSelector.Asset> parseAssets(JSONArray array) {
        ArrayList<GitHubAssetSelector.Asset> assets = new ArrayList<>();
        if (array == null) return assets;
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            assets.add(new GitHubAssetSelector.Asset(
                    object.optString("name", ""),
                    object.optString("browser_download_url", ""),
                    object.optString("url", ""),
                    CurseForgeProvider.parseDate(object.optString("created_at", "")),
                    object.optLong("size", -1)));
        }
        return assets;
    }

    private GitHubAssetSelector.Asset selectFromReleaseJson(GitHubRepo repo, JSONObject release,
                                                            List<GitHubAssetSelector.Asset> assets,
                                                            WowFlavor flavor) {
        GitHubAssetSelector.Asset releaseJson = null;
        for (GitHubAssetSelector.Asset asset : assets) {
            if (asset.name.equalsIgnoreCase("release.json")) {
                releaseJson = asset;
                break;
            }
        }
        if (releaseJson == null) return null;
        String url = releaseJson.browserUrl != null && !releaseJson.browserUrl.isEmpty()
                ? releaseJson.browserUrl : releaseJson.apiUrl;
        if (url == null || url.isEmpty()) return null;
        try {
            Http.Response response = Http.getWithRetry(url, headers(), null, null,
                    MAX_RELEASE_JSON_BYTES, 1);
            if (!response.isSuccess()) return null;
            JSONObject root = new JSONObject(response.text());
            JSONArray releases = root.optJSONArray("releases");
            if (releases == null) return null;
            for (int i = 0; i < releases.length(); i++) {
                JSONObject entry = releases.optJSONObject(i);
                if (entry == null) continue;
                if (!releaseJsonFlavorMatches(entry.optJSONArray("metadata"), flavor)) continue;
                String filename = entry.optString("filename", "");
                if (filename.isEmpty()) continue;
                for (GitHubAssetSelector.Asset asset : assets) {
                    if (asset.name.equalsIgnoreCase(filename)) return asset;
                }
            }
        }
        catch (IOException ignored) {}
        catch (Exception ignored) {}
        return null;
    }

    private static boolean releaseJsonFlavorMatches(JSONArray metadata, WowFlavor flavor) {
        if (metadata == null || flavor == null) return false;
        String wanted = flavorName(flavor.baseFlavor());
        if (wanted.isEmpty()) return false;
        for (int i = 0; i < metadata.length(); i++) {
            JSONObject entry = metadata.optJSONObject(i);
            if (entry == null) continue;
            if (wanted.equalsIgnoreCase(entry.optString("flavor", ""))) return true;
        }
        return false;
    }

    static String flavorName(WowFlavor flavor) {
        switch (flavor) {
            case RETAIL: return "mainline";
            case CLASSIC: return "mists";
            case CLASSIC_ERA: return "classic";
            case ANNIVERSARY: return "bcc";
            case TITAN: return "titan";
            default: return flavor.wagoKey;
        }
    }

    public static List<String> assetNames(JSONArray assets) {
        ArrayList<String> names = new ArrayList<>();
        if (assets == null) return names;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject object = assets.optJSONObject(i);
            if (object != null) names.add(object.optString("name", ""));
        }
        return names;
    }

    static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.US);
    }
}
