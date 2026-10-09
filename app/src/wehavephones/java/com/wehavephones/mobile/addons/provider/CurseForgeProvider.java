package com.wehavephones.mobile.addons.provider;

import com.wehavephones.mobile.addons.model.Channel;
import com.wehavephones.mobile.addons.model.ProviderType;
import com.wehavephones.mobile.addons.model.RemoteAddon;
import com.wehavephones.mobile.addons.model.RemoteFile;
import com.wehavephones.mobile.WowFlavor;
import com.wehavephones.mobile.addons.net.Http;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * CurseForge API v1 with a user-supplied API key. The key is only sent to
 * api.curseforge.com and *.forgecdn.net.
 */
public final class CurseForgeProvider extends BaseProvider {
    private static final Set<String> SECRET_HEADERS = Collections.singleton("x-api-key");
    private static final Set<String> SECRET_HOSTS = new LinkedHashSet<>(java.util.Arrays.asList(
            "api.curseforge.com", "*.forgecdn.net"));

    public CurseForgeProvider(String apiKey) {
        super(apiKey);
    }

    @Override
    public ProviderType type() {
        return ProviderType.CURSEFORGE;
    }

    @Override
    public List<RemoteAddon> search(String query, WowFlavor flavor) throws ProviderException {
        requireKey();
        int typeId = flavorTypeId(flavor);
        JSONObject root = requestJson(
                ProviderUrls.curseForgeSearch(typeId, query != null ? query : "", 50),
                apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
        return parseMods(root.optJSONArray("data"), flavor);
    }

    @Override
    public RemoteAddon getById(String externalId, WowFlavor flavor) throws ProviderException {
        requireKey();
        JSONObject root = requestJson(ProviderUrls.curseForgeMod(externalId),
                apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
        JSONObject data = root.optJSONObject("data");
        return data != null ? parseMod(data, flavor) : null;
    }

    @Override
    public RemoteFile getLatestFile(String externalId, WowFlavor flavor, Channel channel,
                                    String currentVersion) throws ProviderException {
        requireKey();
        int typeId = flavorTypeId(flavor);
        JSONArray files = fetchFiles(externalId, typeId);
        if (files.length() == 0) files = fetchFiles(externalId, -1);
        JSONObject chosen = chooseFreshest(files, channel);
        return chosen != null ? parseFile(chosen, externalId) : null;
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
                if (e.kind == ProviderException.Kind.AUTH
                        || e.kind == ProviderException.Kind.RATE_LIMIT
                        || e.kind == ProviderException.Kind.CANCELLED) {
                    throw e;
                }
            }
        }
        return result;
    }

    @Override
    public ResolvedDownload resolveDownload(RemoteFile file) throws ProviderException {
        requireKey();
        String url = file.downloadUrl;
        if (url == null || url.isEmpty()) {
            String releaseId = file.releaseId != null && !file.releaseId.isEmpty()
                    ? file.releaseId : file.version;
            JSONObject root = requestJson(
                    ProviderUrls.curseForgeDownloadUrl(file.externalId, releaseId),
                    apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
            url = root.optString("data", "");
        }
        if (url == null || url.isEmpty()) {
            throw new ProviderException(ProviderException.Kind.NOT_FOUND,
                    "CurseForge did not return a download URL (author-restricted file?)");
        }
        return new ResolvedDownload(url, apiHeaders(), SECRET_HEADERS, SECRET_HOSTS,
                file.size, file.sha1);
    }

    @Override
    public String getChangelog(String externalId, String releaseId, WowFlavor flavor)
            throws ProviderException {
        requireKey();
        if (releaseId == null || releaseId.isEmpty()) return "";
        JSONObject root = requestJson(ProviderUrls.curseForgeChangelog(externalId, releaseId),
                apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 1);
        return stripHtml(root.optString("data", ""));
    }

    @Override
    public void testKey() throws ProviderException {
        requireKey();
        Http.Response response = request(ProviderUrls.curseForgeGame(1), apiHeaders(),
                SECRET_HEADERS, SECRET_HOSTS, Http.MAX_JSON_BYTES, 1);
        if (!response.isSuccess()) throw errorFor(response.status, "curseforge game");
    }

    private Map<String, String> apiHeaders() {
        return headers("x-api-key", apiKey);
    }

    private static int flavorTypeId(WowFlavor flavor) {
        return flavor != null ? flavor.baseFlavor().curseForgeGameVersionTypeId : 517;
    }

    private JSONArray fetchFiles(String modId, int typeId) throws ProviderException {
        JSONObject root = requestJson(ProviderUrls.curseForgeFiles(modId, typeId, 50),
                apiHeaders(), SECRET_HEADERS, SECRET_HOSTS, 2);
        JSONArray data = root.optJSONArray("data");
        return data != null ? data : new JSONArray();
    }

    private List<RemoteAddon> parseMods(JSONArray array, WowFlavor flavor) {
        ArrayList<RemoteAddon> addons = new ArrayList<>();
        if (array == null) return addons;
        for (int i = 0; i < array.length(); i++) {
            JSONObject object = array.optJSONObject(i);
            if (object == null) continue;
            RemoteAddon addon = parseMod(object, flavor);
            if (addon != null) addons.add(addon);
        }
        return addons;
    }

    private RemoteAddon parseMod(JSONObject object, WowFlavor flavor) {
        String id = String.valueOf(object.optLong("id", 0));
        if ("0".equals(id)) return null;
        String name = object.optString("name", "");
        String summary = object.optString("summary", "");
        JSONObject links = object.optJSONObject("links");
        String website = links != null ? links.optString("websiteUrl", "") : "";
        long downloads = object.optLong("downloadCount", 0);
        String author = "";
        JSONArray authors = object.optJSONArray("authors");
        if (authors != null && authors.length() > 0) {
            JSONObject first = authors.optJSONObject(0);
            if (first != null) author = first.optString("name", "");
        }
        RemoteFile latest = null;
        JSONArray latestFiles = object.optJSONArray("latestFiles");
        if (latestFiles != null && latestFiles.length() > 0) {
            JSONObject file = latestFiles.optJSONObject(0);
            if (file != null) latest = parseFile(file, id);
        }
        return new RemoteAddon(ProviderType.CURSEFORGE, id, name, author, summary, website, downloads, latest);
    }

    private RemoteFile parseFile(JSONObject file, String modId) {
        String fileId = String.valueOf(file.optLong("id", 0));
        String modIdValue = String.valueOf(file.optLong("modId", parseLong(modId)));
        RemoteFile.Builder builder = RemoteFile.builder(ProviderType.CURSEFORGE, modIdValue)
                .releaseId(fileId)
                .name(file.optString("fileName", file.optString("displayName", "")))
                .version(file.optString("displayName", file.optString("fileName", "")))
                .releaseDate(parseDate(file.optString("fileDate", "")))
                .size(file.optLong("fileLength", -1));
        int releaseType = file.optInt("releaseType", 1);
        builder.channel(channelForReleaseType(releaseType).id);
        if (!file.isNull("downloadUrl")) {
            String downloadUrl = file.optString("downloadUrl", "");
            if (!downloadUrl.isEmpty()) builder.downloadUrl(downloadUrl);
        }
        JSONArray gameVersions = file.optJSONArray("gameVersions");
        if (gameVersions != null) {
            for (int i = 0; i < gameVersions.length(); i++) {
                builder.gameVersion(gameVersions.optString(i, ""));
            }
        }
        JSONArray hashes = file.optJSONArray("hashes");
        if (hashes != null) {
            for (int i = 0; i < hashes.length(); i++) {
                JSONObject hash = hashes.optJSONObject(i);
                if (hash == null) continue;
                if (hash.optInt("algo", 0) == 1) builder.sha1(hash.optString("value", ""));
            }
        }
        JSONArray dependencies = file.optJSONArray("dependencies");
        if (dependencies != null) {
            for (int i = 0; i < dependencies.length(); i++) {
                JSONObject dependency = dependencies.optJSONObject(i);
                if (dependency == null) continue;
                int relation = dependency.optInt("relationType", 0);
                if (relation == 2 || relation == 3) {
                    builder.dependencyId(String.valueOf(dependency.optLong("modId", 0)));
                }
            }
        }
        return builder.build();
    }

    private JSONObject chooseFreshest(JSONArray files, Channel channel) {
        JSONObject best = null;
        long bestDate = Long.MIN_VALUE;
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i);
            if (file == null || !file.optBoolean("isAvailable", true)) continue;
            Channel candidate = channelForReleaseType(file.optInt("releaseType", 1));
            if (!channel.allows(candidate)) continue;
            long date = parseDate(file.optString("fileDate", ""));
            if (best == null || date > bestDate) {
                best = file;
                bestDate = date;
            }
        }
        return best;
    }

    private static Channel channelForReleaseType(int releaseType) {
        switch (releaseType) {
            case 3: return Channel.ALPHA;
            case 2: return Channel.BETA;
            default: return Channel.STABLE;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        }
        catch (Exception e) {
            return 0;
        }
    }

    public static long parseDate(String value) {
        if (value == null || value.isEmpty()) return 0;
        String normalized = value.trim();
        SimpleDateFormat withZone = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US);
        Date parsed = withZone.parse(normalized, new ParsePosition(0));
        if (parsed != null) return parsed.getTime();
        SimpleDateFormat withoutZone = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US);
        withoutZone.setTimeZone(TimeZone.getTimeZone("UTC"));
        parsed = withoutZone.parse(normalized, new ParsePosition(0));
        return parsed != null ? parsed.getTime() : 0;
    }

    static String stripHtml(String html) {
        if (html == null) return "";
        String text = html.replaceAll("(?s)<(script|style)[^>]*>.*?</\\1>", " ");
        text = text.replaceAll("<br\\s*/?>", "\n");
        text = text.replaceAll("</p>", "\n");
        text = text.replaceAll("<[^>]+>", "");
        text = text.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
    }
}
