package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.core.FileUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;

public final class WeHavePhonesRuntimeProfiles {
    public static final String ASSET_PATH = "wehavephones/wehavephones-runtime-profiles.json";
    public static final String DEFAULT_PRODUCT_DIR = "_retail_";
    /** The WoW graphics API flag used unless the device profile sets another. */
    public static final String DEFAULT_WOW_API_ARG = "-d3d12";
    public static final String DEFAULT_WOW_EXECUTABLE = "Wow-ARM64.exe";

    public enum Mode {
        SINGLE, HANDOFF
    }

    public static final class Artifact {
        public final String asset;
        public final String target;
        public final String sha256;

        Artifact(String asset, String target, String sha256) {
            this.asset = asset;
            this.target = target;
            this.sha256 = sha256;
        }
    }

    public static final class Layer {
        public final String name;
        public final String archive;
        public final String sizeLabel;
        public final String url;
        public final String sha256;
        public final String asset;

        Layer(String name, String archive, String sizeLabel, String url, String sha256, String asset) {
            this.name = name;
            this.archive = archive;
            this.sizeLabel = sizeLabel;
            this.url = url;
            this.sha256 = sha256;
            this.asset = asset;
        }

        public boolean hasDownload() {
            return url != null && !url.isEmpty();
        }
    }

    public static final class Profile {
        public final String name;
        public final String description;
        public final String wineVersion;
        public final String bnetFlags;
        public final String dxwrapper;
        public final String dxwrapperAssets;
        public final LinkedHashMap<String, String> env;
        public final LinkedHashMap<String, String> graphics;
        public final ArrayList<Artifact> ntdll;
        public final ArrayList<Artifact> files;
        public final Layer layer;

        Profile(String name, String description, String wineVersion, String bnetFlags,
                String dxwrapper, String dxwrapperAssets, LinkedHashMap<String, String> env,
                LinkedHashMap<String, String> graphics, ArrayList<Artifact> ntdll,
                ArrayList<Artifact> files, Layer layer) {
            this.name = name;
            this.description = description;
            this.wineVersion = wineVersion;
            this.bnetFlags = bnetFlags;
            this.dxwrapper = dxwrapper;
            this.dxwrapperAssets = dxwrapperAssets;
            this.env = env;
            this.graphics = graphics;
            this.ntdll = ntdll;
            this.files = files;
            this.layer = layer;
        }

        public String buildBnetBat(String template) {
            String flags = bnetFlags != null ? bnetFlags.trim() : "";
            return template.replace("{flags}", flags.isEmpty() ? "" : " " + flags);
        }
    }

    public static final class ShortcutSpec {
        public final String name;
        public final String target;

        ShortcutSpec(String name, String target) {
            this.name = name;
            this.target = target;
        }
    }

    private static volatile WeHavePhonesRuntimeProfiles cached;

    private final Mode mode;
    private final String defaultProfileName;
    private final String bnetStageProfileName;
    private final String wowStageProfileName;
    private final String singleStageProfileName;
    private final ShortcutSpec bnetShortcut;
    private final ShortcutSpec wowShortcut;
    private final String bnetBatTemplate;
    private final String wowBatTemplate;
    private final LinkedHashMap<String, Profile> profiles = new LinkedHashMap<>();

    private WeHavePhonesRuntimeProfiles(JSONObject json) throws JSONException {
        String modeName = json.optString("mode", "handoff").trim().toUpperCase(Locale.US);
        mode = modeName.equals("SINGLE") ? Mode.SINGLE : Mode.HANDOFF;
        defaultProfileName = json.optString("defaultProfile", "");
        bnetStageProfileName = json.optString("bnetStageProfile", defaultProfileName);
        wowStageProfileName = json.optString("wowStageProfile", defaultProfileName);
        singleStageProfileName = json.optString("singleStageProfile", defaultProfileName);
        bnetShortcut = parseShortcut(json.optJSONObject("bnetShortcut"), "Battle.net", "C:\\bnet.bat");
        wowShortcut = parseShortcut(json.optJSONObject("wowShortcut"), "AAWoW_Retail", "C:\\wow.bat");
        bnetBatTemplate = json.optString("bnetBat", "");
        wowBatTemplate = json.optString("wowBat", "");

        JSONObject profileObjects = json.optJSONObject("profiles");
        if (profileObjects == null) throw new JSONException("Missing profiles object");
        Iterator<String> keys = profileObjects.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            profiles.put(name, parseProfile(name, profileObjects.getJSONObject(name)));
        }
        if (profiles.isEmpty()) throw new JSONException("No runtime profiles defined");
    }

    public static WeHavePhonesRuntimeProfiles load(Context context) throws JSONException {
        WeHavePhonesRuntimeProfiles local = cached;
        if (local != null) return local;
        byte[] data = FileUtils.read(context, ASSET_PATH);
        if (data == null) throw new JSONException("Bundled runtime profile asset missing: " + ASSET_PATH);
        local = new WeHavePhonesRuntimeProfiles(new JSONObject(new String(data, java.nio.charset.StandardCharsets.UTF_8)));
        cached = local;
        return local;
    }

    public static WeHavePhonesRuntimeProfiles loadOrNull(Context context) {
        try {
            return load(context);
        }
        catch (JSONException e) {
            return null;
        }
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isHandoff() {
        return mode == Mode.HANDOFF;
    }

    public String getDefaultProfileName() {
        return !defaultProfileName.isEmpty() ? defaultProfileName : firstProfileName();
    }

    public ArrayList<Profile> getProfiles() {
        ArrayList<Profile> list = new ArrayList<>(profiles.values());
        Collections.sort(list, new Comparator<Profile>() {
            @Override
            public int compare(Profile first, Profile second) {
                return first.name.compareTo(second.name);
            }
        });
        return list;
    }

    public Profile getProfile(String name) {
        return name != null ? profiles.get(name) : null;
    }

    public Profile getSelectedProfile(Context context) {
        if (mode == Mode.SINGLE) return getSingleStageProfile();
        Profile selected = getProfile(WeHavePhonesConfig.getRuntimeProfile(context));
        return selected != null ? selected : getBnetStageProfile();
    }

    public Profile getBnetStageProfile() {
        if (mode == Mode.SINGLE) return getSingleStageProfile();
        return firstAvailable(bnetStageProfileName, defaultProfileName);
    }

    public Profile getWowStageProfile() {
        if (mode == Mode.SINGLE) return getSingleStageProfile();
        return firstAvailable(wowStageProfileName, bnetStageProfileName, defaultProfileName);
    }

    public Profile getSingleStageProfile() {
        return firstAvailable(singleStageProfileName, wowStageProfileName, defaultProfileName);
    }

    public ShortcutSpec getBnetShortcut() {
        return bnetShortcut;
    }

    public ShortcutSpec getWowShortcut() {
        return wowShortcut;
    }

    public String buildBnetBat(Profile profile) {
        String template = !bnetBatTemplate.isEmpty() ? bnetBatTemplate : defaultBnetBat();
        return profile.buildBnetBat(template);
    }

    public String buildWowBat(String productDir, String executable) {
        return buildWowBat(productDir, executable, null);
    }

    /** {@code apiArg} is the device profile's graphics API flag; null or empty keeps "-d3d12". */
    public String buildWowBat(String productDir, String executable, String apiArg) {
        String template = !wowBatTemplate.isEmpty() ? wowBatTemplate : defaultWowBat();
        String resolvedProduct = productDir != null && !productDir.isEmpty() ? productDir : DEFAULT_PRODUCT_DIR;
        String resolvedExecutable = executable != null && !executable.isEmpty() ? executable : DEFAULT_WOW_EXECUTABLE;
        String resolvedApiArg = apiArg != null && !apiArg.isEmpty() ? apiArg : DEFAULT_WOW_API_ARG;
        return template.replace("{productDir}", resolvedProduct).replace("{exe}", resolvedExecutable).replace("{apiArg}", resolvedApiArg);
    }

    public String describe() {
        return "mode=" + mode.name().toLowerCase(Locale.US)
                + " bnet=" + getBnetStageProfile().name
                + " wow=" + getWowStageProfile().name
                + " profiles=" + profiles.size();
    }

    private Profile firstAvailable(String... names) {
        for (String name : names) {
            if (name == null || name.isEmpty()) continue;
            Profile profile = profiles.get(name);
            if (profile != null) return profile;
        }
        return profiles.values().iterator().next();
    }

    private String firstProfileName() {
        Iterator<String> keys = profiles.keySet().iterator();
        return keys.hasNext() ? keys.next() : "";
    }

    private static ShortcutSpec parseShortcut(JSONObject json, String defaultName, String defaultTarget) {
        if (json == null) return new ShortcutSpec(defaultName, defaultTarget);
        String name = json.optString("name", defaultName);
        String target = json.optString("target", defaultTarget);
        return new ShortcutSpec(name, target);
    }

    private static Profile parseProfile(String name, JSONObject json) throws JSONException {
        String description = json.optString("description", "");
        String wineVersion = json.optString("wineVersion", "");
        String bnetFlags = json.optString("bnetFlags", "");
        String dxwrapper = json.optString("dxwrapper", "");
        String dxwrapperAssets = json.optString("dxwrapperAssets", "");
        LinkedHashMap<String, String> env = parseStringMap(json.optJSONObject("env"));
        LinkedHashMap<String, String> graphics = parseStringMap(json.optJSONObject("graphics"));
        ArrayList<Artifact> ntdll = parseArtifacts(json.optJSONArray("ntdll"));
        ArrayList<Artifact> files = parseArtifacts(json.optJSONArray("files"));
        Layer layer = parseLayer(json.optJSONObject("layer"));
        return new Profile(name, description, wineVersion, bnetFlags, dxwrapper, dxwrapperAssets, env, graphics, ntdll, files, layer);
    }

    private static ArrayList<Artifact> parseArtifacts(JSONArray artifacts) {
        ArrayList<Artifact> parsed = new ArrayList<>();
        if (artifacts == null) return parsed;
        for (int i = 0; i < artifacts.length(); i++) {
            JSONObject artifact = artifacts.optJSONObject(i);
            if (artifact == null) continue;
            String asset = artifact.optString("asset", "");
            String target = artifact.optString("target", "");
            if (asset.isEmpty() || target.isEmpty()) continue;
            parsed.add(new Artifact(asset, target, artifact.optString("sha256", "")));
        }
        return parsed;
    }

    private static Layer parseLayer(JSONObject json) {
        if (json == null) return null;
        String name = json.optString("name", "");
        if (name.isEmpty()) return null;
        return new Layer(name, json.optString("archive", ""), json.optString("sizeLabel", ""),
                json.optString("url", ""), json.optString("sha256", ""), json.optString("asset", ""));
    }

    private static LinkedHashMap<String, String> parseStringMap(JSONObject json) throws JSONException {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        if (json == null) return map;
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            map.put(key, json.getString(key));
        }
        return map;
    }

    private static String defaultBnetBat() {
        return "@echo off\r\n"
                + "cd /d \"C:\\Program Files (x86)\\Battle.net\"\r\n"
                + "if exist \"C:\\arm64-vkd3d.dll\" copy /Y \"C:\\arm64-vkd3d.dll\" \"C:\\windows\\system32\\d3d12.dll\" >NUL\r\n"
                + "if exist \"C:\\arm64-libc++.dll\" copy /Y \"C:\\arm64-libc++.dll\" \"C:\\windows\\system32\\libc++.dll\" >NUL\r\n"
                + "if exist \"C:\\arm64-libunwind.dll\" copy /Y \"C:\\arm64-libunwind.dll\" \"C:\\windows\\system32\\libunwind.dll\" >NUL\r\n"
                + "Battle.net.exe{flags}\r\n";
    }

    private static String defaultWowBat() {
        return "@echo off\r\ncd /d \"C:\\Program Files (x86)\\World of Warcraft\\{productDir}\"\r\n{exe} {apiArg}\r\n";
    }
}
