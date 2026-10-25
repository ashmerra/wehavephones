import com.wehavephones.mobile.MemoryReport;
import com.wehavephones.mobile.MemoryTransforms;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host self-test for the WeHavePhones memory pure logic. Compiled and run by
 * scripts/test-wehavephones-memory.sh with javac/java only (no Android, no deps).
 */
public final class MemoryLogicSelfTest {
    private static int passed;
    private static final ArrayList<String> failures = new ArrayList<>();

    private static void check(String name, boolean condition, String detail) {
        if (condition) {
            passed++;
            System.out.println("  PASS " + name);
        }
        else {
            failures.add(name + (detail == null || detail.isEmpty() ? "" : " :: " + detail));
            System.out.println("  FAIL " + name + (detail == null || detail.isEmpty() ? "" : " :: " + detail));
        }
    }

    public static void main(String[] args) {
        testEnvComposition();
        testConfigWtf();
        testScreenSize();
        testReport();

        System.out.println("memory self-test: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) System.out.println("  FAILED " + failure);
        System.out.println("RESULT: " + (failures.isEmpty() ? "PASS" : "FAIL"));
        if (!failures.isEmpty()) System.exit(1);
    }

    private static void testEnvComposition() {
        String base = "WRAPPER_MAX_IMAGE_COUNT=0 ZINK_DESCRIPTORS=lazy MESA_SHADER_CACHE_MAX_SIZE=512MB "
                + "DXVK_HUD=devinfo,fps,frametimes A=1";
        String env = MemoryTransforms.composeEnv(base);
        check("env_no_hud", !env.contains("DXVK_HUD="), env);
        check("env_wrapper", env.contains("WRAPPER_MAX_IMAGE_COUNT=2"), env);
        check("env_mesa", env.contains("MESA_SHADER_CACHE_MAX_SIZE=256MB"), env);
        check("env_keeps_other", env.contains("A=1") && env.contains("ZINK_DESCRIPTORS=lazy"), env);
        check("env_stable", env.equals(MemoryTransforms.composeEnv(env)), env);

        LinkedHashMap<String, String> parsed = MemoryTransforms.parseEnv(env);
        check("env_parse", "1".equals(parsed.get("A")) && "2".equals(parsed.get("WRAPPER_MAX_IMAGE_COUNT")), parsed.toString());
        check("env_parse_ignores_bad", MemoryTransforms.parseEnv("BAD noequals A=1").size() == 1, "");

        String withDxvkConf = "DXVK_CONFIG_FILE=C:/profile-dxvk.conf DXVK_HUD=x A=1";
        String profileDxvk = MemoryTransforms.composeEnv(withDxvkConf);
        check("env_keeps_profile_dxvk_conf",
                profileDxvk.contains("DXVK_CONFIG_FILE=C:/profile-dxvk.conf") && !profileDxvk.contains("DXVK_HUD"),
                profileDxvk);

        String oldLowMode = "MESA_SHADER_CACHE_MAX_SIZE=128MB DXVK_CONFIG_FILE=C:/wehavephones-dxvk.conf A=1";
        String migrated = MemoryTransforms.composeEnv(oldLowMode);
        check("env_drops_old_low_dxvk_conf", !migrated.contains("DXVK_CONFIG_FILE"), migrated);
        check("env_old_low_mesa_replaced", migrated.contains("MESA_SHADER_CACHE_MAX_SIZE=256MB")
                && !migrated.contains("128MB"), migrated);
        check("env_old_low_stable", migrated.equals(MemoryTransforms.composeEnv(migrated)), migrated);

        check("env_dxvk_chunk", env.contains("DXVK_CONFIG=dxvk.maxChunkSize=32")
                && MemoryTransforms.hasDxvkChunkSize(env), env);
        String userDxvk = MemoryTransforms.composeEnv("DXVK_CONFIG=dxgi.syncInterval=1;dxvk.maxChunkSize=256 A=1");
        check("env_dxvk_keeps_user_options",
                userDxvk.contains("DXVK_CONFIG=dxgi.syncInterval=1;dxvk.maxChunkSize=32") && !userDxvk.contains("maxChunkSize=256"), userDxvk);
        check("env_dxvk_stable", userDxvk.equals(MemoryTransforms.composeEnv(userDxvk)), userDxvk);
        check("env_dxvk_missing_detected", !MemoryTransforms.hasDxvkChunkSize("DXVK_CONFIG=dxvk.maxChunkSize=320 A=1"), "");
        check("dxvk_option_empty_entries", "dxvk.maxChunkSize=32".equals(
                MemoryTransforms.withDxvkOption(";;", "dxvk.maxChunkSize", "32")), "");
    }

    private static void testConfigWtf() {
        String original = "SET locale \"enUS\"\r\nSET GxWindowedResolution \"1920x1080\"\r\nSET RenderScale \"0.5\"\r\n"
                + "SET GxMaximize \"0\"\r\n"
                + "SET sound_NumSoundChannels \"64\"\r\n";
        LinkedHashMap<String, String> settings = MemoryTransforms.resolutionCvars("1024x576");
        String edited = MemoryTransforms.editConfigWtf(original, settings);
        check("wtf_crlf_preserved", edited.contains("\r\n") && !edited.contains("\n\r"), "");
        check("wtf_unrelated_first", edited.startsWith("SET locale \"enUS\"\r\n"), edited);
        check("wtf_unrelated_kept", edited.contains("SET sound_NumSoundChannels \"64\"")
                && edited.contains("SET RenderScale \"0.5\""), edited);
        check("wtf_replaced_value", !edited.contains("1920x1080")
                && edited.contains("SET GxWindowedResolution \"1024x576\""), edited);
        check("wtf_maximized", edited.contains("SET GxMaximize \"1\"") && !edited.contains("GxMaximize \"0\""), edited);
        check("wtf_contains_all", MemoryTransforms.configWtfContains(edited, settings), edited);
        check("wtf_idempotent", edited.equals(MemoryTransforms.editConfigWtf(edited, settings)), "");
        check("wtf_parse", "1024x576".equals(MemoryTransforms.parseWtfSettings(edited).get("gxwindowedresolution")), "");

        String lf = "SET locale \"enUS\"\n";
        String lfEdited = MemoryTransforms.editConfigWtf(lf, settings);
        check("wtf_lf_preserved", lfEdited.contains("\n") && !lfEdited.contains("\r"), "");
        check("wtf_empty_file", MemoryTransforms.editConfigWtf("", settings).contains("SET GxWindowedResolution \"1024x576\""), "");
        check("wtf_contains_detects_missing",
                !MemoryTransforms.configWtfContains(lfEdited, MemoryTransforms.resolutionCvars("1280x720")), "");
    }

    private static void testScreenSize() {
        check("screen_default", "1024x576".equals(MemoryTransforms.DEFAULT_SCREEN_SIZE), MemoryTransforms.DEFAULT_SCREEN_SIZE);
        check("screen_default_listed", MemoryTransforms.SCREEN_SIZES[0].equals(MemoryTransforms.DEFAULT_SCREEN_SIZE), "");
        check("screen_unset", "1024x576".equals(MemoryTransforms.screenSizeOrDefault(null)), "");
        check("screen_kept", "1920x1080".equals(MemoryTransforms.screenSizeOrDefault("1920x1080")), "");
        check("screen_malformed", "1024x576".equals(MemoryTransforms.screenSizeOrDefault("big")), "");
        check("screen_zero", "1024x576".equals(MemoryTransforms.screenSizeOrDefault("0x0")), "");
        check("screen_native_landscape", "2340x1080".equals(MemoryTransforms.nativeScreenSize(1080, 2340)), "");
        check("screen_native_unknown", MemoryTransforms.nativeScreenSize(0, 0) == null, "");
        String[] options = MemoryTransforms.screenSizeOptions("2340x1080");
        check("screen_options_native_last", options.length == MemoryTransforms.SCREEN_SIZES.length
                + MemoryTransforms.fittedScreenSizes("2340x1080").length + 1
                && "2340x1080".equals(options[options.length - 1]), String.join(",", options));
        check("screen_options_native_listed",
                MemoryTransforms.screenSizeOptions("1920x1080").length == MemoryTransforms.SCREEN_SIZES.length, "");
        check("screen_options_no_native",
                MemoryTransforms.screenSizeOptions(null).length == MemoryTransforms.SCREEN_SIZES.length, "");

        String wide = String.join(",", MemoryTransforms.fittedScreenSizes("2772x1272"));
        check("screen_fitted_wide", "1256x576,1570x720,1962x900".equals(wide), wide);
        String squarish = String.join(",", MemoryTransforms.fittedScreenSizes("2184x1968"));
        check("screen_fitted_squarish", "640x576,800x720,998x900".equals(squarish), squarish);
        String small = String.join(",", MemoryTransforms.fittedScreenSizes("1600x720"));
        check("screen_fitted_below_native", "1280x576".equals(small), small);
        check("screen_fitted_16_9", MemoryTransforms.fittedScreenSizes("2560x1440").length == 0
                && MemoryTransforms.fittedScreenSizes("2400x1350").length == 0, "");
        check("screen_fitted_unknown", MemoryTransforms.fittedScreenSizes(null).length == 0
                && MemoryTransforms.fittedScreenSizes("big").length == 0, "");
        for (String size : MemoryTransforms.fittedScreenSizes("2340x1080")) {
            check("screen_fitted_valid_" + size, size.equals(MemoryTransforms.screenSizeOrDefault(size)), size);
        }
        String all = String.join(",", MemoryTransforms.screenSizeOptions("2772x1272"));
        check("screen_options_fitted_before_native",
                "1024x576,1280x720,1600x900,1920x1080,1256x576,1570x720,1962x900,2772x1272".equals(all), all);
    }

    private static void testReport() {
        MemoryReport.Sample sample = new MemoryReport.Sample();
        sample.timeMillis = 123456L;
        sample.memAvailableKb = 700000L;
        sample.swapFreeKb = 3300000L;
        sample.psiSomeAvg10 = 1.5;
        sample.psiFullAvg10 = 0.25;
        sample.processes.add(new MemoryReport.ProcessRss(200, "Wow-ARM64.exe", 902300L));
        sample.processes.add(new MemoryReport.ProcessRss(300, "Battle.net \"Helper\" \\ CEF", 641300L));

        String line = MemoryReport.sampleLine(sample);
        check("report_roundtrip_parse", MemoryReport.parse(line) != null, line);
        Map<?, ?> parsed = (Map<?, ?>)MemoryReport.parse(line);
        check("report_type", "sample".equals(parsed.get("type")), String.valueOf(parsed.get("type")));
        check("report_mem", ((Number)parsed.get("memAvailableKb")).longValue() == 700000L, String.valueOf(parsed.get("memAvailableKb")));
        check("report_psi", Math.abs(((Number)parsed.get("psiFullAvg10")).doubleValue() - 0.25) < 1e-9, "");
        List<?> processes = (List<?>)parsed.get("processes");
        check("report_processes", processes.size() == 2
                && "Wow-ARM64.exe".equals(((Map<?, ?>)processes.get(0)).get("name")), String.valueOf(processes));
        check("report_escape", "Battle.net \"Helper\" \\ CEF".equals(((Map<?, ?>)processes.get(1)).get("name")), String.valueOf(processes.get(1)));

        Map<?, ?> header = (Map<?, ?>)MemoryReport.parse(
                MemoryReport.headerLine("low", "env A=1; env B=2", 1L));
        check("report_header", MemoryReport.KIND.equals(header.get("kind"))
                && "low".equals(header.get("mode"))
                && Integer.valueOf(1).equals(((Number)header.get("version")).intValue()), String.valueOf(header));
        check("report_bad_json", MemoryReport.parse("{not json") == null, "");
        check("report_trailing_garbage", MemoryReport.parse(line + " extra") == null, "");
        check("report_null", MemoryReport.parse(null) == null, "");
        check("report_nan", MemoryReport.parse(MemoryReport.sampleLine(new MemoryReport.Sample())) != null, "");
    }
}
