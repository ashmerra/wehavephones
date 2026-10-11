package com.wehavephones.mobile;

import android.content.Context;

import com.wehavephones.core.FileUtils;
import com.wehavephones.core.DefaultVersion;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Host JVM self-test for the WeHavePhonesRuntimeProfiles schema and the
 * single/handoff stage selection. Compiled and run by
 * scripts/test-wehavephones-runtime.sh against the bundled profile JSON using a
 * file-serving FileUtils stub and the cached org.json jar.
 */
public final class RuntimeProfileParseSelfTest {
    private static int passed = 0;
    private static int failed = 0;

    public static void main(String[] args) throws Exception {
        File bundled = new File(args.length > 0
                ? args[0]
                : "app/src/wehavephones/assets/wehavephones/wehavephones-runtime-profiles.json");
        testSingleBundled(bundled);
        testHandoffSelection();
        System.out.println("RuntimeProfileParseSelfTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.out.println("RESULT: FAIL");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }

    // ------------------------------------------------------------------ tests

    private static void testSingleBundled(File bundled) throws Exception {
        WeHavePhonesConfig.selectedProfile = "p10-battle";
        WeHavePhonesRuntimeProfiles profiles = load(bundled);

        check(profiles.getProfiles().size() == 1, "bundled JSON ships only p10-ge34");

        check(profiles.getMode() == WeHavePhonesRuntimeProfiles.Mode.SINGLE, "bundled mode is single");
        check(!profiles.isHandoff(), "bundled mode is not handoff");
        check("p10-ge34".equals(profiles.getDefaultProfileName()), "defaultProfile is p10-ge34");
        check("p10-ge34".equals(profiles.getBnetStageProfile().name), "single mode bnet stage is p10-ge34");
        check("p10-ge34".equals(profiles.getWowStageProfile().name), "single mode wow stage is p10-ge34");
        check("p10-ge34".equals(profiles.getSelectedProfile(new Context()).name),
                "single mode ignores the stored selection (p10-ge34)");

        WeHavePhonesRuntimeProfiles.Profile p10 = profiles.getProfile("p10-ge34");
        String wowBat = profiles.buildWowBat("_retail_", "Wow-ARM64.exe");
        check(wowBat.endsWith("\r\nWow-ARM64.exe -d3d12\r\n"), "default wow.bat keeps -d3d12");
        check(wowBat.equals(profiles.buildWowBat("_retail_", "Wow-ARM64.exe", null)), "null api arg keeps wow.bat byte-identical");
        check(profiles.buildWowBat("_retail_", "Wow-ARM64.exe", "-d3d11").endsWith("\r\nWow-ARM64.exe -d3d11\r\n"), "device api arg replaces -d3d12");
        check(!wowBat.contains("{"), "wow.bat has no unresolved placeholders");
        check(p10 != null, "p10-ge34 profile exists");
        check("proton-10.0-34-arm64ec".equals(p10.wineVersion), "p10-ge34 wineVersion");
        check(("turnip" + DefaultVersion.ADRENOTOOLS_TURNIP).equals(p10.graphics.get("version")),
                "single runtime upgrades existing containers to the bundled Turnip default");
        check("false".equals(p10.env.get("MESA_SHADER_CACHE_DISABLE")),
                "single runtime re-enables the shader cache on existing containers");
        check("off".equals(p10.env.get("WINE_FDSAN_LEVEL")),
                "single runtime requests the Wine fd ownership workaround");
        check(p10.ntdll.size() == 3, "p10-ge34 has 3 ntdll artifacts");
        check("ntdll/ntdll-grace-copysafe-10.0-34.dll".equals(p10.ntdll.get(0).asset),
                "p10-ge34 PE asset name");
        check("09adc21f8400fe3d2c46e227d1c423e0e06a91b338b5343beddc0da014df9cd8"
                .equals(p10.ntdll.get(0).sha256), "p10-ge34 PE sha256");
        check("ntdll/ntdll-grace-copysafe-10.0-34.so".equals(p10.ntdll.get(2).asset),
                "p10-ge34 unix asset name");
        check("9f3e55a032b2a22fb025676b9c010ec97a454c84b8d955036d452699b3b0cd16"
                .equals(p10.ntdll.get(2).sha256), "p10-ge34 unix sha256");
        check(p10.files.size() == 3, "p10-ge34 has 3 generic files");
        check("container:.wine/drive_c/arm64-vkd3d.dll".equals(p10.files.get(0).target),
                "p10-ge34 vkd3d target");
        check(p10.layer != null && p10.layer.hasDownload(), "p10-ge34 layer carries a download");
        check("110 MB".equals(p10.layer.sizeLabel), "p10-ge34 layer size label");
        check("GE-proton-10.0-34-arm64ec.wcp".equals(p10.layer.archive), "p10-ge34 layer archive");
        check("wehavephones/wine/GE-proton-10.0-34-arm64ec.wcp".equals(p10.layer.asset), "p10-ge34 layer bundled for offline setup");
    }

    private static void testHandoffSelection() throws Exception {
        File handoff = File.createTempFile("wehavephones-handoff", ".json");
        handoff.deleteOnExit();
        Files.write(handoff.toPath(), ("{" +
                "\"schemaVersion\":2,\"mode\":\"handoff\"," +
                "\"defaultProfile\":\"p10-battle\"," +
                "\"bnetStageProfile\":\"p10-battle\"," +
                "\"wowStageProfile\":\"p11-wow\"," +
                "\"singleStageProfile\":\"p10-ge34\"," +
                "\"profiles\":{" +
                "\"p10-battle\":{\"wineVersion\":\"proton-10-arm64ec\"}," +
                "\"p11-battle\":{\"wineVersion\":\"proton-11.0-7.1-arm64ec\"}," +
                "\"p11-wow\":{\"wineVersion\":\"proton-11.0-7.1-arm64ec\"}}}}")
                .getBytes(StandardCharsets.UTF_8));

        WeHavePhonesConfig.selectedProfile = null;
        WeHavePhonesRuntimeProfiles profiles = load(handoff);
        check(profiles.isHandoff(), "handoff mode is handoff");
        check("p10-battle".equals(profiles.getBnetStageProfile().name), "handoff bnet stage default");
        check("p11-wow".equals(profiles.getWowStageProfile().name), "handoff wow stage");
        check("p10-battle".equals(profiles.getSelectedProfile(new Context()).name),
                "handoff selection falls back to the bnet stage profile");
        check(profiles.getBnetStageProfile().files.isEmpty(), "backward compat: profile without files parses empty");
        check(profiles.getBnetStageProfile().layer == null, "backward compat: profile without layer parses null");

        WeHavePhonesConfig.selectedProfile = "p11-battle";
        check("p11-battle".equals(profiles.getSelectedProfile(new Context()).name),
                "handoff selection overrides the bnet stage profile");
        check("p11-wow".equals(profiles.getWowStageProfile().name),
                "handoff wow stage ignores the selection");
    }

    // ----------------------------------------------------------------- helpers

    private static WeHavePhonesRuntimeProfiles load(File json) throws Exception {
        Field cached = WeHavePhonesRuntimeProfiles.class.getDeclaredField("cached");
        cached.setAccessible(true);
        cached.set(null, null);
        FileUtils.source = json;
        WeHavePhonesRuntimeProfiles profiles = WeHavePhonesRuntimeProfiles.load(new Context());
        check(profiles != null, "profile JSON parsed: " + json.getName());
        return profiles;
    }

    private static void check(boolean condition, String label) {
        if (condition) {
            passed++;
            System.out.println("  PASS " + label);
        }
        else {
            failed++;
            System.out.println("  FAIL " + label);
        }
    }
}
