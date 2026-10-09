package com.wehavephones.mobile.addons.repo;

import com.wehavephones.mobile.addons.model.AddonRecord;
import com.wehavephones.mobile.addons.model.TocData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * In-memory addon index. Serialization is dependency-free (repo.Json) so the
 * host JVM self-test can round-trip it.
 */
public final class AddonIndex {
    public static final int SCHEMA_VERSION = 1;

    public int schemaVersion = SCHEMA_VERSION;
    public final ArrayList<AddonRecord> addons = new ArrayList<>();
    public String wowRootOverride = "";
    public boolean preferNolib = false;
    public boolean autoCheckOnOpen = true;
    public boolean confirmDelete = true;

    public static final class Settings {
        public final boolean preferNolib;
        public final boolean autoCheckOnOpen;
        public final boolean confirmDelete;
        public final String wowRootOverride;

        public Settings(boolean preferNolib, boolean autoCheckOnOpen, boolean confirmDelete,
                        String wowRootOverride) {
            this.preferNolib = preferNolib;
            this.autoCheckOnOpen = autoCheckOnOpen;
            this.confirmDelete = confirmDelete;
            this.wowRootOverride = wowRootOverride != null ? wowRootOverride : "";
        }
    }

    public AddonRecord findById(String id) {
        if (id == null) return null;
        for (AddonRecord record : addons) {
            if (id.equals(record.id)) return record;
        }
        return null;
    }

    public AddonRecord findByBinding(String providerId, String externalId, String flavorDir) {
        if (providerId == null || externalId == null || externalId.isEmpty()) return null;
        for (AddonRecord record : addons) {
            if (record.providerId.equals(providerId)
                    && record.externalId.equals(externalId)
                    && record.flavorDir.equals(flavorDir)) {
                return record;
            }
        }
        return null;
    }

    public ArrayList<AddonRecord> forFlavor(String flavorDir) {
        ArrayList<AddonRecord> result = new ArrayList<>();
        if (flavorDir == null) return result;
        for (AddonRecord record : addons) {
            if (flavorDir.equals(record.flavorDir)) result.add(record);
        }
        return result;
    }

    public void upsert(AddonRecord record) {
        for (int i = 0; i < addons.size(); i++) {
            if (addons.get(i).id.equals(record.id)) {
                addons.set(i, record);
                return;
            }
        }
        addons.add(record);
    }

    public void remove(AddonRecord record) {
        if (record == null) return;
        for (int i = addons.size() - 1; i >= 0; i--) {
            if (addons.get(i).id.equals(record.id)) addons.remove(i);
        }
    }

    public String toJsonString() {
        Map<String, Object> root = Json.newObject();
        root.put("schemaVersion", (long)schemaVersion);
        ArrayList<Object> array = new ArrayList<>();
        for (AddonRecord record : addons) array.add(recordToMap(record));
        root.put("addons", array);
        Map<String, Object> settings = Json.newObject();
        settings.put("wowRootOverride", wowRootOverride);
        settings.put("preferNolib", preferNolib);
        settings.put("autoCheckOnOpen", autoCheckOnOpen);
        settings.put("confirmDelete", confirmDelete);
        root.put("settings", settings);
        return Json.write(root);
    }

    public static AddonIndex fromJsonString(String text) throws Json.JsonException {
        AddonIndex index = new AddonIndex();
        Map<String, Object> root = Json.asMap(Json.parse(text));
        if (root == null) throw new Json.JsonException("index root is not an object");
        index.schemaVersion = (int)Json.optLong(root, "schemaVersion", SCHEMA_VERSION);
        List<Object> array = Json.asList(root.get("addons"));
        if (array != null) {
            for (Object item : array) {
                Map<String, Object> map = Json.asMap(item);
                if (map == null) continue;
                AddonRecord record = recordFromMap(map);
                if (record != null) index.addons.add(record);
            }
        }
        Map<String, Object> settings = Json.asMap(root.get("settings"));
        if (settings != null) {
            index.wowRootOverride = Json.optString(settings, "wowRootOverride", "");
            index.preferNolib = Json.optBoolean(settings, "preferNolib", false);
            index.autoCheckOnOpen = Json.optBoolean(settings, "autoCheckOnOpen", true);
            index.confirmDelete = Json.optBoolean(settings, "confirmDelete", true);
        }
        return index;
    }

    private static Map<String, Object> recordToMap(AddonRecord record) {
        Map<String, Object> map = Json.newObject();
        map.put("id", record.id);
        map.put("flavor", record.flavorDir);
        map.put("provider", record.providerId);
        map.put("externalId", record.externalId);
        map.put("providerVersionId", record.providerVersionId);
        map.put("channel", record.channel);
        map.put("name", record.name);
        map.put("folderNames", new ArrayList<Object>(record.folderNames));
        map.put("toc", tocToMap(record.toc));
        map.put("enabled", record.enabled);
        map.put("ignored", record.ignored);
        map.put("heuristic", record.heuristic);
        map.put("unrecognized", record.unrecognized);
        map.put("installedAt", record.installedAt);
        map.put("installedFrom", record.installedFrom);
        map.put("archiveSha256", record.archiveSha256);
        map.put("latestVersion", record.latestVersion);
        map.put("latestReleaseId", record.latestReleaseId);
        map.put("latestReleaseDate", record.latestReleaseDate);
        map.put("latestUrl", record.latestUrl);
        map.put("latestChangelog", record.latestChangelog);
        map.put("status", record.status);
        map.put("lastCheckedAt", record.lastCheckedAt);
        map.put("lastError", record.lastError);
        return map;
    }

    private static AddonRecord recordFromMap(Map<String, Object> map) {
        String id = Json.optString(map, "id", "");
        if (id.isEmpty()) return null;
        AddonRecord record = new AddonRecord();
        record.id = id;
        record.flavorDir = Json.optString(map, "flavor", "");
        record.providerId = Json.optString(map, "provider", "local");
        record.externalId = Json.optString(map, "externalId", "");
        record.providerVersionId = Json.optString(map, "providerVersionId", "");
        record.channel = Json.optString(map, "channel", "stable");
        record.name = Json.optString(map, "name", "");
        record.folderNames.clear();
        record.folderNames.addAll(Json.optStringList(map, "folderNames"));
        record.toc = tocFromMap(Json.asMap(map.get("toc")));
        record.enabled = Json.optBoolean(map, "enabled", true);
        record.ignored = Json.optBoolean(map, "ignored", false);
        record.heuristic = Json.optBoolean(map, "heuristic", false);
        record.unrecognized = Json.optBoolean(map, "unrecognized", false);
        record.installedAt = Json.optLong(map, "installedAt", 0);
        record.installedFrom = Json.optString(map, "installedFrom", "");
        record.archiveSha256 = Json.optString(map, "archiveSha256", "");
        record.latestVersion = Json.optString(map, "latestVersion", "");
        record.latestReleaseId = Json.optString(map, "latestReleaseId", "");
        record.latestReleaseDate = Json.optLong(map, "latestReleaseDate", 0);
        record.latestUrl = Json.optString(map, "latestUrl", "");
        record.latestChangelog = Json.optString(map, "latestChangelog", "");
        record.status = Json.optString(map, "status", "unknown");
        record.lastCheckedAt = Json.optLong(map, "lastCheckedAt", 0);
        record.lastError = Json.optString(map, "lastError", "");
        return record;
    }

    private static Map<String, Object> tocToMap(TocData toc) {
        Map<String, Object> map = Json.newObject();
        if (toc == null) return map;
        map.put("baseName", toc.baseName);
        map.put("tocFileName", toc.tocFileName);
        map.put("version", toc.version);
        map.put("interfaces", new ArrayList<Object>(toc.interfaces));
        map.put("title", toc.title);
        map.put("author", toc.author);
        map.put("notes", toc.notes);
        map.put("website", toc.website);
        map.put("curseId", toc.curseId);
        map.put("wagoId", toc.wagoId);
        map.put("wowiId", toc.wowiId);
        map.put("tukuiId", toc.tukuiId);
        map.put("tukuiFolders", new ArrayList<Object>(toc.tukuiFolders));
        map.put("dependencies", new ArrayList<Object>(toc.dependencies));
        map.put("optionalDeps", new ArrayList<Object>(toc.optionalDeps));
        map.put("loadOnDemand", toc.loadOnDemand);
        map.put("defaultState", toc.defaultState);
        map.put("allowLoadGameType", new ArrayList<Object>(toc.allowLoadGameType));
        return map;
    }

    private static TocData tocFromMap(Map<String, Object> map) {
        TocData toc = new TocData();
        if (map == null) return toc;
        toc.baseName = Json.optString(map, "baseName", "");
        toc.tocFileName = Json.optString(map, "tocFileName", "");
        toc.version = Json.optString(map, "version", "");
        toc.interfaces.addAll(Json.optStringList(map, "interfaces"));
        toc.title = Json.optString(map, "title", "");
        toc.author = Json.optString(map, "author", "");
        toc.notes = Json.optString(map, "notes", "");
        toc.website = Json.optString(map, "website", "");
        toc.curseId = Json.optString(map, "curseId", "");
        toc.wagoId = Json.optString(map, "wagoId", "");
        toc.wowiId = Json.optString(map, "wowiId", "");
        toc.tukuiId = Json.optString(map, "tukuiId", "");
        toc.tukuiFolders.addAll(Json.optStringList(map, "tukuiFolders"));
        toc.dependencies.addAll(Json.optStringList(map, "dependencies"));
        toc.optionalDeps.addAll(Json.optStringList(map, "optionalDeps"));
        toc.loadOnDemand = Json.optBoolean(map, "loadOnDemand", false);
        toc.defaultState = Json.optString(map, "defaultState", "");
        toc.allowLoadGameType.addAll(Json.optStringList(map, "allowLoadGameType"));
        return toc;
    }

    public LinkedHashMap<String, AddonRecord> byId() {
        LinkedHashMap<String, AddonRecord> map = new LinkedHashMap<>();
        for (AddonRecord record : addons) map.put(record.id, record);
        return map;
    }
}
