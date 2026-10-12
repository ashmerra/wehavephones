import com.wehavephones.mobile.addons.repo.Json;
import com.wehavephones.mobile.updates.AppRelease;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AppUpdatesSelfTest {
    private static final String HASH = String.join("", Collections.nCopies(64, "a"));
    private static int checks;

    public static void main(String[] args) throws Exception {
        Map<String, Object> v4 = release("v0.0.4");
        Map<String, Object> v10 = release("v0.0.10");
        Map<String, Object> assetOnly = release("assets-v1");
        Map<String, Object> preview = release("v9.0.0");
        preview.put("prerelease", true);
        Map<String, Object> draft = release("v8.0.0");
        draft.put("draft", true);
        AppRelease chosen = AppRelease.findUpdate(json(v4, assetOnly, preview, v10, draft), "0.0.3");
        check(chosen != null && "0.0.10".equals(chosen.version), "select newest numeric version independently of ordering");
        check(AppRelease.findUpdate(json(v4), "0.0.4") == null, "same version is not an update");
        check(AppRelease.findUpdate(json(v4), "0.0.5") == null, "older version is not an update");
        check(AppRelease.findUpdate(json(assetOnly, preview, draft), "0.0.3") == null, "exclude assets, drafts and prereleases");
        rejects(() -> AppRelease.findUpdate("{}", "0.0.3"), "reject non-list API response");
        rejects(() -> AppRelease.findUpdate("[", "0.0.3"), "reject truncated JSON");
        rejects(() -> AppRelease.findUpdate(json(v4), "0.0.3-dev"), "reject development version");

        Map<String, Object> missingFlags = release("v0.0.4");
        missingFlags.remove("prerelease");
        check(AppRelease.findUpdate(json(missingFlags), "0.0.3") == null, "missing publication flags fail closed");
        for (String badUrl : Arrays.asList("http://github.com/ashmerra/wehavephones/releases/download/v0.0.4/WeHavePhones-0.0.4.apk",
                "https://evil.example/update.apk", "https://github.com/other/repo/releases/download/v0.0.4/WeHavePhones-0.0.4.apk",
                "https://github.com/ashmerra/wehavephones/releases/download/v0.0.3/WeHavePhones-0.0.4.apk")) {
            Map<String, Object> item = release("v0.0.4");
            firstAsset(item).put("browser_download_url", badUrl);
            check(AppRelease.findUpdate(json(item), "0.0.3") == null, "reject unexpected download source");
        }
        Map<String, Object> large = release("v0.0.4");
        firstAsset(large).put("size", AppRelease.MAX_APK_BYTES + 1);
        check(AppRelease.findUpdate(json(large), "0.0.3") == null, "reject oversized metadata");
        Map<String, Object> debug = release("v0.0.4");
        firstAsset(debug).put("name", "app-wehavephones-debug.apk");
        check(AppRelease.findUpdate(json(debug), "0.0.3") == null, "exclude debug asset");
        Map<String, Object> unuploaded = release("v0.0.4");
        firstAsset(unuploaded).put("state", "new");
        check(AppRelease.findUpdate(json(unuploaded), "0.0.3") == null, "exclude unfinished upload");
        Map<String, Object> duplicate = release("v0.0.4");
        duplicate.put("assets", Arrays.asList(firstAsset(duplicate), firstAsset(duplicate)));
        check(AppRelease.findUpdate(json(duplicate), "0.0.3") == null, "reject ambiguous APK assets");
        Map<String, Object> noHash = release("v0.0.4");
        firstAsset(noHash).put("digest", null);
        check(AppRelease.findUpdate(json(noHash), "0.0.3") == null, "require digest or checksum asset");
        noHash.put("assets", Arrays.asList(firstAsset(noHash), asset("v0.0.4", "SHA256SUMS", 100)));
        AppRelease fallback = AppRelease.findUpdate(json(noHash), "0.0.3");
        check(fallback != null && fallback.sha256.isEmpty() && !fallback.checksumUrl.isEmpty(), "support older checksum-only releases");
        check(HASH.equals(fallback.checksumFrom(HASH + "  WeHavePhones-0.0.4.apk\n")), "read SHA256SUMS");
        check(HASH.equals(fallback.checksumFrom(HASH + " *WeHavePhones-0.0.4.apk\r\n")), "read binary checksum and CRLF");
        rejects(() -> fallback.checksumFrom(HASH + "  other.apk\n"), "require exact checksum filename");
        rejects(() -> fallback.checksumFrom(HASH + "  ../WeHavePhones-0.0.4.apk\n"), "reject checksum path tricks");
        rejects(() -> fallback.checksumFrom(HASH + "  WeHavePhones-0.0.4.apk\n" + HASH + "  WeHavePhones-0.0.4.apk\n"), "reject duplicate checksums");
        fallback.requireIntegrity(500, HASH, HASH.toUpperCase());
        check(true, "verify size and case-insensitive SHA256");
        rejects(() -> fallback.requireIntegrity(499, HASH, HASH), "reject incomplete download");
        rejects(() -> fallback.requireIntegrity(500, HASH.replace('a', 'b'), HASH), "reject corrupt download");
        rejects(() -> fallback.requireIntegrity(500, "", ""), "reject missing verification");
        fallback.requireCompatible(AppRelease.PACKAGE, 4, "0.0.4", 3, Collections.singleton("certificate"), Collections.singleton("certificate"));
        check(true, "accept a correctly signed newer APK");
        rejects(() -> fallback.requireCompatible(AppRelease.PACKAGE + ".dev", 4, "0.0.4", 3, Collections.singleton("c"), Collections.singleton("c")), "reject wrong app");
        rejects(() -> fallback.requireCompatible(AppRelease.PACKAGE, 3, "0.0.4", 3, Collections.singleton("c"), Collections.singleton("c")), "reject unchanged versionCode");
        rejects(() -> fallback.requireCompatible(AppRelease.PACKAGE, 2, "0.0.4", 3, Collections.singleton("c"), Collections.singleton("c")), "reject downgrade");
        rejects(() -> fallback.requireCompatible(AppRelease.PACKAGE, 4, "0.0.5", 3, Collections.singleton("c"), Collections.singleton("c")), "reject mismatched release version");
        rejects(() -> fallback.requireCompatible(AppRelease.PACKAGE, 4, "0.0.4", 3, Collections.singleton("c"), Collections.singleton("other")), "reject other signer");
        rejects(() -> fallback.requireCompatible(AppRelease.PACKAGE, 4, "0.0.4", 3, Collections.emptySet(), Collections.emptySet()), "reject unsigned APK");
        System.out.println("AppUpdatesSelfTest: " + checks + " checks passed");
    }

    private static Map<String, Object> release(String tag) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("tag_name", tag);
        value.put("draft", false);
        value.put("prerelease", false);
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        value.put("assets", Collections.singletonList(asset(tag, "WeHavePhones-" + version + ".apk", 500)));
        return value;
    }
    private static Map<String, Object> asset(String tag, String name, long size) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("name", name);
        value.put("state", "uploaded");
        value.put("size", size);
        value.put("digest", "sha256:" + HASH);
        value.put("browser_download_url", AppRelease.REPOSITORY + "/releases/download/" + tag + "/" + name);
        return value;
    }
    private static Map<String, Object> firstAsset(Map<String, Object> release) {
        return Json.asMap(Json.asList(release.get("assets")).get(0));
    }
    @SafeVarargs private static String json(Map<String, Object>... values) { return Json.write(Arrays.asList(values)); }
    private interface Checked { void run() throws Exception; }
    private static void rejects(Checked operation, String label) throws Exception {
        try { operation.run(); } catch (IOException expected) { check(true, label); return; }
        throw new AssertionError(label);
    }
    private static void check(boolean condition, String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}
