package com.wehavephones.mobile.updates;

import com.wehavephones.mobile.addons.repo.Json;
import com.wehavephones.mobile.addons.scan.VersionComparator;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Public release metadata and the checks which must pass before offering an APK. */
public final class AppRelease {
    public static final String PACKAGE = "com.wehavephones";
    public static final String REPOSITORY = "https://github.com/ashmerra/wehavephones";
    public static final String API = "https://api.github.com/repos/ashmerra/wehavephones/releases?per_page=100";
    public static final long MAX_APK_BYTES = 1024L * 1024 * 1024;
    private static final String VERSION_PATTERN = "(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)";

    public final String version, tag, assetName, downloadUrl, checksumUrl, sha256, notes;
    public final long size;

    private AppRelease(String version, String tag, String downloadUrl, String checksumUrl,
                       String sha256, String notes, long size) {
        this.version = version;
        this.tag = tag;
        this.assetName = "WeHavePhones-" + version + ".apk";
        this.downloadUrl = downloadUrl;
        this.checksumUrl = checksumUrl;
        this.sha256 = sha256;
        this.notes = notes;
        this.size = size;
    }

    /** Select by version, not API ordering; asset-only releases and prereleases never qualify. */
    public static AppRelease findUpdate(String json, String installedVersion) throws IOException {
        if (installedVersion == null || !installedVersion.matches(VERSION_PATTERN)) {
            throw new IOException("Unrecognized installed version");
        }
        final List<Object> releases;
        try {
            releases = Json.asList(Json.parse(json));
        } catch (Json.JsonException e) {
            throw new IOException("Invalid release response", e);
        }
        if (releases == null) throw new IOException("Expected a release list");
        AppRelease newest = null;
        for (Object value : releases) {
            Map<String, Object> release = Json.asMap(value);
            if (release == null || !Boolean.FALSE.equals(release.get("draft"))
                    || !Boolean.FALSE.equals(release.get("prerelease"))) continue;
            String tag = Json.optString(release, "tag_name", "");
            String version = tag.startsWith("v") ? tag.substring(1) : tag;
            if (!version.matches(VERSION_PATTERN) || VersionComparator.compare(version, installedVersion) <= 0
                    || (newest != null && VersionComparator.compare(version, newest.version) <= 0)) continue;
            List<Object> assets = Json.asList(release.get("assets"));
            if (assets == null) continue;
            String name = "WeHavePhones-" + version + ".apk";
            String base = REPOSITORY + "/releases/download/" + tag + "/";
            String url = "", checksumUrl = "", digest = "";
            long size = 0;
            boolean duplicate = false;
            for (Object item : assets) {
                Map<String, Object> asset = Json.asMap(item);
                if (asset == null || !"uploaded".equals(Json.optString(asset, "state", ""))) continue;
                String assetName = Json.optString(asset, "name", "");
                String assetUrl = Json.optString(asset, "browser_download_url", "");
                if (name.equals(assetName) && (base + name).equals(assetUrl)) {
                    if (!url.isEmpty()) duplicate = true;
                    url = assetUrl;
                    size = Json.optLong(asset, "size", 0);
                    String hash = Json.optString(asset, "digest", "");
                    digest = hash.matches("sha256:[0-9a-fA-F]{64}") ? hash.substring(7) : "";
                } else if ("SHA256SUMS".equals(assetName) && (base + "SHA256SUMS").equals(assetUrl)) {
                    checksumUrl = assetUrl;
                }
            }
            if (duplicate || url.isEmpty() || size <= 0 || size > MAX_APK_BYTES
                    || (digest.isEmpty() && checksumUrl.isEmpty())) continue;
            String notes = Json.optString(release, "body", "");
            // Release notes are plain text, never rendered as remote HTML.
            if (notes.length() > 16000) notes = notes.substring(0, 16000);
            newest = new AppRelease(version, tag, url, checksumUrl, digest, notes, size);
        }
        return newest;
    }

    public String checksumFrom(String sums) throws IOException {
        String found = null;
        for (String line : sums.split("\\r?\\n")) {
            if (line.length() < 67 || !line.substring(0, 64).matches("[0-9a-fA-F]{64}")
                    || line.charAt(64) != ' ' || (line.charAt(65) != ' ' && line.charAt(65) != '*')) continue;
            if (!assetName.equals(line.substring(66))) continue;
            if (found != null) throw new IOException("Duplicate APK checksum");
            found = line.substring(0, 64);
        }
        if (found == null) throw new IOException("Missing APK checksum");
        return found;
    }

    public void requireIntegrity(long downloadedSize, String downloadedHash, String expectedHash) throws IOException {
        if (downloadedSize != size || expectedHash == null || !expectedHash.matches("[0-9a-fA-F]{64}")
                || !expectedHash.equalsIgnoreCase(downloadedHash)) throw new IOException("APK checksum or size mismatch");
    }

    public void requireCompatible(String packageName, long versionCode, String versionName, long installedCode,
                                  Set<String> installedSigners, Set<String> archiveSigners) throws IOException {
        if (!PACKAGE.equals(packageName)) throw new IOException("APK is for a different app");
        if (versionCode <= installedCode || !version.equals(versionName)) throw new IOException("APK is not the expected newer version");
        if (installedSigners == null || installedSigners.isEmpty() || !installedSigners.equals(archiveSigners)) {
            throw new IOException("APK signing certificate does not match the installed app");
        }
    }
}
