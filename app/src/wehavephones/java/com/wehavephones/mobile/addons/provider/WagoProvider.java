package com.wehavephones.mobile.addons.provider;

import com.wehavephones.mobile.addons.model.Channel;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.RemoteAddon;
import com.wehavephones.mobile.addons.model.RemoteFile;
import com.wehavephones.mobile.WowFlavor;
import com.wehavephones.mobile.addons.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

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
 * Wago external read API (Bearer token, optional/paid). Best-effort: the API is
 * semi-private and may change; every call degrades to a typed error.
 */
public final class WagoProvider extends BaseProvider {
    private static final Set<String> SECRET_HEADERS = Collections.singleton("Authorization");
    private static final Set<String> SECRET_HOSTS = new LinkedHashSet<>(java.util.Arrays.asList(
            "addons.wago.io", "*.wago.io"));

    public WagoProvider(String token) {
        super(token);
    }

    @Override
    public ProviderType type() {
        return ProviderType.WAGO;
    }

    @Override
    public List<RemoteAddon> search(String query, WowFlavor flavor) throws ProviderException {
        requireKey();
        requireFlavor(flavor);
        String url = query == null || query.trim().isEmpty()
                ? ProviderUrls.wagoPopular(flavor.baseFlavor().wagoKey)
                : ProviderUrls.wagoSearch(query.trim(), flavor.baseFlavor().wagoKey);
        JSONObject root = requestJson(url, apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
        ArrayList<RemoteAddon> results = new ArrayList<>();
        JSONArray data = root.optJSONArray("data");
        if (data != null) {
            for (int i = 0; i < data.length(); i++) {
                JSONObject addon = data.optJSONObject(i);
                if (addon == null) continue;
                RemoteAddon parsed = parseAddon(addon);
                if (parsed != null) results.add(parsed);
            }
        }
        return results;
    }

    @Override
    public RemoteAddon getById(String externalId, WowFlavor flavor) throws ProviderException {
        requireKey();
        requireFlavor(flavor);
        JSONObject root = requestJson(ProviderUrls.wagoAddon(externalId),
                apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
        JSONObject data = root.optJSONObject("data");
        return parseAddon(data != null ? data : root);
    }

    @Override
    public RemoteFile getLatestFile(String externalId, WowFlavor flavor, Channel channel,
                                    String currentVersion) throws ProviderException {
        RemoteAddon addon = getById(externalId, flavor);
        return addon != null ? addon.latestFile : null;
    }

    @Override
    public Map<String, RemoteFile> getLatestFiles(Collection<String> externalIds, WowFlavor flavor,
                                                   Channel channel) throws ProviderException {
        requireKey();
        requireFlavor(flavor);
        LinkedHashMap<String, RemoteFile> result = new LinkedHashMap<>();
        if (externalIds == null) return result;
        JSONObject body = new JSONObject();
        try {
            body.put("game_version", flavor.baseFlavor().wagoKey);
            JSONArray ids = new JSONArray();
            for (String id : externalIds) ids.put(id);
            body.put("addons", ids);
        }
        catch (Exception e) {
            throw new ProviderException(ProviderException.Kind.PARSE, "Could not build Wago batch body", e);
        }
        try {
            Http.Response response = Http.postJson(ProviderUrls.wagoBatchRecents(),
                    apiHeaders(), body.toString(), SECRET_HEADERS, SECRET_HOSTS, Http.MAX_JSON_BYTES);
            if (!response.isSuccess()) throw errorFor(response.status, "wago recents");
            JSONObject root = new JSONObject(response.text());
            JSONObject addons = root.optJSONObject("addons");
            if (addons != null) {
                java.util.Iterator<String> keys = addons.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    JSONObject entry = addons.optJSONObject(key);
                    if (entry == null) continue;
                    RemoteFile file = parseRecentRelease(entry);
                    if (file != null) result.put(key, file);
                }
            }
        }
        catch (ProviderException e) {
            throw e;
        }
        catch (Exception e) {
            throw new ProviderException(ProviderException.Kind.PARSE, "Malformed Wago batch response", e);
        }
        if (result.isEmpty()) {
            for (String id : externalIds) {
                try {
                    RemoteFile file = getLatestFile(id, flavor, channel, "");
                    if (file != null) result.put(id, file);
                }
                catch (ProviderException e) {
                    if (e.kind == ProviderException.Kind.AUTH
                            || e.kind == ProviderException.Kind.RATE_LIMIT
                            || e.kind == ProviderException.Kind.CANCELLED) {
                        throw e;
                    }
                }
            }
        }
        return result;
    }

    @Override
    public ResolvedDownload resolveDownload(RemoteFile file) throws ProviderException {
        requireKey();
        if (file.downloadUrl == null || file.downloadUrl.isEmpty()) {
            throw new ProviderException(ProviderException.Kind.NOT_FOUND, "Wago release has no download link");
        }
        String url = ProviderUrls.withWagoToken(file.downloadUrl, apiKey);
        return new ResolvedDownload(url, apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, file.size, null);
    }

    @Override
    public String getChangelog(String externalId, String releaseId, WowFlavor flavor)
            throws ProviderException {
        RemoteAddon addon = getById(externalId, flavor);
        if (addon == null || addon.latestFile == null) return "";
        return addon.latestFile.changelog;
    }

    @Override
    public void testKey() throws ProviderException {
        requireKey();
        requestJson(ProviderUrls.wagoPopular("retail"), apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 1);
    }

    private void requireFlavor(WowFlavor flavor) throws ProviderException {
        if (flavor == null || !flavor.baseFlavor().supportsWago()) {
            throw new ProviderException(ProviderException.Kind.UNSUPPORTED,
                    "Wago does not support this game flavor");
        }
    }

    private Map<String, String> apiHeaders() {
        return headers("Authorization", "Bearer " + apiKey);
    }

    private RemoteAddon parseAddon(JSONObject addon) {
        if (addon == null) return null;
        String id = addon.optString("id", "");
        if (id.isEmpty()) id = addon.optString("slug", "");
        if (id.isEmpty()) return null;
        String name = addon.optString("display_name", addon.optString("name", id));
        String summary = addon.optString("summary", addon.optString("description", ""));
        String website = addon.optString("website_url", "");
        long downloads = addon.optLong("download_count", 0);
        String author = "";
        JSONArray authors = addon.optJSONArray("authors");
        if (authors != null && authors.length() > 0) {
            Object first = authors.opt(0);
            if (first instanceof JSONObject) author = ((JSONObject)first).optString("name", "");
            else if (first != null) author = String.valueOf(first);
        }
        RemoteFile latest = parseReleaseBlock(addon.optJSONObject("recent_releases"), id, name);
        if (latest == null) latest = parseReleaseBlock(addon.optJSONObject("releases"), id, name);
        return new RemoteAddon(ProviderType.WAGO, id, name, author, summary, website, downloads, latest);
    }

    private RemoteFile parseRecentRelease(JSONObject entry) {
        String id = entry.optString("id", "");
        String name = entry.optString("name", id);
        RemoteFile file = parseReleaseBlock(entry.optJSONObject("recent_releases"), id, name);
        if (file != null) return file;
        JSONObject matched = entry.optJSONObject("matched_release");
        if (matched != null) return parseRelease(matched, id, name, Channel.STABLE.id);
        return null;
    }

    private RemoteFile parseReleaseBlock(JSONObject releases, String id, String name) {
        if (releases == null) return null;
        for (String channel : new String[]{"stable", "beta", "alpha"}) {
            JSONObject release = releases.optJSONObject(channel);
            if (release == null) continue;
            RemoteFile file = parseRelease(release, id, name, channel);
            if (file != null) return file;
        }
        return null;
    }

    private RemoteFile parseRelease(JSONObject release, String id, String name, String channel) {
        if (release == null) return null;
        String downloadLink = release.optString("download_link", "");
        if (downloadLink.isEmpty()) return null;
        RemoteFile.Builder builder = RemoteFile.builder(ProviderType.WAGO, id)
                .releaseId(release.optString("label", ""))
                .version(release.optString("label", ""))
                .channel(channel)
                .name(name)
                .downloadUrl(downloadLink)
                .releaseDate(CurseForgeProvider.parseDate(release.optString("created_at", "")))
                .changelog(release.optString("changelog", ""));
        for (String key : new String[]{"supported_retail_patch", "supported_classic_patch",
                "supported_mop_patch", "supported_cata_patch"}) {
            String value = release.optString(key, "");
            if (!value.isEmpty()) builder.gameVersion(value);
        }
        return builder.build();
    }

    static String normalizeChannel(String value) {
        if (value == null) return Channel.STABLE.id;
        String key = value.toLowerCase(Locale.US);
        if (key.startsWith("beta")) return Channel.BETA.id;
        if (key.startsWith("alpha")) return Channel.ALPHA.id;
        return Channel.STABLE.id;
    }
}
