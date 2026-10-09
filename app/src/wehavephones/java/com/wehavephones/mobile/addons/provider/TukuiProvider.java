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
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Tukui API: keyless, two addons (ElvUI, Tukui). */
public final class TukuiProvider extends BaseProvider {
    private static final long CACHE_TTL_MS = 5 * 60 * 1000L;

    private JSONArray cachedAddons;
    private long cachedAt;

    public TukuiProvider() {
        super("");
    }

    @Override
    public ProviderType type() {
        return ProviderType.TUKUI;
    }

    @Override
    public List<RemoteAddon> search(String query, WowFlavor flavor) throws ProviderException {
        JSONArray addons = listAddons();
        ArrayList<RemoteAddon> results = new ArrayList<>();
        String needle = query != null ? query.trim().toLowerCase(Locale.US) : "";
        for (int i = 0; i < addons.length(); i++) {
            JSONObject addon = addons.optJSONObject(i);
            if (addon == null) continue;
            String name = addon.optString("name", "");
            String slug = addon.optString("slug", "");
            String description = addon.optString("small_desc", "");
            if (!needle.isEmpty()
                    && !name.toLowerCase(Locale.US).contains(needle)
                    && !slug.toLowerCase(Locale.US).contains(needle)
                    && !description.toLowerCase(Locale.US).contains(needle)) {
                continue;
            }
            results.add(parseAddon(addon));
        }
        return results;
    }

    @Override
    public RemoteAddon getById(String externalId, WowFlavor flavor) throws ProviderException {
        JSONObject addon = requestJson(ProviderUrls.tukuiAddon(externalId),
                Collections.<String, String>emptyMap(), null, null, 2);
        return parseAddon(addon);
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
        LinkedHashMap<String, RemoteFile> result = new LinkedHashMap<>();
        if (externalIds == null || externalIds.isEmpty()) return result;
        JSONArray addons = listAddons();
        for (String id : externalIds) {
            for (int i = 0; i < addons.length(); i++) {
                JSONObject addon = addons.optJSONObject(i);
                if (addon == null) continue;
                if (id.equals(addon.optString("slug", ""))) {
                    RemoteAddon parsed = parseAddon(addon);
                    if (parsed.latestFile != null) result.put(id, parsed.latestFile);
                }
            }
        }
        return result;
    }

    @Override
    public ResolvedDownload resolveDownload(RemoteFile file) throws ProviderException {
        // Tukui download tokens are issued per response; refresh the detail call.
        RemoteAddon addon = getById(file.externalId, WowFlavor.RETAIL);
        String url = addon != null && addon.latestFile != null ? addon.latestFile.downloadUrl : null;
        if (url == null || url.isEmpty()) url = file.downloadUrl;
        if (url == null || url.isEmpty()) {
            throw new ProviderException(ProviderException.Kind.NOT_FOUND, "Tukui returned no download URL");
        }
        return new ResolvedDownload(url, Collections.<String, String>emptyMap(), null, null, file.size, null);
    }

    @Override
    public String getChangelog(String externalId, String releaseId, WowFlavor flavor)
            throws ProviderException {
        Http.Response response = request(ProviderUrls.tukuiChangelog(externalId),
                Collections.<String, String>emptyMap(), null, null, Http.MAX_TEXT_BYTES, 2);
        String body = response.text();
        if (body.length() > 200 * 1024) body = body.substring(0, 200 * 1024);
        return body;
    }

    @Override
    public void testKey() throws ProviderException {
        listAddons();
    }

    private JSONArray listAddons() throws ProviderException {
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (cachedAddons != null && now - cachedAt < CACHE_TTL_MS) return cachedAddons;
        }
        Http.Response response = request(ProviderUrls.tukuiList(),
                Collections.<String, String>emptyMap(), null, null, Http.MAX_JSON_BYTES, 2);
        JSONArray addons;
        try {
            addons = new JSONArray(response.text());
        }
        catch (Exception e) {
            throw new ProviderException(ProviderException.Kind.PARSE, "Unexpected Tukui response", e);
        }
        synchronized (this) {
            cachedAddons = addons;
            cachedAt = System.currentTimeMillis();
        }
        return addons;
    }

    private RemoteAddon parseAddon(JSONObject addon) {
        String slug = addon.optString("slug", "");
        RemoteFile.Builder file = RemoteFile.builder(ProviderType.TUKUI, slug)
                .releaseId(addon.optString("version", ""))
                .version(addon.optString("version", ""))
                .name(addon.optString("name", slug))
                .downloadUrl(addon.optString("url", ""))
                .changelog(addon.optString("changelog", ""));
        JSONArray directories = addon.optJSONArray("directories");
        if (directories != null) {
            for (int i = 0; i < directories.length(); i++) file.folder(directories.optString(i, ""));
        }
        JSONArray patch = addon.optJSONArray("patch");
        if (patch != null) {
            for (int i = 0; i < patch.length(); i++) file.gameVersion(patch.optString(i, ""));
        }
        return new RemoteAddon(ProviderType.TUKUI, slug, addon.optString("name", slug),
                addon.optString("author", ""), addon.optString("small_desc", ""),
                addon.optString("git_url", ""), 0, file.build());
    }
}
